# AresVault security design

## Goals

- Credentials are unreadable without the master password or an enrolled strong biometric.
- Nothing leaves the device: no `INTERNET` permission (enforced at build time),
  `allowBackup=false`, and backup/device-transfer extraction rules exclude every domain.
- Autofill only offers a credential to the app or website it was saved for (Phase 4).
- No home-made cryptography: AES-GCM comes from the platform JCA / Android Keystore,
  Argon2id from Argon2kt (reference C implementation), and the database from SQLCipher.

## Key hierarchy

```
master password ──Argon2id(salt, params)──► KEK (32 B, never stored)
                                              │
                                   AES-256-GCM│wrap (random 96-bit IV, AAD = header fields)
                                              ▼
           random DEK (32 B, SecureRandom) ◄──┴── password slot  ┐
                │                                                 ├─ vault_header.json
                │      Keystore AES-256-GCM key ── biometric slot ┘   (noBackupFilesDir)
                │      (BIOMETRIC_STRONG per use,
                │       invalidated on new enrolment,
                │       StrongBox if available)
                ▼
        SQLCipher raw key  x'<hex DEK>'  ──►  vault.db  (all credentials + settings)
```

- **Argon2id**: baseline 64 MiB, t=3, p=1, 32-byte output. During vault creation the device
  is benchmarked; if a derivation takes under 500 ms, iterations are raised to target
  ~750 ms (capped at 16). Params and the 16-byte salt are stored in the header.
- **Header integrity**: the password slot's GCM AAD binds the format, version, KDF params
  and salt. Lowering the cost params or swapping the salt makes unwrapping fail instead of
  silently weakening the vault. Wrong password and tampering are deliberately
  indistinguishable.
- **SQLCipher raw-key mode**: the DEK is already uniformly random, so SQLCipher's internal
  PBKDF2 is skipped (it would only slow unlocking without adding security).
- **Change master password** re-wraps the DEK with a new salt and re-calibrated params.
  The database is not re-encrypted. *Limitation*: an old copy of the header plus the old
  password would still open the DB. A "rotate database key" (`PRAGMA rekey`) action is
  planned for the settings screen.
- **Biometric slot**: the key needs authentication for every operation (timeout 0) and only
  works through a `BiometricPrompt` `CryptoObject`. Enrolling a new fingerprint invalidates
  it permanently. The app then drops the slot, falls back to the master password and offers
  re-enrolment.

## Memory hygiene

- The DEK and the SQLCipher key literal live only in `VaultSession` while unlocked. They are
  zeroed on lock, and the database is closed.
- The KEK and UTF-8 password bytes are wiped immediately after use. Argon2kt gets
  app-owned direct buffers that are zeroed afterwards.
- Passwords move through crypto paths as `CharArray`/`ByteArray`. *Limitation*: Compose
  text fields hold `String`s, and Room entities hold the decrypted password as a `String`
  while displayed or filled. The JVM gives no way to wipe these. They are kept short-lived.
- `SecretKeySpec` copies the key internally and cannot be wiped (JCA limitation).
- WAL is disabled so SQLCipher never opens a second connection that would need the key again.

## Session, auto-lock and lockout

- **Auto-lock**: after the configured time in the background (default 2 min; options from
  immediately to 1 h) and, by default, as soon as the screen turns off. Infinix XOS freezes
  background processes, so a timer can fire late. Every resume (and, from Phase 4, every
  autofill request) therefore re-checks elapsed *monotonic* time and locks if overdue.
- Locking closes the DB, wipes keys, clears our clipboard entry, and replaces the whole
  vault UI. Top-level screens live in their own navigation entries, so their ViewModels
  (and any decrypted data they held) are destroyed.
- **Lockout**: 5 free attempts, then 30 s, 1, 2, 5, 15, 30, 60 min. The remaining time is the
  max of a same-boot monotonic estimate and a wall-clock estimate. Moving the clock forward
  or rebooting doesn't shorten it, and moving it back restarts the delay. Re-verifying
  the master password (change password, delete vault) is throttled the same way.
- Generated passwords pass to "new entry" in memory (`PendingPassword`), never as navigation
  arguments, which could be persisted in saved-instance state.

## Permissions

- `USE_BIOMETRIC` — fingerprint unlock.
- `QUERY_ALL_PACKAGES` — read other apps' labels, icons and **signing certificates** for app
  identity (Android 11+ hides them otherwise). No network permission of any kind.

## Threat model

| Threat | Mitigation |
|--------|-----------|
| Stolen phone, locked vault | DB encrypted with DEK; DEK only recoverable via Argon2id(master password) or hardware-bound biometric key. |
| Offline brute force of the header (e.g. rooted extraction) | Argon2id at ≥64 MiB / calibrated iterations; master password must be ≥10 chars with zxcvbn score ≥3. In-app attempt delays do **not** protect against offline attacks. |
| Header tampering / parameter downgrade | GCM AAD binding (tested). |
| Cloud/adb backup or device transfer exfiltration | `allowBackup=false` + exclusion rules for every domain. |
| Screenshots, screen recording, recents thumbnails | `FLAG_SECURE` on all vault windows. |
| Malicious app impersonating another app for autofill | Package name **and** signing certificate must match (Phase 4). |
| Phishing site | Registrable-domain match from browser-reported `webDomain`, browsers allowlisted by package + cert (Phase 4). |
| Another autofill service (e.g. Google Password Manager) reading the vault's own fields | Vault windows set `importantForAutofill=noExcludeDescendants` (found on-device: Google offered to save a vault password before this). |
| Network exfiltration by a dependency | No `INTERNET` permission; build fails if one is merged in. |

Out of scope: a compromised/rooted OS or malicious keyboard/accessibility service while
the vault is unlocked, or hardware attacks on the TEE/StrongBox.

## Decisions log

- Locked-state autofill can show account *usernames* (setting "Show account names when
  locked", default **on**, like Google Password Manager). The index is encrypted with a
  separate non-auth Keystore key, so it is protected at rest but readable without unlock.
  Selecting an account always requires biometric/master-password unlock. (Phase 4)
- Brute-force delays after 5 wrong attempts slow down only people using the app UI.
  The real protection is Argon2id. (Phase 3)
