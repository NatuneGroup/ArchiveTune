# AGENTS.md — ArchiveTune (vossgraves fork of 4nx3b/ArchiveTune)

Active development is on `canary`, the integration branch (feature PRs land there
first; `dev` follows it only after device QA and builds the Nightly channel, `main`
is stable). The fork tracks both upstreams, `4nx3b/ArchiveTune` and
`rukamori/ArchiveTune`, at `4nx3b/dev` and `rukamori/dev`. Any agent working here
must preserve the invariants below.

## Fork invariants — never break

- **Presentation**: Material 3 Expressive is the default interface style. Apple Music is selectable in Appearance and uses the existing experience preference to coordinate player, library, headers and tab bar in one atomic edit. Playback providers and source selection remain independent. Compact mini-player and bottom-pill layouts are optional and do not override Apple Music's coordinated experience. Bottom search and scroll-hidden navigation are optional; keep the current settings UI rather than importing upstream's settings redesign.
- **Tidal source**: `app/src/main/kotlin/moe/rukamori/archivetune/tidal/` (`TidalAudioProvider`, `TidalAccountManager`, `TidalInstanceHealthManager`, `TidalDns`, `TidalArtworkProvider`), Tidal settings/login UI, `utils/tidal/`; instance racing documented in `docs/instance-racing.md`. Live progressive-DASH streams use the `tidal-dash://` scheme routed in `MusicService`.
- **Multi-source audio**: providers live in top-level packages `tidal/`, `deezer/` (DeezerCrypto + Media3 decrypting DataSource), `qobuz/` (+ `QobuzBackupProvider` via the kouzu.in mirror), `spotify/`; shared contract (`DirectStream`, `TitleMatch`, source priority) in `audiosource/`. Playback resolution goes through `resolveMultiSourceDataSpec` in `playback/MusicService.kt`; YouTube is the final fallback. Do not rewire playback around this.
- **Audio authenticity**: decoded PCM analysis is opt-in and bounded. It reports spectral suspicion, never proof of studio provenance; missing, short, silent or inconclusive evidence remains unknown. Evidence uses reported sample rates, never quality-tier guesses, and cached evidence stays source-bound. Observed decoded PCM format is track-scoped and is not a measurement of physical device/DAC output. Preserve bit-perfect bypass and caller cancellation. A rejected attempt must not reuse its cached URL, discard playback position or bypass the established source chain.
- **Compact transitions**: `playback/automix/` supplies bounded local feature analysis to the existing smart-transition path. Standard packages contain neither ONNX models nor ONNX native libraries; `-Pautomix=true` explicitly includes the optional models/runtime. Keep analysis cancellation and source-aware cache bounds; do not add bundled Python.
- **Spotify is catalog-only**: `spotifycore/` + app `spotify/` provide metadata, artwork, search, playlist import and track-to-YouTube identification. Spotify is never an `AudioSourceType`.
- **Playback client policy**: `AutoChoosePlaybackClientKey` + `YTPlayerUtils` own bounded YouTube client selection/fallback and video-scoped PO tokens, and `EchoStreamResolver` is the last resort behind them — keep it there. The extractor tiers in `playback/stream/` are ordered in `ResolveAudioStreamUseCase.fallbackTiers` and nowhere else, but are **not wired** behind `YTPlayerUtils` yet (groundwork — see `docs/extraction.md`), so nothing may treat them as live or drop echo on the assumption that they have replaced it. Manual mode must remain available.
- **Telegram streaming**: `telegram/` (TDLib wrapper + `TelegramDataSource`), browse/settings/login UI. Playback routed by the `telegram://` branch in `MusicService`'s `SchemeRoutingDataSource`, independent of the multi-source chain. Channels materialise as local playlists (`LPtg<chatId>`); artwork via the Coil `tgart://` fetcher. api_id/hash via `BuildConfig` with public Telegram Desktop fallback.
- **CI signing**: release/nightly and release-shaped CI builds sign with the existing (historical) release keystore via GitHub Secrets `KEYSTORE`/`KEY_ALIAS`/`KEYSTORE_PASSWORD`/`KEY_PASSWORD` — same signing identity as all prior builds, so updates keep installing in place. No workflow may fall back to a committed `app/persistent-debug.keystore`; the file was removed from the tree and must not be restored (the key material remains public in git history — accepted residual risk, maintainer decision 2026-08-26; do NOT rotate to a fresh key without an explicit user-facing migration announcement, it force-reinstalls every user). Local debug builds use AGP's default debug keystore.
- **Listen Together**: LAN plus exactly the public servers listed in `listentogether/ListenTogetherServers.kt` (the two vivi JSON servers and Metrolist's protobuf-only The Meowery) — nothing else. No koiverse REST/WS path and no `*.koiverse.cloud` / `raw.githubusercontent.com/koiverse/*` call anywhere in the tree. The Meowery never takes a JSON frame: the codec is pre-negotiated per host, the app sends no `client_capabilities` frame, `buffer_ready` is the catch-up mechanism, and the host has no chat relay (chat is JSON-server-only).
- **Protected files**: `applicationId` (`moe.rukamori.archivetune`) is fork identity — never adopt upstream changes to it. `Koiverse.jks*`, `ArchiveTuneKoiverseServer.txt`, `DataServer.txt` were removed and must never be restored.

## Modules & submodules

- Gradle modules: `:app :core :spotifycore :canvas :jiosaavn :lastfm :musixmatch :shazamkit :morideobfuscator :lyrics:*`.
- Submodules: `core` → **vossgraves/core** (NewPipeExtractor-based InnerTube client; a fork of rukamori/core that deliberately carries no `NetworkGatekeeper`, pinned on `feat/podcast-port` for the podcast models), `lyrics` → **4nx3b/lyrics**, `IconPack` → rukamori, `morideobfuscator` → rukamori. Commit + push inside a submodule first, then pin the gitlink; never leave a dirty or unpushed pointer. Never move `core` to a `rukamori/core` revision — its `NetworkGatekeeper` defaults to `connectionBlocked = true` and throws on every request.

## Build & test

- JDK 21, Android SDK (compileSdk 37), Gradle wrapper 9.6.1.
- Flavor matrix: gms/foss × mobile/tv × universal/arm64/x86_64. Debug check: `./gradlew assembleGmsMobileUniversalDebug`.
- Fork contract tests (palette/crossfade/artwork/multi-source/presence/telegram — sources in `app/src/test/`): `./gradlew :app:testGmsMobileUniversalDebugUnitTest`. Run after any merge or feature change.
- Keep the GPL header banner on new `.kt` files. No formatter task — match surrounding style.

## Dependency gotchas

- `settings.gradle.kts` declares a GCS mirror of Maven Central **before** `mavenCentral()` — do not reorder (Maven Central 429-rate-limits CI).
- JitPack is scoped via `exclusiveContent` to an allow-list of `com.github.*` groups (TeamNewPipe, PRDownloader, jaudiotagger, MetrolistGroup…). A new `com.github.*` dependency fails until its group is added.
- The embedded Python/yt-dlp layer (Chaquopy) was removed on 2026-08-26: no Python is bundled, and nothing in the resolution path may grow a dependency on it. YouTube stream resolution runs in-process through the compiled InnerTube core in `YTPlayerUtils` (BotGuard/QuickJS PO tokens), with `EchoStreamResolver` still the last resort — the extractor tiers in `playback/stream/` are groundwork and are not wired behind it yet (`docs/extraction.md`). The external YTDLnis yt-dlp plugin APK is the only yt-dlp route, and never bundled. Upstream still carries Chaquopy — expect merge conflicts in `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `playback/stream/*` on sync; resolve them by keeping the fork's Python-free shape.

## Upstream sync (state as of 2026-09-27)

- **There is no automation.** `upstream-sync.yml`, `upstream-sync-merge.yml`, `mirror-4nx3b.yml` and the scripts they drove (`scripts/upstream_sync.sh`, `scripts/ai_resolve.py`, `docs/UPSTREAM_SYNC.md`) were deleted in 2026-09 (commit `699d1e390` and the mirror removal), so nothing merges or mirrors branches. The `SYNC_PAT` / `AI_API_KEY` secrets are unused.
- Upstream changes land by **manual comparison**: the rukamori line shares no merge base, so read the upstream tree directly (`git show 4nx3b/dev:<path>`, `git show rukamori/dev:<path>`) and port the behaviour onto canary's current code instead of pasting files over it.
- If any automation is ever put back, it must not tree-replace a branch: that would re-commit `app/persistent-debug.keystore` (still present in 4nx3b's tree), resurrect main's committed-keystore signing fallback, and wipe every deliberate fork divergence (yt-dlp removal, keystore removal, UI rework). Direction decision (merge-based 4nx3b sync vs release-tag mirroring only) is pending with the maintainer. When a fork feature or protected file changes, update the invariants in this file — it is the only description of sync policy left.

## Agent hygiene

- Never commit credentials, tokens, keystores or agent session state (`.swarm/`, `.opencode/`, `.jcode/` stay untracked).
- Local working notes (`ARCHIVETUNE_SESSION_NOTES.md`, `ARCHIVETUNE_CODE_MAP.md`) are scratch memory — never ship them.
