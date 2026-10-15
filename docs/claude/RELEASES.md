# Nightly and Stable release procedure

## Build

Use the repository workflow and its existing signing secrets. Never commit or restore a keystore.

```bash
./gradlew assembleGmsMobileUniversalDebug
./gradlew :app:testGmsMobileUniversalDebugUnitTest
```

There are two channels: Nightly (`dev`) and Stable (`main`). For a Nightly candidate, inspect the GitHub Actions run for the `dev` branch and confirm the artifact is produced from the intended head SHA. `Nightly build` (`nightly.yml`) publishes an `N<yyyyMMddHHmm>` prerelease with `app-<dist>-<device>-<abi>-nightly.apk` assets, keeps the newest five, and uploads `nightly.json` to the fixed `update-feed` release; it must preserve the historical signing identity. `Bump to new version` (`release.yml`) is run by hand, publishes the Stable release and uploads `stable.json` to the same `update-feed` release. Never delete the `update-feed` release: the in-app updater reads those manifests first, without a key, and only falls back to the GitHub API (cached, with backoff on 403/429) when a manifest is missing.

Note what gates a release: the unit-test workflow is separate and the publishing job depends only on `check` and `build`, so a red test run does not stop a Nightly prerelease from going out. Every prerelease the updater can see is a build users may install; treat a published tag as shipped. The `versionCode` is the commit count and the updater requires it to be strictly higher, so a new build must sit on top of the last published one.

## Canary retirement

The Canary channel (`canary` branch, `C`-tagged prereleases) is retired, and `canary.yml` has been deleted. The app has no Canary channel any more: a stored `CANARY` choice migrates to Nightly and the Canary-only preference keys are cleared, and the Stable filter ignores leftover `C` tags.

- Final `C202610141722` shipped at `a1a04ea023192d8de03332e5a933bafc3224f8cc`; PR #196 merged into Canary. That historical build used a Nightly lock. The fix shipped in N202610151104 honors explicit Stable/Nightly choices instead; only an unset choice uses the build default.
- `8011d6556` retired the workflow. Earlier continuation `e0ada1758` was green (build 38599097324, tests 38599123903). Those runs do not validate later local follow-ups.
- The `canary` branch is frozen as history. Do not push to it again.
- Never delete the last `C` release or the `update-feed` release and its manifests by hand.

## Delivered baseline and current YouTube follow-up gate

PR #198 delivered the channel/settings/graph/DSP/native/USB and language/AI/lifecycle
follow-ups. Its final head `e857f7131ee269ff5d60e4c156aa5bf90e100c3f` passed separate
Build Pull Request run 38715736467 and Unit tests run 38715865943:
835 passed, 0 failed, 0 skipped, including pinned IPADIC/production-tokenizer coverage.
History-preserving dev merge `643a35fd09888769a4a2d588324398d68f0d6fb5` published automatic
Nightly N202610151104 / build 6833 with six shipping APKs and the keyless manifest.
Inherited lint reported 318 Error / 745 Warning / 43 Hint under `abortOnError=false`;
this is not lint-clean or device evidence.

The new finite-playlist/login/InnerTubeX follow-up is local uncompiled source based on
that released merge. Focused tests, independent changed-path review and final remote
execution are pending; historical 835 passes cannot be reused as new validation.
Required new exact-head parallel gates:
`Build Pull Request` / `build` (GMS mobile universal Debug assembly + lint) and
`Unit tests` / `Compile and run unit tests` (exact `:app:testGmsMobileUniversalDebugUnitTest`).
Keep PR read-only caches and trusted test-cache writes; local profiles/reports are artifacts,
not external build-scan uploads. Task discovery is unnecessary.

Create a new open draft PR into **dev**, never reuse merged PR #198 or #196. After green final-head
checks, use the approved history-preserving dev merge, then verify Nightly assets and keyless
manifest/version/source URLs. Do not push main/canary or repeat the already-completed secret
deletions. Preserve actual Release shrinking/signing/native ABI checks and existing matrices:
ordinary build has two variants, Nightly six and Stable seven plus reproducibility.
Debug green is not full release-packaging evidence.

Baseline native/pin cleanup saved 34,228 net tracked text lines; dead-island cleanup saved
4,542 more: **38,770 before new code/docs**, not a guaranteed overall net reduction or a
100,000-line promise. Do not delete active/generated assets or locales to meet a quota.

Historical 2026-10-06 Home/Listen Together evidence was 453 tests/71 suites and mobile/TV
Debug assembly, not current-head validation. The software emulator did not produce usable
Home screenshots or completed room UI interactions. Historical protocol checks were bounded
synthetic requests, not synchronized audio or proof of host eligibility. No current device QA
has run; the user explicitly waived it for this continuation. This is an accepted limitation,
not a new blocking hardware gate or a claim of smoothness/visual parity.

## Device QA reference (not run for this continuation)

1. Install over the previous build to verify signing and migration.
2. Start a queue with at least three tracks from YouTube fallback and an enabled external source.
3. Test crossfade disabled, enabled, paused during fade, manual next/previous during fade, repeat-one, queue replacement, sleep timer, Bluetooth route changes, and screen/background transitions.
4. Confirm the second and third tracks continue without pressing Play. Capture logcat around any `STATE_BUFFERING`, `STATE_ENDED`, `PlaybackException`, source switch, or codec recovery.
5. Test low-end or battery-saver conditions with canvas/artwork and lyrics both enabled and disabled. Compare heat, buffer stalls, and startup latency.
6. Open Integration, enable Spotify history sync, open History, refresh, search, sign out, and verify the source disappears without background playback.
7. Walk the settings root, every provider page, settings search anchors, dialogs, sliders, toggles, and back navigation.
8. Verify update installation from the prior Nightly artifact and from the last Canary artifact before promoting to `main`.

Stable promotion is separate from the approved dev merge/Nightly delivery. Record available
evidence and the explicit no-device limitation; do not present compilation as physical QA.
