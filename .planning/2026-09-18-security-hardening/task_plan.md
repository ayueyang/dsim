# Batch D — security (backup exclusion / prefs consolidation / ssl default + topic validation)

Base: f64188a. Plan dir: .planning/2026-09-18-security-hardening. AgentDock task: tsk_0f4840847164b5b3.
Findings source: ../2026-09-18-durable-sync-outbox/findings.md — F8 (backup), F9 (plaintext broker),
F12/F16 (prefs scattered). W14 (EncryptedSharedPreferences) stays out of scope, see D5.

## Problem
- F8  `allowBackup="true"` with template rule files. Both rule files are **allow-by-default**: anything
      not named in an `<exclude>` is eligible. They name nothing, so `dSIM_UI_PREFS.PASSWORD`
      (plaintext group password = the key to every synced SMS) and the whole Room DB go to Google
      Drive and to device-to-device transfer.
- F9  Default broker is `tcp://broker.emqx.io:1883`. Payload is DSM3-encrypted so content is safe,
      but topic/timing/size leak in cleartext to anyone on the path. Separately the user-entered base
      topic is never validated: a `+` or `#` in it silently turns `<base>/<deviceId>` into a wildcard
      publish and `<base>/+` into a broader subscription.
- F16 14 call sites read `dSIM_UI_PREFS` directly instead of going through `CloudSettingsManager`,
      including 9 that read BROKER/TOPIC/PASSWORD. Blocks any future storage change (W14) and each
      site re-implements its own blank-handling.

## Design decisions
- D1  Both rule files become **deny-by-default**: exclude every domain (`root`, `file`, `database`,
      `sharedpref`, `external`, plus `device_root`/`device_file`/`device_database`/`device_sharedpref`
      in the extraction rules) with `path="."`. Verified by search: naming individual files is the
      documented failure mode — a rename silently re-exposes the data. `allowBackup` stays `true`
      so the attribute keeps its meaning on API < 31 where `fullBackupContent` drives the behaviour;
      the rule file is what actually denies. Nothing in this app benefits from backup today: the
      device identity is ANDROID_ID-scoped (§6 item 4) and a restored Room DB on a new device would
      carry another device's `deviceId`.
- D2  `CloudSettingsManager` gains pure, testable validation + classification, so it can be unit
      tested on the JVM without Android:
      - `validateBaseTopic(raw): TopicValidation` — rejects blank, `+`, `#`, whitespace, control
        chars, `//`, leading `/`, and > 200 chars; returns the normalized (trimEnd('/')) value.
        Wildcards are the security-relevant ones (C13 assumes `<base>/<deviceId>` is exact).
      - `classifyBroker(uri): BrokerKind` — `SECURE` (`ssl://`/`wss://`), `PLAINTEXT` (`tcp://`/`ws://`),
        `UNKNOWN`. Used to show a warning, never to block.
      - `DEFAULT_BROKER` becomes `ssl://broker.emqx.io:8883`. Verified out-of-band that the endpoint
        presents a publicly-trusted RapidSSL cert (`*.emqx.io`, expires 2027-01-16), so Paho's default
        socket factory validates it against the Android trust store with no extra code.
      - `saveConfig` keeps accepting whatever the caller passes (it is also the migration path for
        existing installs) but normalizes the topic; validation happens at the UI entry points.
- D3  Existing installs are NOT migrated off `tcp://`: the saved value wins, so nobody's working
      setup breaks. Only a fresh install / blank field gets the new default. The settings screen
      shows a one-line warning when the saved broker is plaintext, and a "仅测试用" note for the
      public broker host.
- D4  F16 consolidation is mechanical and limited to the credential triple + the two UI booleans that
      already have owners:
      - BROKER/TOPIC/PASSWORD (9 sites) → `CloudSettingsManager.getConfig(...)`.
      - `IS_MUTED` (4 sites) → new `NotificationPreferences` object (it is not cloud config; putting
        it in CloudSettingsManager would be wrong).
      `USAGE_MODE_EXPANDED` already lives behind a private const in SettingsActivity and is pure UI
      state — left alone, noted here so the next reader does not think it was missed.
      MainActivity's debug panel is included even though it is debug-only, so the grep stays clean.
- D5  Out of scope, deliberately: W14 EncryptedSharedPreferences (needs a key-loss story and a
      migration; the backup exclusion in D1 removes the urgent part of the risk), message
      timestamp/nonce replay validation (F9's second half), and batch E cleanup.

## Steps
- [x] S1 Plan dir + task, baseline check (HEAD f64188a).
- [x] S2 D1 backup_rules.xml + data_extraction_rules.xml deny-by-default.
- [x] S3 D2 CloudSettingsManager: validateBaseTopic, classifyBroker, ssl:// default, topic normalize.
- [x] S4 D3 wire validation into SettingsActivity + OnboardingActivity save paths, plaintext warning.
- [x] S5 D4 consolidate the 9 credential reads + 4 IS_MUTED reads.
- [x] S6 JVM tests: CloudSettingsManagerTest (topic validation table, broker classification, resolve).
- [x] S7 Gradle + emulator: ssl:// connects for real, forged/wildcard topic rejected, `bmgr` backup
      contains no password and no Room DB.
- [x] S8 Docs (AGENTS C16 + §6, FIXES 第五批, REFACTORING, TESTING), progress/checkpoint, one commit.

## Next Step
Batch D is complete and committed. Next session starts batch E (dead code
`DsimMqttEngine`/`DsimNetworkEngine` + hivemq dependency, W5 protocol data classes, W4 splitting
`MqttSyncService`, W6 silent catch, W21 SEND_CMD_RESULT via outbox).

## Errors Encountered
| Error | Attempt | Resolution |
|---|---|---|
| `expected:<EMPTY> but was:<LEADING_SLASH>` | asserted `"///"` reports EMPTY | The code is right: `"///"` does start with `/`, and that is the more useful message. Fixed the test; also deleted the post-trim empty check that can no longer be reached. |
| `run-as … sh -c "sed -i …"` → Permission denied | edit installed prefs in place | run-as cannot inherit the outer shell's redirection. Use `run-as cat` → edit on host → `adb push` → `run-as cp`, after `am force-stop`. |
| `bmgr backupnow` → "Transport rejected package" | first attempt at proving exclusion | Ambiguous on its own. Proved it with an A/B: permissive control build backs up (status 0), deny build is rejected (-1002). |
| `adb root` → "adbd cannot run as root in production builds" | read localtransport output | Not available on this image; rely on PFTBT logs instead. |
