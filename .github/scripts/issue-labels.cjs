// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 vossgraves
const areas = new Map([
  ["Music player / now playing", "area:playback"],
  ["Playback queue / radio / cache", "area:playback"],
  ["Library / playlists / local media", "area:library"],
  ["Lyrics / translation / romanization", "area:lyrics"],
  ["Downloads", "area:downloads"],
  ["Settings / preferences", "area:ui"],
  ["UI / visuals / theme", "area:ui"],
  ["Login / account / proxy", "area:accounts"],
  ["Crash / startup / installation", "area:crash"],
]);

function labelsForIssue(issue, action) {
  const labels = new Set();
  const existing = (issue.labels || []).map((label) =>
    (typeof label === "string" ? label : label.name).toLowerCase(),
  );
  if (action === "opened" || action === "reopened" || existing.length === 0) {
    labels.add("needs-triage");
  }
  if (existing.includes("bug")) labels.add("bug");
  if (existing.includes("enhancement")) labels.add("enhancement");
  const area = (issue.body || "").match(/^### Affected area\r?\n\s*\r?\n([^\r\n]+)/m);
  if (area && areas.has(area[1].trim())) labels.add(areas.get(area[1].trim()));
  return [...labels];
}

module.exports = { labelsForIssue };
