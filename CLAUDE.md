# CLAUDE.md

ArchiveTune — a Kotlin/Compose Android music player. Multi-source: Tidal, Deezer,
Qobuz and Telegram serve audio, Spotify serves catalog metadata (plus an opt-in,
experimental streaming source on a separate account), YouTube is the final fallback.

**Read [AGENTS.md](AGENTS.md) first.** It carries the fork invariants — protected
files, the removed phone-home surface, signing rules, submodule handling. Nothing
here overrides it.

## Build

JDK 21, compileSdk 37, Gradle wrapper 9.6.1. Flavors: `gms|foss` × `mobile|tv` ×
`universal|arm64|…`.

```
./gradlew assembleGmsMobileUniversalDebug          # compile check
./gradlew :app:testGmsMobileUniversalDebugUnitTest # fork contract tests
```

CI is the only compile signal if you cannot build locally: `Build APKs` (`build.yml`,
on push to `main`/`dev`) builds `gms-mobile-arm64` and `gms-tv-universal`; a push
to `dev` also runs `Nightly build` (`nightly.yml`), which publishes the N-tagged
prerelease and the `nightly.json` update-feed manifest the in-app updater reads, and
`release.yml` (run by hand) publishes Stable releases and `stable.json`. Unit tests are their own
workflow (`tests.yml`), manual-only (`workflow_dispatch`) to keep pushes fast; the APK
jobs assemble releases and never compile `app/src/test`, so start it by hand after
integration and before promoting `dev`. `Build Pull Request` / `build` assembles
GMS/mobile/universal Debug plus lint on a GitHub-hosted runner. Run that workflow and
`Unit tests` / `Compile and run unit tests` concurrently on the exact candidate head.
Tests invoke the documented task directly; both workflows retain local Gradle profiles
and reports, without external build scans. PR caches remain read-only. Debug checks
do not replace Release shrinking, signing or native packaging evidence. Nightly
ships six variants (including FOSS mobile and optional automix); Stable ships seven.
Neither workflow ships FOSS TV.

## Branches

`dev` is the integration branch and builds the N-tagged Nightly channel; `main` is
Stable. There are only these two channels. The Canary channel is retired: the last
C-tagged release shipped, `canary.yml` is deleted, the `canary` branch is frozen as
history, and the app migrates a stored Canary channel to Nightly — see
[docs/claude/RELEASES.md](docs/claude/RELEASES.md).

## Subsystems

| Doc | What it covers |
|---|---|
| [docs/extraction.md](docs/extraction.md) | YouTube stream resolution: client order, PO tokens, backoff, yt-dlp fallback |
| [docs/lyrics.md](docs/lyrics.md) | The three renderers, the word sweep, which surface uses which |
| [docs/sponsorblock.md](docs/sponsorblock.md) | Segment lookup and skipping |
| [docs/tv.md](docs/tv.md) | Android TV / Fire TV: detection, focus, what is known to be missing |
| [docs/fork-divergence.md](docs/fork-divergence.md) | What 4nx3b's fork has that we do not and the reverse, by tree comparison rather than commit count |
| [docs/source-logins.md](docs/source-logins.md) | Provider sign-in, YouTube embedded recovery/private verification, credential proof limits and the Qobuz app-secret search |
| [docs/instance-racing.md](docs/instance-racing.md) | Tidal public instances: where they come from, how the resolver races them, health and cooldowns |
| [docs/telegram-native.md](docs/telegram-native.md) | The TDLight engine: why the native library is downloaded, the digest pinning, schema drift from 1.8.56 |
| [docs/spotify-native-playback.md](docs/spotify-native-playback.md) | Playing Spotify tracks with no YouTube release: shipped ISRC resolution, and the unshipped native-id design |
| [docs/spotify-streaming.md](docs/spotify-streaming.md) | The experimental Spotify audio source: the separate streaming account, the two resolve methods, DRM and cache routing |
| [docs/REMOVED_RUKAMORI_COMPONENTS.md](docs/REMOVED_RUKAMORI_COMPONENTS.md) | What the 2026-08 cleanup deleted, and why not to restore it |

## Task state

The channel/default, settings text/shared restart modal, bounded acoustic graph,
existing DSP pin updates and audited native/USB cleanup are implemented follow-ups, including Unicode/phonetics, SimpMusic parity, optional JP-region dictionary
automation, settings/target-bound AI results and downloader/room/account lifecycle repairs.
Those changes shipped through PR #198 at `643a35fd09888769a4a2d588324398d68f0d6fb5`:
Nightly `N202610151104` / build 6833, with separate green build/tests and 835 passed,
0 failed, 0 skipped. Inherited permissive lint was not lint-clean; no device QA is claimed.

The current YouTube follow-up is integrated local source, uncompiled and unreleased.
Finite browse queues preserve occurrences and paging; unknown legacy recovery is conservative.
Strict complete-list actions/sync and client shuffle do not claim exact server shuffle.
Login-only recovery addresses an embedded `net::ERR_UNKNOWN_URL_SCHEME` Music store intent
with one same-WebView return, private verified candidates and YouTube-scoped logout.
The anonymous MetrolistGroup InnerTubeX `v0.9.2` pin is separate from those app fixes.
The 121 focused test methods are authored and integrated, UNEXECUTED.
Independent changed-path review and exact-head remote BUILD/TESTS remain pending.
Historical results do not validate new code or live login/UI/audio behavior. The no-local-JVM/build
policy is scoped to this constrained mission, not ordinary contributor setup. Keep gitlinks
uninitialized/unchanged here and never publish another Canary.

[docs/claude/](docs/claude/) carries what survives between sessions:
[ARCHITECTURE.md](docs/claude/ARCHITECTURE.md) (playback and source boundaries),
[SETTINGS.md](docs/claude/SETTINGS.md) (routes, search anchors, Apple Music status),
[RELEASES.md](docs/claude/RELEASES.md) (release + device QA),
[BACKLOG.md](docs/claude/BACKLOG.md).

## House rules

- Comments explain *why*, not *what*. Most code needs none.
- Player styles are self-contained (`bitchord/`, `simpmusic/`, `tiktok/`). The one
  exception is the swept lyric renderer — see [docs/lyrics.md](docs/lyrics.md).
- New `.kt` files keep the GPL header banner. Never rewrite an existing file's
  copyright banner to credit a different project. No formatter task; match the file
  around you.
- User-facing strings go in `strings.xml`/`archivetune_strings.xml`, never inline.
- Never restore anything in `docs/REMOVED_RUKAMORI_COMPONENTS.md` without reading
  why it went.
