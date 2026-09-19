# Batch I progress

## S1 baseline 2e5573f. Task tsk_db291fc674b34102. Emulator dSIM_B (emulator-5554) carried the batch-H build.

## S2 control run (HEAD 2e5573f build) — burst probe `C:\Users\admin\AgentDock\dsim_burst.py kill 400 1.0 0`
- Environment finding first: public broker.emqx.io silently drops QoS1 bursts from one publisher (40 msgs -> an
  independent witness subscriber saw 10; 200 -> 70; `dsim_brokertest.py`). Not a dSIM bug. Switched the E2E to a local
  Docker Mosquitto 2.1 (`docker run -d --name dsim-mosq -p 1883:1883 eclipse-mosquitto:2`, `log_type all`,
  persistence on) and pointed the emulator at `tcp://10.0.2.2:1883` via `dsim_setbroker.sh` (prefs export / edit /
  push / run-as cp; needs `MSYS_NO_PATHCONV=1` under Git Bash). Sanity: 100 msgs, no kill -> 100/100.
- Control: 400 unique silent SmsSync rows QoS1, `am force-stop` at +1.7 s, relaunch. Broker log: 525 PUBLISH sent,
  505 PUBACK received. Room rows 357/400, **lost 43** (idx 0, 4-45: acked by Paho before the coroutine inserted).
  0 redeliveries. Witness saw 400/400 -> the loss is on the receiving device.

## S3 code
- `InboundDispatcher` (pure Kotlin + Log): own echo -> ack, no handler; handler ok -> ack; handler exception -> log +
  ack; cancelled -> rethrow, no ack. `onMessage` returns the Job for tests.
- `MqttSyncService.connectAndSubscribe`: `client.setManualAcks(true)` before connect; dispatcher per client with
  `ack = { id, qos -> client.messageArrivedComplete(id, qos) }`; `messageArrived` is a one-liner.
- `MqttInboundHandler.handleIncomingMessage`: `catch (CancellationException) { throw e }` before the generic catch.
- `InboundDispatcherTest` 7 cases (echo, ack-after-handler via gate, cancelAndJoin never acks, scope.cancel leaves
  in-flight unacked, exception still acks, ack throwing does not propagate, out-of-base sender is null).

## S4 build: compileDebugKotlin + testDebugUnitTest DONE exit=0 (JVM 94/94); assembleDebug + assembleRelease
(-x lintVital*) DONE exit=0 10:19.

## S5 emulator (new build, same broker, same probe parameters)
- `dsim_burst.py kill 400 1.0 0`: force-stop at +2.6 s; rows before relaunch 0; after relaunch **400/400, lost 0**;
  broker log `session taken over` then 20x `Sending PUBLISH ... (d1, q1 ...)`; app log 20x `broker redelivered ... dup=true`;
  no ReplayGuard rejects; broker totals 423 PUBLISH / 403 PUBACK for the run.
- `dsim_smoke_i.py`: PING->PONG 0.5 s; replayed PING -> 0 extra PONG + `DUPLICATE` WARN; `emu sms send` ->
  `outbox[capture] sent=1 failed=0 remaining=0` 3.6 s; 300 msgs sustained (> Mosquitto max_inflight 20), no kill ->
  300/300 in 62 s, PUBACK cadence tracks PUBLISH cadence (no stall).
- Emulator at 108 MB free / 0.9 GB swap during the run; throughput (~5 rows/s) is bounded by the 2 GB emulator, not by
  the ack change (per-message work is unchanged).

## S6 docs: AGENTS (C24, inbound path in §3, file tree), FIXES 第九批 18-20 + broker note, REFACTORING metrics,
HANDOVER §5 item 7/8, arch doc §9.1 table refreshed (was stale: still said MemoryPersistence / cleanSession=true) + §9.3 note.

## S7 commit below.

Commit c561a6e (12 files, +462/-17). Not pushed. HANDOVER_2026-09-18.md (previously untracked) is now tracked in this commit.
