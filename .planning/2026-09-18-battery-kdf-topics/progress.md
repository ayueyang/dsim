# Progress — batch B
2026-09-18: plan created. Emulator dSIM_B still up on emulator-5554 from batch A. Gradle/emulator
launch: detached via WMI (see ../2026-09-18-durable-sync-outbox/progress.md tooling notes).

## Implementation (2026-09-18, baseline c765de2)
| Step | Result |
|---|---|
| S1 `DsimCryptoUtils` → DSM3, fixed salt, per-password key cache (LRU 4), `seal/open` internal for JVM tests | done |
| S2 `CloudTopics` (`<base>/<deviceId>` publish, `<base>/+` subscribe, `senderOf`, `isOwnEcho`), `HeartbeatPolicy` (TICK 30 s, MAX_SILENCE 120 s, battery bucket 5 %) | done |
| S3 `MqttSyncService`: subscribe filter, own-echo skip in `messageArrived` before decrypt, LWT OFFLINE, `publishDeviceSnapshot(force)` gated by fingerprint, OFFLINE on manual disconnect / local mode / onDestroy, `MQTT_ACTION_OFFLINE` handler, PONG Toast removed, debug logs `subscribed…`, `skip own echo`, `PONG published reason=forced|changed|keepalive`, `peer OFFLINE` | done |
| S4 all 8 other publish sites → `CloudTopics.publishTopic` (DeviceManagerActivity, HistorySyncQueueManager, MainActivity ×2, SmsChatActivity, SyncOutbox, SystemSmsHistoryImporter) — `grep '\.publish('` shows none left on bare topic | done |
| S5 `DeviceDirectoryManager.markOffline`, `ONLINE_TIMEOUT_MS` 45 s → 5 min | done |
| S6 JVM tests `DsimCryptoUtilsTest` 8 / `CloudTopicsTest` 4 / `HeartbeatPolicyTest` 6; `unitTests.isReturnDefaultValues = true` added (android.util.Log in failure paths) | 39/39 PASS |
| S7 `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug` | PASS |

## E2E on emulator-5554 (dSIM_B, broker.emqx.io, group dsim/test/9f3a2b)
- connect: `subscribed dsim/test/9f3a2b/+, publishing on dsim/test/9f3a2b/d9e0118b7d57f7c6`; own PING + PONG → `skip own echo`; `dSIM_Crypto:W` count 0 over the whole session.
- incoming SMS via `adb emu sms send` → `outbox[capture] sent=1 failed=0 remaining=0` on the per-device topic (batch A path intact).
- heartbeat: after forced PONG at connect, 4 min idle produced only `reason=keepalive sinceLast≈120 s`. `dumpsys battery set status 3` (discharging) / `reset` → `reason=changed` at next 30 s tick. Note: `dumpsys battery unplug` alone did not change `isCharging` on this emulator image (status stayed 2) — expected, not a bug.
- LWT: external paho subscriber on `<base>/+`; `am force-stop com.example.dsim` → broker delivered `{"action":"OFFLINE","deviceId":"d9e0118b7d57f7c6",…}` as DSM3, decrypted in Python with `pbkdf2_hmac(sha256, pw, b"dSIM/v3/master-key", 120000)` + AESGCM(AAD=b"DSM3") → cross-implementation format check PASS.
- peer OFFLINE: external client published PONG then OFFLINE as `peerprobe0001` → log `peer OFFLINE: peerprobe0001`; `device_profiles.lastSeenAt` for it pushed behind the 5-min window (age 389 s while local row age 46 s). Forged OFFLINE carrying the local deviceId on `<base>/forger` → ignored (no log, no DB change).
- `am startservice … DISCONNECT` from adb is blocked (`Requires permission not exported`) so the manual-disconnect OFFLINE path was verified by code review only; the LWT path covers the unclean case.

## Tooling additions (outside repo, C:\Users\admin\AgentDock)
`dsim_e2e_b*.sh`, `dsim_lwt_probe.py` (paho + cryptography, python 3.14 has both), `dsim_peer_probe.py`, `dsim_dbdump.py` (pull Room DB `dsim_core_database` via `exec-out run-as … cat`, incl. -wal), generic detached runner writes `dsim_bg.log{,.state}`.

## Docs updated
AGENTS.md (C3 → DSM3, new C13 topic rule, C14 heartbeat rule, §3 layer list, §6 item 10 resolved, §7 two rows), FIXES_2026-09-18.md (第三批 13–17), REFACTORING.md (W20 done, batch table, test count, §6), TESTING.md (§3 log hint, §5.4 rewritten), architecture doc §8.1/8.2, R4, constants table, test todo.
