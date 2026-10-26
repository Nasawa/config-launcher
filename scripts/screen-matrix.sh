#!/usr/bin/env bash
# Pre-release screen matrix (docs/test-plan-screen-matrix.md).
# Drives one booted emulator through the matrix points in scripts/screen-matrix/points.tsv: for each point it sets
# the display size/density, font scale, night mode and rotation with adb, seeds the fixture layout (debug APK, run-as),
# walks flows F1-F7, writes uiautomator dumps + screenshots, then runs scripts/screen_matrix_check.py on the dumps.
#
# usage: scripts/screen-matrix.sh [--shard NAME] [--points REGEX] [--gate] [--apk PATH] [--out DIR] [--settle SEC]
#   --shard   only rows of this shard (small-a, small-b, phones, tablets, fold)
#   --points  only rows whose id matches this extended regex
#   --gate    only the on-box dev7 gate subset (see the test plan)
#   --apk     debug APK, or a directory holding one (default app/build/outputs/apk/debug/)
#   --settle  seconds to wait after start/reload/taps (default 4; use 15-20 on a software-only emulator)
# Exit: 0 when every point PASSes, 1 otherwise. Artifacts: <out>/<point>/ and <out>/summary.md.
set -uo pipefail

PKG=dev.nasawa.configlauncher
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
FIX=$HERE/screen-matrix
CHECK="python3 $HERE/screen_matrix_check.py"
GATE_RE='^(s320-a51-v1|s320-a51-v2|s320-mdpi-v1|p411-a51-v1|p411-a51-v2|t7-land-v1)$'

SHARD=''; POINTS='.'; OUT=screen-matrix-out; SETTLE=4; APK=''
while [ $# -gt 0 ]; do
  case $1 in
    --shard) SHARD=$2; shift 2;;
    --points) POINTS=$2; shift 2;;
    --gate) POINTS=$GATE_RE; shift;;
    --apk) APK=$2; shift 2;;
    --out) OUT=$2; shift 2;;
    --settle) SETTLE=$2; shift 2;;
    -h|--help) sed -n '2,12p' "$0"; exit 0;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
if [ -z "$APK" ]; then
  apks=("$ROOT"/app/build/outputs/apk/debug/*.apk)
  APK=${apks[0]}
fi
if [ -d "$APK" ]; then
  apks=("$APK"/*.apk)
  APK=${apks[0]}
fi
[ -f "$APK" ] || { echo "no debug APK (build with ./gradlew assembleDebug or pass --apk)" >&2; exit 2; }
mkdir -p "$OUT"

A() { timeout 120 adb "$@"; }
sh_() { A shell "$@" < /dev/null; }
pause() { sleep "${1:-$SETTLE}"; }

# dump <dir> <name>: uiautomator dump + screenshot
dump() {
  sh_ uiautomator dump /sdcard/sm.xml > /dev/null 2>&1
  sh_ cat /sdcard/sm.xml > "$1/$2.xml" 2> /dev/null
  A exec-out screencap -p > "$1/$2.png" < /dev/null 2> /dev/null || true
  [ -s "$1/$2.png" ] || rm -f "$1/$2.png"
}
# at <xml> <text|desc> <regex>: echo "x y" of the first matching node
at() { $CHECK find "$1" "$2" "$3" 2> /dev/null; }
tap() { local p x y; p=$(at "$@") || return 1; read -r x y <<< "$p"; sh_ input tap "$x" "$y"; }
longpress() { local p x y; p=$(at "$@") || return 1; read -r x y <<< "$p"; sh_ input swipe "$x" "$y" "$x" "$y" 800; }

# ANR dialogs: record the title (launcher vs System UI), tap Wait. Software-only emulators show System UI ANRs.
anr() {
  local d=$1 _
  for _ in 1 2 3; do
    sh_ uiautomator dump /sdcard/anr.xml > /dev/null 2>&1
    sh_ cat /sdcard/anr.xml > "$d/.anr.xml" 2> /dev/null
    grep -q "isn't responding\|isn&apos;t responding" "$d/.anr.xml" || break
    grep -o 'text="[^"]*responding[^"]*"' "$d/.anr.xml" >> "$d/anr.txt"
    tap "$d/.anr.xml" text '^Wait$' || break
    pause 3
  done
  rm -f "$d/.anr.xml"
}

home() { sh_ input keyevent HOME; pause 2; }

run_point() {
  local id=$1 shard=$2 size=$3 dens=$4 rot=$5 fs=$6 night=$7 fixture=$8 tier=$9 shape=${10}
  local d=$OUT/$id w h sw sh cx p x y
  rm -rf "$d"; mkdir -p "$d"
  w=${size%x*}; h=${size#*x}
  if [ "$rot" = 1 ]; then sw=$h; sh=$w; else sw=$w; sh=$h; fi
  {
    echo "id=$id"; echo "shard=$shard"; echo "size_px=$size"; echo "screen_px=${sw}x${sh}"; echo "density=$dens"
    echo "rotation=$rot"; echo "font_scale=$fs"; echo "night=$night"; echo "fixture=$fixture"; echo "tier=$tier"
    echo "shape=$shape"; echo "api=$(sh_ getprop ro.build.version.sdk | tr -d '\r')"
    echo "apk_sha256=$(sha256sum "$APK" | cut -c1-64)"
  } > "$d/meta.txt"
  echo "== $id ($shape)"

  # Display + system settings for this point
  sh_ "wm size $size; wm density $dens; settings put system font_scale $fs; cmd uimode night $night"
  sh_ "settings put system accelerometer_rotation 0; settings put system user_rotation $rot"
  pause 3
  sh_ "wm size; wm density; settings get system font_scale; cmd uimode night" > "$d/run.txt" 2>&1

  # Fresh app data, launcher as home, seeded layout (app-only mode)
  sh_ am force-stop "$PKG"; sh_ pm clear "$PKG" > /dev/null
  A push "$FIX/$fixture" /data/local/tmp/sm-launcher.json > /dev/null < /dev/null
  A push "$FIX/settings.xml" /data/local/tmp/sm-settings.xml > /dev/null < /dev/null
  sh_ "cmd package set-home-activity $PKG/.MainActivity > /dev/null 2>&1; run-as $PKG sh -c 'mkdir -p shared_prefs files/config/source && cp /data/local/tmp/sm-settings.xml shared_prefs/settings.xml && cp /data/local/tmp/sm-launcher.json files/config/source/launcher.json'"
  sh_ logcat -b all -c > /dev/null 2>&1

  # F1 cold start
  sh_ am start -W -n "$PKG/.MainActivity" > "$d/start.txt" 2>&1
  pause; anr "$d"
  # F2 reload the seeded layout
  sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/.ReloadReceiver" >> "$d/run.txt" 2>&1
  pause; anr "$d"
  dump "$d" home1

  # F3 long-press a home item (label, or the first tile when labels are hidden at large font scales)
  if longpress "$d/home1.xml" text '^QA Settings$' || longpress "$d/home1.xml" desc '^QA Settings'; then
    pause 2; dump "$d" lp-item
    sh_ dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' > "$d/top-lp-item.txt"
    sh_ input keyevent BACK; pause 2
  fi
  # F4 long-press empty grid space
  if p=$($CHECK find-empty "$d/home1.xml" "$dens"); then
    read -r x y <<< "$p"; sh_ input swipe "$x" "$y" "$x" "$y" 800; pause 2; dump "$d" lp-empty
    sh_ input keyevent BACK; pause 2
  fi
  # F5 drawer: swipe up (ux.md §1.4), then long-press an app in it
  cx=$((sw / 2))
  sh_ input swipe "$cx" $((sh * 80 / 100)) "$cx" $((sh * 30 / 100)) 300; pause
  dump "$d" drawer; echo swipe > "$d/drawer-via.txt"
  if ! grep -q 'Search apps' "$d/drawer.xml"; then
    home; tap "$d/home1.xml" text '^Apps$' && { pause; dump "$d" drawer; echo button > "$d/drawer-via.txt"; }
  fi
  if longpress "$d/drawer.xml" text '^Settings$'; then pause 2; dump "$d" drawer-lp; fi
  sh_ input keyevent BACK; pause 1; home

  # F6 Pages list from the {page} slot, then page 2
  dump "$d" .home
  if tap "$d/.home.xml" desc ', Pages$'; then
    pause; anr "$d"; dump "$d" pages
    tap "$d/pages.xml" desc '^Second, page 2 of 2' && { pause; anr "$d"; dump "$d" home2; }
  fi
  rm -f "$d/.home.xml" "$d/.home.png"

  # F7 warnings list: ⋮ → Config folder → "Layout loaded · N warnings" (app-only mode has no status.json)
  dump "$d" .w0
  if tap "$d/.w0.xml" text '^⋮$'; then
    pause; dump "$d" .w1
    if tap "$d/.w1.xml" text '^Config folder$'; then
      pause; dump "$d" config-folder
      tap "$d/config-folder.xml" text '^Layout loaded' && { pause; dump "$d" warnings; }
    fi
  fi
  rm -f "$d"/.w0.* "$d"/.w1.*
  sh_ input keyevent BACK; pause 1; sh_ input keyevent BACK; pause 1; home

  # Crash buffer, launcher ANRs, focus
  sh_ logcat -d -b crash > "$d/crash.txt" 2>&1
  sh_ logcat -d -b main -b system | grep -E "ANR in $PKG|FATAL EXCEPTION" >> "$d/crash.txt"
  sh_ dumpsys window | grep -m1 mCurrentFocus > "$d/focus.txt"

  $CHECK check "$d" > /dev/null || echo "checker error for $id" >&2
  grep -m1 '^#' "$d/result.md" 2> /dev/null
}

A wait-for-device < /dev/null
A install -r -g "$APK" < /dev/null | tee "$OUT/install.txt"
grep -q '^Success' "$OUT/install.txt" || { echo "install failed" >&2; exit 1; }

n=0
while IFS=$'\t' read -r id shard size dens rot fs night fixture tier shape <&3; do
  case $id in '#'*|'') continue;; esac
  [ -n "$SHARD" ] && [ "$shard" != "$SHARD" ] && continue
  [[ $id =~ $POINTS ]] || continue
  run_point "$id" "$shard" "$size" "$dens" "$rot" "$fs" "$night" "$fixture" "$tier" "$shape"
  n=$((n + 1))
done 3< "$FIX/points.tsv"

# Restore the emulator
sh_ "wm size reset; wm density reset; settings put system font_scale 1.0; cmd uimode night no; settings put system user_rotation 0"

[ "$n" -gt 0 ] || { echo "no points matched (shard='$SHARD' points='$POINTS')" >&2; exit 2; }
$CHECK summarize "$OUT"
