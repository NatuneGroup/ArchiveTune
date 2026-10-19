#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Repeat real unit-test execution, retain every outcome, and fail on any failure."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
TASK = ":app:testGmsMobileUniversalDebugUnitTest"


def read_results(directory: Path) -> dict:
    cases = {}
    for report in sorted(directory.glob("TEST-*.xml")):
        document = ET.parse(report).getroot()
        if document.tag != "testsuite":
            raise ValueError("unexpected JUnit report format")
        for case in document.findall("testcase"):
            key = f"{case.attrib['classname']}#{case.attrib['name']}"
            if key in cases:
                raise ValueError("duplicate JUnit test identity")
            status = "failed" if case.find("failure") is not None or case.find("error") is not None else "passed"
            if case.find("skipped") is not None:
                status = "skipped"
            cases[key] = {"status": status, "seconds": float(case.attrib.get("time", "0"))}
    if not cases:
        raise ValueError("no executed-test reports; cached/NO-SOURCE/compile-only work is not a stability check")
    return cases


def summarize(iterations: list[dict], exits: list[int]) -> dict:
    keys = sorted(set().union(*(set(run) for run in iterations)))
    history = {
        key: [run.get(key, {"status": "missing"})["status"] for run in iterations]
        for key in keys
    }
    flaky = [key for key, states in history.items() if len(set(states)) > 1]
    incomplete = [key for key, states in history.items() if any(state != "passed" for state in states)]
    failed_runs = [number for number, code in enumerate(exits, start=1) if code]
    slowest = sorted((
        {
            "test": key,
            "medianSeconds": statistics.median(run[key]["seconds"] for run in iterations if key in run),
            "maxSeconds": max(run[key]["seconds"] for run in iterations if key in run),
        }
        for key in keys
    ), key=lambda item: item["maxSeconds"], reverse=True)[:20]
    return {
        "passed": not (flaky or incomplete or failed_runs),
        "repetitions": len(iterations),
        "testCases": len(keys),
        "flakyCases": flaky,
        "failedSkippedOrMissingCases": incomplete,
        "failedGradleIterations": failed_runs,
        "slowestTests": slowest,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--tests", action="append", required=True, help="Gradle test filter; repeat to select more")
    args = parser.parse_args()
    if not 2 <= args.repeat <= 10:
        parser.error("--repeat must be between 2 and 10")
    parent = ROOT / ".factory/reports/test-stability"
    parent.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    iterations, exits = [], []
    for number in range(1, args.repeat + 1):
        attempt = evidence / f"iteration-{number}"
        attempt.mkdir()
        results = attempt / "junit"
        command = [
            str(ROOT / "gradlew"), "--console=plain", "--stacktrace", "--profile",
            "-I", str(ROOT / "quality/unit-tests.init.gradle"),
            f"-PqualityTestResults={results}", TASK, "--rerun",
        ]
        for selected in args.tests:
            command += ["--tests", selected]
        started = time.monotonic()
        with (attempt / "gradle.log").open("w") as output:
            result = subprocess.run(
                command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                timeout=1800, check=False,
            )
        cases = read_results(results)
        iterations.append(cases)
        exits.append(result.returncode)
        (attempt / "cases.json").write_text(json.dumps({
            "gradleExitCode": result.returncode,
            "elapsedSeconds": time.monotonic() - started,
            "cases": cases,
        }, indent=2) + "\n")
        print(f"Iteration {number}: {len(cases)} real test cases; Gradle exit {result.returncode}", flush=True)
    summary = summarize(iterations, exits)
    summary["filters"] = args.tests
    summary["evidenceDirectory"] = str(evidence.relative_to(ROOT))
    (evidence / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"Test stability failed: {error}", file=sys.stderr)
        sys.exit(1)
