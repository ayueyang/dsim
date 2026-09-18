# Batch F1 progress

## S1 baseline
- HEAD d3c49ee, untracked only HANDOVER_2026-09-18.md + session log. OK. AgentDock task tsk_27596bdc1b5f2092.

## S2 edits
- AndroidManifest: + `FOREGROUND_SERVICE_REMOTE_MESSAGING`; MqttSyncService `foregroundServiceType="remoteMessaging"` (comment says why).
  SystemHistoryImportService stays dataSync.
- MqttSyncService: new `promoteToForeground()` -> typed `startForeground(id, n, FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)` on API 34+
  (constant + typed overload from 34; older frameworks parse the unknown manifest type as 0), untyped below; any exception -> Log.e,
  return false -> onStartCommand does `stopSelf(startId)` + START_NOT_STICKY (before the 5 s startForegroundService deadline).
- BootReceiver: log line only (ASCII), comment on why the direct start is allowed.

## S3 build
- WMI-detached gradle `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease -x lintVital*` -> DONE exit=0 (23:32:52).
  JVM 64/64 (0 fail/err). app-debug.apk 7,641,884 B; app-release-unsigned.apk 5,967,964 B.

## S4 emulator (API 36, emulator-5554)
- `dumpsys package`: requested + granted FOREGROUND_SERVICE_REMOTE_MESSAGING.
- `am broadcast -a BOOT_COMPLETED <pkg>` -> SecurityException (protected broadcast, shell uid 2000) -> unusable; real `adb reboot` only.
- Pitfall found: after `am force-stop` the package is `stopped=true` and BOOT_COMPLETED is never delivered (no dSIM log at all).
  Must launch the app once (monkey) before rebooting. First reboot attempt was invalid for this reason.
- Second reboot (launched first): boot_completed 15:42; `dSIM_Boot: boot broadcast + auto-connect on -> starting MqttSyncService`
  15:42:59 -> `subscribed dsim/test/9f3a2b/+` 15:43:11 -> `PONG published reason=forced` 15:43:14. No
  ForegroundServiceStartNotAllowedException, no FATAL. `dumpsys activity services`: MqttSyncService isForeground=true
  foregroundId=888 types=0x00000200 (= REMOTE_MESSAGING).
- `emu sms send 10086 "F1 boot smoke"` -> dSIM_Receiver captured SMS_DELIVER -> `dSIM_Outbox: flush sent=1 dropped=0 failed=0 remaining=0`.
- Helper scripts: C:\Users\admin\AgentDock\dsim_boot_f1.ps1 (install/grant/role + broadcast attempt), dsim_reboot_f1.ps1
  (reboot + wait + logcat; > 120 s so it hits the Cloudflare 524 on exec_command - run it, then read logcat in a second call).

## S5 docs
- AGENTS.md: new C19 (remoteMessaging type, promoteToForeground single entry, dataSync forbidden for the daemon).
- Architecture doc: service table row + permission list. FIXES_2026-09-18.md: 未完成 line struck. HANDOVER §5 item 1 -> done + test pitfalls.

## S6
- see commit.
