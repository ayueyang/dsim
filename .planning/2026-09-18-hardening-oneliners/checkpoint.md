# Recovery checkpoint — batch C (BROADCAST_SMS / .gitattributes / debug panel out of release)

## Current status
Batch C implemented, verified and committed on top of 0c86a5d. No push. Project:
C:\Users\admin\AndroidStudioProjects\dSIM. AgentDock task: tsk_44d5435e0cfaa2f8.

## Read next
1. task_plan.md (design D1–D4, steps S1–S8) and progress.md (evidence + the 5 gotchas)
2. ../../FIXES_2026-09-18.md §第四批, ../../AGENTS.md C15 + §2 build notes
3. ../2026-09-18-durable-sync-outbox/findings.md — remaining F-items feed batches D and E

## Invariants introduced
- AGENTS C15: both SMS receivers must keep `android:permission="android.permission.BROADCAST_SMS"`.
  Verify with a real inbound SMS, never with `adb am broadcast` (protected broadcast, always denied).
- Debug-only components belong in `app/src/debug/AndroidManifest.xml`. The release manifest must not
  declare `MainActivity`. `buildFeatures { buildConfig = true }` must stay on — `SettingsActivity`
  uses `BuildConfig.DEBUG`.
- `.gitattributes` is authoritative for line endings: everything LF except `*.bat`/`*.cmd`/`gradlew.bat`.
  After touching it, re-checkout affected files; `--renormalize` alone does not rewrite the work tree.

## Compatibility
No wire-format, schema or protocol change. Existing installs keep working; DSM3 and the batch B topic
layout are untouched. Release builds simply no longer expose the test panel.

## Next batch (D — security)
F8 backup rules: exclude `dSIM_UI_PREFS` and the Room DB from `allowBackup` / data extraction
(W14 EncryptedSharedPreferences is the follow-up); F16/F12 route the 14 direct
`getSharedPreferences("dSIM_UI_PREFS")` reads through `CloudSettingsManager`; F9 default broker to
`ssl://` plus a "test only" warning for public brokers, and reject `+`/`#` in the user-entered base
topic. Then batch E (W10/W11 + drop hivemq → W5 → W4 → W6). W21 stays open.

## Environment
Emulator dSIM_B on emulator-5554 now runs the batch C debug build; SMS role is back on com.example.dsim
and the runtime permissions were re-granted via `pm grant` after the reinstall. Gradle still launched
detached through WMI (`dsim_gradle.sh` + `.state` polling). For `assembleRelease` add
`-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` if the network stalls.
