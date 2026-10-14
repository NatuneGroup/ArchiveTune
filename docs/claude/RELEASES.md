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

The Canary channel (`canary` branch, `C`-tagged prereleases, `canary.yml`) is retired. The app has no Canary channel any more: a stored `CANARY` choice migrates to Nightly and the Canary-only preference keys are cleared, and the Stable filter ignores leftover `C` tags. Remaining steps, in order:

1. Push the integrated head to `canary` once so `canary.yml` publishes the last `C` release with the migration (that build must be Nightly-locked).
2. In a commit on top of it, delete `canary.yml`, then fast-forward `dev` to that commit so `nightly.yml` publishes the next `N` build with a higher `versionCode` and `nightly.json`.
3. Leave the `canary` branch frozen as history and never delete the old `C` releases.

## Device QA

1. Install over the previous build to verify signing and migration.
2. Start a queue with at least three tracks from YouTube fallback and an enabled external source.
3. Test crossfade disabled, enabled, paused during fade, manual next/previous during fade, repeat-one, queue replacement, sleep timer, Bluetooth route changes, and screen/background transitions.
4. Confirm the second and third tracks continue without pressing Play. Capture logcat around any `STATE_BUFFERING`, `STATE_ENDED`, `PlaybackException`, source switch, or codec recovery.
5. Test low-end or battery-saver conditions with canvas/artwork and lyrics both enabled and disabled. Compare heat, buffer stalls, and startup latency.
6. Open Integration, enable Spotify history sync, open History, refresh, search, sign out, and verify the source disappears without background playback.
7. Walk the settings root, every provider page, settings search anchors, dialogs, sliders, toggles, and back navigation.
8. Verify update installation from the prior Nightly artifact (and, until the retirement above is finished, from the last Canary artifact) before promoting to `main`.

Do not promote `dev` to `main` until the playback and installation checks pass on a physical device.
