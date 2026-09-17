# AGENTS.md

本文件是给在本仓库工作的**AI 智能体与人类协作者**的约定手册。目标是让任何人在改动代码前，先知道哪些地方是"碰了会坏"的。

先读本文，再读 `dSIM 分布式多设备短信同步系统 技术架构说明书 V2.0.md`（架构与协议全貌）。

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
| 单测（目前只有模板用例） | `./gradlew :app:testDebugUnitTest` |

注意：

- compileSdk 36 但 AGP 为 8.7.3，构建会输出"AGP 仅验证到 compileSdk 35"警告。这是**已知且可接受**的，不要为此改 compileSdk。
- `gradle/libs.versions.toml` 是模板残留、未生效，且其 `agp = "9.1.0"` 与根构建文件不一致。**不要以该文件为准**，实际版本看根 `build.gradle.kts`。

---

## 3. 分层与数据流

```
入口   SmsListActivity(LAUNCHER) / OnboardingActivity / SettingsActivity / MainActivity(调试)
界面   SmsChatActivity / OtpConversationActivity / DeviceManagerActivity / SimBindingActivity
能力   SmsSourceResolver / PrivacyModeManager / OtpRulesStore / UsageModeManager
       SenderColorUtils / ConversationProfileStore / DeviceDirectoryManager
同步   MqttSyncService(前台服务·协议中枢) / HistorySyncQueueManager / DsimCryptoUtils
采集   SmsReceiver(+SmsReceivedReceiver) / SystemSmsHistoryImporter / HardwareProbeUtils
数据   Room DsimDatabase v5 (4 实体) + 9 组 SharedPreferences
```

**两条主链路（务必记住真实走向）：**

- 入站短信：`SmsReceiver` → 标准化 → 解析归属卡 → 写 Room → 回写系统库 → 发通知 → **直接取 `MqttSyncService.globalMqttClient` 发布**。不经过 `MqttSyncService` 的方法中转。
- 出站短信：`SmsChatActivity` 预生成 uuid 并落库 status=0 → 发 `SEND_CMD` → 执行端 `handleSendCommand` 实际发短信 → 回 `SEND_CMD_RESULT` → 发起端更新 status。

---

## 4. 硬性约束（改错会坏）

| # | 约束 | 原因 |
|---|---|---|
| C1 | 改 `@Entity` 字段必须同时递增 `@Database(version)` 并补一条 `Migration` | `exportSchema = false`，没有 schema diff 会提醒你。漏了会直接崩在老用户的升级路径上 |
| C2 | `sms_messages.uuid` 的唯一索引不能去掉 | 跨设备幂等全靠它。`MIGRATION_4_5` 专门为此做过数据清洗 |
| C3 | 加密写入必须走 `DsimCryptoUtils.encryptMessage`，不要自己拼装格式 | 统一走 V2（`DSM2` 魔数 + PBKDF2 + AES-GCM）。改格式时必须保留对旧魔数的读取分支并新增魔数，不能原地改语义 |
| C4 | 密钥派生自**口令**，与 MQTT Topic 无关 | 历史上形参名曾叫 `topic`，导致多次误判。V2 已改名 `secret`，别再改回去 |
| C5 | Manifest 里默认短信应用的必须组件不能删 | `SmsReceiver`、`ComposeSmsActivity`、`HeadlessSmsSendService`、`MmsReceiver`。少一个系统就拒绝授予默认短信角色（后三者目前仍是占位实现，见 §6） |
| C6 | 两个短信接收器的动作互斥规则不能破坏 | 默认短信应用只处理 `SMS_DELIVER`，非默认只处理 `SMS_RECEIVED`。破坏它会收到重复短信 |
| C7 | 新增云端上传点前，必须过 `UsageModeManager` 的闸 | `canUploadIncomingSms` / `canReceiveCloudSms` / `canUseCloud`。这是"本地模式"隐私承诺的代码落实 |
| C8 | 所有源文件必须 UTF-8 无 BOM、LF 行尾 | 曾发生过 GBK 被误读为 UTF-8 后写回导致的中文乱码事故（4 处，已在 V2.0 修复） |
| C9 | 新增 MQTT 动作时，需在 `handleIncomingMessage` 中按正确的顺序位置加分支，并对定向动作校验 `targetDeviceId == localDeviceId` | 顺序错会导致消息被上游分支吞掉；缺校验会导致别的设备的回执被误处理 |
| C10 | 发信链路必须以 `uuid` 贯穿，落库前用 `dao.checkUuidExists` 判重 | 否则重发/回声会产生重复会话记录 |

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
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/dsim/
        │   │   ├── database/
        │   │   │   ├── DsimEntities.kt           4 个 @Entity
        │   │   │   ├── DsimDao.kt                40 个 DAO 方法
        │   │   │   └── DsimDatabase.kt           v5 + 4 次 Migration
        │   │   ├── MqttSyncService.kt            ★ 协议中枢（1088 行）
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
        │   │   ├── MainActivity.kt               调试入口（exported=false）
        │   │   ├── DSimHardwareTester.kt         硬件自检
        │   │   ├── SmsDatabaseTester.kt          数据库测试工具
        │   │   ├── ComposeSmsActivity.kt         ✗ 占位
        │   │   ├── HeadlessSmsSendService.kt     ✗ 占位
        │   │   ├── MmsReceiver.kt                ✗ 占位
        │   │   ├── SendCmdPayload.kt             ⚠ 死代码
        │   │   ├── DsimMqttEngine.kt             ⚠ 死代码
        │   │   └── DsimNetworkEngine.kt          ⚠ 死代码
        │   └── res/
        │       ├── layout/          20 个（item_sms.xml 为死资源）
        │       ├── drawable/        29 个（23 bg_* + 6 ic_*）
        │       ├── drawable-nodpi/  6 张预置头像
        │       ├── menu/            1
        │       ├── values/          colors / strings / themes
        │       ├── values-night/    themes（仅重复 Base）
        │       └── xml/             backup_rules / data_extraction_rules（模板）
        ├── test/                     模板用例
        └── androidTest/              模板用例
```

---

## 6. 当前待办（按优先级）

1. **补齐默认短信应用的三个必备组件**（`ComposeSmsActivity` 目前直接 `finish()`、`HeadlessSmsSendService` 只有 `onBind`、`MmsReceiver` 空实现）。这是功能可用性的硬缺口——系统会因为这三个组件不合格而拒绝授予默认短信角色。
2. **补测试**：加密 V1/V2 交叉兼容、历史队列状态迁移、隐私模式号码变体匹配、Room v1→v5 逐级迁移。这四块都是纯逻辑，**不依赖模拟器 modem**，用 `androidTest`/`test` 即可，是投入产出比最高的方向。
3. **`mappingKey` 在 Root 模式下不含设备维度**：`mappingKey = ICCID_<iccid>`，而它又是 `sim_card_configs` 的主键。真实卡 ICCID 唯一所以平时不暴露，但模拟器各实例共用同一 ICCID 时两张卡会碰撞，导致对端卡的 `REMOTE_SHADOW` 不被创建、跨设备发信无法触发。**已在多设备测试中实测到，绕法（让其中一台切无 Root 模式）也已实测跑通，见 `TESTING.md` §5.1。** 建议在键里加入设备维度或补唯一性兜底。
4. **`deviceId` 直接取自 `ANDROID_ID`**：Android 8+ 该值按应用签名作用域隔离，**debug 与 release 构建切换会让应用把自己当成新设备**，历史 `sms_message.deviceId` 与 `DeviceProfile.isLocalDevice` 判定会漂移。建议改为应用自管持久 UUID。
5. **`isMockNoRootMode` 不持久化**：它是 `HardwareProbeUtils` 里 `object` 的普通 `var`，进程重启即失效，多设备测试时每次都要重新切换。
6. **口令存储加固**：`dSIM_UI_PREFS.PASSWORD` 目前明文。迁移到 `EncryptedSharedPreferences`。
7. **发布工程化**：无签名配置、`isMinifyEnabled = false`、版本号未迭代。
8. **清理死代码**：`DsimMqttEngine.kt`、`DsimNetworkEngine.kt`、`SendCmdPayload.kt`、`res/layout/item_sms.xml`、`gradle/libs.versions.toml`。
9. **文案与配色去硬编码**：中文字符串应进 `strings.xml`（当前 `R.string.*` 使用次数为 0），界面色值应进 `colors.xml`。
10. **自身回声日志级别**：客户端订阅的 topic 与发布 topic 相同且未用 MQTT no-local，自己发的 PING/PONG 会回环并落到「无 sms 载荷」分支打 WARNING，每 20 秒一条。行为正确但日志误导，建议提前静默返回。

---

## 7. 常见改动指引

| 要做的事 | 需要动的地方 |
|---|---|
| 给短信加字段 | `DsimEntities.kt` → 递增 `DsimDatabase` 版本 + 新增 `Migration` → 检查所有 `SmsMessage(...)` 构造点与 `SystemSmsHistoryImporter` 的字段映射 |
| 加一种云端消息 | `MqttSyncService` 加 `MQTT_ACTION_*` 常量 → `handleIncomingMessage` 按顺序插分支 → 对应 `handle*` 函数 → 若要回执，补 `targetDeviceId` 校验 |
| 加一个设置项 | `SettingsActivity`（含 `insertUsageModeSection` 附近）→ 若需持久化，优先落在既有 prefs 文件而非新建 → 同步更新架构文档 §5 与 §12.2 |
| 改通知文案 | `MqttSyncService.buildXxxMessage()` 与 `NotificationUtils` |
| 加号码脱敏场景 | 调用 `PrivacyModeManager` 的显示包装方法，**不要**自己在界面里写打码逻辑 |
| 改使用模式语义 | `UsageModeManager` 的 `canUploadIncomingSms` / `canReceiveCloudSms` / `canUseCloud` 三处分支，以及全部 7 个拦截点 |

---

## 8. 调试与测试入口

### 8.1 应用内调试面板

`MainActivity`（label「测试功能」，`exported=false`，只能从「设置 → 测试功能」进入）提供：硬件探测、注入模拟短信、全量历史导入、双库只读测试、SIM 管理、Root 探测/切换、清空私有库、通知测试、设备雷达。

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
