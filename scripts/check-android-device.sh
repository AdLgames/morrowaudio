#!/usr/bin/env bash
set -euo pipefail
# The suite uses mock AI responses and loopback torrent peers. Keep the Google APIs
# image offline so first-boot Play Services downloads do not compete with native speech.
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
pull_screens() {
  set +e
  mkdir -p app/build/reports/device-screenshots
  for name in library player downloads widget sound backup appearance ai-lab transcript chapter-summaries recap-sheet ai-studio; do
    adb pull "/sdcard/morrow-$name.png" "app/build/reports/device-screenshots/$name.png" >/dev/null 2>&1
  done
}
# Retain early UI captures even if the emulator process exits later in the suite.
while true; do pull_screens; sleep 5; done &
capture_pid=$!
capture_screens() {
  status=$?
  kill "$capture_pid" 2>/dev/null || true
  pull_screens
  exit "$status"
}
trap capture_screens EXIT
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx768m connectedDebugAndroidTest
