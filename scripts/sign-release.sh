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
  --out dist/MorrowAudio.apk app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose dist/MorrowAudio.apk
sha256sum dist/MorrowAudio.apk > dist/MorrowAudio.apk.sha256
