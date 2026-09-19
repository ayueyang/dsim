# Task Plan: dSIM 阶段 1（T1.1–T1.5）

## Goal
仅按 `修复计划_2026-09-19.md` 阶段 1 执行 T1.1–T1.5：每项一个独立 conventional commit、每项跑对应验收并保留真实输出，阶段末跑全量回归，然后停下等用户批准才进阶段 2。

## Next Step
阶段 1 及其审查后跟进（F-1~F-5）全部完成并停下汇报。等待：①push（`origin/main...main` = 0/15，用户裁决暂缓）；②D1–D5 用户确认（D6 已由 F-2 收口）；③REFACTORING W23 可选清理是否批准（已登记，未实现）；④阶段 2（T2.x）暂不开始。

## Current Phase
Complete

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
