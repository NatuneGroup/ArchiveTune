#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
export JAVA_HOME="${JAVA_HOME:-$(mise where java)}"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$sdkmanager" ]]; then
    temporary="$(mktemp -d)"
    trap 'rm -rf "$temporary"' EXIT
    curl --fail --silent --show-error --location --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
        --output "$temporary/tools.zip"
    unzip -q "$temporary/tools.zip" -d "$temporary"
    mkdir -p "$sdk_root/cmdline-tools"
    mv "$temporary/cmdline-tools" "$sdk_root/cmdline-tools/latest"
fi

"$sdkmanager" --licenses < <(printf 'y\n%.0s' {1..100}) > /dev/null
"$sdkmanager" 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0' \
    'ndk;30.0.16248370' 'cmake;3.22.1'

properties="local.properties"
if [[ ! -f "$properties" ]]; then
    printf 'sdk.dir=%s\n' "$sdk_root" > "$properties"
elif ! grep -q '^sdk.dir=' "$properties"; then
    printf '\nsdk.dir=%s\n' "$sdk_root" >> "$properties"
fi

if [[ "${1:-}" == '--emulator' ]]; then
    "$sdkmanager" 'emulator' 'system-images;android-35;google_atd;x86_64'
    if ! python3 -c 'import ctypes; ctypes.CDLL("libxkbfile.so.1"); ctypes.CDLL("libtcmalloc_minimal.so.4")' 2>/dev/null; then
        sudo -n true
        sudo apt-get update -qq
        tcmalloc_package='libtcmalloc-minimal4'
        if apt-cache show libtcmalloc-minimal4t64 > /dev/null 2>&1; then
            tcmalloc_package='libtcmalloc-minimal4t64'
        fi
        sudo apt-get install -y -qq libxkbfile1 "$tcmalloc_package"
    fi
    export ANDROID_AVD_HOME="$PWD/.hoplite/android-avd"
    export ANDROID_USER_HOME="$PWD/.hoplite/android-user"
    mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
    if [[ ! -f "$ANDROID_AVD_HOME/ArchiveTuneQA35.ini" ]]; then
        printf 'no\n' | "$sdk_root/cmdline-tools/latest/bin/avdmanager" create avd \
            -n ArchiveTuneQA35 -k 'system-images;android-35;google_atd;x86_64'
    fi
    printf 'QA AVD: ArchiveTuneQA35\nANDROID_AVD_HOME=%s\nANDROID_USER_HOME=%s\n' \
        "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
fi
