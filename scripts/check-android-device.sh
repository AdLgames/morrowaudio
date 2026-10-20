#!/usr/bin/env bash
set -euo pipefail
capture_screens() {
  status=$?
  set +e
  mkdir -p app/build/reports/device-screenshots
  for name in library player downloads widget sound backup appearance ai-lab transcript chapter-summaries recap-sheet; do
    adb pull "/sdcard/morrow-$name.png" "app/build/reports/device-screenshots/$name.png" >/dev/null 2>&1
  done
  exit "$status"
}
trap capture_screens EXIT
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx768m connectedDebugAndroidTest
