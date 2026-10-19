#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Lightweight repository contracts. Never starts Gradle, a JVM, or a network call."""

from __future__ import annotations

import argparse
import fnmatch
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tomllib
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
POLICY = "quality/repository-policy.json"
VENDORED = ("third_party/", "native/", "audio/", "app/src/main/java/org/drinkless/")
HEADER_MARKER = "GPL-3.0 License | Contributors: see git history"


def git(*args: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(ROOT), *args],
        capture_output=True,
        check=False,
    )
    if result.returncode:
        raise ValueError(f"Git operation failed: {args[0]}")
    return result.stdout


class GitObjects:
    """One lazy object reader avoids hundreds of Git startups in a staged hook."""

    def __init__(self, root: Path):
        self.root = root
        self.process = None
        self.sizes: dict[str, int | None] = {}

    def request(self, command: str, ref: str) -> int | None:
        if "\n" in ref or "\r" in ref:
            raise ValueError("source paths containing newlines are not supported")
        if self.process is None:
            self.process = subprocess.Popen(
                ["git", "-C", str(self.root), "cat-file", "--batch-command"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            )
        self.process.stdin.write(f"{command} {ref}\n".encode())
        self.process.stdin.flush()
        header = self.process.stdout.readline()
        if not header:
            raise ValueError("Git object reader ended unexpectedly")
        if header.endswith(b" missing\n"):
            return None
        fields = header.split()
        if len(fields) == 2 and fields[1] == b"submodule":
            # Git 2.55 reports a submodule pointer by type alone: no size, no content.
            return None
        if len(fields) != 3 or not fields[2].isdigit():
            raise ValueError("Git object reader returned an invalid header")
        return int(fields[2]) if fields[1] == b"blob" else None

    def size(self, ref: str) -> int | None:
        if ref not in self.sizes:
            self.sizes[ref] = self.request("info", ref)
        return self.sizes[ref]

    def read(self, ref: str) -> bytes:
        size = self.request("contents", ref)
        if size is None:
            return b""
        data = self.process.stdout.read(size)
        if len(data) != size or self.process.stdout.read(1) != b"\n":
            raise ValueError("Git object reader returned a truncated blob")
        return data

    def close(self) -> None:
        if self.process is None:
            return
        process, self.process = self.process, None
        try:
            process.stdin.close()
        except OSError:
            pass
        process.stdout.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


class Snapshot:
    def __init__(self, staged: bool = False, base: str | None = None):
        self.staged = staged
        self.base = "HEAD"
        if base:
            resolved = git("rev-parse", "--verify", "--end-of-options", base + "^{commit}")
            self.base = git("merge-base", resolved.decode().strip(), "HEAD").decode().strip()
        self.committed = bool(base)
        self.cache: dict[str, str] = {}
        self.objects = GitObjects(ROOT)
        self.files = self._files()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.objects.close()

    def _files(self) -> set[str]:
        if self.committed:
            output = git("ls-tree", "-r", "--name-only", "-z", "HEAD")
        else:
            args = ["ls-files", "--cached", "-z"]
            if not self.staged:
                args += ["--others", "--exclude-standard"]
            output = git(*args)
        files = set(output.decode().rstrip("\0").split("\0")) - {""}
        if not self.staged and not self.committed:
            files = {path for path in files if (ROOT / path).exists() or (ROOT / path).is_symlink()}
        return files

    def changed(self) -> set[str]:
        args = ["diff", "--name-only", "--diff-filter=ACMR", "-z", "--no-renames"]
        if self.staged:
            args += ["--cached", "HEAD"]
        elif self.committed:
            args += [self.base, "HEAD"]
        else:
            args += ["HEAD"]
        changed = set(git(*args, "--").decode().rstrip("\0").split("\0")) - {""}
        if not self.staged and not self.committed:
            changed |= set(
                git("ls-files", "--others", "--exclude-standard", "-z")
                .decode().rstrip("\0").split("\0")
            ) - {""}
        return changed

    def ref(self, path: str, old: bool = False) -> str:
        if old:
            return f"{self.base}:{path}"
        return f":{path}" if self.staged else f"HEAD:{path}"

    def size(self, path: str, old: bool = False) -> int | None:
        if not old and not self.staged and not self.committed:
            candidate = ROOT / path
            return candidate.stat().st_size if candidate.is_file() and not candidate.is_symlink() else None
        return self.objects.size(self.ref(path, old))

    def text(self, path: str, old: bool = False) -> str:
        key = ("old:" if old else "") + path
        if key in self.cache:
            return self.cache[key]
        size = self.size(path, old)
        if size is None or size > 2 * 1024 * 1024:
            return ""
        if old or self.staged or self.committed:
            data = self.objects.read(self.ref(path, old))
        else:
            data = (ROOT / path).read_bytes()
        self.cache[key] = data.decode("utf-8", errors="replace")
        return self.cache[key]


def budget_problem(size: int, old_size: int | None, limit: int) -> bool:
    return size > limit and (old_size is None or size > max(limit, old_size))


def copyright_lines(text: str) -> set[str]:
    header = text.split("package ", 1)[0][:16000]
    return {
        line.strip().lstrip("*").strip()
        for line in header.splitlines()
        if re.search(r"(?:©|\bcopyright\b)", line, re.IGNORECASE)
    }


def header_problems(path: str, current: str, previous: str, new: bool) -> list[str]:
    if not path.endswith(".kt") or path.startswith(VENDORED):
        return []
    problems = []
    if new and not re.search(r"GPL-3\.0|SPDX-License-Identifier:\s*GPL-3\.0", current[:16000]):
        problems.append("new Kotlin source requires its GPL banner and provenance")
    if current.count(HEADER_MARKER) > max(1, previous.count(HEADER_MARKER)):
        problems.append("duplicate ArchiveTune license banners")
    if copyright_lines(previous) - copyright_lines(current):
        problems.append("existing copyright attribution was removed or replaced")
    return problems


def forbidden_imports(text: str, prefixes: list[str]) -> list[str]:
    imports = re.findall(r"(?m)^\s*import\s+([\w.*]+)", text)
    return sorted({
        imported for imported in imports
        if any(imported == prefix or imported.startswith(prefix + ".") for prefix in prefixes)
    })


def markdown_targets(text: str) -> list[str]:
    without_code = re.sub(r"(?ms)^```.*?^```[^\n]*", "", text)
    return re.findall(r"!?\[[^\]\n]*\]\(\s*<?([^\s)>]+)>?(?:\s+[^)]*)?\)", without_code)


def link_problem(source: str, target: str, files: set[str]) -> str | None:
    url = urlsplit(target)
    if url.scheme or url.netloc or not url.path:
        return None
    path = PurePosixPath(source).parent / unquote(url.path)
    parts: list[str] = []
    for part in path.parts:
        if part == "..":
            if not parts:
                return "link escapes the repository"
            parts.pop()
        elif part not in ("", "."):
            parts.append(part)
    normalized = "/".join(parts)
    if normalized not in files and not any(name.startswith(normalized.rstrip("/") + "/") for name in files):
        return f"missing local link target: {normalized}"
    return None


def skill_problems(path: str, text: str) -> list[str]:
    if not path.endswith("/SKILL.md"):
        return []
    parts = text.split("---", 2)
    if len(parts) != 3 or parts[0].strip():
        return ["skill requires YAML frontmatter"]
    fields = dict(re.findall(r"(?m)^([a-z-]+):\s*(.+?)\s*$", parts[1]))
    name = fields.get("name", "").strip("'\"")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        return ["skill name must use lowercase words separated by hyphens"]
    if name != PurePosixPath(path).parent.name:
        return ["skill name must match its directory"]
    if not fields.get("description", "").strip("'\"") or not parts[2].strip():
        return ["skill requires a description and a substantive workflow"]
    return []


def documented_tasks(snapshot: Snapshot, policy: dict) -> list[tuple[str, str]]:
    problems = []
    docs = "\n".join(snapshot.text(path) for path in ("AGENTS.md", "CONTRIBUTING.md"))
    for task, workflow in policy["documentedTasks"].items():
        bare = task.removeprefix(":app:")
        if bare not in docs:
            problems.append(("AGENTS.md", f"documented Gradle task missing: {task}"))
        if task not in snapshot.text(workflow) and bare not in snapshot.text(workflow):
            problems.append((workflow, f"documented task is not exercised by CI: {task}"))
    versions = tomllib.loads(snapshot.text("gradle/libs.versions.toml"))["versions"]
    wrapper = snapshot.text("gradle/wrapper/gradle-wrapper.properties")
    match = re.search(r"gradle-([\d.]+)-bin\.zip", wrapper)
    for value in (versions["kotlin"], match.group(1) if match else ""):
        if not value:
            problems.append(("gradle/wrapper/gradle-wrapper.properties", "wrapper version is not pinned"))
    if not re.search(r"(?m)^distributionSha256Sum=[0-9a-fA-F]{64}\s*$", wrapper):
        problems.append(("gradle/wrapper/gradle-wrapper.properties", "wrapper checksum is missing"))
    return problems


def toolchain_markdown(snapshot: Snapshot, policy: dict) -> str:
    versions = tomllib.loads(snapshot.text("gradle/libs.versions.toml"))["versions"]
    app = snapshot.text("app/build.gradle.kts")
    wrapper = snapshot.text("gradle/wrapper/gradle-wrapper.properties")
    def value(pattern: str, source: str) -> str:
        match = re.search(pattern, source)
        if not match:
            raise ValueError(f"toolchain reference cannot resolve {pattern}")
        return match.group(1)

    tools = [
        ("JDK / JVM target", value(r"JvmTarget\.JVM_(\d+)", app)),
        ("Gradle wrapper", value(r"gradle-([\d.]+)-bin\.zip", wrapper)),
        ("Android Gradle plugin", versions["agp"]),
        ("Kotlin", versions["kotlin"]),
        ("Android compile SDK", value(r"compileSdk\s*=\s*(\d+)", app)),
        ("Android minimum SDK", value(r"minSdk\s*=\s*(\d+)", app)),
        ("Android target SDK", value(r"targetSdk\s*=\s*(\d+)", app)),
        ("NDK", value(r'ndkVersion\s*=\s*"([^"]+)"', app)),
    ]
    modules = re.findall(r'(?m)^include\("([^"]+)"\)', snapshot.text("settings.gradle.kts"))
    lines = [
        "# Generated toolchain and CI reference", "",
        "<!-- Generated by scripts/update_toolchain_docs.py. Do not hand-edit. -->", "",
        "Regenerate after changing toolchain pins, modules or documented CI tasks:", "",
        "```sh", "python3 scripts/update_toolchain_docs.py", "```", "",
        "## Toolchain", "", "| Tool | Pinned value |", "|---|---|",
        *(f"| {name} | `{version}` |" for name, version in tools), "",
        "## Documented CI commands", "", "| Command | Workflow |", "|---|---|",
    ]
    for task, workflow in policy["documentedTasks"].items():
        lines.append(f"| `./gradlew {task}` | [{PurePosixPath(workflow).name}](../../{workflow}) |")
    lines += [
        "", "APK assembly does not compile unit tests. Debug success does not establish",
        "Release shrinking/signing or device behavior.", "", "## Gradle modules", "",
        *(f"- `{module}`" for module in modules), "",
        "Submodule pins and initialization remain separate from this generated reference.", "",
    ]
    return "\n".join(lines)


def literal_dependencies(text: str) -> list[tuple[str, str]]:
    declaration = re.compile(
        r'(?:(?:implementation|api|compileOnly|runtimeOnly|testImplementation|classpath)'
        r'\s*\(\s*|add\s*\(\s*"[\w.]+"\s*,\s*)'
        r'"([\w.-]+:[\w.-]+):([^"\r\n]+)"'
    )
    return declaration.findall(text)


def dependency_problems(snapshot: Snapshot) -> list[tuple[str, str]]:
    catalog = tomllib.loads(snapshot.text("gradle/libs.versions.toml"))
    versions = catalog["versions"]
    aliases = {}
    expected = {}
    for alias, library in catalog.get("libraries", {}).items():
        coordinate = library.get("module") or f"{library['group']}:{library['name']}"
        version = library.get("version")
        if isinstance(version, dict):
            version = versions.get(version.get("ref", ""))
        if isinstance(version, str):
            expected[coordinate] = version
            aliases[re.sub(r"[-_]", ".", alias)] = (coordinate, version)
    declarations: dict[str, list[tuple[str, str]]] = {}
    problems = []
    for path in sorted(snapshot.files):
        if not path.endswith("build.gradle.kts") or path.startswith(("third_party/", ".worktrees/")):
            continue
        text = snapshot.text(path)
        for alias in re.findall(r"\blibs\.([\w.]+)", text):
            if alias in aliases:
                coordinate, version = aliases[alias]
                declarations.setdefault(coordinate, []).append((path, version))
        for coordinate, version in literal_dependencies(text):
            match = re.fullmatch(r"\$\{libs\.versions\.([\w]+)\.get\(\)\}", version)
            if match:
                version = versions.get(match.group(1), "")
            if not version or "$" in version:
                problems.append((path, f"dependency version cannot be validated: {coordinate}"))
                continue
            if re.search(r"(?i)LATEST|RELEASE|SNAPSHOT|latest\.|[+\[\],]", version):
                problems.append((path, f"dynamic dependency version: {coordinate}"))
            declarations.setdefault(coordinate, []).append((path, version))
            if coordinate in expected and version != expected[coordinate]:
                problems.append((path, f"{coordinate} differs from the shared catalog; use its alias/version"))
    for coordinate, uses in declarations.items():
        if len({version for _, version in uses}) > 1:
            for path, _ in uses:
                problems.append((path, f"cross-module version drift: {coordinate}"))
    return sorted(set(problems))


def architecture_problems(snapshot: Snapshot, policy: dict) -> list[tuple[str, str]]:
    problems = []
    for path in sorted(snapshot.files):
        module = path.split("/", 1)[0]
        if module in policy["libraryModules"] and path.endswith((".kt", ".java")):
            for imported in forbidden_imports(snapshot.text(path), policy["libraryForbiddenImports"]):
                problems.append((path, f"library must not depend on the application layer: {imported}"))
        if module in policy["libraryModules"] and path.endswith("build.gradle.kts"):
            if re.search(r'project\s*\(\s*":app"\s*\)', snapshot.text(path)):
                problems.append((path, "library must not depend on :app"))
    for path in policy["contractSources"]:
        if path not in snapshot.files:
            problems.append((path, "protected source-selection contract is missing"))
        for imported in forbidden_imports(snapshot.text(path), policy["contractForbiddenImports"]):
            problems.append((path, f"source-selection policy must remain independent: {imported}"))
    return problems


def check(snapshot: Snapshot, headers_only: bool = False) -> list[tuple[str, str]]:
    policy = json.loads(snapshot.text(POLICY))
    problems = []
    for path in sorted(snapshot.changed()):
        size = snapshot.size(path)
        if size is None:
            continue
        previous_size = snapshot.size(path, old=True)
        text = snapshot.text(path)
        previous = snapshot.text(path, old=True)
        for problem in header_problems(path, text, previous, previous_size is None):
            problems.append((path, problem))
        if headers_only:
            continue
        if budget_problem(size, previous_size, policy["maxFileBytes"]):
            problems.append((path, "new/expanded large file exceeds the 1 MiB budget; split it or use reviewed external assets"))
        if path.endswith(".kt") and not path.startswith(VENDORED):
            if budget_problem(len(text.splitlines()), len(previous.splitlines()) if previous_size is not None else None, policy["maxKotlinLines"]):
                problems.append((path, "new/expanded Kotlin file exceeds the 1,000-line budget; extract a cohesive component"))
    if headers_only:
        return problems
    for path in sorted(snapshot.files):
        if not any(fnmatch.fnmatchcase(path, pattern) for pattern in policy["maintainedDocs"]):
            continue
        text = snapshot.text(path)
        for target in markdown_targets(text):
            problem = link_problem(path, target, snapshot.files)
            if problem:
                problems.append((path, problem))
        for problem in skill_problems(path, text):
            problems.append((path, problem))
    problems.extend(documented_tasks(snapshot, policy))
    problems.extend(architecture_problems(snapshot, policy))
    problems.extend(dependency_problems(snapshot))
    if snapshot.text("docs/engineering/toolchain.md") != toolchain_markdown(snapshot, policy):
        problems.append(("docs/engineering/toolchain.md", "generated toolchain/CI reference is stale; run python3 scripts/update_toolchain_docs.py"))
    return sorted(set(problems))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--staged", action="store_true", help="validate index contents, not unstaged edits")
    scope.add_argument("--base", help="validate committed changes against the merge base")
    scope.add_argument("--worktree", action="store_true", help="validate the working tree (default)")
    parser.add_argument("--headers-only", action="store_true")
    args = parser.parse_args()
    try:
        with Snapshot(staged=args.staged, base=args.base) as snapshot:
            problems = check(snapshot, headers_only=args.headers_only)
    except (OSError, ValueError, KeyError, tomllib.TOMLDecodeError) as error:
        print(f"Repository check could not complete: {error}", file=sys.stderr)
        return 2
    for path, message in problems:
        print(f"{path}: {message}")
    print(f"Repository checks: {len(problems)} finding(s); no JVM or network operations.")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
