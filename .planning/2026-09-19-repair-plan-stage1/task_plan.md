# Task Plan: dSIM 阶段 1（T1.1–T1.5）

## Goal
仅按 `修复计划_2026-09-19.md` 阶段 1 执行 T1.1–T1.5：每项一个独立 conventional commit、每项跑对应验收并保留真实输出，阶段末跑全量回归，然后停下等用户批准才进阶段 2。

## Next Step
批次A已完成并停止：只待用户审查及另行批准push/批次B，不自行开始阶段2/4/5/6。阶段0/1维持关闭。

## Current Phase
Phase 23 — complete

## Phases

### Phase 1: 起点、push、技能初始化
- [x] 实测 HEAD=88dc277、`origin/main...main` = 0/4、emulator-5554 device（5556 当时离线）。
- [x] `git push origin main` → `e23ba74..88dc277 main -> main`，随后 `0	0`，无 fetch/merge 需要，未 force。
- [x] `init-session.ps1 -ProjectName repair-plan-stage1` 创建本目录三件；同一命令 `finally` 写回 `.active_plan` 原字节（校验 True）。
- **Status:** complete

### Phase 2: T1.1 MQTT 阻塞调用收口（commit d981457）
- [x] `client.timeToWait = 15_000`；三处生命周期入口改为 `serviceScope` 串行 teardown（0 ms 主线程等待）；`disconnectForcibly(1s,1s)+close(true)` 有界收尾。
- [x] 弱网仪器验收：黑洞 Broker（CONNACK/SUBACK 后丢弃全部 PUBACK）下三动作主线程最大帧间隔 25–46 ms。
- **Status:** complete（含偏差 D1/D2，见 findings）

### Phase 3: T1.2 radarEventFlow 缓冲化 + 生命周期感知收集（commit 9a87a46）
- [x] `extraBufferCapacity = 64` + `DROP_OLDEST`；`emit` → `tryEmit`；两个收集端 `repeatOnLifecycle(STARTED)`。
- [x] 仪器 3/3：`subscriptionCount` 随前后台/同栈变化；主线程被占 3 s 时 200 次 `tryEmit` 全成功且 <200 ms。
- **Status:** complete

### Phase 4: T1.3 deviceId 改为应用自管 UUID（commit 46e2bd3）
- [x] `dSIM_IDENTITY` 独立 prefs + `commit()` + 进程缓存；删除 `DSimHardwareTester` 私有副本；不做 ANDROID_ID 迁移（已在 FIXES 写明）。
- [x] 验收①：同机 debug 与 release（debug key 签名、数据保留）deviceId 完全一致 `7218351f…3cc5`。
- [x] 验收②：卸载重装 → 新 UUID `aa6b284f…75e5`；重跑历史导入 `first(scanned=40,imported=40,skipped=0) / second(scanned=40,imported=0,skipped=40) rows=40->40`。
- [x] 验收③：mappingKey 前缀/往返已断言；isLocalDevice 与自回声在阶段末双机回归实测。
- **Status:** complete

### Phase 5: T1.4 日志脱敏层（commit 3b0e1c2）
- [x] 新 `DsimLog`（d/w/e）+ `maskNumber`/`fingerprint`/`redact`；24 个文件全量转换，main 源集裸 `Log.*` 归零；`MqttInboundHandler` 不再输出解密载荷（改 action/length/hash）。
- [x] JVM 113/113（新增 4 例）；设备实测：完整号码与正文在 logcat 中 0 次出现，`from=*******8999`、`action=UNKNOWN_PROBE length=149 hash=f4b60645`。
- **Status:** complete

### Phase 6: T1.5 Keystore 明文降级可见化（commit cefd02a）
- [x] `isPasswordPlaintextFallback` + 设置页持久警示（本地模式分支前刷新）；`CredentialVault.sealOverride` 内部测试替身入口。
- [x] 仪器 1/1：替身返回 null → 保存仍成功且警示逐字可见；正常密封 → 警示 GONE；prefs 完整还原（`<map />`）。
- **Status:** complete

### Phase 7: 阶段末全量回归与交接
- [x] `:app:connectedDebugAndroidTest`（ANDROID_SERIAL=emulator-5554）：`20/20 completed (0 skipped) (0 failed)`、`BUILD SUCCESSFUL in 3m 49s`。
- [x] 计划原文全局回归命令：`BUILD SUCCESSFUL in 18s`、`FINAL JVM tests=113 failures=0 errors=0`。
- [x] `verify-dsim.sh emulator-5554 emulator-5556`：`PASS=12 FAIL=0`、exit 0（两次 harness 失败已定位并重跑，见 FIXES）。
- [x] 自回声/isLocalDevice/跨设备单份落库：5554 `skip own echo` 1 行；两台各 1 行同 UUID `fb2add1a…`、`sync_outbox` 均 0；两侧 `device_profiles` 各只有 1 条 LOCAL。
- [x] `tmp_dbpull/` 删除；`git status` 仅剩审查方脏项、只读文档、两个冒烟目录与本账本；5 个提交 ahead 5 未 push。
- **Status:** complete

### Phase 8: 审查后跟进 F-1~F-5（2026-09-20）
- [x] F-1 `scripts/check-agents-tree.sh` + AGENTS §5 补 10 项（首跑如实列出缺失、探针负面验证 exit 1/还原 exit 0）；三处易失数字标注重改为指向源码（55/14300 头注、MqttSyncService 568 行标称、DsimDao 40 计数——实测 46） — commit e4f5ea1
- [x] F-2 `sealOverride` 加 `@VisibleForTesting` + setter `check(BuildConfig.DEBUG)` — commit 4ec7c3f；build 绿
- [x] F-3 C7 例外（APPLY_LOCAL_MODE 的 OFFLINE 告别）登记 — commit e5b7415
- [x] F-4 FIXES 补对端残留影响面 + REFACTORING 登记 W23（可选·未批准） — commit b84f56b
- [x] F-5 `redact()` ≥7 位数字串副作用写入 KDoc — commit fc70d45
- [x] FIXES 执行记录与 lint 验收（NewApi=0、5 errors / 312 warnings 与基线一致） — commit f516e68
- **Status:** complete

### Phase 9: 收尾审查 F-6 新装设备测试正确性
- [x] GrantPermissionRule.grant(Manifest.permission.READ_SMS) 与空库 Assume；补 androidTest-only rules:1.5.0，不改产品代码。
- [x] 起点无应用的5554全套20/20（0 skipped/failed），BUILD SUCCESSFUL in 3m；historyImport 46→46，二次 imported=0/skipped=46；commit c66a18b。
- **Status:** complete

### Phase 10: 防复发回归约定
- [x] AGENTS §8.3 写明每轮回归至少一次新装/清数据仪器测试及设备状态依赖，commit 23c7e99。
- **Status:** complete

### Phase 11: F-7 文件树文件名边界
- [x] 完整文件名 token 正则 + ERE 转义；Topics.kt 修前0/修后1且指名/删除后0，无残留，commit 21fea7d。
- **Status:** complete

### Phase 12: F-8 历史度量口径
- [x] REFACTORING §1 只新增一句说明；去除此句后与起点全文相同，历史数字未改；commit 8fe7eb9。
- **Status:** complete

### Phase 13: 本轮回归、记录与交接
- [x] 编译/JVM113/113（22s）；lint已知5 errors/312 warnings（exit1，NewApi=0）；9文件UTF8无BOM/LF；217受保护文件摘要一致；FIXES与三件账本收尾记录后提交并停下。
- **Status:** complete

### Phase 14: F-9/F-10 独立复核起点与原始证据
- [x] HEAD=21a162e，0/23，3提交只改AGENTS/TESTING/FIXES；app/src差异为空；树检查exit0。
- [x] 三文档UTF8/BOM0/CRLF0/尾LF；保护247文件摘要已存；新AVD创建并启动5558。
- **Status:** complete

### Phase 15: 全新AVD空短信库原套件
- [x] 原20例：XML20节点，19 passed/1 skipped/0 failures/0 errors，historyImport found0；控制台21/20计数异常已保留，未改框架。
- **Status:** complete

### Phase 16: adopted shell与授权残留实测
- [x] 仅新AVD临时用例：无adopt/有adopt均URI=sms/0、persisted=0、总数0→0，drop/清理断言成功；权限两轮不卸载保持true，pm clear后false。
- [x] 仓库临时代码已删；Google Messages默认接收端可不经dSIM onboarding播种，原目标用例实际通过；随后卸载两包并重跑原20例全过，重新构建APK无临时类。
- **Status:** complete

### Phase 17: 文档纠正、独立复核记录与停下
- [x] 00b994d仅提交AGENTS/TESTING/FIXES；实测结论、误写“已测”归属、非必要onboarding路径和0failed判读均已纠正。
- [x] UTF8无BOM/CRLF0/尾LF、diff-check、树74/exit0；247保护摘要一致，app/src零差异；新5558已关停保留1条合成fixture，旧5554/5556未动。
- [x] 更新现有三件账本，任务二仅候选；无push，停止等待批准。
- **Status:** complete

## Decisions Made
| Decision | Rationale |
|---|---|
| 范围只做 T1.1–T1.5 | 用户指令；F4、F2 有界隔离、阶段 3/5 一律不做 |
| 不动 `InboundCommitGate` 的 Mutex、不改回并发 | W22 已裁决"接受并记录" |
| teardown 立即摘除客户端引用并把清理排入 IO 动作 | 主线程 0 ms 等待；连接与清理共用进程级锁，避免同 clientId/持久化目录竞态（C12/C20） |
| `hasClient()` → `isDaemonActive()`（含 `serviceAlive`） | 摘除引用后原谓词会漏掉"服务仍在前台"的情形，导致通知刷新/本地模式切换被跳过（偏差 D1） |
| deviceId 惰性创建、不做迁移、不加首启钩子 | 计划只点名 `HardwareProbeUtils` 与调用点；无发布用户，兼容分支即死代码 |
| `Log.i` 归入 `DsimLog.d` | 门面只有三级；安全相关的两条降级日志保留为 `w`，并由 T1.5 变成用户可见警示 |
| `CredentialVault.sealOverride` 为 internal 测试替身 | 项目无 mock 框架；真机 Keystore 正常，否则无法实测降级分支 |
| 5 个提交暂不 push | 阶段 0 先例：push 属对外动作，需用户批准 |

## Errors Encountered
| Error | Resolution |
|---|---|
| PowerShell → `bash -c` 传参丢引号，Gradle 报 `Cannot convert relative path C:Program to an absolute file` | 改为用 `file_edit` 写仓库外 `.sh`，Git Bash 直接执行文件（不用 `-c` 内联） |
| `DsimLogTest` 首轮 2 例失败 | 均为测试期望/正则边界问题：星号上限 8 未计入；无边界正则会误伤十六进制 deviceId 中 7 位数字段。加字母数字前后界 + 修正期望后 113/113 |
| DeviceIdentityTest 首次失败 `SecurityException: requires READ_SMS` | 5554 重装后未授权；`pm grant` 8 项权限后通过（环境问题，非代码） |
| emulator-5554 实例在 T1.3 验证途中消失 | 按已知处置清 `dSIM_B.avd\*.lock` 冷启动恢复；`session_act kill` 终止卡在 `- waiting for device -` 的脚本后重跑，AVD 数据未丢 |
| `session_observe` 返回的是增量输出且有上限 | 长脚本一律 `exec > >(tee 证据文件)`，以文件为准 |
| 首次 `verify-dsim.sh` = `PASS=8 FAIL=1`（no mutual discovery） | 脚本只 `am start` 主机；5556 在 `install -r` 后未被拉起、根本没连 broker。显式拉起两端并等双方 `subscribed` 后重跑 → 12/12 |
| 第二次双机脚本报 `com.example.dsim not installed` | `connectedDebugAndroidTest` 结束时会卸载两个 APK（实测 `pm list packages` 计数 0）；重新 `onboard-device.sh` 后重跑 → 12/12 |

### 本轮执行观察（2026-09-20 F-6~F-8）
- 可选 rules 缓存目录探测使用 Get-ChildItem：路径当时不存在，辅助命令 exit1；改以 Gradle 依赖报告和实际构建验证，未调整系统配置。
- F-8 首次 Python 控制台输出中文因默认编码显示乱码，但 UTF-8 解码与全文比对断言成功，文件未乱码；后续明确 PYTHONIOENCODING=utf-8 复核。

- 本轮 lintDebug 真实失败：5 errors / 312 warnings，MissingSuperCall×1 + ChromeOS×4，NewApi=0；按用户指令仅记录，不改产品代码。
- 收尾编排首次在本地 Python 解析 Windows 路径时发生 unicodeescape SyntaxError，尚未发出任何远端命令；改用 raw string 后继续，不涉及源码或测试失败。

### 本轮执行观察（2026-09-20 F-9/F-10独立复核）
- 空库控制台21/20 completed / Finished21，与XML20节点、textproto19PASSED+1IGNORED不一致；原始证据已存。采用用例级结果，未假称20全过；计数原因尚未验证，另行批准才排查runner/UTP。
- 新AVD启动器的内部adb -e报multiple emulators；本轮管理/测试均显式5558，两轮Gradle均成功；不为了消除辅助警告而动旧设备。
- 所有临时源与驱动仅保留仓库外证据副本。新AVD当前有1条合成fixture；若再次测空库，必须新建或重置这台专用AVD，不能复用其当前数据冒称为空。

## 批次 A（裁决_2026-09-20，起点e51a92f，ahead0）
- Phase18 T3.1/T3.2 删除死代码：complete；7c00b72/b07da50，零引用、编译绿、JVM113。
- Phase19 T3.3 BootReceiver保护/死过滤器：complete；dcd4275，API28异常拒绝/locked-boot仪器1/1。
- Phase20 T3.4 内容判重/真实重投：complete；929f088，原Receiver同PDU重投修前2行、修后1行，窗口外/正文不同不合并。
- Phase21 T3.5注释与T3.6占位日志：complete；3c881f0/7d3352c，XML排除项不变、树74、API24三占位日志断言1/1。
- Phase22 低API与回归：complete；5d2e600；API24定向3/3、API28新装全套26/26（逐个case对源码，无skip/error/failure）、JVM113/113；verify8/8、真实双SMS_DELIVER仅一行。
- Phase23 保护范围/证据/账本收尾：complete；74文件SHA一致，编码/树/范围检查通过，三台测试AVD均停机保留；无push，停止等批次B批准。

### 批次A失败与边界
- T3.4 harness非void首次失败已修；随后才取得真实expected1/actual2负测，再修产品后绿灯。
- API28 shell无READ_SMS曾被driver误算空库，Gradle尚未启动；修外部driver后原规则读回system1、import1/0、rows1→1，全套26/26无skip。
- 可选raw-PDU实验未形成可观察投递，第二次未执行；不报通过。永久同PDU别名测试+真实双SMS_DELIVER均证实单行；完整onboarding不在已验收结论内。
- API24只定向3例与另1组件例，不冒称全套；计费注释留T2.1。其余未覆盖/命令/证据见FIXES批次A节。
- 最新裁决覆盖历史候选状态：D1–D5接受，UTP21/20结案不再追；W23/F2不做，F4仅下一批阶段2。
