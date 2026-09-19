# Findings & Decisions

## Requirements
唯一范围来源：`修复计划_2026-09-19.md` 阶段 1（T1.1–T1.5）+ 本轮用户交接指令。已读：`AGENTS.md`（当前版本）、两份阶段 0 审查、`FIXES_2026-09-19.md`、`REFACTORING.md` §1/§2/§3 W22、阶段 0 账本三件。审查方账本 `.planning/2026-09-19-code-review-track/` 只读未改。

## Research Findings
- 起点实测：`git log --oneline -6` = 88dc277 / 9923853 / da44f6c / 3d1c738 / e23ba74 / 931196e；`rev-list --left-right --count origin/main...main` = `0	4`；`adb devices` 仅 emulator-5554。push 后 `0	0`。
- 脏项与只读项全部按原样保留（未 stash/checkout/clean）：审查方 3 个修改（`.active_plan` 与两个 2026-09-18 `task_plan.md`）、`代码质量审查/`、`修复计划_2026-09-19.md`、`claude-fable-5.1-high · 001.md`、`.planning/2026-09-19-code-review-track/`，以及两个技能冒烟目录 `2026-09-19-agentdock-skill-smoke-204306/`、`2026-09-19-arena-skill-check-7d9c2f64/`。基线哈希存于证据目录 `protected-baseline.json`。
- 阶段 1 期间远端 `ahead` 从 0 增至 5（d981457 / 9a87a46 / 46e2bd3 / 3b0e1c2 / cefd02a），**未 push**。
- 环境实测：宿主出站 IPv6 可用（`IPv6 OK`）；`dsim-mosq` 容器在跑；build-tools 36.1.0 可用于 `zipalign`/`apksigner`；`~/.android/debug.keystore` 存在（证书 `CN=Android Debug`，SHA-256 `d89e4638…14333b`）。
- 设备：5554 = dSIM_B（API 36，冷启动，ICC sim_a.xml）、5556 = dSIM_C（ICC sim_b.xml，已 onboard 到 `dsim/test/9f3a2b` / 本机 Mosquitto）。T1.3 验收要求卸载重装，5554 的应用数据被清空并按流程重新 onboard。

## Technical Decisions
- 编排：`exec_command` 长任务一律 `execution_mode=async` + `session_observe`；远端命令写在仓库外 `.sh`（`C:\Users\admin\AgentDock\dsim-stage1-evidence\`）由 Git Bash 执行，避开 PowerShell/bash 引号与 100 s HTTP 524 限制；令牌只经环境变量，不落盘。
- 每次远端调用显式钉 `PLAN_ID=2026-09-19-repair-plan-stage1` + `PWF_PLAN_ROOT=<仓库根>`；`.active_plan` 保持审查方字节不变。
- 所有写文件走 MCP `file_edit`（无 shell 重定向）；UTF-8 无 BOM + LF 每任务实测。

## Issues Encountered
见 `task_plan.md` 的 Errors Encountered 表（同一事实只记一处）。

## Verification Evidence（真实输出摘要）
| 任务 | 命令 | 结果 |
|---|---|---|
| T1.1 | gradle 五项 + 新仪器用例 `MqttLifecycleResponsivenessTest` | `BUILD SUCCESSFUL in 1m 40s`；JVM 109/109；`OK (1 test) Time: 35.225`；DISCONNECT frames=331 gap=28 ms / APPLY_LOCAL_MODE frames=329 gap=46 ms / destroy frames=330 gap=25 ms |
| T1.2 | gradle 五项 + `RadarFlowLifecycleTest` | `BUILD SUCCESSFUL in 1m 32s`；JVM 109/109；`OK (3 tests) Time: 83.476`；主线程占用 3 s 时 200 次 tryEmit 全成功、<200 ms |
| T1.3 | gradle 五项 + `DeviceIdentityTest` ×2 进程 + release 对比 + 卸载重装 | `BUILD SUCCESSFUL in 1m 8s`；JVM 109/109；`OK (2 tests)` ×2（身份 `e789752a…db4c` 不变）；debug/release 同机同为 `7218351f…3cc5`；重装后新身份 `aa6b284f…75e5`，导入 `40→40`、第二次 `imported=0 skipped=40` |
| T1.4 | gradle 五项 + `DsimLogTest` + 双设备 logcat 取证 | `BUILD SUCCESSFUL in 1m 5s`；JVM 113/113；main 源集裸 `Log.*` = 0；完整号码/正文 0 次出现，`from=*******8999`，`action=UNKNOWN_PROBE length=149 hash=f4b60645` |
| T1.5 | gradle 五项 + `CredentialStorageWarningTest` | `BUILD SUCCESSFUL in 2m 9s`；JVM 113/113；`OK (1 test) Time: 17.689`；测试后 `dSIM_UI_PREFS.xml` = `<map />` |
| 阶段末 | `:app:connectedDebugAndroidTest`（5554） | `20/20 completed (0 skipped) (0 failed)`、`BUILD SUCCESSFUL in 3m 49s` |
| 阶段末 | 计划原文回归命令 | `BUILD SUCCESSFUL in 18s`、`FINAL JVM tests=113 failures=0 errors=0` |
| 阶段末 | `verify-dsim.sh 5554 5556` | `PASS=12 FAIL=0`、exit 0；互发现 5554=`63fcfefd…`/5556=`fa9e4551…` |
| 阶段末 | 跨设备注入 + 自回声取证 | 两台各 1 行同 UUID `fb2add1a…`、`sync_outbox`=0；5554 `skip own echo`=1、5556=0；两侧 `local_count=1` |

## Deviations / 待裁决
- **D1（T1.1，范围外 1 行 ×2）**：`hasClient()` 改名 `isDaemonActive()` 并纳入 `serviceAlive`，同步改 `SettingsActivity`、`UsageModeManager` 各 1 行调用。理由：teardown 立即摘除客户端会让原谓词在"服务仍在前台但已无客户端"时返回 false，从而跳过通知刷新与本地模式切换（用户可见：通知停留"已手动断开"）。若审查方要求保留原名，可只回退命名。
- **D2（T1.1）**：`onDestroy` 不做任何主线程等待（计划允许 ≤3 s 有界 join）。代价：进程在 onDestroy 后立即被杀时 best-effort OFFLINE 可能发不出；非正常断链由 LWT 覆盖。
- **D3（T1.1）**：graceful `disconnect()` 改 `disconnectForcibly(1_000, 1_000)`，避免弱网下吃满 `timeToWait`=15 s；OFFLINE 先以 QoS1 发出，未获 PUBACK 时由 LWT/下一个 PONG 纠正。
- **D4（T1.3）**：身份**惰性创建**（首次 `getDeviceId` 调用时 `commit()` 落盘），未新增首启钩子；实测卸载重装后、任何调用方运行前 `dSIM_IDENTITY.xml` 不存在，首次调用后出现并跨进程稳定。
- **D5（T1.4）**：唯一的 `Log.i` 归入 `d` 级，release 不再输出"migrated plaintext password to sealed storage"。
- **D6（T1.5）**：为实测降级分支给 `CredentialVault` 增加 `internal var sealOverride`（生产不赋值）。
- **F-T1.3-upgrade（新发现，仅记录未修，等裁决）**：覆盖安装（保留数据）时旧 ANDROID_ID 时代的 `device_profiles` 行不会被清理，5556 实测同时存在 `('96fad9a2276863b0',1,'LOCAL')` 与 `('7218351f6a5342608458192447933cc5',1,'LOCAL')`，即同机两条本机档案：设备中心会出现幽灵"本机"条目，`verify-dsim.sh` 取 `local_a[0]` 也变得不确定。计划已裁决"不做迁移、旧数据可弃"，故本轮不改代码；为取得干净 12/12 对 5556 执行 `pm clear` + 重新 onboard（测试设备、无用户数据）。若要做一次性清理（首启删除 `source=LOCAL` 且 `deviceId != 当前身份` 的档案行），需另批任务。
- 未验证（不以其它证据冒充）：真实公网 broker / iptables 丢包 / 真机（非模拟器）；API 24/25 运行时；release 包上的 T1.4/T1.5 界面与 logcat 复核（release 包出在 T1.3 时点）。

## Resources
- MCP 任务：`tsk_ce935dd0dcc0b837`。
- 证据目录（仓库外，未入 git）：`C:\Users\admin\AgentDock\dsim-stage1-evidence\`（各阶段 `.sh`、`.log`、`.json`、`protected-baseline.json`、`app-release-debugkey.apk`）。
- 技能安装根：`C:\Users\admin\.agentdock\skill-store\installed\planning-with-files\3.20.0`。

## 用户裁决记录（2026-09-20，会话确认）
- Push：暂缓。7 个本地提交（`d981457`…`3f8c6ac`，ahead 7 / behind 0）暂不推送，等待用户后续决定。
- Stage 2（T2.x）：暂不开始，等待用户进一步指示。
- 陈旧 LOCAL `device_profiles` 行（5556 覆盖安装后同机两条 `isLocalDevice=1`）：仅记录——不开清理/迁移任务、不修改代码（与本文件上文"不做迁移、旧数据可弃"的既有裁决一致）。
- T1.x 偏差 D1–D6：已于 2026-09-20 向用户提交完整细节（位置/做法/理由/风险）。D5 经提交级 grep 核实：`3b0e1c2` 中唯一一处级别降级为 `CloudSettingsManager.kt` 的 `Log.i`→`DsimLog.d`（"migrated plaintext password to sealed storage"），其余全部转换保持原级别（w→w、e→e、d→d）。用户本轮尚未裁决 D1–D6，全部维持现状；裁决结果待后续补记。
