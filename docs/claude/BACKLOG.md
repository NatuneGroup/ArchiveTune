# Prioritized backlog

## Playback and performance

- Collect Perfetto/Media3 analytics for the reported second-track stop and compare crossfade, source resolution, decoder, cache, artwork, and thermal costs.
- Review upstream baseline-profile and preload-loop fixes individually against fork invariants.
- Validate Tidal instance recovery and Apple Music Widevine failure messaging so a failed source falls through without leaving a stalled player.

## Apple Music experience

- Split the current global experience flag into independent playlist, player-menu, lyrics-menu, Home, Library, and navigation options.
- Implement a non-locking Apple Music preset that writes those independent options and does not overwrite later manual choices.
- Add Apple-style Home/library parity and Spotify Recommended for Today where the data source supports it.
- Evaluate upstream public catalog search separately from authenticated Apple playback.

## Spotify and ecosystem

- Expand read-only Recently Played import/presentation only if users need more than the provider limit.
- Do not embed SpotifySync’s hidden WebView playback without an explicit privacy, battery, account, and licensing decision.
- Review Stash playlist/library sync ideas for a user-visible, cancellable, authenticated flow.

## Release

- PR #198 shipped Nightly N202610151104 / build 6833 at merge
  `643a35fd09888769a4a2d588324398d68f0d6fb5`, after separate green build/tests
  (835 passed, 0 failed, 0 skipped; inherited permissive lint was not lint-clean).
  The new finite-playlist/login/InnerTubeX source is local and uncompiled; focused fixtures,
  independent changed-path review and exact-head remote BUILD/TESTS remain pending.
  A new dev PR and history-preserving merge must precede its automatic Nightly/feed
  verification. Never reuse merged #198/#196 or publish another Canary.
- Device/thermal/USB/visual checks are unrun and explicitly waived for this continuation; keep
  that limitation visible. Stable promotion remains separate.

## Unresolved review evidence

Retain the detailed [2026-10-09 audit](../maintenance-review-2026-10-09.md),
[2026-10-10 review](../review-2026-10-10.md), [upstream comparison](../upstream-review-2026-10-04.md)
and [playback checkpoint](../playback-refinements-2026-10-04.md) while their open requirements,
reference pins and sibling ArchivePool delivery remain unresolved. Their dated branch/CI/provider
snapshots are historical, not current service or feature status. In particular:

- Half-silent downloads/seek-background silence need affected-source evidence; request-owned
  downloader staging/latch fixes shipped in N202610151104, not established root-cause closure.
- Preserve per-style seek, codec/Track Info, playlist/Spotify routing, lyrics offset/rendering,
  pill/BitChord/Yuma geometry and Looper requirements until actual current implementation is reviewed.
  Looper now exists; the old “not restored” snapshot is not current status.
- APK cap request (25 MB, preferably 22 MB), CPU/thermal comparison and TV installation reports
  remain unverified on current release artifacts/hardware. Do not remove active fonts/icons/locales
  on the basis of historical size estimates.
- AGPL-derived AMLL tuning (`86200dead`) and unlicensed Tryptify Kotlin provenance still require
  owner decisions in [CREDITS](../CREDITS.md). Cleanup is not relicensing; retain headers/notices.
- A historical Pool read-key exposure was reported; resolve with the owner in the Pool repository,
  not by deleting unrelated app secrets or claiming a rotation. Historical ArchivePool follow-up/
  WAF/deployment work is a separate delivery, not proof it was published.
- Japanese/Unicode/AI/provider/cache and Listen Together/Spotify-account lifecycle changes are
  implemented and source-reviewed with focused fixtures, including real pinned IPADIC data and
  the production tokenizer. Those fixtures executed in PR #198's green 835-test run;
  neither that run nor source checks establish service, all-language or device validation.

The superseded appearance and Home/Listen Together review notes were consolidated into
[SETTINGS](SETTINGS.md), [ARCHITECTURE](ARCHITECTURE.md) and [RELEASES](RELEASES.md).
No current screenshots, exact visual parity or zero-bug/all-language claims follow from them.
