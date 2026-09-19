# Findings & Decisions

## Requirements
（源自仓库根《修复计划_2026-09-19.md》阶段 0，逐条可验证；计划/审查文档只读）
- T0.1 MqttProtocol.kt：java.util.Base64 → android.util.Base64.encodeToString(bytes, URL_SAFE or NO_WRAP or NO_PADDING)；验收=定向 lint NewApi 消失，及/或 API24/25 真机保存配置+发送；仅 JVM 绿不算平台证据。
- T0.2 类型化重放策略：SMS_SYNC/SEND_CMD_RESULT/HISTORY_SYNC_ACK 无时间窗、UUID 幂等 + 有界 nonce 缓存、不按龄清除；SEND_CMD 10min；PING/PONG 短窗；OFFLINE 24h；禁止 dup 旁路和全局 TTL 延长；测试须含“30 分钟前旧 SMS 仍接受入库”“过期 SEND_CMD 拒绝不执行”、守卫测试、真实 A 离线 30 分钟/B 收短信/A 重连补投。
- T0.3 三态：Committed ack；PermanentlyRejected（解密/JSON/未知 action）记录+ack；RetryableFailure（SQLite/IO）不 ack 等下个 broker 会话；取消重新抛出不 ack；checkFreshness 纯函数；markConsumed 只在处理提交后；守卫跨重连持久。替换旧 handlerExceptionStillAcks 测试；须测 SQLite 不 ack、JsonSyntax ack、失败后同 nonce 重投被接受；全量测试 + 重连 E2E。
- T0.4：git push origin main，git branch -vv 验证无 ahead；origin=https://github.com/ayueyang/dsim.git（已核实）。
- 全局回归固定命令（Git Bash）：export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot"; ./gradlew :app:compileDebugKotlin :app:cleanTestDebugUnitTest :app:testDebugUnitTest -Dorg.gradle.java.installations.paths="C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"；阶段 0 另加 TESTING.md 双模拟器 scripts/verify-dsim.sh。
- 流程：进度写 FIXES_2026-09-19.md；每任务独立 conventional commit；汇报须含真实输出，禁止“应该/理论上通过”；一次只做一个阶段，阶段报告后停下等批准。

## 本轮恢复核对（2026-09-19）
- MCP 自带 planning-with-files 已读取并用 resolve-plan-dir.ps1 + PLAN_ID/PWF_PLAN_ROOT 实际解析到本目录。只编辑现有三件套，不重建、不覆盖。
- git log --oneline -5：fc3fef2 / f3ae147 / ec0abaf / 1816213 / c561a6e；main ahead 32。
- 初始脏项：.planning/.active_plan、两个 2026-09-18 历史 task_plan；本计划目录未跟踪。另有只读用户文件、修复计划与代码质量审查目录未跟踪，均不修改/不提交。
- F1 是同族 Error 崩溃风险而非 lint 噪声；本轮用户明确授权修复。F2 仅文档化，F4 仅方案，不实施隔离/过期回执。
- 远程 task_manage ID：tsk_a28be642c1d2e2fc。

## Research Findings
- 基线 main@1816213（其本身是 docs(planning): batch I checkpoint），领先 origin/main 29；加本阶段 3 个提交后=32（git rev-list --count 实测）。
- 旧 ReplayGuard 三处缺陷：处理前消耗 nonce、按时间清除长窗消息、只同步检查不同步处理——T0.2/T0.3 已修。
- app testOptions.isReturnDefaultValues=true：JVM 测试里 android.util.Base64 是返回 null 的桩（实测触发 9 个失败，非臆测）。
- 历史 lint（T0.1修复后、F1前）实测8 errors/313 warnings；NewApi3处均在HardwareProbeUtils（API26/29），MqttProtocol=0。未测1816213修复前基线lint，不能称全部存量或阶段0前一致；F1后实测NewApi=0，剩5 errors/313 warnings，exit1。
- 双模拟器环境（现存可用）：A=emulator-5554（API 36，先已在跑）；B=emulator-5556（AgentDock 托管会话 session-99c4b1555b8a320877398034，sim_b.xml，Vulkan 关闭）。两机 app 均配置 broker tcp://10.0.2.2:1883、topic dsim/test/9f3a2b（本机 Docker 容器 dsim-mosq，eclipse-mosquitto:2，端口 1883）。logcat 曾确认双端订阅同 topic 并强制 PONG。
- 真实 30 分钟离线补投 E2E 全时间线（UTC，session-d1ce15533ea70954725eb789，exit 0）：05:21:11 B 注入在线对照 → 05:21:28 A/B 同 UUID 2f811923-f460-4069-a6e8-c4881cb3c1e5 各 1 条 → 05:21:28.503 force-stop A（持久会话留在 broker，cleanSession=false）→ 05:21:28.596 B 注入 STAGE0_OFFLINE_20260919T052101Z → 05:21:45 A=0 条、B=1 条（uuid ea5bb496-1c70-4739-a8b3-3eec0b3bc7a3）、B sync_outbox 剩 0（B 已交付 broker，由 broker 为 A 持久会话保留）→ 05:51:33.513 恢复 A，offline_seconds=1805.01、message_age_seconds=1805.01（真实墙钟≥1800）→ 05:51:57.921 A/B 各恰 1 条同 UUID → PASS_REAL_30_MINUTE_OFFLINE_DELIVERY。证明 T0.2“30 分钟旧消息仍接受入库”在真机成立（信封龄约 30.1 分钟，落在 OFFLINE 24h 窗，UUID 幂等单份）。
- B 机设置 UI 结构（真实 uiautomator dump）：连接状态下 broker/topic/密码/保存按钮全部 disabled；“断开”弹确认对话框，确认键文本“确认断开”（android:id/button1）；断开后字段才可编辑；焦点在禁用字段上按 Back 会直接退出设置页而不是收键盘。
- API 24/25 真机/模拟器运行时验证未做（无该级别设备）；T0.1 验收按用户认可的“定向 lint NewApi=0”达成，此项偏差需写入最终汇报。
- .planning/ 目录含 13 个历史命名计划 + .active_plan 指针（init 前指向 2026-09-19-inbound-manual-ack）；skill 解析规则命名计划优先于根目录 legacy 文件——因此三件套必须放命名计划目录并切指针（已做）。

## F1 续作证据
- 修复前 HEAD=fc3fef2：:app:lintDebug 实际运行 exit1 / BUILD FAILED in 32s / 8 errors, 313 warnings（分析任务 UP-TO-DATE）；XML NewApi=3，HardwareProbeUtils:238/293/294，MqttProtocol=0。不是 1816213 基线测量。
- 已用 file_edit 增加 Build 导入、API26 getSimState 守卫（旧版本只取 slot0，其余 UNKNOWN）、API29 订阅 API 守卫（旧版本继续既有 activeSubscription fallback）。无 catch(Throwable) / NewApi 抑制。
- git diff --check exit0；HardwareProbeUtils BOM=False、CR=0。修复后 compile+lint 正在执行 session-55ddc0d89b3140bffe6737d9。
- F3 将使用独立 detached worktree 构建 1816213，避免切换/覆盖当前脏工作区；同一模拟器、同一 Mosquitto、同一探针，分别报告无 kill 吞吐与 +1s force-stop 恢复，轮次用独立 UUID 前缀。

## F3 前置变更
- A已不具备配置：首次引导、无prefs/服务；并观察到System UI无响应弹窗（已选Wait，未改产品）。首轮suite exit1 No fresh subscribed log，finally CURRENT_APK_RESTORED；未发burst。
- 改用当前有配置且前台服务运行的B=emulator-5556，BROKER tcp://10.0.2.2:1883、TOPIC dsim/test/9f3a2b、BIDIRECTIONAL_SYNC/AUTO_CONNECT=true。四轮均在同一B，不用A/B跨设备比较，不需绕过UI直接写凭据。下述初始A方案由此替代。

## F3 首组测量作废并修正采样
- B四轮suite session-f0f9c3efaca5e28b35eaf6f4 exit0，均400/400；但当前nokill中间计数121→103，暴露分开复制活跃DB/WAL时checkpoint竞态。不能把该轮时间作为可信最终对比。原始result/trace保留，不删除、不冒充产品丢失。
- 仅修改测量脚本rows()：用两次主DB读取夹住WAL，要求主DB字节一致；checkpoint期间样本丢弃重采，最多5次，否则失败。两版本均用修正后同脚本全新fixture重跑。

## F3 测量口径
- 新脚本 scripts/measure-inbound-burst.py，沿用 batch I DSM3/QoS1 silent SmsSync + 独立 witness；不发运营商短信。口令仅环境变量，不落盘。
- elapsed=首条发布前 monotonic 到首次 Room 快照验证400条唯一UUID；包含DB采样/adb开销，是观测上界，不是纯handler耗时。kill模式额外包含实际force-stop/重启耗时，单列实际kill时间。dup只计本轮精确topic的dup=true日志。
- 每轮先重启并等新订阅日志、预热3秒；均A(API36)、同broker、400条，uuid/topic每轮唯一。无kill与+1s kill分别测。保留A数据，APK用install -r，suite finally恢复当前APK。B仍在线。
- baseline detached worktree位于C:/Users/admin/AgentDock/dsim-baseline-1816213；两份APK构建完成后才开始测量。远程结果目录C:/Users/admin/AgentDock/dsim-f3-evidence。

## F4：过期 SEND_CMD 的安全收敛方案（仅提案，未实现）

### 目标
`ReplayGuard` 发现过期 `SEND_CMD` 后仍应拒绝执行；但拒绝不能让请求端的 `sms_messages.status=0` 永久停在“发送中”。提案是由执行端持久发一条 `SEND_CMD_RESULT(success=false, state=FAILED, message=已过期未执行)`，让既有请求端 `handleSendCommandResult` 将状态收敛为失败。不要放宽 SEND_CMD 的10分钟窗口。

### 前置判断（缺一不可）
1. 只针对解密、解码成功且 `inbound is SendCmd` 的 `STALE` 拒绝；MISSING、DUPLICATE、非 SEND_CMD 的拒绝不生成业务失败回执。
2. `uuid` 与 `SendCmd.deviceId` 必须非空；优先要求 payload 的 `deviceId` 与 topic sender 一致，防止把失败回执路由给被伪造的设备。目标设备只取经校验的 requester deviceId。
3. 执行端先查 `dao.getSendCommand(uuid)`；**仅为 null 才允许生成过期回执**。已有 PENDING/UNKNOWN/SENT/FAILED 账本时只按已有账本语义处理，绝不能用“过期”结果覆盖真实执行结论。
4. 必须确认 `UsageModeManager.canUseCloud(context)`、`session.isConfigured` 与当前组配置可用。隐私本地模式继续遵守现有“确认后丢弃”语义，不为生成回执而新增云端上传点。
5. 失败回执先进入现有 `SyncOutbox`，而不是直发；使用已有唯一 `controlKey`，建议 `KIND_SEND_CMD_RESULT + uuid + "expired"` discriminator，避免重复拒绝创建多条相同 outbox。只有入队事务成功后才允许该 MQTT 报文 PUBACK。

### 实现边界
- 将 `InboundCommitGate` 的拒绝回调改为可挂起且可返回 outcome，或增加等价的“拒绝副作用已提交”路径：outbox 入队成功→`PermanentlyRejected`→ack；SQLite/IO 失败→`RetryableFailure`→不 ack、重投重试。不能继续用当前无返回值的 `onRejected` 在入队前 ack。
- 扩展现有 `MqttPublisher.publishSendCommandResult` 的参数/专用方法，使 `state=FAILED` 与 `expired` discriminator 显式编码；不新增实体字段、不递增 Room version、不改变 DSM3 或 MQTT topic 层级。
- 既有请求端已按 `state` 映射失败状态，需保留 `targetDeviceId` 校验；若新增稳定错误码，应先确认协议兼容范围，最小方案只复用现有 `message` 字段。

### 验收与回滚
- JVM：过期 SEND_CMD 不调用 dispatcher；无账本时生成一条可解密的 FAILED 回执，重复相同拒绝因 outbox key 幂等；已有账本时零过期回执；入队 SQLite 失败时无 ack、nonce 未消费，重投后可成功入队。
- 仪器/双机：请求端原 status=0 在收到回执后变 -1；执行端无 `send_commands` 执行记录、无运营商调用；断网时回执留在 `sync_outbox`，恢复后只发布一次。
- 回滚风险：错误的 deviceId/uuid 校验会把别人的气泡标成失败；错误的 ack 时序会丢掉“失败回执”或使过期报文反复重投；复用 `prepare_failed` discriminator 会覆盖/合并不同结论。故必须先加上述守卫与故障注入测试，再改生产路径。方案不应引入 schema migration，便于单提交回滚；回滚后已入 outbox 的过期回执仍可能发布，若要完全撤回需明确 outbox 数据清理策略，不能静默删除。

### 当前裁决状态
本轮只记录方案，未改代码、未改协议、未改数据库、未实现过期回执。需要用户批准前置判断和“本地模式不发回执”的语义后，才进入实现；不随本轮进入阶段 1。

## 运行时验证偏差（本轮收尾）

- API24/25/28：尝试用本机 SDK Manager 安装 `platforms;android-28` 与 `system-images;android-28;google_apis;x86`。下载过程实际报 `Error reading Zip content from a SeekableByteChannel`；清理后检查 `system-images/android-28/google_apis/x86/system.img=False`，只剩已删除的 `.installer` 临时目录；可用 AVD 仍只有 `Medium_Phone_API_36.1`、`dSIM_B`、`dSIM_C`。没有创建/启动 API28 模拟器，所以 T0.1/F1 的低 API 运行时路径**仍未验证**，未用 lint 或 API36 结果冒充。
- 同进程断网/重连脚本未执行：当前 A（emulator-5554）被覆盖安装后处于首次引导、无 `dSIM_UI_PREFS.xml`、无 MQTT 服务，且曾出现 System UI 无响应弹窗；只有 B 保持已配置。为避免绕过真实引导或把不满足前置条件的失败伪装成产品结论，本轮不跑该脚本，列为未验证。
- F3 最终 suite 的 B 网络设置由脚本 finally 恢复；当前 APK 已恢复并启动。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 见 task_plan.md「Decisions Made」全表 | 同一事实只记一处，避免双写漂移 |
| E2E 取证只读 sms_messages/sync_outbox 的 sqlite 快照，不用 logcat 断言 | 数据库状态是唯一可信验收面 |
| 重连 E2E 设计：断 B 机 wifi+data → 注入 → 验 outbox 滞留 → 恢复网络 → 验投递 + PID 不变 + outbox 清空，finally 还原网络设置 | “同进程重连”必须以 PID 不变证明；不动生产代码 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| B 机 SettingsActivity 未导出，am start 失败；run-as am start UID 不符；无 su | 从导出的 SmsListActivity 进入，再走真实 UI |
| scripts/uiauto.sh tapid 报 not-found 而新鲜 dump 明明有该字段 | 弃用该脚本，直接 adb + 每次取新鲜 XML 校验 enabled+bounds |
| 一次性 UI 脚本（dsim-stage0-ui.py）AssertionError: broker field disabled | 根因=漏掉断开确认对话框；脚本作废，人工真实 UI 完成配置 |
| 离线 E2E 首跑 OSError 22 写 -shm（Windows、未关连接） | contextlib.closing 修复后整轮重跑（脚本在仓库外，不影响产品代码） |
| planning 文件首版位置/格式错误 | 用户指正；skill init-session.ps1 重建为命名计划并按模板填写 |

## Resources
- 修复计划（只读）：C:\Users\admin\AndroidStudioProjects\dSIM\修复计划_2026-09-19.md
- 项目约束：AGENTS.md；测试规程：TESTING.md；进度文档：FIXES_2026-09-19.md（已随 ec0abaf/f3ae147/fc3fef2 提交更新）
- 验证脚本：scripts/verify-dsim.sh（未改动；运行时仅设 VDSIM_PYTHON=C:/Python314/python.exe、PYTHONIOENCODING=utf-8）
- 一次性 E2E 脚本（仓库外）：C:\Users\admin\AgentDock\dsim-stage0-offline-e2e.py（已用，PASS）；dsim-stage0-reconnect-e2e.py（就绪未跑）；dsim-stage0-ui.py（作废勿跑）
- E2E 数据库快照证据：C:\Users\admin\AgentDock\dsim-stage0-evidence\20260919T052101Z-emulator-5554\ 与 …-emulator-5556\（成功轮）；20260919T051842Z-*（失败首跑，仅脚本错误）
- 工具链：JDK21 C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot；Git Bash C:\Program Files\Git\bin\bash.exe（PATH 里的 bash 是 WSL，勿用）；Python C:\Python314\python.exe；adb C:\platform-tools\adb.exe；SDK %LOCALAPPDATA%\Android\Sdk
- skill 安装根：C:\Users\admin\.agentdock\skill-store\installed\planning-with-files\3.20.0（init-session.ps1 / set-active-plan.ps1 / resolve-plan-dir.ps1 / check-complete.ps1）
- 远端仓库：https://github.com/ayueyang/dsim.git（git remote get-url --push origin 实测）
- 编排通道：AgentDock MCP https://mcp.1110022.xyz/mcp；令牌仅经环境变量 AGENTDOCK_TOKEN 传递，严禁写入任何文件

## Visual/Browser Findings
- B 机设置页 UI（文本化自 uiautomator dump）：顶部“断开”按钮 → 确认对话框（按钮“确认断开”=android:id/button1）；broker 地址、topic、密码输入框与“保存”在已连接时 disabled；滚动/状态变化会改 bounds，操作前必须重新 dump 并核对 enabled+bounds。
- 双机 SmsListActivity 正常启动，logcat 显示同一 topic 订阅与强制 PONG；B 机最近时间戳 05:14:04/06（UTC）。

---

*Update this file regularly during research so important evidence remains available after context changes.*
