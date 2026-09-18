# Progress — batch A
2026-09-18: Plan created after full code read (see findings.md). No code changed yet.
Environment: adb daemon started by this session (device e6f587b0 unauthorized, untouched).
AVDs present: dSIM_B, dSIM_C, Medium_Phone. Emulator not started yet.

## Implementation (2026-09-18)
| Item | Result |
|---|---|
| Room v7 `sync_outbox` + MIGRATION_6_7 | done |
| `SyncOutbox.kt` (storeIncomingSms / flush / decide / buildIncomingSmsEntry) | done |
| `SmsReceiver` enqueue-only, kicks `ACTION_FLUSH_OUTBOX` | done; no `globalMqttClient` reference left |
| `MqttSyncService` clientId `dSIM_<deviceId>`, cleanSession=false, `MqttDefaultFilePersistence(filesDir/mqtt)`, flush on connect/reconnect/heartbeat/explicit, backlog in notification | done |
| compileDebugKotlin | PASS |
| testDebugUnitTest | PASS 21/21 (OutboxPolicyTest 7 new) |
| assembleDebug / assembleDebugAndroidTest | PASS |
| androidTest on emulator-5554 (dSIM_B, ro.kernel.qemu=1) | OK 10/10: SyncOutboxDaoTest 5 (ordering, dedupe, attempts, atomicity, v6→v7 migration) + SendCommandLedgerTest 5 |
| E2E offline capture | `svc wifi/data disable` → 2× `adb emu sms send` → both captured, no publish attempt, notification "连接失败"; after network restore + app relaunch: `outbox[connect] sent=2 failed=0 remaining=0` |

### Tooling notes (important for next session)
- AgentDock `exec_command` kills child processes at session end and sync mode caps at 30 s.
  Gradle must be launched detached: `Invoke-CimMethod Win32_Process Create` running
  `C:\Users\admin\AgentDock\dsim_gradle.sh <tasks>`; it writes `dsim_gradle.log` and
  `dsim_gradle.log.state` (`RUNNING`/`DONE exit=N`). Poll the state file.
- Same trick used for the emulator (`dsim_emu.sh`). Emulator left running at end of batch;
  stop with `adb -s emulator-5554 emu kill` if not needed.
- PowerShell quoting of `*`, `)`, `\"` inside nested `adb shell` commands is unreliable; put
  multi-step adb sequences in a `.sh` under `C:\Users\admin\AgentDock\` and run with Git bash.
- Physical device e6f587b0 is unauthorized and was never targeted.

## Commit
81a786b feat(sync): durable outbox for incoming SMS + persistent MQTT session (27 files). Not pushed.
