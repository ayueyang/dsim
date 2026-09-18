# Recovery checkpoint — batch A (durable sync outbox)

## Current status
Batch A implemented and verified; committed on top of 88c3883 (hash in progress.md). No push.
Project: C:\Users\admin\AndroidStudioProjects\dSIM
AgentDock task: tsk_9511e97f4e78dc5c.

## Read next
1. findings.md (full review list F1–F17 + doc drift; batches A–E)
2. task_plan.md (design decisions D1–D7)
3. progress.md (verification table + tooling notes)
4. ../../FIXES_2026-09-18.md §第二批, ../../REFACTORING.md §5 修订版

## Invariants introduced
- sms_messages row and sync_outbox row are written in one transaction; the receiver never publishes.
- Outbox row deleted only after PUBACK. First failure stops the loop; next trigger resumes in order.
- Rows with a foreign groupFingerprint are dropped, never published to another group.
- MQTT clientId is stable `dSIM_<deviceId>`, cleanSession=false, file persistence (AGENTS C12).
- New must-deliver cloud messages go through SyncOutbox (AGENTS C11).

## Next batch (B)
W20: derive master key once per group (new magic DSM3, drop DSM2 per N2), publish to
`<topic>/<deviceId>` and subscribe `<topic>/+` to skip own echo before decrypting, heartbeat
20 s → on-change + 2–5 min. Then batch C one-liners (F4 Toast, F5 receiver permission,
.gitattributes, W15).

## Environment
Emulator dSIM_B may still be running on emulator-5554. Gradle/emulator must be launched detached
(see progress.md tooling notes). Git Bash: C:\Program Files\Git\bin\bash.exe.
