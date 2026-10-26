# Security Policy

## 🔒 Security Documentation

For detailed information about AresVault's security architecture, please see [SECURITY.md](../SECURITY.md) in the root of the repository.

## 🚨 Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in AresVault, please report it responsibly:

### Where to Report

**DO NOT** open a public GitHub issue for security vulnerabilities.

Instead:
1. **Email**: Create a new issue with the label `security` and mark it as private (if GitHub private vulnerability reporting is enabled)
2. **Or**: Contact the maintainer directly via email (check profile)

### What to Include

Please provide:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if you have one)
- Your contact information

### Response Timeline

- **Initial Response**: Within 48 hours
- **Status Update**: Within 7 days
- **Fix Timeline**: Depends on severity (critical issues prioritized)

### After Reporting

- We will acknowledge your report
- We will investigate and validate the issue
- We will develop and test a fix
- We will release a security update
- We will publicly disclose the issue (with credit to you, if desired) after the fix is widely deployed

## 🛡️ Security Best Practices for Users

1. **Use a strong master password** (≥10 characters, zxcvbn score ≥3)
2. **Enable fingerprint unlock** for convenience without compromising security
3. **Regular backups** with a strong backup password
4. **Keep Android and AresVault updated**
5. **Review permissions** granted to other apps
6. **Device security**: Use screen lock, enable encryption

## 🔍 Verified Security Features

- ✅ No `INTERNET` permission (enforced at build time)
- ✅ Argon2id key derivation (calibrated per-device)
- ✅ SQLCipher database encryption
- ✅ Android Keystore with hardware backing
- ✅ Certificate pinning for anti-phishing
- ✅ Memory wiping for sensitive data
- ✅ FLAG_SECURE on all vault windows
- ✅ No cloud sync or external data transmission

## 📜 Security Audits

This project has not undergone a formal third-party security audit. We welcome security researchers to review the code and report findings responsibly.

## 🙏 Acknowledgments

We appreciate the security research community's efforts in keeping AresVault secure. Researchers who report valid vulnerabilities will be credited (with their permission) in our security advisories.
