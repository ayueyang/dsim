# Batch F1 — boot-start FGS crash (F-boot)

Base: d3c49ee. Plan dir: .planning/2026-09-18-boot-fgs-type. AgentDock task: see progress.md.
Source: HANDOVER_2026-09-18.md §5 item 1; engineering-cleanup/progress.md E5 env note (F-boot).

## Problem
`BootReceiver` -> `startForegroundService(ACTION_CONNECT)` -> `MqttSyncService.startForeground()` throws
`ForegroundServiceStartNotAllowedException: FGS type dataSync not allowed to start from BOOT_COMPLETED`
(targetSdk 36; Android 15 rule). Daemon stays dead until the user opens the app. Inbound SMS still land in
sync_outbox (C11) so nothing is lost, only delayed.

## Decision (FGS type policy)
Switch `MqttSyncService` from `dataSync` to `remoteMessaging`:
- Android 15 BOOT_COMPLETED block list = dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone.
  remoteMessaging is NOT blocked; BOOT_COMPLETED remains a documented background-start exemption.
- remoteMessaging is defined as "transfer text messages from one device to another" = this product. No runtime prereqs.
- dataSync additionally has the 6 h / 24 h budget on target 35+ (then onTimeout -> stopSelf or crash). A permanent
  MQTT daemon under dataSync is a latent second outage; remoteMessaging has no budget.
- `SystemHistoryImportService` stays dataSync (user-initiated, bounded).
Rejected: WorkManager deferral (adds a dependency + still needs an allowed FGS type to become long-lived);
specialUse (needs PROPERTY_SPECIAL_USE_FGS_SUBTYPE + store justification; unnecessary when a precise type exists).

## Scope
1. AndroidManifest: `FOREGROUND_SERVICE_REMOTE_MESSAGING` permission; MqttSyncService type `remoteMessaging`.
2. MqttSyncService.onStartCommand: `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)`
   wrapped so a rejected start (any future policy) logs + stopSelf() instead of killing the process.
3. BootReceiver: no behaviour change (keep direct start); tidy log.
4. Docs: AGENTS.md (permission list / C-note), architecture doc rows (type + permission list), FIXES "未完成" line,
   HANDOVER §5 item 1 -> done, REFACTORING table if it lists F-boot.

## Acceptance
- assembleDebug + assembleRelease(-x lintVital*) exit=0; JVM tests unchanged (64/64).
- Emulator API 36: `aapt2 dump badging` shows FOREGROUND_SERVICE_REMOTE_MESSAGING; after `am force-stop`,
  `adb reboot` (shell `am broadcast BOOT_COMPLETED` is rejected as protected; app must not be in stopped state) -> `dSIM_Boot` log -> service reaches
  `subscribed` with NO ForegroundServiceStartNotAllowedException; inbound `emu sms send` still syncs (sent=1).
- c8check 0 violations + `git diff --check` clean; single local commit, no push.

## Steps
- [x] S1 plan dir + task + baseline d3c49ee
- [x] S2 manifest + service + receiver edits
- [x] S3 build (WMI-detached gradle)
- [x] S4 emulator: install/grant/role/launch; BOOT_COMPLETED broadcast test; SMS smoke
- [x] S5 docs
- [ ] S6 c8check + diff --check + review + local commit
