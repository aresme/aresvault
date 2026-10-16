#!/usr/bin/env bash
# Installs the staging build signed with the debug→release rotation lineage (once a package has
# been upgraded to the release key, plain debug-signed APKs can no longer update it).
set -euo pipefail
cd "$(dirname "$0")/.."
dir="$HOME/.aresvault"
bt="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)"
./gradlew --console=plain -q :app:assembleStaging
in=app/build/outputs/apk/staging/app-staging.apk
out=app/build/outputs/apk/staging/app-staging-rotated.apk
"$bt/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --next-signer --ks "$dir/release.jks" --ks-pass file:"$dir/release.pass" --ks-key-alias aresvault \
  --lineage "$dir/lineage.bin" --rotation-min-sdk-version 28 --out "$out" "$in" 2>/dev/null
adb install -r -t "$out"
