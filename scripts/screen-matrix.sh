#!/usr/bin/env bash
# Pre-release screen matrix (docs/test-plan-screen-matrix.md).
# Drives one booted emulator through the matrix points in scripts/screen-matrix/points.tsv: for each point it sets
# the display size/density, font scale, night mode and rotation with adb, seeds the fixture layout (debug APK, run-as),
# walks flows F1-F7, writes uiautomator dumps + screenshots, then runs scripts/screen_matrix_check.py on the dumps.
#
# usage: scripts/screen-matrix.sh [--shard NAME] [--points REGEX] [--gate] [--matrix-gate LEVEL]
#                                  [--apk PATH] [--out DIR] [--settle SEC]
#   --shard        only rows of this shard (small-a, small-b, phones, tablets, fold; hinge = real-hinge pixel_fold AVD,
#                  opt-in, needs HINGE_TEST_APK for QaHingeProbeTest)
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
TAP_ROUTING="python3 $HERE/screen_matrix_tap_routing.py"
DND="python3 $HERE/screen_matrix_dnd.py"
# Live DnD / pager probe (#187, PR #210): MATRIX_PROBES=dnd enables it on these points (launcher.json fixture only).
MATRIX_PROBES=${MATRIX_PROBES:-}
DND_POINTS=${DND_POINTS:-'^(rtl-.*|t7-land-v1|t7-port-v1|phone-land-v1|p411-a51-v1)$'}
# Pager-restore probe (#188, PR #214): MATRIX_PROBES=pager runs it after the checker on these points;
# pager-only skips F3-F7, L1/L7 and the checker on every point (probe runs only, no matrix verdict).
PAGER="python3 $HERE/screen_matrix_pager.py"
PAGER_POINTS=${PAGER_POINTS:-'^(p411-a51-v1|p411-a51-v1-a36|rtl-phone-port-v1|t7-port-v1|rtl-t7-land-v1|fold-open-v1|split-phone-v1|hinge-book-v1|rtl-hinge-book-v1)$'}
# PR #231 QA: MATRIX_PROBES=drop runs drop_probe after the checker (L checks + probe in one job); nohinge skips
# hinge_probe on the hinge shard (book-split drags come from drop_probe there).
# dev8 edge drag-and-drop pass (test-plan-screen-matrix §4): MATRIX_PROBES=edge-only runs only edge_probe on these
# points, in the point's rotation and (EDGE_FLIP_RE) the flipped one. No checker, no matrix verdict.
EDGE_POINTS=${EDGE_POINTS:-'^(p411-a51-v1|t7-port-v1|t10-port-v1|fold-open-v1|rtl-phone-port-v1)$'}
EDGE_FLIP_RE=${EDGE_FLIP_RE:-'^(p411-a51-v1|t7-port-v1|t10-port-v1)$'}
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
    -h|--help) sed -n '2,15p' "$0"; exit 0;;
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
  # Multi-display devices (pixel_fold: inner + cover) print "[Warning] Multiple displays were found..." on stdout
  # before the PNG; cut everything before the PNG signature (run 41498249766: 524 PNGs unreadable, TH/contrast SKIP).
  [ -s "$1/$2.png" ] && python3 -c 'import sys;p=sys.argv[1];b=open(p,"rb").read();i=b.find(b"\x89PNG");i>0 and open(p,"wb").write(b[i:])' "$1/$2.png"
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

# ANR dialogs: record the title (launcher vs System UI). Prefer "Close app" for Pixel/Nexus
# Launcher (Wait leaves it wedged on API 35 google_apis tablets — all-18 X3 on run 40545707363);
# otherwise tap Wait. Software-only emulators also show System UI ANRs.
anr() {
  local d=$1 _
  for _ in 1 2 3; do
    sh_ uiautomator dump /sdcard/anr.xml > /dev/null 2>&1
    sh_ cat /sdcard/anr.xml > "$d/.anr.xml" 2> /dev/null
    grep -q "isn't responding\|isn&apos;t responding" "$d/.anr.xml" || break
    grep -o 'text="[^"]*responding[^"]*"' "$d/.anr.xml" >> "$d/anr.txt"
    if grep -qE 'Pixel Launcher|Nexus Launcher|nexuslauncher' "$d/.anr.xml"; then
      tap "$d/.anr.xml" text '^Close app$' \
        || tap "$d/.anr.xml" text '^Close App$' \
        || tap "$d/.anr.xml" text '^Wait$' || break
      # Stock launcher ANRing while a second HOME is installed — disable so it cannot reclaim focus.
      disable_stock_launchers
    else
      tap "$d/.anr.xml" text '^Wait$' || break
    fi
    pause 3
  done
  rm -f "$d/.anr.xml"
}

# After our app is HOME, force-stop Pixel/Nexus (and AOSP Launcher3) so they cannot ANR over dumps.
# Do NOT pm disable-user by default: on API 35 google_apis Pixel Launcher hosts the gesture-nav
# recents provider, and disabling it drops the navigationBars inset (800×312 split went 196→220 dp
# grid, pitch 39→44; run 40553533989). MATRIX_DISABLE_STOCK_LAUNCHER=1 restores the disable.
disable_stock_launchers() {
  local p
  for p in com.google.android.apps.nexuslauncher com.android.launcher3; do
    if [ "${MATRIX_DISABLE_STOCK_LAUNCHER:-0}" = 1 ]; then
      sh_ "pm disable-user --user 0 $p" >/dev/null 2>&1 || true
    fi
    sh_ "am force-stop $p" >/dev/null 2>&1 || true
  done
}

wait_boot_idle() {
  local i b
  for i in $(seq 1 90); do
    b=$(sh_ getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$b" = 1 ] && break
    sleep 2
  done
  # Extra settle: tablet google_apis images still schedule Pixel Launcher work after boot_completed.
  pause 8
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
  # Locale from shape keywords: rtl → ar-EG; longlabel/de → de-DE; enxa → en-XA; else en-US.
  # API 35 CI images ignore `settings put system system_locales` for layoutDirection / app
  # resources (meta recorded ar-EG while status bar + chrome stayed LTR English). Apply with
  # cmd locale set-app-locales (API 33+) and, for RTL shapes, debug.force_rtl + SystemUI bounce.
  if echo "$shape" | grep -qi rtl; then
    loc=ar-EG
  elif echo "$shape" | grep -qiE 'longlabel|\bde\b'; then
    loc=de-DE
  elif echo "$shape" | grep -qi enxa; then
    loc=en-XA
  else
    loc=en-US
  fi
  sh_ "settings put system system_locales $loc" >/dev/null 2>&1 || true
  sh_ "cmd locale set-app-locales $PKG --locales $loc" >/dev/null 2>&1 || true
  if echo "$shape" | grep -qi rtl; then
    sh_ "settings put global debug.force_rtl 1" >/dev/null 2>&1 || true
  else
    sh_ "settings put global debug.force_rtl 0" >/dev/null 2>&1 || true
  fi
  # Bounce SystemUI so status-bar mirroring picks up force_rtl (best-effort on CI images).
  if echo "$shape" | grep -qi rtl; then
    sh_ "am crash com.android.systemui" >/dev/null 2>&1       || sh_ "pkill -f com.android.systemui" >/dev/null 2>&1       || true
    pause 2
  fi
  echo "locale=$loc" >> "$d/meta.txt"
  if [ "$shard" = hinge ]; then
    # Real-hinge AVD (pixel_fold profile): native panel, posture + rotation from shape (screen-matrix-hinge.sh)
    sh_ "settings put system font_scale $fs; cmd uimode night $night; settings put system accelerometer_rotation 0"
    hinge_setup "$d" "$shape"
  else
    sh_ "wm size $size; wm density $dens; settings put system font_scale $fs; cmd uimode night $night"
    sh_ "settings put system accelerometer_rotation 0; settings put system user_rotation $rot"
  fi
  # One-side cutout in landscape: enable tall cutout overlay (becomes a side inset when rotated).
  if echo "$shape" | grep -qi cutout; then
    sh_ "cmd overlay enable --user 0 com.android.internal.display.cutout.emulation.tall" >/dev/null 2>&1 \
      || sh_ "cmd overlay enable com.android.internal.display.cutout.emulation.tall" >/dev/null 2>&1 \
      || true
    echo "cutout_overlay=tall" >> "$d/meta.txt"
  else
    sh_ "cmd overlay disable --user 0 com.android.internal.display.cutout.emulation.tall" >/dev/null 2>&1 || true
  fi
  # Short multi-window: true split-screen is not reliably scriptable on CI AVDs (needs a second
  # visible task + --windowingMode). Simulate the top-half pane with wm size on a short window
  # (points.tsv size_px already set); record how we simulated it for the report.
  if echo "$shape" | grep -qiE 'multi-window|multiwindow|split'; then
    echo "multiwindow_sim=wm_size_short_pane" >> "$d/meta.txt"
    echo "multiwindow_note=true split-screen not scripted; wm size ${size} @ dens ${dens} stands in for a short page column so titleBarPaddingV keys off measured height" >> "$d/meta.txt"
  fi
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

  # Seed app-only prefs + fixture BEFORE set-home (set-home can auto-launch into empty prefs → FirstRun).
  # Verify home is showing after RELOAD; retry/reseed up to 3 times. Seed failure = harness (X3), not product.
  A push "$FIX/$fixture" /data/local/tmp/sm-launcher.json > /dev/null < /dev/null
  A push "$FIX/settings.xml" /data/local/tmp/sm-settings.xml > /dev/null < /dev/null
  seed_ok=0
  seed_attempt=0
  while [ "$seed_attempt" -lt 3 ]; do
    seed_attempt=$((seed_attempt + 1))
    sh_ am force-stop "$PKG"
    sh_ pm clear "$PKG" > /dev/null
    # Prefs + launcher first, then set-home, then force-stop again (kills any race launch), then re-copy.
    sh_ "run-as $PKG sh -c 'mkdir -p shared_prefs files/config/source && cp /data/local/tmp/sm-settings.xml shared_prefs/settings.xml && cp /data/local/tmp/sm-launcher.json files/config/source/launcher.json'"
    sh_ "cmd package set-home-activity $PKG/.MainActivity > /dev/null 2>&1"
    disable_stock_launchers
    sh_ am force-stop "$PKG"
    sh_ "run-as $PKG sh -c 'mkdir -p shared_prefs files/config/source && cp /data/local/tmp/sm-settings.xml shared_prefs/settings.xml && cp /data/local/tmp/sm-launcher.json files/config/source/launcher.json'"
    prefs_chk=$(sh_ "run-as $PKG sh -c 'grep -c appOnly shared_prefs/settings.xml; test -s files/config/source/launcher.json && echo LOK'" | tr -d '\r' | tr '\n' ' ')
    echo "seed_attempt=$seed_attempt prefs_chk=$prefs_chk" >> "$d/run.txt"
    sh_ logcat -b all -c > /dev/null 2>&1

    # F1 cold start (last attempt's start.txt is kept)
    sh_ am start -W -n "$PKG/.MainActivity" > "$d/start.txt" 2>&1
    pause; anr "$d"
    sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/.ReloadReceiver" >> "$d/run.txt" 2>&1
    pause; anr "$d"
    dump "$d" home1

    if grep -q 'content-desc="Home, Pages"' "$d/home1.xml" 2>/dev/null \
       && grep -q ', dock"' "$d/home1.xml" 2>/dev/null; then
      seed_ok=1
      break
    fi
    echo "seed: home not ready on attempt $seed_attempt" >> "$d/run.txt"
    # System dialog over home (seen on CI after RTL points: "System UI keeps stopping"). Not the launcher
    # process; keep the crash buffer as evidence, close the dialog and reseed.
    if grep -qE 'keeps stopping|isn.t responding' "$d/home1.xml" 2>/dev/null; then
      echo "seed: system dialog over home on attempt $seed_attempt" >> "$d/run.txt"
      sh_ logcat -b crash -d > "$d/sys-crash-$seed_attempt.txt" 2>&1 || true
      tap "$d/home1.xml" text '^(Close app|Close|OK|Wait)$' && pause 3
    fi
    # First-run recovery: dismiss via Skip (confirm sheet) so next clear+reseed is clean; do not treat as product.
    if grep -q 'Choose your config folder' "$d/home1.xml" 2>/dev/null; then
      echo "seed: first-run screen on attempt $seed_attempt" >> "$d/run.txt"
      if tap "$d/home1.xml" text '^Skip for now$'; then
        pause 2; dump "$d" seed-skip
        tap "$d/seed-skip.xml" text '^Skip for now$' && pause 2
      fi
    fi
  done
  echo "seed_ok=$seed_ok" >> "$d/meta.txt"
  echo "seed_attempts=$seed_attempt" >> "$d/meta.txt"
  # Locale / RTL application probe (X4). set-app-locales survives pm clear; re-assert after seed start.
  sh_ "cmd locale set-app-locales $PKG --locales $loc" >/dev/null 2>&1 || true
  if echo "$shape" | grep -qi rtl; then
    sh_ "settings put global debug.force_rtl 1" >/dev/null 2>&1 || true
  else
    sh_ "settings put global debug.force_rtl 0" >/dev/null 2>&1 || true
  fi
  # Recreate only when a non-default locale/RTL variant is in play (avoid doubling every point).
  if [ "$seed_ok" -eq 1 ] && { [ "$loc" != en-US ] || echo "$shape" | grep -qi rtl; }; then
    sh_ am force-stop "$PKG"
    # Re-copy prefs after stop (belt-and-suspenders; pm clear is not used here).
    sh_ "run-as $PKG sh -c 'mkdir -p shared_prefs files/config/source && cp /data/local/tmp/sm-settings.xml shared_prefs/settings.xml && cp /data/local/tmp/sm-launcher.json files/config/source/launcher.json'" >/dev/null 2>&1 || true
    sh_ "am start -W -n $PKG/.MainActivity" >> "$d/run.txt" 2>&1
    pause 2
    sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/.ReloadReceiver" >> "$d/run.txt" 2>&1
    pause 2
    dump "$d" home1
  fi
  app_loc=$(sh_ "cmd locale get-app-locales $PKG" 2>/dev/null | tr -d '\r' | tr '\n' ' ' | sed 's/[[:space:]]\+$//')
  force_rtl=$(sh_ "settings get global debug.force_rtl" 2>/dev/null | tr -d '\r')
  # layoutDir from the focused activity config (1 = RTL). Fall back to dumpsys package.
  layout_dir=$(sh_ "dumpsys activity activities" 2>/dev/null | tr -d '\r' | grep -oE 'layoutDir=[01]' | head -1 | cut -d= -f2)
  [ -n "$layout_dir" ] || layout_dir=$(sh_ "dumpsys activity top" 2>/dev/null | tr -d '\r' | grep -oE 'layoutDir=[01]' | head -1 | cut -d= -f2)
  echo "app_locales=$app_loc" >> "$d/meta.txt"
  echo "force_rtl=${force_rtl:-}" >> "$d/meta.txt"
  echo "layout_dir=${layout_dir:-}" >> "$d/meta.txt"
  {
    echo "app_locales=$app_loc"
    echo "force_rtl=$force_rtl"
    echo "layout_dir=$layout_dir"
    echo "want_locale=$loc"
  } > "$d/locale.txt"
  if [ "$seed_ok" -ne 1 ]; then
    echo "home1 still not seeded after $seed_attempt attempts (first-run or missing Home/dock)" > "$d/seed-failed.txt"
    echo "partial=yes" >> "$d/meta.txt"
    echo "skipping F3–F7: seed failed (harness X3)" >> "$d/run.txt"
  fi

  # L3/L5 safeDrawing: system bars + cutout from dumpsys window (checker parses → insets).
  sh_ dumpsys window > "$d/insets.txt" 2>&1 || true
  # Record the nav-bar inset so a harness change that drops it is visible (pitch reference geometry).
  echo "nav_inset=$(grep -m1 -o 'type=navigationBars frame=\[[^]]*\]\[[^]]*\]' "$d/insets.txt" | sed 's/.*frame=//' || true)" >> "$d/meta.txt"

  # Warm-up + F3–F7 only when seed produced home (else product checks would score FirstRun as app bugs).
  hd="$d/home1.xml"
  if [[ ",$MATRIX_PROBES," == *,edge-only,* ]]; then
    if [[ $id =~ $EDGE_POINTS ]] && [ "$seed_ok" -eq 1 ]; then edge_probe "$d" "$dens" "$size" "$rot" "$shape"; fi
    sh_ "settings put system user_rotation $rot"
    return
  fi
  if [[ ",$MATRIX_PROBES," == *,drop-only,* ]]; then
    if [[ $id =~ $PAGER_POINTS ]] && [ "$seed_ok" -eq 1 ]; then drop_probe "$d" "$dens" "$size" "$rot" "$shape"; fi
    sh_ "settings put system user_rotation $rot"
    return
  fi
  if [[ ",$MATRIX_PROBES," == *,pager-only,* ]]; then
    if [[ $id =~ $PAGER_POINTS ]] && [ "$seed_ok" -eq 1 ]; then pager_probe "$d" "$dens" "$size" "$rot" "$shape"; fi
    sh_ "settings put system user_rotation $rot"
    return
  fi
  if [ "$seed_ok" -eq 1 ]; then
  # L6s: dock7 on a short rail — swipe the rail toward the end so Dock7 is reachable, then dump.
  # DnD into/out of the rail is left on the manual dev8 checklist (input draganddrop is unreliable on CI).
  if [ "$fixture" = dock7.json ] && echo "$id" | grep -q land; then
    read -r _sw _sh <<< "$($CHECK screen "$d/home1.xml" 2>/dev/null || echo "$sw $sh")"
    # Rail is on the end edge: swipe up inside the rail column to reveal lower slots.
    rail_x=$((_sw * 92 / 100))
    echo "$shape" | grep -qi rtl && rail_x=$((_sw * 8 / 100)) || true
    sh_ input swipe "$rail_x" $((_sh * 75 / 100)) "$rail_x" $((_sh * 25 / 100)) 400
    pause 2
    dump "$d" home-dock-end
    # Swipe back so later LP/menu taps still see the head.
    sh_ input swipe "$rail_x" $((_sh * 25 / 100)) "$rail_x" $((_sh * 75 / 100)) 400
    pause 1
  fi
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
  if longpress "$d/drawer.xml" text '^(Calendar|Camera|Clock|Contacts|Settings|Chrome|Drive|Files|Gmail|Maps|التقويم|الكاميرا|الساعة|جهات الاتصال|الإعدادات)$'; then
    pause 2; dump "$d" drawer-lp
    if ! grep -q 'App info' "$d/drawer-lp.xml"; then
      retried "$d" F5b; sh_ input keyevent BACK; pause 1
      # the drawer may have closed (an app opened); reopen it the same way before the second try
      if ! { dump "$d" .dr; grep -q 'Search apps' "$d/.dr.xml"; }; then
        home; sh_ input swipe "$cx" $((sh * 70 / 100)) "$cx" $((sh * 25 / 100)) 300; pause
      fi
      rm -f "$d"/.dr.*
      longpress "$d/drawer.xml" text '^(Calendar|Camera|Clock|Contacts|Settings|Chrome|Drive|Files|Gmail|Maps|التقويم|الكاميرا|الساعة|جهات الاتصال|الإعدادات)$'; pause 2; dump "$d" drawer-lp
    fi
  fi
  sh_ input keyevent BACK; pause 1; home

  # F6 Pages list from the {page} slot, then page 2
  dump "$d" .home
  if tap "$d/.home.xml" desc ', Pages$'; then
    pause; anr "$d"; dump "$d" pages
    tap "$d/pages.xml" desc '^Second, page [2٢] of [2٢]' && { pause; anr "$d"; dump "$d" home2; }
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
        tap "$d/.pages-rot.xml" desc '^Second, page [2٢] of [2٢]' && { pause; dump "$d" home2-rot; }
      fi
      rm -f "$d"/.home-rot.* "$d"/.pages-rot.*
    fi
    sh_ "settings put system user_rotation $rot"; pause 2
    home
  fi
  fi  # seed_ok: F3–F7 + L1/L7

  # Crash buffer, launcher ANRs, focus
  sh_ logcat -d -b crash > "$d/crash.txt" 2>&1
  sh_ logcat -d -b main -b system | grep -E "ANR in $PKG|FATAL EXCEPTION" >> "$d/crash.txt"
  sh_ dumpsys window | grep -m1 mCurrentFocus > "$d/focus.txt"

  # TR1 before the checker so G3 short-pane option B can read tap-routing.json
  # (docs PR #209: pitchH≥32 + TR1 hit=visible; a11y overlap → #208).
  case $id in
    split-phone*|*-split-phone*) tap_routing_probe "$d" "$dens";;
  esac

  $CHECK check "$d" > /dev/null || echo "checker error for $id" >&2
  grep -m1 '^#' "$d/result.md" 2> /dev/null

  # DnD / pager probe after the checker: it rewrites the layout, so it must not feed F*/L* dumps.
  if [[ ",$MATRIX_PROBES," == *,dnd,* ]] && [[ $id =~ $DND_POINTS ]] && [ "$fixture" = launcher.json ] && [ "$seed_ok" -eq 1 ]; then
    dnd_probe "$d" "$dens"
  fi
  if [[ ",$MATRIX_PROBES," == *,pager,* ]] && [[ $id =~ $PAGER_POINTS ]] && [ "$seed_ok" -eq 1 ]; then
    pager_probe "$d" "$dens" "$size" "$rot" "$shape"
  fi
  if [[ ",$MATRIX_PROBES," == *,drop,* ]] && [[ $id =~ $PAGER_POINTS ]] && [ "$seed_ok" -eq 1 ]; then
    drop_probe "$d" "$dens" "$size" "$rot" "$shape"
  fi
  if [[ ",$MATRIX_PROBES," == *,src,* ]] && [[ $id =~ $SRC_POINTS ]] && [ "$seed_ok" -eq 1 ]; then
    src_probe "$d" "$dens" "$size" "$rot"
  fi
  [ "$shard" = hinge ] && [[ ",$MATRIX_PROBES," != *,nohinge,* ]] && hinge_probe "$d" "$shape"
  return 0
}

# --- Pager-restore probe (#188 / PR #214; scripts/screen_matrix_pager.py scores it).
# Fixture pager.json = pages home, second, third (tiles "QA Settings", "Second tile", "Third tile").
# Every case starts cold from pager.json and moves to a NON-zero page through the Pages list, then changes the
# window or reloads. Each step: pg-<case>-<step>.xml/.png; crash + ANR per case: pg-<case>-crash.txt.
pg_seed() {   # pg_seed <dir> <fixture>: cold start on <fixture>
  sh_ am force-stop "$PKG"
  A push "$FIX/$2" /data/local/tmp/sm-pager.json > /dev/null < /dev/null
  sh_ "run-as $PKG sh -c 'cp /data/local/tmp/sm-pager.json files/config/source/launcher.json'"
  sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1; pause; anr "$1"
  sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/.ReloadReceiver" > /dev/null 2>&1; pause 3; anr "$1"
}
pg_reload() {  # pg_reload <dir> <fixture>: replace the config in place and RELOAD (no restart)
  A push "$FIX/$2" /data/local/tmp/sm-pager.json > /dev/null < /dev/null
  sh_ "run-as $PKG sh -c 'cp /data/local/tmp/sm-pager.json files/config/source/launcher.json'"
  sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/.ReloadReceiver" > /dev/null 2>&1; pause; anr "$1"
}
pg_goto() {    # pg_goto <dir> <Title>: Pages list → that page (swipe fallback is not used: it is a check of its own)
  local d=$1 t=$2
  dump "$d" .pg
  tap "$d/.pg.xml" desc ', Pages$' || { rm -f "$d"/.pg.*; return 1; }
  pause 3; dump "$d" .pg
  tap "$d/.pg.xml" desc "^$t, page" || { rm -f "$d"/.pg.*; sh_ input keyevent BACK; return 1; }
  pause 3; rm -f "$d"/.pg.*
}
pg_hash() { sh_ "run-as $PKG sha256sum files/config/source/launcher.json 2>/dev/null | cut -c1-64" | tr -d '\r'; }
pg_snap() {    # pg_snap <dir> <case> <step>
  dump "$1" "pg-$2-$3"
  sh_ dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 -E 'topResumedActivity|mResumedActivity' > "$1/pg-$2-$3-top.txt" || true
  echo "$2 $3 hash=$(pg_hash)" >> "$1/pager-hashes.txt"
}
pg_case_start() { echo "== pager $2" >> "$1/run.txt"; echo "$2" >> "$1/pager-cases.txt"; sh_ logcat -b all -c > /dev/null 2>&1; }
pg_case_end() {
  { sh_ logcat -d -b crash; sh_ logcat -d -b main -b system | grep -E "ANR in $PKG|FATAL EXCEPTION|AndroidRuntime.*$PKG"; } > "$1/pg-$2-crash.txt" 2>&1
}
pg_task() { sh_ "dumpsys activity activities" 2>/dev/null | tr -d '\r' | grep -E "Task\{|mWindowingMode|windowingMode=" | grep -m3 -iE "configlauncher|freeform|multi" ; }
# dragrot + plain drop (#214 r2) as functions so the drop probe (#218) can re-run them; they use the caller's
# d, dens, rot, flip, W, H (bash dynamic scope).
pg_dragrot() {
  local p x y
  # dragrot: page 2, edit mode, long-press-drag "Second tile", rotate while the finger is down, then UP.
  # Want: drag cancelled, launcher.json unchanged, no crash, still page 2.
  pg_case_start "$d" dragrot; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager dragrot: goto failed" >> "$d/run.txt"
  dnd_edit "$d" "$dens" || echo "pager dragrot: no edit mode" >> "$d/run.txt"
  pg_snap "$d" dragrot 0-edit
  pg_hash > "$d/pg-dragrot-hash-before.txt"
  sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/pg-dragrot-config-before.json" 2>/dev/null || true
  if p=$($CHECK find "$d/pg-dragrot-0-edit.xml" text '^Second tile$' || $CHECK find "$d/pg-dragrot-0-edit.xml" desc '^Second tile$'); then
    read -r x y <<< "$p"
    sh_ "input motionevent DOWN $x $y; sleep 1.6; input motionevent MOVE $((x + W / 20)) $((y + H / 40)); input motionevent MOVE $((x + W / 10)) $((y + H / 20)); input motionevent MOVE $((x + W / 6)) $((y + H / 12))"
    dump "$d" pg-dragrot-1-dragging
    sh_ "settings put system user_rotation $flip"; pause
    sh_ "input motionevent MOVE $((x + W / 5)) $((y + H / 10)); input motionevent UP $((x + W / 5)) $((y + H / 10))"
    pause 3; anr "$d"; pg_snap "$d" dragrot 2-rotated
    sh_ "settings put system user_rotation $rot"; pause; anr "$d"; pg_snap "$d" dragrot 3-back
  else
    echo "pager dragrot: Second tile not found" >> "$d/run.txt"
  fi
  pg_hash > "$d/pg-dragrot-hash-after.txt"
  sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/pg-dragrot-config-after.json" 2>/dev/null || true
  pg_case_end "$d" dragrot

}
pg_plaindrop() {
  local p x y
  # drop (r2 LTR/RTL regression): plain drag-and-drop in edit mode, no rotation → the drop must still commit.
  pg_case_start "$d" drop; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager drop: goto failed" >> "$d/run.txt"
  dnd_edit "$d" "$dens" || echo "pager drop: no edit mode" >> "$d/run.txt"
  pg_snap "$d" drop 0-edit
  sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/pg-drop-config-before.json" 2>/dev/null || true
  if p=$($CHECK find "$d/pg-drop-0-edit.xml" text '^Second tile$' || $CHECK find "$d/pg-drop-0-edit.xml" desc '^Second tile$'); then
    read -r x y <<< "$p"
    sh_ "input motionevent DOWN $x $y; sleep 1.6; input motionevent MOVE $((x + W / 20)) $((y + H / 40)); input motionevent MOVE $((x + W / 10)) $((y + H / 20)); input motionevent MOVE $((x + W / 6)) $((y + H / 12)); sleep 0.6; input motionevent MOVE $((x + W / 5)) $((y + H / 10)); sleep 0.6; input motionevent UP $((x + W / 5)) $((y + H / 10))"
    pause 3; anr "$d"; pg_snap "$d" drop 1-dropped
  else
    echo "pager drop: Second tile not found" >> "$d/run.txt"
  fi
  sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/pg-drop-config-after.json" 2>/dev/null || true
  pg_case_end "$d" drop
}
pager_probe() {
  local d=$1 dens=$2 size=$3 rot=$4 shape=$5 flip w h hw x y tid
  w=${size%x*}; h=${size#*x}; flip=$((1 - rot))
  echo "== pager probe ($id)" >> "$d/run.txt"
  : > "$d/pager-cases.txt"; : > "$d/pager-hashes.txt"
  sh_ "settings put system user_rotation $rot"

  # cold: LTR/RTL regression: cold start lands on page 1, swipe next twice and back once (2, 3, 2)
  pg_case_start "$d" cold; pg_seed "$d" pager.json; pg_snap "$d" cold 0-start
  read -r W H <<< "$($CHECK screen "$d/pg-cold-0-start.xml" 2>/dev/null || echo "$w $h")"
  # warm-up tap (first touch after cold start is often dropped)
  if p=$($CHECK find-empty "$d/pg-cold-0-start.xml" "$dens"); then read -r x y <<< "$p"; sh_ input tap "$x" "$y"; pause 2; fi
  y=$((H * 55 / 100))
  if echo "$shape" | grep -qi rtl; then a=$((W * 20 / 100)); b=$((W * 80 / 100)); else a=$((W * 80 / 100)); b=$((W * 20 / 100)); fi
  sh_ input swipe "$a" "$y" "$b" "$y" 300; pause 3; pg_snap "$d" cold 1-next
  sh_ input swipe "$a" "$y" "$b" "$y" 300; pause 3; pg_snap "$d" cold 2-next
  sh_ input swipe "$b" "$y" "$a" "$y" 300; pause 3; pg_snap "$d" cold 3-prev
  pg_case_end "$d" cold

  # rot: page 2 → rotate → rotate back
  pg_case_start "$d" rot; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager rot: goto failed" >> "$d/run.txt"
  pg_snap "$d" rot 0-before
  sh_ "settings put system user_rotation $flip"; pause; anr "$d"; pg_snap "$d" rot 1-rotated
  sh_ "settings put system user_rotation $rot"; pause; anr "$d"; pg_snap "$d" rot 2-back
  pg_case_end "$d" rot

  # resize (fold/unfold stand-in): page 2 → half width → density change → back
  pg_case_start "$d" resize; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager resize: goto failed" >> "$d/run.txt"
  pg_snap "$d" resize 0-before
  hw=$(( w / 2 )); sh_ "wm size ${hw}x$h"; pause; anr "$d"; sh_ wm size >> "$d/pager-wm.txt"; pg_snap "$d" resize 1-half
  sh_ "wm density $(( dens * 3 / 4 ))"; pause; anr "$d"; sh_ wm density >> "$d/pager-wm.txt"; pg_snap "$d" resize 2-density
  sh_ "wm density $dens; wm size $size"; pause; anr "$d"; sh_ "wm size; wm density" >> "$d/pager-wm.txt"; pg_snap "$d" resize 3-back
  pg_case_end "$d" resize

  # mw: real freeform window if the image allows it (enter, resize twice, leave), then the short-pane wm-size stand-in
  pg_case_start "$d" mw; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager mw: goto failed" >> "$d/run.txt"
  pg_snap "$d" mw 0-before
  {
    echo "--- enable freeform"
    sh_ "settings put global enable_freeform_support 1; settings put global force_resizable_activities 1; settings put global enable_non_resizable_multi_window 1" 2>&1
    tid=$(sh_ "dumpsys activity activities" 2>/dev/null | tr -d '\r' | grep -m1 -oE "Task\{[0-9a-f]+ #[0-9]+[^}]*configlauncher" | grep -oE '#[0-9]+' | tr -d '#')
    echo "task=$tid"
    [ -n "$tid" ] && sh_ "am task set-windowing-mode $tid 5" 2>&1 || true
    [ -n "$tid" ] && sh_ "cmd activity task set-windowing-mode $tid 5" 2>&1 || true
    pg_task | grep -qi freeform || sh_ "am start -W --windowingMode 5 -n $PKG/.MainActivity" 2>&1
    pg_task
  } >> "$d/pager-mw.txt" 2>&1
  pause; anr "$d"; pg_snap "$d" mw 1-freeform
  if [ -n "$tid" ]; then
    { echo "--- resize"; sh_ "am task resize $tid 0 0 $((w * 2 / 3)) $((h / 2))" 2>&1; pg_task; } >> "$d/pager-mw.txt" 2>&1
    pause; anr "$d"; pg_snap "$d" mw 2-resize-a
    { echo "--- resize"; sh_ "am task resize $tid 0 0 $((w / 2)) $((h * 2 / 3))" 2>&1; pg_task; } >> "$d/pager-mw.txt" 2>&1
    pause; anr "$d"; pg_snap "$d" mw 3-resize-b
    { echo "--- leave"; sh_ "am task set-windowing-mode $tid 1" 2>&1; sh_ "cmd activity task set-windowing-mode $tid 1" 2>&1; pg_task; } >> "$d/pager-mw.txt" 2>&1
    pause; anr "$d"; pg_snap "$d" mw 4-left
  fi
  # short pane stand-in (as split-phone-v1): same width, 40 % height; then a taller pane; then back
  sh_ "wm size ${w}x$(( h * 40 / 100 ))"; pause; anr "$d"; pg_snap "$d" mw 5-pane-short
  sh_ "wm size ${w}x$(( h * 60 / 100 ))"; pause; anr "$d"; pg_snap "$d" mw 6-pane-tall
  sh_ "wm size $size"; pause; anr "$d"; pg_snap "$d" mw 7-full
  sh_ "wm size" >> "$d/pager-wm.txt"
  pg_case_end "$d" mw

  # reorder: on second (page 2) → second moves to the front → same page, now 1 of 3
  pg_case_start "$d" reorder; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager reorder: goto failed" >> "$d/run.txt"
  pg_snap "$d" reorder 0-before
  pg_reload "$d" pager-reorder.json; pg_snap "$d" reorder 1-reloaded
  # then a recreation on the reordered list (saved id + new index survive a rotate)
  sh_ "settings put system user_rotation $flip"; pause; anr "$d"; pg_snap "$d" reorder 2-rotated
  sh_ "settings put system user_rotation $rot"; pause; anr "$d"
  pg_case_end "$d" reorder

  # remove-last: on third (page 3) → third deleted → clamp to new last (second, 2 of 2)
  pg_case_start "$d" rmlast; pg_seed "$d" pager.json; pg_goto "$d" Third || echo "pager rmlast: goto failed" >> "$d/run.txt"
  pg_snap "$d" rmlast 0-before
  pg_reload "$d" pager-remove-last.json; pg_snap "$d" rmlast 1-reloaded
  pg_case_end "$d" rmlast

  # remove-mid: on second (page 2) → second deleted → same index (third, 2 of 2)
  pg_case_start "$d" rmmid; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager rmmid: goto failed" >> "$d/run.txt"
  pg_snap "$d" rmmid 0-before
  pg_reload "$d" pager-remove-mid.json; pg_snap "$d" rmmid 1-reloaded
  pg_case_end "$d" rmmid

  pg_dragrot; pg_plaindrop

  # rcycle (r2): repeated resize cycles — half width / back / ×3/4 density / back, three times.
  pg_case_start "$d" rcycle; pg_seed "$d" pager.json; pg_goto "$d" Second || echo "pager rcycle: goto failed" >> "$d/run.txt"
  pg_snap "$d" rcycle 0-before
  for i in 1 2 3; do
    sh_ "wm size $((w / 2))x$h"; pause; anr "$d"; pg_snap "$d" rcycle "$i-a-half"
    sh_ "wm size $size"; pause; anr "$d"; pg_snap "$d" rcycle "$i-b-full"
    sh_ "wm density $((dens * 3 / 4))"; pause; anr "$d"; pg_snap "$d" rcycle "$i-c-density"
    sh_ "wm density $dens"; pause; anr "$d"; pg_snap "$d" rcycle "$i-d-back"
  done
  pg_case_end "$d" rcycle

  # oos (out of scope, #188 stays open): drawer / edit mode across a rotate, recorded not scored
  pg_case_start "$d" oos; pg_seed "$d" pager.json; pg_goto "$d" Second || true
  dnd_edit "$d" "$dens" || true; pg_snap "$d" oos 0-edit
  sh_ "settings put system user_rotation $flip"; pause; anr "$d"; pg_snap "$d" oos 1-edit-rotated
  sh_ "settings put system user_rotation $rot"; pause; anr "$d"; home
  sh_ input swipe $((W / 2)) $((H * 70 / 100)) $((W / 2)) $((H * 25 / 100)) 300; pause; pg_snap "$d" oos 2-drawer
  sh_ "settings put system user_rotation $flip"; pause; anr "$d"; pg_snap "$d" oos 3-drawer-rotated
  sh_ "settings put system user_rotation $rot"; pause; anr "$d"
  pg_case_end "$d" oos

  $PAGER score "$d" >> "$d/run.txt" 2>&1 || echo "pager score non-zero" >> "$d/run.txt"
  grep -m1 '^# Pager' "$d/pager.md" 2>/dev/null || true
  sh_ "wm size $size; wm density $dens; settings put system user_rotation $rot"
  dnd_reset "$d"; home
}

# --- Drop spring-back probe (#212 / PR #218; scored off-CI from the artifacts, scripts/screen_matrix_drop.py
# computes the targets). CI boots with animations disabled, so this probe sets animator_duration_scale 1 (0 for the
# reduced-motion case) and restores 0 at the end. Every case: cold start on drop.json (page 1 "Drag": Drag A at
# (0,0), Drag B at (3,4); its id is "home" so a cold start opens it), Edit layout, screenrecord while one `input motionevent` chain drags and releases, then
# dumps. Files: sb-<case>.mp4, sb-<case>-times.txt (device /proc/uptime around UP), sb-<case>-gfx.txt (framestats), pg-<case>-<step>.xml/.png,
# pg-<case>-config-{before,after}.json, sb-targets-<orient>.txt.
DROPH="python3 $HERE/screen_matrix_drop.py"
sb_moves() {  # sb_moves x0 y0 x1 y1 -> "input motionevent MOVE ..." x8
  local i out=''
  for i in 1 2 3 4 5 6 7 8; do out="$out; input motionevent MOVE $(( $1 + ($3 - $1) * i / 8 )) $(( $2 + ($4 - $2) * i / 8 ))"; done
  echo "${out#; }"
}
sb_drag_cmd() { echo "input motionevent DOWN $1 $2; sleep 1.6; $(sb_moves "$1" "$2" "$3" "$4"); sleep 0.5; input motionevent UP $3 $4"; }
sb_cfg() { sh_ "run-as $PKG cat files/config/source/launcher.json" > "$1" 2>/dev/null || true; }
sb_setup() {  # sb_setup <case> [fixture]: cold start, edit mode, 0-edit snapshot, config before
  pg_case_start "$d" "$1"; pg_seed "$d" "${2:-drop.json}"
  dump "$d" .sbchk
  grep -q 'content-desc="Drag, Pages"' "$d/.sbchk.xml" || { echo "drop $1: not on Drag, goto" >> "$d/run.txt"; pg_goto "$d" Drag; }
  rm -f "$d"/.sbchk.*
  dnd_edit "$d" "$dens" || echo "drop $1: no edit mode" >> "$d/run.txt"
  pg_snap "$d" "$1" 0-edit; sb_cfg "$d/pg-$1-config-before.json"
}
# sb_run <case> <x0> <y0> <fx> <fy> <rec-seconds> [after-cmds]: record, drag (x0,y0)->(fx,fy), UP, then after-cmds
# in the same device shell (no adb round trip between UP and them).
sb_run() {
  local cs=$1 rp cmd
  cmd="echo T_DOWN \$(cat /proc/uptime); input motionevent DOWN $2 $3; sleep 1.6; $(sb_moves "$2" "$3" "$4" "$5"); sleep 0.5; echo T_UP0 \$(cat /proc/uptime); input motionevent UP $4 $5; echo T_UP1 \$(cat /proc/uptime)"
  [ -n "${7:-}" ] && cmd="$cmd; $7; echo T_AFTER \$(cat /proc/uptime)"
  echo "drag $2 $3 -> $4 $5 after=[${7:-}]" > "$d/sb-$cs-times.txt"
  sh_ "rm -f /sdcard/sb.mp4; dumpsys gfxinfo $PKG reset" > /dev/null 2>&1
  sh_ "screenrecord --time-limit $6 --bit-rate 6000000 --size $RECSZ /sdcard/sb.mp4" > "$d/sb-$cs-rec.txt" 2>&1 &
  rp=$!; sleep 1.5
  echo "T_REC_GO $(sh_ cat /proc/uptime | tr -d '\r')" >> "$d/sb-$cs-times.txt"
  sh_ "$cmd" 2>&1 | tr -d '\r' >> "$d/sb-$cs-times.txt"
  wait "$rp" || true
  # per-frame IntendedVsync (CLOCK_MONOTONIC ns; /proc/uptime is BOOTTIME, equal while the emulator never suspends): the return's real frame times
  sh_ "dumpsys gfxinfo $PKG framestats" 2>&1 | tr -d '\r' > "$d/sb-$cs-gfx.txt"
  A pull /sdcard/sb.mp4 "$d/sb-$cs.mp4" > /dev/null 2>&1 < /dev/null || echo "drop $cs: no video" >> "$d/run.txt"
}
# PR #231 / #228: sb_run2 <case> <x0> <y0> <mx> <my> <fx> <fy> <rec-seconds>: drag to (mx,my), hold 0.8 s over it
# (the dock or rail), then on to (fx,fy) and UP there (a refused drop springs back across the dock/rail).
sb_run2() {
  local cs=$1 rp cmd
  cmd="echo T_DOWN \$(cat /proc/uptime); input motionevent DOWN $2 $3; sleep 1.6; $(sb_moves "$2" "$3" "$4" "$5"); sleep 0.8; echo T_MID \$(cat /proc/uptime); $(sb_moves "$4" "$5" "$6" "$7"); sleep 0.5; echo T_UP0 \$(cat /proc/uptime); input motionevent UP $6 $7; echo T_UP1 \$(cat /proc/uptime)"
  echo "drag $2 $3 -> $4 $5 -> $6 $7" > "$d/sb-$cs-times.txt"
  sh_ "rm -f /sdcard/sb.mp4; dumpsys gfxinfo $PKG reset" > /dev/null 2>&1
  sh_ "screenrecord --time-limit $8 --bit-rate 6000000 --size $RECSZ /sdcard/sb.mp4" > "$d/sb-$cs-rec.txt" 2>&1 &
  rp=$!; sleep 1.5
  echo "T_REC_GO $(sh_ cat /proc/uptime | tr -d '\r')" >> "$d/sb-$cs-times.txt"
  sh_ "$cmd" 2>&1 | tr -d '\r' >> "$d/sb-$cs-times.txt"
  wait "$rp" || true
  sh_ "dumpsys gfxinfo $PKG framestats" 2>&1 | tr -d '\r' > "$d/sb-$cs-gfx.txt"
  A pull /sdcard/sb.mp4 "$d/sb-$cs.mp4" > /dev/null 2>&1 < /dev/null || echo "drop $cs: no video" >> "$d/run.txt"
}
sb_end() {  # sb_end <case> [later]: after snapshot (+ one ~5 s later), config after, crash/ANR
  pause 2; anr "$d"; pg_snap "$d" "$1" 1-after
  if [ -n "${2:-}" ]; then sleep 4; pg_snap "$d" "$1" 2-later; fi
  sb_cfg "$d/pg-$1-config-after.json"; pg_case_end "$d" "$1"
}
sb_geo() {  # sb_geo <orient>: one cold start + edit dump, targets -> variables
  local o=$1
  # run 41493658236: the first cold start opened page 2 (id "home"); drop.json now gives Drag that id, and
  # this falls back to the Pages list when Drag is still not shown
  pg_case_start "$d" "geo-$o"; pg_seed "$d" drop.json
  dump "$d" .sbchk
  grep -q 'content-desc="Drag, Pages"' "$d/.sbchk.xml" || { echo "drop geo-$o: not on Drag, goto" >> "$d/run.txt"; pg_goto "$d" Drag; }
  rm -f "$d"/.sbchk.*
  dnd_edit "$d" "$dens" || echo "drop geo-$o: no edit mode" >> "$d/run.txt"
  pg_snap "$d" "geo-$o" 0-edit; pg_case_end "$d" "geo-$o"
  for v in AX AY BX BY PW PH RAIL SIDE_NEAR_X SIDE_NEAR_Y SIDE_LEFT_X SIDE_LEFT_Y SIDE_RIGHT_X SIDE_RIGHT_Y TITLE_X TITLE_Y \
           DOTS_X DOTS_Y GAP_X GAP_Y DOCK_X DOCK_Y DOCKI_X DOCKI_Y CELL11_X CELL11_Y CELL10_X CELL10_Y CELL33_X CELL33_Y B24_X B24_Y SW_X1 SW_X2 SW_Y ERR; do
    unset "$v"
  done
  $DROPH targets "$d/pg-geo-$o-0-edit.xml" "$d/pg-geo-$o-0-edit.png" "$rtl" "$dens" > "$d/sb-targets-$o.txt" 2>> "$d/run.txt" || true
  # shellcheck disable=SC1090
  . "$d/sb-targets-$o.txt"
  RECSZ="$(( ${W:-1080} / 2 / 2 * 2 ))x$(( ${H:-1920} / 2 / 2 * 2 ))"
  [ -z "${ERR:-}" ] && [ -n "${AX:-}" ]
}
sb_refused() {  # sb_refused <case> <fx> <fy> [from=A|dock]: one refused drop, recorded
  local cs=$1 x0=$AX y0=$AY
  [ "${4:-A}" = dock ] && { x0=$DOCKI_X; y0=$DOCKI_Y; }
  if [ -z "$2" ]; then echo "$cs n/a" >> "$d/drop-na.txt"; return; fi
  sb_setup "$cs"; sb_run "$cs" "$x0" "$y0" "$2" "$3" 7; sb_end "$cs"
}
# #221 / PR #223: two uiautomator dumps while the finger holds the lifted tile (lifted in place, then over the
# neighbour cell (1,1)), then a refused drop on the title row; after the return, a tap on Drag B (neighbour routing).
# Files: pg-lift-0-edit.*, pg-lift-m1-held.*, pg-lift-m2-over11.*, pg-lift-1-after.*, pg-lift-2-tapb.*, sb-lift-times.txt
sb_lift() {
  local hx=$((AX + 3)) hy=$((AY + 3)) cmd
  sb_setup lift
  cmd="echo T_DOWN \$(cat /proc/uptime); input motionevent DOWN $AX $AY; sleep 1.6; input motionevent MOVE $hx $hy; sleep 0.6"
  cmd="$cmd; echo T_M1 \$(cat /proc/uptime); uiautomator dump /sdcard/sb-m1.xml; screencap -p /sdcard/sb-m1.png; echo T_M1E \$(cat /proc/uptime)"
  cmd="$cmd; $(sb_moves "$hx" "$hy" "$CELL11_X" "$CELL11_Y"); sleep 0.6"
  cmd="$cmd; echo T_M2 \$(cat /proc/uptime); uiautomator dump /sdcard/sb-m2.xml; screencap -p /sdcard/sb-m2.png; echo T_M2E \$(cat /proc/uptime)"
  cmd="$cmd; $(sb_moves "$CELL11_X" "$CELL11_Y" "$TITLE_X" "$TITLE_Y"); sleep 0.5; echo T_UP0 \$(cat /proc/uptime); input motionevent UP $TITLE_X $TITLE_Y; echo T_UP1 \$(cat /proc/uptime)"
  echo "lift $AX $AY -> $CELL11_X $CELL11_Y -> $TITLE_X $TITLE_Y" > "$d/sb-lift-times.txt"
  sh_ "$cmd" 2>&1 | tr -d '\r' >> "$d/sb-lift-times.txt"
  sh_ cat /sdcard/sb-m1.xml > "$d/pg-lift-m1-held.xml" 2>/dev/null; A pull /sdcard/sb-m1.png "$d/pg-lift-m1-held.png" > /dev/null 2>&1 < /dev/null
  sh_ cat /sdcard/sb-m2.xml > "$d/pg-lift-m2-over11.xml" 2>/dev/null; A pull /sdcard/sb-m2.png "$d/pg-lift-m2-over11.png" > /dev/null 2>&1 < /dev/null
  pause 2; anr "$d"; pg_snap "$d" lift 1-after
  sb_cfg "$d/pg-lift-config-after.json"
  sh_ input tap "$BX" "$BY"; pause 2; anr "$d"; pg_snap "$d" lift 2-tapb
  sh_ input keyevent BACK > /dev/null 2>&1; pause 1
  pg_case_end "$d" lift
}
# #221 / PR #223: drop-cards.json = drop.json + a 2x1 card (widget host) at (1,2)-(2,2). Own geo pass (a review
# notice may move the grid); then a long diagonal A -> (3,3) across the host (commit) and a refused drop on the
# dots row whose spring-back crosses the host. Leaves the cards targets in the variables (flip re-runs sb_geo).
sb_cards() {
  pg_case_start "$d" geo-cards; pg_seed "$d" drop-cards.json
  dump "$d" .sbchk
  grep -q 'content-desc="Drag, Pages"' "$d/.sbchk.xml" || { echo "drop geo-cards: not on Drag, goto" >> "$d/run.txt"; pg_goto "$d" Drag; }
  rm -f "$d"/.sbchk.*
  dnd_edit "$d" "$dens" || echo "drop geo-cards: no edit mode" >> "$d/run.txt"
  pg_snap "$d" geo-cards 0-edit; pg_case_end "$d" geo-cards
  for v in AX AY BX BY CELL33_X CELL33_Y DOTS_X DOTS_Y ERR; do unset "$v"; done
  $DROPH targets "$d/pg-geo-cards-0-edit.xml" "$d/pg-geo-cards-0-edit.png" "$rtl" "$dens" > "$d/sb-targets-cards.txt" 2>> "$d/run.txt" || true
  # shellcheck disable=SC1090
  . "$d/sb-targets-cards.txt"
  if [ -n "${ERR:-}" ] || [ -z "${AX:-}" ]; then echo "drop geo-cards: targets failed" >> "$d/run.txt"; return; fi
  sb_setup cards-diag drop-cards.json; sb_run cards-diag "$AX" "$AY" "$CELL33_X" "$CELL33_Y" 7; sb_end cards-diag
  if [ -n "${DOTS_X:-}" ]; then
    sb_setup cards-dots drop-cards.json; sb_run cards-dots "$AX" "$AY" "$DOTS_X" "$DOTS_Y" 7; sb_end cards-dots
  else echo "cards-dots n/a" >> "$d/drop-na.txt"; fi
}
# #217 / PR #233 (dev8 final QA): ⋮ -> "Show config location". MATRIX_PROBES=src runs it after the other probes on
# SRC_POINTS. Steps (all dumps src-<step>.xml/.png, launcher.json sha256 + mtime per step in src-hashes.txt):
# on (default) -> menu -> toggle off -> menu closed; rotate + back; process death (force-stop, cold start);
# problem banners with the note off: parse error, invalid, too new, missing file (RELOAD), save error (read-only
# source dir + one drag) -> each cleared; one recorded drag with the note off (sb-src-drag.mp4).
SRC_POINTS=${SRC_POINTS:-'^(p411-a51-v1|rtl-phone-port-v1|t7-port-v1|phone-land-v1|t7-land-v1)$'}
src_h() { echo "$1 $(sh_ "run-as $PKG sh -c 'sha256sum files/config/source/launcher.json 2>/dev/null | cut -c1-64; stat -c %Y.%y files/config/source/launcher.json 2>/dev/null; grep -o showSource[^/]*value=.[a-z]*. shared_prefs/settings.xml'" | tr -d '\r' | tr '\n' ' ')" >> "$d/src-hashes.txt"; }
src_snap() { dump "$d" "src-$1"; src_h "$1"; }
src_reload() { sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/.ReloadReceiver" 2>&1 | tr -d '\r' | grep -m1 -o 'result=.*' >> "$d/src-reload.txt"; pause; anr "$d"; }
src_menu_toggle() {  # open ⋮, dump menu (<step>-menu), tap the item, dump (<step>-menu2), close
  dump "$d" .src; tap "$d/.src.xml" text '^⋮$' || { echo "src $1: no ⋮" >> "$d/run.txt"; rm -f "$d"/.src.*; return 1; }
  pause 2; src_snap "$1-menu"
  tap "$d/src-$1-menu.xml" text '^Show config location$' || { echo "src $1: no item" >> "$d/run.txt"; sh_ input keyevent BACK; return 1; }
  pause 2; src_snap "$1-menu2"; sh_ input keyevent BACK; pause 2; rm -f "$d"/.src.*
}
src_put() {  # src_put <sed-expr|rm|good>: change launcher.json in place, then RELOAD
  case $1 in
    good) sh_ "run-as $PKG sh -c 'cp /data/local/tmp/sm-launcher.json files/config/source/launcher.json'";;
    rm) sh_ "run-as $PKG sh -c 'rm -f files/config/source/launcher.json'";;
    junk) sh_ "run-as $PKG sh -c 'echo \"{ not json\" > files/config/source/launcher.json'";;
    *) sh_ "sed '$1' /data/local/tmp/sm-launcher.json > /data/local/tmp/sm-src.json; chmod 644 /data/local/tmp/sm-src.json; run-as $PKG sh -c 'cp /data/local/tmp/sm-src.json files/config/source/launcher.json'";;
  esac
  src_reload
}
src_probe() {
  local d=$1 dens=$2 size=$3 rot=$4 flip p x y ex ey
  flip=$((1 - rot))
  echo "== src probe ($id)" >> "$d/run.txt"; : > "$d/src-hashes.txt"; : > "$d/src-reload.txt"
  sh_ "settings put system user_rotation $rot"; sh_ logcat -b all -c > /dev/null 2>&1
  sh_ am force-stop "$PKG"; src_put good > /dev/null 2>&1
  sh_ "run-as $PKG sh -c 'cp /data/local/tmp/sm-settings.xml shared_prefs/settings.xml'"
  sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1; pause; anr "$d"
  sh_ "run-as $PKG cat files/config/source/status.json" > "$d/src-status-0.json" 2>/dev/null || true
  src_snap 0-on
  src_menu_toggle 1 && src_snap 2-off
  sh_ "settings put system user_rotation $flip"; pause; anr "$d"; src_snap 3-rot
  sh_ "settings put system user_rotation $rot"; pause; anr "$d"; src_snap 4-back
  sh_ am force-stop "$PKG"; sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1; pause; anr "$d"; src_snap 5-cold
  # problem banners with the note off
  src_put junk; src_snap 6-parse;   src_put good; src_snap 6-parse-clear
  src_put 's/"columns": 4/"columns": "four"/'; src_snap 7-invalid; src_put good; src_snap 7-invalid-clear
  src_put 's/"schemaVersion": 1/"schemaVersion": 99/'; src_snap 8-toonew; src_put good; src_snap 8-toonew-clear
  src_put rm; src_snap 9-missing; src_put good; src_snap 9-missing-clear
  # save error: read-only file + dir, then one committed drag (QA Settings -> empty cell), Done
  sh_ "run-as $PKG sh -c 'chmod 444 files/config/source/launcher.json; chmod 555 files/config/source'"
  if dnd_edit "$d" "$dens"; then
    dump "$d" src-10-edit
    p=$(at "$d/src-10-edit.xml" text '^QA Settings$') && read -r x y <<< "$p"
    p=$($CHECK find-empty "$d/src-10-edit.xml" "$dens") && read -r ex ey <<< "$p"
    [ -n "${x:-}" ] && [ -n "${ex:-}" ] && dnd_drag "$x" "$y" "$ex" "$ey"
    pause 4; src_snap 10-saveerr-edit
    tap "$d/src-10-saveerr-edit.xml" text '^Done$' || true
    pause 3; anr "$d"; src_snap 10-saveerr
    rm -f "$d"/.srce.*
  else echo "src 10: no edit mode" >> "$d/run.txt"; fi
  sh_ "run-as $PKG sh -c 'chmod 755 files/config/source; chmod 644 files/config/source/launcher.json'"
  sh_ am force-stop "$PKG"; src_put good > /dev/null 2>&1
  sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1; pause; anr "$d"; src_snap 10-saveerr-clear
  # one recorded drag with the note off (DragOverlay, #231): QA Settings -> empty cell, committed
  if dnd_edit "$d" "$dens"; then
    dump "$d" src-11-edit
    W=${size%x*}; H=${size#*x}; read -r W H <<< "$($CHECK screen "$d/src-11-edit.xml" 2>/dev/null || echo "$W $H")"
    RECSZ="$(( W / 2 / 2 * 2 ))x$(( H / 2 / 2 * 2 ))"
    p=$(at "$d/src-11-edit.xml" text '^QA Settings$') && read -r x y <<< "$p"
    p=$($CHECK find-empty "$d/src-11-edit.xml" "$dens") && read -r ex ey <<< "$p"
    sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/src-drag-config-before.json" 2>/dev/null || true
    sh_ "settings put global animator_duration_scale 1"
    [ -n "${x:-}" ] && [ -n "${ex:-}" ] && sb_run src-drag "$x" "$y" "$ex" "$ey" 7
    sh_ "settings put global animator_duration_scale 0"
    pause 3; anr "$d"; src_snap 11-after
    sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/src-drag-config-after.json" 2>/dev/null || true
    tap "$d/src-11-after.xml" text '^Done$' || true; pause 2
  else echo "src 11: no edit mode" >> "$d/run.txt"; fi
  sh_ "logcat -d -b crash" 2>/dev/null | tr -d '\r' > "$d/src-crash.txt"
  sh_ "wm size $size; wm density $dens; settings put system user_rotation $rot"
  dnd_reset "$d"; home
}
drop_probe() {
  local d=$1 dens=$2 size=$3 rot=$4 shape=$5 flip rtl=0 o
  flip=$((1 - rot)); { [[ $id == rtl-* ]] || echo "$shape" | grep -qi rtl; } && rtl=1
  echo "== drop probe ($id) rtl=$rtl" >> "$d/run.txt"
  : > "$d/pager-cases.txt"; : > "$d/pager-hashes.txt"; : > "$d/drop-na.txt"
  sh_ "settings put system user_rotation $rot; settings put global animator_duration_scale 1; settings put system show_touches 1"
  sh_ "settings get global animator_duration_scale" > "$d/sb-animscale.txt" 2>&1
  for o in base flip; do
    [ "$o" = flip ] && { sh_ "settings put system user_rotation $flip"; pause; anr "$d"; }
    sb_geo "$o" || { echo "drop geo-$o: targets failed" >> "$d/run.txt"; continue; }
    # 1-3: refused drops (side margin on A's side, title/notice row, dots, gap above the dock); RTL: left margin too
    sb_refused "$o-side" "${SIDE_NEAR_X:-}" "${SIDE_NEAR_Y:-}"
    [ "$rtl" = 1 ] && sb_refused "$o-rtl-left" "${SIDE_LEFT_X:-}" "${SIDE_LEFT_Y:-}"
    sb_refused "$o-title" "${TITLE_X:-}" "${TITLE_Y:-}"
    if [ "$o" = base ]; then
      sb_refused "$o-dots" "${DOTS_X:-}" "${DOTS_Y:-}"
      sb_refused "$o-gap" "${GAP_X:-}" "${GAP_Y:-}"
    fi
    # 4: rail — from the grid and from the rail, into the gap beside the rail (or the title when there is none)
    if [ -n "${RAIL:-}" ]; then
      if [ "$RAIL" = left ]; then gx=${SIDE_LEFT_X:-$TITLE_X}; gy=${SIDE_LEFT_Y:-$TITLE_Y}; else gx=${SIDE_RIGHT_X:-$TITLE_X}; gy=${SIDE_RIGHT_Y:-$TITLE_Y}; fi
      sb_refused "$o-rail-grid" "$gx" "$gy"
      # from the rail: same drop point; the dock item has no label, so its press point is its centre
      sb_refused "$o-rail-rail" "$gx" "$gy" dock
    fi
    # PR #231 (#228): over the dock/rail, held, then off it to a refused spot (bottom dock: the gap above it, else
    # the title; rail: the margin beside it) -> the spring-back crosses the dock/rail. pexit: A across the grid to
    # 4 px from the far window edge (past the pager's end edge), UP there.
    if [ -n "${DOCK_X:-}" ]; then
      if [ -n "${RAIL:-}" ]; then rx=$gx; ry=$gy; else rx=${GAP_X:-$TITLE_X}; ry=${GAP_Y:-$TITLE_Y}; fi
      if [ -n "$rx" ]; then
        sb_setup "$o-dock-refuse"; sb_run2 "$o-dock-refuse" "$AX" "$AY" "$DOCK_X" "$DOCK_Y" "$rx" "$ry" 9; sb_end "$o-dock-refuse"
        if [ "$o" = base ]; then
          sb_setup rm-dock; sh_ "settings put global animator_duration_scale 0"
          sb_run2 rm-dock "$AX" "$AY" "$DOCK_X" "$DOCK_Y" "$rx" "$ry" 9
          sh_ "settings put global animator_duration_scale 1"; sb_end rm-dock
        fi
      else echo "$o-dock-refuse n/a" >> "$d/drop-na.txt"; fi
    else echo "$o-dock-refuse n/a" >> "$d/drop-na.txt"; fi
    if [ "$rtl" = 1 ]; then px=4; else px=$(( ${W:-1080} - 5 )); fi
    sb_setup "$o-pexit"; sb_run "$o-pexit" "$AX" "$AY" "$px" "$AY" 7; sb_end "$o-pexit"
    if [ "$o" = base ] && [ -n "${DOCKI_X:-}" ]; then
      # dock/rail item back onto the grid (cell (1,1) drop point): must commit
      sb_setup dock-cell; sb_run dock-cell "$DOCKI_X" "$DOCKI_Y" "$CELL11_X" "$CELL11_Y" 7; sb_end dock-cell
    fi
    [ "$o" = flip ] && continue
    # RTL valid drop toward the left: A (0,0) -> mirrored (1,0), physical -x; must commit
    if [ "$rtl" = 1 ]; then sb_setup rtl-valid-left; sb_run rtl-valid-left "$AX" "$AY" "$CELL10_X" "$CELL10_Y" 7; sb_end rtl-valid-left; fi
    # 5: reduced motion — instant return and nothing blocked: a pager swipe right after UP must page
    sb_setup rm; sh_ "settings put global animator_duration_scale 0"
    sb_run rm "$AX" "$AY" "$TITLE_X" "$TITLE_Y" 7 "input swipe $SW_X1 $SW_Y $SW_X2 $SW_Y 300"
    sh_ "settings put global animator_duration_scale 1"; sb_end rm later
    # 6a: pager swipe during the return is dropped (not replayed later); then a control swipe pages
    sb_setup swipe; sb_run swipe "$AX" "$AY" "$TITLE_X" "$TITLE_Y" 7 "input swipe $SW_X1 $SW_Y $SW_X2 $SW_Y 300"
    pause 2; anr "$d"; pg_snap "$d" swipe 1-after; sleep 4; pg_snap "$d" swipe 2-later
    sh_ "input swipe $SW_X1 $SW_Y $SW_X2 $SW_Y 300"; pause 3; pg_snap "$d" swipe 3-control
    sb_cfg "$d/pg-swipe-config-after.json"; pg_case_end "$d" swipe
    # 6b/6c: quick re-drag of the same tile, then of another tile, right after a refused release
    sb_setup redrag-same
    sb_run redrag-same "$AX" "$AY" "$TITLE_X" "$TITLE_Y" 10 "$(sb_drag_cmd "$AX" "$AY" "$CELL11_X" "$CELL11_Y")"
    sb_end redrag-same
    sb_setup redrag-other
    sb_run redrag-other "$AX" "$AY" "$TITLE_X" "$TITLE_Y" 10 "$(sb_drag_cmd "$BX" "$BY" "$B24_X" "$B24_Y")"
    sb_end redrag-other
    # 7: rotate during the return
    sb_setup rot; sb_run rot "$AX" "$AY" "$TITLE_X" "$TITLE_Y" 7 "settings put system user_rotation $flip"
    pause 3; anr "$d"; pg_snap "$d" rot 1-after
    sh_ "settings put system user_rotation $rot"; pause; anr "$d"; pg_snap "$d" rot 2-back
    sb_cfg "$d/pg-rot-config-after.json"; pg_case_end "$d" rot
    # 8: successful drops are unchanged (cell, dock)
    sb_setup ok-cell; sb_run ok-cell "$AX" "$AY" "$CELL11_X" "$CELL11_Y" 7; sb_end ok-cell
    sb_setup ok-dock; sb_run ok-dock "$AX" "$AY" "${DOCK_X:-$AX}" "${DOCK_Y:-$AY}" 7; sb_end ok-dock
    # #221 / PR #223: hit areas while lifted, neighbour tap, long diagonal, drags across a widget host
    sb_lift
    sb_setup diag; sb_run diag "$AX" "$AY" "$CELL33_X" "$CELL33_Y" 7; sb_end diag
    sb_cards
  done
  sh_ "settings put system user_rotation $rot"; pause; anr "$d"
  # #214 regressions with animations on: rotate mid-drag writes nothing; plain drop commits (pager.json, page 2)
  W=${size%x*}; H=${size#*x}
  read -r W H <<< "$($CHECK screen "$d/pg-geo-base-0-edit.xml" 2>/dev/null || echo "$W $H")"
  pg_dragrot; pg_plaindrop
  sh_ "settings put global animator_duration_scale 0; settings put system show_touches 0"
  sh_ "wm size $size; wm density $dens; settings put system user_rotation $rot"
  dnd_reset "$d"; home
}

# --- dev8 edge drag-and-drop pass (test-plan-screen-matrix §4 "Manual edge drag-and-drop"; scored off-CI).
# Fixture edge.json: page 1 "Drag" (id home) holds Edge C (1,1) and Edge D (2,3), so every edge/corner cell of the
# 4x5 grid is empty. Per orientation: one geo cold start -> scripts/screen_matrix_drop.py edge -> sb-edge-<o>.txt, then
# per case a cold start, Edit layout, one `input motionevent` drag of Edge C, UP, after-dump, config before/after,
# crash/ANR (pg-<case>-crash.txt; anr.txt holds every ANR dialog title, System UI vs ours). Gutter and outer cases
# are screenrecorded (sb-<case>.mp4) so the return is visible. Animations on (scale 1), as a user has them.
edge_probe() {
  local d=$1 dens=$2 size=$3 rot=$4 shape=$5 flip rtl=0 o r cs fx fy kind col row
  flip=$((1 - rot)); { [[ $id == rtl-* ]] || echo "$shape" | grep -qi rtl; } && rtl=1
  echo "== edge probe ($id) rtl=$rtl" >> "$d/run.txt"
  : > "$d/pager-cases.txt"; : > "$d/pager-hashes.txt"; : > "$d/edge-cases.txt"
  sh_ "settings put system user_rotation $rot; settings put global animator_duration_scale 1; settings put system show_touches 1"
  for o in base flip; do
    if [ "$o" = flip ]; then
      [[ $id =~ $EDGE_FLIP_RE ]] || continue
      r=$flip
    else
      r=$rot
    fi
    sh_ "settings put system user_rotation $r"; pause; anr "$d"
    pg_case_start "$d" "egeo-$o"; pg_seed "$d" edge.json
    dump "$d" .sbchk
    grep -q 'content-desc="Drag, Pages"' "$d/.sbchk.xml" || { echo "edge geo-$o: not on Drag, goto" >> "$d/run.txt"; pg_goto "$d" Drag; }
    rm -f "$d"/.sbchk.*
    dnd_edit "$d" "$dens" || echo "edge geo-$o: no edit mode" >> "$d/run.txt"
    pg_snap "$d" "egeo-$o" 0-edit; pg_case_end "$d" "egeo-$o"
    $DROPH edge "$d/pg-egeo-$o-0-edit.xml" "$d/pg-egeo-$o-0-edit.png" "$rtl" "$dens" > "$d/sb-edge-$o.txt" 2>> "$d/run.txt" || true
    CX=$(sed -n 's/^CX=//p' "$d/sb-edge-$o.txt"); CY=$(sed -n 's/^CY=//p' "$d/sb-edge-$o.txt")
    W=$(sed -n 's/^W=//p' "$d/sb-edge-$o.txt"); H=$(sed -n 's/^H=//p' "$d/sb-edge-$o.txt")
    if [ -z "$CX" ] || [ -z "$CY" ]; then echo "edge geo-$o: targets failed" >> "$d/run.txt"; continue; fi
    RECSZ="$(( ${W:-1080} / 2 / 2 * 2 ))x$(( ${H:-1920} / 2 / 2 * 2 ))"
    while read -r _ cs fx fy kind col row; do
      cs="$o-$cs"
      echo "$cs $kind $col $row $fx $fy rot=$r" >> "$d/edge-cases.txt"
      sb_setup "$cs" edge.json
      # EDGE_REC_ALL=1 (#221 / PR #223): screenrecord the cell cases too (same gesture as sb_drag_cmd), so the
      # lifted tile's visibility next to the window edges can be scored from the video
      if [ "$kind" = cell ] && [ "${EDGE_REC_ALL:-1}" != 1 ]; then
        echo "drag $CX $CY -> $fx $fy" > "$d/sb-$cs-times.txt"
        sh_ "$(sb_drag_cmd "$CX" "$CY" "$fx" "$fy")" >> "$d/sb-$cs-times.txt" 2>&1
      else
        sb_run "$cs" "$CX" "$CY" "$fx" "$fy" 7
      fi
      sb_end "$cs"
    done < <(grep '^CASE ' "$d/sb-edge-$o.txt")
  done
  sh_ "settings put global animator_duration_scale 0; settings put system show_touches 0"
  sh_ "wm size $size; wm density $dens; settings put system user_rotation $rot"
  dnd_reset "$d"; home
}

# --- DnD / pager probe (scripts/screen_matrix_dnd.py). Each case starts from the seeded fixture.
dnd_reset() {
  sh_ am force-stop "$PKG"
  sh_ "run-as $PKG sh -c 'cp /data/local/tmp/sm-launcher.json files/config/source/launcher.json'"
  sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1; pause 3; anr "$1"
}
# Enter edit mode the way a user does without dragging: long-press empty grid → Edit layout.
dnd_edit() {
  local d=$1 p x y
  dump "$d" .dnde
  p=$($CHECK find-empty "$d/.dnde.xml" "$2") || { rm -f "$d"/.dnde.*; return 1; }
  read -r x y <<< "$p"; hold "$x" "$y"; pause 2; dump "$d" .dnde
  tap "$d/.dnde.xml" text '^Edit layout$' || { rm -f "$d"/.dnde.*; sh_ input keyevent BACK; return 1; }
  pause 2; rm -f "$d"/.dnde.*
}
# drag x1 y1 x2 y2: DOWN, hold past the long-press timeout, 8 MOVE steps, settle, UP (input motionevent, API 33+).
dnd_drag() {
  local cmd i mx my
  cmd="input motionevent DOWN $1 $2; sleep 1.6"
  for i in 1 2 3 4 5 6 7 8; do
    mx=$(( $1 + ($3 - $1) * i / 8 )); my=$(( $2 + ($4 - $2) * i / 8 ))
    cmd="$cmd; input motionevent MOVE $mx $my"
  done
  sh_ "$cmd; sleep 0.5; input motionevent UP $3 $4"
}
dnd_probe() {
  local d=$1 dens=$2 cid how x1 y1 x2 y2
  echo "== dnd probe ($id)" >> "$d/run.txt"
  dnd_reset "$d"
  dnd_edit "$d" "$dens" || echo "dnd: could not enter edit mode for the plan dump" >> "$d/run.txt"
  dump "$d" dnd-edit
  $DND plan "$d" "$dens" >> "$d/run.txt" 2>&1 || { echo "dnd plan failed" >> "$d/run.txt"; dnd_reset "$d"; return; }
  while IFS=$'\t' read -r cid how x1 y1 x2 y2; do
    case $cid in id|'') continue;; esac
    echo "dnd $cid $how $x1,$y1 -> $x2,$y2" >> "$d/run.txt"
    [ "$cid" = pg-fwd ] || dnd_reset "$d"
    sh_ logcat -b all -c > /dev/null 2>&1
    case $how in
      drag)
        if dnd_edit "$d" "$dens"; then dnd_drag "$x1" "$y1" "$x2" "$y2"; else echo "dnd $cid: no edit mode" >> "$d/run.txt"; fi
        pause 3;;   # 750 ms debounce + flush
      hold) hold "$x1" "$y1"; pause 2;;
      swipe) sh_ input swipe "$x1" "$y1" "$x2" "$y2" 300; pause 2;;
    esac
    dump "$d" "dnd-$cid-after"
    sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/dnd-$cid-config.json" 2> /dev/null || true
    sh_ dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 -E 'topResumedActivity|mResumedActivity' > "$d/dnd-$cid-top.txt" || true
    { sh_ logcat -d -b crash; sh_ logcat -d -b main -b system | grep -E "ANR in $PKG|FATAL EXCEPTION.*$PKG"; } > "$d/dnd-$cid-crash.txt" 2>&1
    grep -q "isn't responding\|isn&apos;t responding" "$d/dnd-$cid-after.xml" 2>/dev/null && echo "ANR dialog in dump" >> "$d/dnd-$cid-crash.txt"
    [ "$how" = hold ] && { sh_ input keyevent BACK; pause 1; }
  done < "$d/dnd-cases.tsv"
  $DND score "$d" >> "$d/run.txt" 2>&1 || echo "dnd score non-zero" >> "$d/run.txt"
  dnd_reset "$d"; home
}

# Live tap-routing probe: plan from home1.xml, long-press/tap each target, score.
# Artifacts: tap-routing-plan.json, tap-*.xml, tap-routing.md / .json
tap_routing_probe() {
  local d=$1 dens=$2 tid how x y expect line
  [ -s "$d/home1.xml" ] || { echo "tap-routing: no home1.xml" >> "$d/run.txt"; return; }
  echo "== tap-routing probe ($id)" >> "$d/run.txt"
  $TAP_ROUTING plan "$d" "$dens" >> "$d/run.txt" 2>&1 || {
    echo "tap-routing plan failed" >> "$d/run.txt"; return
  }
  [ -s "$d/tap-routing-taps.tsv" ] || return
  # Ensure launcher is foreground before probing
  home
  sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1 || true
  pause 2
  while IFS=$'\t' read -r tid kind how x y expect; do
    case $tid in id|'') continue;; esac
    echo "tap-routing $tid $how @ $x,$y expect=$expect" >> "$d/run.txt"
    if [ "$how" = longpress ]; then
      hold "$x" "$y"
    else
      sh_ input tap "$x" "$y"
    fi
    pause 2
    anr "$d"
    dump "$d" "tap-$tid"
    sh_ dumpsys activity activities 2>/dev/null | tr -d '\r' \
      | grep -m1 -E 'topResumedActivity|mResumedActivity' > "$d/tap-$tid-top.txt" || true
    # Dismiss sheet / return home for the next tap
    sh_ input keyevent BACK; pause 1
    home
    # Re-assert launcher (a launch may have left another app on top)
    if ! grep -q "$PKG" "$d/tap-$tid-top.txt" 2>/dev/null; then
      sh_ am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1 || true
      pause 2
    fi
  done < "$d/tap-routing-taps.tsv"
  $TAP_ROUTING score "$d" >> "$d/run.txt" 2>&1 || echo "tap-routing score non-zero" >> "$d/run.txt"
  grep -m1 '^# Tap-routing' "$d/tap-routing.md" 2>/dev/null || true
}

# shellcheck source=scripts/screen-matrix-hinge.sh
. "$HERE/screen-matrix-hinge.sh"

A wait-for-device < /dev/null
wait_boot_idle
A install -r -g "$APK" < /dev/null | tee "$OUT/install.txt"
grep -q '^Success' "$OUT/install.txt" || { echo "install failed" >&2; exit 1; }
# Claim HOME and disable stock Pixel/Nexus Launcher before any point (API 35 tablet ANR storm).
sh_ "cmd package set-home-activity $PKG/.MainActivity > /dev/null 2>&1" || true
disable_stock_launchers
[ "$SHARD" = hinge ] && hinge_env
echo "stock_launcher_disabled=${MATRIX_DISABLE_STOCK_LAUNCHER:-0} (force-stop only when 0)" >> "$OUT/harness-setup.txt"
sh_ "pm list packages -d" >> "$OUT/harness-setup.txt" 2>&1 || true

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
[ "$SHARD" = hinge ] && set_posture "$OUT" open
sh_ "wm size reset; wm density reset; settings put system font_scale 1.0; cmd uimode night no; settings put system user_rotation 0; settings put system system_locales en-US"
sh_ "settings put global debug.force_rtl 0" >/dev/null 2>&1 || true
sh_ "cmd locale set-app-locales $PKG --locales en-US" >/dev/null 2>&1 || true
sh_ "cmd overlay disable --user 0 com.android.internal.display.cutout.emulation.tall" >/dev/null 2>&1 || true

[ "$n" -gt 0 ] || { echo "no points matched (shard='$SHARD' points='$POINTS')" >&2; exit 2; }
$CHECK summarize "$OUT"
