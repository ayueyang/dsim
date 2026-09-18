# Batch G - F9 replay protection (one-shot, all devices upgrade together)

Base: 6321ace. Plan dir: .planning/2026-09-19-replay-guard. Source: durable-sync-outbox/findings.md F9; HANDOVER §5.
User decisions: no compatibility phase (missing ts/nonce = reject). F11 read-sync deferred: `isRead` has no local
producer (nothing marks messages read), so a READ_SYNC action would be dead protocol until read semantics exist.

## Design
- Envelope fields on every encoded payload: `ts` (sender epoch ms) and `nonce` (16 random bytes, base64url).
  Added by `MqttPayloadCodec.encode` (JSON tree post-step), so data classes / call sites do not change (C18 kept:
  the codec is still the only place that knows the wire).
- `ReplayGuard` (pure): `check(senderId, ts, nonce, now, maxSkewMs)` -> Accept / Reject(reason). Reasons: MISSING,
  STALE (|now-ts| > window), DUPLICATE (senderId+nonce seen). LRU of 4096 entries, entries older than window dropped.
  Window: 10 min for everything except OFFLINE: 24 h (the Last Will is encrypted at connect time, so its ts is old
  by the time the broker emits it; replaying OFFLINE only flips an online dot).
- `MqttPayloadCodec.decode` returns the envelope too: `decodeEnvelope(json): Decoded(inbound, ts, nonce)`; the
  `decode()` used by tests stays. `MqttInboundHandler.handleIncomingMessage` runs the guard right after decode and
  before any side effect; rejections log WARN with reason (rate-limited to one line per reason per minute) and bump
  a counter shown in the notification when STALE dominates ("对端设备时间偏差过大").
- Outbox rows: `payloadJson` is stored plaintext and encoded at flush time via encryptOrNull(entry.payloadJson) -
  the envelope must be stamped at flush, not at enqueue, otherwise a row queued offline for an hour is STALE on
  arrival. => `SyncOutbox.flush` calls `MqttPayloadCodec.stamp(entry.payloadJson)` before encrypt. Same for the
  Last Will (`buildOfflineJson` is encoded once, fine: 24 h window) and every other direct encode site (already via
  encode()).
- Duplicate detection key includes senderId so two devices sharing a nonce by chance do not collide.

## Acceptance
- ReplayGuardTest (accept, missing, stale both directions, duplicate, LRU eviction, OFFLINE window) + codec tests
  updated (encode adds ts/nonce; decodeEnvelope). JVM green; debug + release builds.
- Emulator with probe (updated to stamp ts/nonce): fresh PING -> PONG; same ciphertext replayed -> ignored (WARN
  DUPLICATE, no second PONG); ts 20 min old -> ignored (STALE); no ts -> ignored (MISSING); SMS sync still works;
  outbox row queued during an outage of > 10 min still delivered and accepted (stamp at flush).
- c8check + diff --check; one local commit; no push. Docs: AGENTS C22 + C18 note, arch doc protocol section,
  HANDOVER §5, REFACTORING metrics.

## Steps
- [x] S1 plan + task + baseline 6321ace
- [x] S2 ReplayGuard + codec + handler + outbox stamp + tests
- [x] S3 build
- [x] S4 emulator (probe v2)
- [x] S5 docs
- [x] S6 commit
