# Batch I - inbound at-least-once: manual PUBACK after the handler commits

Base: 2e5573f. Plan dir: .planning/2026-09-19-inbound-manual-ack. Source: code reading of
MqttSyncService.messageArrived + MqttInboundHandler (not in findings.md; HANDOVER §5 list is exhausted).

## Problem
Paho sends PUBACK for a QoS 1 PUBLISH as soon as `messageArrived` returns. Ours returns immediately after
`serviceScope.launch { inbound.handleIncomingMessage(...) }`, so the broker marks the message delivered while
the Room insert is still pending. Process death in that window (LMK, force-stop, `serviceScope.cancel()` in
onDestroy) loses the message on the receiving device; the persistent session (C12) cannot help because the
ack already went out. Second defect: `handleIncomingMessage` has a bare `catch (e: Exception)` that swallows
`CancellationException`, so a cancelled handler returns "normally".

## Design
- `client.setManualAcks(true)` right after constructing the Paho client.
- New `InboundDispatcher(scope, baseTopic, localDeviceId, handler, ack)` (pure Kotlin + Log, unit-testable):
  `onMessage(topic, payload, id, qos, dup)`: own echo -> ack immediately, no handler; otherwise launch
  handler, then `ensureActive()`, then ack. Cancellation -> rethrow, NO ack (broker redelivers to the next
  session; DB dedupes by uuid, `send_commands` ledger dedupes SEND_CMD, ReplayGuard is per-process so a new
  process accepts the redelivery). Non-cancellation exception -> log + ack (poison message must not loop).
  Every delivery is acked eventually; otherwise the broker's in-flight window (EMQX default 32) fills and
  delivery stalls.
- Service: `messageArrived` becomes a one-liner into the dispatcher; ack lambda =
  `client.messageArrivedComplete(id, qos)` on the delivering client instance (a stale client throws -> logged,
  broker redelivers).
- `MqttInboundHandler.handleIncomingMessage`: add `catch (e: CancellationException) { throw e }`.
- Log `broker redelivered id=… dup=true` at debug so the E2E evidence is visible.
- No schema change. No protocol change. Old and new builds interoperate (ack timing is local).

## Acceptance
- `InboundDispatcherTest`: own echo acked w/o handler; ack strictly after handler completes (deferred);
  cancelled handler never acks; handler exception still acks. JVM green; assembleDebug + assembleRelease
  (skip lintVital).
- Emulator A/B burst: probe publishes N=400 unique silent SmsSync rows QoS1, `am force-stop` ~1.5 s in, relaunch,
  wait, pull Room DB (run-as cat, incl. -wal) and count rows. Control (HEAD build): rows < N expected
  (PUBACK'd-but-not-inserted backlog lost). New build: rows == N, logcat shows `dup=true` redeliveries.
  Plus the usual: PING->PONG, real `emu sms send` -> outbox sent=1.
- c8check + git diff --check; one local commit; no push. Docs: AGENTS C24 + §3 inbound path, FIXES batch 6,
  REFACTORING metrics/tests, arch doc §9.1, HANDOVER §5.

## Steps
- [x] S1 plan + task + baseline 2e5573f
- [x] S2 control run on HEAD build (burst A)
- [x] S3 InboundDispatcher + service wiring + handler catch fix + tests
- [x] S4 build
- [x] S5 emulator burst B + smoke
- [x] S6 docs
- [ ] S7 commit
