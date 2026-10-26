# Changelog

All notable changes to AresVault will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-25

### Initial Release

#### Added
- **Core Security**
  - Argon2id key derivation (calibrated to ~750ms on device)
  - AES-256-GCM encryption with Android Keystore
  - SQLCipher encrypted database
  - Fingerprint unlock with hardware-backed keys
  - Auto-lock with configurable timeouts
  - Progressive lockout after failed attempts (30s → 60min)
  
- **Password Management**
  - Full CRUD for password entries
  - Password generator with presets, custom rules, EFF passphrases
  - Entropy meter and strength validation
  - Password history (last 5 passwords per entry)
  - Folders for organization
  
- **Autofill System**
  - System-wide autofill in apps and browsers
  - Anti-phishing: package + certificate verification for apps
  - Domain-based matching with Public Suffix List
  - "Generate strong password" on sign-up forms
  - "Save to AresVault?" after form submission
  - Update detection with password history preservation
  - Locked-state index for username suggestions
  
- **Passkeys (WebAuthn)**
  - Full passkey support via Credential Manager (Android 14+)
  - ES256 (P-256) key pairs
  - Browser origin verification
  - Native app approval workflow
  - User verification (biometric/password)
  
- **2FA/TOTP**
  - Built-in authenticator with live code generation
  - QR code scanning for setup (CameraX + ZXing)
  - Manual key entry
  - Import from Google Authenticator export
  - Autofill 2FA codes
  
- **Backup & Import**
  - Encrypted `.aresvault` backups (independent password)
  - Automatic backups to chosen folder
  - Email encrypted backups (no internet permission needed)
  - Weekly backup reminders
  - Import from Google Password Manager CSV
  - Import from Firefox CSV
  - Export to CSV (with security warnings)
  
- **User Experience**
  - Jetpack Compose UI with Material 3
  - Dark/light theme support
  - Biometric authentication throughout
  - Secure clipboard with auto-clear
  - Search and filtering
  - Custom app icons and labels
  
- **Security Features**
  - Zero network access (no INTERNET permission, enforced at build)
  - FLAG_SECURE on all vault windows
  - Memory wiping for sensitive data
  - Crash-safe key rotation
  - Database re-keying support
  - Verified browser allowlist
  
- **Testing & Quality**
  - Comprehensive JVM unit tests
  - Instrumented E2E tests
  - Anti-phishing verification tests
  - Build-time security checks
  - Debug test harness

#### Platform Support
- Android 8.0+ (API 26+)
- Target/Compile SDK 37 (Android 16)
- Tested on Infinix Hot 60 Pro+ (X6886, Android 16/XOS)

#### Known Limitations
- Inline keyboard suggestions disabled (XOS compatibility)
- No cloud sync (by design)
- Requires QUERY_ALL_PACKAGES permission for app certificate verification

[Unreleased]: https://github.com/yourusername/aresvault/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/yourusername/aresvault/releases/tag/v0.1.0
