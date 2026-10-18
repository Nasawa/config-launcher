#!/usr/bin/env bash
# Install the debug APK, cold-start the launcher 3 times, and fail on a crash or ANR.
set -euo pipefail
PKG=dev.nasawa.configlauncher
mkdir -p smoke
adb install -r app/build/outputs/apk/debug/*.apk
adb logcat -c
for i in 1 2 3; do
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" | tee -a smoke/am-start.txt
  sleep 5
done
adb logcat -d > smoke/logcat.txt
grep -E 'TotalTime|WaitTime' smoke/am-start.txt || true
if grep -E "FATAL EXCEPTION|ANR in $PKG" smoke/logcat.txt; then echo "crash or ANR"; exit 1; fi
adb shell pidof "$PKG" >/dev/null || { echo "process not running"; exit 1; }
echo "smoke OK"
