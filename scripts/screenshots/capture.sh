#!/usr/bin/env bash
# Capture Natune app screenshots on a running emulator via adb + uiautomator.
# Never hard-fails: every screen is best-effort, failures are logged and the
# run continues so the artifact still carries what worked + the dumps.
set -u
cd "$(dirname "$0")/../.."
mkdir -p shots dumps
exec > >(tee capture.log) 2>&1

PKG=moe.rukamori.archivetune
U="python3 scripts/screenshots/uiauto.py"

log() { echo "[$(date +%H:%M:%S)] $*"; }
dump() { $U dump "dumps/$1.xml" >/dev/null 2>&1 || true; }
listd() { $U list > "dumps/$1.txt" 2>&1 || true; }
shot() { $U shot "shots/$1.png"; log "shot $1: $(stat -c%s "shots/$1.png" 2>/dev/null) bytes"; }
try() { "$@" >/dev/null 2>&1 || true; }
fg() {
  # Make sure our app is foreground; relaunch if a stray tap/background left it.
  local p
  p=$($U pkg 2>/dev/null || echo "?")
  if [ "$p" != "$PKG" ]; then
    log "foreground=$p — relaunching app"
    adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
    sleep 5
  fi
}
dismiss() {
  # Best-effort close of dialogs/sheets that may block a screen.
  for label in "Later" "Not now" "Dismiss" "Got it" "OK" "Cancel" "Close"; do
    $U tap "$label" >/dev/null 2>&1 && log "dismissed: $label" && sleep 1
  done
}

log "== boot wait =="
adb wait-for-device
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
sleep 5
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb shell input keyevent 82 || true
adb shell cmd uimode night yes || true

log "== install =="
adb install -r apk/*.apk || { log "INSTALL FAILED"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT || true

log "== launch =="
adb shell am start -n $PKG/.MainActivity || adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 10

log "== onboarding =="
for i in $(seq 1 14); do
  fg
  dump "onboarding_$i"
  listd "onboarding_$i"
  if   $U tap "Let’s go" >/dev/null 2>&1; then log "tap Let's go";
  elif $U tap "Let's go" >/dev/null 2>&1;   then log "tap Let's go";
  elif $U tap "Skip for now" >/dev/null 2>&1; then log "tap Skip for now";
  elif $U tap "Next" >/dev/null 2>&1;       then log "tap Next";
  elif $U tap "Start listening" >/dev/null 2>&1; then log "tap Start listening"; sleep 4; break;
  elif $U tap "While using the app" >/dev/null 2>&1; then log "tap While using";
  elif $U tap "ALLOW" >/dev/null 2>&1; then log "tap ALLOW";
  else
    log "no onboarding button (iter $i) — scrolling for footer"
    $U swipe 540 1700 540 600 400 || true
  fi
  sleep 3
  # exit early once the home tabs show up
  if $U list 2>/dev/null | grep -q "d='Settings'"; then log "home reached"; break; fi
done

# Do not drive the rest of the tour until the main UI is really up.
if ! $U wait "Settings" 15 >/dev/null 2>&1; then
  log "ABORT: never reached home UI"
  dump abort; listd abort
  shot 00_abort_state
  ls -la shots/
  exit 0
fi

log "== home =="
sleep 14
dismiss
dump home; listd home
shot 01_home

log "== search =="
fg
try $U tap "Search"; sleep 3
# tap the search field (Compose EditText) and type
if ! $U tapcls "EditText" >/dev/null 2>&1; then try $U tapf 0.5 0.08; fi
sleep 1
$U text "Daft Punk"; sleep 1
$U key 66
if ! $U wait "Albums" 12 >/dev/null 2>&1; then
  log "no result chips yet — retrying submit"
  $U key 84 || true
  sleep 8
fi
sleep 6
dump search; listd search
shot 02_search_results

log "== album =="
fg
if ! $U tap "Albums" >/dev/null 2>&1; then try $U tapi "album"; fi
sleep 8
dump albums; listd albums
shot 03_search_albums
if ! $U tap "Random Access Memories" >/dev/null 2>&1; then try $U tapf 0.5 0.35; fi
sleep 7
dump album; listd album
shot 04_album

log "== playback =="
fg
if ! $U tap "Play" >/dev/null 2>&1; then
  if ! $U tap "Shuffle" >/dev/null 2>&1; then try $U tapf 0.5 0.5; fi
fi
sleep 12
dump playing; listd playing
# expand the mini player (bar just above the bottom nav) — only when playback UI exists
if $U list 2>/dev/null | grep -qE "d='(Pause|Lyrics|Queue|Collapse)'"; then
  for frac in 0.91 0.87 0.94; do
    $U tapf 0.5 $frac; sleep 3
    if $U wait "Lyrics" 4 >/dev/null 2>&1; then log "player expanded at $frac"; break; fi
  done
else
  log "no playback UI detected — skipping expand"
fi
sleep 3
dump player; listd player
shot 05_player

log "== lyrics =="
try $U tap "Lyrics"; sleep 5
dump lyrics; listd lyrics
shot 06_lyrics
$U key 4; sleep 2

log "== equalizer (player menu) =="
if $U tap "More options" >/dev/null 2>&1 || $U tap "More" >/dev/null 2>&1 || $U tapi "menu" >/dev/null 2>&1; then
  sleep 2
  try $U tap "Equalizer"; sleep 4
  dump equalizer; listd equalizer
  shot 07_equalizer
  $U key 4; sleep 1; $U key 4; sleep 2
else
  log "no player menu found"
fi
# collapse player back to mini
$U key 4; sleep 2

log "== library =="
fg
try $U tap "Library"; sleep 4
dump library; listd library
shot 08_library

log "== settings =="
fg
try $U tap "Home"; sleep 3
if ! $U tap "Settings" >/dev/null 2>&1; then
  # gear sits top-right on the home header
  try $U tapf 0.9 0.055
  sleep 1
  try $U tap "Settings"
fi
sleep 4
dump settings; listd settings
shot 09_settings

log "== playback settings =="
$U swipe 540 1900 540 900 400; sleep 1
if ! $U tap "Playback" >/dev/null 2>&1; then
  $U swipe 540 1900 540 900 400; sleep 1
  try $U tap "Playback"
fi
sleep 4
dump playback_settings; listd playback_settings
shot 10_playback_settings
$U swipe 540 1900 540 700 500; sleep 1
shot 10b_playback_settings_scrolled
$U key 4; sleep 2

log "== apple music experience (optional) =="
# back on the settings list; scroll to top so Appearance is visible
$U swipe 540 700 540 1900 500; sleep 1
$U swipe 540 700 540 1900 500; sleep 1
if ! $U tap "Appearance" >/dev/null 2>&1; then
  $U swipe 540 1900 540 900 400; sleep 1
  try $U tap "Appearance"
fi
sleep 3
dump appearance; listd appearance
if $U tap "Apple Music Experience" >/dev/null 2>&1; then
  sleep 3
  $U key 4; sleep 2
  try $U tap "Library"; sleep 2
  try $U tap "Home"; sleep 2
  # reopen player if something is still in the queue
  $U tapf 0.5 0.91; sleep 4
  dump player_am; listd player_am
  shot 11_player_apple_music
fi
$U key 4 || true

log "== done =="
adb logcat -d -t 400 2>/dev/null | grep -iE "playback|error|exception|MusicService" | tail -60 || true
ls -la shots/
