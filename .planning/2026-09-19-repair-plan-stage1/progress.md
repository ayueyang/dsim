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

## Session: F-9/F-10 独立复核（2026-09-20）
- MCP任务tsk_95d3e44737290799；git log/status、ce54744..HEAD差异、指定文档均已读取；树脚本74文件exit0，diff --check无输出。
- 新AVD复用已装API36.1 google_apis_playstore/x86_64镜像，不下载低API、不启动候选任务。

- 空库session-30d6b77d75628d9a448d925d exit0：BUILD SUCCESSFUL in5m37s；XML20=19pass+1skip（historyImport found0）+0failed。保存f9f10-empty-suite.log、empty-result.xml/textproto、empty-instrumentation.log、empty-case-analysis.log至既有仓库外证据目录。
- 原套件跑完后才向androidTest加入临时F9F10TemporaryProbe.kt；单独assemble测试APK，不混入原20例计数，最终必须删除。

- 探针构建：BUILD SUCCESSFUL in1m27s；session-eb03729aab134d96aa7a4035 exit0，3类各OK(1test)。不加-g的新装false→规则true→第二轮无规则true；adopt/不adopt均返回sms/0却persisted0，总数0→0，读回/清理/drop完成。
- 播种验证session-aeddbd89dd3756d2e517e37d exit0：Google Messages默认、SIM LOADED、dSIM未onboarding；emu sms send后_row1；原historyImport单例通过、首次导入1二次0。
- 已通过file_edit删除仓库F9F10TemporaryProbe.kt；pm clear实测READ_SMS true→false，随后卸载app/test，包列表空、系统短信1行。
- 播种后新装原套件session-fc830841fd07e94f89ea84d8 exit0：BUILD SUCCESSFUL in3m25s；XML20唯一case、20passed/0skipped/failure/error，未包含临时类；导入日志first1/second0/rows1→1。UTP收尾卸载两包。
- 00b994d docs(testing): independently verify F-9 and F-10 claims：仅AGENTS.md/TESTING.md/FIXES_2026-09-19.md三文件。最终树74/exit0、diff-check空、UTF8/BOM0/CRLF0/尾LF、app/src零差异；247保护摘要与起点完全一致。
- 新AVD5558已明确按名称核对后emu kill，session-73252cef8b1ab507839dd66e=exited，adb devices仅旧5554/5556。保留新AVD当前1条合成SMS供复验，不再称其空库；外部探针源码/日志保留，仓库源码不留临时测试。
- 原始空库与播种后XML/textproto/runner日志均已另存C:/Users/admin/AgentDock/dsim-stage1-evidence；原空库报告在第二轮覆盖build目录前保存。f9f10-doc-commit-check.log保存提交前编码/范围/树及关停后设备列表。
- 未执行/待批准：所有任务二候选、push、D1–D5、W23、F4过期SEND_CMD；本轮不重跑无关JVM/lint、未修五条既有lint错误。新发现UTP控制台计数不一致仅记录，是否独立排查待用户批准。
- 本轮文档提交后ahead24/behind0；本三件账本另行收尾提交，最终状态以末次git核验为准。阶段0/1维持关闭，本轮结束停下。

## Session: 批次 A（2026-09-20）
- MCP task tsk_26c0ce14de08e11c；PLAN_ID固定原stage1；无新计划目录。
- 已读取AGENTS/权威裁决/修复计划阶段3/F6-F8背景/FIXES/三账本；SDK低API下载session-5320b15c5485c7c43e65fd4f并行进行。

- T3.1 7c00b72：compile45s成功；源码getRecentConversationsFlow零匹配。T3.2 b07da50：compile+JVM55s成功，XML113/0/0/0，hasKnownMagic零匹配。
- T3.3 dcd4275：API28定向connected1/1，BUILD SUCCESSFUL3m32s；异常模拟IllegalState/Security受控，locked-boot忽略。
- T3.4 929f088：首跑harness返回非void失败（1m2s）；修harness后真实重复行失败（56s，expected1/actual2）；产品修复后XML1/1且窗口边界断言通过（1m6s）。
- T3.5 3c881f0：compile32s、树74/exit0、backup XML排除元素完全相同，版本目录不存在属实；计费注释未改。
- T3.6 7d3352c：API24定向connected1/1，2m21s；三个真实入口日志断言通过，三文件尾LF。
- 低API：新增AVD API24/5560（Android7.0/NYC/6696031）、API28/5562（Android9/PSR1.180720.122/6736742），无覆写旧AVD；nonce/真QoS1 publish和两守卫专门仪器3例开始运行。
- 另启动裁决保留的5558，作为API36真实采集verify-dsim设备；其有历史合成fixture，不称空库。仅新测试配置，口令运行时生成经DSIM_TEST_PASSWORD传入，不入源码/日志。

- API24核心运行证据已取得：PlatformCompatibilityTest XML3/3，2m6s；nonce16字符/12字节、真实QoS1 PUBACK、DSM3回环一致；legacy_getSimState()返回slot0，legacy_activeSlotInfo解析subscription1。原始runner/XML/textproto已归档；API24应用由UTP卸载，专用AVD已停机保留。
- API28全套首次前置脚本失败，尚未启动Gradle：shell读取content://sms被READ_SMS拒绝，脚本错误地把无Row输出算0后报fixture未入库；这个结论无效。emu注入已OK，未用root/手工授权绕过；修订外部driver，改由原DeviceIdentityTest自带READ_SMS规则实际读回/明确跳过，全套重跑中。

### 批次A最终回归与停机收尾
- API28 retry session-72e7aadeb50d74e75bc155b6 exit0，BUILD SUCCESSFUL3m11s；XML26/0/0/0，与源码26逐个case一致；historyImport实际first1/second0/rows1→1/system1，非fixture跳过。XML/runner/textproto在覆盖前归档。
- API28 runner：SUBSCRIPTION_GUARD legacy_activeSlotInfo slot0 resolved1；SIM_STATE_GUARD getSimState(slot) ready=true slots1；NONCE_QOS1_PUBLISH nonceChars16 nonceBytes12 puback=true DSM3_roundtrip=true。
- 5d2e600提交PlatformCompatibilityTest三例与TESTING §5.9；最终JVM113/0/0/0、17XML逐份另存读回一致。未改生产低API代码，未用JVM代替设备证明。
- API36 verify session-1f454b738c2e73195e15dcc7 exit0/460993ms，PASS8 FAIL0；真实SMS_DELIVER/Room/通知/FG。onboarding仍在SimBinding，不声明完整onboarding通过。
- 可选raw-PDU session-538a47f42d1a902136dc19a7 exit1：第一次注入OK但Room标记0，无第二次；原始日志/快照保留，根因未验证。
- 补充标准emu sms send两次：session-d5386e87d51f87f17aeb65ad exit0；02:43:01与02:43:05两条生产SMS_DELIVER，同正文仅Room id2一行；REAL_SMS_DELIVERY_COUNT=2、TWO_REAL_BROADCASTS_ONE_ROOM_ROW=True。
- 清理本轮tmp_dbpull/emulator-5558；新5560/5562停机保留，app/test被UTP卸载。5558卸载当前app后立即查询角色曾仍显示dSIM；重启复核session-9a966a2ad166fdd5ab2040c5 exit0，系统已恢复Google Messages，未手工赋权，三条合成SMS保留，再次停机。旧5554/5556未改。
- 保护74文件逐一SHA256一致；Manifest对基线只有locked-boot删除，Phase2出站/结果/outbox/ACK代码未动；源文档UTF8/BOM0/CRLF0/尾LF，git diff --check与树74通过。
- FIXES追加逐项文件/diff/命令/真实输出、失败与边界；只更新原stage1三账本。六项各自提交+低API独立测试提交，收尾文档另commit；无push，批次A完成停下。

## Session: 批次A返工与批次B（2026-09-20）
- 起点dd5a114/0behind8ahead；task tsk_2f4ca92ab1929e9b；原计划目录不变，审查源与指定章节已读；41受保护文件快照保存仓库外。
- 新增相差4秒仪器用例，保留同PDU/id/uuid断言；生产尚未修改。首轮外部driver传-P参数未加引号，PowerShell拆分为-Pandroid与.test...，Gradle任务选择失败，未执行测试。自动复制的26例为之前审查遗留报告，**不算本轮红绿/基线证据**。修参数引用并加报告mtime新鲜度检查后强制重跑lint与目标仪器，保留原失败日志。

- T3.4有效红灯：batchB-dedup-red-retry，2例中4秒反例expected2/actual1，旧同PDU例通过；独立lint实际5/313/NewApi0。修两路径+F11后新装27/27、JVM113，lint5/312/NewApi0，仅移除UnsafeProtectedBroadcastReceiver；Gradle组合exit1仅由存量lint错误导致，connected正常完成。1281bfd/469c788分别提交。
- T2.1 b4d3d4d：并发最后一段仅1个claim，失败multipart计2段而非退费，重复UUID免费、冲突回滚、认领日计费，共5仪器通过；compile/JVM/connected成功1m9s。
- T2.2 83bbc06：第二outbox写失败触发器使账本/短信/两队列全部回滚；重复回调分别补齐缺行、部分失败/隐私/换组/无效段号/重开DB，共6仪器通过，1m8s。实际进程死亡与双机恢复留最终回归。
- T2.3 88da47e：仅active REMOTE_SHADOW可选，发送/重试/最终publish守卫均加；新装2例首次发现setOnClickListener(null)不会清除clickable标志，显式false后2/2通过1m14s。提交前发现strings.xml原本无尾LF，本轮补齐后编码检查通过。
- F4先加测试，生产未改：JVM114中拒绝副作用Retryable被吞的新增例红灯；connected未启动，因为5562启动命令的30分钟timeout已触发，不算设备用例结果。已确认managed emulator session timeout，原数据保留，重启同AVD并将会话时限设4小时，重跑6设备负测。
- 另发现SystemSmsHistoryImporter第三处±2min误并风险，已向用户说明，将按同源P1补等值判重与系统库双fixture红绿；同时披露非默认应用provider时间/PDU时间不一致时宁可保留疑似重复，不用窗口吞信。

- F4有效设备红灯6例3失败；首次绿灯编译因误用CloudConfig.isConfigured失败（无新测试结果），改为检查broker/topic/password后6/6、JVM115/115通过1m22s，3ac782a独立提交。
- 历史导入实provider双fixture（modem间隔4秒）1例红灯expected2actual1，改精确provider DATE后1/1且重复导入id/uuid稳定，49a695d独立提交；未用adopted-shell插入假设。
- 首次全量最终新装47/47、0失败/错误/跳过，case逐条列出；lint实际5errors/312warnings/NewApi0，组合exit1唯一失败任务lintDebug（存量错误）。unit在这一全量轮UP-TO-DATE，不冒充新测，115/115使用前一轮新鲜记录。
- 为双机离线收敛补F4显式opt-in测试分支，不改生产、不发运营商短信；再次全量connected并单任务--rerun强制JVM正在执行。
- 新peer首次冷启动未完成时onboard安装失败，已保存失败日志；等待sys.boot_completed=1后重试真实UI引导，尚未据脚本exit0认定引导完成。

- 第二次新装全量 batchB-full-live-probe：47/47、0失败/错误/跳过，BUILD SUCCESSFUL 3m57s exit0；JVM单任务--rerun实际115/115。逐例XML与源码集合完全相等47，保护43文件哈希无差异（batchB-full-case-protection-audit.log）。F4新增f4LiveUuid/f4Requester opt-in离线双机探针已编译/常规套件通过，尚待现场分支运行、未提交。
- 独立接收端5564首次冷启动完成后重跑引导，但脚本exit0时仍未HAS_SEEN_ONBOARDING；再次确认SMS role已变com.example.dsim，并从实际步骤6点击“进入dSIM”，确认HAS_SEEN_ONBOARDING=true、BIDIRECTIONAL_SYNC、AUTO_CONNECT=true，已安装最终test APK。
- 执行端改用5562（API28）与5564配对，不启动/不改5558，5554/5556也未干预。5562通过真实系统ACTION_CHANGE_DEFAULT对话框获默认SMS；真实UI配置脚本结束，但完成标记仍缺，补走导引。首次显式启动非导出OnboardingActivity被拒（安全边界正常），外部脚本改为从导出的SmsListActivity冷启动、自动路由引导，不改manifest/产品可见性。
- 外部双机驱动batchB-live-driver.py已落地（preflight/t34/t23/f4-wire/f4-offline/t22），尚未执行实际验收。使用合成lab组和号码、不调用运营商发送；T22将真实kill于生产onSentResult提交后，不能冒充真实运营商回调。

## Phase 29 收尾续作（2026-09-20，本轮起点49a695d）

- 已读三件账本、批次A审查、裁决、T2.1–T2.3及F4提案；PLAN_ID固定stage1，不创建新计划。原43项保护哈希一致；本轮仓库外phase29-resume-protected.json新增286文件保护快照，涵盖除6个负责文件外的跟踪/未跟踪项。
- 在途F4探针保留：debug显式opt-in、两次拒绝只留一个expired队列、不建执行账本；默认47例分支不变。AGENTS/TESTING为完整约束与历史证据说明，保留并分别提交；三件账本独立归档。
- 发现交接漏记：旧batchB-live-all.log已运行t34通过、t23失败（90秒等待超时）；旧驱动后加REMOTE_SEND_ALLOWED=false前置，历史曾产生PENDING认领。本轮不把旧输出算新证据，不批准运营商发送。驱动实际位于AgentDock/dsim-stage1-evidence/而非AgentDock根目录。
- 本轮preflight首次因5564未显式关闭代发失败，尚未发布探针；5562已false。通过真实设置UI将5564开关true改false并读取prefs确认；未直写prefs。第一次UI驱动未识别设置页顶部，修正外部驱动后成功。
- 编译首轮重复传--console而失败（Gradle未构建）；去重后compileDebugKotlin/compileDebugAndroidTestKotlin BUILD SUCCESSFUL in24s，29项UP-TO-DATE，明确不是重新执行测试。HTTP524后从远端真实日志确认完成，未重复误报。现场六段正在新跑，Phase29不提前标complete。
