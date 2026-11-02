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
  # ANR/crash dialogs steal the foreground as package "android". Always pick
  # Wait — never Close app — and let the dialog clear itself.
  if $U tap "Wait" >/dev/null 2>&1; then log "dismissed system dialog via Wait"; sleep 3; fi
  local p
  p=$($U pkg 2>/dev/null || echo "?")
  if [ "$p" != "$PKG" ]; then
    log "foreground=$p — relaunching app"
    adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
    sleep 6
    $U tap "Wait" >/dev/null 2>&1 || true
  fi
  # A restart can land back on a restored onboarding page — walk it again.
  if ! on_home && $U list 2>/dev/null | grep -qE "t='(Welcome to Natune|Review permissions|Stay connected|Sync your listening)'"; then
    log "onboarding re-shown — walking it"
    walk_onboarding
  fi
}
on_home() {
  # Real gate: our app must be foreground AND show a bottom-nav "Library"
  # node — the Pixel Launcher also has "Settings"/"Home" labels.
  [ "$($U pkg 2>/dev/null || echo '?')" = "$PKG" ] || return 1
  $U list 2>/dev/null | grep -qE "(t|d)='Library'"
}
walk_onboarding() {
  # Compact pass through the four onboarding pages; used by the main loop
  # and again by fg() when a process restart lands back on onboarding.
  for j in $(seq 1 9); do
    if   $U tap "Let’s go" >/dev/null 2>&1;        then log "walk: Let's go";
    elif $U tap "Let's go" >/dev/null 2>&1;         then log "walk: Let's go";
    elif $U tap "Skip for now" >/dev/null 2>&1;     then log "walk: skip login";
    elif $U tap "Next" >/dev/null 2>&1;             then log "walk: Next";
    elif $U tap "Start listening" >/dev/null 2>&1;  then log "walk: start"; sleep 4; break;
    elif $U tap "Wait" >/dev/null 2>&1;             then log "walk: ANR wait";
    else
      if $U list 2>/dev/null | grep -q "this device"; then
        $U tap "Allow" >/dev/null 2>&1 && log "walk: granted perm dialog"
      else
        $U swipe 540 1700 540 600 400 || true
      fi
    fi
    on_home && break
    sleep 2
  done
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
for perm in POST_NOTIFICATIONS RECORD_AUDIO BLUETOOTH_CONNECT READ_MEDIA_AUDIO GET_ACCOUNTS; do
  adb shell pm grant $PKG android.permission.$perm 2>/dev/null || true
done

log "== launch =="
adb shell am start -n $PKG/.MainActivity || adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 12
# boot-time ANR on the launcher/app steals input until dismissed
for i in 1 2 3; do $U tap "Wait" >/dev/null 2>&1 && sleep 3 || break; done

log "== onboarding =="
for i in $(seq 1 16); do
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
  elif $U tap "Wait" >/dev/null 2>&1; then log "tap Wait (ANR)";
  else
    if $U list 2>/dev/null | grep -q "this device"; then
      $U tap "Allow" >/dev/null 2>&1 && log "granted perm dialog"
    else
      log "no onboarding button (iter $i) — scrolling for footer"
      $U swipe 540 1700 540 600 400 || true
    fi
  fi
  sleep 3
  if on_home; then log "home reached"; break; fi
done

# Do not drive the rest of the tour until the main UI is really up.
for i in 1 2 3; do on_home && break; fg; sleep 5; done
if ! on_home; then
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
# search bar pill, measured from a real capture: ~x 60-1010, y 330-460
$U tapf 0.5 0.165; sleep 4
shot 02a_search_input
$U text "Daft Punk"; sleep 1
# tap Gboard's search/go key first (~930,2195) — the definitive submit —
# then fall back to hardware SEARCH/ENTER codes
$U tapf 0.86 0.915; sleep 3
$U key 84 || true; sleep 2
$U key 66 || true
sleep 12
# hide the keyboard so results are unobstructed, then capture
$U key 4; sleep 2
dump search; listd search
shot 02_search_results
$U key 4; sleep 2

log "== album (hero card on home) =="
fg
try $U tap "Home"; sleep 4
# "Thriller — Michael Jackson" hero card: ~x 60-780, y 300-630
$U tapf 0.39 0.19; sleep 8
dump album; listd album
shot 04_album

log "== playback =="
# album track rows sit mid-screen; tap the play area then a track
$U tapf 0.5 0.6; sleep 3
$U tapf 0.5 0.72; sleep 14
dump playing; listd playing
# a "Playback confirmation required" dialog (no a11y nodes) can block the
# UI — its Close button sits at ~ (415, 1715); BACK first, then Close.
$U key 4; sleep 1
$U tapf 0.385 0.715; sleep 2
shot 04b_post_play
# expand the mini player — the bar sits at ~y 2150-2280, over the nav pill
for frac in 0.915 0.88 0.94; do
  $U tapf 0.5 $frac; sleep 4
done
dump player; listd player
shot 05_player

log "== lyrics (tap artwork) =="
# in the expanded player the artwork toggles the lyrics view
$U tapf 0.5 0.4; sleep 5
dump lyrics; listd lyrics
shot 06_lyrics
$U key 4; sleep 2
$U tapf 0.385 0.715; sleep 1
$U key 4; sleep 2

log "== library =="
fg
# the confirmation dialog may still be up — Close it (no a11y nodes)
$U tapf 0.385 0.715; sleep 1
try $U tap "Library"; sleep 5
dump library; listd library
shot 08_library

log "== settings =="
fg
$U tapf 0.385 0.715; sleep 1
try $U tap "Home"; sleep 4
# gear sits top-right of the home header at ~ (975, 225)
$U tapf 0.9 0.094; sleep 5
dump settings; listd settings
shot 09_settings
# scroll the settings list and try the Playback category area
$U swipe 540 1700 540 700 400; sleep 2
shot 09b_settings_scrolled
$U tapf 0.5 0.62; sleep 4
dump playback_settings; listd playback_settings
shot 10_settings_subpage
$U key 4; sleep 2

log "== done =="
adb logcat -d -t 400 2>/dev/null | grep -iE "playback|error|exception|MusicService" | tail -60 || true
ls -la shots/
