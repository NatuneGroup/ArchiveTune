#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Run SDK-independent Kotlin analysis remotely and fail on new or worse findings."""

from __future__ import annotations

import argparse
from collections import Counter
from io import BytesIO
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOTS = (
    "app/src/main/kotlin", "canvas/src", "jiosaavn/src", "lastfm/src",
    "musixmatch/src", "shazamkit/src", "spotifycore/src",
)
FORMAT_ROOTS = (*SOURCE_ROOTS, "app/src/test")


def prepare_formatter(root: Path, revision: str, destination: Path) -> int:
    paths = set()
    for arguments in (
        ["diff", "--name-only", "--diff-filter=ACMRT", "-z", revision, "--", *FORMAT_ROOTS],
        ["ls-files", "--others", "--exclude-standard", "-z", "--", *FORMAT_ROOTS],
    ):
        paths.update(subprocess.check_output(["git", "-C", str(root), *arguments]).decode().split("\0"))
    count = 0
    for value in sorted(paths):
        if not value or Path(value).suffix != ".kt":
            continue
        path = relative_path(value, root)
        source = root / path
        if source.is_symlink() or not source.resolve().is_relative_to(root.resolve()):
            raise ValueError("formatter source must be a regular file inside the repository")
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        count += 1
    return count


def relative_path(value: str, root: Path) -> str:
    path = Path(value)
    if path.is_absolute():
        path = path.relative_to(root)
    if ".." in path.parts:
        raise ValueError("analysis report contains an escaping source path")
    return path.as_posix()


def detekt_findings(report: Path, root: Path) -> tuple[Counter, dict]:
    document = ET.parse(report).getroot()
    if document.tag != "checkstyle":
        raise ValueError("invalid Detekt report")
    findings = Counter()
    weights = {}
    for file in document.findall("file"):
        path = relative_path(file.attrib["name"], root)
        for error in file.findall("error"):
            message = error.attrib["message"]
            rule = error.attrib["source"].split(".")[-1]
            key = (path, rule, re.sub(r"\b\d+\b", "#", message))
            findings[key] += 1
            numbers = re.findall(r"\b\d+\b", message)
            if numbers and rule in {"CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth"}:
                weights[key] = max(weights.get(key, 0), int(numbers[0]))
    return findings, weights


def cpd_findings(report: Path, root: Path) -> Counter:
    document = ET.parse(report).getroot()
    if document.tag.rsplit("}", 1)[-1] != "pmd-cpd":
        raise ValueError("invalid CPD report")
    if any(node.tag.rsplit("}", 1)[-1] in {"error", "suppressedviolation"} for node in document.iter()):
        raise ValueError("CPD could not analyze every source")
    findings = Counter()
    for duplicate in document:
        if duplicate.tag.rsplit("}", 1)[-1] != "duplication":
            continue
        paths = tuple(sorted(
            relative_path(node.attrib["path"], root)
            for node in duplicate
            if node.tag.rsplit("}", 1)[-1] == "file"
        ))
        fragment = next((
            node.text or "" for node in duplicate
            if node.tag.rsplit("}", 1)[-1] == "codefragment"
        ), "")
        if len(paths) < 2 or not fragment.strip():
            raise ValueError("incomplete CPD duplication")
        findings[(paths, re.sub(r"\s+", " ", fragment).strip())] += 1
    return findings


def regressions(before: Counter, after: Counter, old_weights: dict, new_weights: dict) -> list:
    added = list((after - before).elements())
    for key, weight in new_weights.items():
        if key in old_weights and weight > old_weights[key] and key not in added:
            added.append(key)
    return added


def run(command: list[str], *, output: Path | None = None) -> None:
    if output:
        with output.open("w") as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=False)
    else:
        result = subprocess.run(command, cwd=ROOT, check=False)
    if result.returncode:
        raise ValueError(f"quality tool failed ({result.returncode}); inspect {output or 'console output'}")


def extract_sources(revision: str, destination: Path) -> None:
    tracked = subprocess.check_output(
        ["git", "-C", str(ROOT), "ls-tree", "-d", "--name-only", revision, "--", *SOURCE_ROOTS],
    ).decode().splitlines()
    if not tracked:
        raise ValueError("base revision has no Kotlin source roots")
    archive = subprocess.check_output(
        ["git", "-C", str(ROOT), "archive", revision, "--", *tracked],
    )
    with tarfile.open(fileobj=BytesIO(archive)) as tree:
        for member in tree:
            if not member.isfile():
                continue
            path = destination / member.name
            if not path.resolve().is_relative_to(destination.resolve()):
                raise ValueError("base archive escapes its temporary directory")
            path.parent.mkdir(parents=True, exist_ok=True)
            with tree.extractfile(member) as source:
                path.write_bytes(source.read())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="HEAD", help="revision to compare; never an analyzer suppression file")
    parser.add_argument("--classpath-file", type=Path, help="real compiler classpath from the optional qualityInputs task")
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "-C", str(ROOT), "rev-parse", "--verify", "--end-of-options", args.base + "^{commit}"],
    ).decode().strip()
    revision = subprocess.check_output(
        ["git", "-C", str(ROOT), "merge-base", revision, "HEAD"],
    ).decode().strip()
    reports = ROOT / ".factory/reports/code-quality"
    reports.mkdir(parents=True, exist_ok=True)
    wrapper = [str(ROOT / "gradlew"), "-p", str(ROOT / "quality"), "--console=plain", "--stacktrace"]
    if args.classpath_file:
        entries = [Path(line) for line in args.classpath_file.read_text().splitlines() if line.strip()]
        if not entries or not all(path.is_absolute() and path.exists() for path in entries):
            raise ValueError("compiler classpath must contain existing absolute paths from this build host")
        wrapper.append(f"-PanalysisClasspathFile={args.classpath_file.resolve()}")
    formatter_parent = ROOT / "quality/build"
    formatter_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="format-input-", dir=formatter_parent) as temporary:
        formatter_root = Path(temporary)
        formatted_files = prepare_formatter(ROOT, revision, formatter_root)
        run([*wrapper, f"-PanalysisFormatRoot={formatter_root}", "spotlessCheck"], output=reports / "formatter.log")
    with tempfile.TemporaryDirectory(prefix="archivetune-quality-") as temporary:
        base = Path(temporary)
        extract_sources(revision, base)
        collected = {}
        for name, source in (("base", base), ("candidate", ROOT)):
            report = reports / name
            report.mkdir(exist_ok=True)
            for filename in ("detekt.xml", "cpd.xml"):
                (report / filename).unlink(missing_ok=True)
            run(
                [*wrapper, f"-PanalysisRoot={source}", f"-PanalysisReports={report}",
                 "detektReport", "duplicateCodeReport", "--rerun-tasks"],
                output=reports / f"{name}.log",
            )
            findings, weights = detekt_findings(report / "detekt.xml", source)
            duplicates = cpd_findings(report / "cpd.xml", source)
            collected[name] = (findings, weights, duplicates)
        before, old_weights, old_duplicates = collected["base"]
        after, new_weights, new_duplicates = collected["candidate"]
        new_findings = regressions(before, after, old_weights, new_weights)
        duplicate_regressions = []
        for key in (new_duplicates - old_duplicates):
            paths, fragment = key
            if not any(old_paths == paths and fragment in old_fragment for old_paths, old_fragment in old_duplicates):
                duplicate_regressions.append(key)
        summary = {
            "base": revision,
            "semanticAnalysis": bool(args.classpath_file),
            "changedKotlinFilesChecked": formatted_files,
            "inheritedDetektFindings": before.total(),
            "candidateDetektFindings": after.total(),
            "newOrWorseDetektFindings": len(new_findings),
            "inheritedDuplicateBlocks": old_duplicates.total(),
            "candidateDuplicateBlocks": new_duplicates.total(),
            "newDuplicateBlocks": len(duplicate_regressions),
            "rules": dict(sorted(Counter(key[1] for key in after.elements()).items())),
        }
        (reports / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(summary, indent=2))
        for path, rule, message in new_findings:
            print(f"{path}: {rule}: {message}")
        for paths, _ in duplicate_regressions:
            print(f"new duplicate Kotlin block: {', '.join(paths)}")
        return int(bool(new_findings or duplicate_regressions))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"Code quality failed: {error}", file=sys.stderr)
        sys.exit(2)
