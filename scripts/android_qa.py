#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Auth-free local-audio QA on an explicitly selected Android emulator."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import wave

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "moe.rukamori.archivetune.debug"
COMPONENT = f"{PACKAGE}/moe.rukamori.archivetune.MainActivity"
FIXTURE_NAME = "archivetune-qa.wav"
DEVICE_FIXTURE = f"/sdcard/Music/ArchiveTune-QA/{FIXTURE_NAME}"


def write_fixture(path: Path) -> None:
    sample_rate = 22050
    with wave.open(str(path), "wb") as audio:
        audio.setparams((1, 2, sample_rate, 0, "NONE", "not compressed"))
        for second in range(30):
            audio.writeframes(b"".join(
                struct.pack("<h", round(1000 * math.sin(2 * math.pi * 220 * (second + i / sample_rate))))
                for i in range(sample_rate)
            ))


def session_state(dump: str) -> tuple[int, int, str] | None:
    match = re.search(r"(?m)^\s*package=" + re.escape(PACKAGE) + r"\s*$", dump)
    if not match:
        return None
    following = re.search(r"(?m)^\s*package=", dump[match.end():])
    end = match.end() + following.start() if following else len(dump)
    block = dump[match.start():end]
    state = re.search(r"PlaybackState\s*\{\s*state=(\d+),\s*position=(-?\d+)", block)
    if not state:
        return None
    # Do not retain metadata or other apps' session dumps.
    return int(state.group(1)), int(state.group(2)), FIXTURE_NAME if FIXTURE_NAME in block else ""


class Device:
    def __init__(self, serial: str):
        executable = shutil.which("adb")
        if not executable:
            raise ValueError("adb is not on PATH; add ANDROID_HOME/platform-tools")
        self.command = [executable, "-s", serial]
        if self.run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise ValueError("this harness only operates on an emulator, never a physical/personal device")
        if not self.run("shell", "pm", "path", PACKAGE).startswith("package:"):
            raise ValueError("install the exact debug APK before preparing QA")

    def run(self, *args: str, timeout: int = 40) -> str:
        result = subprocess.run([*self.command, *args], capture_output=True, text=True, timeout=timeout, check=False)
        if result.returncode:
            raise ValueError(f"adb operation failed: {args[0]}")
        return result.stdout

    def assert_apk(self, apk: Path) -> str:
        with apk.open("rb") as artifact:
            digest = hashlib.file_digest(artifact, "sha256").hexdigest()
        installed = self.run("shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_~./=+-]+/base\.apk", installed):
            raise ValueError("expected a single installed debug APK")
        installed_digest = self.run("shell", "sha256sum", installed).split()[0]
        if installed_digest != digest:
            raise ValueError("installed APK differs from --apk; install the exact candidate and retry")
        return digest

    def wait_state(self, expected: int) -> int:
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = session_state(self.run("shell", "dumpsys", "media_session"))
            if state and state[0] == expected and state[2] == FIXTURE_NAME:
                return state[1]
            time.sleep(0.5)
        raise ValueError(f"synthetic track did not reach media-session state {expected}; inspect the actual screen/ANRs")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "verify"))
    parser.add_argument("--serial", required=True, help="explicit emulator serial, for example emulator-5554")
    parser.add_argument("--apk", required=True, type=Path, help="exact installed debug APK to verify by SHA-256")
    args = parser.parse_args()
    if not args.apk.is_file():
        raise ValueError("--apk must identify an existing debug APK")
    device = Device(args.serial)
    digest = device.assert_apk(args.apk)
    evidence = ROOT / ".factory/reports/android-qa"
    evidence.mkdir(parents=True, exist_ok=True)
    if args.operation == "prepare":
        fixture = evidence / FIXTURE_NAME
        write_fixture(fixture)
        device.run("shell", "mkdir", "-p", "/sdcard/Music/ArchiveTune-QA")
        device.run("push", str(fixture), DEVICE_FIXTURE)
        sdk = int(device.run("shell", "getprop", "ro.build.version.sdk").strip())
        permission = "READ_MEDIA_AUDIO" if sdk >= 33 else "READ_EXTERNAL_STORAGE"
        output = device.run("shell", "pm", "grant", PACKAGE, f"android.permission.{permission}")
        if "Exception" in output or "Error" in output:
            raise ValueError("media permission was not granted")
        device.run("shell", "am", "start", "-W", "-n", COMPONENT)
        print("Fixture ready. Complete onboarding without signing in, then run verify with the same --serial and --apk.")
        return 0
    device.run(
        "shell", "am", "start", "-W", "-n", COMPONENT,
        "-a", "android.intent.action.VIEW", "-d", f"file://{DEVICE_FIXTURE}", "-t", "audio/wav",
    )
    device.run("shell", "input", "keyevent", "KEYCODE_MEDIA_PLAY")
    device.wait_state(3)
    time.sleep(2)
    device.run("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
    position = device.wait_state(2)
    if position < 1000:
        raise ValueError("player did not advance at least one second before pausing")
    device.run("shell", "input", "keyevent", "KEYCODE_MEDIA_PLAY")
    device.wait_state(3)
    revision = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).decode().strip()
    result = {
        "checkoutRevision": revision,
        "apkSha256": digest,
        "package": PACKAGE,
        "fixture": "generated 30-second 220 Hz mono WAV; no account or remote audio",
        "verified": ["local file intent", "playing", "position advanced", "pause", "resume"],
        "pausedPositionMs": position,
        "notVerified": ["physical audio output", "provider authentication", "TV UI", "release signing"],
    }
    (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Android QA failed: {error}", file=sys.stderr)
        sys.exit(1)
