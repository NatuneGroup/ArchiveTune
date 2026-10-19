# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
from pathlib import Path
import tempfile
import unittest

from scripts.check_test_stability import read_results, summarize


class TestStabilityTest(unittest.TestCase):
    def test_a_later_pass_never_erases_the_first_failure(self):
        failed = {"example.Test#case": {"status": "failed", "seconds": 0.2}}
        passed = {"example.Test#case": {"status": "passed", "seconds": 0.1}}
        summary = summarize([failed, passed], [1, 0])
        self.assertFalse(summary["passed"])
        self.assertEqual(["example.Test#case"], summary["flakyCases"])
        self.assertEqual([1], summary["failedGradleIterations"])

    def test_skipped_or_missing_execution_cannot_be_reported_as_stable(self):
        skipped = {"example.Test#case": {"status": "skipped", "seconds": 0.0}}
        self.assertFalse(summarize([skipped, skipped], [0, 0])["passed"])
        self.assertFalse(summarize([skipped, {}], [0, 0])["passed"])

    def test_empty_report_or_build_without_testcases_is_not_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                read_results(root)
            (root / "TEST-empty.xml").write_text('<testsuite name="empty" tests="0"/>')
            with self.assertRaises(ValueError):
                read_results(root)


if __name__ == "__main__":
    unittest.main()
