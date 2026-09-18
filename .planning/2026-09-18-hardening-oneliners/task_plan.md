# Batch C — one-liner hardening (F5 BROADCAST_SMS / F14 .gitattributes / W15 debug-out-of-release)

Base: 0c86a5d. Plan dir: .planning/2026-09-18-hardening-oneliners. AgentDock task: tsk_44d5435e0cfaa2f8.
Source of findings: ../2026-09-18-durable-sync-outbox/findings.md (F5, F14, F17=W15).

## Problem
- F5  `SmsReceivedReceiver` is exported with an `SMS_RECEIVED` filter and NO
      `android:permission="android.permission.BROADCAST_SMS"`. Any installed app can broadcast a
      forged PDU; it is stored, written back to the system inbox and synced to every device in the
      group. `SmsReceiver` (SMS_DELIVER) already has the guard, so this is the one open hole.
- F14 No `.gitattributes`; 18 tracked text files are CRLF in the working tree while the index is LF.
      Only `core.autocrlf` keeps this from producing whole-file diffs (violates AGENTS C8 intent).
- W15 `MainActivity` (956 lines of debug tooling: inject fake SMS, wipe DB, root-probe toggle,
      dual-DB dump) is in the release manifest and reachable from Settings on any release build.

## Design decisions
- D1  F5 is a one-attribute Manifest change, mirroring the existing `SmsReceiver` block. No code
      change: `SmsReceivedReceiver` is an empty subclass of `SmsReceiver` (AGENTS C6 mutual
      exclusion is untouched — the receiver keeps its `SMS_RECEIVED` filter, it only becomes
      callable by the system alone).
- D2  `.gitattributes`: `* text=auto eol=lf` as the default, `*.bat`/`*.cmd` pinned `eol=crlf`
      (gradlew.bat must stay CRLF to run), and binary types (`*.png *.jpg *.webp *.jar *.apk *.keystore
      *.ttf ...) marked `binary` so git never touches them. Then `git add --renormalize .` so the
      working tree matches. Expected: only `gradlew.bat` (+ any .bat) remain w/crlf.
- D3  W15 gets the structural fix, not just the `if (BuildConfig.DEBUG)` wrapper:
      move the `<activity android:name=".MainActivity">` entry out of the main manifest into
      `app/src/debug/AndroidManifest.xml`. Release manifest then has no MainActivity at all, so the
      class is unreachable even via explicit intent from another app or via a stale launcher shortcut.
      The Settings entry button is hidden with `BuildConfig.DEBUG` and the `startActivity` call is
      guarded, because the class itself still compiles into release (moving the source file to a
      debug source set would break `SettingsActivity` compilation in release).
      Kept simple deliberately: no manifestPlaceholders, no product flavours.
- D4  Not in scope (stay in later batches): allowBackup/backup rules (F8), prefs consolidation
      (F12/F16), ssl:// default + base-topic wildcard validation (F9), dead code + hivemq (W10/W11/F13),
      W21 outbox for SEND_CMD_RESULT.

## Steps
- [ ] S1 Plan dir + task, baseline check (HEAD 0c86a5d, tree clean apart from handover files).
- [ ] S2 F5: Manifest `android:permission` on `SmsReceivedReceiver`.
- [ ] S3 F14: `.gitattributes` + `git add --renormalize .`, verify with `git ls-files --eol`.
- [ ] S4 W15: debug-only manifest entry + `BuildConfig.DEBUG` guard in `SettingsActivity`.
- [ ] S5 Gradle: compileDebugKotlin, testDebugUnitTest, assembleDebug, assembleRelease (detached WMI runner).
- [ ] S6 Emulator proof on emulator-5554: forged `SMS_RECEIVED` broadcast rejected; debug build still
      opens the panel; `aapt dump xmltree` on the release APK shows no MainActivity.
- [ ] S7 Docs: AGENTS (C5/C6 note + §5 tree + §6), FIXES 第四批, REFACTORING (W15 done, batch table),
      TESTING (how to re-run the forged-broadcast check).
- [ ] S8 progress.md + checkpoint.md, one commit, no push.

## Next Step
S2 — add the BROADCAST_SMS permission attribute to SmsReceivedReceiver in AndroidManifest.xml.

## Errors Encountered
| Error | Attempt | Resolution |
|---|---|---|
| `SettingsActivity.kt:642 Unresolved reference 'BuildConfig'` on compileDebugKotlin | 1 | AGP 8 stops generating BuildConfig unless asked. Added `buildFeatures { buildConfig = true }` to app/build.gradle.kts (module-scoped, preferred over the global `android.defaults.buildfeatures.buildconfig` property). |
