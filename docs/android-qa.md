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
render-capable Android 35 AOSP image. ATD images disable drawing by default and
are not used for this visual-QA path.

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
