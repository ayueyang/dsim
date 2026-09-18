# Recovery checkpoint — 2026-09-18

## Current status
First repair batch implemented and targeted verification passed. No pending code upload.
Project: C:\Users\admin\AndroidStudioProjects\dSIM
Baseline HEAD: 82c84f0. Edits are uncommitted; do not reset them.
AgentDock task: tsk_127a9a127067171c (first-batch scope only).

## Read next
1. task_plan.md
2. findings.md
3. progress.md
4. ../../FIXES_2026-09-18.md
5. Current git diff and git status before new edits.

## Implemented
Room v6 command ledger + migration, OutgoingSmsDispatcher, SmsSentResultReceiver,
SendCommandPolicy; strict result targeting; Broker restoration; service scope cancellation;
UNKNOWN status/query UI; updated core docs and tests.
No deviceId, mappingKey or crypto wire format changes.

## Evidence
compileDebugKotlin, testDebugUnitTest, assembleDebug, assembleDebugAndroidTest succeeded.
13 new JVM tests (+1 template) passed. 5 isolated Room instrumentation tests passed.
Existing single-emulator smoke script: 8/8 PASS. Not a two-device 12/12 validation.
No physical-device or carrier send tests performed.

## Important semantics
Claimed UUID is NEVER resubmitted. Unknown outcome is -2, not success or confirmed non-delivery.
User taps query to recover same UUID; intentional new sending requires composing a new message.
All old outgoing records are tombstoned conservatively on v5→v6 migration.
Result publication is best-effort, no durable outbox yet. Preserve ledger during conversation deletion.

## Next batch
Prefer separating local SIM sending from MQTT, then default SMS entry components.
Also pending: Android 15+ foreground service architecture, durable sync/result outbox,
history ACK receiver-set/ignored semantics, backup/credential protection.
Do not execute stale W2 UUID acceptance criteria; they were corrected in REFACTORING.md.

## Environment
Git Bash: C:\Program Files\Git\bin\bash.exe (system bash.exe points to WSL).
Gradle -D argument must be quoted as a whole under PowerShell.
Emulator-only tests; never select the unauthorized physical device.
Managed emulator was shut down with adb emu kill; the ADB server session started for this batch was stopped. Check environment before restarting.
23 uploaded files were verified against local SHA-256, strict UTF-8 without BOM, and LF endings.
