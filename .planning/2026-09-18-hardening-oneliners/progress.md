# Progress — batch C
2026-09-18: plan created on top of 0c86a5d. Emulator dSIM_B still up on emulator-5554 (a78cf09
installed). Gradle runs detached via WMI (`dsim_gradle.sh` + `.state` polling), see
../2026-09-18-durable-sync-outbox/progress.md for the tooling notes.

## Implementation (2026-09-18, baseline 0c86a5d)
| Step | Result |
|---|---|
| S2 F5 — `android:permission="android.permission.BROADCAST_SMS"` on `SmsReceivedReceiver` | done |
| S3 F14 — `.gitattributes` (`* text=auto eol=lf`, `*.bat`/`gradlew.bat` `eol=crlf`, binaries marked), then `git add --renormalize .` + delete/re-checkout of the 17 CRLF files | done, only `gradlew.bat` stays w/crlf |
| S4 W15 — `<activity .MainActivity>` moved to new `app/src/debug/AndroidManifest.xml`; `SettingsActivity` entry wrapped in `BuildConfig.DEBUG` (GONE otherwise); `buildFeatures { buildConfig = true }` added | done |
| S5 Gradle — `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease` | 39/39 tests, all PASS |
| S6 Emulator + APK proof | done, see below |
| S7 Docs — AGENTS (C15, §2 build notes, tree, §8.1), FIXES 第四批 18–20, REFACTORING (W15 done, batch table), TESTING (§3.2 grant note, §3.2.1, §3.2.2, batch C section) | done |

### Errors / gotchas hit
1. `Unresolved reference 'BuildConfig'` — AGP 8 does not generate BuildConfig by default. Fixed with
   `buildFeatures { buildConfig = true }` in app/build.gradle.kts.
2. `git add --renormalize .` alone did NOT fix the working tree: the index was already LF, so git saw
   no change. Had to delete the 17 files and `git checkout --` them so the new `eol=lf` attribute was
   applied on checkout.
3. `:app:assembleRelease` hung for ~15 min in `lintVital*` downloading lint deps (jstack: SSL socket
   read, no cache writes after 14:52). Killed the daemon, re-ran without lintVital — PASS in <25 s.
   Recorded in TESTING.md; lint itself was not run.
4. First emulator attempt produced no receiver logs at all: reinstalling the APK reset the runtime
   permissions, and the system skips the broadcast at enqueue when `RECEIVE_SMS` is missing
   (`skipped by policy at enqueue: Permission Denial`). Not related to this change — `pm grant` fixed it.
5. An ANR dialog from the previous session blocked uiautomator; force-stop + relaunch cleared it.

## Verification evidence
- **APK level** (`aapt2 dump xmltree`): release `app-release-unsigned.apk` → `MainActivity` refs **0**;
  debug `app-debug.apk` → **2**. Both variants: `SmsReceiver` and `SmsReceivedReceiver` carry a
  permission attribute.
- **F5 real delivery** on emulator-5554: SMS role temporarily handed to Google Messages, then
  `adb emu sms send 10010` → `dSIM_Receiver: Captured incoming SMS action=android.provider.Telephony.SMS_RECEIVED,
  from=10010` → `dSIM_Outbox: flush sent=1 dropped=0 failed=0 remaining=0`. Proves the new permission
  does not block the system (`com.android.phone`, uid 1001) from delivering.
- **C6 regression**: role restored to dSIM → `adb emu sms send 95533` → `action=…SMS_DELIVER`,
  `SystemSmsStore: Inserted system SMS row`, `outbox sent=1 remaining=0`. No duplicate handling.
- **W15 in debug**: Settings → 测试功能 → `topResumedActivity=com.example.dsim/.MainActivity`.
  `adb am start -n com.example.dsim/.MainActivity` is refused (`not exported`) — expected, not evidence.
- **Negative control noted**: `adb shell am broadcast -a …SMS_RECEIVED` is rejected as a protected
  broadcast (`uid=2000`) both before and after the change, so it cannot be used as proof.
- **EOL**: `git ls-files --eol | grep w/crlf` → only `gradlew.bat`. `git diff --check` clean.

