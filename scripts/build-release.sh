#!/usr/bin/env bash
# Builds the minified release APK and signs it with the release key, carrying the v3 rotation
# lineage from the old debug key so it installs as an update over debug-signed installs.
#   scripts/build-release.sh [--install]
# Output: app/build/outputs/apk/release/AresVault-<version>.apk
set -euo pipefail
cd "$(dirname "$0")/.."
dir="$HOME/.aresvault"
[ -f "$dir/release.jks" ] || { echo "Run scripts/make-release-key.sh first" >&2; exit 1; }
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
bt="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)"
./gradlew --console=plain -q assembleRelease
version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
out="app/build/outputs/apk/release"
"$bt/zipalign" -f -p 4 "$out/app-release-unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --next-signer --ks "$dir/release.jks" --ks-pass file:"$dir/release.pass" --ks-key-alias aresvault \
  --lineage "$dir/lineage.bin" --rotation-min-sdk-version 28 \
  --out "$out/AresVault-$version.apk" "$out/aligned.apk" 2>/dev/null
rm -f "$out/aligned.apk"
"$bt/apksigner" verify --print-certs "$out/AresVault-$version.apk" 2>/dev/null | grep -E 'Signer|SHA-256' | head -4
echo "Built $out/AresVault-$version.apk"
if [ "${1:-}" = "--install" ]; then adb install -r "$out/AresVault-$version.apk"; fi
