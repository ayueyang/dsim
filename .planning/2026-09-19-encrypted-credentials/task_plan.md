# Batch H - W14 encrypted credential storage

Base: b084012. Plan dir: .planning/2026-09-19-encrypted-credentials. User decision: key lost -> wipe credentials,
user re-enters (no plaintext fallback).

## Design
- No new dependency. `androidx.security:security-crypto` is deprecated (1.1.0-alpha07 is the last), drags Tink in, and
  its Keystore failure modes are opaque. We need exactly one thing: AES-GCM under an Android Keystore key.
- New `CredentialVault` (Android-only, small): Keystore alias `dsim_cred_v1`, AES-256-GCM, non-user-auth-bound (the
  daemon must read it on boot), `setRandomizedEncryptionRequired(true)`. `seal(plain) -> "v1:" + b64(iv||ct)`,
  `open(sealed) -> String?`. Any Keystore/GCM failure returns null and, on decrypt, is reported as KEY_LOST.
- `CloudSettingsManager`: password stored in key `PASSWORD_ENC` (sealed). `getConfig()`:
  1. `PASSWORD_ENC` present -> open(); null (key gone after factory reset / Keystore wipe / OEM bug) -> wipe
     BROKER/TOPIC/PASSWORD/PASSWORD_ENC, set `CREDENTIALS_RESET_REASON=key_lost`, return empty config.
  2. Legacy `PASSWORD` plaintext present -> seal it, write PASSWORD_ENC, remove PASSWORD (one-shot migration, done
     lazily on first read so BootReceiver / service / UI all trigger it). If sealing fails, keep plaintext (device has
     no working Keystore; better than locking the user out) and log once.
  `saveConfig()` writes sealed; if sealing fails -> plaintext + WARN (same rationale).
- `consumeCredentialsResetNotice(context): String?` for SettingsActivity/MainActivity: shows a one-time dialog
  "安全密钥已重置，请重新填写房间号与口令" when non-null. MqttSyncService already shows 未配置 for empty config.
- Broker/topic stay plaintext (not secrets; topic is on the wire anyway). C17 unchanged: every read/write still goes
  through CloudSettingsManager. Backup rules already exclude sharedpref (C16).
- Pure `CredentialCodec` helper (format prefix parse / iv split) unit-tested; Keystore itself only on device.

## Acceptance
- JVM tests (CredentialCodecTest), assembleDebug + assembleRelease.
- Emulator: (a) install over the F/G build that has plaintext PASSWORD -> after launch prefs show PASSWORD_ENC=v1:...
  and no PASSWORD; service connects (subscribed) => migration + read path OK; (b) reboot -> BootReceiver connects
  (Keystore key readable without user auth at boot); (c) simulate key loss: `keystore` cannot be edited from shell,
  so replace PASSWORD_ENC with a v1 blob sealed by another key (garbage) -> launch -> prefs wiped, notification
  未配置, settings shows reset dialog; re-enter via saveConfig (probe writes prefs? no - use the UI-less path:
  push a plaintext PASSWORD again and confirm re-migration) -> connects.
- c8check + diff --check; one local commit, no push. Docs: AGENTS C17 note / new C23, arch doc storage section,
  HANDOVER §5.

## Steps
- [x] S1 plan + task + baseline b084012
- [x] S2 CredentialCodec + CredentialVault + CloudSettingsManager + reset notice UI + tests
- [x] S3 build
- [x] S4 emulator a/b/c
- [x] S5 docs
- [x] S6 commit
