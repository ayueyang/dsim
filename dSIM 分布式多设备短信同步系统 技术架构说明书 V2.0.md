> **最新补充（2026-09-18）**：参见 `FIXES_2026-09-18.md`。当前 Room v6 新增 send_commands，发送成功改由系统回调决定，新增 status=-2（结果未知）；本文件中的历史行数和流程图尚未全面重绘。

# dSIM 分布式多设备短信同步系统 技术架构说明书

| 项 | 内容 |
|---|---|
| 文档版本 | **V2.0** |
| 适用代码基线 | `app/src/main/java/com/example/dsim/` 全部 55 个 Kotlin 文件，共 14,306 行 |
| 代码版本 | git `fdaf92d`（`fix: show source details on otp cards`）之上，含工作区未提交改动 |
| 编写日期 | 2026-09-17 |
| 目标读者 | 架构师、后续维护者、安全评审人 |
| 前版文档 | 《dSIM_技术文档_架构师版》（2026-03-26）——**已废弃，被本文档取代** |

---

## 0. 文档说明

### 0.1 本次重写的原因

前版文档生成于 2026-03-26，而代码持续迭代至 2026-05-18 之后，二者已经严重脱节。经逐项比对，前版存在以下问题：

1. **覆盖不足**：只描述了 55 个源文件中的 25 个（约 45%），30 个文件完全未提及，其中包括使用模式、首次引导、验证码子系统、隐私模式、系统历史短信导入与远程同步队列、设备目录与档案、会话档案、发送者配色、设置中心、SIM 绑定等 12 个成体系的功能块。
2. **事实错误**：Launcher 入口写成了 `MainActivity`（实际是 `SmsListActivity`）；架构图把 Room 数据库与 MQTT 通道画成直连，与真实数据流不符；数据模型缺 2 个实体与 3 个字段；传输协议只写了 5 类动作中的 1 类。
3. **表述夸大**："全链路数据加密传输"不成立——实际只有 MQTT 载荷加密，本地数据库与口令存储均为明文。
4. **掩盖风险**：旧版加密方案（无认证的 AES-CBC、单轮哈希派生密钥）在文档中被描述为"AES-256 加密"而未标注任何局限，且把两个已无任何引用的死代码文件标注为"旧版"而非"可删除"。

因此本文档不是对前版的修订，而是**依据当前源码事实的完整重写**，所有架构描述、字段清单、常量值均对照代码核实。凡与代码不符者，以代码为准。

### 0.2 标记约定

| 标记 | 含义 |
|---|---|
| ✅ | 已完整实现并在生产路径上生效 |
| △ | 部分实现，或实现存在已知局限 |
| ✗ | 未实现（占位或缺失） |

### 0.3 安全说明

dSIM 会在用户主动配置后，将本机收到的短信与设备状态上传至第三方公共 MQTT Broker，并接受其他持有相同口令的设备下发的远程发信指令。**本文档第 18、19 章完整列出了当前的未完成项与已知安全缺陷，任何部署决策都应先读完这两章。**

---

## 1. 项目定位与能力边界

### 1.1 定位

dSIM 是一款**分布式多设备短信同步应用**：用户在多台 Android 设备上安装并填入同一组 `MQTT Broker + Topic + 口令`，设备之间即形成一个私有的短信同步组，实现：

- 任一设备收到的短信，在本机入库后广播到组内其他设备；
- 在任一台设备上发起发送，可指定由组内另一台设备（使用其物理 SIM 卡）实际发出；
- 组内设备互相可见，包括电量、充电状态、是否默认短信应用、SIM 卡清单与历史同步进度。

### 1.2 为什么需要这个项目

典型场景是"多号多机"：一个人同时持有主力机与备用机，或家中长辈的机器需要代为配置。dSIM 让用户不必在多台设备之间来回切换，就能在一台设备上集中查看与发送另一台设备的短信。

### 1.3 明确的能力边界

以下内容属于产品的**有意约束**，不是缺陷：

| 边界 | 实现方式 |
|---|---|
| 不做静默代收 | 首次引导强制展示反诈风险告知，并设 8 秒阅读倒计时（`ANTI_FRAUD_COUNTDOWN_MS = 8_000L`），未确认则 `BootReceiver` 与云端功能全部不启动 |
| 不做后台偷传 | 提供四档使用模式，用户可切到"本地模式"彻底关闭全部云端入口 |
| 不做无提示上传 | 云端状态常驻通知栏，文案随连接状态变化 |
| 不做号码收集 | 无账号体系、无自有服务端，不留存任何用户数据；所有数据落在设备本地或用户自选的 Broker |

### 1.4 免责与合规风险

- **数据出境**：默认 Broker 为公共服务器 `tcp://broker.emqx.io:1883`。若用户不改用自建 Broker，短信内容将经由公网第三方中转。
- **口令强度即安全强度**：系统没有账号体系，安全性完全取决于用户设定口令的强度与保密性（详见 19.1）。
- **应用商店合规**：本应用申请短信读写、默认短信应用、开机自启等高风险权限，且具有远程发信能力。若计划上架，需重新评估各商店的短信类应用政策。

---

## 2. 技术栈与构建基线

### 2.1 构建配置

| 项 | 值 |
|---|---|
| 模块 | 单模块 `:app`，`rootProject.name = "dSIM"` |
| namespace / applicationId | `com.example.dsim` |
| compileSdk | 36 |
| minSdk | **24**（Android 7.0） |
| targetSdk | 36 |
| JVM target | 17 |
| versionCode / versionName | 1 / "1.0"（尚未正式发布版本化） |
| 构建插件 | AGP 8.7.3、Kotlin 2.1.10、KSP 2.1.10-1.0.31 |
| 代码生成 | KSP（Room compiler） |

### 2.2 依赖清单

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.core:core-ktx` | 1.10.1 | Kotlin 扩展 |
| `androidx.appcompat:appcompat` | 1.6.1 | 兼容性 |
| `com.google.android.material:material` | 1.10.0 | Material 3 主题（DayNight） |
| `androidx.activity:activity` | 1.8.0 | Activity 基础设施 |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | 布局 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.6.2 | `lifecycleScope` |
| `androidx.room:room-runtime` / `room-ktx` | 2.6.1 | 本地数据库 |
| `org.eclipse.paho:org.eclipse.paho.client.mqttv3` | 1.2.5 | **实际使用的 MQTT 实现** |
| `com.google.code.gson:gson` | 2.10.1 | JSON 序列化 |
| `com.googlecode.libphonenumber:libphonenumber` | 9.0.29 | E.164 号码标准化 |

### 2.3 构建时已知隐患

1. ~~版本目录未生效且数据陈旧~~ 已解决（批次 E）：`gradle/libs.versions.toml` 从未被引用（`agp = "9.1.0"` 与实际 8.7.3 矛盾），已删除。
2. **AGP 与 compileSdk 不匹配**：AGP 8.7.3 官方仅验证到 compileSdk 35，当前使用 36，构建时会产生 `This Android Gradle plugin (8.7.3) was tested up to compileSdk = 35` 警告。建议升级 AGP 或添加 `android.suppressUnsupportedCompileSdk=36`。
3. **Gradle 守护进程要求 JDK 21**：`gradle/gradle-daemon-jvm.properties` 固定 `toolchainVersion=21`。在未将 JDK 21 加入 Gradle 可发现的安装路径时，构建会直接失败并报 `Cannot find a Java installation ... Compatible with Java 21`。当前可用的绕过方式：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot"
./gradlew :app:compileDebugKotlin \
  -Dorg.gradle.java.installations.paths="C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"
```

4. **`exportSchema = false`**：Room 未导出 schema JSON，迁移的正确性无法通过 schema diff 自动校验。若后续继续加字段，建议开启 `exportSchema` 并把 schema 纳入版本库。

### 2.4 常用命令

```bash
./gradlew :app:compileDebugKotlin     # 仅编译 Kotlin（最快的语法/类型校验）
./gradlew :app:assembleDebug          # 产出 debug APK
./gradlew :app:testDebugUnitTest      # 单测（当前仅有模板用例）
```

---

## 3. 系统架构

### 3.1 分层视图

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 入口层                                                                    │
│   SmsListActivity(LAUNCHER) │ OnboardingActivity │ SettingsActivity        │
│   MainActivity(测试入口, exported=false)                                   │
├──────────────────────────────────────────────────────────────────────────┤
│ 界面层                                                                    │
│   SmsChatActivity │ OtpConversationActivity │ DeviceManagerActivity        │
│   SimBindingActivity │ 7 个 Dialog                                        │
├──────────────────────────────────────────────────────────────────────────┤
│ 能力层（短信加工 / 合规 / 呈现）                                            │
│   SmsSourceResolver │ SmsSourceRepairManager │ SmsTagParserUtils           │
│   GlobalNumberUtils │ PrivacyModeManager │ OtpRulesStore                   │
│   UsageModeManager │ OnboardingStateStore │ SetupChecklistManager          │
│   ConversationProfileStore │ SenderColorUtils │ DeviceDirectoryManager      │
├──────────────────────────────────────────────────────────────────────────┤
│ 同步层（云端）                                                             │
│   MqttSyncService(前台服务) ←→ HistorySyncQueueManager                      │
│   DsimCryptoUtils │ SyncPayload │ SendCmdPayload                           │
├──────────────────────────────────────────────────────────────────────────┤
│ 采集层                                                                    │
│   SmsReceiver / SmsReceivedReceiver (广播)                                │
│   SystemSmsHistoryImporter + SystemHistoryImportService (导入服务)          │
│   HardwareProbeUtils (SIM/设备探测)                                        │
├──────────────────────────────────────────────────────────────────────────┤
│ 数据层                                                                    │
│   Room: DsimDatabase v6 (5 实体) │ 9 组 SharedPreferences                  │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.2 真实数据流（修正前版架构图的错误）

前版架构图把"Room DB"与"AES-256 加密通道"画成直连，这是错误的——数据库不参与网络通信。真实的两条主链路如下（`→` 表示同步调用，`⇒` 表示经 MQTT 网络）：

**链路 A：入站短信（本机收到 → 云端）**

```
系统 SMS_DELIVER / SMS_RECEIVED 广播
  → SmsReceiver.onReceive
      → 动作互斥判定（依是否默认短信应用）
      → GlobalNumberUtils.formatToE164       号码标准化
      → SmsSourceResolver.resolveIncomingLocalSource   定位是哪个卡槽收到的
      → PrivacyModeManager.rememberOwnPhone  记录本机号（供脱敏）
      → dao.insertMessage                    写入 Room
      → SystemSmsStore.insertIncomingIfNeeded  回写系统短信库（去重窗口 2 分钟）
      → NotificationUtils.showNewMessageNotification
      → 直接取 MqttSyncService.globalMqttClient 并 publish  ⇒ 云端
```

注意最后一跳：**入站短信不经过 `MqttSyncService` 中转**，而是由 `SmsReceiver` 直接使用 `MqttSyncService` 暴露的全局静态客户端对象发布。`MqttSyncService` 只负责连接生命周期、订阅与入站消息分发。

**链路 B：出站短信（跨设备发信）**

```
SmsChatActivity.sendCommand
  → 本地先插入一条 status=0（发送中）的记录，uuid 预生成
  ⇒ 加密 JSON {action:"SEND_CMD", target, body, mappingKey, uuid, deviceId, deviceName}
  ↓（云端）
执行端 MqttSyncService.handleSendCommand
  → HardwareProbeUtils.resolveSubscriptionIdForMappingKey  定位物理卡
  → SmsManager.sendTextMessage
  → 按同一 uuid 回写本机 Room 记录
  ⇒ {action:"SEND_CMD_RESULT", uuid, targetDeviceId, success, message, timestamp}
  ↓（云端）
发起端 handleSendCommandResult → 将本地记录 status 更新为 1（成功）或 -1（失败）
```

整条链路以 `uuid` 贯穿，保证幂等：执行端在落库前用 `dao.checkUuidExists(uuid)` 判重，发起端收到自己 `deviceId` 的短信会直接丢弃。

### 3.3 进程与组件模型

| 组件 | 类型 | 进程 | 说明 |
|---|---|---|---|
| `SmsListActivity` | Activity | 主进程 | Launcher 入口 |
| `SmsChatActivity` | Activity | 主进程 | 会话详情 |
| `MqttSyncService` | 前台 Service（`remoteMessaging`） | 主进程 | 常驻守护，持通知 id 888；类型见 AGENTS C19（`dataSync` 不能从开机广播启动） |
| `SystemHistoryImportService` | 前台 Service（`dataSync`） | 主进程 | 历史导入期间临时启动 |
| `SmsReceiver` | 广播接收器（优先级 2147483647） | 主进程 | 默认短信应用时处理 `SMS_DELIVER` |
| `SmsReceivedReceiver` | 广播接收器（优先级 2147483647） | 主进程 | 非默认短信应用时处理 `SMS_RECEIVED` |
| `BootReceiver` | 广播接收器 | 主进程 | 开机拉起守护 |
| `OtpCopyReceiver` | 广播接收器 | 主进程 | 通知栏"复制验证码" |
| `MmsReceiver` | 广播接收器 | 主进程 | 占位，见 18.1 |
| `ComposeSmsActivity` | Activity | 主进程 | 占位，见 18.1 |
| `HeadlessSmsSendService` | Service | 主进程 | 占位，见 18.1 |

---

## 4. 数据层

### 4.1 数据库实例

| 项 | 值 |
|---|---|
| 数据库文件 | `dsim_core_database` |
| 当前版本 | **5** |
| 导出 schema | 否（`exportSchema = false`） |
| 访问方式 | `DsimDatabase.getDatabase(context).dsimDao()`，双重检查锁单例 |

### 4.2 实体

#### `sms_messages` — 短信主表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK autoGenerate | 自增主键 |
| `uuid` | String | 全局唯一标识，**建有唯一索引** `index_sms_messages_uuid`，跨设备幂等的基础 |
| `address` | String | 对端号码，E.164 格式 |
| `body` | String | 短信正文 |
| `timestamp` | Long | 毫秒时间戳 |
| `type` | Int | 1 = 收到，2 = 发出 |
| `status` | Int | 0 = 等待发送结果，1 = 系统发送成功（非送达），-1 = 失败，-2 = 结果未知 |
| `isRead` | Boolean | 已读状态，默认 false |
| `deviceId` | String | 归属设备（`ANDROID_ID`） |
| `simId` | Int | subscriptionId，-1 表示未知 |
| `iccid` | String? | ICCID，无 Root 权限时为 null |
| `mappingKey` | String | 硬件映射键，关联 `sim_card_configs` |
| `errorMsg` | String? | 失败原因 |

#### `sim_card_configs` — SIM 卡绑定配置

| 字段 | 类型 | 说明 |
|---|---|---|
| `mappingKey` | String PK | 硬件映射键 |
| `phoneNumber` | String | 号码（同时用作云端的"花名册"备注） |
| `alias` | String? | 别名 |
| `bindMode` | String | `ROOT_ICCID` / `NOROOT_DEVICE` / `REMOTE_SHADOW` |
| `isActive` | Boolean | 软删除标记 |
| `deviceId` | String | 所属设备 |
| `subscriptionId` | Int? | 系统订阅 id |
| `slotIndex` | Int? | 卡槽序号 |

索引：`(deviceId, subscriptionId)`、`(deviceId, slotIndex)`。

`bindMode` 语义：
- `ROOT_ICCID`：拿到 ICCID，映射键为 `ICCID_<iccid>`，换机插卡仍能识别；
- `NOROOT_DEVICE`：无权限，映射键为 `DEV_<deviceId>_SUBID_<id>` 或 `DEV_<deviceId>_SLOT_<n>`，与设备强绑定；
- `REMOTE_SHADOW`：远端设备通过 PONG 同步过来的"影子"配置，仅用于展示与选择，不代表本机有这张卡。

#### `device_profiles` — 设备档案（当前快照）

主键 `deviceId`。含设备名、号码串、电量、充电状态、是否默认短信应用、SIM 卡数、来源、是否本机、`allowsRemoteHistorySync`，以及一组历史队列快照字段：`historyQueueId` / `historyQueueStatus` / `historyQueuePosition` / `historyQueueLabel` / `historyQueueDetail` / `historyQueueProgressCurrent` / `historyQueueProgressTotal` / `historyQueueUpdatedAt`，另有 `firstSeenAt` / `lastSeenAt`。

#### `device_history` — 设备状态历史

自增 `id` 主键，字段与档案基本一致，外加 `seenAt` 与 `summary`，索引 `(deviceId, seenAt)`。用于"设备中心"的历史记录列表。

### 4.3 迁移历史

| 迁移 | 内容 |
|---|---|
| 1 → 2 | 新建 `device_profiles` 与 `device_history`（含索引） |
| 2 → 3 | `sim_card_configs` 增加 `deviceId` / `subscriptionId` / `slotIndex` 三列；用 SQL 从既有 `mappingKey` 反解填充（`substr` + `instr`）；创建两个复合索引 |
| 3 → 4 | `device_profiles` 增加 9 个历史队列相关列 |
| 4 → 5 | 修复历史脏数据：为 `uuid` 为空的记录补 `legacy_<id>`；按 `uuid` 分组仅保留最大 `id`；创建 `uuid` 唯一索引 |

4→5 这一步值得注意：它是为了引入 `uuid` 唯一索引而做的**数据清洗**，会物理删除同一 `uuid` 的重复历史记录。

### 4.4 DAO 方法分组（共 40 个）

| 分组 | 方法 |
|---|---|
| 短信写入 | `insertMessage`、`updateMessageStatus`、`updateSentMessageAfterSend`、`updateMessageMappingKey`、`clearAllSmsMessages` |
| 幂等与去重 | `checkUuidExists`、`countSimilarLocalMessage`、`findSimilarLocalMessage`、`countSmsMessages` |
| 短信读取 | `getAllSmsMessages`、`getAllSmsMessagesAsc`、`getAllSmsMessagesFlow`、`getLatestSmsMessages(limit)`、`getMessagesAfterWatermark` |
| 会话视图 | `getRecentConversations`、`getRecentConversationsFlow`、`getMessagesByAddress`(Flow)、`getMessagesByAddressFlow`、`getMessagesByAddressList` |
| 发件卡推断 | `findPreferredLocalMappingKeyForAddress`、`findMostUsedLocalMappingKey` |
| SIM 配置 | `saveSimConfig`、`getSimConfigByKey`、`getSimConfigByDeviceAndSubscriptionId`、`getSimConfigByDeviceAndSlot`、`updateSimConfigIdentity`、`getAllSimConfigs`、`getActiveSimConfigs`、`getAllSimConfigsForUi`、`deleteSimConfigByKey`、`unbindSimConfig`、`markConfigsAsInactive` |
| 设备档案 | `saveDeviceProfile`、`getDeviceProfile`、`getAllDeviceProfiles`、`getDeviceProfilesByQueueId` |
| 设备历史 | `insertDeviceHistory`、`getLatestDeviceHistory`、`getRecentDeviceHistory` |

响应式 UI 依赖 3 个 Flow 查询：`getRecentConversationsFlow`（会话列表自动刷新）、`getMessagesByAddressFlow`（聊天页自动刷新）、`getAllSmsMessagesFlow`。

---

## 5. 本地持久化全景

除 Room 外，全部持久化都走 SharedPreferences（无应用私有文件写入；头像图片通过持久化 URI 权限引用外部文件）。

| Prefs 文件 | 键 | 用途 |
|---|---|---|
| `dSIM_UI_PREFS` | `BROKER` `TOPIC` `PASSWORD` `AUTO_CONNECT` `AUTO_RECONNECT` | 云端连接配置 |
| | `USAGE_MODE` `USAGE_MODE_EXPANDED` | 使用模式与设置项展开态 |
| | `DEVICE_NAME` | 本机显示名 |
| | `IS_MUTED` | 通知静音 |
| | `PRIVACY_MODE` `OWN_PHONE_VARIANTS` | 隐私模式开关与本机号变体表 |
| | `HAS_SEEN_ONBOARDING` `ANTI_FRAUD_ACKNOWLEDGED` | 引导与反诈确认 |
| | `SHOW_OTP_COPY_TOAST` | 验证码复制提示 |
| `dSIM_OTP_RULES` | `INCLUDE_KEYWORDS` `EXCLUDE_KEYWORDS` `OVERRIDES`(JSON) | 验证码识别规则与临时纠错 |
| `dSIM_HISTORY_QUEUE_PREFS` | `ALLOW_REMOTE_START` 及 13 个 `QUEUE_*` / `LAST_BROADCAST_AT` | 历史同步队列状态 |
| `dSIM_HISTORY_QUEUE_NOTIFY_PREFS` | `REMOTE_QUEUE_ID` `REMOTE_QUEUE_REQUESTED_AT` `REMOTE_QUEUE_TARGET_COUNT` `REMOTE_QUEUE_TARGET_NAMES` | 远程队列通知去重 |
| `dSIM_CONVERSATION_PROFILE_PREFS` | `remark_<hash>` `avatar_<hash>` `avatar_mode_<hash>` `avatar_preset_<hash>` `avatar_image_<hash>` `pin_priority_<hash>` | 会话备注、头像、置顶（1–99） |
| `dSIM_CHAT_SENDER_PREFS` | `sender_<hash>` | 会话首选发件卡 |
| `dSIM_SENDER_COLOR_PREFS` | `color_identity_enabled` `device_auto_<key>` `device_custom_<key>` `sim_custom_<key>` | 设备/卡配色标识 |
| `dSIM_SYSTEM_HISTORY_IMPORT` | `ENABLED` `LAST_IMPORT_AT` `CURSOR_DATE` `CURSOR_ID` `REACHED_END` `IMPORT_VERSION` | 系统历史导入断点续传 |
| `dSIM_SYNC_PREFS` | `HIGH_WATERMARK` | 增量同步水位线 |
| `dsim_prefs` | `last_mqtt_broker` `last_mqtt_topic` | 测试页表单记忆 |

另有剪贴板写入：验证码复制时标签为「验证码」并标记 `android.content.extra.IS_SENSITIVE`。

**注意**：`dSIM_UI_PREFS` 中的 `PASSWORD` 以明文存储，这是 19.1 节记录的关键安全缺陷。

---

## 6. 短信采集与加工

### 6.1 SmsReceiver — 双动作互斥采集

这是整个系统最关键的采集点，逻辑比前版文档描述的复杂得多。

**双动作互斥规则**（解决"默认短信应用与非默认短信应用收到重复广播"的问题）：

| 是否默认短信应用 | 处理的动作 | 忽略的动作 |
|---|---|---|
| 是 | `SMS_DELIVER_ACTION` | `SMS_RECEIVED_ACTION` |
| 否 | `SMS_RECEIVED_ACTION` | `SMS_DELIVER_ACTION` |

`SmsReceivedReceiver` 是 `SmsReceiver` 的空壳子类（`class SmsReceivedReceiver : SmsReceiver()`），在 Manifest 中单独注册以监听 `SMS_RECEIVED`，两个接收器共享同一套处理逻辑。

**关键实现细节**：

- 长短信合并：`messages.joinToString("") { it.displayMessageBody }`；
- subscriptionId 提取按 4 个候选键依次尝试：`subscription`、`android.telephony.extra.SUBSCRIPTION_INDEX`、`subscription_id`、`sub_id`；
- slotIndex 提取按 6 个候选键依次尝试：`slot`、`slot_id`、`slotId`、`simSlot`、`phone`、`android.telephony.extra.SLOT_INDEX`；
- 使用 `goAsync()` + `CoroutineScope(Dispatchers.IO)`，并在 `finally` 中 `pendingResult.finish()`，避免广播超时；
- 云端发布前依次检查 `UsageModeManager.canUploadIncomingSms`、客户端连接状态、口令与 Topic 非空。

### 6.2 SmsSourceResolver — 入站短信归属判定

`resolveIncomingLocalSource(activeConfigs, deviceId, subscriptionId, slotIndex)` 的匹配优先级：

1. `deviceId + subscriptionId` 精确匹配（排除 `REMOTE_SHADOW`）；
2. `deviceId + slotIndex` 匹配，且该配置的 `subscriptionId` 为 null；
3. 未命中则构造"未绑定"占位键：`DEV_{deviceId}_SUBID_{id}_SLOT_{slot}_UNBOUND`，退化形式依次为 `DEV_{deviceId}_SUBID_{id}_UNBOUND`、`DEV_{deviceId}_SLOT_{slot}_UNBOUND`、`DEV_{deviceId}_UNKNOWN_UNBOUND`。

`isUnboundMapping` 判定即检查映射键是否含 `_UNBOUND`。`resolveHistoryImportSource` 只按 `deviceId + subscriptionId` 匹配。

前版文档中 `config.mappingKey.contains("SUBID_$subId")` 的字符串包含匹配已被此模块取代。

### 6.3 SmsSourceRepairManager — 借卡场景修复

`repairBorrowedMappings` 处理"SIM 卡被换到另一台设备"的历史数据：扫描 `type == 1 && simId > 0` 的消息，若其 `mappingKey` 对应的配置在 `deviceId` 或 `subscriptionId` 上与消息记录不符（说明这张卡被"借"走了），就把消息的映射键重写为对应的 `_UNBOUND` 键；若原配置是 `REMOTE_SHADOW` 且本地没有对应键，则补建一条占位 `SimCardConfig`，`alias` 取设备名。

### 6.4 GlobalNumberUtils — 号码标准化

基于 libphonenumber 的 `formatToE164`：解析失败或号码非法时回退为"去掉空格与短横线"的原串。作用是把 `(650) 555-0100`、`13800138000` 这类异构写法统一成 E.164，避免同一对端因写法不同而在会话列表里被拆成多个会话。

### 6.5 SmsTagParserUtils — 来源标签渲染

`parseAndFormatTag(mappingKey, simConfig, isLocalMessage, localDeviceName, maskPhoneNumbers)` 输出形如「位置 · 设备 · 卡槽 · 号码」的标签：

| 维度 | 取值 |
|---|---|
| 位置 | `本机`；`bindMode == "REMOTE_SHADOW"` 时为`云端` |
| 设备 | 本机名；远端为设备名或`设备+后4位`，缺省`远端设备` |
| 卡槽 | 解析 `_SLOT_` → `卡{n+1}`；`_SUBID_` → `Sub{id}`；`ICCID_` → `ICCID 后6位`；`_UNBOUND` → `未绑定卡`；否则`未知卡` |
| 号码 | 依 `maskPhoneNumbers` 决定是否经隐私模式打码 |

---

## 7. 硬件探测与 SIM 绑定

### 7.1 HardwareProbeUtils（327 行）

**映射键规则**：

| 模式 | 键格式 |
|---|---|
| `ROOT_ICCID` | `ICCID_<iccid>` |
| `NOROOT_DEVICE` + 有 subscriptionId | `DEV_<deviceId>_SUBID_<id>` |
| `NOROOT_DEVICE` + 无 subscriptionId | `DEV_<deviceId>_SLOT_<slotIndex>` |

**反解析与解析系列方法**：

- `parseDeviceIdFromMappingKey` / `parseSubscriptionIdFromMappingKey` / `parseSlotIndexFromMappingKey`：从键串还原三个维度；
- `buildNoRootMappingKey(deviceId, subscriptionId, slotIndex)`：构造键；
- `resolveSubscriptionIdForMappingKey(context, mappingKey)`：按 `subscriptionId` → `slotIndex` → `iccid` 的优先级，把键解析成当前系统可用的 subscriptionId；
- `resolveSubscriptionId(context, config)`：额外先尝试配置里记录的 `subscriptionId` 与 `slotIndex`。

这些方法是"跨设备远程发信"能落到正确物理卡上的基础。

`isMockNoRootMode` 是给测试用的全局开关，可在测试页强制走无 Root 分支。

### 7.2 SimConfigIdentityManager

`syncLocalConfigs` 把硬件探测到的真实 SIM 信息写回数据库：按探测结果补全或修正 `sim_card_configs` 的 `deviceId` / `subscriptionId` / `slotIndex`（跳过 `REMOTE_SHADOW`）。

### 7.3 SimBindingActivity（600 行，纯代码构建布局）

「SIM 绑定管理」界面，提供「探测并绑定本机 SIM」与解绑/恢复操作，并展示每张卡的号码、模式（`Root / ICCID 模式`等）与配色。

---

## 8. 加密层

> **本章描述的格式已在 V2.0 中重写。旧版为无认证的 AES-CBC，存在可篡改风险，且密钥仅经单轮 SHA-256 派生。**

### 8.1 线上格式

**V3（当前写入格式，认证加密，2026-09-18 批次 B）**

```
[4B 魔数 "DSM3"][12B GCM IV][密文][16B GCM 认证标签]        AAD = 魔数
主密钥 = PBKDF2-HMAC-SHA256(口令, 固定盐 "dSIM/v3/master-key", 120_000 轮, 256 bit)
```

主密钥**每个口令只派生一次**并在进程内缓存（LRU，上限 4 个口令）；IV 每条报文随机。各端只需共享口令即可互解。

**V1、V2 已移除**：当前只接受 DSM3 魔数。V2（`DSM2`，每条报文 16 字节随机盐 + 各自 PBKDF2）因收发双方每条报文都要跑 12 万轮而被替换；无已发布旧设备，不保留读取路径。

### 8.2 关键实现决策

| 决策 | 理由 |
|---|---|
| 当前只读写 V3/DSM3 | 所有设备应采用相同协议版本 |
| 固定盐 + 每口令一次派生 | 随机每条盐只在"同口令跨多条报文可被预计算表攻击"时有意义；这里口令本身就是组的共享密钥，攻击者需要的是口令而非某条报文，固定应用盐不降低实际安全性，却把每条报文的 CPU 从 0.2~0.5 秒降到微秒级 |
| 用魔数而非版本号字节区分格式 | V1 报文开头是随机 IV，单字节版本号有 1/256 概率误判；4 字节魔数把误判概率降到 2⁻³² |
| 自行实现 PBKDF2（RFC 8018 §5.2） | `SecretKeyFactory("PBKDF2WithHmacSHA256")` 需要 API 26，而 minSdk = 24。若按 API 级别回退到 `PBKDF2WithHmacSHA1`，同一口令在不同 Android 版本上会派生出**不同**密钥，导致跨设备静默解密失败。固定使用 HmacSHA256 可保证各端一致 |
| 迭代轮数 120,000 | 中端机单次派生约 0.2~0.5 秒；V3 下每个口令只派生一次（首次连接或改口令），之后每条报文零派生 |
| 移除 `topic.padEnd(KEY_SIZE, 'd')` | SHA-256 输出长度恒为 32 字节、与输入长度无关，该补位对结果无任何影响，属无副作用的死代码。当前不再提供 V1 派生或读取路径 |

### 8.3 升级操作要求

**所有已配对设备必须一并升级。** 若仅升级部分设备，升级后的设备发出的 V2 报文会被未升级设备解密失败并丢弃，表现为"单向消息丢失"。当前版本同样不能读取旧设备的 V1 报文。

若需要回滚：将 `encryptMessage` 换回 CBC 实现即可，但 V2 报文将无法被旧版本读取。

### 8.4 参数命名修正

旧实现中 `deriveKeyFromTopic(topic)` 与 `encryptMessage(plaintext, topic)` 的第二个形参名为 `topic`，但全部 13 处调用点传入的都是 `password`。**"Topic 参与密钥派生"是错误认知**——Topic 只决定报文投递到哪个频道，与密钥无关。V2 已将形参统一改名为 `secret` 并补充注释，避免后续维护者再被误导。

### 8.5 校验结果

PBKDF2 实现已用标准测试向量校验通过：

| 用例 | 结果 |
|---|---|
| PBKDF2-HMAC-SHA256, P=`password`, S=`salt`, c=1, dkLen=256 | PASS |
| 同上 c=2 | PASS |
| 同上 c=4096 | PASS |
| RFC 7914 §11, P=`passwd`, c=1, dkLen=512（多分块路径） | PASS |
| 与 Python `hashlib.pbkdf2_hmac('sha256', b'password', b'salt', 120000, 32)` 交叉比对 | PASS |

---

## 9. 云端同步协议

### 9.1 连接参数

| 项 | 值 |
|---|---|
| 默认 Broker | `tcp://broker.emqx.io:1883`（公共服务器，可配置） |
| 客户端 id | `dSIM_SEC_${deviceId}_${System.currentTimeMillis()}` |
| 持久化 | `MemoryPersistence`（不落盘） |
| cleanSession | true |
| connectionTimeout | 15 秒 |
| keepAliveInterval | 30 秒 |
| 自动重连 | 由 `AUTO_RECONNECT` 开关控制 |
| 订阅 QoS | 1 |
| 发布 QoS | 1（全部） |

### 9.2 动作（action）全集

| 动作 | 方向 | payload 字段 | 处理函数 |
|---|---|---|---|
| `SEND_CMD` | 发起端 → 全组 | `action` `target` `body` `mappingKey` `uuid` `deviceId` `deviceName` | `handleSendCommand` |
| `SEND_CMD_RESULT` | 执行端 → 发起端 | `action` `uuid` `targetDeviceId` `deviceId` `deviceName` `success` `message`(≤120) `timestamp` | `handleSendCommandResult` |
| `PING` | 任意 → 全组 | `action` `deviceId` | 收到即回快照 |
| `PONG`（设备快照） | 全组广播 | `action` `deviceId` `deviceName` `battery` `isCharging` `isDefaultSms` `sims[]` `historyQueue{}` | `saveRemoteSnapshot` + `syncRemoteSimsFromPong` |
| `HISTORY_QUEUE_BATCH` | 发起端 → 全组 | `action` `queueId` `createdAt` `requestedByDeviceId` `requestedByDeviceName` `targets[{deviceId,deviceName,position}]` | `handleHistoryQueueBatch` |
| `HISTORY_SYNC_ACK` | 接收端 → 发起端 | `action` `uuid` `targetDeviceId` `deviceId` `deviceName` `success` `message`(≤120) | `handleHistorySyncAck` |
| （无 action）短信同步载荷 | 双向 | `SyncPayload` | `handleIncomingMessage` 尾段 |

`PONG` 的 `sims[]` 元素字段：`mappingKey` `deviceId` `subscriptionId` `slotIndex` `phone` `mode`。

`PONG` 的 `historyQueue{}` 字段：`allowRemoteStart` `queueId` `status` `position`(可空) `label` `detail` `progressCurrent` `progressTotal` `updatedAt`。

`SyncPayload` 结构：

```kotlin
data class SyncPayload(
    val sms: SmsMessage,
    val remarkPhone: String,
    val deviceName: String? = null,
    val silentSync: Boolean = false,
    val historyImport: Boolean = false
)
```

### 9.3 入站消息处理顺序（`handleIncomingMessage`）

```
①  DsimCryptoUtils.decryptMessage(encrypted, currentPassword)  解密，失败即返回
②  JSONObject 解析
③  读取 action / deviceId / localDeviceId
④  HISTORY_SYNC_ACK   → 校验 targetDeviceId == localDeviceId，否则丢弃
⑤  HISTORY_QUEUE_BATCH
⑥  PING（发送方非本机）→ 回发设备快照
⑦  PONG（发送方非本机）→ 落库档案 → 刷新通知 → 同步远端 SIM → 评估是否轮到自己导入
                        → 发出雷达事件 → Toast
⑧  SEND_CMD_RESULT
⑨  SEND_CMD
⑩  无 sms 字段则忽略
⑪  Gson 反序列化为 SyncPayload
⑫  sms.deviceId == localDeviceId 则丢弃（自己发的回声）
⑬  UsageModeManager.canReceiveCloudSms 拦截；历史导入场景回 ack "ignored_by_mode"
⑭  dao.checkUuidExists 判重；重复则回 ack "already_exists"
⑮  影子配置处理
⑯  insertMessage + 回 ack；非 silentSync 时弹通知
```

### 9.4 去重与幂等

| 场景 | 机制 | 时间窗 |
|---|---|---|
| 云端短信落库 | `dao.checkUuidExists(uuid)`，依赖 `uuid` 唯一索引 | 无窗口，永久判重 |
| 回写系统短信库 | `SystemSmsStore.DEDUPE_WINDOW_MS`，按 address + body + type + DATE 比对 | ±2 分钟 |
| 历史导入 | `SystemSmsHistoryImporter.APP_DEDUPE_WINDOW_MS`，走 `dao.findSimilarLocalMessage(deviceId, address, body, type, mappingKey, ±窗口)` | ±2 分钟 |
| 跨设备发信 | 预生成 uuid，全链路复用；`SEND_CMD_RESULT` 更新 status | 无窗口 |

### 9.5 历史同步队列状态机

状态常量：`IDLE` / `QUEUED` / `RUNNING` / `PAUSED` / `COMPLETED` / `FAILED`。

状态迁移（`updateFromImportState`）：

| 条件 | 迁移结果 |
|---|---|
| `isRunning` | → `RUNNING` |
| `isPaused` | → `PAUSED` |
| `RUNNING` 且阶段文案含"完成" | → `COMPLETED` |
| `RUNNING` 且阶段文案含"失败" | → `FAILED` |
| 处于 `QUEUED` / `COMPLETED` | 保持不变 |
| 存在快照 | → `PAUSED` |
| 其余 | → `IDLE` |

queueId 生成规则：远程发起为 `queue_<当前毫秒>_<UUID 前 6 位>`；本地为 `local_<UUID>`；推断场景为 `local_<UUID>`。

`handleQueueBatch` 的忽略条件：本机已是 `RUNNING` 且 queueId 不同；或本机未开启 `ALLOW_REMOTE_START` 且请求方不是本机。

协作流程：`publishQueueBatchRequest` 广播批次 → 各端 `handleQueueBatch` 落库排队 → `evaluateAndMaybeStartLocal` 校验"队列首位且无在线 RUNNING 对端"后启动 `SystemHistoryImportService.startImport` → 导入服务每步回调 `updateFromImportState` → 经 `maybeBroadcastLocalSnapshot` 节流广播本机队列快照 → 以 `HISTORY_SYNC_ACK` 回执确认。回执等待实现为 `registerHistoryImportAckWaiter(uuid)` + `withTimeoutOrNull`（deadline 20 秒、轮询间隔 120 毫秒）。

### 9.6 时间常量总表

| 常量 | 值 | 位置 | 含义 |
|---|---|---|---|
| `DEVICE_SNAPSHOT_INTERVAL_MS` | 20,000 | MqttSyncService | 设备快照广播周期 |
| `SNAPSHOT_BROADCAST_MIN_INTERVAL_MS` | 1,200 | 两处 | 快照广播节流 |
| `ONLINE_TIMEOUT_MS` | 45,000 | DeviceDirectoryManager | 判定设备离线 |
| `HISTORY_MIN_INTERVAL_MS` | 60,000 | DeviceDirectoryManager | 设备历史落库最小间隔 |
| `QUEUE_STATE_STALE_PROTECTION_WINDOW_MS` | 120,000 | DeviceDirectoryManager | 队列状态陈旧保护窗 |
| `HISTORY_ACK_TIMEOUT_MS` | 20,000 | HistorySyncQueueManager | 历史回执超时 |
| `DEDUPE_WINDOW_MS` | 120,000 | SystemSmsStore | 系统库去重窗 |
| `APP_DEDUPE_WINDOW_MS` | 120,000 | SystemSmsHistoryImporter | 应用库去重窗 |
| `ANTI_FRAUD_COUNTDOWN_MS` | 8,000 | OnboardingActivity | 反诈阅读倒计时 |

---

## 10. 使用模式与本地模式

这是产品的核心隐私控制，前版文档完全未提及。

| 模式 | 上传本机入站短信 | 接收云端短信并落库/通知 | 连接云端 |
|---|---|---|---|
| `BIDIRECTIONAL_SYNC` 双向同步 | ✅ | ✅ | ✅ |
| `RECEIVE_ONLY` 只接收 | ✗ | ✅ | ✅ |
| `FORWARD_ONLY` 只转发 | ✅ | ✗ | ✅ |
| `LOCAL_ONLY` 本地模式 | ✗ | ✗ | ✗ |

默认模式判定：若 `CloudSettingsManager.hasConnectionConfig()`（Broker + Topic + 口令均非空）为真，则为 `BIDIRECTIONAL_SYNC`，否则为 `LOCAL_ONLY`。

**本地模式的拦截点（共 7 处）**：

| 拦截点 | 判定方法 |
|---|---|
| `MqttSyncService.onStartCommand`（4 个分支） | `isLocalOnly` |
| `MqttSyncService.connectAndSubscribe` | `canUseCloud` |
| 入站消息处理第 ⑬ 步 | `canReceiveCloudSms` |
| `HistorySyncQueueManager.publishQueueBatchRequest` / `maybeBroadcastLocalSnapshot` | `canUseCloud` |
| `SmsChatActivity.sendCommand` / `retrySend` | `canUseCloud` |
| `SmsReceiver.publishIncomingSmsToCloud` | `canUploadIncomingSms` |
| `BootReceiver.onReceive` | `canUseCloud` |

切入本地模式时，`ACTION_APPLY_LOCAL_MODE` 会主动 `disconnect()` + `close()` 并把全局客户端置空，同时停止快照心跳；通知栏文案变为「本地模式，云端入口已关闭」。

---

## 11. 通知层

### 11.1 通知渠道

| 渠道 id | 名称 | 重要级 | 用途 |
|---|---|---|---|
| `dsim_loud_v1` | 极客高优新消息 (响铃) | HIGH | 新短信，带声音、震动（0/250/250/250）与横幅 |
| `dsim_silent_v1` | 极客静默新消息 (静音) | LOW | 静音模式下的新短信 |
| `dsim_sync_channel` | dSIM 安全同步服务 | LOW | 前台服务常驻通知，`VISIBILITY_PRIVATE` |
| `dsim_history_import_queue` | — | — | 历史同步队列进度 |

旧的 `dsim_new_message_channel` 在每次创建渠道时会被主动删除（渠道名与重要级一旦创建便不可修改，故采用"新版本号命名 + 删旧渠道"的迁移策略）。

### 11.2 通知 id 分配

| id | 用途 |
|---|---|
| 888 | MqttSyncService 前台服务常驻通知 |
| 1088 | 历史导入执行方进度通知 |
| 1089 | 历史导入请求方进度通知 |
| `System.currentTimeMillis() % 10000` | 新短信通知（滚动，避免互相覆盖） |

### 11.3 静音开关

`dSIM_UI_PREFS.IS_MUTED` 在通知构建时决定渠道与优先级：静音走 `CHANNEL_SILENT` + `PRIORITY_LOW` + 无震动，非静音走 `CHANNEL_LOUD` + `PRIORITY_MAX` + `DEFAULT_ALL`。Android 13+ 未授予 `POST_NOTIFICATIONS` 时直接返回不弹通知。

同步服务的常驻通知标题为「dSIM 云端状态：<状态>」，正文由详情与「隐私模式已开启」以 `·` 连接。状态文案分支包括：正在启动守护服务 / 未配置 / 正在自动连接 / 未连接，自动连接未开启 / 已连接，守护进程常驻中 / 已恢复连接 / 已断开，正在自动重连 / 自动重连已关闭 / 已手动断开，本次不会自动重连 / 本地模式，云端入口已关闭 / 连接失败，请检查网络或 Broker。

---

## 12. 界面层

### 12.1 Activity 清单

| 类 | 标题 | 职责 | 接收的 Intent extra |
|---|---|---|---|
| `SmsListActivity` | 信息 | **Launcher 主入口**。会话列表、菜单「设置」、引导补完卡、FAB 新建会话、长按弹「会话操作」（置顶 1–99 / 清除置顶 / 编辑会话资料） | `RETURN_HOME_ON_FINISH` |
| `SmsChatActivity` | — | 聊天气泡、发件卡选择弹窗、失败「点此重试」 | `CHAT_ADDRESS`、`TARGET_UUID` |
| `SettingsActivity` | 设置 | 全部配置项（见 12.2） | `EXTRA_OPEN_QUEUE` |
| `DeviceManagerActivity` | 设备中心 | 设备卡、`全选在线设备`、`开始排队同步`、`颜色设置`、颜色标识开关、历史记录 | — |
| `SimBindingActivity` | SIM 绑定管理 | `探测并绑定本机 SIM`、解绑/恢复 | — |
| `OtpConversationActivity` | — | 验证码列表、「验证码规则」弹窗、长按纠错 | `CHAT_ADDRESS`、`TARGET_UUID` |
| `OnboardingActivity` | — | 6 步首次引导 | `RETURN_HOME_ON_FINISH` |
| `MainActivity` | 测试功能 | `exported=false` 的调试入口：硬件探测、注入模拟短信、全量历史、双库只读测试、清空私有库、通知测试、雷达 | — |

### 12.2 设置项分组

| 分组 | 项 |
|---|---|
| 使用模式（由 `insertUsageModeSection` 动态注入，非 XML） | 双向同步 / 只接收 / 只转发 / 本地模式 / 重新打开首次引导 |
| 设备名 | 当前生效、系统默认、保存设备名、恢复系统默认设备名 |
| 默认短信应用 | 已是默认短信应用 / 设为默认短信应用 |
| 入口 | 本机收件箱 / 管理本机 SIM / 查看设备中心 |
| 通知 | 静音通知 |
| 验证码 | 验证码复制提示 |
| 隐私 | 隐私模式 |
| 云端配置 | MQTT 服务器 / 房间号（MQTT Topic） / 加密密码 / 保存 / 连接云端 / 断开云端 / 开机自动连接 / 断线自动重连 / 允许其他设备通过本机发短信（默认开） / 每日代发上限（默认 50 条，0 = 不限）+ 今日用量 |
| 系统历史短信 | 系统历史短信导入 / 允许远程发起历史同步 / 本机立即开始 / 重置本机读取进度 / 历史同步任务弹窗 |
| 测试 | 测试功能 |

### 12.3 布局清单（20 个）

`activity_sms_list`、`activity_sms_chat`、`activity_settings`、`activity_device_manager`、`activity_otp_conversation`、`activity_onboarding`、`activity_main`、`dialog_create_conversation`、`dialog_contact_picker`、`dialog_conversation_actions`、`dialog_conversation_profile`、`dialog_sender_selector`、`dialog_history_import_queue`、`item_conversation`、`item_chat_bubble`、`item_otp_message`、`item_sender_option`、`item_contact_phone`、`item_avatar_preset`（`item_sms` 已于批次 E 删除）。

`SimBindingActivity` 与 `DeviceManagerActivity` 不使用 XML 布局，界面由代码构建。

---

## 13. 首次引导与合规设计

### 13.1 六步引导

`TOTAL_STEPS = 6`，第 0 步设 `ANTI_FRAUD_COUNTDOWN_MS = 8_000L` 强制阅读倒计时。

| 步骤 | 标题 |
|---|---|
| 0 | 高风险提醒，请先完整阅读 |
| 1 | 授权基础权限 |
| 2 | 设为默认短信应用 |
| 3 | 填写云端配置 |
| 4 | 绑定本机 SIM |
| 5 | 准备好了 |

第 0 步风险文案（原文）：

> 1. 别人要求你安装 dSIM，极可能是诈骗。
> 2. 不要把房间号（MQTT Topic）、加密密码、验证码、短信内容告诉任何人。
> 3. 远程协助配置、代收验证码、让你把主力机接入云端，都属于高风险行为。
> 4. 涉及支付、实名、金融、社交账号的设备，尤其不要交给别人指导配置。

确认按钮文案为「我已了解风险，继续使用」。

### 13.2 状态与强制生效

`OnboardingStateStore` 维护两个键：`HAS_SEEN_ONBOARDING`、`ANTI_FRAUD_ACKNOWLEDGED`。

`ANTI_FRAUD_ACKNOWLEDGED` 是**硬门槛**，不只是展示：`BootReceiver` 在收到开机广播时若该键为假则直接返回，不拉起任何云端服务。这是"不做静默代收"这条产品边界的代码级落实。

### 13.3 配置完备度检查

`SetupChecklistManager.missingItems` 返回未完成项，检查 5 项：安全确认、基础权限、默认短信应用、SIM 绑定、云端通道。其中"SIM 绑定"的判定为存在至少一张 `bindMode != "REMOTE_SHADOW"` 的活跃卡。该结果驱动收件箱顶部的引导补完卡。

---

## 14. 验证码（OTP）子系统

### 14.1 识别规则

`OtpRulesStore`（prefs `dSIM_OTP_RULES`）维护三组规则：`INCLUDE_KEYWORDS`、`EXCLUDE_KEYWORDS`、`OVERRIDES`（按 uuid 的临时纠错，可强制判定为验证码或移出）。

默认包含关键词：`验证码`、`校验码`、`动态码`、`驗證碼`、`認證碼`、`otp`、`one-time password`、`verification code`、`verify code`、`security code`、`login code`。

### 14.2 提取算法

`OtpConversationUtils` 的正则常量：

| 常量 | 正则 |
|---|---|
| `bracketSenderRegex` | `[【\[]([^】\]]{1,24})[】\]]` |
| `digitCodeRegex` | `(?<!\d)(\d{4,8})(?!\d)` |
| `mixedCodeRegex` | `(?i)\b([A-Z0-9]{4,10})\b` |

`extractOtpCode` 策略：先取默认关键词的首个出现位置作为锚点；优先做纯数字匹配，用 `selectClosestMatch` 返回距锚点最近的一个（无锚点则取第一个）；若没有数字匹配，再取同时含字母与数字的 `mixedCodeRegex` 匹配，同样取最近者。

### 14.3 展示与复制

`OtpConversationActivity` 采用扁平列表按时间升序、最新在底部并自动滚动到底。卡片展示发件人标签、时间、来源标签、验证码本体、`senderLabel | 验证码` 与正文预览。在收件箱中，仅最新一条会被聚合为「验证码」会话（pinKey `__DSIM_OTP_CONVERSATION__`）。

复制通道走通知栏动作 `com.example.dsim.action.COPY_OTP`，extras 为 `EXTRA_OTP_CODE` / `EXTRA_SMS_UUID` / `EXTRA_SMS_ADDRESS` / `EXTRA_MAPPING_KEY`。`OtpCopyReceiver` **在锁屏状态下拒绝复制**，写入剪贴板时标签为「验证码」并置敏感标记（`android.content.extra.IS_SENSITIVE` / `ClipDescription.EXTRA_IS_SENSITIVE`），Android 13 以下且开关开启时才弹提示。

---

## 15. 隐私模式

`PrivacyModeManager`（键 `dSIM_UI_PREFS.PRIVACY_MODE`，默认关闭）对**本机号码**做中段打码。

### 15.1 脱敏算法

| 号码位数 | 保留前缀 | 输出格式 |
|---|---|---|
| ≥ 11 | 前 3 位 | `前N****后4` |
| ≥ 9 | 前 2 位 | 同上 |
| 其余 | 前 1 位 | 同上 |
| ≤ 7，或以 `106` 开头 | — | 不处理 |

E.164 格式会按需保留国家码。

### 15.2 本机号变体识别

只对**已记录的本机号**生效，非本机号一律不打码。本机号变体在收到短信时由 `SmsReceiver` 调用 `rememberOwnPhone` 记录，变体集合包括：原串、纯数字、`+数字`，以及在解析有效时的 E.164、E.164 去加号、`+E.164 去加号`、national、`国家码 + national`、`+国家码 + national`。仅保留数字长度大于 7 的变体。

匹配时用宽松正则：每位数字之间允许 `[\s\-().]*`；有 `+` 前缀用 `(?<![\w+])\+?`，否则用 `(?<!\d)`；尾部加 `(?!\d)`。这样可以命中用户输入时的各种分隔写法。

### 15.3 作用范围

已接入脱敏的场景：会话列表、聊天页、验证码列表与工具、SIM 绑定页、设备中心、设备目录的号码展示、短信来源标签、设置页，以及三类云端通知文案（新短信正文、云端状态、通知中的设备名）。

---

## 16. 设备目录与视觉标识

### 16.1 设备目录

`DeviceDirectoryManager`（286 行）负责把本机快照与远端 PONG 快照统一落库到 `device_profiles` / `device_history`，并提供在线判定与历史记录查询（时间常量见 9.6）。`formatDeviceId` 对超过 14 位的 id 截断为 `前6...后4`。

`DeviceNameManager` 管理显示名：`getSystemName()` 取 `Build.MODEL`，为空时回退为 `Android Device`；用户可覆盖并在设置页一键恢复默认。

### 16.2 配色体系

`SenderColorPreferenceStore` 提供 8 色预设：

| 名称 | 色值 |
|---|---|
| 绿 | `#2FA84F` |
| 紫 | `#6750A4` |
| 青 | `#0F8B8D` |
| 蓝 | `#2563EB` |
| 橙 | `#D97706` |
| 玫红 | `#D9466F` |
| 湖蓝 | `#0891B2` |
| 靛蓝 | `#4F46E5` |

自动分配规则：`floorMod(deviceKey.hashCode(), 8)`，若目标色已被占用则向后顺延取第一个未用色，避免同一组内出现撞色。默认 `color_identity_enabled = true`。

`SenderColorUtils` 从基础 accent 派生出完整调色板：`dark = shiftValue(0.72)`、`soft = blendWhite(0.92)`、`strongSoft = 0.82`、`border = 0.55`、`source = shiftValue(0.78)`。卡片索引 `cardIndexForSim` 的判定顺序为 `slotIndex` → `floorMod(subscriptionId, 4)` → `floorMod(mappingKey.hashCode(), 4)`。`deriveCardAccent` 用固定的色相偏移 `[0, 8, -8, 15, -15]` 度、饱和度系数 `[1, 1.05, 0.94, 1.08, 0.9]`、明度系数 `[1, 0.86, 1.05, 0.94, 0.9]` 区分同色系的多张卡。中性色为 `#7C8796`，文字色切换阈值为亮度 0.62。

### 16.3 会话档案

`ConversationProfileStore` 提供会话备注、头像（三种模式：`TEXT` / `PRESET` / `IMAGE`，6 张预置头像位于 `res/drawable-nodpi/`）与置顶优先级（1–99）；`ConversationSenderStore` 记录每个会话的首选发件卡。

---

## 17. 权限模型

`CorePermissionHelper` 只做**缺失检测与标签映射**，不含申请逻辑（申请在各界面自行触发）。

| 权限 | 生效条件 | 中文标签 |
|---|---|---|
| `READ_SMS` | — | 读取短信 |
| `RECEIVE_SMS` | — | 接收短信 |
| `SEND_SMS` | — | 发送短信 |
| `READ_PHONE_STATE` | — | 读取电话状态 |
| `READ_PHONE_NUMBERS` | SDK ≥ 26 | 读取本机号码 |
| `POST_NOTIFICATIONS` | SDK ≥ 33 | 通知 |

`grantedSummary` 输出形如「已授权 x / y 项」。

Manifest 声明的完整权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`FOREGROUND_SERVICE_REMOTE_MESSAGING`、`SEND_SMS`、`RECEIVE_SMS`、`READ_SMS`、`RECEIVE_MMS`、`READ_PHONE_STATE`、`READ_PHONE_NUMBERS`、`READ_CONTACTS`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`。

其中 `READ_CONTACTS` 仅用于「新建会话」的联系人选择器。

---

## 18. 未完成项

### 18.1 默认短信应用的三个必备组件（✗）

应用若被设为默认短信应用，系统要求实现三个组件。目前三者均为占位：

| 文件 | 现状 | 影响 |
|---|---|---|
| `ComposeSmsActivity` | `onCreate` 中仅剩注释「占位逻辑：将来可以显示一个发送短信的界面」，随后立即 `finish()` | 其他应用通过 `sms:` / `smsto:` Intent 调用时页面会立即关闭；此业务缺口不等于系统必然拒绝角色 |
| `HeadlessSmsSendService` | 仅有 `onBind` 返回 null | 系统的"通过短信回复"（`RESPOND_VIA_MESSAGE`）能力失效 |
| `MmsReceiver` | `onReceive` 为空 | 彩信无法接收。注意：`RECEIVE_MMS` 权限与接收器已在 Manifest 声明，文档层面易被误认为已支持彩信 |

这些组件存在业务实现缺口。角色资格主要由 Manifest 声明等条件决定，实际授予与业务可用性应分别验证；不要把不在本项目的 `OutgoingSmsReceiver` 当作通用必备组件。

### 18.2 测试（✗）

2026-09-18 已新增 13 项发送状态/配置恢复单测及 5 项隔离 Room 设备测试，见 `FIXES_2026-09-18.md`。模板测试仍保留；完整业务覆盖、真实运营商发送及旧版本逐级迁移仍有缺口。尚未配置 detekt/ktlint 等专项静态检查。

考虑到系统里存在加密协议、状态机、多设备时序与数据迁移这些高回归风险区域，建议优先补齐：

1. ~~`DsimCryptoUtils` 的加解密往返、篡改拒收与错误口令拒收用例~~（已补：`DsimCryptoUtilsTest`，DSM3）；
2. `HistorySyncQueueManager` 的状态迁移用例；
3. `PrivacyModeManager` 的号码变体匹配用例；
4. Room 迁移 v1→v6 的逐级用例（已补 v5→v6 定向测试）。

### 18.3 发布工程化（✗）

| 项 | 现状 |
|---|---|
| 签名 | 未配置 `signingConfigs`，无 release keystore |
| 混淆 | `isMinifyEnabled = false`，`proguard-rules.pro` 为空模板 |
| 版本管理 | `versionCode = 1`、`versionName = "1.0"`，尚未随功能迭代更新 |
| CI | 无 |
| 应用图标 | 使用 AS 默认生成的 `ic_launcher` 自适应图标 |

### 18.4 遗留死代码（△）— 批次 E 已清理

已删除：`DsimMqttEngine.kt`、`DsimNetworkEngine.kt`、`res/layout/item_sms.xml`、`gradle/libs.versions.toml`、`hivemq-mqtt-client` 依赖及其 netty 打包排除项；`DsimDao` 中 `getRecentConversations` / `getMessagesByAddress`（Flow）/ `getMessagesByAddressList` / `countSimilarLocalMessage` 四个零调用方法已删，`getAllSimConfigsForUi` 并入同 SQL 的 `getAllSimConfigs`。

| 对象 | 说明 |
|---|---|
| `SendCmdPayload.kt`（10 行） | 远程发信载荷的 data class，目前仍未被使用；W5 协议数据类化时启用，届时不再算死代码 |

---

## 19. 已知缺陷与风险清单

### 19.1 安全

| # | 风险 | 现状 | 建议 |
|---|---|---|---|
| S1 | **口令明文存储** | `dSIM_UI_PREFS.PASSWORD` 与 `BROKER`/`TOPIC` 以明文写入普通 SharedPreferences，未使用 `EncryptedSharedPreferences` 或 SQLCipher。任何具备 Root 或备份提取能力的第三方可直读 | 迁移到 `EncryptedSharedPreferences`，并在读取侧做一次性迁移 |
| S2 | **本地数据库明文** | Room 数据库未加密，短信正文以明文落盘 | 视威胁模型决定是否引入 SQLCipher；至少确保 `allowBackup` 策略不会把数据库带出设备 |
| S3 | **公共 Broker 与可猜 Topic** | 默认 `broker.emqx.io`:1883 为公共服务器；Topic 由用户自拟，常见做法是可读字符串。攻击者可订阅通配 Topic 收集密文 | 引导用户改用自建 Broker；对 Topic 提出不可猜测性要求；可考虑对 Topic 也做派生 |
| S4 | **弱口令的离线爆破** | V2 已引入 PBKDF2（12 万轮）显著抬高代价，但口令若为用户自拟短串，仍存在被爆破风险 | 在设置页增加口令强度提示与最小长度校验 |
| S5 | **升级期单向不可读** | 只读写 V2，旧 V1 设备与当前设备不互通（详见 8.3） | 发布说明中强制要求全设备同步升级 |
| S6 | **`allowBackup="true"`** | Manifest 允许备份，规则文件为模板，未排除数据库与 prefs | 明确排除 `dsim_core_database` 与 `dSIM_UI_PREFS` |

### 19.2 稳定性

| # | 风险 | 说明 |
|---|---|---|
| R1b | 断网后不重连 | 已修（批次 F2）：服务自管指数退避 + 网络恢复立即重试，见 AGENTS C20；真机运营商网络切换仍需实测 |
| R1 | 守护进程被系统回收 | 依赖前台服务 + `START_STICKY` + 开机广播。在国产 ROM 的省电策略下仍可能被清理，需用户手动加入白名单 |
| R2 | 去重时间窗 | 系统库与应用库去重均依赖 ±2 分钟窗口。若同一条短信在窗口外被重复导入，会产生重复记录 |
| R3 | 状态迁移依赖文案匹配 | `HistorySyncQueueManager` 通过"阶段文案是否含『完成』/『失败』"判断终态，对文案改动敏感，属脆弱设计 |
| R4 | 快照节流与心跳叠加 | 心跳 tick 30 秒，但只在指纹变化或 120 秒静默后发布（`HeartbeatPolicy`）；每台设备发布到 `<base>/<deviceId>`，订阅 `<base>/+`，自身回声不解密。多设备（>10）同时在线时 Broker 侧流量与客户端解析压力仍需实测 |
| R5 | `exportSchema = false` | 迁移正确性无法自动化校验 |

### 19.3 代码卫生

| # | 项 | 状态 |
|---|---|---|
| H1 | `MqttSyncService.kt` 4 处中文乱码（GBK 被误读为 UTF-8 后写回，含 1 处位于注释中） | **已修复**（V2.0 本次） |
| H2 | `DsimCryptoUtils` 死代码 `padEnd`、形参名 `topic` 误导、无认证加密 | **已修复**（V2.0 本次） |
| H3 | `ui_prototypes/`（395 个文件，含竞品 APK 与反编译产物）未纳入 `.gitignore` | **已修复**（V2.0 本次） |
| H4 | 5 个源文件长期未提交版本库 | **已修复**（V2.0 本次） |
| H5 | `derived` 与 `build_backup` 等目录 | 已在 `.gitignore` 中排除 |
| H6 | 大量界面色值以 `Color.parseColor` 硬编码在代码里，`colors.xml` 仅有黑白 | 待重构为资源或主题属性 |
| H7 | 中文字符串硬编码在 Kotlin 中，未走 `strings.xml` | 影响多语言与文案统一维护 |

---

## 20. 文件结构总览

```
dSIM/
├── .gitignore                     已排除 ui_prototypes/、tmp_db/、tmp_previews/、.workbuddy/
├── build.gradle.kts               根构建：AGP 8.7.3 / Kotlin 2.1.10 / KSP 2.1.10-1.0.31
├── settings.gradle.kts            rootProject.name = "dSIM"，单模块 :app
├── gradle.properties
├── gradle/
│   └── gradle-daemon-jvm.properties  固定 toolchainVersion=21
├── local.properties               不入库
├── ui_prototypes/                 UI 参考素材（已 gitignore）
├── tmp_db/  tmp_previews/         调试产物（已 gitignore）
├── AGENTS.md                      面向 AI 智能体与协作者的仓库约定
├── dSIM 分布式多设备短信同步系统 技术架构说明书 V2.0.md   本文档
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro         空模板
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/dsim/         55 个 Kotlin 文件 / 14,306 行
        │   └── res/                           74 个资源文件
        ├── test/                     模板用例
        └── androidTest/              模板用例
```

### 20.1 源码清单（按行数降序）

| 文件 | 行数 | 职责 |
|---|---|---|
| `SettingsActivity.kt` | 1393 | 设置中心（含动态注入的使用模式分组） |
| `DeviceManagerActivity.kt` | 1099 | 设备中心 |
| `MqttSyncService.kt` | 1088 | MQTT 前台服务，协议中枢 |
| `SmsListActivity.kt` | 1027 | 收件箱主入口（LAUNCHER） |
| `MainActivity.kt` | 954 | 调试测试入口（exported=false） |
| `SmsChatActivity.kt` | 727 | 会话详情与发信 |
| `OnboardingActivity.kt` | 711 | 6 步首次引导 |
| `HistoryQueueNotificationHelper.kt` | 679 | 历史同步队列通知 |
| `SimBindingActivity.kt` | 600 | SIM 绑定管理 |
| `SystemSmsHistoryImporter.kt` | 576 | 系统历史短信导入 |
| `HistorySyncQueueManager.kt` | 497 | 历史同步队列状态机 |
| `HardwareProbeUtils.kt` | 327 | SIM/设备探测与映射键 |
| `OtpConversationActivity.kt` | 306 | 验证码列表 |
| `DeviceDirectoryManager.kt` | 286 | 设备档案与在线判定 |
| `SystemHistoryImportService.kt` | 264 | 历史导入前台服务 |
| `PrivacyModeManager.kt` | 262 | 号码脱敏 |
| `SenderColorUtils.kt` | 249 | 配色派生 |
| `SmsDatabaseTester.kt` | 244 | 数据库测试工具 |
| `DsimCryptoUtils.kt` | 226 | 加解密（V2 AEAD） |
| `database/DsimDao.kt` | 224 | Room DAO（40 方法） |
| `database/DsimDatabase.kt` | 201 | Room 实例与 4 次迁移 |
| `OtpConversationUtils.kt` | 191 | 验证码识别与提取 |
| `DSimHardwareTester.kt` | 175 | 硬件自检 |
| `SmsReceiver.kt` | 160 | 短信采集 |
| `SenderColorPreferenceStore.kt` | 156 | 配色偏好 |
| `NotificationUtils.kt` | 144 | 通知渠道与构建 |
| `SystemSmsStore.kt` | 131 | 系统短信库读写 |
| `OtpRulesStore.kt` | 117 | 验证码规则 |
| `SmsSourceResolver.kt` | 113 | 短信来源解析 |
| `ConversationProfileStore.kt` | 106 | 会话档案 |
| `UsageModeManager.kt` | 106 | 使用模式 |
| `SmsTagParserUtils.kt` | 89 | 来源标签渲染 |
| `database/DsimEntities.kt` | 86 | 4 个实体 |
| `SmsSourceRepairManager.kt` | 72 | 借卡场景修复 |
| `CloudSettingsManager.kt` | 72 | 云端配置读写 |
| `OtpCopyReceiver.kt` | 65 | 验证码复制广播 |
| `DefaultSmsManager.kt` | 64 | 默认短信角色查询/申请 |
| `CorePermissionHelper.kt` | 56 | 权限清单与缺失检测 |
| `SimConfigIdentityManager.kt` | 47 | SIM 配置身份同步 |
| `DeviceNameManager.kt` | 40 | 设备名 |
| `BootReceiver.kt` | 36 | 开机自启 |
| `OnboardingStateStore.kt` | 34 | 引导状态 |
| `SetupChecklistManager.kt` | 34 | 配置完备度检查 |
| `ConversationSenderStore.kt` | 29 | 会话首选发件卡 |
| `GlobalNumberUtils.kt` | 26 | E.164 标准化 |
| `DsimNavigation.kt` | 21 | 返回收件箱导航 |
| `OtpCopyPreferenceStore.kt` | 21 | 复制提示偏好 |
| `ComposeSmsActivity.kt` | 16 | ✗ 占位 |
| `MmsReceiver.kt` | 15 | ✗ 占位 |
| `HeadlessSmsSendService.kt` | 14 | ✗ 占位 |
| `SyncPayload.kt` | 12 | 同步载荷 |
| `SendCmdPayload.kt` | 10 | ⚠ 死代码 |
| `SmsReceivedReceiver.kt` | 4 | `SmsReceiver` 空壳子类 |

### 20.2 资源清单

| 目录 | 文件数 | 说明 |
|---|---|---|
| `res/layout` | 19 | 见 12.3 |
| `res/drawable` | 29 | 23 个 `bg_*` 自定义背景 + 6 个 `ic_*` 矢量图标 |
| `res/drawable-nodpi` | 6 | 预置头像 PNG |
| `res/menu` | 1 | `menu_sms_list.xml` |
| `res/values` | 3 | `colors.xml`（仅黑/白）、`strings.xml`、`themes.xml` |
| `res/values-night` | 1 | `themes.xml`，仅重复 `Base.Theme.DSIM` |
| `res/xml` | 2 | `backup_rules.xml`、`data_extraction_rules.xml`（均为模板） |
| `res/mipmap-anydpi-v26` | 2 | 自适应图标 |

主题基类为 `Theme.Material3.DayNight.NoActionBar`。界面色值主要硬编码在 Kotlin 代码中，`colors.xml` 仅定义黑白两色。

---

## 21. 附录：核心常量总表

| 常量 | 值 | 位置 |
|---|---|---|
| `NOTIFICATION_ID` | 888 | MqttSyncService |
| `CHANNEL_ID` | `dsim_sync_channel` | MqttSyncService |
| `EXECUTION_NOTIFICATION_ID` | 1088 | HistoryQueueNotificationHelper |
| `REQUESTER_NOTIFICATION_ID` | 1089 | HistoryQueueNotificationHelper |
| `CHANNEL_ID` | `dsim_history_import_queue` | HistoryQueueNotificationHelper |
| `CHANNEL_LOUD` | `dsim_loud_v1` | NotificationUtils |
| `CHANNEL_SILENT` | `dsim_silent_v1` | NotificationUtils |
| `DEFAULT_BROKER` | `tcp://broker.emqx.io:1883` | CloudSettingsManager |
| `AEAD_MAGIC` | `DSM3` | DsimCryptoUtils |
| `appSalt` | `"dSIM/v3/master-key"`（固定） | DsimCryptoUtils |
| `KEY_CACHE_LIMIT` | 4 个口令 | DsimCryptoUtils |
| `HeartbeatPolicy.TICK_MS` / `MAX_SILENCE_MS` | 30,000 / 120,000 毫秒 | HeartbeatPolicy |
| `ReconnectPolicy.BASE_DELAY_MS` / `MAX_DELAY_MS` | 5,000 / 300,000 毫秒 | ReconnectPolicy（服务自管重连退避；Paho automaticReconnect 关闭） |
| `SendCostPolicy.DEFAULT_DAILY_LIMIT` / `MAX_LIMIT` | 50 / 10,000 条 | SendCostPolicy（执行端每自然日代发计费条数上限，见 AGENTS C21） |
| `AEAD_IV_SIZE` | 12 字节 | DsimCryptoUtils |
| `AEAD_TAG_BITS` | 128 | DsimCryptoUtils |
| `AEAD_KEY_BITS` | 256 | DsimCryptoUtils |
| `PBKDF2_ROUNDS` | 120,000 | DsimCryptoUtils |
| `ENCRYPTION_ERROR` | `"ENCRYPTION_ERROR"` | DsimCryptoUtils（哨兵值） |
| `TOTAL_STEPS` | 6 | OnboardingActivity |
| `ANTI_FRAUD_COUNTDOWN_MS` | 8,000 毫秒 | OnboardingActivity |
| `ONLINE_TIMEOUT_MS` | 300,000 毫秒（5 分钟；OFFLINE/LWT 会提前置离线） | DeviceDirectoryManager |
| `HISTORY_MIN_INTERVAL_MS` | 60,000 毫秒 | DeviceDirectoryManager |
| `DEVICE_SNAPSHOT_INTERVAL_MS` | 20,000 毫秒 | MqttSyncService |
| `SNAPSHOT_BROADCAST_MIN_INTERVAL_MS` | 1,200 毫秒 | 两处 |
| `HISTORY_ACK_TIMEOUT_MS` | 20,000 毫秒 | HistorySyncQueueManager |
| 去重窗口 | 120,000 毫秒 | SystemSmsStore / SystemSmsHistoryImporter |

---

**文档结束**

> 本文档的所有架构描述、字段清单与常量值均对照 2026-09-17 的源码核实。若与代码不一致，以代码为准，并请同步修订本文档。
