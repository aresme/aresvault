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
  The database is not re-encrypted, so an old copy of the header plus the old password
  would still open the DB. **Rotate database key** (Settings) closes that gap: it generates a
  new DEK and re-keys SQLCipher in place (`changePassword` with the raw key). The order is
  crash-safe:
  1. write a *pending* header for the new DEK;
  2. re-key the database;
  3. promote the pending header.

  If the app dies in between, the next password unlock detects the pending header and either
  discards it (DB not yet re-keyed) or completes the rotation (tested on device). The
  biometric slot wrapped the old DEK, so it is removed and the user is offered re-enrolment.
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

## Autofill (Phase 4)

- **Identity.** Native apps are identified by package name **and** signing certificate
  (SHA-256, read via `PackageManager`). An entry is offered only if both match. A verified
  APK v3 key-rotation lineage also counts. Same package with a different signer, or an entry
  with no pinned certificate, → nothing is offered and a warning is logged.
- **Websites** are trusted only when the requester is an allowlisted browser *and* carries
  that browser's real signing certificate (`core/.../browsers.json`, from Google's
  privileged-apps list plus on-device-verified Tor Browser and Phoenix). A `webDomain`
  reported by any other app (e.g. a WebView claiming to be bank.com) is ignored, and the
  request is treated as coming from that app (verified on device).
- **Matching** is on the registrable domain (eTLD+1) from the bundled Public Suffix List
  (ICANN + private sections, so `alice.github.io` ≠ `bob.github.io`). Never by title or field text.
- **No secrets in responses by default.** Every account suggestion needs authentication via
  `AutofillAuthActivity`: unlock (fingerprint CryptoObject or master password) when locked,
  or a presence check (fingerprint / screen lock) when unlocked ("Ask before every fill",
  default on). Only with that setting off and the vault unlocked do datasets carry values.
- The auth activity re-derives the requester's identity from the **system-provided**
  `EXTRA_ASSIST_STRUCTURE` and re-checks the match before returning a dataset.
- Only visible, focusable fields are filled. OTP, search and payment-card fields are skipped.
  AresVault's own windows are excluded from autofill.
- **Locked-state index**: usernames, titles and identities (no passwords), encrypted with a
  non-auth Keystore AES-GCM key in `noBackupFilesDir`. It's deleted when "Show account names
  when locked" is turned off or the vault is deleted.
- Every fill request first runs the auto-lock deadline check, because XOS can freeze the
  timer.
- Instrumented tests run against a separate `com.ares.vault.staging` package and restore the
  user's autofill service afterwards.

## Generate and save (Phase 5)

- **Generate** on sign-up forms reads no stored secret, so it works while locked. The
  generator options (not secret) are mirrored to plain prefs for this. The password goes
  straight into the form's new and confirm password fields.
- **Save**: every response carries `SaveInfo` (`FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`). A
  username-only first page uses `FLAG_DELAY_SAVE` so a two-page login is saved as one entry.
  - New login, vault unlocked → saved directly after the system's "Save to AresVault?".
  - Same identity + username already saved → asks before updating. The old password moves
    to history (last 5).
  - Vault locked → `SaveActivity` unlocks first. The captured login waits in an in-memory
    store (`PendingSaveStore`, 10 min TTL) and only a random token travels in the
    PendingIntent. Plaintext is never written to disk.
- New entries pin the requester's identity: app package + signing certificate, or the site
  host from a verified browser.

## Backups (Phase 6)

- `.aresvault` = JSON container `{format, version, kdf, salt, sealed}`. The payload (entries
  and password history) is encrypted with AES-256-GCM under Argon2id(backup password, random
  16-byte salt), default 64 MiB / t=3, so it restores on slower phones. Format, version, KDF
  params and salt are bound as AAD. Hostile files with huge KDF params are rejected before
  deriving.
- The backup key is independent of the vault's keys. A backup can be restored on a new phone
  with only its backup password, which must meet the same strength policy as the master
  password.
- CSV import (Google Password Manager / Firefox) keeps Android-app identity:
  `android://<base64url cert SHA-256>@<package>/` becomes package + cert. Exact duplicates are
  skipped. The user is prompted to delete the plaintext CSV through the Storage Access
  Framework.
- Plaintext CSV export requires re-entering the master password (throttled like unlock) and
  shows an explicit warning.
- All file access goes through the Storage Access Framework. No storage permission is
  requested. Auto-lock is held for up to 5 minutes while the system file picker is open
  (screen-off still locks).

## 2FA codes (Phase 8)

- TOTP secrets are stored as `otpauth://` URIs in the encrypted database (DB v3), backed up in
  `.aresvault` files, and never put in the locked-state index (it only records *whether* an
  entry has 2FA).
- Codes use RFC 6238 over the platform's `HmacSHA1/256/512` (tested against the RFC 6238
  and RFC 4226 vectors). Counter-based HOTP is not supported.
- **Trade-off:** keeping the password and its 2FA secret in one vault means one compromise
  defeats both factors. It is opt-in per entry, and the edit screen says so.
- One-time-code autofill datasets carry no value. The code is generated inside the auth
  activity after unlock or confirmation, and only for a login matching the requester's identity.
- `CAMERA` permission is requested only when scanning. QR decoding is ZXing on-device.

## Passkeys (Phase 9)

- AresVault is a Credential Manager provider for `androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL`.
  Passkeys are ES256 (P-256) key pairs from the platform JCA. Private keys are stored PKCS#8
  in the SQLCipher DB (v4), not the Keystore, so encrypted backups include them. Attestation
  is `none`. The AAGUID is fixed, and flags are UP|UV (device-bound: BE/BS not set).
  The sign counter is incremented on every use.
- **Caller identity:**
  - **Browsers:** an origin is accepted only through `CallingAppInfo.getOrigin()` with
    AresVault's privileged allowlist, the same verified browser packages and certificates as
    autofill. The rpId must equal the origin host or a registrable parent of it, over https,
    and must not be a public suffix.
  - **Native apps:** the origin is `android:apk-key-hash:<cert SHA-256>`. The usual Digital
    Asset Links check needs the network, so AresVault instead asks the user once per
    (rpId, package, signing cert), with a clear warning, and stores the approval. A re-signed
    impostor has a different cert and gets asked again.
- **User verification:** every create and sign-in requires unlock (fingerprint CryptoObject or
  master password) or a fingerprint/screen-lock confirmation. An unlock performed by
  Credential Manager's "Unlock AresVault" step counts once for the very next operation, within
  30 s, so the user isn't prompted twice.
- `excludeCredentials` is honoured (InvalidStateError when the account already has an AresVault
  passkey).
- Verified on device: registration and assertion through the real Credential Manager, with the
  test relying party checking the signature, challenge, rpIdHash and flags (`PasskeyTest`).
  Browser passkeys follow the same code path with a browser-supplied origin, but they weren't
  exercised on device because that would mean changing the user's Chrome settings.

## Automatic backups

- At setup the backup password is stretched once with Argon2id (fresh salt). The **derived key**,
  salt and params are stored in the encrypted settings row (DB v5), never in plain files or prefs.
  Each automatic file is AES-256-GCM under that key with a fresh IV, and records the salt and
  params, so restore needs only the backup password (same `.aresvault` format).
- Backups are produced only while unlocked: a change to credentials or passkeys, debounced by
  3 s, ignoring `lastUsedAt` and sign-counter churn.
- Folder access is a persisted Storage Access Framework grant (no storage permission). Losing it
  shows "Lost access to the folder — choose it again". Pruning only touches `AresVault-auto-*` files.
- Gradle's build cache is **disabled** for this project: corrupted entries left behind by an
  out-of-memory-killed build were silently reused twice and produced jars with missing classes.

## Email backups

- Only the **encrypted** `.aresvault` file is ever emailed. Plain-text passwords are never put
  in an email. The user's email app sends it, so AresVault keeps no network access and stores no
  email credentials.
- The attachment is written to `cache/email-backups/` and shared read-only through a
  `FileProvider` limited to that folder. It is deleted on the next share and on app start.
- The backup address, reminder flag and last-sent time live in app-private prefs (not secret).
  The reminder notification shows no vault contents, and acting on it still requires unlock.
- Found on device while building this: Compose **dialogs are separate windows** and did not
  inherit MainActivity's autofill exclusion, so an autofill service was offered the backup
  password typed into a dialog. `PasswordField` and the 2FA-key/app-picker dialogs now exclude
  their window (`ExcludeWindowFromAutofill`).

## Platform quirks found on Infinix XOS

- **Inline (keyboard) suggestions are disabled.** XOS switches password fields to its own
  secure keyboard (`com.transsion.sk`), which never answers Android's inline-suggestions
  request. With inline support declared, the autofill session hangs and no suggestions
  appear. The dropdown works with every keyboard.
- Credential Manager ignores a provider whose capability is `android.credentials.…`; the
  passkey capability must be spelled `androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL`
  ("Service does not have the required capabilities" in logcat).
- On XOS, Credential Manager's settings intent opens *Users & Accounts*. Providers are chosen
  on the *Preferred service* page (the autofill picker).
- XOS replaces Android's share chooser (`com.transsion.resolver`) and ignores Intent
  selectors ("No apps can perform this action"). Email apps are therefore targeted explicitly.
- **Compatibility mode** entries (Phoenix, Opera, Samsung Internet) are ignored unless the
  package is in the system's compat allowlist (`AutofillManagerService: Ignoring not allowed
  compat package`). Those browsers need native Autofill-Framework support to work.
- A Gradle build killed by the out-of-memory killer left **corrupt build-cache entries**
  (jars with missing classes) that were silently reused. Fix: `rm -rf ~/.gradle/caches/build-cache-1`.
- XOS freezes background processes ~1.5 s after they lose their last visible window (see
  auto-lock notes). Instrumented tests keep a window open.

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
  Selecting an account always requires biometric or master-password unlock.
- "Ask before every fill" defaults to **on** (spec §1: picking an account asks for the
  fingerprint), even when the vault is already unlocked.
- Brute-force delays after 5 wrong attempts slow down only people using the app UI.
  The real protection is Argon2id.
- `QUERY_ALL_PACKAGES` is needed to read other apps' signing certificates (app identity).
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is used only by the XOS help card's exemption prompt.
- Inline keyboard suggestions are off because of the XOS secure keyboard (see above).
- Opera is not in the browser allowlist: its signing certificate couldn't be verified
  without the app installed. Use `scripts/browser-cert.sh` to add it.
- Release builds are signed with a dedicated EC P-256 key stored outside the repository, with a
  v3 rotation lineage from the original debug key so existing installs upgrade without data
  loss. `FLAG_SECURE` is disabled only in the `staging` test build (`ALLOW_SCREENSHOTS`).
- Same-package/different-signature impersonation was tested on device with an
  impostor-signed build (README → Anti-phishing test).
