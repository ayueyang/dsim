# Findings — batch C

Carried from ../2026-09-18-durable-sync-outbox/findings.md: F5 (P0 security), F14 (P2), F17/W15 (P2).

## Verified state before the change (2026-09-18, HEAD 0c86a5d)

| Item | Measured |
|---|---|
| F5 | AndroidManifest.xml L78-85 `SmsReceiver` has `android:permission="android.permission.BROADCAST_SMS"`; L87-93 `SmsReceivedReceiver` `exported="true"` + `SMS_RECEIVED` filter, no permission attribute. |
| F14 | `.gitattributes` absent. `git ls-files --eol \| grep w/crlf` → 18 files: .gitignore, .planning/2026-09-17-untitled-9566bb32/task_plan.md, 7 Kotlin files (ComposeSmsActivity, DSimHardwareTester, DefaultSmsManager, DsimMqttEngine, DsimNetworkEngine, HeadlessSmsSendService, MmsReceiver), gradle/wrapper/gradle-wrapper.properties, gradlew.bat, 6 scripts/*.sh, test-fixtures/icc/README.md. |
| W15 | `MainActivity.kt` 956 lines, `BuildConfig.DEBUG` occurrences 0. Entry point `SettingsActivity.kt:640` `btnOpenTestTools.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }`. Manifest L36-40 declares MainActivity with `exported="false"`, label 测试功能. Debug surface inside: inject fake SMS + MQTT publish (L196-283), dual-DB read test (L301-314), root-probe mock toggle (L643-649), notification probe (L706-724), full history import / private DB wipe (L655-670), DSimHardwareTester.runFullTest (L780). |

Note on W15 severity: `exported="false"` already blocks other apps, so the real risk is a user (or
support flow) reaching destructive tooling — "清空私有库", forged SMS injection that publishes to the
whole sync group — from a shipped build. Removing the activity from the release manifest is the
cheap structural fix.
