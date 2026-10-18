// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 vossgraves
const assert = require("node:assert/strict");
const test = require("node:test");
const { labelsForIssue } = require("./issue-labels.cjs");

test("form labels are derived from the affected-area answer, not crash words in logs", () => {
  const issue = {
    labels: [{ name: "BUG" }],
    body: "### Affected area\n\nDownloads\n\n### Logs\n\nAn unrelated crash string and Login / account / proxy",
  };
  assert.deepEqual(labelsForIssue(issue, "opened"), ["needs-triage", "bug", "area:downloads"]);
});

test("editing a triaged issue does not restore a removed needs-triage label", () => {
  assert.deepEqual(labelsForIssue({ labels: [{ name: "priority:normal" }], body: "" }, "edited"), []);
});

test("unknown or command-like issue text never becomes a label or executable input", () => {
  const body = "### Affected area\n\n${process.exit(1)}\n\nCrash / startup / installation";
  assert.deepEqual(labelsForIssue({ labels: [], body }, "opened"), ["needs-triage"]);
});
