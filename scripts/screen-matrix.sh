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
TAP_ROUTING="python3 $HERE/screen_matrix_tap_routing.py"
DND="python3 $HERE/screen_matrix_dnd.py"
# Live DnD / pager probe (#187, PR #210): MATRIX_PROBES=dnd enables it on these points (launcher.json fixture only).
MATRIX_PROBES=${MATRIX_PROBES:-}
DND_POINTS=${DND_POINTS:-'^(rtl-.*|t7-land-v1|t7-port-v1|phone-land-v1|p411-a51-v1)$'}
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
  sh_ "wm size $size; wm density $dens; settings put system font_scale $fs; cmd uimode night $night"
  sh_ "settings put system accelerometer_rotation 0; settings put system user_rotation $rot"
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
  if longpress "$d/drawer.xml" text '^(Calendar|Camera|Clock|Contacts|Settings|التقويم|الكاميرا|الساعة|جهات الاتصال|الإعدادات)$'; then
    pause 2; dump "$d" drawer-lp
    if ! grep -q 'App info' "$d/drawer-lp.xml"; then
      retried "$d" F5b; sh_ input keyevent BACK; pause 1
      # the drawer may have closed (an app opened); reopen it the same way before the second try
      if ! { dump "$d" .dr; grep -q 'Search apps' "$d/.dr.xml"; }; then
        home; sh_ input swipe "$cx" $((sh * 70 / 100)) "$cx" $((sh * 25 / 100)) 300; pause
      fi
      rm -f "$d"/.dr.*
      longpress "$d/drawer.xml" text '^(Calendar|Camera|Clock|Contacts|Settings|التقويم|الكاميرا|الساعة|جهات الاتصال|الإعدادات)$'; pause 2; dump "$d" drawer-lp
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

A wait-for-device < /dev/null
wait_boot_idle
A install -r -g "$APK" < /dev/null | tee "$OUT/install.txt"
grep -q '^Success' "$OUT/install.txt" || { echo "install failed" >&2; exit 1; }
# Claim HOME and disable stock Pixel/Nexus Launcher before any point (API 35 tablet ANR storm).
sh_ "cmd package set-home-activity $PKG/.MainActivity > /dev/null 2>&1" || true
disable_stock_launchers
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
sh_ "wm size reset; wm density reset; settings put system font_scale 1.0; cmd uimode night no; settings put system user_rotation 0; settings put system system_locales en-US"
sh_ "settings put global debug.force_rtl 0" >/dev/null 2>&1 || true
sh_ "cmd locale set-app-locales $PKG --locales en-US" >/dev/null 2>&1 || true
sh_ "cmd overlay disable --user 0 com.android.internal.display.cutout.emulation.tall" >/dev/null 2>&1 || true

[ "$n" -gt 0 ] || { echo "no points matched (shard='$SHARD' points='$POINTS')" >&2; exit 2; }
$CHECK summarize "$OUT"
