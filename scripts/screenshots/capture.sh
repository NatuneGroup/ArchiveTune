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
handle_anr() {
  # ANR dialogs steal the foreground as package "android". If the stuck app
  # is ours, choose Wait; if it is another process (e.g. the launcher, which
  # can wedge for the whole boot), Close app so our window can come up.
  local title
  title=$($U list 2>/dev/null | grep "isn't responding" | head -1)
  [ -z "$title" ] && return 1
  log "ANR: $title"
  case "$title" in
    *Natune*|*archivetune*) $U tap "Wait" >/dev/null 2>&1 || true ;;
    *)                      $U tap "Close app" >/dev/null 2>&1 || true ;;
  esac
  sleep 3
}
fg() {
  # ANR/crash dialogs steal the foreground as package "android".
  handle_anr
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
close_dialog() {
  # "Playback confirmation required" is a bottom sheet with no a11y nodes.
  # Its Close button is the left button in the first action row, but the
  # sheet height varies — tap both observed positions.
  $U tapf 0.385 0.596; sleep 1
  $U tapf 0.385 0.715; sleep 1
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
for i in 1 2 3; do handle_anr || break; done

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
  elif $U list 2>/dev/null | grep -q "isn't responding"; then handle_anr;
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

log "== player =="
fg
# hero card opens an album/playlist page; Play pill sits at ~ (510,1330)
$U tapf 0.39 0.19; sleep 8
dump album; listd album
shot 02a_opened_page
$U tapf 0.47 0.555; sleep 16
dump playing; listd playing
$U key 4; sleep 1
close_dialog
shot 04b_post_play
# expand the mini player — bar sits ~y 2150-2280 over the nav pill
for frac in 0.915 0.88 0.94; do
  $U tapf 0.5 $frac; sleep 4
done
dump player; listd player
shot 05_player

log "== lyrics =="
# expanded player bottom row: Queue / like / Lyrics pills at ~ y 2290
$U tapf 0.62 0.955; sleep 5
shot 06_lyrics
$U tapf 0.75 0.955; sleep 4
shot 06b_lyrics_alt
$U key 4; sleep 2

log "== library =="
fg
# single close-tap in case the confirm sheet is up
$U tapf 0.385 0.715; sleep 1
try $U tap "Library"; sleep 5
dump library; listd library
shot 08_library

log "== settings =="
fg
try $U tap "Home"; sleep 4
# gear sits top-right of the home header at ~ (975, 225)
$U tapf 0.9 0.094; sleep 5
dump settings; listd settings
shot 09_settings
$U swipe 540 1700 540 700 400; sleep 2
shot 09b_settings_scrolled
$U tapf 0.5 0.55; sleep 4
dump settings_sub; listd settings_sub
shot 10_settings_subpage
$U key 4; sleep 2

log "== done =="
adb logcat -d -t 400 2>/dev/null | grep -iE "playback|error|exception|MusicService" | tail -60 || true
ls -la shots/
