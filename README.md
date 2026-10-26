<p align="center"><img src="docs/images/icon.png" alt="AresVault" width="120"/></p>

<h1 align="center">AresVault</h1>

<p align="center">A local-only, zero-cloud Android password manager with system-wide autofill, passkeys, and 2FA — your credentials never leave your device.</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"/>
  <img src="https://img.shields.io/badge/build-passing-brightgreen" alt="Build Status"/>
  <img src="https://img.shields.io/badge/internet-none-red" alt="No Internet"/>
</p>

---

## Why AresVault?

Your passwords are yours. AresVault is a privacy-first Android password manager that **never connects to the internet** — the `INTERNET` permission is explicitly forbidden at build time. No cloud sync, no account sign-up, no tracking. Your vault is encrypted locally with SQLCipher, Argon2id, and Android Keystore, and only you hold the keys. Open source, verifiable, and built for users who value full control over their credentials.

## ✨ Features at a glance

- 🔐 **Encrypted vault** — SQLCipher + Argon2id + Android Keystore with fingerprint unlock
- 🤖 **System-wide autofill** — Works in apps and browsers with anti-phishing protection
- 🔑 **Passkey support** — FIDO2/WebAuthn passkeys for modern passwordless authentication (Android 14+)
- ⏱️ **2FA/TOTP codes** — Built-in authenticator with autofill support
- 🛡️ **Zero internet permission** — Enforced at build time; verified before every build
- 🔄 **Encrypted backups** — `.aresvault` backups with optional auto-backup to local folders
- 📥 **CSV import** — Migrate from Google Password Manager or Firefox
- 🔏 **Password generator** — Presets, custom rules, and EFF passphrase wordlists with entropy meter
- 📱 **Tested on Android 16** — Built and verified on Infinix XOS (Hot 60 Pro+, X6886)

## Screenshots

<!-- Add screenshots here once available -->
| Home | Autofill | Password Generator |
|------|----------|--------------------|
| TBD  | TBD      | TBD                |

---

## 🏗️ Project layout

| Module  | What it is |
|---------|------------|
| `:core` | Pure Kotlin/JVM, fast JVM tests: crypto wrappers, key hierarchy and vault header, generator, PSL/browser allowlist, field classifier and credential matcher, lockout policy, backup and CSV codecs. |
| `:data` | Android library: Argon2kt KDF, SQLCipher + Room database, Keystore keys, `VaultSession`/`VaultManager` (unlock, rotate key), repositories, autofill index, backups. |
| `:app`  | Compose UI, Hilt wiring, `AresAutofillService` and its activities, debug test harness (`src/testHarness`). |

**Build types:**
- `debug` — Development builds
- `staging` — Test package (`com.ares.vault.staging`) used by instrumented tests, never touches your real vault; screenshots allowed for visual QA
- `release` — Minified, signed with your own key (see release signing section below)

## 📋 Requirements

Tested on Ubuntu with:

- **JDK 17+** (tested with Temurin 25). Gradle comes from the wrapper (`./gradlew`).
- **Android SDK** at `~/Android/Sdk` with platform `android-37` and build-tools.
  `local.properties` must contain `sdk.dir=/home/<you>/Android/Sdk` (not committed).
- **`adb`** on `PATH`.

## 📱 Connect the phone

**USB:** Enable *Developer options → USB debugging* and accept the prompt.

**Wireless:** *Developer options → Wireless debugging → Pair device with pairing code*, then:

```bash
adb pair 192.168.x.y:PAIR_PORT        # enter the 6-digit code shown on the phone
adb connect 192.168.x.y:CONNECT_PORT  # the IP:port on the main Wireless debugging screen
adb devices -l                        # should list the Infinix X6886
```

**XOS note:** XOS asks you to confirm "Install via USB" the first time. Approve it on the phone.

## 🚀 Build, install, run

```bash
./gradlew :app:installDebug                     # build + install
adb shell am start -n com.ares.vault/.MainActivity
./gradlew assembleRelease                       # minified release APK (unsigned)
```

## 🔐 Release signing (your own key)

AresVault's release key lives **outside the repo** in `~/.aresvault/` (`release.jks`,
`release.properties`, `release.pass`, `lineage.bin`). **Back up that folder offline.** Losing it
means future updates can't be installed over the app.

```bash
scripts/make-release-key.sh          # once: creates the EC P-256 key with a random password
scripts/build-release.sh --install   # minified release, signed with the key, installed as an update
./gradlew assembleRelease -PreleaseTest   # same release as com.ares.vault.rtest, for smoke-testing next to the real app
```

The first installs were debug-signed. `build-release.sh` signs with an **APK Signature
Scheme v3 rotation lineage** (debug key → release key), so `adb install -r` upgrades the
existing app **in place**. The vault, Keystore keys and settings all survive (verified on
staging). After that upgrade, plain debug-signed builds can no longer install over the app.
Use `build-release.sh`, or `scripts/install-staging.sh` for the test package.

## ⚙️ Set up autofill

### Initial setup

1. **Onboarding** (or **Settings**) → **Set AresVault as autofill service** → pick AresVault.
2. **Chrome:** ⋮ → Settings → **Autofill services → Autofill using another service**, then restart
   Chrome. Do the same in **Brave** and **Edge**. **Firefox** works without extra steps.
3. **Infinix XOS:** In Settings, the **Keep autofill working on Infinix** card walks you through:
   - Battery-optimisation exemption
   - Phone Master auto-start
   - Locking the app in Recents
   - Turning off "Manage app if unused"

### Using autofill

- **Login forms:** Tap a field → pick your account → fingerprint → filled.
  "Search vault…" lets you pick any login, and can remember it for that app or site.
- **Sign-up forms:** Tap the password field → **🔑 Generate strong password** → *Use
  password*. It fills the password and confirmation fields.
- **After submitting** a form, Android asks **"Save username and password to AresVault?"**.
  For a known account, AresVault asks **Update password?** and keeps the old one in history.

**Note:** Keyboard-strip (inline) suggestions are disabled on purpose: XOS's secure password keyboard
never answers Android's inline request, which made autofill hang. See [SECURITY.md](SECURITY.md).

## 💾 Automatic backups

**Settings → Backup & import → Automatic backups → Choose folder & turn on:**

Pick a folder (Android won't allow the root of internal storage or Downloads, so use a
subfolder), then set a backup password once. From then on, every change to your logins,
passkeys or 2FA codes writes `AresVault-auto-<date_time>.aresvault` to that folder a few
seconds later. Only the newest 3/5/10 are kept, and manual backups are never deleted.

- Choose a folder that **leaves the phone**: SD card, USB-OTG drive, or a folder synced by your
  Google Drive / OneDrive / Syncthing app. AresVault only writes a local file. It still has no
  internet permission.
- Restore any of them with **Restore from backup** and the backup password.
- Backups run only while the vault is unlocked, since that's the only time AresVault can read it.

## 📧 Email backups

**Settings → Backup & import → Email encrypted backup:**

1. Enter your dedicated **backup email address** and tap Save.
2. Tap **Email encrypted backup** and enter a backup password (it can be your master
   password). Your email app opens with the address, the subject and the encrypted
   `.aresvault` attached. Tap Send.
3. With **Weekly reminder** on, AresVault posts "Time to email your backup" once a week has
   passed since the last one. Tapping it opens the email step after unlock.

AresVault itself still has **no internet permission**: the email app sends the message, and
the attachment can't be opened without the backup password. To restore on a new phone, save
the attachment and use *Restore from backup*.

## ⏱️ 2FA codes (TOTP)

- **Add to a login:** Edit the login → *Two-factor (2FA) code* → **Scan QR code** (the code the
  site shows when you turn on 2FA), or **Enter setup key**. The detail screen shows the live
  code with a countdown and a copy button.
- **Autofill:** On "enter verification code" screens, AresVault offers **2FA code · <site>**.
  The code is computed after you confirm, so it is always current. When you fill a password
  for a login that has 2FA, the current code is also copied, ready to paste.
- **Import from Google Authenticator:** Settings → Backup & import → *Import 2FA codes* →
  scan Authenticator's *Transfer accounts → Export* QR codes. Codes attach to matching
  logins (same username, title or site mentions the issuer). The rest become new entries in a
  "2FA" folder.

**Privacy note:** Scanning uses CameraX + ZXing on the phone. Camera frames never leave the device.

## 🔑 Passkeys (Android 14+)

1. **Android Settings → Passwords & accounts → Preferred service** → choose **AresVault**
   (subtitle "Passkeys stored on this phone"). It then handles passwords *and* passkeys.
   In-app: home → **Passkeys** chip → *Turn on AresVault for passkeys*.
2. When a site or app offers **Create a passkey**, pick AresVault. Sign-in then needs only
   your fingerprint.
3. **Websites** work through browsers on the verified list (Chrome, Firefox, Brave, Edge…),
   which vouch for the site's address. Chrome must use "Autofill using another service".
4. **Native apps:** The first time an app wants a passkey for a site, AresVault asks you to
   approve that app (package + signature) for that site. Android normally checks this online,
   and AresVault has no internet. Only allow official apps.

Passkeys are listed and deleted on home → **Passkeys**, and they are included in encrypted
`.aresvault` backups.

**Debug test** (relying party simulator):
```bash
adb shell am start -n com.ares.vault.staging/com.ares.vault.debug.AutofillTestActivity --es mode passkey
```

## 📦 Backup and import

**Settings → Backup & import:**

- **Export encrypted backup:** A `.aresvault` file encrypted with a backup password you
  choose (Argon2id + AES-256-GCM). Keep it off the phone.
- **Restore from backup:** Merges by entry. Restoring the same file twice changes nothing.
- **Import CSV:** Google Password Manager (*Settings → Export passwords*) or Firefox. App
  logins keep their package and signing certificate. AresVault offers to delete the CSV right
  after importing.
- **Export unencrypted CSV:** Google format. Needs your master password and shows a warning.

## 🧪 Tests

```bash
./gradlew test                                  # JVM + Robolectric unit tests (core, data, app)
./gradlew :data:connectedDebugAndroidTest       # on-device: Argon2kt vs Bouncy Castle, SQLCipher, lockout,
                                                #   key rotation and crash recovery, backup round trip
./gradlew :app:connectedStagingAndroidTest      # end-to-end: fill, locked fill, generate, save, update, 2FA, passkeys
```

The staging tests switch the phone's autofill service to `com.ares.vault.staging` and restore
the previous one afterwards. Keep the phone unlocked while they run:

```bash
adb shell svc power stayon usb   # keeps the screen on while plugged in
```

**Infinix XOS note:** XOS's power manager (`Usf_Hiber`) freezes app processes that have no
visible window about a second after they start. Instrumented tests therefore keep a window
open (`ForegroundRule`, or the test form).

**Reports:** `*/build/reports/tests/` and `*/build/reports/androidTests/connected/`.

## 🐛 Debug test forms

Debug builds include a test activity:

```bash
adb shell am start -n com.ares.vault/com.ares.vault.debug.AutofillTestActivity --es mode login   # or signup | web
```

`web` loads a page claiming to be example.com inside a non-browser app. AresVault must ignore
that domain (logcat: "Ignoring webDomain reported by non-browser app").

### 🛡️ Anti-phishing test: same package, different signature

1. Install the staging build:
   ```bash
   ./gradlew :app:installStaging
   ```
   In the staging app, create a vault and set it as your autofill service.
2. In the staging app, add a login and **Link an Android app** → `com.ares.vault`. The
   signature is pinned. Open the debug test form above: the login is offered.
3. Replace the debug app with one signed by a different key:
   ```bash
   scripts/make-impostor-keystore.sh
   adb uninstall com.ares.vault          # Android refuses to update across signatures
   ./gradlew :app:installDebug -PimpostorSigning
   ```
4. Open the test form again: only "Search vault…" appears, and logcat shows
   `Signature mismatch for com.ares.vault … not offering`.
5. Clean up:
   ```bash
   adb uninstall com.ares.vault && ./gradlew :app:installDebug
   ```

(Verified on the X6886 on 2026-10-16.)

## 🌐 Adding a browser

Websites are trusted only from browsers in
`core/src/main/resources/com/ares/vault/core/autofill/browsers.json` (package + signing
cert). To add one that is installed on the phone, e.g. Opera:

```bash
scripts/browser-cert.sh com.opera.browser
```

Add `{"package": "...", "certs": ["<sha256>"]}` to `browsers.json`, then rebuild.

## 📋 Logs

All app logs use the single tag `AresVault` (secrets are never logged):

```bash
adb logcat -c && adb logcat -s AresVault
# include the framework's autofill/biometric messages too:
adb logcat AresVault:V BiometricPrompt:V AutofillManagerService:V AutofillSession:V SaveUi:V '*:S'
```

(`adb logcat -s AresVault*` does not glob tags, which is why there is one tag.)

## 🔒 Build guards

`verify<Variant>NoNetworkPermission` runs before every assemble/install and fails the build if
any dependency adds `android.permission.INTERNET`.

## 📚 Third-party data

- **EFF Large Wordlist:** CC BY 3.0, Electronic Frontier Foundation.
- **Public Suffix List:** MPL 2.0, Mozilla Foundation (bundled in `core/src/main/resources`).
- **Browser signing certificates:** Google privileged-apps list (gstatic.com), fetched
  2026-10-15. Tor Browser and Phoenix were verified on device.

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## 🔐 Security

See [SECURITY.md](SECURITY.md) for the threat model, key hierarchy, and responsible disclosure.

## 📄 License

```
Copyright 2024-2025 AresVault Contributors
Licensed under the Apache License, Version 2.0
```

See [LICENSE](LICENSE) for the full text.

---

<p align="center">
  <strong>Package:</strong> <code>com.ares.vault</code> · <strong>minSdk:</strong> 26 (Android 8.0) · <strong>target/compile SDK:</strong> 37 (Android 16)
</p>

<p align="center">
  Built and tested on Infinix Hot 60 Pro+ (X6886, Android 16, XOS)
</p>
