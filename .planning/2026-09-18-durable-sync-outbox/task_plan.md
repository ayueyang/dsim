# Batch A — durable incoming-SMS sync (outbox + persistent session)

Base: d9f4dde / 88c3883. Skill: planning-with-files 1.2.0. Docs read: AGENTS.md (C1–C10),
FIXES_2026-09-18.md, REFACTORING.md, TESTING.md. Prior plan dirs left untouched.

## Goal
An incoming SMS captured by `SmsReceiver` must eventually reach every peer even when the MQTT
client is disconnected at capture time and even when the peer is offline at publish time.

## Design decisions
- D1  Outbox is a new Room table `sync_outbox` (v6 → v7, C1). Do NOT overload `sms_messages.status`
      (it already encodes carrier send state -2/-1/0/1). Columns: id PK autogen, uuid (unique),
      kind (`SMS_SYNC`), payloadJson (plaintext SyncPayload JSON, encrypted only at publish time so a
      later password change re-encrypts correctly), groupFingerprint, createdAt, attempts, lastError,
      state (`PENDING` / `SENT`). Rows are deleted on PUBACK, not kept as SENT history (keep table small).
- D2  `SmsReceiver` no longer touches `globalMqttClient`. It writes sms + outbox row in one
      `withTransaction`, then `startForegroundService(ACTION_FLUSH_OUTBOX)`. Receiver budget is now
      DB-only. Usage-mode gate (`canUploadIncomingSms`, C7) is evaluated at enqueue time and stored
      as the decision; the flush loop re-checks `canUseCloud` before publishing.
- D3  `MqttSyncService` gets `OutboxFlusher` (single-flight via Mutex): drains PENDING rows ordered
      by createdAt, publishes QoS1 with `MqttClient.publish` (blocking = waits for PUBACK), deletes
      row on success, increments attempts + lastError on failure and stops the loop (next trigger:
      `connectComplete`, explicit ACTION_FLUSH_OUTBOX, or heartbeat tick).
- D4  Persistent session: clientId = `dSIM_<deviceId>` (stable), `isCleanSession = false`,
      `MqttDefaultFilePersistence(context.filesDir/mqtt)`. Subscribe with QoS1 after each connect
      (idempotent on broker). Subscription survives; broker queues QoS1 while offline.
      RISK: the public test broker may cap session expiry; document, do not "fix" in code.
- D5  Group change (`staticConfig` differs from row.groupFingerprint) → row is deleted, never
      published into a different group (same rule as `publishOutcome`).
- D6  `OutgoingSmsDispatcher.publishOutcome` and `publishEncryptedSms` are NOT migrated to the
      outbox in this batch (scope control). `SystemSmsHistoryImporter` keeps its own ack loop.
- D7  Gate every schema change with a v6→v7 migration test (androidTest, isolated DB) mirroring
      `SendCommandLedgerTest.migrationAddsLedgerWithoutLosingMessages`.

## Steps
- [x] S1 Entities/DAO/DB: `SyncOutboxEntry`, DAO (enqueue, nextPending(limit), delete, markFailed,
      deleteByGroupMismatch, count), `MIGRATION_6_7`, version 7. Compile.
- [x] S2 `SyncOutbox` helper (pure-ish): `enqueueIncomingSms(context, sms, remarkPhone)` and
      `OutboxFlusher.flush(context, client, config)` returning FlushResult(sent, failed, stopped).
      Unit-test the decision logic (group mismatch, mode gate) as pure functions.
- [x] S3 Rewire `SmsReceiver`: remove `publishIncomingSmsToCloud`, enqueue in the same transaction as
      insertMessage, kick service with `ACTION_FLUSH_OUTBOX`.
- [x] S4 `MqttSyncService`: stable clientId, cleanSession=false, file persistence, handle
      `ACTION_FLUSH_OUTBOX`, call flusher from `connectComplete` (both fresh and reconnect) and from the
      heartbeat tick. Notification line shows pending count when > 0.
- [x] S5 Tests: `SyncOutboxMigrationTest` (v6→v7 keeps sms + send_commands), `SyncOutboxDaoTest`
      (enqueue/ordering/delete), JVM `OutboxPolicyTest` (group mismatch drop, attempts increment).
- [x] S6 compileDebugKotlin, testDebugUnitTest, assembleDebug, assembleDebugAndroidTest; run
      androidTest on emulator-5554 (dSIM_B). Never touch physical device e6f587b0 (unauthorized).
- [x] S7 Update docs: AGENTS.md (§3 inbound path, §5 tree, Room v7, C-constraint note on outbox),
      FIXES_2026-09-18.md (add batch-2 section), REFACTORING.md (add W19 durable sync as done +
      fix W1 contradiction + stale metrics). progress.md / checkpoint.md. One commit, no push.

## Out of scope (explicitly)
Local-SIM direct send, default-SMS components, KDF change (batch B), backup rules (batch D).

## Result
All steps done. Commit pending at the time of writing; see progress.md for hashes.
Deviation from D3: flush is also triggered by `ACTION_FLUSH_OUTBOX` when disconnected — it falls
through to the daemon connect path (honours manual-disconnect + auto-connect), then
connectComplete flushes. `SendCommandLedgerTest` migration case had to chain 5→6→7.
