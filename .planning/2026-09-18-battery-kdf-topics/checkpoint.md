# Recovery checkpoint — batch B (battery: KDF once / per-device topics / on-change heartbeat)

## Current status
Batch B implemented, verified and committed as a78cf09 (on top of c765de2). No push. Working tree
clean apart from this checkpoint file. Project: C:\Users\admin\AndroidStudioProjects\dSIM.
AgentDock task: tsk_197d09fd37292069.

## Read next
1. task_plan.md (design D1–D6, steps S1–S8)
2. progress.md (verification table, E2E evidence, tooling)
3. ../../FIXES_2026-09-18.md §第三批, ../../AGENTS.md C3/C13/C14
4. ../2026-09-18-durable-sync-outbox/findings.md (remaining F-items → batches C–E)

## Invariants introduced
- Wire format is DSM3 only: [4B "DSM3"][12B IV][GCM ct+tag], AAD = magic; master key =
  PBKDF2-HMAC-SHA256(password, "dSIM/v3/master-key", 120k) cached per password. Changing salt,
  rounds or AAD is a format change → new magic (AGENTS C3).
- Every publish goes to `<base>/<deviceId>` via CloudTopics.publishTopic; subscription is
  `<base>/+`; nothing is published to `<base>` itself (AGENTS C13). Own echo is dropped by topic
  before decrypt; payload deviceId == local is dropped after decrypt.
- PONG is published only through publishDeviceSnapshot(force); heartbeat uses force=false and
  HeartbeatPolicy (30 s tick, publish on fingerprint change or ≥120 s). ONLINE_TIMEOUT_MS = 5 min
  must stay > MAX_SILENCE_MS (AGENTS C14, asserted in HeartbeatPolicyTest).
- OFFLINE action: sent on manual disconnect / local mode / onDestroy, and registered as LWT.
  Peers apply DeviceDirectoryManager.markOffline (lastSeenAt pushed behind the window; no schema).

## Compatibility
Devices on the previous build (DSM2, bare-topic publish) cannot talk to this build in either
direction. There are no released devices; all test emulators must be reinstalled.

## Next batch (C)
One-liners: SmsReceivedReceiver BROADCAST_SMS permission (F5), .gitattributes (eol), W15 debug
tooling out of release. Then D (backup rules, prefs consolidation, ssl:// default, base-topic
wildcard validation), E (W10/W11/F13 → W5 → W4 → W6). W21 (SEND_CMD_RESULT / history ACK via
outbox) stays open.

## Environment
Emulator dSIM_B still running on emulator-5554 with a78cf09 installed and connected to
dsim/test/9f3a2b (broker.emqx.io). Detached Gradle/emulator/E2E launch via WMI, helpers in
C:\Users\admin\AgentDock (dsim_gradle.sh, dsim_e2e_b*.sh, dsim_lwt_probe.py, dsim_peer_probe.py,
dsim_dbdump.py). Physical device e6f587b0 unauthorized, never targeted.
