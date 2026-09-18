# dSIM correctness repair plan

Baseline: 82c84f0. User authorized fixes after static review, 2026-09-18.
Skill: planning-with-files 1.2.0 loaded through MCP.
AgentDock task: tsk_127a9a127067171c.
Previous emulator plan is complete; historical progress/findings contain superseded conclusions. Preserve it unchanged.

## First batch
- [x] Read project instructions, existing plan and current clean Git status.
- [x] Add Room v6 persistent command ledger, atomic UUID claim BEFORE telephony side effect.
- [x] Receive system sent callbacks; aggregate multipart outcomes; do not call submission success delivery success.
- [x] Correct Broker restoration, strict result targeting, cancel service scope.
- [x] Compile and run targeted tests. Emulator tests only on emulator-*; never send via real SIM.
- [x] Update docs and checkpoint with actual results and remaining gaps.

## Safety decisions
- At-most-once submission per command UUID, not impossible cross-process exactly-once guarantee.
- If killed after claim or callback absent, do NOT automatically re-send the same UUID. Explicitly surface uncertain state on retry.
- Existing pending sms_messages row does not mean command already executed: ledger is separate.
- Same UUID with different request identity/content is rejected.
- No encryption format, mappingKey identity or deviceId change.
- No force push, no unrelated resets, no production SMS tests.

## Later batches (not complete)
- Local SIM direct send independent of MQTT, default SMS components.
- Foreground service type / Android 15+ lifecycle architecture.
- Durable synchronization outbox and per-target ACK policy; IGNORED is not stored.
- Backup/secrets, state-machine text coupling, remaining audit findings.

## First-batch result
Implementation and targeted verification complete. Remaining batches above are NOT complete. Changes left uncommitted for review; no push performed.
