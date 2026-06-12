#!/usr/bin/env bash
# Pharos release pipeline — build a signed, installable APK and (optionally) publish it
# to GitHub Releases. GitHub does NOT build this app; this script is the build pipeline.
#
# Usage:
#   ./scripts/build-release.sh            # build + sign + verify, output to dist/
#   ./scripts/build-release.sh --publish  # also create/upload a GitHub Release for the current versionName
#
# Requires: keystore.properties (gitignored) pointing at your release keystore. Without it
# the release build falls back to the debug key (installable but not your durable identity).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

source ./env.sh >/dev/null 2>&1
mkdir -p /tmp/pharos-test-home/.m2/repository   # Robolectric jar dir (unit tests / lint)

VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)"
echo "==> Building Pharos v${VERSION} (signed release, R8)"

./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleRelease

APK_SRC="app/build/outputs/apk/release/app-release.apk"
APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner | sort | tail -1)"
echo "==> Verifying signature"
"$APKSIGNER" verify --verbose "$APK_SRC" | head -3
"$APKSIGNER" verify --print-certs "$APK_SRC" | grep "certificate DN" | head -1

mkdir -p dist
OUT="dist/Pharos-v${VERSION}.apk"
cp "$APK_SRC" "$OUT"
echo "==> Built: ${ROOT}/${OUT}  ($(du -h "$OUT" | cut -f1))"

if [[ "${1:-}" == "--publish" ]]; then
  TAG="v${VERSION}"
  echo "==> Publishing GitHub Release ${TAG}"
  gh release view "$TAG" >/dev/null 2>&1 \
    && gh release upload "$TAG" "$OUT" --clobber \
    || gh release create "$TAG" "$OUT" \
         --title "Pharos ${TAG}" \
         --notes "Signed release APK for sideloading. Download on the device, enable install-from-unknown-sources for your browser, and open the APK. Works on Android 8.0+ (Pixel, Samsung)."
  echo "==> Release URL: $(gh release view "$TAG" --json url -q .url)"
fi
