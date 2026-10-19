#!/usr/bin/env bash
# #53 / test plan LP-E1: on a real emulator, a stationary long-press on a home tile must show the
# item sheet (real app name + package, `App info`) and must NOT launch the app.
# Requires a debug build installed (run-as). Seeds app-only mode with one Settings tile.
set -euo pipefail
PKG=dev.nasawa.configlauncher
mkdir -p smoke
adb shell am force-stop "$PKG"
cat > smoke/launcher.json <<'J'
{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[
 {"id":"s","type":"app","package":"com.android.settings","label":"LPTarget","x":0,"y":0}]}]}
J
cat > smoke/settings.xml <<'J'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="appOnly" value="true" /></map>
J
for f in launcher.json settings.xml; do adb push smoke/$f /data/local/tmp/$f >/dev/null; done
adb shell "run-as $PKG sh -c 'mkdir -p files/config/source shared_prefs && cp /data/local/tmp/launcher.json files/config/source/launcher.json && cp /data/local/tmp/settings.xml shared_prefs/settings.xml'"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 4
# Remove the old dump first: a failed `uiautomator dump` would otherwise leave a stale file to read.
dump() { adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true; adb shell cat /sdcard/ui.xml 2>/dev/null || true; }
for _ in $(seq 1 ${LP_WAIT:-10}); do dump > smoke/lp-before.xml; grep -q 'text="LPTarget"' smoke/lp-before.xml && break; sleep 3; done
b=$(grep -o 'text="LPTarget"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' smoke/lp-before.xml | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 || true)
[ -n "$b" ] || { echo "FAIL: tile LPTarget not on screen"; exit 1; }
read x1 y1 x2 y2 < <(echo "$b" | tr '[],' '   ')
x=$(( (x1 + x2) / 2 )); y=$(( (y1 + y2) / 2 ))
echo "long-press at $x,$y"
adb shell input swipe $x $y $x $y 800
sleep ${LP_SETTLE:-2}
top=$(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' || true)
dump > smoke/lp-after.xml
fail=0
echo "$top" | grep -q "$PKG" || { echo "FAIL: long-press launched another app: $top"; fail=1; }
grep -q 'text="App info"' smoke/lp-after.xml || { echo "FAIL: item sheet not shown (no 'App info')"; fail=1; }
grep -q 'com.android.settings' smoke/lp-after.xml || { echo "FAIL: sheet doesn't show the real package"; fail=1; }
[ $fail = 0 ] && echo "longpress smoke OK" || exit 1
