# Batch F2 progress

## S1
- HEAD a9ae199. AgentDock task tsk_53cc1639e1353374.

## S2 code
- New `ReconnectPolicy` (pure): delayForAttempt 5 s doubling, cap 5 min, overflow-safe; shouldReconnect gates.
  `ReconnectPolicyTest` 4 cases.
- MqttSyncService: `setAutomaticReconnect(false)`; `scheduleReconnect(reason, immediate)` single job; `reconnectAttempts`
  reset on subscribe / ACTION_CONNECT / network available; `connectionLost` and the connect catch call it;
  `registerDefaultNetworkCallback` in onCreate (unregistered in onDestroy) -> immediate retry; DISCONNECT / LOCAL_MODE
  cancelReconnect(); stale client: setCallback(null) + disconnectForcibly + close(true) before rebuild; callbacks ignore a
  superseded client (`globalMqttClient !== client`). Notification shows "N 秒后自动重试".

## S3 build
- WMI gradle test+debug+release(-x lintVital*) DONE exit=0 23:53; JVM 68/68 (4 new).

## S4 emulator (API 36)
- Round 1: svc wifi/data disable 23:56:15 -> connection lost -> scheduled 5 s -> 连接失败 attempt 1 (15 s timeout) ->
  scheduled 10 s; SMS captured during outage. enable 23:57:19 -> network available -> subscribed 23:57:30 ->
  outbox[connect] sent=1. 11 s, no force-stop.
- Round 2: same; enable 23:59:51 -> subscribed 00:00:04 -> sent=1. 13 s.
- Cold start with network down: 连接失败 attempts 1..3, backoff 10/20/40 s as designed; enable 00:02:55 -> several
  "network available" (emulator flaps) -> subscribed 00:03:27 (32 s; first tries hit the 15 s connect timeout while
  the emulator's route settled).
- Script: C:\Users\admin\AgentDock\dsim_f2.ps1 <install|check|off|on|coldoff> (each phase < 120 s for exec_command).

## S5 docs
- AGENTS.md: component line + new C20. REFACTORING.md: W4 step 2 done, test list. Arch doc: constants row + R1b.
  HANDOVER §5 items 2 and 3.

## S6
- c8check 182 files 0 violations; git diff --check clean; commit below.
