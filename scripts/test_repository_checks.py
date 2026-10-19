# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
"""Regression coverage for checks that can block a contributor's commit."""

import unittest
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

import scripts.check_repository as checker
from scripts.check_repository import (
    Snapshot,
    budget_problem,
    forbidden_imports,
    header_problems,
    link_problem,
    literal_dependencies,
    markdown_targets,
    skill_problems,
)


class RepositoryChecksTest(unittest.TestCase):
    def test_large_file_gate_blocks_new_files_and_growth_but_allows_legacy_reduction(self):
        self.assertTrue(budget_problem(101, None, 100))
        self.assertTrue(budget_problem(201, 200, 100))
        self.assertFalse(budget_problem(199, 200, 100))
        self.assertFalse(budget_problem(100, None, 100))

    def test_existing_copyright_cannot_be_replaced_by_fork_credit(self):
        before = "/*\n * © Rukamori\n * GPL-3.0 License\n */\npackage example\n"
        replaced = before.replace("© Rukamori", "© Someone Else")
        supplemented = before.replace(" * GPL", " * Portions © vossgraves\n * GPL")
        self.assertTrue(header_problems("app/src/main/kotlin/Example.kt", replaced, before, False))
        self.assertFalse(header_problems("app/src/main/kotlin/Example.kt", supplemented, before, False))

    def test_new_kotlin_requires_license_without_relicensing_vendored_files(self):
        self.assertTrue(header_problems("app/src/main/kotlin/New.kt", "package example", "", True))
        self.assertFalse(header_problems("third_party/example/New.kt", "package example", "", True))

    def test_module_boundary_matches_package_not_a_similarly_named_package(self):
        source = "import app.ui.Screen\nimport app.uihelper.Model\nimport app.ui.*\n"
        self.assertEqual(["app.ui.*", "app.ui.Screen"], forbidden_imports(source, ["app.ui"]))

    def test_doc_links_allow_external_and_fragments_but_reject_escape_and_missing_files(self):
        files = {"docs/guide.md", "AGENTS.md", "app/src/main/Example.kt"}
        self.assertIsNone(link_problem("docs/guide.md", "../AGENTS.md#commands", files))
        self.assertIsNone(link_problem("docs/guide.md", "https://example.invalid/reference", files))
        self.assertIsNone(link_problem("docs/guide.md", "#commands", files))
        self.assertIsNotNone(link_problem("docs/guide.md", "../../private.md", files))
        self.assertIsNotNone(link_problem("docs/guide.md", "missing.md", files))

    def test_code_examples_are_not_treated_as_real_markdown_links(self):
        text = "[real](../AGENTS.md)\n```md\n[example](not-a-file.md)\n```\n"
        self.assertEqual(["../AGENTS.md"], markdown_targets(text))

    def test_skill_requires_matching_frontmatter_and_nonempty_workflow(self):
        path = ".factory/skills/maintain-docs/SKILL.md"
        valid = "---\nname: maintain-docs\ndescription: Maintain documentation.\n---\nCheck links and commands."
        self.assertFalse(skill_problems(path, valid))
        self.assertTrue(skill_problems(path, valid.replace("name: maintain-docs", "name: another-skill")))
        self.assertTrue(skill_problems(path, "---\nname: maintain-docs\n---\n"))

    def test_dependency_parser_does_not_treat_compiler_arguments_as_maven_artifacts(self):
        source = 'freeCompilerArgs.add("plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$path")'
        self.assertEqual([], literal_dependencies(source))

    def test_dependency_parser_checks_direct_and_flavor_specific_declarations(self):
        source = 'implementation("org.json:json:20250517")\nadd("gmsImplementation", "example:library:1.0")\nadd("implementation", "example:other:2.0")'
        self.assertEqual(
            [("org.json:json", "20250517"), ("example:library", "1.0"), ("example:other", "2.0")],
            literal_dependencies(source),
        )

    def temporary_repository(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        subprocess.run(["git", "init", "-q", str(root)], check=True, capture_output=True)
        return root

    def test_staged_validation_cannot_be_hidden_by_unstaged_repairs(self):
        root = self.temporary_repository()
        path = root / "dependency.gradle.kts"
        path.write_text('implementation("example:library:+")')
        subprocess.run(["git", "-C", str(root), "add", path.name], check=True)
        path.write_text('implementation("example:library:1.0")')
        with patch.object(checker, "ROOT", root), Snapshot(staged=True) as staged, Snapshot() as working:
            self.assertIn(':+")', staged.text(path.name))
            self.assertIn(':1.0")', working.text(path.name))

    def test_deleted_worktree_documents_are_not_valid_link_targets(self):
        root = self.temporary_repository()
        path = root / "guide.md"
        path.write_text("# Existing guide")
        subprocess.run(["git", "-C", str(root), "add", path.name], check=True)
        path.unlink()
        with patch.object(checker, "ROOT", root), Snapshot() as snapshot:
            self.assertNotIn(path.name, snapshot.files)
            self.assertIsNotNone(link_problem("AGENTS.md", path.name, snapshot.files))

    def test_batched_git_reads_respect_utf8_and_binary_blob_boundaries(self):
        root = self.temporary_repository()
        (root / "first.txt").write_bytes("© upstream\nλ\0value\n".encode())
        (root / "second.txt").write_text("separate object")
        subprocess.run(["git", "-C", str(root), "add", "first.txt", "second.txt"], check=True)
        with patch.object(checker, "ROOT", root), Snapshot(staged=True) as snapshot:
            self.assertEqual("© upstream\nλ\0value\n", snapshot.text("first.txt"))
            self.assertEqual("separate object", snapshot.text("second.txt"))

    def test_submodule_pointers_are_not_read_as_blobs(self):
        root = self.temporary_repository()
        sha = "0dc0699899d9e4268dad8b696fba0e6a60727e3c"
        subprocess.run(
            ["git", "-C", str(root), "update-index", "--add", "--cacheinfo", f"160000,{sha},IconPack"],
            check=True,
        )
        with patch.object(checker, "ROOT", root), Snapshot(staged=True) as snapshot:
            self.assertIsNone(snapshot.size("IconPack"))
            self.assertEqual("", snapshot.text("IconPack"))


if __name__ == "__main__":
    unittest.main()
