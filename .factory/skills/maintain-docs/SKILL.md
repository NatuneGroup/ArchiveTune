---
name: maintain-docs
description: Update ArchiveTune's maintained Markdown after behavior, build, workflow or release changes. Use when documentation is stale or a code change affects agent instructions.
license: GPL-3.0-only
---

# Maintain project documentation

1. Read `AGENTS.md` and the relevant subsystem document. Keep fork invariants in
   `AGENTS.md`; use `CLAUDE.md` as a concise entry point, not another copy.
2. Put current subsystem knowledge in `docs/claude/`, development/runbook details
   in `docs/engineering/`, and reusable workflows beside this skill.
3. Inspect the actual code/configuration before updating commands or feature claims.
   A historical green run does not validate a changed candidate.
4. Update only documents affected by the change. Keep comments focused on why.
   Do not add session transcripts, duplicate plans, speculative claims or filler tests.
5. Preserve historical evidence and copyright/provenance. Distinguish implementation,
   compilation, synthetic tests, device behavior and physical-audio evidence.
6. Run `python3 scripts/check_repository.py --worktree` to validate links, skill
   frontmatter, documented CI commands and repository contracts. Inspect failures;
   do not disable the checks or erase evidence to make them pass.
7. If a change needs Gradle/emulator verification, use the authorized build host.
   Never start a local JVM on the constrained Termux host or expose credentials.

Finish with the affected paths, the checks actually run and any remaining evidence gaps.
