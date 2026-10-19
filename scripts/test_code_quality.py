# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
from collections import Counter
from pathlib import Path
import re
import tempfile
import unittest
import subprocess

from scripts.check_code_quality import ROOT, FORMAT_ROOTS, SOURCE_ROOTS, cpd_findings, detekt_findings, formatter_patch, formatting_regressions, prepare_base_formatter, prepare_formatter, regressions


class CodeQualityTest(unittest.TestCase):
    def report(self, text):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "report.xml"
        path.write_text(text)
        return path

    def test_line_movement_does_not_create_a_new_detekt_violation(self):
        report = '<checkstyle><file name="app/A.kt"><error line="10" source="detekt.LongMethod" message="Function resolve is too long (90). Allowed: 80."/></file></checkstyle>'
        before, weights = detekt_findings(self.report(report), Path("/repo"))
        after, new_weights = detekt_findings(self.report(report.replace('line="10"', 'line="30"')), Path("/repo"))
        self.assertEqual([], regressions(before, after, weights, new_weights))

    def test_existing_complexity_may_shrink_but_cannot_grow(self):
        key = ("app/A.kt", "CyclomaticComplexMethod", "resolve complexity (#)")
        findings = Counter({key: 1})
        self.assertEqual([], regressions(findings, findings, {key: 20}, {key: 19}))
        self.assertEqual([key], regressions(findings, findings, {key: 20}, {key: 21}))

    def test_multiple_identical_named_violations_are_not_collapsed(self):
        key = ("app/A.kt", "UnusedPrivateProperty", "unused cache")
        self.assertEqual([key], regressions(Counter({key: 1}), Counter({key: 2}), {}, {}))

    def test_duplicate_fingerprints_ignore_temporary_roots_and_line_offsets(self):
        body = '<pmd-cpd><duplication tokens="120"><file path="/base/app/A.kt" line="1"/><file path="/base/app/B.kt" line="20"/><codefragment>fun duplicate() { return 1 }</codefragment></duplication></pmd-cpd>'
        before = cpd_findings(self.report(body), Path("/base"))
        after = cpd_findings(self.report(body.replace("/base/", "/candidate/").replace('line="1"', 'line="7"')), Path("/candidate"))
        self.assertEqual(before, after)

    def test_incomplete_analyzer_reports_fail_closed(self):
        with self.assertRaises(ValueError):
            cpd_findings(self.report('<pmd-cpd><error filename="A.kt"/></pmd-cpd>'), Path("/repo"))
        with self.assertRaises(ValueError):
            detekt_findings(self.report("<html/>"), Path("/repo"))

    def test_formatter_copies_modified_and_untracked_sources_but_not_unchanged_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "--quiet")
            sources = root / "app/src/main/kotlin"
            sources.mkdir(parents=True)
            for name in ("Changed.kt", "Same.kt", "Deleted.kt"):
                (sources / name).write_text(f"class {name[:-3]}\n")
            git("add", ".")
            revision = git("write-tree")
            (sources / "Changed.kt").write_text("class Changed(val value: Int)\n")
            (sources / "Deleted.kt").unlink()
            (sources / "New.kt").write_text("class New\n")
            destination = Path(temporary) / "formatter"
            self.assertEqual(2, prepare_formatter(root, revision, destination))
            self.assertEqual(
                ["Changed.kt", "New.kt"],
                sorted(path.name for path in destination.rglob("*.kt")),
            )
            self.assertEqual(
                (sources / "Changed.kt").read_bytes(),
                (destination / "app/src/main/kotlin/Changed.kt").read_bytes(),
            )

    def test_formatter_covers_the_selected_gms_flavor_source_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "--quiet")
            flavor = root / "app/src/gms/kotlin"
            flavor.mkdir(parents=True)
            (flavor / "Gms.kt").write_text("class Gms\n")
            git("add", ".")
            revision = git("write-tree")
            (flavor / "Gms.kt").write_text("class Gms(val source: String)\n")
            destination = Path(temporary) / "formatter"
            self.assertEqual(1, prepare_formatter(root, revision, destination))
            self.assertEqual(
                (flavor / "Gms.kt").read_bytes(),
                (destination / "app/src/gms/kotlin/Gms.kt").read_bytes(),
            )

    def test_gradle_analysis_patterns_match_the_python_source_roots(self):
        text = (ROOT / "quality/build.gradle.kts").read_text(encoding="utf-8")
        block = text.split("val sourcePatterns = listOf(", 1)[1].split(")", 1)[0]
        patterns = re.findall(r'"([^"]+)"', block)
        self.assertEqual([f"{root}/**/*.kt" for root in SOURCE_ROOTS], patterns)
        self.assertIn("app/src/gms/kotlin", FORMAT_ROOTS)
        self.assertTrue((ROOT / "app/src/gms/kotlin").is_dir())

    def test_formatter_refuses_symlinks_instead_of_copying_unrelated_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            subprocess.run(["git", "-C", str(root), "init", "--quiet"], check=True)
            revision = subprocess.check_output(
                ["git", "-C", str(root), "hash-object", "-t", "tree", "--stdin"], input=b"",
            ).decode().strip()
            sources = root / "app/src/main/kotlin"
            sources.mkdir(parents=True)
            outside = Path(temporary) / "outside.kt"
            outside.write_text("class Outside\n")
            (sources / "Alias.kt").symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "regular file"):
                prepare_formatter(root, revision, Path(temporary) / "formatter")

    def test_formatter_patch_is_applicable_and_does_not_rewrite_the_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            formatted = Path(temporary) / "formatter"
            relative = Path("app/src/main/kotlin/Example.kt")
            source = root / relative
            target = formatted / relative
            source.parent.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            original = "/* Original attribution */\nfun example(){println(1)}\n"
            expected = "/* Original attribution */\nfun example() {\n    println(1)\n}\n"
            source.write_text(original)
            target.write_text(expected)
            subprocess.run(["git", "-C", str(root), "init", "--quiet"], check=True)
            patch = formatter_patch(root, formatted)
            self.assertEqual(original, source.read_text())
            subprocess.run(["git", "-C", str(root), "apply", "--check", "-"], input=patch.encode(), check=True)
            subprocess.run(["git", "-C", str(root), "apply", "-"], input=patch.encode(), check=True)
            self.assertEqual(expected, source.read_text())

    def formatting_case(self, base, base_formatted, candidate, candidate_formatted):
        """Run the ratchet with formatter output supplied for the base and candidate copies."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        work = Path(temporary.name)
        root = work / "repo"
        relative = Path("app/src/main/kotlin/Legacy.kt")
        source = root / relative
        source.parent.mkdir(parents=True)
        subprocess.run(["git", "-C", str(root), "init", "--quiet"], check=True)
        if base is not None:
            source.write_text(base)
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        revision = subprocess.check_output(["git", "-C", str(root), "write-tree"]).decode().strip()
        source.write_text(candidate)
        candidates = work / "candidate"
        (candidates / relative).parent.mkdir(parents=True)
        (candidates / relative).write_text(candidate_formatted)
        copied = prepare_base_formatter(root, revision, candidates, work / "original", work / "formatted")
        self.assertEqual(0 if base is None else 1, copied)
        if base is not None:
            self.assertEqual(base, (work / "original" / relative).read_text())
            (work / "formatted" / relative).write_text(base_formatted)
        return formatting_regressions(root, candidates, work / "original", work / "formatted")

    LEGACY = "fun legacy(){println(1)}\n\nfun kept() = 1\n"
    LEGACY_FORMATTED = "fun legacy() {\n    println(1)\n}\n\nfun kept() = 1\n"

    def test_formatted_edit_in_a_legacy_file_inherits_its_debt(self):
        candidate = self.LEGACY.replace("= 1", "= 2")
        self.assertEqual([], self.formatting_case(
            self.LEGACY, self.LEGACY_FORMATTED, candidate, self.LEGACY_FORMATTED.replace("= 1", "= 2"),
        ))

    def test_removing_unformatted_code_from_a_legacy_file_passes(self):
        candidate = "fun kept() = 1\n"
        self.assertEqual([], self.formatting_case(self.LEGACY, self.LEGACY_FORMATTED, candidate, candidate))

    def test_unformatted_addition_to_a_legacy_file_fails(self):
        candidate = self.LEGACY + "fun added( ) = 3\n"
        self.assertEqual(
            ["app/src/main/kotlin/Legacy.kt: formatter rewrites 2 lines, base 1"],
            self.formatting_case(
                self.LEGACY, self.LEGACY_FORMATTED, candidate, self.LEGACY_FORMATTED + "fun added() = 3\n",
            ),
        )

    def test_new_file_must_be_fully_formatted(self):
        self.assertEqual(
            ["app/src/main/kotlin/Legacy.kt: formatter rewrites 1 lines, base 0"],
            self.formatting_case(None, None, "fun added(){}\n", "fun added() {}\n"),
        )


if __name__ == "__main__":
    unittest.main()
