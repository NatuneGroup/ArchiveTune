#!/usr/bin/env python3
"""Tiny adb/uiautomator driver for CI screenshot capture.

Usage:
  uiauto.py dump <file>            save raw window dump XML
  uiauto.py list                   print text|desc|bounds of every node
  uiauto.py tap <text> [nth]       tap center of node whose text or
                                   content-desc equals <text>
  uiauto.py tapi <text>            tap node containing <text> (substring, case-insensitive)
  uiauto.py tapcls <substr>        tap first node whose class contains substr
  uiauto.py wait <text> <sec>      poll dump until text/desc appears (exact)
  uiauto.py tapxy <x> <y>          raw tap
  uiauto.py tapf <fx> <fy>         tap at fraction of screen size
  uiauto.py swipe <x1> <y1> <x2> <y2> [ms]
  uiauto.py text <string>          type text (%s encoding for spaces)
  uiauto.py key <keycode>          input keyevent
  uiauto.py shot <file.png>        exec-out screencap
  uiauto.py size                   print WxH
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = ["adb"]
DUMP_PATH = "/sdcard/_shot_ui.xml"


def adb(*args, timeout=60):
    return subprocess.run(ADB + list(args), capture_output=True, timeout=timeout)


def screen_size():
    r = adb("shell", "wm", "size")
    m = re.search(rb"(\d+)x(\d+)", r.stdout)
    if m:
        return int(m.group(1)), int(m.group(2))
    return 1080, 2400


def dump_xml():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", DUMP_PATH)
        r = adb("exec-out", "cat", DUMP_PATH)
        data = r.stdout.decode("utf-8", "replace")
        end = data.rfind("</hierarchy>")
        if end != -1:
            data = data[: end + len("</hierarchy>")]
        try:
            return ET.fromstring(data)
        except ET.ParseError:
            time.sleep(0.7)
    return None


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    l, t, r, b = map(int, m.groups())
    return ((l + r) // 2, (t + b) // 2)


def nodes(root):
    return list(root.iter("node")) if root is not None else []


def find_exact(root, text):
    out = []
    for n in nodes(root):
        if n.get("text", "") == text or n.get("content-desc", "") == text:
            out.append(n)
    return out


def find_contains(root, text):
    tl = text.lower()
    out = []
    for n in nodes(root):
        if tl in n.get("text", "").lower() or tl in n.get("content-desc", "").lower():
            out.append(n)
    return out


def tap_node(n):
    c = center(n.get("bounds"))
    if not c:
        return False
    adb("shell", "input", "tap", str(c[0]), str(c[1]))
    return True


def cmd_tap(args, contains=False):
    text = " ".join(args[:-1]) if len(args) > 1 and args[-1].isdigit() else " ".join(args)
    nth = int(args[-1]) if len(args) > 1 and args[-1].isdigit() else 0
    root = dump_xml()
    hits = find_contains(root, text) if contains else find_exact(root, text)
    if len(hits) <= nth:
        print("MISS:", text, file=sys.stderr)
        return 1
    print("TAP:", text, "->", hits[nth].get("bounds"))
    tap_node(hits[nth])
    return 0


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "dump":
        root = dump_xml()
        if root is None:
            return 1
        with open(args[0], "w") as f:
            f.write(ET.tostring(root, encoding="unicode"))
        return 0
    if cmd == "list":
        root = dump_xml()
        for n in nodes(root):
            t = n.get("text", "")
            d = n.get("content-desc", "")
            cl = (n.get("class", "") or "").rsplit(".", 1)[-1]
            if t or d:
                print(f"{cl:24s} t={t!r:60s} d={d!r:40s} {n.get('bounds')}")
        return 0
    if cmd == "tap":
        return cmd_tap(args)
    if cmd == "tapi":
        return cmd_tap(args, contains=True)
    if cmd == "tapcls":
        root = dump_xml()
        for n in nodes(root):
            if args[0] in (n.get("class") or ""):
                print("TAPCLS:", args[0], "->", n.get("bounds"))
                return 0 if tap_node(n) else 1
        print("MISS cls:", args[0], file=sys.stderr)
        return 1
    if cmd == "wait":
        text, secs = args[0], float(args[1])
        deadline = time.time() + secs
        while time.time() < deadline:
            root = dump_xml()
            if find_exact(root, text) or find_contains(root, text):
                print("FOUND:", text)
                return 0
            time.sleep(1.5)
        print("TIMEOUT waiting:", text, file=sys.stderr)
        return 1
    if cmd == "tapxy":
        adb("shell", "input", "tap", args[0], args[1])
        return 0
    if cmd == "tapf":
        w, h = screen_size()
        x, y = int(float(args[0]) * w), int(float(args[1]) * h)
        print("TAPF:", args[0], args[1], "->", x, y)
        adb("shell", "input", "tap", str(x), str(y))
        return 0
    if cmd == "swipe":
        adb("shell", "input", "swipe", *args[:4], *(args[4:] or ["400"]))
        return 0
    if cmd == "text":
        adb("shell", "input", "text", args[0].replace(" ", "%s"))
        return 0
    if cmd == "key":
        adb("shell", "input", "keyevent", args[0])
        return 0
    if cmd == "shot":
        with open(args[0], "wb") as f:
            subprocess.run(ADB + ["exec-out", "screencap", "-p"], stdout=f, timeout=60)
        return 0
    if cmd == "size":
        w, h = screen_size()
        print(w, h)
        return 0
    print("unknown cmd", cmd, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
