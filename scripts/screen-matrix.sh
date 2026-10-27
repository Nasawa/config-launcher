#!/usr/bin/env bash
# Pre-release screen matrix (docs/test-plan-screen-matrix.md).
# Drives one booted emulator through the matrix points in scripts/screen-matrix/points.tsv: for each point it sets
# the display size/density, font scale, night mode and rotation with adb, seeds the fixture layout (debug APK, run-as),
# walks flows F1-F7, writes uiautomator dumps + screenshots, then runs scripts/screen_matrix_check.py on the dumps.
#
# usage: scripts/screen-matrix.sh [--shard NAME] [--points REGEX] [--gate] [--matrix-gate LEVEL]
#                                  [--apk PATH] [--out DIR] [--settle SEC]
#   --shard        only rows of this shard (small-a, small-b, phones, tablets, fold)
#   --points       only rows whose id matches this extended regex
#   --gate         only the on-box gate subset (see the test plan)
#   --matrix-gate  severity for L* / large G*: dev7 (advisory, default) or dev8 (blocking).
#                  Also set by MATRIX_GATE. Tag refs matching *dev8* force blocking in CI.
#   --apk          debug APK, or a directory holding one (default app/build/outputs/apk/debug/)
#   --settle       seconds to wait after start/reload/taps (default 4; use 15-20 on a software-only emulator)
# Exit: 0 when every point PASSes, 1 otherwise. Artifacts: <out>/<point>/ and <out>/summary.md.
set -uo pipefail

PKG=dev.nasawa.configlauncher
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
FIX=$HERE/screen-matrix
CHECK="python3 $HERE/screen_matrix_check.py"
GATE_RE='^(s320-a51-v1|s320-a51-v2|s320-mdpi-v1|p411-a51-v1|p411-a51-v2|t7-land-v1)$'

SHARD=''; POINTS='.'; OUT=screen-matrix-out; SETTLE=4; APK=''
# Severity switch for L* / large G* (docs/test-plan-screen-matrix.md "dev7 / dev8 gate").
MATRIX_GATE=${MATRIX_GATE:-dev7}
while [ $# -gt 0 ]; do
  case $1 in
    --shard) SHARD=$2; shift 2;;
    --points) POINTS=$2; shift 2;;
    --gate) POINTS=$GATE_RE; shift;;
    --matrix-gate) MATRIX_GATE=$2; shift 2;;
    --apk) APK=$2; shift 2;;
    --out) OUT=$2; shift 2;;
    --settle) SETTLE=$2; shift 2;;
    -h|--help) sed -n '2,14p' "$0"; exit 0;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
case $MATRIX_GATE in
  dev7|dev8) ;;
  *) echo "MATRIX_GATE must be dev7 or dev8, got '$MATRIX_GATE'" >&2; exit 2;;
esac
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

# dump <dir> <name>: uiautomator dump + screenshot. A system ANR dialog that pops up after the last anr() call would
# replace the whole dump (run 4: "Pixel Launcher isn't responding" over t7-port-v1's home1), so clear it and redump once.
dump() {
  local i
  for i in 1 2; do
    sh_ uiautomator dump /sdcard/sm.xml > /dev/null 2>&1
    sh_ cat /sdcard/sm.xml > "$1/$2.xml" 2> /dev/null
    grep -q "isn't responding\|isn&apos;t responding" "$1/$2.xml" || break
    [ "$i" = 1 ] && anr "$1"
  done
  A exec-out screencap -p > "$1/$2.png" < /dev/null 2> /dev/null || true
  [ -s "$1/$2.png" ] || rm -f "$1/$2.png"
}
# at <xml> <text|desc> <regex>: echo "x y" of the first matching node
at() { $CHECK find "$1" "$2" "$3" 2> /dev/null; }
tap() { local p x y; p=$(at "$@") || return 1; read -r x y <<< "$p"; sh_ input tap "$x" "$y"; }
# Long-press = a zero-length swipe held LP_MS. 800 ms sometimes registered as a tap on a software-only emulator
# (pr178 review); 1.6 s held 3/3, so that is the default.
LP_MS=${LP_MS:-1600}
hold() { sh_ input swipe "$1" "$2" "$1" "$2" "$LP_MS"; }
longpress() { local p x y; p=$(at "$@") || return 1; read -r x y <<< "$p"; hold "$x" "$y"; }
# retried <dir> <flow>: a gesture had to be repeated; the checker reports these as X2 (advisory) so flakes stay visible
retried() { echo "$2" >> "$1/retries.txt"; }

# Night mode. API 29 images lock it (mNightModeLocked=true, pr182 review), so `cmd uimode night` and the
# ui_night_mode setting are ignored there. Try, in order: cmd, the secure setting, cmd as root (a locked
# UiModeManagerService still accepts callers holding MODIFY_DAY_NIGHT_MODE, which root has), then the
# Settings > Display > Dark theme switch. NIGHT_VIA records which one stuck (X1); `none` if none did.
night_is() { sh_ cmd uimode night 2> /dev/null | grep -q "Night mode: $1"; }
ROOTED=''
dark_theme_ui() {
  local f=$OUT/.settings.xml
  sh_ am start -a android.settings.DISPLAY_SETTINGS > /dev/null 2>&1; pause 4
  sh_ uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; sh_ cat /sdcard/ui.xml > "$f" 2> /dev/null
  if tap "$f" text '^Dark theme$'; then
    pause 3
    sh_ uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; sh_ cat /sdcard/ui.xml > "$f" 2> /dev/null
    tap "$f" text '^(GOT IT|Got it)$' && pause 2
  fi
  rm -f "$f"; home
}
set_night() {
  NIGHT_VIA=none
  if night_is "$1"; then NIGHT_VIA=cmd; return; fi
  sh_ "settings put secure ui_night_mode $([ "$1" = yes ] && echo 2 || echo 1); cmd uimode night $1" > /dev/null 2>&1
  pause 2
  if night_is "$1"; then NIGHT_VIA=settings; return; fi
  if [ -z "$ROOTED" ]; then
    ROOTED=$(A root < /dev/null 2>&1 | tr -d '\r' | head -1); pause 3; A wait-for-device < /dev/null
    echo "adb root: $ROOTED" >&2
  fi
  sh_ cmd uimode night "$1" > /dev/null 2>&1; pause 2
  if night_is "$1"; then NIGHT_VIA=root; return; fi
  dark_theme_ui
  if night_is "$1"; then NIGHT_VIA=settings-ui; fi
}

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
lp_item() { longpress "$hd" text '^QA Settings$' || longpress "$hd" desc '^QA Settings'; }

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
    echo "gate=$MATRIX_GATE"
  } > "$d/meta.txt"
  echo "== $id ($shape)"

  # Display + system settings for this point
  # L12 RTL: shape containing "rtl" → Arabic locale (rail on start/left once L5 lands).
  if echo "$shape" | grep -qi rtl; then
    sh_ "settings put system system_locales ar-EG"
    echo "locale=ar-EG" >> "$d/meta.txt"
  else
    sh_ "settings put system system_locales en-US" >/dev/null 2>&1 || true
    echo "locale=en-US" >> "$d/meta.txt"
  fi
  sh_ "wm size $size; wm density $dens; settings put system font_scale $fs; cmd uimode night $night"
  sh_ "settings put system accelerometer_rotation 0; settings put system user_rotation $rot"
  pause 3
  sh_ "wm size; wm density; settings get system font_scale; cmd uimode night" > "$d/run.txt" 2>&1
  # Android clamps a wm size override to a multiple of the physical panel (2x on API 29, less on 35/36), so record
  # what was applied; the checker fails X0 when it differs (run 1: 1080x2400 became 1080x1920 on a 320x640 AVD).
  p=$(grep -m1 'Override size' "$d/run.txt" | grep -oE '[0-9]+x[0-9]+')
  [ -n "$p" ] || p=$(grep -m1 'Physical size' "$d/run.txt" | grep -oE '[0-9]+x[0-9]+')
  echo "applied_px=$p" >> "$d/meta.txt"
  # API 29 images ignore `cmd uimode night yes` (run 1, #150): fall back as in set_night, then record what stuck (X1)
  set_night "$night"
  echo "night_applied=$(sh_ cmd uimode night | grep -oE 'yes|no|auto' | head -1)" >> "$d/meta.txt"
  echo "night_via=$NIGHT_VIA" >> "$d/meta.txt"

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
  # L3/L5 safeDrawing: system bars + cutout from dumpsys window (checker parses → insets).
  sh_ dumpsys window > "$d/insets.txt" 2>&1 || true
  # Warm-up: the first touch after a cold start is often dropped on the emulator (pr181: the first swipe never
  # reached the launcher; main does the same), so spend it on a tap on empty grid space, which does nothing.
  if p=$($CHECK find-empty "$d/home1.xml" "$dens"); then read -r x y <<< "$p"; sh_ input tap "$x" "$y"; pause 2; fi
  if [ "$fixture" != launcher.json ] && tap "$d/home1.xml" desc '^Dismiss$'; then
    pause 2; dump "$d" home-chip      # §17.4: × collapses the banner into the count chip
  fi
  # later taps use the latest home dump: collapsing the banner moves the grid up (run 2: F3 missed at the c1 points)
  hd="$d/home1.xml"; [ -s "$d/home-chip.xml" ] && hd="$d/home-chip.xml"

  # F3 long-press a home item (label, or the first tile when labels are hidden at large font scales)
  # One retry when the menu is missing (a long-press read as a tap opens the app instead).
  if lp_item; then
    pause 2; dump "$d" lp-item
    if ! grep -q 'App info' "$d/lp-item.xml"; then
      retried "$d" F3; home; lp_item; pause 2; dump "$d" lp-item
    fi
    sh_ dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' > "$d/top-lp-item.txt"
    sh_ input keyevent BACK; pause 2
  fi
  # F4 long-press empty grid space
  if p=$($CHECK find-empty "$hd" "$dens"); then
    read -r x y <<< "$p"; hold "$x" "$y"; pause 2; dump "$d" lp-empty
    if ! grep -q 'Edit layout' "$d/lp-empty.xml"; then
      retried "$d" F4; home; hold "$x" "$y"; pause 2; dump "$d" lp-empty
    fi
    sh_ input keyevent BACK; pause 2
  fi
  # F5 drawer: swipe up (ux.md §1.4), then long-press an app in it
  read -r sw sh <<< "$($CHECK screen "$d/home1.xml" 2> /dev/null || echo "$sw $sh")"
  cx=$((sw / 2))
  sh_ input swipe "$cx" $((sh * 70 / 100)) "$cx" $((sh * 25 / 100)) 300; pause
  dump "$d" drawer; echo swipe > "$d/drawer-via.txt"
  if ! grep -q 'Search apps' "$d/drawer.xml"; then
    retried "$d" F5; home; sh_ input swipe "$cx" $((sh * 70 / 100)) "$cx" $((sh * 25 / 100)) 300; pause
    dump "$d" drawer
  fi
  if ! grep -q 'Search apps' "$d/drawer.xml"; then
    home; tap "$hd" text '^Apps$' && { pause; dump "$d" drawer; echo button > "$d/drawer-via.txt"; }
  fi
  if longpress "$d/drawer.xml" text '^(Calendar|Camera|Clock|Contacts|Settings)$'; then
    pause 2; dump "$d" drawer-lp
    if ! grep -q 'App info' "$d/drawer-lp.xml"; then
      retried "$d" F5b; sh_ input keyevent BACK; pause 1
      # the drawer may have closed (an app opened); reopen it the same way before the second try
      if ! { dump "$d" .dr; grep -q 'Search apps' "$d/.dr.xml"; }; then
        home; sh_ input swipe "$cx" $((sh * 70 / 100)) "$cx" $((sh * 25 / 100)) 300; pause
      fi
      rm -f "$d"/.dr.*
      longpress "$d/drawer.xml" text '^(Calendar|Camera|Clock|Contacts|Settings)$'; pause 2; dump "$d" drawer-lp
    fi
  fi
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

  # L1 / L7 (ux.md §1.5): on large-tier points, reverse-rotate once; hash launcher.json and dump page 2.
  if [ "$tier" = large ]; then
    sh_ "run-as $PKG sha256sum files/config/source/launcher.json 2>/dev/null | cut -c1-64" > "$d/hash-before.txt" || true
    flip=$((1 - rot))
    sh_ "settings put system user_rotation $flip"; pause 3
    sh_ "run-as $PKG sha256sum files/config/source/launcher.json 2>/dev/null | cut -c1-64" > "$d/hash-after-rot.txt" || true
    if [ -s "$d/home2.xml" ]; then
      home
      dump "$d" .home-rot
      if tap "$d/.home-rot.xml" desc ', Pages$'; then
        pause
        dump "$d" .pages-rot
        tap "$d/.pages-rot.xml" desc '^Second, page 2 of 2' && { pause; dump "$d" home2-rot; }
      fi
      rm -f "$d"/.home-rot.* "$d"/.pages-rot.*
    fi
    sh_ "settings put system user_rotation $rot"; pause 2
    home
  fi

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

dups=$(awk -F'\t' '!/^#/ && NF > 1 {print $1}' "$FIX/points.tsv" | sort | uniq -d)
[ -z "$dups" ] || { echo "duplicate point ids in points.tsv: $dups" >&2; exit 2; }

n=0
while IFS=$'\t' read -r id shard size dens rot fs night fixture tier shape <&3; do
  case $id in '#'*|'') continue;; esac
  [ -n "$SHARD" ] && [ "$shard" != "$SHARD" ] && continue
  [[ $id =~ $POINTS ]] || continue
  run_point "$id" "$shard" "$size" "$dens" "$rot" "$fs" "$night" "$fixture" "$tier" "$shape"
  n=$((n + 1))
done 3< "$FIX/points.tsv"

# Restore the emulator
sh_ "wm size reset; wm density reset; settings put system font_scale 1.0; cmd uimode night no; settings put system user_rotation 0; settings put system system_locales en-US"

[ "$n" -gt 0 ] || { echo "no points matched (shard='$SHARD' points='$POINTS')" >&2; exit 2; }
$CHECK summarize "$OUT"
