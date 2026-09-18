# Batch B — battery: one-time key derivation, per-device topics, on-change heartbeat (W20)

Base: c765de2. Skill: planning-with-files 1.2.0. Previous plan dir (durable-sync-outbox) untouched.

## Problem (from findings F3 / F6, batch A findings.md)
- Every payload runs PBKDF2 120k rounds with a fresh random salt → 0.2–0.5 s CPU per message,
  no caching possible. Heartbeat every 20 s × N peers; every device also decrypts its own echo.
- Publish and subscribe share one topic, so "is this mine?" requires a full decrypt first.

## Design decisions
- D1  New wire format `DSM3`: `[4B "DSM3"][12B IV][AES-256-GCM ct+tag]`, AAD = magic.
      Master key = PBKDF2-HMAC-SHA256(password, APP_SALT, 120k) derived ONCE per password and
      cached in-process (bounded map). APP_SALT is a fixed app constant: per-password work factor is
      unchanged; the salt only stops cross-application rainbow tables, which 120k rounds already
      make impractical. Key remains a function of the password only (C4).
      DSM2 read path is removed (N2: no users). `encryptMessage`/`decryptMessage` signatures kept so
      the 14 call sites do not change; both annotated `@WorkerThread` (first call still ~0.3 s).
      Byte-level core (`seal`/`open`) separated from Base64 so it is testable on the JVM.
- D2  Topic layout: publish to `<topic>/<deviceId>`, subscribe `<topic>/+` (QoS 1).
      `CloudTopics` is the single place that builds/parses these. `messageArrived` drops messages
      whose topic tail == local deviceId BEFORE decrypting. All 14 publish sites go through
      `CloudTopics.publishTopic(base, deviceId)`. Wildcards in the user-entered base topic are
      rejected at settings level later (batch D); here we just `trimEnd('/')`.
- D3  Heartbeat: tick 30 s; publish PONG only if snapshot fingerprint changed (battery bucketed
      to 5 %, `historyQueue.updatedAt` excluded) or ≥ 120 s since last publish. PING and connect
      still force a publish. `ONLINE_TIMEOUT_MS` 45 s → 5 min (2.5 × max silence).
- D4  Liveness without fast heartbeat: MQTT Last-Will `{"action":"OFFLINE"}` on the device's
      publish topic (encrypted at connect time with the cached key), plus an explicit OFFLINE publish
      on manual disconnect / onDestroy. Peer handler `DeviceDirectoryManager.markOffline` moves
      `lastSeenAt` behind the online window; no schema change.
- D5  Remove the per-PONG Toast (findings F4) — same code block, wrong for users regardless.
- D6  Not in scope: BROADCAST_SMS permission on SmsReceivedReceiver, .gitattributes, W15 (batch C);
      routing SEND_CMD_RESULT / history ACK through the outbox (W21).

## Steps
- [ ] S1 DsimCryptoUtils DSM3 + key cache + @WorkerThread + Log instead of printStackTrace.
- [ ] S2 CloudTopics + HeartbeatPolicy (pure objects).
- [ ] S3 MqttSyncService: subscribe `<topic>/+`, own-echo skip, will, OFFLINE handler, heartbeat
      policy, remove Toast, publish sites → CloudTopics.
- [ ] S4 Other publish sites (DeviceManagerActivity, HistorySyncQueueManager, MainActivity ×2,
      SmsChatActivity, SyncOutbox, SystemSmsHistoryImporter) → CloudTopics.
- [ ] S5 DeviceDirectoryManager.markOffline + ONLINE_TIMEOUT_MS.
- [ ] S6 Tests: DsimCryptoUtilsTest (roundtrip, tamper, wrong pw, short/garbage, cache), CloudTopicsTest,
      HeartbeatPolicyTest. All JVM.
- [ ] S7 Gradle full verification; single-emulator E2E: after connect no "忽略不包含 sms 载荷" warnings
      for own PING/PONG, outbox E2E from batch A still passes, key derived once in logcat.
- [ ] S8 Docs (AGENTS C3 note + §6 item 10, FIXES batch 3, REFACTORING W20 done, architecture doc
      §crypto/§topic), progress/checkpoint, one commit, no push.
