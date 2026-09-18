# Batch G progress

## S1 baseline 6321ace. Task tsk_7d9f8431b91eccc4.

## S2 code
- `ReplayGuard` (pure): check(senderId, ts, nonce, now, window) -> Accept / Reject(MISSING|STALE|DUPLICATE);
  access-order LinkedHashMap LRU (4096), prune of out-of-window entries once half full. `ReplayGuardTest` 7 cases.
- `MqttPayloadCodec`: encode() = gson + stamp(); stamp(json, now) rewrites ts/nonce (12 random bytes base64url) on
  any JSON object; decodeEnvelope() -> DecodedEnvelope(inbound, ts?, nonce?). No data class / call-site change (C18).
  Codec tests +4 (stamp, restamp, legacy null envelope, non-object passthrough).
- `MqttInboundHandler`: decodeEnvelope -> guard (synchronized, window 24 h for Offline) before any side effect;
  WARN rate-limited per reason per minute.
- `SyncOutbox.flush`: stamp(entry.payloadJson) before encrypt (flush-time freshness).
- Heartbeat fingerprint is computed from fields, not from JSON, so nonce randomness does not cause extra PONGs.

## S3 build: DONE exit=0 05:12; JVM 83/83; debug + release APKs.

## S4 emulator API 36 (C:\Users\admin\AgentDock\dsim_probe_g.py / dsim_probe_g2.py)
1 fresh PING -> PONG True
2 same ciphertext replayed -> 0 extra PONG; log DUPLICATE (nonce seen)
3 ts 20 min old -> 0; log STALE (skew=1200245ms window=600000ms)
4 no envelope -> 0; log MISSING
5 fresh PING again -> PONG True
6 emu sms send -> SyncPayload received with ts+nonce
7 network off 5 s -> SMS captured -> 75 s later on -> delivered; ts - capture = 137 s, ts - network_on = 42 s,
  i.e. stamped at flush (would have been rejected only if > 600 s, but proves the stamp site).
- exec_command note: a probe > 120 s must be split into phases (Cloudflare 524); python output must flush before.

## S5 docs: AGENTS C22 + component line; REFACTORING test list; arch doc protocol table + constants; HANDOVER §5.
## S6 commit below.
