# Batch F2 - reconnect after network outage (W4 step 2: connection state machine)

Base: a9ae199. Plan dir: .planning/2026-09-18-reconnect-policy. Source: HANDOVER §5 item 2; engineering-cleanup/progress.md E6 env note.

## Root cause (code reading, MqttSyncService.connectAndSubscribe + Paho callbacks)
1. A failed initial `connect()` (network up but no route yet / broker unreachable) only logs 连接失败. Nothing schedules a
   retry: Paho automaticReconnect works only after a successful connect followed by connectionLost.
2. While Paho is auto-reconnecting, any FLUSH_OUTBOX / INIT_DAEMON start reaches `close()` on the reconnecting client
   (throws, swallowed), builds a second MqttClient with the same clientId + MqttDefaultFilePersistence dir, and `connect()`
   fails (persistence in use / no network) -> 连接失败, old client orphaned. Matches "一次连接失败然后沉默".

## Design
- Service owns reconnection. `setAutomaticReconnect(false)`; `connectionLost` and connect failures call
  `scheduleReconnect(reason)`: one job at a time, delay = `ReconnectPolicy.delayForAttempt(n)` (5 s doubling, cap 5 min),
  attempt counter reset on successful subscribe.
- `ConnectivityManager.registerDefaultNetworkCallback`: `onAvailable` -> if not connected and reconnect allowed, retry NOW
  (attempt counter reset). Registered in onCreate, unregistered in onDestroy.
- Reconnect allowed = canUseCloud && !manualDisconnectInCurrentSession && isAutoReconnectEnabled && config complete.
  Manual disconnect / local mode / destroy cancel the pending job.
- Before creating a new client: `disconnectForcibly` + `close(true)` so the persistence lock is released; never leave an
  orphan with a live callback.
- The user-facing 断线自动重连 switch now gates the service scheduler instead of Paho's timer (same meaning).
- Invariants untouched: C12 (fixed clientId, cleanSession=false, file persistence), C11/C13/C14.

## Acceptance
- New `ReconnectPolicyTest`; JVM tests pass; assembleDebug + assembleRelease(-x lintVital*) exit=0.
- Emulator: `svc wifi disable; svc data disable` 60 s, send an SMS during outage, `enable` -> service reconnects WITHOUT
  force-stop (log 已恢复连接 / subscribed), outage SMS flushed sent=1. Repeat twice. Also: start service with network
  down -> 连接失败 -> retry log -> connects when network returns.
- c8check + diff --check; single local commit, no push.

## Steps
- [x] S1 plan dir + task + baseline a9ae199
- [x] S2 ReconnectPolicy + test; service changes
- [x] S3 build
- [x] S4 emulator outage A/B
- [x] S5 docs (AGENTS C20?, REFACTORING W4, HANDOVER §5 item 2/3, arch doc)
- [x] S6 c8check + review + commit
