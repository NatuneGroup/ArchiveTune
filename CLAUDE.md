# CLAUDE.md

ArchiveTune — a Kotlin/Compose Android music player. Multi-source: Tidal, Deezer,
Qobuz and Telegram serve audio, Spotify serves catalog metadata only, YouTube is
the final fallback.

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
to `canary` runs `Canary build` (`canary.yml`), which publishes the C-tagged
prerelease the in-app updater takes, and a push to `dev` also runs `Nightly
(canary) build` (`nightly.yml`, N-tagged). Unit tests are their own workflow
(`tests.yml`, on `main`/`dev`/`canary`) because the APK jobs assemble releases and
never compile `app/src/test`. There is no `foss`+`tv` variant, deliberately — the
fork ships GMS-only.

## Branches

`canary` is the integration branch and builds the C-tagged Canary channel. It merges
into `dev` only after the device QA in [docs/claude/RELEASES.md](docs/claude/RELEASES.md);
`dev` builds the N-tagged Nightly channel, and `main` is stable.

## Subsystems

| Doc | What it covers |
|---|---|
| [docs/extraction.md](docs/extraction.md) | YouTube stream resolution: client order, PO tokens, backoff, yt-dlp fallback |
| [docs/lyrics.md](docs/lyrics.md) | The three renderers, the word sweep, which surface uses which |
| [docs/sponsorblock.md](docs/sponsorblock.md) | Segment lookup and skipping |
| [docs/tv.md](docs/tv.md) | Android TV / Fire TV: detection, focus, what is known to be missing |
| [docs/fork-divergence.md](docs/fork-divergence.md) | What 4nx3b's fork has that we do not and the reverse, by tree comparison rather than commit count |
| [docs/source-logins.md](docs/source-logins.md) | The four WebView sign-in screens: what each captures, why nothing saves unverified, the Qobuz app-secret search |
| [docs/instance-racing.md](docs/instance-racing.md) | Tidal public instances: where they come from, how the resolver races them, health and cooldowns |
| [docs/addons.md](docs/addons.md) | Declarative HTTPS add-on manifest and search/stream response contract |
| [docs/telegram-native.md](docs/telegram-native.md) | The TDLight engine: why the native library is downloaded, the digest pinning, schema drift from 1.8.56 |
| [docs/spotify-native-playback.md](docs/spotify-native-playback.md) | Playing Spotify tracks with no YouTube release: shipped ISRC resolution, and the unshipped native-id design |
| [docs/REMOVED_RUKAMORI_COMPONENTS.md](docs/REMOVED_RUKAMORI_COMPONENTS.md) | What the 2026-08 cleanup deleted, and why not to restore it |

## Task state

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
