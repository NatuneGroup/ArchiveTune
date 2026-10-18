#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Regenerate the source-derived toolchain reference, without a JVM or network."""

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from scripts.check_repository import POLICY, Snapshot, toolchain_markdown


if __name__ == "__main__":
    destination = ROOT / "docs/engineering/toolchain.md"
    with Snapshot() as snapshot:
        destination.write_text(toolchain_markdown(snapshot, json.loads(snapshot.text(POLICY))))
    print("Updated docs/engineering/toolchain.md")
