# dSIM 代码质量优化工作单

> **2026-09-18 补充**：先读 `FIXES_2026-09-18.md`。本工作单的历史度量和优先级不代表已修复后的现状；发送幂等、回调（Room v6）与入站同步发件箱、持久会话（Room v7，W19）已另行实现，其余条目仍需逐项验证。
>
> **产品方向（2026-09-18 用户确认）**：运营商发短信按条收费，产品核心是"入站短信经互联网同步"。因此 **W1（默认短信组件）降为 P2**，§5 的批次顺序以本注记后的"修订版"为准。

> **本文件是一份可直接交给新会话执行的工作单。** 写作目的是让执行者不需要本轮对话的任何上下文。
>
> | 项 | 内容 |
> |---|---|
> | 基线 | commit `0f6b445`，55 个 Kotlin 文件 / 14,245 行 / 503 个函数 |
> | 必读顺序 | ① `AGENTS.md`（硬性约束 C1–C10 与文件树）② `TESTING.md`（回归门槛与环境）③ 本文档 |
> | 回归门槛 | 每个条目完成必须 `./gradlew :app:compileDebugKotlin` 通过；涉及采集/同步/加密的条目再跑 `scripts/verify-dsim.sh`（12/12 PASS，退出码 0） |
> | 环境 | 两台模拟器 `dSIM_B`(5554) / `dSIM_C`(5556)，`bash scripts/emu-up.sh 2` 启动、`bash scripts/emu-down.sh` 关闭 |
> | 提交 | 每完成一个工作项一个 commit，conventional commits（`refactor:` / `feat:` / `chore:` / `test:`） |

---

## 0. 执行守则

1. **先读 `AGENTS.md` 的硬性约束 C1–C10**，再动手。其中最容易踩的是 C1（改实体必须递增版本并补 Migration）、C3（加密格式）、C5（默认短信应用必须组件）、C6（双接收器互斥）。
2. **一个条目一个 commit**。不要把多个条目混在一个 commit 里，否则回归定位困难。
3. **编译是最低门槛**：`export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot"` 后跑
   `./gradlew :app:compileDebugKotlin -Dorg.gradle.java.installations.paths="C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"`。
4. **UI 自动化的四个坑已由 `scripts/uiauto.sh` 处理**（MSYS 路径改写、软键盘遮挡、控制台 GBK、系统 ANR 弹窗），
   但不要用 `set -e` 写 UI 驱动脚本——UI 步骤返回非零是常态。
5. **不要预先写"兼容旧版本"的代码**。项目无发布、无用户，兼容分支是死代码（V1 加密兼容已因此删除，见 `f906653`）。
6. 遇到"看起来该有却没有"的配置/文件，先查 `TESTING.md` 的已知限制，再查 `.planning/2026-09-17-untitled-9566bb32/findings.md`。

---

## 1. 度量基线（2026-09-18 实测，改动后请更新本表）

| 指标 | 基线值 | 说明 |
|---|---|---|
| 源文件 / 行数 | 55 个 / 14,245 行（2026-09-18 晚：59 个 / ~14,700 行） | `app/src/main/java` |
| 函数总数 | 503 | 按 `^    (private\|internal\|open\|)(suspend )?fun` 统计 |
| 单包平铺 | `com.example.dsim` 下 52 个文件 | 仅 `database/` 单独成包 |
| `object` 单例 | 33 个 | 即全局可变状态 |
| DI / ViewModel | 无 / 无 | 全部 `findViewById`，无 ViewBinding |
| 兜底 catch | 仍有多处，但 `catch(_: Exception){}` 静默吞掉 = 0（W6） | 每处至少 `Log.d/w` 并注明为何可忽略 |
| `printStackTrace` | 0（W6 前 4 处） | |
| 手工拼 JSON | 0（W5 前 15 处） | 协议全部经 `MqttPayloadCodec`；接收端 `when (inbound)` |
| 哨兵字符串比较 | 0（W5 前 11 处） | `encryptOrNull()` 返回可空，哨兵已删 |
| `R.string.*` 使用 | **0 次** | `strings.xml` 仅 `app_name` 一条 |
| DAO 死方法 | 0（批次 E 前 4 个） | W10 已完成 |
| 有效测试 | 0（2026-09-18 晚：JVM 39 + 仪器 10） | 原仅有 2 个工程模板用例；现有 SendCommandPolicyTest / CloudConfigRestoreTest / OutboxPolicyTest / DsimCryptoUtilsTest / CloudTopicsTest / HeartbeatPolicyTest / ReconnectPolicyTest / SendCostPolicyTest / SendCommandLedgerTest / SyncOutboxDaoTest |
| 非空断言 `!!` | 1 处 | 这一项是好的 |

---

## 2. 工作项总览

| ID | 优先级 | 标题 | 工作量 | 依赖 |
|---|---|---|---|---|
| W1 | ~~P0~~ **P2**（已降级） | 补齐默认短信应用的三个必备组件 | M | — |
| W2 | **P0** | `deviceId` 改为应用自管持久标识 | S | — |
| W3 | **P0** | 历史同步状态机与文案解耦 | S | — |
| W4 | P1 | 拆分 `MqttSyncService` — 第 1 步 ✅（批次 E：入站/出站抽出，1102 → 568 行）；第 2 步 ✅（批次 F2：重连状态收口到服务 `scheduleReconnect` + `ReconnectPolicy` + 网络回调，AGENTS C20）；独立 `ConnectionManager` 类暂不抽，等第 3 步有真实需求再做 | L | — |
| W5 | P1 | ✅ 协议消息数据类化 + 去哨兵字符串（批次 E） | M | — |
| W6 | P1 | ✅ 错误处理与可观测性（批次 E：静默 catch 清零、outbox 失败原因进通知） | M | — |
| W7 | P1 | ✅（批次 E） 并发与生命周期治理 | S/M | 与 W4 绑定 |
| W8 | P1 | `mappingKey` 唯一性：补诊断日志（结构改造需人拍板） | S | — |
| W9 | P2 | 包结构分层 | M | **放最后做** |
| W10 | P2 | DAO 清理（4 个死方法 + 1 对重复方法） | S | — |
| W11 | P2 | 死代码清理（5 个对象） | S | — |
| W12 | P2 | 文案进 `strings.xml` | L | 建议在 W4/W5 后 |
| W13 | P2 | 颜色进 `colors.xml` | M | — |
| W14 | P3 | 口令存储加固（EncryptedSharedPreferences） | M | — |
| W15 | **P3 ✅ 已完成** | debug 工具不进 release（清单分变体 + `BuildConfig.DEBUG`） | S | — |
| W16 | P3 | 发布工程化（签名/混淆/版本号） | M | — |
| W17 | P3 | 通知 id 稳定化 | S | — |
| W18 | P3 | 测试补齐（4 类纯逻辑） | M | W5 后更好做 |
| W19 | **P0 ✅ 已完成** | 入站短信可靠同步：`sync_outbox` + 持久 MQTT 会话 | M | — |
| W20 | **P0 ✅ 已完成** | 耗电：口令→主密钥一次派生（新魔数 DSM3）、按 topic 后缀跳过自身回声、心跳按变化发布 + LWT OFFLINE | M | 详见 `FIXES_2026-09-18.md` 第三批 |
| W21 | P1 | ✅（批次 E）发送回执 / 历史 ACK 也走 `SyncOutbox`（W19 未覆盖） | S | W19 |

---

## 3. 工作项详情

### W1（P0）补齐默认短信应用的三个必备组件

**问题**：应用若要成为默认短信应用，系统要求实现四个组件。`SmsReceiver` 已实现，但其余三个是占位：

| 文件 | 现状 | 后果 |
|---|---|---|
| `ComposeSmsActivity.kt`（16 行） | `onCreate` 里只有注释，随即 `finish()` | 其他应用通过 `sms:`/`smsto:` Intent 唤起撰写界面时直接退出 |
| `HeadlessSmsSendService.kt`（14 行） | 仅 `onBind` 返回 null | 系统的"通过短信回复"（`RESPOND_VIA_MESSAGE`）失效 |
| `MmsReceiver.kt`（15 行） | `onReceive` 为空 | 彩信无法接收 |

模拟器上用 `adb shell cmd role add-role-holder` 能**强制**授予角色。**修正（与 `FIXES_2026-09-18.md` 对齐）**：不能仅凭方法体是占位就断言真机系统设置必然拒绝授予角色——角色资格看的是 Manifest 组件声明，业务能力缺失是另一回事，两者需分别验证。

**改法**：

1. `ComposeSmsActivity`：从 Intent 取号码（`intent.data.schemeSpecificPart`，scheme 为 `smsto:`/`sms:`）与正文（`EXTRA_TEXT`），展示收件人 + 输入框 + 发送按钮，用 `SmsManager.sendTextMessage` 发送，成功后 `finish()`。可复用 `SmsChatActivity` 的发送与发件卡选择逻辑。
2. `HeadlessSmsSendService`：把 `Service` 改为 `IntentService`（或在 `onStartCommand` 处理后 `stopSelf()`），从 Intent 取号码与正文，`SmsManager` 发送。**不要弹 UI**——系统约定这是静默通道。
3. `MmsReceiver`：解析 `WAP_PUSH_DELIVER` 的 PDU 并写入系统彩信库。若短期做不完，至少记录日志并显式标注"彩信未支持"，**不要留空函数**（否则排查时误以为已实现）。

**验收**：真机（或模拟器）上走系统设置的默认应用切换，能授予角色；`sms:` Intent 能打开撰写界面；`RESPOND_VIA_MESSAGE` 能发出。

**风险**：这是系统级契约。**必须先在可恢复的模拟器上验证**，写错会导致设备收不到任何短信（现有 `SmsReceiver` 也会失效）。

---

### W2（P0）`deviceId` 改为应用自管持久标识

**问题**：`HardwareProbeUtils.getDeviceId()` 读 `Settings.Secure.ANDROID_ID`。Android 8+ 该值按 **(应用签名密钥, 用户, 设备)** 三元组隔离，因此 **debug 与 release 构建在同一台设备上会得到不同的 `deviceId`**——切换构建等于换了一台设备。

**影响面**：
- `sms_messages.deviceId` 历史记录与新值不再匹配；
- `DeviceProfile.isLocalDevice` 判定漂移；
- `dao.findPreferredLocalMappingKeyForAddress`（按 deviceId 过滤）查不到历史；
- `MqttSyncService.handleIncomingMessage` 第 ⑫ 步的自身回声过滤失效。

**改法**：首次启动生成一个 UUID（**去掉连字符**，见下），存入独立 prefs（建议新文件 `dSIM_IDENTITY`），`getDeviceId()` 改为读它。

**必须验证的坑**：无 Root 模式的 mappingKey 会把 deviceId 嵌进键里——`DEV_<deviceId>_SUBID_1` 与 `..._UNBOUND`。`HardwareProbeUtils.parseDeviceIdFromMappingKey` 按 `_SUBID_`/`_SLOT_` 分割，UUID 的连字符不影响分割，但**建议去掉连字符后再用**，避免键里出现 `-`。

**验收修正**：普通 prefs UUID 仅保证同一安装数据内稳定；卸载重装可变化，不保证跨签名一致。若要求可恢复的逻辑设备身份，必须先设计显式身份恢复/重新配对协议，并防止跨设备恢复备份克隆身份。SEND_CMD 往返仍需验证。

**数据迁移**：现阶段无用户，直接切换即可，不做迁移。若将来有用户，再补"旧值→新值"的一次性映射。

---

### W3（P0）历史同步状态机与文案解耦

**问题**：`HistorySyncQueueManager.updateFromImportState` 通过"阶段文案是否含『完成』/『失败』"判断终态（中文子串匹配）。改一条提示文案就会改变状态机行为。

**改法**：`SystemHistoryImportService` 的进度回调里增加一个**结构化字段**（枚举或整型 stage code），状态机改读它；中文文案仅用于展示。枚举建议：`IDLE / SCANNING / IMPORTING / PAUSED / COMPLETED / FAILED`，与现有 6 个状态常量一一对应。

**验收**：单测覆盖 6 个状态的迁移路径（见 W18）；临时改一处提示文案，状态机行为不变。

---

### W4（P1）拆分 `MqttSyncService` — 第 1 步 ✅ 已完成（批次 E）

**已做**：`MqttSyncService` 1102 → 568 行。新增三个包内 `internal` 类：
- `CloudSession`：当前组的 broker / topic / password（`@Volatile`），服务写、其余两类读，取代三个 `current*` 字段；
- `MqttPublisher`：全部出站控制消息（`publishHistorySyncAck` / `publishSendCommandResult` / `publishPing` / `publishDeviceSnapshot` / `publishOfflineBestEffort` / `buildOfflineJson`）与心跳指纹状态 `heartbeatState`（`resetHeartbeat()` 在连接成功时调用）。C13 / C14 的实现点现在集中在这里；
- `MqttInboundHandler`：`handleIncomingMessage` 的 `when (inbound)` 分发和 7 个处理器（`handleHistorySyncAck` / `handleHistoryQueueBatch` / `handleSendCommand` / `handleSendCommandResult` / `syncRemoteSimsFromPong` / `buildRemoteShadowConfig` / SMS 落库）。回复经 `publisher`。

有意**没做**：按 action 拆成 6 个独立 handler 类（W5 之后 `when` 穷举已给了编译期保障，再拆一层收益小）；连接管理 / 静态量 / 通知文案仍在服务里（与 W7 绑定）。方法体是原样搬迁（仅 `this` → `context`、字段 → `session.*`），行为未变。

**原问题**：1088 行、21 个私有方法，一个类同时承担：连接生命周期、通知文案渲染（5 个 `build*Message`）、7 个协议处理器、短信实际发送、SIM 解析、JSON 辅助。

**改法（分两步，不要一步到位）**：

1. **先抽协议处理器**。`handleIncomingMessage` 的 16 步分支改为按 action 分发到独立类：
   - `SendCommandHandler` / `SendCommandResultHandler` / `HistorySyncAckHandler` / `HistoryQueueBatchHandler` / `PingPongHandler` / `SmsPayloadHandler`
   - 每个处理器构造注入 `context` / `dao` / 一个 `CloudPublisher` 接口（封装 publish），**做成纯函数式的**（输入 JSON + 依赖，输出副作用），这样才能进单测
2. **再抽连接管理**：`connectAndSubscribe`、断开/重连、心跳 → `MqttConnectionManager`
3. 通知文案的 5 个 `build*Message` 集中到 `CloudNotificationCopy`

**约束**：本条**不改** `globalMqttClient` / `staticTopic` / `manualDisconnectInCurrentSession` 这些全局静态量的语义（那是 W7 的事），避免一次改动引入两个变量。分发顺序必须保持 `handleIncomingMessage` 现有的 ①–⑯ 顺序（见架构说明书 §9.3），顺序错了消息会被上游分支吞掉。

**验收**：编译 + `verify-dsim.sh` 12/12 + SEND_CMD 往返实测（见 `TESTING.md` §5.2）。

---

### W5（P1）协议消息数据类化 + 去哨兵字符串 — ✅ 已完成（批次 E）

**结果**：`MqttProtocol.kt` 定义 `sealed interface MqttInbound`（`SendCmd` / `SendCmdResult` / `HistorySyncAckMsg` / `HistoryQueueBatch` / `Ping` / `Pong` / `Offline` / `SmsSync`）与 `MqttPayloadCodec.encode/decode/senderId`（Gson，宽松解析，永不抛出）；线格式字段名不变，与 W5 前构建双向兼容（有旧格式 PONG 解析单测 + 外部 paho 探针实测）。`DsimCryptoUtils.encryptMessage` 改为 `encryptOrNull(): String?`，`ENCRYPTION_ERROR` 常量删除。`SendCmdPayload.kt` 被 `SendCmd` 取代后删除。`org.json` 仅剩 `OtpRulesStore`（本地 prefs，不属协议）。

**原问题**：
- 15 处手工 `JSONObject()` 拼 payload，字段名散落各处，拼错无编译期检查；
- 接收端是 16 步 if-else 逐 action 判断；
- 11 处硬编码比较 `"ENCRYPTION_ERROR"` 字符串；
- `SendCmdPayload.kt` 这个 data class 从未被使用（死代码，见 W11）。

**改法**：

1. 定义 `sealed interface MqttInbound`，每个动作一个 data class：
   `SendCmd` / `SendCmdResult` / `Ping` / `Pong` / `HistoryQueueBatch` / `HistorySyncAck` / `SmsSync(SyncPayload)`
2. 一个 `MqttPayloadCodec`（用 Gson）统一 `decode(json): MqttInbound?` 与各 `encode(...)`，替换 15 处手工拼装
3. `DsimCryptoUtils` 增加返回 `sealed class CryptoResult { Ok(ciphertext) / Failure(cause) }` 的新方法，
   **保留** `ENCRYPTION_ERROR` 哨兵与旧方法签名以兼容迁移期，逐步迁移 11 处调用点后删除旧方法
4. `handleIncomingMessage` 的分支改为 `when (inbound)`

**验收**：编译 + 12/12 + SEND_CMD 往返；`grep -c '"ENCRYPTION_ERROR"'` 降为 0（const 定义保留到迁移完成）。

**注意**：实际实现中 `SendCmdPayload.kt` 缺 `deviceId`/`deviceName` 字段，直接由 `SendCmd` 取代并删除。

---

### W6（P1）错误处理与可观测性 — ✅ 已完成（批次 E）

**已做**：`catch (_: Exception)` 与 `printStackTrace` 归零（18 处；`HardwareProbeUtils` 的 OEM 探测回退用 `Log.d` + 注释，真实失败用 `Log.w(e)`）。`SyncOutbox.FlushResult` 新增 `lastError`（`not_connected` / `encrypt_failed` / 异常摘要 ≤40 字）；`MqttSyncService.flushOutbox` 在 `failed > 0 && remaining > 0` 时通知栏显示「N 条短信待同步，上次发送失败（原因），将自动重试」，下次成功冲刷后恢复常规文案。窄类型 `catch (_: JsonSyntaxException)` 等（`MqttProtocol` / `DsimCryptoUtils` / `PrivacyModeManager`）属于预期分支，保留。

**验证说明**：模拟器断网测试中，Paho 先于 outbox 察觉连接丢失并走自动重连（`Connection lost (32109)` → `已恢复连接`），入站短信在重连后 `sent=1`；「已连接但 publish 抛异常」这条分支没有在真机上触发到，仅由代码路径与 `OutboxPolicyTest` 覆盖。

**原问题**：63 处兜底 `catch(...Exception...)`、4 处 `printStackTrace`、多处 `catch(_: Exception){}` 静默吞掉。失败退化为静默 no-op——例如 `SmsReceiver.publishIncomingSmsToCloud` 发布失败时用户完全无感知。

**改法（分级，不要一刀切）**：

1. **先消灭"静默吞掉"**：`grep -n "catch (_: Exception)"` 逐处处理，至少 `Log.w(tag, "...", e)`
2. **影响数据一致性的路径改为显式返回**（Result 或密封类）
3. **云端发布失败要可见**：通知栏已有云端状态文案，可加"最近一次发送失败"状态
4. 纯 UI/可选路径保留 catch 但补日志

**验收**：`grep -c "catch (_: Exception)"` 为 0；模拟一条发布失败（断网发送），能在日志与通知状态中看到。

---

### W7（P1）并发与生命周期治理 — ✅ 已完成（批次 E）

**已做**：
1. `serviceScope.cancel()` 已在 `onDestroy`（W4 时补上），本次核对 8 个 `launch` 点全部挂在 `serviceScope`。
2. `globalMqttClient` 改为 `private @Volatile`，服务外 **0** 处直接触碰；`staticTopic` 删除，只剩 `staticConfig`（订阅成功时写、三条 teardown 路径清空）。外部发布统一走 `MqttSyncService.publishToGroup(context, json, topic, password)`——校验 canUseCloud、连接、**topic 与 password 都等于当前订阅组**，再加密、`publishTopic()` 发布（C13 不变），失败返回 false 并记日志。迁移了 7 个直发点：`DeviceManagerActivity.sendRadarPing`、`SmsChatActivity.publishSendCommand`、`HistorySyncQueueManager`（队列批次）、`SystemSmsHistoryImporter.CloudPublisher`、`MainActivity` 调试页两处；`publishCurrentGroup(config)` 保留为薄包装。只读方替换为 `isConnected()` / 新增 `hasClient()`。同时删除无调用者的 `publishEncryptedSms` 与 `"ACTION_PUBLISH_MSG"` 分支（后者会绕过加密校验直发任意主题）。`MqttPublisher` 改为通过构造参数 `client: () -> MqttClient?` 取客户端，不再反向引用服务静态量。
3. `DsimCryptoUtils` 各入口早已标注 `@WorkerThread` 并有 KDoc；`publishToGroup` 同样标注（内部会加密）。

**未做**：`manualDisconnectInCurrentSession` 仍是 companion 可变量——它必须跨 service 实例存活（START_STICKY 重启后仍保持"手动断开"），改成实例字段会破坏该语义；仅由服务线程写、`@Volatile` 读，够用。

**原问题**（三处，均已实测确认）：

1. `MqttSyncService` 的 `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`（第 40 行）**从不取消**——`onDestroy` 只取消了心跳 Job，8 个 `launch` 点可活过 Service 销毁
2. `globalMqttClient` / `staticTopic` / `manualDisconnectInCurrentSession` 是 companion object 可变量，被 Activity/Receiver/Service 同时读写且无同步；`publishEncryptedSms` 里的 `staticTopic != topic` 守卫正是二者可能失步的自证
3. `encryptMessage` 是非 `suspend` 的阻塞调用（PBKDF2 12 万轮，中端机 0.2~0.5 秒）。当前 12 个调用点恰好都在 IO 线程，但签名上没有任何保护——未来谁把它放到主线程就是 ANR

**改法**：

1. `onDestroy` 补 `serviceScope.cancel()`；同时确认所有 `launch` 点都在 scope 上
2. `encryptMessage` / `decryptMessage` 标注 `@WorkerThread` 并在 KDoc 写明"阻塞约 0.2~0.5 秒，禁止主线程调用"；或改为 `suspend fun` + `withContext(Dispatchers.Default)`（后者更彻底，但 12 个调用点都要改）
3. 全局静态量在 W4 拆分时一并收敛（绑定 W4）

**验收**：编译；断开云端 → 重连 → 收发短信，行为不变。

---

### W8（P1）`mappingKey` 唯一性：先补诊断日志，结构改造需人拍板

**问题**：Root 模式下 `mappingKey = ICCID_<iccid>` **不含设备维度**，而它是 `sim_card_configs` 的主键。真实卡 ICCID 全球唯一所以不暴露；但**模拟器各实例共用同一 ICCID** 时，两端键相同 → 对端卡的 `REMOTE_SHADOW` 不被创建（`MqttSyncService.kt:989-992` 直接 `continue` 且**无任何日志**）→ 跨设备发信无法触发。

**已在测试环境根治**：`test-fixtures/icc/` 给每台模拟器不同的 ICCID（见 `TESTING.md` §5.1）。

**本次要做**：989-992 的 `continue` **补一条 `Log.w`**（带上 mappingKey 与远端 deviceId）——本次排查因此绕了远路，静默跳过是可观测性缺陷。

**不要做**：给键加设备维度。这会破坏 ROOT_ICCID 模式"卡换机仍能识别"的既有语义。若产品确实要打破该语义，需要先明确产品决策再动，且涉及主键迁移。

---

### W9（P2）包结构分层

**问题**：52 个文件平铺在 `com.example.dsim`，只有 `database/` 单独成包。

**改法**：按 `sync/`、`sms/`、`ui/`、`settings/`、`otp/`、`privacy/`、`identity/` 分包。**纯移动，不改逻辑**，一次一个包、一个 commit，用 IDE 的 Move 重构（不要手工改 import）。

**注意**：`AndroidManifest.xml` 里的组件类名要同步改（`.sync.MqttSyncService` 等），`verify-dsim.sh` / `onboard-device.sh` 里的包名引用也要同步。

**放最后做**：它是纯 churn，放在大重构之后做可避免与其余条目冲突。

---

### W10（P2）DAO 清理 — ✅ 已完成（批次 E）

**问题**：`DsimDao.kt` 40 个方法中 4 个零调用：`getRecentConversations`、`getMessagesByAddress`、`getMessagesByAddressList`、`countSimilarLocalMessage`。另有 `getAllSimConfigs` 与 `getAllSimConfigsForUi` 的 SQL **完全相同**（后者有 3 处调用：`DeviceManagerActivity:162`、`OtpConversationActivity:76`、`SmsChatActivity:111`）。

**改法**：删 4 个死方法；两个重复方法合并为一个（保留 `getAllSimConfigs`，改 3 处调用点）。

**验收**：编译 + `grep -rn "getRecentConversations\|getMessagesByAddressList\|countSimilarLocalMessage\|getAllSimConfigsForUi"` 为 0。

---

### W11（P2）死代码清理 — ✅ 已完成（批次 E，`SendCmdPayload.kt` 按计划保留给 W5）

| 对象 | 说明 |
|---|---|
| `DsimMqttEngine.kt`（94 行） | 早期 EMQX 引擎，仅含 `SYNC_SMS` 旧协议 |
| `DsimNetworkEngine.kt`（65 行） | 早期网络引擎 |
| `SendCmdPayload.kt`（10 行） | W5 时由 `SendCmd` 取代，已删除 |
| `res/layout/item_sms.xml` | 无任何引用 |
| `gradle/libs.versions.toml` | 版本目录未生效，AGP 9.1.0 与实际 8.7.3 冲突；确认 `build.gradle.kts` 无 `libs.` 引用后删除 |

注意：`DsimCryptoUtils` 精简后（164 行）不再含 V1/CBC 残留。

---

### W12（P2）文案进 `strings.xml`

**现状**：`R.string.*` 使用 **0 次**，`strings.xml` 仅 `app_name`。全部中文文案硬编码在 Kotlin。

**改法**：按屏幕分批（收件箱 → 聊天 → 设置 → 设备中心 → 引导 → 验证码），**每批一个 commit**。不要一次性全改——改动面覆盖所有 UI，回归风险高。

**附带收益**：这是 W3（状态机解耦）的前提之一——文案进了资源，逻辑才能只依赖枚举。

---

### W13（P2）颜色资源化

**现状**：`colors.xml` 仅黑/白两条，界面色值以 `Color.parseColor("#...")` 硬编码在 Kotlin（`SenderColorPreferenceStore` 的 8 色预设是**数据**不是主题色，保留在代码里是合理的）。

**改法**：把 UI 主题色（状态色、背景、边框）抽到 `colors.xml` 或主题属性；`SenderColorUtils` 的派生逻辑保留。

---

### W14（P3）口令存储加固

**问题**：`dSIM_UI_PREFS.PASSWORD` / `BROKER` / `TOPIC` 明文存储，未用 `EncryptedSharedPreferences`，也未用 SQLCipher。

**改法**：引入 `androidx.security:security-crypto`，首次读取时做一次"明文 → 加密"迁移并删除旧键。

**注意**：EncryptedSharedPreferences 在部分厂商 ROM 上有已知崩溃报告，需在目标设备实测；且 `allowBackup="true"` 的备份规则（`res/xml/backup_rules.xml` 目前是模板）应排除该 prefs 与数据库。

---

### W15（P3）debug 工具不进 release ✅ 已完成（2026-09-18，批次 C）

**问题**：`SettingsActivity:640` 直接 `startActivity(MainActivity)`，956 行的调试面板在任何 release 构建里都可达。

**已实施**：`<activity .MainActivity>` 从主清单移入新增的 `app/src/debug/AndroidManifest.xml`（清单合并只在 debug 变体加入），release 清单不再声明该组件；入口按钮用 `BuildConfig.DEBUG` 包裹并在非 debug 下 `GONE`。为此在 `app/build.gradle.kts` 打开 `buildFeatures { buildConfig = true }`（AGP 8 默认关闭）。

**验收**：`aapt2 dump xmltree` 对 release APK 的 `MainActivity` 引用数为 0，debug APK 为 2；debug 包里「设置 → 测试功能」仍可进入面板（模拟器实测）。

---

### W16（P3）发布工程化

| 项 | 现状 | 要做 |
|---|---|---|
| 签名 | 无 `signingConfigs` | 增加 release signingConfig（密钥**不入库**，用 `local.properties` 或环境变量） |
| 混淆 | `isMinifyEnabled = false` | 开启并补 proguard 规则：Paho、Gson（`SyncPayload` 等需 `@Keep` 或规则）、Room |
| 版本 | `versionCode=1` / `1.0` | 迭代 |
| CI | 无 | 至少加一个"编译 + verify-dsim.sh"的脚本化流程 |

---

### W17（P3）通知 id 稳定化

**问题**：`NotificationUtils.kt:109` 用 `(System.currentTimeMillis() % 10000).toInt()` 作为通知 id，1 万个槽位，连续短信会互相覆盖。

**改法**：按 `address.hashCode()` 派生稳定 id（同一会话覆盖、不同会话并存），或维护一个自增的会话级 id 映射。

---

### W18（P3）测试补齐（4 类纯逻辑，不依赖模拟器 modem）

| 目标 | 覆盖点 |
|---|---|
| `DsimCryptoUtils` | 加解密往返；**篡改密文必须解密失败**（GCM 认证的价值所在）；**错误口令必须失败**；超短输入返回 null |
| `HistorySyncQueueManager` | 6 个状态的迁移路径（依赖 W3 的结构化字段） |
| `PrivacyModeManager` | 8 种号码变体匹配、掩码位数规则、`106` 开头不处理 |
| `SmsSourceResolver` | 三级回退链与 4 种 `_UNBOUND` 键构造 |
| `GlobalNumberUtils` | E.164 与非法号码回退 |

**注意**：部分类是 `object` 单例并读取 Context，需要小幅重构（把依赖改成参数）才能测——这本身就是改善。

---

### W21（P1）发送回执 / 历史 ACK 走 `SyncOutbox` — ✅ 已完成（批次 E）

**问题**：W19 只让入站短信走发件箱；`SEND_CMD_RESULT`（执行端→请求端的发送结果）和 `HISTORY_SYNC_ACK`（接收端→导入端的确认）仍是活连接直发。断连时丢失 → 请求端消息永远"发送中"、导入端队列每行等 20 s 超时后停住。

**已做**：`SyncOutbox` 新增 `enqueueControl(kind, uuid, discriminator, json, group)` + `requestFlush()`；行键 `controlKey = "$kind:$uuid:$discriminator"`（表内 `uuid` 唯一），discriminator 区分同一 UUID 的不同结论（`PENDING`→`SENT` 两次都要送达；`ok:`/`ok:already_exists` 是不同回答），同一结论的回调重放则合并到未发出的行上。迁移：`OutgoingSmsDispatcher.publishOutcome`（结果 + 已发短信的 silentSync 同步，均按记录自带的 `groupFingerprint` 入队）、`MqttPublisher.publishHistorySyncAck` / `publishSendCommandResult`（变为 `suspend`，只负责组装 JSON 入队）。`SmsReceiver.requestOutboxFlush` 收敛为 `SyncOutbox.requestFlush`。删除已无调用者的 `publishCurrentGroup`。通知文案「N 条短信待同步」→「N 条消息待同步」。

**不进发件箱**：PING / PONG / OFFLINE / 雷达 PING / 聊天页发出的 `SEND_CMD`（用户可见失败并可重试，且 C11 的"不自动重发"语义要求发送指令不能在后台悄悄补发）。

**验证**：JVM 64/64（新增 2 个 `controlKey` / `buildControlEntry` 测试）；模拟器：历史导入行 → `HISTORY_SYNC_ACK` 2.1 s，同行重发 → `already_exists` ACK 2.3 s；`SEND_CMD` → `SEND_CMD_RESULT(SENT)` + 已发短信同步 1.9 s（`outbox[capture] sent=2`）；断网期间收到短信 → 落库，重连后 `outbox[connect] sent=1`。

---

## 4. 明确不要做的事

| # | 不要做 | 原因 |
|---|---|---|
| N1 | **不要给 `mappingKey` 加设备维度** | 会破坏 ROOT_ICCID 模式"卡换机仍能识别"的既有语义。测试环境已用 ICC Profile（`test-fixtures/icc/`）根治，无需改应用 |
| N2 | **不要预先写"兼容旧版本"的代码** | 无发布无用户，兼容分支是死代码（V1 加密兼容已因此删除） |
| N3 | **不要删 Room 迁移代码**（`MIGRATION_1_2`…`4_5`） | 无用户不代表迁移无用；删了会让任何后续升级路径直接崩溃 |
| N4 | **不要用 `-read-only` 双开同一 AVD** | 两实例共享 `ANDROID_ID` → 应用把对端当自己 |
| N5 | **不要参考 `gradle/libs.versions.toml`** | 模板残留未生效，AGP 9.1.0 与实际 8.7.3 冲突 |
| N6 | **不要为此降 compileSdk 36** | AGP 8.7.3 的"仅验证到 35"警告是已知可接受 |
| N7 | **不要把旧会话工具故障当平台约束** | 旧会话 PowerShell 空输出为历史现象；2026-09-18 AgentDock exec_command 可正常运行 PowerShell，Gradle -D 参数须整体加引号 |
| N8 | **UI 驱动脚本不要用 `set -e`** | UI 步骤返回非零是常态，会导致第一处未命中就静默退出 |
| N9 | **不要用 `adb emu sms send` 在真机上测试** | 仅模拟器支持 |
| N10 | **拉应用库不要分三次 `cat`** | 必须设备侧一次拷齐再拉（WAL + 应用持续写，见 `pull-app-db.sh`） |
| N11 | **遇到 SystemUI ANR 弹窗先消掉再操作** | 它会盖住应用界面，所有点击静默失效（`uiauto.sh` 已自动处理） |
| N12 | **不要用 `adb shell settings get secure android_id` 去核对应用日志里的 deviceId** | Android 8+ 该值按签名作用域隔离，两者本来就不相等 |

---

## 5. 分批执行建议（每个批次 ≈ 一个会话）

> **修订版（2026-09-18 晚，按"入站同步是核心"重排）**
>
> | 批次 | 条目 | 状态 |
> |---|---|---|
> | A | W19 发件箱 + 持久会话 | ✅ 已完成 |
> | B | W20 KDF 一次派生 + 按 topic 跳过回声 + 心跳放宽（PONG Toast 已顺手去掉） | ✅ 已完成 |
> | C | 一行修复合集：`SmsReceivedReceiver` 加 `BROADCAST_SMS` 权限、`.gitattributes`、W15 | ✅ 已完成 |
> | D | 安全：backup 规则排除 prefs/DB（W14 前置）、14 处 `dSIM_UI_PREFS` 直读收口到 `CloudSettingsManager`、默认 `ssl://`、base topic 通配符校验 | ✅ 已完成 |
> | E | W10 + W11 + 删 hivemq 依赖 → W5 → W4 → W6/W7 | W10/W11/hivemq ✅；W5 ✅；W4 下一步 |
> | F | W21、W2、W3、W8、W12、W13、W9、W16–W18 | |
> | 末 | W1（仅当产品决定支持运营商发送侧时） | |
>
> 下表为原始版本，保留供参考。

| 批次 | 条目 | 为什么这么排 |
|---|---|---|
| ① | W1 | 唯一的功能缺口，独立无依赖，先补齐它应用才真正可用 |
| ② | W10 + W11 | 低风险清理，热身，同时缩小后续 diff |
| ③ | W5 | 协议数据类化 —— 它是 W4 拆分的前置（拆出来的处理器要用数据类） |
| ④ | W4 | 拆 `MqttSyncService`，最大的一块 |
| ⑤ | W6 + W7 | 错误处理 + 并发治理，都在 W4 拆出的新结构上做 |
| ⑥ | W2 + W3 + W8 | 三个 P0/P1 的语义类小项 |
| ⑦ | W12 + W13 | 资源化（机械，放在逻辑重构之后避免冲突） |
| ⑧ | W9 | 包结构分层（纯 churn，最后做） |
| ⑨ | W14 + W15 + W16 + W17 | 工程化与安全收尾 |
| ⑩ | W18 | 测试补齐（可与任何批次并行） |

**每个批次的收尾动作**：`compileDebugKotlin` → 涉及采集/同步/加密时 `verify-dsim.sh`（12/12）→ 涉及发信时按 `TESTING.md` §5.2 实测 SEND_CMD → 一个 commit。

---

## 6. 已完成、勿重做

| 项 | 状态 |
|---|---|
| 加密升级为 AES-256-GCM + PBKDF2 | 已做（`d95ea67`），PBKDF2 已过 RFC 向量与 Python 交叉校验；2026-09-18 批次 B 升为 DSM3（固定盐、每口令一次派生），Python `hashlib.pbkdf2_hmac` + `AESGCM` 交叉解密通过 |
| V1 旧格式兼容 | **已移除**（`f906653`），勿再加 |
| 4 处中文乱码 | 已修（`d95ea67`） |
| 多设备 ICCID 碰撞 | 已用 ICC Profile 根治（`0f6b445`，`test-fixtures/icc/`） |
| 模拟器回归脚本 | 已就绪，`verify-dsim.sh` 12/12 PASS |
| 架构文档 | `dSIM 分布式多设备短信同步系统 技术架构说明书 V2.0.md`（依据源码核实） |
| 旧技术文档 | `dSIM_技术文档_架构师版.md` 已废弃删除，可用 `git show 382d587` 找回 |
| F8 备份规则 / F12+F16 prefs 收口 / F9 `ssl://` 默认与 topic 校验 | 已做（2026-09-18 批次 D）：备份与换机迁移改 deny-by-default；云端凭据统一走 `CloudSettingsManager`，静音标志走新增 `NotificationPreferences`；默认 broker 改 `ssl://broker.emqx.io:8883`（存量配置不迁移），保存入口拒绝含 `+`/`#` 的房间号 |

---

**文档结束。** 执行中若发现本文档与代码不符，以代码为准，并请同步更新本文件与 `AGENTS.md`。
