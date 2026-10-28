#!/bin/sh
# Builds the APKs of a release tag from a clean clone and signs them so F-Droid can reproduce them:
# F-Droid builds the same commit, copies this signature onto its build and publishes ours if they match.
#   fdroid/sign-release.sh v1.0.0 ~/keys/nowcast-release.jks
# Writes release-<tag>/nowcast.app_<versionCode>.apk, the names fdroid/nowcast.app.yml's Binaries expects.
set -eu
tag=$1; keystore=$2
repo=$(git rev-parse --show-toplevel)
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -1)
out="$repo/release-$tag"; work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# a clone, not this checkout: the APK records the commit, and a worktree or other commit would differ
git clone -q "$repo" "$work" && git -C "$work" checkout -q "$tag"
cp "$repo/local.properties" "$work/" 2>/dev/null || true
(cd "$work" && ./gradlew -q :app:assembleRelease)

if [ -z "${NOWCAST_KS_PASS:-}" ]; then
    printf 'Keystore password: '; stty -echo 2>/dev/null || true; read -r NOWCAST_KS_PASS; stty echo 2>/dev/null || true; echo
fi
export NOWCAST_KS_PASS
base=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$work/app/build.gradle.kts")
mkdir -p "$out"
for split in arm64-v8a:2 x86_64:4; do
    abi=${split%:*}; code=$((base * 10 + ${split#*:}))
    # v2/v3 only and alignment kept: F-Droid's apksigcopier needs the APK unchanged apart from the signature
    "$apksigner" sign --alignment-preserved --v1-signing-enabled false --v4-signing-enabled false --ks "$keystore" --ks-pass env:NOWCAST_KS_PASS \
        --out "$out/nowcast.app_$code.apk" "$work/app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
done
"$apksigner" verify --print-certs "$out/nowcast.app_$((base * 10 + 2)).apk" | grep 'SHA-256'
ls -l "$out"
