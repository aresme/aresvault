# AresVault

Local-only Android password manager with system-wide autofill. No cloud, no account,
no `INTERNET` permission. Package `com.ares.vault`, minSdk 26, target/compile SDK 37.
Built and tested on an Infinix Hot 60 Pro+ (X6886, Android 16, XOS).

- Password generator: presets, custom, EFF passphrases, entropy meter.
- Encrypted vault: SQLCipher, Argon2id master password, fingerprint unlock via Android
  Keystore, auto-lock, brute-force delays.
- Autofill in apps and browsers:
  - suggests saved logins
  - "🔑 Generate strong password" on sign-up forms
  - "Save to AresVault?" after you submit a form, with update and password history
- Anti-phishing: apps are matched by package + signing certificate, websites by
  registrable domain from verified browsers only.
- Encrypted `.aresvault` backups, Google Password Manager / Firefox CSV import.

See [SECURITY.md](SECURITY.md) for the key hierarchy, threat model and design decisions.

## Project layout

| Module  | What it is |
|---------|------------|
| `:core` | Pure Kotlin/JVM, fast JVM tests: crypto wrappers, key hierarchy and vault header, generator, PSL/browser allowlist, field classifier and credential matcher, lockout policy, backup and CSV codecs. |
| `:data` | Android library: Argon2kt KDF, SQLCipher + Room database, Keystore keys, `VaultSession`/`VaultManager` (unlock, rotate key), repositories, autofill index, backups. |
| `:app`  | Compose UI, Hilt wiring, `AresAutofillService` and its activities, debug test harness (`src/testHarness`). |

Build types: `debug` (your install), `staging` (`com.ares.vault.staging`, used by instrumented
tests so they never touch your vault), `release` (minified).

## Requirements (Ubuntu)

- JDK 17+ (tested with Temurin 25). Gradle comes from the wrapper (`./gradlew`).
- Android SDK at `~/Android/Sdk` with platform `android-37` and build-tools.
  `local.properties` must contain `sdk.dir=/home/<you>/Android/Sdk` (not committed).
- `adb` on `PATH`.

## Connect the phone

USB: enable *Developer options → USB debugging* and accept the prompt.
Wireless: *Developer options → Wireless debugging → Pair device with pairing code*, then:

```bash
adb pair 192.168.x.y:PAIR_PORT        # enter the 6-digit code shown on the phone
adb connect 192.168.x.y:CONNECT_PORT  # the IP:port on the main Wireless debugging screen
adb devices -l                        # should list the Infinix X6886
```

XOS asks you to confirm "Install via USB" the first time. Approve it on the phone.

## Build, install, run

```bash
./gradlew :app:installDebug                     # build + install
adb shell am start -n com.ares.vault/.MainActivity
./gradlew assembleRelease                       # minified release APK (unsigned)
```

## Set up autofill

1. Onboarding (or **Settings**) → **Set AresVault as autofill service** → pick AresVault.
2. Chrome: ⋮ → Settings → **Autofill services → Autofill using another service**, then restart
   Chrome. Do the same in Brave and Edge. Firefox works without extra steps.
3. Infinix XOS: in Settings, the **Keep autofill working on Infinix** card walks you through:
   - battery-optimisation exemption
   - Phone Master auto-start
   - locking the app in Recents
   - turning off "Manage app if unused"

Using it:

- **Login forms**: tap a field → pick your account → fingerprint → filled.
  "Search vault…" lets you pick any login, and can remember it for that app or site.
- **Sign-up forms**: tap the password field → **🔑 Generate strong password** → *Use
  password*. It fills the password and confirmation fields.
- **After submitting** a form, Android asks **"Save username and password to AresVault?"**.
  For a known account, AresVault asks **Update password?** and keeps the old one in history.

Keyboard-strip (inline) suggestions are disabled on purpose: XOS's secure password keyboard
never answers Android's inline request, which made autofill hang. See SECURITY.md.

## Backup and import

**Settings → Backup & import**:
- **Export encrypted backup**: a `.aresvault` file encrypted with a backup password you
  choose (Argon2id + AES-256-GCM). Keep it off the phone.
- **Restore from backup**: merges by entry. Restoring the same file twice changes nothing.
- **Import CSV**: Google Password Manager (*Settings → Export passwords*) or Firefox. App
  logins keep their package and signing certificate. AresVault offers to delete the CSV right
  after importing.
- **Export unencrypted CSV**: Google format. Needs your master password and shows a warning.

## Debug test forms

Debug builds include a test activity:

```bash
adb shell am start -n com.ares.vault/com.ares.vault.debug.AutofillTestActivity --es mode login   # or signup | web
```

`web` loads a page claiming to be example.com inside a non-browser app. AresVault must ignore
that domain (logcat: "Ignoring webDomain reported by non-browser app").

### Anti-phishing test: same package, different signature

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

## Adding a browser

Websites are trusted only from browsers in
`core/src/main/resources/com/ares/vault/core/autofill/browsers.json` (package + signing
cert). To add one that is installed on the phone, e.g. Opera:

```bash
scripts/browser-cert.sh com.opera.browser
```

Add `{"package": "...", "certs": ["<sha256>"]}` to `browsers.json`, then rebuild.

## Logs

All app logs use the single tag `AresVault` (secrets are never logged):

```bash
adb logcat -c && adb logcat -s AresVault
# include the framework's autofill/biometric messages too:
adb logcat AresVault:V BiometricPrompt:V AutofillManagerService:V AutofillSession:V SaveUi:V '*:S'
```

(`adb logcat -s AresVault*` does not glob tags, which is why there is one tag.)

## Tests

```bash
./gradlew test                                  # JVM + Robolectric unit tests (core, data, app)
./gradlew :data:connectedDebugAndroidTest       # on-device: Argon2kt vs Bouncy Castle, SQLCipher, lockout,
                                                #   key rotation and crash recovery, backup round trip
./gradlew :app:connectedStagingAndroidTest      # end-to-end autofill: fill, locked fill, generate, save, update
```

The staging tests switch the phone's autofill service to `com.ares.vault.staging` and restore
the previous one afterwards. Keep the phone unlocked while they run:

```bash
adb shell svc power stayon usb   # keeps the screen on while plugged in
```

**Infinix XOS note:** XOS's power manager (`Usf_Hiber`) freezes app processes that have no
visible window about a second after they start. Instrumented tests therefore keep a window
open (`ForegroundRule`, or the test form).

Reports: `*/build/reports/tests/` and `*/build/reports/androidTests/connected/`.

## Build guards

`verify<Variant>NoNetworkPermission` runs before every assemble/install and fails the build if
any dependency adds `android.permission.INTERNET`.

## Third-party data

- EFF Large Wordlist: CC BY 3.0, Electronic Frontier Foundation.
- Public Suffix List: MPL 2.0, Mozilla Foundation (bundled in `core/src/main/resources`).
- Browser signing certificates: Google privileged-apps list (gstatic.com), fetched
  2026-10-15. Tor Browser and Phoenix were verified on device.
