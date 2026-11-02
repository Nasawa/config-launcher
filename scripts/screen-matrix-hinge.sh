# shellcheck shell=bash
# Real-hinge probes for the opt-in `hinge` shard (PR #219 QA). Sourced by screen-matrix.sh; uses its A, sh_, pause,
# dump, hold, home, dnd_reset, dnd_edit, CHECK, PKG, OUT, HERE. Only hinge-shard points call into this file.
#
# Posture is driven through the emulator console (adb emu posture 1|2|3 = closed|half-opened|opened on the
# pixel_fold AVD: hw.sensor.posture_list=1,2,3, hinge 0-30/30-150/150-180) plus the hinge-angle sensor as a backup.
# The console answers KO on a bad command while adb still exits 0, so every change is recorded with the device_state
# that resulted, and what the app saw is read back through WindowInfoTracker by QaHingeProbeTest (hinge*.txt).
HINGE_PY="python3 $HERE/screen_matrix_hinge.py"
HINGE_TEST_APK=${HINGE_TEST_APK:-}
HINGE_RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner

emu_() { timeout 30 adb emu "$@" < /dev/null 2>&1 | tr -d '\r'; }
json_hashes() { sh_ "run-as $PKG sh -c 'cd files 2>/dev/null && find . -type f \\( -name \"*.json\" -o -name \"*.tmp\" \\) -exec sha256sum {} +'" 2> /dev/null | tr -d '\r' | sort -k2; }

hinge_env() {
  {
    echo "## adb emu help"; emu_ help
    echo "## adb emu help posture"; emu_ help posture
    echo "## adb emu posture (no arg)"; emu_ posture
    echo "## cmd device_state print-states"; sh_ cmd device_state print-states
    echo "## cmd device_state state"; sh_ cmd device_state state
    echo "## dumpsys device_state"; sh_ dumpsys device_state
    echo "## sensors (hinge/posture)"; sh_ dumpsys sensorservice | grep -iE 'hinge|posture|angle'
    echo "## wm size / density"; sh_ wm size; sh_ wm density
    echo "## displays"; sh_ dumpsys display | grep -E 'mBaseDisplayInfo|DisplayDeviceInfo|mViewports' | head -20
    echo "## props"; sh_ getprop | grep -iE 'qemu|fold|hinge|posture|device_state|display' | head -80
    echo "## window extensions"; sh_ getprop persist.wm.extensions.enabled; sh_ pm list libraries | grep -i extension
  } > "$OUT/hinge-env.txt" 2>&1
  find "$HOME/.android" "${ANDROID_AVD_HOME:-/nonexistent}" -name config.ini -path '*avd*' 2> /dev/null \
    | while read -r f; do echo "## $f"; cat "$f"; done > "$OUT/avd-config.txt"
  if [ -n "$HINGE_TEST_APK" ] && [ -f "$HINGE_TEST_APK" ]; then
    A install -r -t "$HINGE_TEST_APK" < /dev/null > "$OUT/test-install.txt" 2>&1
  else
    echo "no HINGE_TEST_APK ($HINGE_TEST_APK)" > "$OUT/test-install.txt"
  fi
}

# set_posture <dir> closed|half|open
set_posture() {
  local d=$1 n a r
  case $2 in closed) n=1; a=0;; half) n=2; a=90;; open) n=3; a=180;; *) return 1;; esac
  r="posture $n: $(emu_ posture $n | tr '\n' ' ')"
  r="$r | hinge-angle0 $a: $(emu_ sensor set hinge-angle0 $a | tr '\n' ' ')"
  pause 3
  echo "$(date +%T) set $2 -> $r | device_state: $(sh_ cmd device_state state 2>&1 | tr -d '\r' | tr '\n' ' ')" >> "$d/hinge-cmds.txt"
}

# hinge_read <dir> <name>: WindowInfoTracker as MainActivity sees it (restarts the app process).
hinge_read() {
  sh_ logcat -c > /dev/null 2>&1
  sh_ am instrument -w -r -e class "$PKG.QaHingeProbeTest" "$HINGE_RUNNER" > "$1/$2-instr.txt" 2>&1
  sh_ logcat -d -s QA-HINGE:I | tr -d '\r' | sed -n 's/.*QA-HINGE: //p' > "$1/$2.txt"
  [ -s "$1/$2.txt" ] || grep -o 'qa_hinge=.*' "$1/$2-instr.txt" > "$1/$2.txt"
}

# hinge_setup <dir> <shape>: native panel, posture + rotation from shape, read the feature, record it in meta.
# Book wants a VERTICAL fold, tabletop a HORIZONTAL one; if the tsv rotation gives the other one, flip once.
hinge_setup() {
  local d=$1 shape=$2 posture want=''
  sh_ "wm size reset; wm density reset"
  case $shape in
    *hinge:book*) posture=half; want=VERTICAL;;
    *hinge:tabletop*) posture=half; want=HORIZONTAL;;
    *hinge:cover*) posture=closed;;
    *) posture=open;;
  esac
  set_posture "$d" "$posture"
  sh_ "settings put system user_rotation $rot"; pause 3
  hinge_read "$d" hinge
  if [ -n "$want" ] && ! grep -q "orientation=$want" "$d/hinge.txt"; then
    echo "rotation $rot gave $(grep -m1 -o 'orientation=[A-Z]*' "$d/hinge.txt" || echo 'no fold'); flipping" >> "$d/hinge-cmds.txt"
    rot=$((1 - rot)); sh_ "settings put system user_rotation $rot"; pause 3
    hinge_read "$d" hinge
  fi
  { echo "hinge_posture=$posture"; echo "hinge_rotation=$rot"; $HINGE_PY meta "$d/hinge.txt" "$shape"; } >> "$d/meta.txt"
  sh_ "wm size; wm density" > "$d/hinge-wm.txt" 2>&1
}

# Drag with a posture change while the finger is still down. <dir> <tag> <from> <to|none> <rot_from> <rot_to>
hinge_drag() {
  local d=$1 tag=$2 from=$3 to=$4 r1=$5 r2=$6 p x1 y1 x2 y2 cmd i mx my
  dnd_reset "$d"
  set_posture "$d" "$from"; sh_ "settings put system user_rotation $r1"; pause 3
  dnd_edit "$d" "$dens" || echo "hinge-drag $tag: no edit mode" >> "$d/run.txt"
  dump "$d" "md-$tag-before"
  p=$($HINGE_PY drag-plan "$d/md-$tag-before.xml" "$d/meta.txt") || { echo "hinge-drag $tag: no plan" >> "$d/run.txt"; return; }
  read -r x1 y1 x2 y2 <<< "$p"
  echo "md-$tag $from->$to rot $r1->$r2 drag $x1,$y1 -> $x2,$y2" >> "$d/md-plan.txt"
  json_hashes > "$d/md-$tag-hash-before.txt"
  cmd="input motionevent DOWN $x1 $y1; sleep 1.6"
  for i in 1 2 3 4; do mx=$(( x1 + (x2 - x1) * i / 8 )); my=$(( y1 + (y2 - y1) * i / 8 )); cmd="$cmd; input motionevent MOVE $mx $my"; done
  cmd="$cmd; sleep 7"
  for i in 5 6 7 8; do mx=$(( x1 + (x2 - x1) * i / 8 )); my=$(( y1 + (y2 - y1) * i / 8 )); cmd="$cmd; input motionevent MOVE $mx $my"; done
  cmd="$cmd; sleep 0.5; input motionevent UP $x2 $y2"
  sh_ "$cmd" &
  local bg=$!
  sleep 4
  if [ "$to" != none ]; then
    sh_ screencap -p /sdcard/md.png; A pull /sdcard/md.png "$d/md-$tag-mid.png" > /dev/null 2>&1 < /dev/null
    set_posture "$d" "$to"
    [ "$r2" = "$r1" ] || sh_ "settings put system user_rotation $r2"
  fi
  wait "$bg"
  pause 3   # 750 ms debounce + flush
  json_hashes > "$d/md-$tag-hash-after.txt"
  sh_ "run-as $PKG cat files/config/source/launcher.json" > "$d/md-$tag-config.json" 2> /dev/null || true
  dump "$d" "md-$tag-after"
  set_posture "$d" "$from"; sh_ "settings put system user_rotation $r1"; pause 3
  dump "$d" "md-$tag-restored"
  { sh_ logcat -d -b crash; sh_ logcat -d -b main -b system | grep -E "ANR in $PKG|FATAL EXCEPTION.*$PKG"; } > "$d/md-$tag-crash.txt" 2>&1
  sh_ input keyevent BACK; pause 1
}

# Focus walk: TAB and D-pad from the first focus; record the focused node after each key.
focus_walk() {
  local d=$1 key=$2 n=$3 i
  home; sh_ input keyevent DPAD_DOWN; pause 1    # leave touch mode
  for i in $(seq 1 "$n"); do
    sh_ input keyevent "$key"; sleep 0.5
    sh_ uiautomator dump /sdcard/f.xml > /dev/null 2>&1
    sh_ cat /sdcard/f.xml > "$d/.f.xml" 2> /dev/null
    echo "$key $i $($HINGE_PY focused "$d/.f.xml")" >> "$d/focus-walk.txt"
  done
  rm -f "$d/.f.xml"
}

hinge_probe() {
  local d=$1 shape=$2 p x y yt yb rb rt
  echo "== hinge probe ($id)" >> "$d/run.txt"
  rb=$(sed -n 's/^hinge_rotation=//p' "$d/meta.txt" | tail -1)
  case $shape in
    *hinge:book*)
      # Wallpaper (end) half: swipe up → drawer, long-press → home menu, no semantics, no focus.
      if p=$($HINGE_PY wall "$d/meta.txt"); then
        read -r x y yt yb <<< "$p"
        home; sh_ input swipe "$x" "$yb" "$x" "$yt" 300; pause 2; dump "$d" wall-swipe
        sh_ input keyevent BACK; pause 1; home
        hold "$x" "$y"; pause 2; dump "$d" wall-lp
        sh_ input keyevent BACK; pause 1; home
        sh_ input tap "$x" "$y"; pause 2; dump "$d" wall-tap
        home
      else
        echo "no wallpaper half in meta" >> "$d/run.txt"
      fi
      focus_walk "$d" TAB 30
      focus_walk "$d" DPAD_RIGHT 12
      [ -n "${seed_ok:-}" ] && [ "$seed_ok" -eq 1 ] && [ "$fixture" = launcher.json ] || return 0
      # Page kept through flat → book → tabletop → flat, config untouched.
      rt=$((1 - rb))
      dnd_reset "$d"; set_posture "$d" open; home
      dump "$d" .pk
      if tap "$d/.pk.xml" desc ', Pages$'; then pause; dump "$d" .pk
        tap "$d/.pk.xml" desc '^Second, page [2٢] of [2٢]' && pause
      fi
      rm -f "$d"/.pk.*
      json_hashes > "$d/pk-hash-before.txt"
      dump "$d" pk-0-flat
      set_posture "$d" half; dump "$d" pk-1-book
      sh_ "settings put system user_rotation $rt"; pause 3; dump "$d" pk-2-tabletop
      set_posture "$d" open; sh_ "settings put system user_rotation $rb"; pause 3; dump "$d" pk-3-flat
      json_hashes > "$d/pk-hash-after.txt"
      # Posture change mid-drag (config must stay byte-identical) + a control drag that must write.
      hinge_drag "$d" book-to-flat half open "$rb" "$rb"
      hinge_drag "$d" flat-to-book open half "$rb" "$rb"
      hinge_drag "$d" control-book half none "$rb" "$rb"
      dnd_reset "$d"; set_posture "$d" half; sh_ "settings put system user_rotation $rb"; pause 2
      ;;
    *hinge:tabletop*)
      [ -n "${seed_ok:-}" ] && [ "$seed_ok" -eq 1 ] && [ "$fixture" = launcher.json ] || return 0
      hinge_drag "$d" tabletop-to-flat half open "$rb" "$rb"
      hinge_drag "$d" control-tabletop half none "$rb" "$rb"
      dnd_reset "$d"; set_posture "$d" half; sh_ "settings put system user_rotation $rb"; pause 2
      ;;
  esac
  hinge_read "$d" hinge-end
  $HINGE_PY score "$d" >> "$d/run.txt" 2>&1 || echo "hinge score non-zero" >> "$d/run.txt"
  home
}
