#!/usr/bin/env bash
# Print the signing-certificate SHA-256 of an installed app, in the format used by
# core/src/main/resources/com/ares/vault/core/autofill/browsers.json.
#   scripts/browser-cert.sh com.opera.browser
# Then add {"package": "<pkg>", "certs": ["<sha256>"]} to browsers.json and rebuild.
set -euo pipefail
pkg="${1:?usage: $0 <package>}"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
apksigner="$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -1)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
apk="$(adb shell pm path "$pkg" | head -1 | sed 's/^package://' | tr -d '\r')"
[ -n "$apk" ] || { echo "$pkg is not installed" >&2; exit 1; }
adb pull "$apk" "$tmp/base.apk" >/dev/null
"$apksigner" verify --print-certs "$tmp/base.apk" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
