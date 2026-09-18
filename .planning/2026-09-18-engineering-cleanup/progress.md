# Batch E progress

## S1 baseline
- HEAD 842a32a, ahead origin/main 16, untracked only HANDOVER_2026-09-18.md + session log. OK.

## E1 (W10 + W11 + F13) — done
- S2: `git rm` DsimMqttEngine.kt / DsimNetworkEngine.kt / res/layout/item_sms.xml / gradle/libs.versions.toml;
  app/build.gradle.kts: dropped `com.hivemq:hivemq-mqtt-client:1.3.3` and `META-INF/io.netty.versions.properties` exclude.
- S3: DsimDao -33 lines (getMessagesByAddress Flow, countSimilarLocalMessage, getRecentConversations,
  getMessagesByAddressList, getAllSimConfigsForUi); 3 callers -> getAllSimConfigs(). No schema change.
- S4: WMI-detached gradle `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease -x lintVital*`
  -> `DONE exit=0`. JVM 52/52 (8 suites, 0 fail). app-debug.apk 7,224,383 B @19:50; app-release-unsigned.apk 5,954,080 B @19:51.
- S5: emulator-5554 install -r + pm grant x8 + role add-role-holder SMS + monkey launch; `emu sms send 10086 ...`
  -> `dSIM_Receiver: Captured incoming SMS action=SMS_DELIVER from=10086` -> `dSIM_SyncService: subscribed dsim/test/9f3a2b/+`
  -> `dSIM_Outbox: flush sent=1 dropped=0 failed=0 remaining=0` -> `outbox[capture] sent=1 failed=0 remaining=0`.
  Note: cold start on this emulator takes ~60 s of class verification before the receiver logs; don't misjudge as silent.
- S6: AGENTS.md (tree, item 8), REFACTORING.md (metrics row, W10/W11 headings, batch table D/E), architecture doc
  (dep table, 2.3, tree, 18.4, file table, layout count).
- S7: c8check 176 files 0 violations; `git diff --cached --check` clean.
- Helper added outside repo: C:\Users\admin\AgentDock\dsim_launch.ps1 (WMI-detached gradle launcher, avoids nested-quote issue).

## Next: E2 = W5 protocol data classes (separate plan step / commit).
