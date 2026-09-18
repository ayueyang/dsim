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

## E2 (W5) — done
- New `MqttProtocol.kt`: `MqttAction` consts, `sealed interface MqttInbound` (SendCmd / SendCmdResult / HistorySyncAckMsg /
  HistoryQueueBatch / Ping / Pong / Offline / SmsSync), nested `QueueTargetMsg` / `SimSnapshotMsg` / `HistoryQueueStateMsg`,
  `MqttPayloadCodec.encode/decode/senderId` (Gson, disableHtmlEscaping, nulls omitted, decode never throws).
- `handleIncomingMessage`: 8-step if-chain -> `when (inbound)` exhaustive; unknown/garbage logs one WARN and returns.
- Producers migrated: SmsChatActivity (SEND_CMD), OutgoingSmsDispatcher (SEND_CMD_RESULT + SyncPayload), HistorySyncQueueManager
  (HISTORY_QUEUE_BATCH), DeviceManagerActivity (PING), MqttSyncService (ACK / RESULT / PING / PONG / OFFLINE / will),
  MainActivity + SystemSmsHistoryImporter + SyncOutbox (SyncPayload encode). DeviceDirectoryManager.saveRemoteSnapshot takes `Pong`.
- `DsimCryptoUtils.encryptMessage` -> `encryptOrNull(): String?`; `ENCRYPTION_ERROR` const deleted; 11 sentinel compares -> `?: return`.
- `SendCmdPayload.kt` deleted (superseded by `SendCmd`, which also carries deviceId/deviceName).
- Wire format unchanged. PONG fingerprint input switched from JSONArray.toString() to a stable joined string; the first PONG after
  upgrade is therefore "changed" (one extra publish), harmless.
- Tests: new `MqttPayloadCodecTest` 10 cases (round trips, legacy PONG with `position:null`, legacy RESULT without `state`,
  garbage/unknown -> null, wrong-typed field -> null, senderId per type, nulls omitted). JVM 62/62.
- Build: `testDebugUnitTest assembleDebug` DONE exit=0; `assembleRelease -x lintVital*` DONE exit=0 (20:17:55).
- Emulator (dsim_probe_w5.py, external paho + Python AES-GCM, pre-W5 JSON shapes):
  legacy PONG accepted; PING -> emulator answered PONG with keys [action,battery,deviceId,deviceName,historyQueue,isCharging,
  isDefaultSms,sims], sims[0] = {mappingKey ICCID_8986..., subscriptionId 2, slotIndex 0, phone +13800138001, mode ROOT_ICCID},
  historyQueue without `position` (null omitted -> old builds read optInt default, fine);
  `{"action":"NOPE"}` and `{"hello":"world"}` -> "忽略无法识别的云端消息" and nothing else; OFFLINE -> "peer OFFLINE: peerprobeW5";
  real inbound SMS -> dSIM_Receiver captured -> dSIM_Outbox flush sent=1 remaining=0.
- Docs: REFACTORING.md (metrics, W5 heading/result, W11 table, batch table), AGENTS.md (tree, item 8, new C18).
- Note: WMI-detached `cmd.exe /c python > log` produced no log file; running the probe via exec_command directly (~50 s) works.
