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

## 2026-09-20 收尾审查续作（F-6/F-7/F-8）
- 起点 HEAD=8ee0cf5；origin/main...main=0/15；5554、5556 均在线；5554 `pm list packages com.example.dsim` 空输出，尚未预装/授权。
- 已读指定审查、AGENTS、修复计划、FIXES 与本账本。仅 DeviceIdentityTest 触及系统 SMS/导入器；现有 systemCount 的 assertTrue 是第二个前提缺失。
- F-6 仅测试与必要测试依赖；采用 Assume 空库跳过，不播种/改系统短信。F-7 用 Topics.kt 验证 CloudTopics.kt 不能误覆盖。
- 既有未提交/未跟踪内容全保留；不改审查方账本、不新建计划目录。显式 PLAN_ID/PWF_PLAN_ROOT 进程级绑定；.active_plan 不写。

### 本轮已完成项与证据
- F-6 c66a18b：仅 DeviceIdentityTest + androidTestImplementation("androidx.test:rules:1.5.0")；READ_SMS 为唯一新增权限规则，空库 Assume。
- 5554 新装前提在 Gradle 调用前再次断言：无 com.example.dsim 包；未预装、未手动 pm grant。session-bbbd4ed9696345c78e55717c exit0：20/20 completed (0 skipped) (0 failed)，BUILD SUCCESSFUL in 3m。XML tests=20/failures=0/errors=0/skipped=0；historyImport first(scanned=46,imported=46,skipped=0)，second(scanned=46,imported=0,skipped=46)，rows=46->46 system=46。空库跳过分支本机未触发，不声称另做了空库设备验收。
- 防复发 23c7e99：AGENTS §8.3 一条约定，每轮仪器回归至少一次新装/清数据。
- F-7 21fea7d：ERE 转义 + 空白/行首行尾边界，匹配完整文件名 token。Topics.kt 探针：旧脚本误通过75文件exit0 → 新脚本缺1文件且指名exit1 → 删除后74文件exit0，Test-Path=False。
- F-8 8fe7eb9：只增 AGENTS §5 + 脚本活口径说明；删除新增句后全文等于8ee0cf5，历史数字不改。
- 保护基线：217 文件（既有计划但不含本账本、审查文档、只读资料、app/src/main 全部），路径排序+内容摘要的 SHA256=de7714eb2fd0a8afe50374e6085140354bc015f3f9e2b2d72c6c355353430cac。
- 本轮证据仍在既有仓库外目录 C:\Users\admin\AgentDock\dsim-stage1-evidence：f6-fresh-install-20260920.sh / part1.log / part2.log（日志全名含 f6-fresh-install-20260920- 前缀）、f6-report-verification-20260920.log、f7-boundary-probe-20260920.log、f8-history-preservation-20260920.log。

### 本轮收尾核验
- 编译与强制JVM回归：BUILD SUCCESSFUL in 22s，17份XML共113例，failures/errors/skipped均0。
- lintDebug：BUILD FAILED in 1m 3s，exit1；5 errors/312 warnings，错误为MissingSuperCall×1 + ChromeOS×4，NewApi=0；未抑制/改基线/动产品代码。
- 217受保护文件摘要与起点相同；app/src/main相对8ee0cf5无diff。9个本轮源/文档UTF8、无BOM、CR=0、末尾LF；工作区与提交区间diff --check通过。
- 原始仪器XML已保存为 f6-instrumented-result-20260920.xml；全局输出 f6-f8-regression-20260920.log；最终核验脚本/日志 f6-f8-final-verify-20260920.py/.log（均既有仓库外证据目录）。
- 本轮未额外跑双机E2E/低API/真机；没有变更相关产品链路。push暂缓、D1–D5/W23未批准、阶段2不开始。旧T1.3记录中的pm grant是当时绕行，F-6现已将权限前提写入测试，不再以该绕行为修复证据。

## F-9/F-10 独立复核（2026-09-20）
- 起点21a162e，origin/main...main=0/23；3提交仅AGENTS/TESTING/FIXES。不继承审查结论，adopt插入与空库跳过均待实测。
- 官方GrantPermissionRule文档明确：授权对当前Instrumentation所有测试生效，不能在该进程中撤销（否则崩溃）；另用不卸载的两次instrument验证保留，避免把UTP卸载混为规则撤销。https://developer.android.com/reference/androidx/test/rule/GrantPermissionRule
- 计划使用独立新AVD dSIM_F9F10_20260920（5558），先原套件空库，后临时探针；不触碰5554/5556的数据、应用与默认角色。

- 新AVD空库真实结果：XML20个唯一testcase，19 passed/1 skipped/0 failures/0 errors；唯一跳过historyImport，AssumptionViolatedException明确found0；textproto该用例IGNORED，其余19 PASSED。
- 控制台却显示21/20 completed / Finished21；与XML和计划20数不一致，只记录报告计数异常，不擅自改runner/UTP。BUILD SUCCESSFUL in5m37s，原测试源码未改。
- 启动新AVD时emulator自带adb -e辅助设置报multiple emulators，本轮所有设备操作均显式-s5558；新AVD正常启动、LOADED，未更改其他设备。

## F-9/F-10 独立复核结论（2026-09-20）
- 证实：三审查提交仅AGENTS/TESTING/FIXES，编码与树检查合格；初始/最终247保护文件SHA256相同：5ebeb6059ed1effb486fe36af3ba84c7719122223a5e9060b74d2a89697f206e。app/src与依赖无永久变更。
- 证实（限定本API36镜像、非默认角色）：实际adopt插入返回content://sms/0、exception=none，标记persisted=0；对照无adopt相同；总数0→0。不再泛化所有版本/特权配置，也不把probe OK当插入成功。
- 证实：GrantPermissionRule不自动撤销；初装READ_SMS=false，第一轮有规则true，第二轮无规则仍true；pm clear后false。UTP收尾卸载不等于规则撤销。
- 证实：全新AVD原套件空库XML19pass/1skip/0failure/error，historyImport明确found0；其余19逐条名称/结果已存仓库外empty-case-analysis.log。
- 证伪并已改文档：之前adopt被误写为已实测；必须先onboarding dSIM并非必要，Google Messages默认接收端+emu sms send实际0→1，无需让dSIM持有默认角色。
- 为检验新装与fixture要求自洽，临时源码删除后卸载两只APK，再跑原套件：BUILD SUCCESSFUL in3m25s，XML20pass/0skip/failure/error，historyImport首次1、二次0、rows1→1。此轮才是有fixture的新装证据，权限残留探针不混入。
- 文档00b994d：AGENTS §8.3将0failed收紧为任务成功/用例齐全/0failure+error/解释可接受skip；fixture缺失允许跳过，但不能据此验收去重覆盖。TESTING §5.8与FIXES补测量、命令、归属与限制。
- 新发现未解决：空库控制台21/20，而XML/textproto一致为20；原因尚未验证，未扩展改runner/UTP。播种后回归Finished20。逐条证据位于C:/Users/admin/AgentDock/dsim-stage1-evidence/f9f10-*。
- 无法验证范围：以上adopt结论不能外推未测Android版本、root/特权应用/不同AppOps；这些不是当前任务已授权工作。未运行任务二中的低API/双机同进程E2E/release/UI或阶段2/3修改。

## 批次 A 起点
- 权威裁决已读：D1-D5接受、W23不做、F2不做、F4留批次B、UTP21/20为报表伪影已结案，不继续追runner。存量lint不修。
- e51a92f与origin一致0/0；现有5554/5556在线，最低已装镜像36.1。启动SDK24+28 google_apis/x86_64下载，不用lint替代运行验证。
- 原脏项/审查文档/不变量文件74条独立SHA256基线保存仓库外batchA-protected-baseline.json。

- T3.4 修前负面确证：同一PDU通过测试专用广播action别名进入真实SmsReceiver/goAsync，XML失败expected1/actual2。修后复用findSimilarLocalMessage ±120000ms，lookup+store同Room事务，复用id/uuid；原断言通过，窗口外及不同正文仍独立。不调整生产受保护广播过滤器/权限。
- 新增IncomingSmsDedupTest首跑初始化错误（runBlocking推断返回Int，JUnit要求void）；改runBlocking<Unit>后重跑才得到上述产品缺陷复现。此harness失败与产品失败分别留档。
- T3.5 按计划将计费注释留到T2.1，不提前改费用语义；补AGENTS实际加密入口encryptOrNull及不写死套件20例，避免新增测试后再次漂移。
- T3.6 仅三个入口显式警告，headless新增onStartCommand后仍委托super；无MMS/编辑/发信功能实现，不删默认SMS必需组件。

## 批次A最终发现与结论
- 低API并非静态推测：API24 nonce16chars/12bytes/PUBACK/DSM3一致，legacy_getSimState()与legacy_activeSlotInfo真实成功；API28 slot overload与旧订阅路径成功。5d2e600收永久3例和TESTING说明。单SIM/google_apis镜像结论，不泛化OEM/多卡。
- API28源码期望26与XML26唯一case完全一致，MISSING/UNEXPECTED均空，failure/error/skip全0；历史导入first1/second0/rows1→1/system1。最终JVM17XML合计113全过。
- 原API28外部driver把shell READ_SMS拒绝误读为0行，原fixture失败断言无效；没有提升shell权限，改用既有规则后实际全套通过。
- 默认SMS入口真实验证：verify-dsim 8/8；两次emu sms send实际两条SMS_DELIVER日志，唯一标记正文Room仅id2一行。永久别名测试另证明同一PDU/id/uuid保持及窗口/正文边界。
- 可选raw-PDU注入第一次OK却无Room标记/相应radio观察，第二次未执行，原因未确定；不作为成功证据、不扩展诊断。onboarding脚本exit0不等于完成UI：顶部SimBinding、完成标记缺失，只认定采集所需配置有效。
- 最后保护74文件SHA256一致；Manifest只删locked boot，Phase2/硬件已修主代码均未动，UTF8/BOM0/CRLF0/尾LF及树74通过。
- 三台测试AVD均停，5558系统SMS由原1条变3条合成fixture，app/test卸载；重启确认Google Messages默认恢复后再停。旧5554/5556未改。原有脏/未跟踪项继续保留，不push、不进批次B。
