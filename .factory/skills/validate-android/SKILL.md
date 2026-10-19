---
name: validate-android
description: Validate an ArchiveTune Android change with bounded lightweight checks and exact-candidate remote build/test evidence. Use before declaring Kotlin, dependency, native or playback changes ready.
license: GPL-3.0-only
---

# Validate an Android change

1. Read `AGENTS.md`; record the candidate commit and uncommitted changes. Do not
   initialize/move local gitlinks or include agent reports in a source snapshot.
2. Run `python3 scripts/check_repository.py --worktree` and, when changing its
   checks, `python3 -m unittest discover -s scripts -p 'test_*.py'`.
3. On the authorized build host, verify JDK 21, SDK 37, the pinned NDK and checked-in
   submodule revisions. Stop if SSH access, resources or toolchains are unavailable.
4. Run the narrow relevant test first, for example
   `./gradlew --rerun-tasks :app:testGmsMobileUniversalDebugUnitTest --tests moe.rukamori.archivetune.logcat.DiagnosticRedactionTest`.
   A cached, skipped or compile-only task is not test-execution evidence.
5. Before integration, obtain the separate documented build/lint and unit-test gates
   on the exact candidate. Keep PR caches read-only and avoid per-feature CI churn.
6. Use `docs/android-qa.md` for device interaction. Record the real target and
   interaction; APK assembly does not establish UI, login, playback or USB behavior.
   Use the synthetic-file harness on a clean emulator for auth-free player checks;
   verify the installed APK hash instead of assuming it matches the checkout.
7. Preserve complete failure output and sanitized reports. Fix failures without
   weakening tests, silently retrying flaky assertions or changing unrelated policies.

Report passed/failed/unrun checks explicitly. Never claim a report uploaded or a
release shipped unless the relevant operation returned confirmed evidence.
