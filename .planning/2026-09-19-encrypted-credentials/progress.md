# Batch H progress

## S1 baseline b084012. Task tsk_4a503c6b4b7ec0de.

## S2 code
- `CredentialCodec` (pure): v1: + b64(iv||ct) framing, unframe rejects non-prefix / bad b64 / too short. 4 tests.
- `CredentialVault`: Keystore alias dsim_cred_v1, AES-256-GCM, randomized IV, no user-auth binding; seal()/open()
  return null on any failure.
- `CloudSettingsManager`: PASSWORD_ENC; readPassword(): sealed -> open or WIPE (+CREDENTIALS_RESET_REASON); legacy
  PASSWORD -> seal + remove (lazy one-shot); Keystore-unavailable -> plaintext stays (logged). saveConfig seals; clears
  the reset notice. consumeCredentialsResetNotice() / isPasswordSealed() for UI.
- SettingsActivity.onResume: one-time AlertDialog when a reset notice exists.
- No new dependency (security-crypto is deprecated).

## S3 build DONE exit=0 05:27; JVM 87/87; debug + release APKs.

## S4 emulator API 36 (C:\Users\admin\AgentDock\dsim_h.ps1 a|b|c|reboot|bootcheck)
a) old build prefs: PASSWORD=dsimTest2026 plaintext -> install new -> launch -> PASSWORD gone, PASSWORD_ENC=v1:kgzD...,
   log "migrated plaintext password to sealed storage", subscribed 8 s later.
b) PASSWORD_ENC replaced by a v1 frame of 44 random bytes (= key lost) -> launch -> BROKER/TOPIC/PASSWORD_ENC all
   removed, CREDENTIALS_RESET_REASON=key_lost. (dialog not exercised: MainActivity not exported; consume path is
   trivial.)
c) re-enter (simulated by writing plaintext triple, what a pre-seal save would leave) -> launch -> re-sealed with a new
   blob (v1:KwWE...), migrated log, subscribed. Note: RESET_REASON stayed because saveConfig() was bypassed; real UI
   save clears it.
d) adb reboot -> dSIM_Boot 21:35:34 -> subscribed 21:36:01: Keystore key readable at boot without user auth.
- Pitfall: PowerShell 5 script with a Chinese literal broke (GBK) - keep helper scripts ASCII (known, bit me again).

## S5 docs: AGENTS C23 + C17 note; REFACTORING W14 done + test list; arch doc R0; HANDOVER §5 item 6.
## S6 commit below.
