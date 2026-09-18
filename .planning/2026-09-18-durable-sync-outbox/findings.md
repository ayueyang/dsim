# Findings — 2026-09-18 code review (post d9f4dde)

Scope: full read of SmsReceiver, MqttSyncService, DsimCryptoUtils, OutgoingSmsDispatcher,
SmsSentResultReceiver, CloudSettingsManager, BootReceiver, entities/DAO/DB, Manifest, build
scripts, plus AGENTS.md / FIXES_2026-09-18.md / REFACTORING.md / TESTING.md.

Product premise confirmed by user: carrier SMS costs money; the product value is
"incoming SMS -> internet -> other devices". Carrier-side sending features (local SIM direct
send, default-SMS-app components) are deprioritised. Network is assumed available.

## P0 — core reliability / security

| # | Finding | Evidence |
|---|---|---|
| F1 | Incoming SMS sync is fire-and-forget. If the MQTT client is null/disconnected at receive time the message is never synced and nothing records that. | `SmsReceiver.publishIncomingSmsToCloud`: `if (client == null \|\| !client.isConnected \|\| ...) return` |
| F2 | Broker keeps no offline messages for peers. clientId contains `System.currentTimeMillis()`, `isCleanSession = true`, `MemoryPersistence()`. A peer that is offline for an hour misses every message from that hour. | `MqttSyncService.connectAndSubscribe` |
| F3 | Battery: every payload runs PBKDF2 120k rounds with a fresh random salt (no key caching possible); device snapshot heartbeat every 20 s; each device decrypts its own echo plus every peer's heartbeat. | `DsimCryptoUtils.deriveAeadKey`, `DEVICE_SNAPSHOT_INTERVAL_MS = 20_000`, `handleIncomingMessage` decrypts before deviceId check |
| F4 | A Toast is shown for every PONG (i.e. every peer every 20 s). | `handleIncomingMessage` PONG branch `Toast.makeText(... "雷达响应: ...")` |
| F5 | `SmsReceivedReceiver` (SMS_RECEIVED path) has no `android:permission="android.permission.BROADCAST_SMS"`; any app can inject a fake SMS broadcast that gets stored and synced to all devices. `SmsReceiver` (SMS_DELIVER) does have it. | AndroidManifest.xml |
| F6 | Publish and subscribe use the same topic, so every device decrypts its own messages to discover they are its own. | `connectAndSubscribe` subscribes `currentTopic`; all publishers publish `currentTopic` |
| F7 | `SmsReceiver` performs blocking QoS1 publish inside `goAsync()`; slow network can exceed receiver budget after the DB write already happened (ties into F1). | `SmsReceiver.publishIncomingSmsToCloud` uses sync Paho `MqttClient.publish` |

## P1 — security / robustness

| # | Finding | Evidence |
|---|---|---|
| F8 | `allowBackup="true"` with template backup rules: plaintext group password (`dSIM_UI_PREFS.PASSWORD`) and the Room DB go into cloud backup. | Manifest, `res/xml/backup_rules.xml`, `data_extraction_rules.xml` |
| F9 | Default broker is public plaintext `tcp://broker.emqx.io:1883`. Payload is encrypted but topic/timing/size leak and messages can be replayed; protocol has no timestamp/nonce validation. | `CloudSettingsManager.DEFAULT_BROKER` |
| F10 | 16 `catch (_: Exception) {}` + 4 `printStackTrace` (incl. crypto decrypt failure) → zero observability for bad packets. | grep over `app/src/main/java` |
| F11 | `isRead` is carried in `SyncPayload.sms` but read state is never synchronised afterwards. | `SmsMessage.isRead`, no read-sync action |
| F12 | 14 call sites read `dSIM_UI_PREFS` directly instead of via `CloudSettingsManager`. Blocks encrypted-prefs migration. | grep `getSharedPreferences("dSIM_UI_PREFS"` |

## P2 — engineering

| # | Finding | Evidence |
|---|---|---|
| F13 | `hivemq-mqtt-client` (+Netty) is only referenced by dead `DsimMqttEngine` / `DsimNetworkEngine`. | `app/build.gradle.kts`, grep |
| F14 | 16 tracked text files are CRLF in the working tree (checkout via `core.autocrlf=true`); index is LF. No `.gitattributes`. | `git ls-files --eol`, byte scan |
| F15 | `enableJetifier=true`; core-ktx 1.10.1 / appcompat 1.6.1 / material 1.10.0 / lifecycle 2.6.2 vs compileSdk 36. | `gradle.properties`, `app/build.gradle.kts` |
| F16 | `MqttSyncService` still 1023 lines: connection mgmt + 7 handlers + notification copy + SIM shadow sync. | wc -l |
| F17 | Debug `MainActivity` reachable in release builds. | `SettingsActivity` ~L640 |

## Documentation drift (written by other assistants)

- `REFACTORING.md` §1 metrics stale ("有效测试 0", 55 files / 14,245 lines); now 14 JVM + 5 instrumentation tests, 58 files / 14,531 lines.
- `REFACTORING.md` W1 claims real devices refuse the default-SMS role because of stub components; `FIXES_2026-09-18.md` explicitly corrected that. The two files contradict each other.
- `REFACTORING.md` §5 batch order puts W1 first; per user direction carrier-send features are deprioritised. There is **no work item for durable sync / outbox** anywhere in REFACTORING.md; it only appears as "未完成" in FIXES.
- `AGENTS.md` §6 same ordering issue; item 10 (self-echo) is framed as a logging nuisance but is actually a CPU/battery cost (F3/F6).
- None of the docs assess reliability from the "incoming SMS must not be lost" angle.

## Batches

- **A (this plan)**: F1 + F2 + F7 — durable outbox, persistent MQTT session, receiver only enqueues.
- **B**: F3 + F6 + heartbeat — one-time key derivation (new magic `DSM3`), per-device publish topic, heartbeat 2–5 min / on-change. Format change must bump magic (C3).
- **C**: one-liners — F4, F5, F14 (`.gitattributes`), F17.
- **D**: F8 backup exclusion, F12 centralise prefs, F9 default `ssl://`.
- **E**: W10/W11/F13 cleanup → W5 → W4 → W6.
