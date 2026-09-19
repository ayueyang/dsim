# Progress

## Session: 2026-09-19 阶段 1（T1.1–T1.5）

### 起手四项
- `git log --oneline -6`：88dc277 / 9923853 / da44f6c / 3d1c738 / e23ba74 / 931196e；`git status --porcelain=v1` 与交接一致（审查方 3 改 + 只读未跟踪项 + 两个冒烟目录）；`adb devices` 仅 emulator-5554。
- `git push origin main`（session-d8d3a05e0bf0d072f36dabd0，exit 0）：`e23ba74..88dc277 main -> main`；`rev-list --left-right --count origin/main...main` = `0	0`；未 force、无需 merge。
- `init-session.ps1 -ProjectName repair-plan-stage1`：创建本目录三件；`finally` 写回 `.active_plan` 原字节，`ACTIVE_POINTER_RESTORED=True`；`resolve-plan-dir.ps1` 在钉住 `PLAN_ID`/`PWF_PLAN_ROOT` 后返回本目录。
- 保留 `2026-09-19-agentdock-skill-smoke-204306/`（未删、未提交、未改）。
- 记录受保护文件基线哈希到证据目录 `protected-baseline.json`（`.active_plan`、两个 2026-09-18 `task_plan.md`、`InboundCommitGate.kt`、`DsimEntities.kt`、`DsimDatabase.kt`）。

### T1.1（d981457）
- 首次构建启动即失败：PowerShell 传 `bash -c '…'` 丢引号 → `Cannot convert relative path C:Program to an absolute file`（未到源码编译）。改为写仓库外 `.sh` 后由 Git Bash 执行。
- 重跑：`BUILD SUCCESSFUL in 1m 40s`、JVM `109/109`、`git diff --check` 干净、5 文件 `BOM False / CR 0`。
- 新仪器用例 `MqttLifecycleResponsivenessTest`：`OK (1 test)`、`Time: 35.225`；三种动作 logcat `frames=331/329/330`、`maxMainGapMs=28/46/25`（阈值 5 s）；每轮先断言黑洞 Broker 收到可解密 OFFLINE。
- 用例卫生：前置断言 `sync_outbox` 为空；运行期临时 `RECEIVE_ONLY`，`finally` 还原 `USAGE_MODE` 原字节；不改已保存 broker/topic/口令、不清库。

### T1.2（9a87a46）
- `BUILD SUCCESSFUL in 1m 32s`、JVM `109/109`、编码/空白干净。
- `RadarFlowLifecycleTest`：`OK (3 tests)`、`Time: 83.476`（前后台订阅数 1→0→1→0；两 Activity 同栈时只有 STARTED 者订阅；主线程被占 3 s 时 200 次 `tryEmit` 全成功且 <200 ms，收集器存活）。

### T1.3（46e2bd3）
- `BUILD SUCCESSFUL in 1m 8s`、JVM `109/109`。
- `DeviceIdentityTest` 在两个独立进程各 `OK (2 tests)`，身份 `e789752a53d54893a1c649776accdb4c` 跨进程不变；首次因 5554 未授权 READ_SMS 失败（`pm grant` 后通过）。
- release 对比（5556）：`assembleRelease -x lintVital*` + `zipalign` + `apksigner`（debug keystore）→ `install -r` 覆盖安装，debug 与 release 的 `publishing on dsim/test/9f3a2b/7218351f6a5342608458192447933cc5` 完全一致；release 下 `run-as` 被拒（`package not debuggable`）；随后装回 debug，身份不变。
- 卸载重装（5554）：卸载前 `e789752a…`、`dsim_core_database` 90112 B；重装后身份文件尚不存在 → 跑用例 `OK (2 tests) Time: 9.2`，日志 `historyImport deviceId=aa6b284f first(scanned=40,imported=40,skipped=0) second(scanned=40,imported=0,skipped=40) rows=40->40 system=40`，新身份 `aa6b284f1c2249a38b18d6c32f1675e5`。
- 事故：5554 模拟器实例中途消失（宿主仅剩 5556 的 qemu）；清 lock 冷启动恢复，`session_act kill` 终止卡住脚本后重跑，AVD 数据未丢。

### T1.4（3b0e1c2）
- 24 个 main 文件全量转换 + 新 `DsimLog`/`DsimLogTest`；首轮 2 例测试失败（期望与正则边界），加字母数字前后界并修正期望后 `BUILD SUCCESSFUL in 1m 5s`、JVM `113/113`、`git grep -c` 裸 `Log.*` = 0、29 文件编码检查通过。
- 设备取证：5554 真注入短信 → 完整号码/正文各 0 次、`from=*******8999`、mappingKey 完整；5556 收"可解密但无法识别"的云端消息（宿主 Python 按 DSM3 加密、本机 Mosquitto 发布、244 字节 QoS1）→ marker/明文/号码各 0 次，日志 `action=UNKNOWN_PROBE length=149 hash=f4b60645`。

### T1.5（cefd02a）
- `BUILD SUCCESSFUL in 2m 9s`、JVM `113/113`、编码检查 `[]`。
- `CredentialStorageWarningTest`：`OK (1 test)`、`Time: 17.689`；替身使 `seal` 返回 null → 保存成功、口令可读、`PASSWORD_ENC` 为空、警示 VISIBLE 且文案逐字相符；撤替身 → 密封成功、警示 GONE；`finally` 停服务并还原 prefs（实读 `<map />`）。

### 阶段末回归（完成）
- `stage1-regression.sh`（session-5125e2a9c540aa64a413034d）：仪器 `20/20 completed (0 skipped) (0 failed)`、`BUILD SUCCESSFUL in 3m 49s`；计划原文回归命令 `BUILD SUCCESSFUL in 18s`、`FINAL JVM tests=113 failures=0 errors=0`；该次 `verify-dsim` = `PASS=8 FAIL=1`（5556 未拉起，harness 问题）。
- `stage1-dualdevice.sh`（session-56ee8741f5f4134da5176a26，已 kill）：取到 5556 升级安装后的脏档案证据 `[('d9e0118b7d57f7c6',0,'REMOTE'),('96fad9a2276863b0',1,'LOCAL'),('7218351f6a5342608458192447933cc5',1,'LOCAL')]`，随后 `pm clear` + 重新 onboard 5556；因 5554 被仪器任务卸载而中止。
- `stage1-dualdevice2.sh`（session-66b18f42855490811ef2e255）：重新 onboard 5554 → 双方 `subscribed dsim/test/9f3a2b` → `verify-dsim.sh 5554 5556` = **`PASS=12 FAIL=0`、VERIFY_EXIT=0**；跨设备注入 `STAGE1_XDEV_1789833464`：5554 `skip own echo`=1、5556=0；两台各 1 行同 UUID `fb2add1a-bc37-40d8-ad42-ab9c2da3758f`、`deviceId=63fcfafde93645a0aa48cdf5e227501f`、`status=1`、`sync_outbox`=0；`device_profiles` 两侧各 1 条 LOCAL（5554 local=`63fcfefd…`，5556 local=`fa9e4551…`）。`tmp_dbpull` 已删。
- 工作区：仅审查方 3 个修改 + 只读未跟踪文档 + 两个冒烟目录 + 本账本目录；`ahead 5` 未 push。

## Session: 2026-09-20 审查后跟进（F-1~F-5）

- 偏移修正：起手发现 `.planning/.active_plan` 为 `2026-09-19-arena-skill-check-7d9c2f64`（上一技能烟测轮次遗留，即 git status 中那笔 M），已按任务书意图改指 `2026-09-19-repair-plan-stage1`；保持未提交状态不动其余审查方脏项。
- F-1：`scripts/check-agents-tree.sh` 创建（UTF-8 LF）；首跑 `EXIT=1` 如实列出 10 个遗漏（CloudConfigMessages/CloudTopics/CredentialCodec/CredentialVault/DsimLog/HeartbeatPolicy/NotificationPreferences/ReconnectPolicy/ReplayGuard/SendCostPolicy）；补树 + 探针 `ZzTreeCheckProbe.kt` 负面验证 `EXIT=1`、删除后 `OK：…覆盖全部 74 个 main Kotlin 源文件。EXIT=0`；发现 10 行缩进瑕疵 → amend 未推送提交；最终 commit e4f5ea1。
- F-2：compile+assembleDebug `BUILD_EXIT=0`；commit 4ec7c3f。
- F-3：编辑后 `check-agents-tree.sh` 仍 exit 0；commit e5b7415。
- F-4：两文档 `BOM False / CR 0`；commit b84f56b。
- F-5：`BUILD SUCCESSFUL in 38s`、`FINAL JVM tests=113 failures=0 errors=0`；commit fc70d45。
- lint：`:app:lintDebug` → 文本报尾部 `5 errors, 312 warnings`（基线一致）、`NewApi_count=0`。
- FIXES 记录段追加（末尾补漏掉的尾换行 → amend）→ commit f516e68。

## Test Results
| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| T1.1 弱网主线程 | 无 >5 s 阻塞 | 最大帧间隔 25–46 ms，3/3 动作 | pass |
| T1.2 收集生命周期 | 退后台不收集、发射不挂起 | subscriptionCount 1/0/1/0；200 tryEmit <200 ms | pass |
| T1.3 身份 | debug=release、重装后去重仍命中 | 同机同值；重装新 UUID + `imported=0 skipped=40` | pass |
| T1.4 脱敏 | logcat 无完整号码/正文 | 0 次出现；掩码与 action/length/hash 如预期 | pass |
| T1.5 警示 | 替身→警示；正常→无警示 | 逐字文案 VISIBLE / GONE | pass |
| 阶段末双机 12/12 | PASS=12 FAIL=0 | `PASS=12 FAIL=0`、exit 0（重跑后） | pass |
| 全套仪器 | 20 例通过 | `20/20 completed (0 skipped) (0 failed)`、`BUILD SUCCESSFUL in 3m 49s` | pass |
| 计划原文全局回归 | 编译 + 单测全绿 | `BUILD SUCCESSFUL in 18s`、`tests=113 failures=0 errors=0` | pass |
| 自回声 / isLocalDevice / 跨设备单份 | 各 1 行、单 LOCAL | 5554 echo 跳过 1 行；两台同 UUID 各 1 行；两侧 local_count=1 | pass |

## Error Log
见 `task_plan.md` 的 Errors Encountered 表。

## Session: 2026-09-20 收尾审查 F-6/F-7/F-8
- 已执行起点 git log --oneline -8、git status、adb devices；5554 应用查询空输出。未安装应用、未手动 pm grant。
- 本轮 MCP task=tsk_b776448cfe1265fd；严格按 F-6 → 回归约定 → F-7 → F-8 → 收尾顺序。

### 本轮逐任务结果
- F-6：c66a18b，权限规则+Assume+仅测试依赖。Gradle直接负责新装，20/20（0 skipped/failed），BUILD SUCCESSFUL in 3m，exit0；导入46条、重导0新增/46跳过。UTP结束后5554应用查询仍为空，未为双机回归重新安装。
- 防复发：23c7e99，仅 AGENTS §8.3 加回归门槛。
- F-7：21fea7d，Topics.kt探针修前exit0（复现误通过）；修后exit1且指名；file_edit删除后exit0，74文件，探针不存在；bash -n与diff --check通过。
- F-8：8fe7eb9，仅新增一句；HISTORICAL_CONTENT_UNCHANGED=True，历史基线数值未改。
- 已启动编译+强制JVM回归+lint复跑，session-1b0a8186ad601de74016771f，待收尾解析。

### 本轮最终回归与交接
- session-1b0a8186ad601de74016771f：编译+clean JVM成功22s（113/113）；lint失败1m3s（5 errors/312 warnings，NewApi=0），完整日志已保存，未为绿灯改产品代码。
- f6-f8-final-verify-20260920.py：217受保护文件摘要完全一致；9个改动文件UTF8/BOM0/CR0/末尾LF；MAIN_DIFF_EMPTY=True；仪器20/20、目标historyImport未跳过；两种diff --check通过。
- F-6 c66a18b；回归约定23c7e99；F-7 21fea7d；F-8 8fe7eb9；本次FIXES与既有三件账本另做独立收尾commit。
- 未push；5554仍未安装应用（UTP卸载后未恢复）；不清理既有脏项；不开始阶段2。停止执行，等待用户转达审查。
