#!/bin/sh
set -eu
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${MORROW_KEYSTORE:?Set MORROW_KEYSTORE to your private morrowaudio.jks}"
: "${MORROW_PASSWORD_FILE:?Set MORROW_PASSWORD_FILE to the private password file}"
./gradlew testDebugUnitTest lintDebug assembleRelease
mkdir -p dist
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks "$MORROW_KEYSTORE" --ks-key-alias morrow \
  --ks-pass "file:$MORROW_PASSWORD_FILE" \
  --out dist/Narrumo-1.7.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose dist/Narrumo-1.7.0.apk
(cd dist && sha256sum Narrumo-1.7.0.apk > Narrumo-1.7.0.apk.sha256)
