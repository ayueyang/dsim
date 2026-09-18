# Findings — batch D

Carried from ../2026-09-18-durable-sync-outbox/findings.md: F8, F9, F12/F16.

## Verified state before the change (2026-09-18, HEAD f64188a)

| Item | Measured |
|---|---|
| F8 | `backup_rules.xml` and `data_extraction_rules.xml` are both untouched IDE templates — every rule is inside an XML comment, so zero exclusions are in force. Manifest has `allowBackup="true"`, `fullBackupContent="@xml/backup_rules"`, `dataExtractionRules="@xml/data_extraction_rules"`. Backup rule files are allow-by-default, so `dSIM_UI_PREFS.xml` (holds `PASSWORD` in plaintext) and `dsim_core_database` are both eligible for cloud backup and D2D transfer. |
| F9 | `CloudSettingsManager.DEFAULT_BROKER = "tcp://broker.emqx.io:1883"`. Referenced as the fallback in `getConfig`, `saveConfig`, `SettingsActivity:772`, `OnboardingActivity:491`, and hard-coded a second time in `MainActivity:158/163`. No validation anywhere on the topic string — `CloudTopics.normalizeBase` only does `trim().trimEnd('/')`. |
| F16 | 14 direct `getSharedPreferences("dSIM_UI_PREFS")` sites. Split by key: **credentials (9)** — BootReceiver:17, DeviceManagerActivity:350, HistorySyncQueueManager:292, SettingsActivity:1385, SmsChatActivity:386, SmsChatActivity:463, SystemSmsHistoryImporter:438, MainActivity:162, MainActivity:262; **IS_MUTED (4)** — NotificationUtils:60, SettingsActivity:608, MainActivity:679, MainActivity:716; **USAGE_MODE_EXPANDED (2, via private const)** — SettingsActivity:224/498. Note `MainActivity:355-357` also *writes* the credential triple directly. |

## TLS endpoint check (needed before changing the default)

`broker.emqx.io:8883` with Python `ssl.create_default_context()` (verification on):
`VERIFIED OK, TLSv1.3, subject *.emqx.io, issuer RapidSSL TLS RSA CA G1, SAN [*.emqx.io, emqx.io],
expires Jan 16 2027`. A publicly trusted chain means Paho's default `SSLSocketFactory` validates it
against the Android system trust store — no custom TrustManager, no pinned CA, no code beyond the
`ssl://` scheme. This is what makes D2 a safe default rather than a breakage.

## Why backup rules must be deny-by-default

Both files are allow-by-default: anything not named in an `<exclude>` is backed up. A list of
filenames has to be updated every time a store is renamed or added, and that maintenance is exactly
what gets forgotten. Excluding every domain with `path="."` and adding back nothing is the only
form that stays correct as the app grows.
