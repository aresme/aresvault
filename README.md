# AresVault

Local-only Android password manager with system-wide autofill. No cloud, no account,
no `INTERNET` permission. Package `com.ares.vault`, minSdk 26, target/compile SDK 37.

## Project layout

| Module  | What it is |
|---------|------------|
| `:core` | Pure Kotlin/JVM: AES-GCM wrapper, Argon2 abstraction + calibration, vault header, key hierarchy (later: generator, PSL matcher, CSV/backup codecs). Fast JVM unit tests. |
| `:data` | Android library: Argon2kt KDF, SQLCipher + Room database, Keystore biometric key, `VaultSession`/`VaultManager`. |
| `:app`  | Compose UI, Hilt wiring, autofill service (Phase 4+). |

## Requirements (Ubuntu)

- JDK 17+ (tested with Temurin 25). Gradle comes from the wrapper (`./gradlew`).
- Android SDK at `~/Android/Sdk` with platform `android-37` and build-tools.
  `local.properties` must contain `sdk.dir=/home/<you>/Android/Sdk` (not committed).
- `adb` on `PATH`.

## Connect the phone (wireless debugging)

On the phone: *Settings → System → Developer options → Wireless debugging → On*, then
*Pair device with pairing code*.

```bash
adb pair 192.168.x.y:PAIR_PORT      # enter the 6-digit code shown on the phone
adb connect 192.168.x.y:CONNECT_PORT  # the IP:port on the main Wireless debugging screen
adb devices -l                      # should list the Infinix X6886
```

## Build, install, run

```bash
./gradlew assembleDebug                         # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug                     # build + install on the connected phone
adb shell am start -n com.ares.vault/.MainActivity
```

## Autofill setup

1. AresVault → Settings → **Set AresVault as autofill service** (or Android Settings →
   Passwords & accounts → Autofill service / "Preferred service" on XOS → AresVault).
2. Chrome: ⋮ → Settings → **Autofill services → Autofill using another service**, then restart
   Chrome. Do the same in Brave and Edge. Firefox works natively.

Debug test forms (debug builds only):

```bash
adb shell am start -n com.ares.vault/com.ares.vault.debug.AutofillTestActivity --es mode login   # or signup | web
```

`web` loads a page claiming to be example.com inside a non-browser app. AresVault must ignore
that domain (logcat: "Ignoring webDomain reported by non-browser app").

## Logs

All app logs use the single tag `AresVault` (secrets are never logged):

```bash
adb logcat -c && adb logcat -s AresVault
# include biometric/keystore/autofill framework messages too:
adb logcat AresVault:V BiometricPrompt:V AutofillManagerService:V AutofillSession:V '*:S'
```

(`adb logcat -s AresVault*` does not glob tags, which is why there is one tag.)

## Tests

```bash
./gradlew :core:test                            # JVM unit tests (crypto, header, calibration)
./gradlew :data:connectedDebugAndroidTest       # on-device: Argon2kt vs Bouncy Castle, SQLCipher, VaultManager
./gradlew :app:connectedStagingAndroidTest      # end-to-end autofill (installs com.ares.vault.staging; phone unlocked)
./gradlew test connectedAndroidTest             # everything
```

**Infinix XOS note:** XOS's power manager (`Usf_Hiber`) freezes app processes that have
no visible window about a second after they start. Instrumented tests hold a small
foreground activity (`ForegroundRule`) for this reason. Keep the phone unlocked while tests
run (`adb shell svc power stayon usb` keeps the screen on while plugged in).

Reports: `*/build/reports/tests/` and `*/build/reports/androidTests/connected/`.

## Build guards

`verify<Variant>NoNetworkPermission` runs before every assemble/install and fails the build if any
dependency adds `android.permission.INTERNET`.

## Third-party data (from Phase 2)

- EFF Large Wordlist — CC BY 3.0, Electronic Frontier Foundation.
- Public Suffix List — MPL 2.0, Mozilla Foundation (bundled in `core/src/main/resources`).
- Browser signing certificates — Google privileged-apps list (gstatic.com), fetched 2026-10-15.

See [SECURITY.md](SECURITY.md) for the key hierarchy and threat model.
