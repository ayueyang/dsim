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

## E2 (W5) — protocol data classes
Scope: sealed `MqttInbound` + `MqttPayloadCodec` in `MqttProtocol.kt`; all 15 JSONObject producers and the 8-branch receiver
migrated; `encryptOrNull()` replaces the `ENCRYPTION_ERROR` sentinel (all 11 compares); `SendCmdPayload.kt` removed.
Wire format unchanged -> must interoperate with pre-W5 builds (legacy-shape unit tests + external paho probe).
Acceptance: JVM tests incl. new codec suite; assembleDebug + assembleRelease; emulator PING->PONG, legacy PONG accepted,
garbage ignored, OFFLINE handled, inbound SMS still syncs.

## Steps
- [x] S1 plan dir + task + baseline (HEAD 842a32a)
- [x] S2 delete dead files + gradle deps
- [x] S3 DAO cleanup + 3 call sites
- [x] S4 build: test + assembleDebug + assembleRelease via WMI-detached gradle
- [x] S5 emulator smoke (install -r, grant, role, launch, emu sms send)
- [x] S6 docs sync
- [x] S7 c8check + diff --check + review + local commit (no push)
- [x] S8 W5 codec + migrate producers/receiver + tests
- [x] S9 W5 build (debug+release) + emulator probe + docs (C18) + commit
- [x] S10 W4 step 1: CloudSession + MqttPublisher + MqttInboundHandler; build + probe + docs + commit
