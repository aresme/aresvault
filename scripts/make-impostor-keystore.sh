#!/usr/bin/env bash
# Creates impostor-debug.keystore: a throwaway key used to build a "fake" AresVault debug app
# with the same package name but a different signature (see README → Anti-phishing test).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f impostor-debug.keystore ] && { echo "impostor-debug.keystore already exists"; exit 0; }
keytool -genkeypair -keystore impostor-debug.keystore -storepass impostor -keypass impostor \
  -alias impostor -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Impostor, O=Not Ares" >/dev/null 2>&1
echo "Created impostor-debug.keystore"
