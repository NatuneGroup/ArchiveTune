# Android visual and playback QA

Normal debug builds and release signing are unchanged. The optional
`-PqaOptimized=true` property applies R8/resource shrinking to a **debug** build,
retaining its `.debug` application ID and AGP's local debug signing identity.
This reduces cold DEX-verification work in a software-only emulator; it is not a
new release channel or a signing fallback.

## Setup and build

Use JDK 21 and the repository-owned SDK setup:

```sh
bash scripts/setup_android.sh --emulator
./gradlew :app:assembleGmsMobileX86_64Debug -PqaOptimized=true
```

Setup reuses `sdk.dir` when present and creates `ArchiveTuneVisualQA35` with the
render-capable Android 35 AOSP image. ATD images disable drawing by default. The
optional `--emulator-atd` setup flag creates a separate `ArchiveTuneATDVisualQA35`
fallback and enables drawing in the image's preboot configuration. Use that AVD
name in the run command if the standard image's system apps are unstable. Setting
the property after boot is not a durable repair; Android 35's empty-data marker
otherwise skips the image's `data/local.prop`.

## Run

```sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_AVD_HOME="$PWD/.hoplite/android-avd"
export ANDROID_USER_HOME="$PWD/.hoplite/android-user"
"$ANDROID_HOME/emulator/emulator" -avd ArchiveTuneVisualQA35 \
  -no-window -no-boot-anim -no-snapshot -no-audio \
  -gpu swiftshader_indirect -feature -Vulkan \
  -accel off -memory 2048 -cores 2 -camera-back none -camera-front none
```

The software-OpenGL/Vulkan-disabled configuration avoids the graphics-backend
startup crash observed in this sandbox. On a host with working KVM, omit
`-accel off`. A software-only device can take several minutes to boot and render
its first app screen. Run the emulator as a long-lived process and restart it if
the session expires; its installed app and data remain in the QA AVD.

```sh
"$ANDROID_HOME/platform-tools/adb" wait-for-device
"$ANDROID_HOME/platform-tools/adb" install -r \
  app/build/outputs/apk/gmsMobileX86_64/debug/app-gms-mobile-x86_64-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell cmd package compile \
  -m speed -f moe.rukamori.archivetune.debug
"$ANDROID_HOME/platform-tools/adb" shell am start \
  -n moe.rukamori.archivetune.debug/moe.rukamori.archivetune.MainActivity
```

Precompilation succeeded in the sandbox and allowed the app to progress beyond
its previous cold-start DEX-verification ANR. Do not hide ANRs or treat a successful
install/launch command as UI verification: inspect the current screen and check
the actual interactions.

## Evidence boundaries

Use generated local audio/artwork/lyrics for publishable screenshots. Keep AVD
data, debug keystores, account sessions, databases and raw private diagnostics
untracked. Check the exact build's credential fields before sharing an APK.

`-no-audio` disables host playback. The emulator can verify player state, seek,
queue handoff, decoded-PCM observation and UI behavior; it cannot establish
physical-device audio quality, USB/DAC output or authenticated-provider behavior.
Those checks must remain explicitly unverified until there is real evidence.

## Auth-free synthetic playback check

Use a clean QA emulator, not an AVD containing personal accounts. After installing
the exact debug APK above, select its serial explicitly (`adb devices`):

```sh
export PATH="$ANDROID_HOME/platform-tools:$PATH"
APK=app/build/outputs/apk/gmsMobileX86_64/debug/app-gms-mobile-x86_64-debug.apk
python3 scripts/android_qa.py prepare --serial emulator-5554 --apk "$APK"
```

The harness refuses physical devices and verifies that the installed APK's
SHA-256 matches the supplied file. It creates a real, seekable 30-second PCM WAV
and grants only the app's media-read permission. It never inserts account tokens,
alters onboarding preferences or requires an authenticated streaming provider.

Complete the normal onboarding:

1. Continue from Welcome.
2. On Permissions, media access is already granted. Notification/battery requests
   are optional for this foreground check; continue without granting extra access.
3. On the login page, scroll to the actions and choose **Skip**, not Sign in.
4. Continue past Community without opening an external link and finish onboarding.
5. If an ANR or system error appears, record it and stop. Do not dismiss it and
   claim a pass.

Then exercise the real external-audio intent, media session and player:

```sh
python3 scripts/android_qa.py verify --serial emulator-5554 --apk "$APK"
```

The check requires this app's synthetic track to enter Playing, advance at least
one second, pause and resume. It fails on a wrong APK, missing session/track,
timeout or nonadvancing playback. It records only the supplied APK hash, checkout
revision and assertions in `.factory/reports/android-qa/result.json`; unrelated
apps' raw media-session metadata is not retained. Match the APK to the build's
recorded source snapshot, because a checkout revision alone cannot prove APK
provenance. This is not a visual-layout, physical-audio or authenticated-source test.

## Bounded performance capture

With only synthetic playback running on that clean emulator:

```sh
mkdir -p .factory/reports/android-qa
adb -s emulator-5554 shell perfetto --txt -c - \
  -o /data/misc/perfetto-traces/archivetune-qa.perfetto-trace \
  < quality/perfetto.pbtxt
adb -s emulator-5554 pull \
  /data/misc/perfetto-traces/archivetune-qa.perfetto-trace \
  .factory/reports/android-qa/
```

The config bounds recording to 15 seconds and 8 MiB, including scheduler,
CPU-frequency and Android activity/view/audio slices. During capture, interact
with the synthetic track's player, or run the synthetic `verify` command in a
second terminal. Inspect the trace locally in Perfetto or
Android Studio; do not upload it to an external service. It can expose framework
rendering/stall timing, not prove physical output or provider/server traces.

Media3 1.10.1 reports [the sink's AudioTrack configuration](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L871).
The bit-perfect output provider can change the PCM container after that request.
Decoded PCM and Android-output rows therefore remain unavailable for the custom
bypass rather than presenting its pre-conversion configuration as observed output.

GMS sessions wrap the local ExoPlayer in Media3's Cast player even when not casting.
Local observations follow the matching local track and active device route, not
the session wrapper's runtime type. Switching to remote Cast clears local
observations; a remote receiver's decoded PCM and output remain unobserved.

## Local verification and remaining checks

The final 2026-10-04 serial Gradle run passed 400 unit tests and assembled both
`gmsMobileUniversalDebug` and `gmsTvUniversalDebug`. Earlier optimized x86_64 and
universal debug APKs were installed and precompiled in the QA emulator. These are
build and installation results, not approval of player behavior or release
installation on real devices. The final metadata-display checkpoint has no
verified running-player evidence.

During those earlier UI attempts, the AOSP image produced system and app ANRs.
The ATD fallback initially returned black or unavailable captures; after enabling
preboot drawing, a capture showed onboarding beneath an app ANR dialog. No
completed player interaction was verified. The universal debug APK also timed out
during launch, returned no UI hierarchy root, and produced a black capture. An app
trace was blocked in rendering, but that does not rule out an app defect on a
physical device.

- [x] Fork unit tests and mobile/TV universal debug assembly.
- [x] Earlier debug emulator installation and precompilation.
- [ ] Physical mobile and TV installation, including update/signing compatibility.
- [ ] Authenticated-provider playback, YouTube fallback, downloaded-track
  reproduction, and source rejection without losing playback position.
- [ ] Crossfade and Automix playback, pause/resume, seek, manual skips, repeat,
  queue replacement, background transitions, and Bluetooth route changes.
- [ ] Player and Home appearance, gestures, lyric-button touch targets, and lyric
  animation in the running app.
- [ ] Track Info reported, decoded-PCM, and Android-output observations across
  track/source changes, offload, and bit-perfect bypass.
- [ ] Audible transitions and physical device/DAC output checks.

Keep these checks separate from the physical-device release gate in
[`docs/claude/RELEASES.md`](claude/RELEASES.md).
