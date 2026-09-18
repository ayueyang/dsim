# Batch E — engineering (dead code / DAO cleanup / hivemq removal, then W5)

Base: 842a32a. Plan dir: .planning/2026-09-18-engineering-cleanup. AgentDock task: see progress.md.
Findings source: ../2026-09-18-durable-sync-outbox/findings.md F13; REFACTORING.md W10 / W11 / W5 / W4 / W6.
Order from REFACTORING.md batch table: W10+W11+hivemq -> W5 -> W4 -> W6/W7. Each step = one commit.

## E1 (W10 + W11 + F13) — low-risk cleanup
Scope, verified by `git grep` on 842a32a:
- delete `DsimMqttEngine.kt` (94 lines) and `DsimNetworkEngine.kt` (65 lines): only references are each
  other's imports of hivemq and docs.
- delete `com.hivemq:hivemq-mqtt-client:1.3.3` + `META-INF/io.netty.versions.properties` packaging
  exclude (netty only arrives via hivemq).
- delete `res/layout/item_sms.xml` (0 references in app/src).
- delete `gradle/libs.versions.toml` (0 `libs.` references in *.kts; declares agp 9.1.0 vs real 8.7.3).
- DsimDao: drop `getRecentConversations`, `getMessagesByAddress` (Flow, line 61), `getMessagesByAddressList`,
  `countSimilarLocalMessage` (0 callers each); merge `getAllSimConfigsForUi` into `getAllSimConfigs`
  (identical SQL, 3 callers: DeviceManagerActivity:162, OtpConversationActivity:76, SmsChatActivity:111).
  No schema change -> no Room version bump.
- keep `SendCmdPayload.kt` (W5 will use it).
- docs: AGENTS.md tree + item 8, REFACTORING.md W10/W11 status + metrics, architecture doc dependency table.

Acceptance: `git grep` for the 5 DAO names + hivemq + DsimMqttEngine + DsimNetworkEngine == 0 outside
history docs; JVM unit tests pass (52 baseline); assembleDebug PASS; assembleRelease PASS (lintVital skipped
as documented); c8check + `git diff --check` clean. Runtime behaviour unchanged, so no emulator E2E needed
beyond install + one inbound SMS smoke (`outbox sent=1 remaining=0`) to prove the APK still works.

## E2 (W5) — protocol data classes (only if E1 lands cleanly)
See REFACTORING.md W5. Separate commit; decide scope after E1.

## Steps
- [ ] S1 plan dir + task + baseline (HEAD 842a32a)
- [ ] S2 delete dead files + gradle deps
- [ ] S3 DAO cleanup + 3 call sites
- [ ] S4 build: test + assembleDebug + assembleRelease via WMI-detached gradle
- [ ] S5 emulator smoke (install -r, grant, role, launch, emu sms send)
- [ ] S6 docs sync
- [ ] S7 c8check + diff --check + review + local commit (no push)
