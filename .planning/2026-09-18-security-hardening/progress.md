# Progress — batch D
2026-09-18: plan created on top of f64188a. Emulator dSIM_B on emulator-5554 runs the batch C debug
build (SMS role on com.example.dsim, runtime permissions granted). Gradle detached via WMI
(`dsim_gradle.sh` + `.state` polling); add `-x lintVital*` if assembleRelease stalls on downloads.

## Done
- S1 plan dir, task tsk_0f4840847164b5b3, baseline f64188a confirmed clean.
- S2 both rule files rewritten deny-by-default (5 and 18 `<exclude>` entries).
- S3 `CloudSettingsManager`: `validateBaseTopic`, `classifyBroker`, `isPublicTestBroker`,
  DEFAULT_BROKER -> `ssl://broker.emqx.io:8883`, `saveConfig` normalizes the topic.
  New `CloudConfigMessages` (user-facing text), new `NotificationPreferences` (IS_MUTED).
- S4 validation wired into SettingsActivity, OnboardingActivity and the debug panel;
  plaintext/public-broker warning appended to the settings status line.
- S5 13 direct prefs reads consolidated; `getSharedPreferences("dSIM_UI_PREFS")` hits in app/src = 0.
  Stale `tcp://` default text in activity_main.xml updated to `ssl://`.
- S6 `CloudSettingsManagerTest` (13 cases). One expectation was wrong on the first run
  (`"///"` reports LEADING_SLASH, not EMPTY) — corrected the test, and removed the
  now-unreachable post-trim empty branch in the implementation.
- S7 verification, see below.
- S8 docs: AGENTS C16/C17 + §6 item 6, FIXES 第五批, TESTING 批次 D, REFACTORING §6.

## Verification (emulator-5554, debug build)
| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | 52 tests, 0 failures |
| `:app:assembleDebug` | exit 0 |
| Rules compiled into APK | `aapt2 dump xmltree`: backup_rules 5 excludes, data_extraction 18 (9 cloud + 9 transfer) |
| TLS really connects | `/proc/net/tcp6` ESTABLISHED uid 10227 -> `:22B3` (8883); `subscribed dsim/test/9f3a2b/+`; notification "已连接" |
| Sync still works over TLS | `emu sms send 10086` -> `dSIM_Receiver: Captured … SMS_DELIVER` -> `dSIM_Outbox: flush sent=1 dropped=0 failed=0 remaining=0` |
| Backup denied (A/B) | deny rules -> `PFTBT: Error -1002 … Transport rejected`; permissive control build -> `Full backup completed with status: 0`. Control reverted and reinstalled afterwards. |
| Existing config untouched | saved `tcp://` survived the upgrade install — only new installs get the ssl:// default |

## Notes for the next session
- `bmgr` needs `bmgr enable true` + `bmgr transport com.android.localtransport/.LocalTransport`;
  `adb root` is unavailable on this production image, so localtransport's on-disk output cannot be
  read — PFTBT logs are the evidence.
- The emulator's saved broker is now `ssl://broker.emqx.io:8883` (was tcp://), changed by hand for
  the TLS test. Harmless, but remember it is not the app's own migration.
