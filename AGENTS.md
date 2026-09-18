# AGENTS.md

本文件是给在本仓库工作的**AI 智能体与人类协作者**的约定手册。目标是让任何人在改动代码前，先知道哪些地方是"碰了会坏"的。

先读本文，再按需读：
- `FIXES_2026-09-18.md` —— 最新正确性修复与未完成边界（冲突时以此为准）
- `dSIM 分布式多设备短信同步系统 技术架构说明书 V2.0.md` —— 架构与协议全貌
- `TESTING.md` —— 测试环境与回归验证
- `REFACTORING.md` —— 代码质量优化工作单（要做重构/优化先读它）

---

## 1. 项目是什么

dSIM 是一款 Android 应用：多台设备填入同一组 `MQTT Broker + Topic + 口令` 后组成私有同步组，实现短信跨设备同步、跨设备远程发信、设备状态互见。

- 单模块 Gradle 工程，`rootProject.name = "dSIM"`，模块 `:app`
- 包名 `com.example.dsim`，源码根 `app/src/main/java/com/example/dsim/`
- 55 个 Kotlin 文件 / 约 14,300 行

---

## 2. 构建与验证

Gradle 守护进程固定要求 **JDK 21**（`gradle/gradle-daemon-jvm.properties`）。若未把 JDK 21 暴露给 Gradle，构建会直接失败并报 `Cannot find a Java installation ... Compatible with Java 21`。可用命令：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot"
./gradlew :app:compileDebugKotlin \
  -Dorg.gradle.java.installations.paths="C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"
```

| 目的 | 命令 |
|---|---|
| 最快的类型/语法校验（改完必跑） | `./gradlew :app:compileDebugKotlin` |
| 产出 debug 包 | `./gradlew :app:assembleDebug` |
| 单测（含发送状态与配置恢复用例） | `./gradlew :app:testDebugUnitTest` |

注意：

- compileSdk 36 但 AGP 为 8.7.3，构建会输出"AGP 仅验证到 compileSdk 35"警告。这是**已知且可接受**的，不要为此改 compileSdk。
- `gradle/libs.versions.toml` 是模板残留、未生效，且其 `agp = "9.1.0"` 与根构建文件不一致。**不要以该文件为准**，实际版本看根 `build.gradle.kts`。
- `app/build.gradle.kts` 显式打开了 `buildFeatures { buildConfig = true }`。AGP 8 默认不生成 `BuildConfig`，关掉它会让 `SettingsActivity` 的 `BuildConfig.DEBUG` 直接编译失败（W15 依赖它）。
- `:app:assembleRelease` 会跑 `lintVital*`，该任务需要联网拉 lint 依赖；在网络受限的环境里可能长时间挂死。确认过依赖已缓存时可用 `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` 跳过，但这不代表 lint 检查通过。

---

## 3. 分层与数据流

```
入口   SmsListActivity(LAUNCHER) / OnboardingActivity / SettingsActivity / MainActivity(调试)
界面   SmsChatActivity / OtpConversationActivity / DeviceManagerActivity / SimBindingActivity
能力   SmsSourceResolver / PrivacyModeManager / OtpRulesStore / UsageModeManager
       SenderColorUtils / ConversationProfileStore / DeviceDirectoryManager
同步   MqttSyncService(前台服务·协议中枢) / HistorySyncQueueManager / DsimCryptoUtils
       CloudTopics(topic 布局) / HeartbeatPolicy(快照发布决策) / SyncOutbox(发件箱)
采集   SmsReceiver(+SmsReceivedReceiver) / SystemSmsHistoryImporter / HardwareProbeUtils
数据   Room DsimDatabase v7 (6 实体：send_commands 执行账本、sync_outbox 同步发件箱) + 9 组 SharedPreferences
```

**两条主链路（务必记住真实走向）：**

- 入站短信：`SmsReceiver` → 标准化 → 解析归属卡 → **`SyncOutbox.storeIncomingSms` 在同一事务里写 `sms_messages` + `sync_outbox`** → 回写系统库 → 发通知 → `startForegroundService(ACTION_FLUSH_OUTBOX)`。Receiver **不再触碰 MQTT 客户端**；真正的发布由 `MqttSyncService.flushOutbox()` 在连接建立 / 重连 / 心跳 / 显式冲刷时通过 `SyncOutbox.flush` 完成，QoS1 PUBACK 后才删行。断网时短信落库但留在发件箱，通知栏显示「N 条短信待同步」。
- 出站短信：`SmsChatActivity` 预生成 uuid 并落库 status=0 → 发 `SEND_CMD` → 执行端 `OutgoingSmsDispatcher` 原子认领 UUID → SmsManager → `SmsSentResultReceiver` 汇总真实发送回调 → 回 `SEND_CMD_RESULT` → 发起端更新 status。认领后回调缺失不可自动重发。

---

## 4. 硬性约束（改错会坏）

| # | 约束 | 原因 |
|---|---|---|
| C1 | 改 `@Entity` 字段必须同时递增 `@Database(version)` 并补一条 `Migration` | `exportSchema = false`，没有 schema diff 会提醒你。漏了会直接崩在老用户的升级路径上 |
| C2 | `sms_messages.uuid` 的唯一索引不能去掉 | 跨设备记录去重依赖它；发送副作用另外依赖 send_commands 的原子认领。`MIGRATION_4_5` 专门为此做过数据清洗 |
| C3 | 加密写入必须走 `DsimCryptoUtils.encryptMessage`，不要自己拼装格式 | 统一走 V3（`DSM3` 魔数 + 每口令一次 PBKDF2 派生主密钥 + 每条随机 IV 的 AES-256-GCM，AAD=魔数）。改格式时必须新增魔数；仅存在已发布旧设备时保留读取分支，V1/V2 读取路径均已移除，不能原地改语义。派生固定盐 `dSIM/v3/master-key`、12 万轮，改任一项即等于改格式 |
| C4 | 密钥派生自**口令**，与 MQTT Topic 无关 | 历史上形参名曾叫 `topic`，导致多次误判。V2 已改名 `secret`，别再改回去 |
| C5 | Manifest 里默认短信应用的必须组件不能删 | `SmsReceiver`、`ComposeSmsActivity`、`HeadlessSmsSendService`、`MmsReceiver`。少一个系统就拒绝授予默认短信角色（后三者目前仍是占位实现，见 §6） |
| C6 | 两个短信接收器的动作互斥规则不能破坏 | 默认短信应用只处理 `SMS_DELIVER`，非默认只处理 `SMS_RECEIVED`。破坏它会收到重复短信 |
| C15 | 两个短信接收器都必须带 `android:permission="android.permission.BROADCAST_SMS"` | 少了它，任何应用都能伪造 PDU 广播，被落库、回写系统库并同步给全组设备。系统（`com.android.phone`）持有该权限，加了不影响真实投递——改动后务必用真实入站短信回归一次，不要用 `adb am broadcast` 判定（它一直被 protected broadcast 规则拒绝） |
| C7 | 新增云端上传点前，必须过 `UsageModeManager` 的闸 | `canUploadIncomingSms` / `canReceiveCloudSms` / `canUseCloud`。这是"本地模式"隐私承诺的代码落实 |
| C8 | 所有源文件必须 UTF-8 无 BOM、LF 行尾 | 曾发生过 GBK 被误读为 UTF-8 后写回导致的中文乱码事故（4 处，已在 V2.0 修复） |
| C9 | 新增 MQTT 动作时，需在 `handleIncomingMessage` 中按正确的顺序位置加分支，并对定向动作校验 `targetDeviceId == localDeviceId` | 顺序错会导致消息被上游分支吞掉；缺校验会导致别的设备的回执被误处理 |
| C10 | 发信链路必须以 `uuid` 贯穿，落库前用 `dao.checkUuidExists` 判重 | 否则重发/回声会产生重复会话记录；实际发信前还必须原子认领 send_commands，不可用 status=0 短信行代替执行记录 |
| C11 | 新增"必须送达对端"的云端发布点时，走 `SyncOutbox`（先落 `sync_outbox` 再由服务冲刷），不要用 `MqttSyncService.publishToGroup` 直发（W7 起 `globalMqttClient` 已私有，服务外只能经该入口发布） | 直接发布在断连时静默丢失。心跳 / PING / PONG 这类可丢的状态消息例外，可以直发。控制消息用 `SyncOutbox.enqueueControl(kind, uuid, discriminator, json, group)`，discriminator 区分同一 UUID 的不同结论（W21） |
| C12 | MQTT 必须保持 clientId = `dSIM_<deviceId>`、`cleanSession=false`、文件持久化 | Broker 端为离线设备排队 QoS1 消息依赖持久会话；改成随机 clientId 或 cleanSession=true 会让对端离线期间的短信全部丢失 |
| C13 | 发布只能发到 `CloudTopics.publishTopic(base, 本机 deviceId)`（即 `<base>/<deviceId>`），订阅只能订 `<base>/+`；不要往 `base` 本身发布 | 自身回声靠 topic 后缀在解密前丢弃（`messageArrived` 第一行）。发到 `base` 的报文所有人都要解密一次才能识别，且发送者身份无法从 topic 得到 |
| C16 | 备份规则必须 deny-by-default：`backup_rules.xml` 与 `data_extraction_rules.xml` 排除**所有** domain，不要改成逐文件点名 | 备份规则是 allow-by-default 语义，没被 `<exclude>` 点名的一切都会进 Google Drive 备份与换机迁移。逐文件清单在下次改存储名时会静默失效，把明文口令和整个消息库漏出去。`<device-transfer>` 不受 `allowBackup` 约束，必须单独写 |
| C17 | 房间号（base topic）在保存入口必须过 `CloudSettingsManager.validateBaseTopic`，云端凭据一律经 `CloudSettingsManager` 读写 | base topic 含 `+`/`#` 会让 C13 的 `<base>/<deviceId>` 变成非法发布目标、`<base>/+` 变成过宽订阅。直接 `getSharedPreferences("dSIM_UI_PREFS")` 读 BROKER/TOPIC/PASSWORD 会绕过校验与默认值，也挡死后续迁移到加密存储 |
| C18 | MQTT 载荷只经 `MqttProtocol.kt` 的数据类 + `MqttPayloadCodec` 编解码；不得再手工拼 `JSONObject` 或读 `optString("action")`。字段名即线格式，改名 = 改协议，须与所有设备同步升级 | 字段名散落各处时拼错没有编译期检查；`decode()` 对未知 action / 缺失 `sms` 返回 `null` 而不是抛异常，新增消息类型必须同时加 `sealed` 子类、`decode` 分支与 `senderId` 分支（`when` 穷举会在编译期提醒） |
| C19 | `MqttSyncService` 的前台服务类型是 `remoteMessaging`（清单 + `startForeground(id, n, FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)` 二者必须一致），不可改回 `dataSync`；`startForeground` 只经 `promoteToForeground()`，被系统拒绝时 `stopSelf` 而不是让异常杀进程 | Android 15+（targetSdk 35+）禁止 `BOOT_COMPLETED` 拉起 `dataSync` 前台服务，改回去 = 开机自启崩溃、守护进程直到用户开 app 才起来；`dataSync` 另有 6 h/24 h 时长预算，常驻守护会被 `onTimeout` 掐掉。`SystemHistoryImportService` 是用户发起、有界的导入，保持 `dataSync` |
| C14 | 设备快照（PONG）只经 `publishDeviceSnapshot(force)` 发布，心跳路径必须 `force=false` | `HeartbeatPolicy` 按指纹变化 / 120 秒静默上限决定是否发；绕过它会把心跳退回到每 20 秒一条。`ONLINE_TIMEOUT_MS`（5 分钟）必须大于 `MAX_SILENCE_MS` |

---

## 5. 文件树

```
dSIM/
├── build.gradle.kts                    AGP 8.7.3 / Kotlin 2.1.10 / KSP（版本以此为准）
├── settings.gradle.kts                 rootProject.name = "dSIM"，单模块 :app
├── gradle/gradle-daemon-jvm.properties 固定 toolchainVersion=21
├── gradle/libs.versions.toml           ⚠ 模板残留，未生效，勿参考
├── local.properties                    不入库
├── .gitignore                          已排除 ui_prototypes/ tmp_db/ tmp_previews/ .workbuddy/
├── ui_prototypes/                      竞品 UI 参考素材（已 gitignore）
├── AGENTS.md                           本文件
├── dSIM 分布式多设备短信同步系统 技术架构说明书 V2.0.md
└── app/
    ├── build.gradle.kts                minSdk 24 / targetSdk 36 / JVM 17
    ├── proguard-rules.pro              空模板
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml         （不含 MainActivity，见 src/debug）
        │   ├── java/com/example/dsim/
        │   │   ├── database/
        │   │   │   ├── DsimEntities.kt           5 个 @Entity
        │   │   │   ├── DsimDao.kt                40 个 DAO 方法
        │   │   │   └── DsimDatabase.kt           v7 + 6 次 Migration
        │   │   ├── MqttSyncService.kt            ★ 前台服务：连接生命周期 / 心跳循环 / 通知 / 冲刷发件箱（W4 后 568 行）
        │   │   ├── CloudSession.kt               当前组凭据（服务写，Publisher/Inbound 读）
        │   │   ├── MqttPublisher.kt              全部出站控制消息 + 心跳指纹（C13/C14 实现点）
        │   │   ├── MqttInboundHandler.kt         解密 → MqttPayloadCodec.decode → when(inbound) 分发
        │   │   ├── SyncOutbox.kt                 ★ 入站同步发件箱：事务入队 + 单飞冲刷
        │   │   ├── OutgoingSmsDispatcher.kt     原子认领及系统发送
        │   │   ├── SmsSentResultReceiver.kt     系统发送回调
        │   │   ├── SendCommandPolicy.kt         分段状态纯逻辑
        │   │   ├── DsimCryptoUtils.kt            ★ 加解密 V2（AEAD）
        │   │   ├── HistorySyncQueueManager.kt    历史同步队列状态机
        │   │   ├── HistoryQueueNotificationHelper.kt
        │   │   ├── SmsReceiver.kt                ★ 入站采集
        │   │   ├── SmsReceivedReceiver.kt        SmsReceiver 空壳子类
        │   │   ├── SmsSourceResolver.kt          入站归属卡判定
        │   │   ├── SmsSourceRepairManager.kt     借卡场景修复
        │   │   ├── SystemSmsHistoryImporter.kt   系统历史导入
        │   │   ├── SystemHistoryImportService.kt 导入前台服务
        │   │   ├── SystemSmsStore.kt             系统短信库读写
        │   │   ├── HardwareProbeUtils.kt         ★ 映射键与订阅号解析
        │   │   ├── SimConfigIdentityManager.kt
        │   │   ├── UsageModeManager.kt           ★ 隐私闸门
        │   │   ├── PrivacyModeManager.kt         号码脱敏
        │   │   ├── OtpRulesStore.kt              验证码规则
        │   │   ├── OtpConversationUtils.kt       验证码提取
        │   │   ├── OtpConversationActivity.kt    验证码列表
        │   │   ├── OtpCopyReceiver.kt            复制验证码广播
        │   │   ├── OtpCopyPreferenceStore.kt
        │   │   ├── OnboardingActivity.kt         6 步引导（含反诈告知）
        │   │   ├── OnboardingStateStore.kt
        │   │   ├── SetupChecklistManager.kt      配置完备度
        │   │   ├── CorePermissionHelper.kt       权限清单与缺失检测
        │   │   ├── DefaultSmsManager.kt          默认短信角色
        │   │   ├── BootReceiver.kt               开机自启
        │   │   ├── DeviceDirectoryManager.kt     设备档案
        │   │   ├── DeviceNameManager.kt
        │   │   ├── NotificationUtils.kt          通知渠道与构建
        │   │   ├── SenderColorUtils.kt           配色派生
        │   │   ├── SenderColorPreferenceStore.kt 8 色预设
        │   │   ├── ConversationProfileStore.kt   备注/头像/置顶
        │   │   ├── ConversationSenderStore.kt
        │   │   ├── SmsTagParserUtils.kt          来源标签渲染
        │   │   ├── GlobalNumberUtils.kt          E.164 标准化
        │   │   ├── CloudSettingsManager.kt       云端配置读写
        │   │   ├── DsimNavigation.kt
        │   │   ├── SyncPayload.kt                同步载荷
        │   │   ├── SmsListActivity.kt            LAUNCHER 主入口
        │   │   ├── SmsChatActivity.kt            会话详情
        │   │   ├── SettingsActivity.kt           设置中心
        │   │   ├── DeviceManagerActivity.kt      设备中心
        │   │   ├── SimBindingActivity.kt         SIM 绑定
        │   │   ├── MainActivity.kt               调试入口（仅 debug 变体声明，见 src/debug/AndroidManifest.xml）
        │   │   ├── DSimHardwareTester.kt         硬件自检
        │   │   ├── SmsDatabaseTester.kt          数据库测试工具
        │   │   ├── ComposeSmsActivity.kt         ✗ 占位
        │   │   ├── HeadlessSmsSendService.kt     ✗ 占位
        │   │   ├── MmsReceiver.kt                ✗ 占位
        │   │   └── MqttProtocol.kt               MQTT 线协议数据类 + MqttPayloadCodec（W5）
        │   └── res/
        │       ├── layout/          19 个
        │       ├── drawable/        29 个（23 bg_* + 6 ic_*）
        │       ├── drawable-nodpi/  6 张预置头像
        │       ├── menu/            1
        │       ├── values/          colors / strings / themes
        │       ├── values-night/    themes（仅重复 Base）
        │       └── xml/             backup_rules / data_extraction_rules（模板）
        ├── debug/
        │   └── AndroidManifest.xml   仅 debug 变体：声明 MainActivity 调试面板（W15）
        ├── test/                     模板用例
        └── androidTest/              模板用例
```

---

## 6. 当前待办（按优先级）

1. **补齐默认短信应用的三个必备组件**（`ComposeSmsActivity` 目前直接 `finish()`、`HeadlessSmsSendService` 只有 `onBind`、`MmsReceiver` 空实现）。这是业务功能缺口；不能仅凭方法体占位断言系统必然拒绝默认短信角色，需分别验证角色资格与业务实现。
2. **补测试**：加密往返与篡改拒收、历史队列状态迁移、隐私模式号码变体匹配、Room v1→v6 逐级迁移（已补 v5→v6 定向测试，其余仍待补齐）。这四块都是纯逻辑，**不依赖模拟器 modem**，用 `androidTest`/`test` 即可，是投入产出比最高的方向。（注：V1 旧格式兼容已移除——项目无发布无用户，该路径是死代码；今后改加密格式时，只有当线上真有旧版设备才保留读取分支。）
3. **`mappingKey` 在 Root 模式下不含设备维度**：`mappingKey = ICCID_<iccid>`，而它又是 `sim_card_configs` 的主键。真实卡 ICCID 唯一所以平时不暴露，但**模拟器各实例共用同一 ICCID 时两张卡会碰撞**，导致对端卡的 `REMOTE_SHADOW` 不被创建、跨设备发信无法触发——已在多设备测试中实测到。**测试环境已用 ICC Profile 根治**（每台模拟器指定不同 ICCID，见 `TESTING.md` §5.1 与 `test-fixtures/icc/`）。若希望从应用侧根治，可考虑给键加设备维度或补唯一性兜底，但注意这会破坏「卡换机仍能识别」的既有语义，需要先想清楚。
4. **`deviceId` 直接取自 `ANDROID_ID`**：Android 8+ 该值按应用签名作用域隔离，**debug 与 release 构建切换会让应用把自己当成新设备**，历史 `sms_message.deviceId` 与 `DeviceProfile.isLocalDevice` 判定会漂移。先明确安装级/可恢复身份语义；普通 prefs UUID 不能保证卸载重装或换签名后身份不变。
5. **`isMockNoRootMode` 不持久化**：它是 `HardwareProbeUtils` 里 `object` 的普通 `var`，进程重启即失效，多设备测试时每次都要重新切换。
6. **口令存储加固**：`dSIM_UI_PREFS.PASSWORD` 目前仍是明文，待迁移到 `EncryptedSharedPreferences`。批次 D 已做完两件事收窄风险：备份/迁移规则改为 deny-by-default（口令与 Room 库不再离开设备），且所有云端凭据读写已收口到 `CloudSettingsManager`（迁移时只需改一处）。迁移前要先想清楚密钥丢失（恢复出厂、Keystore 失效）后的降级路径。
7. **发布工程化**：无签名配置、`isMinifyEnabled = false`、版本号未迭代。
8. ~~清理死代码~~ 已完成（批次 E）：`DsimMqttEngine.kt`、`DsimNetworkEngine.kt`、`res/layout/item_sms.xml`、`gradle/libs.versions.toml` 与 `hivemq-mqtt-client` 依赖已删；`DsimDao` 4 个零调用方法已删、`getAllSimConfigsForUi` 并入 `getAllSimConfigs`。`SendCmdPayload.kt` 随后在 W5 中由 `MqttProtocol.kt` 的 `SendCmd` 取代。
9. **文案与配色去硬编码**：中文字符串应进 `strings.xml`（当前 `R.string.*` 使用次数为 0），界面色值应进 `colors.xml`。
10. ~~自身回声日志级别~~ 已解决（批次 B）：发布 topic 带设备后缀，`messageArrived` 按 topic 丢弃自身回声，不再解密也不再打 WARNING。

---

## 7. 常见改动指引

| 要做的事 | 需要动的地方 |
|---|---|
| 给短信加字段 | `DsimEntities.kt` → 递增 `DsimDatabase` 版本 + 新增 `Migration` → 检查所有 `SmsMessage(...)` 构造点与 `SystemSmsHistoryImporter` 的字段映射 → 迁移测试里把假版本号链到最新版（`SendCommandLedgerTest` / `SyncOutboxDaoTest` 的写法） |
| 加一种"必须送达"的云端消息 | 加 `SyncOutbox.KIND_*` → 构造 `SyncOutboxEntry` 入队 → 由现有 `flush` 发送；对端解析仍在 `handleIncomingMessage` |
| 加一种云端消息 | `MqttSyncService` 加 `MQTT_ACTION_*` 常量 → `handleIncomingMessage` 按顺序插分支 → 对应 `handle*` 函数 → 若要回执，补 `targetDeviceId` 校验。发布用 `CloudTopics.publishTopic(...)`（C13） |
| 让 PONG 多带一个字段 | 改 `publishDeviceSnapshot` 的 JSON → 若对端会渲染它，同时把它加进 `HeartbeatPolicy.fingerprint(...)`，否则变化不会触发发布 |
| 加一个设置项 | `SettingsActivity`（含 `insertUsageModeSection` 附近）→ 若需持久化，优先落在既有 prefs 文件而非新建 → 同步更新架构文档 §5 与 §12.2 |
| 改通知文案 | `MqttSyncService.buildXxxMessage()` 与 `NotificationUtils` |
| 加号码脱敏场景 | 调用 `PrivacyModeManager` 的显示包装方法，**不要**自己在界面里写打码逻辑 |
| 改使用模式语义 | `UsageModeManager` 的 `canUploadIncomingSms` / `canReceiveCloudSms` / `canUseCloud` 三处分支，以及全部 7 个拦截点 |

---

## 8. 调试与测试入口

### 8.1 应用内调试面板

`MainActivity`（label「测试功能」，`exported=false`，只能从「设置 → 测试功能」进入）提供：硬件探测、注入模拟短信、全量历史导入、双库只读测试、SIM 管理、Root 探测/切换、清空私有库、通知测试、设备雷达。

**它只存在于 debug 变体**：`<activity>` 声明在 `app/src/debug/AndroidManifest.xml`，release 清单里没有这个组件；`SettingsActivity` 的入口按钮同样用 `BuildConfig.DEBUG` 包裹（非 debug 下 `GONE`）。类本身仍参与 release 编译，所以改 `MainActivity` 不会只在 debug 下编译失败。若要新增调试组件，一并放进 debug 清单，不要加回主清单。

注意：其中的「注入模拟短信」**直接 `dao.insertMessage` 后发 MQTT，绕过了 `SmsReceiver`**，
因此它验证不了采集链路（互斥规则、号码标准化、来源解析、系统库回写都不经过）。要测采集链路请用真注入。

### 8.2 模拟器回归流程（AI 可无人值守）

```bash
bash scripts/emu-up.sh 2                                        # 启动 2 台独立设备
bash scripts/onboard-device.sh emulator-5554 dsim/test/x pw      # 装包+授权+走完引导
bash scripts/onboard-device.sh emulator-5556 dsim/test/x pw      # 同一 topic/password
bash scripts/verify-dsim.sh emulator-5554 emulator-5556          # 回归验证（12 项，退出码即结论）
bash scripts/emu-down.sh                                         # 收工
```

| 脚本 | 作用 |
|---|---|
| `emu-up.sh N` / `emu-down.sh` | 启动 N 台 / 关闭全部并清锁 |
| `uiauto.sh dump\|tap\|tapid\|type\|wait\|shot\|activities\|wake\|clearfield` | adb + uiautomator 的 UI 驱动 |
| `onboard-device.sh <serial> <topic> [password]` | 无人值守完成 6 步引导 + 云端配置 + SIM 绑定 |
| `pull-app-db.sh <serial>` | 拉取应用数据库（自动带上 `-wal`/`-shm`，否则只有 4 KB 空头文件） |
| `verify-dsim.sh <serial> [peer]` | 回归验证：采集链路 / 落库 / 通知 / 前台服务 / 跨设备发现 |

**完整说明、前置自检与已知限制见 `TESTING.md`。** 启动模拟器前务必先读它的 §0.1
（宿主出站 IPv6 必须可用，否则 modem 挂不上、SIM 不激活）。

### 8.3 改完代码后

至少执行一次 `./gradlew :app:compileDebugKotlin`；涉及采集/同步逻辑时再跑一遍 `verify-dsim.sh`。
