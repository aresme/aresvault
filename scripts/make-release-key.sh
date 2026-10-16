#!/usr/bin/env bash
# One-time: creates AresVault's release signing key in ~/.aresvault (outside the repo) and a
# v3 rotation lineage debug key → release key, so the existing debug-signed install can be
# upgraded in place without losing the vault.
#
# BACK UP ~/.aresvault (USB drive / password manager export). Losing release.jks or its
# password means future updates can't be installed over the app.
set -euo pipefail
dir="$HOME/.aresvault"
mkdir -p "$dir" && chmod 700 "$dir"
[ -f "$dir/release.jks" ] && { echo "Release key already exists in $dir"; exit 0; }
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
apksigner="$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -1)"
pass="$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)"
umask 077
keytool -genkeypair -keystore "$dir/release.jks" -storetype PKCS12 -storepass "$pass" -keypass "$pass" \
  -alias aresvault -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10950 \
  -dname "CN=AresVault, O=AresVault, C=KE" >/dev/null 2>&1
umask 077
cat > "$dir/release.properties" <<PROPS
storeFile=$dir/release.jks
storePassword=$pass
keyAlias=aresvault
keyPassword=$pass
PROPS
printf '%s' "$pass" > "$dir/release.pass"
# Lineage: the debug key vouches for the release key (APK Signature Scheme v3 rotation).
"$apksigner" rotate --out "$dir/lineage.bin" \
  --old-signer --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --new-signer --ks "$dir/release.jks" --ks-pass file:"$dir/release.pass" --ks-key-alias aresvault 2>/dev/null
echo "Created $dir/release.jks, release.properties and lineage.bin. Back up $dir now."
