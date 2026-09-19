# Task Plan: dSIM 修复计划_2026-09-19 阶段 0（T0.1–T0.4）

## Goal
按仓库根目录《修复计划_2026-09-19.md》完成阶段 0（T0.1 Base64 API24、T0.2 按消息语义拆分重放窗口、T0.3 三态 ack + 提交后消耗 nonce、T0.4 推送备份），每任务一个独立 conventional commit，全部验收以真实命令输出为准，随后停下等用户批准才进入阶段 1。

## Next Step
阶段 0 收尾续作 2 已完成（AGENTS 漂移修复 3d1c738 / 仪器复跑 9923853 / W22 登记 da44f6c；本地 3 个提交，**未 push**）。下一步：等用户明确批准后再进阶段 1（T1.1–T1.5）；在此之前不动源码、不开新范围。

## Current Phase
Phase 6（收尾：文档提交、push、ahead核对后停止）

## Phases

### Phase 1: Requirements & Discovery
- [x] 通读《修复计划_2026-09-19.md》、AGENTS.md、TESTING.md 及全部相关源码/测试
- [x] 取得用户对 minSdk、C22/C24 冲突、测试适配器、TTL、未知异常、业务拒绝类型化等裁决
- [x] 约束与红线记入 findings.md
- **Status:** complete

### Phase 2: T0.1 Base64 API 24 修复（commit ec0abaf）
- [x] MqttProtocol.kt 改用 android.util.Base64（URL_SAFE|NO_WRAP|NO_PADDING）
- [x] 经批准的 src/test-only Base64 适配器（仅救 JVM 桩，不作平台证据）
- [x] 定向 lint 验收：MqttProtocol NewApi=0；JVM 94/94
- **Status:** complete

### Phase 3: T0.2 重放窗口按消息语义拆分（commit f3ae147）
- [x] 类型化 TTL 策略 + UUID 幂等 + 有界 nonce 缓存，取消按龄清除
- [x] JVM 守卫测试 + 新仪器测试 ReplayDeliveryTest（30 分钟旧 SMS 入库、过期 SEND_CMD 拒绝）
- **Status:** complete

### Phase 4: T0.3 三态 ack + 提交后 nonce（commit fc3fef2）
- [x] InboundOutcome / InboundCommitGate（Mutex 串行）/ checkFreshness 与 markConsumed 拆分
- [x] 用户追加裁决：SEND_CMD 既有业务校验拒绝类型化（SendCommandPreparation.kt），保留持久负回执后 ack
- [x] JVM 109/109；A 机全套仪器测试 OK (13 tests)
- **Status:** complete

### Phase 5: Stage 0 global regression & real-device E2E
- [x] 计划原文命令回归（Git Bash + JDK21）：BUILD SUCCESSFUL in 25s，exit 0
- [x] scripts/verify-dsim.sh emulator-5554 emulator-5556：PASS=12 FAIL=0
- [x] 真实 30 分钟离线补投 E2E：PASS（offline_seconds=1805.01，非改时间戳）
- [ ] 同进程断网/重连 E2E（脚本已备好未运行，等用户批准）
- **Status:** complete with deviations (同进程重连未执行；API24/25/28仍未验证)

### Phase 6: T0.4 push & stage report
- [x] FIXES_2026-09-19.md 已补记最终 API/重连偏差（本次收尾 docs commit待提交）
- [x] git push origin main；处理远端非快进后merge 931196e，再push成功；branch -vv / rev-list左右均0
- [x] 账本与FIXES含逐任务文件/diff/命令/输出/验收/偏差/裁决；本轮不进阶段1，等待批准
- **Status:** complete with deviations; waiting for approval

## 本轮审查收尾（2026-09-19，续作）
- [x] F1：HardwareProbeUtils 三处 NewApi SDK 守卫；NewApi 3→0，编译完成，全量 lint 仍5错误
- [x] F3：400条两版无kill/kill均齐全；10.054→14.667s、23.831→27.540s，dup 0/20不变；吞吐退化信号待后续评估
- [x] F6：lint历史措辞、5份测试显式导入、fromFailure显式SQLite/IO；JVM109/109、仪器13/13
- [x] F2：AGENTS/FIXES 已写明无界重投导致 broker 在途窗口耗尽、后续投递整体停摆；未实现隔离
- [x] F4：已记录安全回执方案、前置判断与回滚风险；未实现，等待用户批准
- [x] API28拉起尝试：sdkmanager下载包损坏，未生成可用system.img，仍未验证；同进程重连未执行（A无配置），F6全局构建/JVM/仪器已完成
- [ ] 更新远程账本/FIXES，独立提交，push main 与 ahead 0

## 阶段 0 收尾续作 2（2026-09-19 晚，用户指令）

- [x] **1. AGENTS.md 文档漂移修复（收尾审查 §4.2）**：§5 文件树补 `InboundOutcome.kt` / `InboundCommitGate.kt` / `SendCommandPreparation.kt`；同表 `DsimEntities.kt` 5→6 个 `@Entity`、`DsimCryptoUtils.kt` V2→DSM3；§3 入站叙述改三态 ack（`Committed`/`PermanentlyRejected` 才 ack，`RetryableFailure` 与取消不 ack）。commit **3d1c738**（+6/-3）
- [x] **2. 仪器测试复跑（§4.1）**：冷启动 emulator-5554（dSIM_B，API 36）后 `:app:connectedDebugAndroidTest` → **13/13 (0 skipped, 0 failed)，BUILD SUCCESSFUL in 3m 38s**；机器判定取 `test-result.textproto`（`scheduled_test_case_count: 13`、`test_status: PASSED`）。上次同 HEAD 的 `failed to attach` 归因为**模拟器实例状态**（crash buffer 多条 `DeadSystemException` + `pm list packages` Broken pipe 32），冷启动后消失。commit **9923853**（FIXES +23）
- [x] **3. REFACTORING.md 登记 W22**：入站串行化吞吐代价 10.054 s → 14.667 s（+45.9%），裁决「接受并记录、不改设计」，列为追踪项（含触发条件与未批准的候选方向）。commit **da44f6c**（+25）
- **Status:** complete
- **边界**：未实现 F4、未做 F2 有界隔离/告警、未改并发设计、未进阶段 1、未 push。

## Key Questions
1. minSdk 取值？→ 已答：保持 24，只改 Base64，不动 PBKDF2（用户裁决）。
2. AGENTS C22/C24 与计划冲突？→ 已答：以用户批准的 T0.2/T0.3 具体方案为准；保留手动 ack、处理成功后才 ack、取消不 ack。
3. JVM 测试 Base64 桩返回 null？→ 已答：批准 src/test-only 适配器，不扩依赖/框架，不作平台证据。
4. HISTORY_QUEUE_BATCH TTL？→ 已答：保留既有 10 分钟。
5. 未知异常如何处理？→ 已答：保守归 RetryableFailure 不 ack，接受可能反复失败并记录在案。
6. SEND_CMD 业务校验拒绝会不会丢负回执？→ 已答：用户批准类型化区分（SendCommandRejectedException），仍发持久失败回执后 ack；费率算法/账本/电话栈/事务边界一律不动。
7. planning 三件套是否提交进 git？→ 部分已答：用户裁决放仓库工作目录（现位于本 .planning 计划目录，未提交）；仓库惯例有 docs(planning) checkpoint 提交（如基线 1816213），是否照做待用户定。
8. 何时恢复执行（重连 E2E → FIXES 补记 → push）？→ 部分已答：本续作按用户逐条指令完成了文档/复跑/登记三项；重连 E2E 仍未跑。
9. 本轮 3 个提交（3d1c738 / da44f6c / 9923853）是否 push 到 origin/main？→ **未答，等用户裁决**（上一轮流程要求 ahead 0，但 push 属对外动作，未获批不做）。

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 保持 minSdk 24，仅替换 Base64 编码 | 用户裁决；T0.1 只修 NewApi，不扩范围 |
| src/test/java/android/util/Base64.java 测试适配器 | mockable-android 桩返回 null 导致 9 个 JVM 编解码测试失败；用户批准，仅测试源集 |
| SMS_SYNC/SEND_CMD_RESULT/HISTORY_SYNC_ACK 无时间窗，仅 UUID 幂等 + 有界 nonce 缓存 | 计划 T0.2 规定；防重放不靠按龄清除 |
| SEND_CMD 10min、PING/PONG 短窗、OFFLINE 24h、HISTORY_QUEUE_BATCH 保留 10min | 计划 + 用户裁决 |
| 未知异常=RetryableFailure 不 ack；取消重新抛出；协议错误=PermanentlyRejected 记录+ack | 用户裁决：宁可不 ack 等下个 broker 会话，也不丢数据 |
| markConsumed 移到处理成功提交后，InboundCommitGate 用 Mutex 串行化 | 防止“先消耗后失败”丢消息，及拆分后并发同 nonce 双通过 |
| SEND_CMD 业务拒绝类型化（仅改异常类型，6 处） | 用户追加授权；保留既有负回执语义，不改费率/账本/事务 |
| E2E 用本机 Mosquitto（tcp://10.0.2.2:1883, topic dsim/test/9f3a2b） | 规避文档记载的公网 EMQX 突发静默丢弃；B 机经真实设置 UI 配置 |
| planning 三件套放 .planning/2026-09-19-repair-plan-stage0/（skill init-session.ps1 创建，.active_plan 已指向） | 用户要求放仓库工作目录；仓库既有 slug 模式，skill 解析优先命名计划，根目录 legacy 文件会被忽略 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Cloudflare 1010 拒绝 MCP 端点（urllib 默认 UA） | 1 | 所有请求带浏览器 User-Agent |
| exec_command 静默返回空 | 1 | 参数名是 cmd/workdir（search_text 是 query/regex），非 command/cwd |
| 9 个 JVM Base64 编解码测试失败（桩返回 null） | 1 | 经批准新增 src/test-only 适配器，94/94 通过 |
| B 机旧 APK 开机崩溃（BOOT_COMPLETED 起 dataSync FGS 不允许） | 1 | 覆盖安装当前 APK 后消失；未改生产代码 |
| B 设置界面自动化屡次失败（SettingsActivity 未导出、连接态字段禁用、盲点无效） | 3 | 真实 UI 流程：断开→确认对话框“确认断开”(android:id/button1)→改 broker→Back 收 IME→保存；只读校验 prefs |
| 离线 E2E 第一次运行 OSError 22（Windows 下未关 sqlite 连接，-shm 无法覆盖） | 1 | 测试脚本改 contextlib.closing；全新 fixture 从头重跑，第二次通过 |
| planning 三件套首版未按 skill 模板且放错位置（仓库根 legacy 模式会被 .planning 命名计划遮蔽） | 1 | 用户指正后：删除根目录三件，改用 skill 自带 init-session.ps1 创建命名计划并按模板填写 |

## Notes
- 红线（不得破坏）：uuid 唯一索引、DSM3 加密格式、口令派生密钥、SMS 接收互斥/BROADCAST_SMS、UsageModeManager 隐私门、topic 层级、手动 ack 契约、稳定 clientId/cleanSession=false。
- 源码/文档一律 UTF-8 无 BOM + LF；用编辑工具（MCP file_edit）写，禁止 shell 重定向。
- 范围外改动禁止；新发现问题只记录不动手。用户未跟踪文件不得触碰：`claude-fable-5.1-high · 001.md`、`代码质量审查/`、`修复计划_2026-09-19.md`。
- 恢复方式（任何 AI）：先设 `$env:PLAN_ID='2026-09-19-repair-plan-stage0'` 再跑 skill 的 resolve-plan-dir（实测：仓库有多个命名计划时，无 PLAN_ID 的裸跑会静默 exit 0 且无输出，这是拒绝选择而非故障；也可直接读 `.planning/.active_plan`，现指向本计划）。然后读本目录三件 + `git diff --stat` + `git log --oneline -5`。`.planning/` 下其他目录是历史任务，勿当当前状态。
- 本任务由远程 Linux 编排沙箱经 AgentDock MCP（https://mcp.1110022.xyz/mcp，令牌走环境变量 AGENTDOCK_TOKEN，不落盘）操作本机；沙箱侧路径（/home/user/…）在本机不存在。
- 观察到并行活动：`.planning/2026-09-18-battery-kdf-topics/task_plan.md` 于 2026-09-19 14:03(+0800) 被本任务之外的会话修改（内容引用“第六份审查”与提交 a78cf09）；本任务未触碰该文件，仅记录。
