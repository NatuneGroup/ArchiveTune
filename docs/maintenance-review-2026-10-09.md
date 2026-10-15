# Maintenance and audit review — 2026-10-09

Scope: `feat/bitchord-navbar` (head `ac7f8a154`) against the original ArchiveTune/ArchivePool
handoff ZIP and this session's requirements. Canonical upstream is `NatuneGroup/ArchiveTune`.

## Fixed and pushed

**`66624bc8f` — five compile blockers**, none caught by the earlier "brace-balance" checks:

| File | Defect |
|---|---|
| `ui/screens/HomeFeedDesign.kt` | `package moe.rukamori.archivetune.ui.screens` deleted; ~40 Home helpers silently moved to the default package, breaking `SpotifyHomeSurface.kt`, `HomeScreen` and `GlassScreenHeader.kt`. Restored. |
| `ui/screens/HomeFeedDesign.kt:653` | `rememberPreference(CropThumbnailToSquareKey, false)` still called, import had been swapped for `LiquidGlassEnabledKey`. Restored both. |
| `ui/component/LyricsScrollSpring.kt` | `sqrt()` never imported; `animateScrollBy` imported from `foundation.lazy` instead of `foundation.gestures`. |
| `ui/screens/settings/LyricsAnimationSettings.kt:112` | `lrcBounceEnabled` / `onLrcBounceEnabledChange` read but never declared. |
| `ui/component/LyricsV2.kt` | `LyricsLineSpotify` missing `textAlign` while body `:1741` and call site `:1032` still use it. |

**`ac7f8a154` — provenance honesty.** Both lyric files claimed "IDEA PORT ONLY — no upstream code is
copied"; verified false against `amll-dev/applemusic-like-lyrics@86200dead`. `LyricsScrollSpring`
reproduces upstream's interval clamp, stiffness range, 5th-root bias and damping multiplier;
`LyricsEmphasize` reproduces the 1000 ms floor, piecewise `/2000`//`3000` growth, `0.6`/`0.5` weights,
`1.6`/`1.5`/`1.2` last-word boosts, `1.2`/`0.8` caps and `/2.5` stagger. Upstream is **AGPL-3.0-only**,
this fork is **GPL-3.0**. Both headers and `docs/CREDITS.md` now state this. **Decision required:**
relicense those two files AGPL-3.0, replace the constants with independently-derived values, or drop both
techniques. An AGPL-derived work cannot be distributed as GPL-3.0-only.

## Confirmed defects, not yet fixed

- `GlassScreenHeader.glassHeaderSource()` calls `hazeSource(...)` unconditionally even when
  `liquidGlassActive` is false — verify it is a no-op rather than a doubled source.
- `GlassScreenHeaderOverlay` composes `ScreenHeaderHaze(...)` *before* the `!liquidGlassActive` early
  return. Confirm it matches the pre-port per-screen behaviour.
- **HomeViewModel refresh race (worktree fix, unverified):** `HomeRequestGate.runCurrentLoad` serializes
  reloads instead of dropping one during `isLoading`; stale queued generations are skipped, `commit()`
  drops stale publishes, and each implicit reload gets a new generation. Tests cover waiting behind the
  initial load, skipping a superseded queued load, and both commit paths. The focused Gradle test was
  canceled at the user's request to avoid phone load; no pass is claimed.
- `refresh()` watchdog (`HomeViewModel.kt:1108-1119`) force-resets `isRefreshing` without cancelling the
  old job: two refresh jobs can still launch, but their `load()` bodies now serialize on the gate mutex,
  so only the non-stale one runs while the stale generation is dropped at commit.
- `EmphasizedWordOverlay` gets **no reveal mask** when `emphasizeLastWord && !softReveal`
  (`LyricsV2.kt:1634-1667`) — the final word lights instantly.
- `EmphasizedWordOverlay` splits `word.text.toList()`, i.e. per **UTF-16 code unit**. Surrogate pairs,
  combining marks and ZWJ sequences break, so Arabic, Tamil and emoji render wrong and lose kerning.
- The four lyric techniques (`ba1bd9101`, `a8ba5ac72`, `3542431ae`, `1a5752c08`) are wired into
  **`LyricsV2` only**. The request was to make *enhanced* lyrics more fluid; the default renderer is
  `LyricsEnhanced` / `KaraokeLyricsView`. **Open scope gap.**
- `HomeFeedDesign.kt` should still be treated as suspect until `:app` compiles.

## Open requirements

- **Z1** Downloaded songs truncate at ~half — cache span / Range / Content-Length handling in
  `CachedReadWindow`, `CacheDataSource`, PRDownloader. Needs a real download before changing code.
- **Z3** Rewind/fast-forward in *every* player style.
- **Z4** BitChord codec badge alignment; lyrics XML cleanup.
- **Z5** Search / nav / mini-player pills must use the single Appearance pill-style system.
- **Z6** Track Info & Specs (4nx3b `26b407c39`) with Apple + MD3 variants.
- **Z7** Playlist port review — remove leftovers and orphaned callers of the earlier partial port.
- **Z8** Spotify album/artist routing from every entry point, not only Search (`b7a5d6d47`).
- **Z10** Editorial V10 + immersive refinements + `showCodecOnPlayer` on every applicable surface.
- **Z11** Background CPU (user: ~30% vs Spotify's ~15%): lifecycle gates for animations, canvas video,
  visualizers, lyric tickers, polling and automix analysis. **Requires device profiling.**
- **H8** PR #188 (Listen Together hang + Spotify Home borders) is still OPEN and unintegrated.
- **S8** APK is 29.18 MiB against a 25 MB cap (preferably 22 MB). The 1.17 MiB BouncyCastle exclude is
  already in (`b6220f321`). Remaining levers: ~3.6 MB of 18 unidentified 1024×1024 PNGs (needs
  `aapt2 dump resources`), ~0.6 MB ListenTogether avatars (WebP only, with design sign-off), and a 4.2 MB
  variable-font subset on `spatialflow_google_sans_flex.ttf`.
- **Looper port remains requested but not restored.** Historical commit `4525811e5` introduced it; a clean
  reapply is blocked by current API drift in the saved source: it imports missing `LocalVideoPlaybackFailed`,
  passes removed `controlsOnTap` to `InlineVideoPlayer`, and uses removed `onPlaybackAvailabilityChange` on
  `CanvasArtworkPlayer`. Adaptation remains pending; no phone build will be run.

## Verification blockers

- **No build environment.** `gradle.properties` pins `-Xmx6G` because CI is 8 vCPU / 16 GB. Every box
  available is far smaller: `dev.new` is 1 CPU / 1907 MB RAM / 511 MB swap by cgroup. One run produced
  **135,726 `memory.high` throttle events**, `memory.peak` 1.66 GB against a 1.86 GB cap, `oom_kill 0` —
  throttled to death, not OOM-killed. Best reach: 85 tasks, `:app:generateGmsMobileUniversalDebugBuildConfig`,
  **0 Kotlin errors**. `:app`, where all five fixes live, is still uncompiled.
- **Kotlin LSP.** `fwcd/kotlin-language-server` 1.3.13 dies in Gradle sync on that box. JetBrains'
  official `Kotlin/kotlin-lsp` (VSIX `kotlin-server-0.0.13-linux-amd64.vsix`, server at
  `/root/klsp/extension/server/kotlin-lsp.sh`, *experimental* AGP support) installs and runs but is untested.
  An earlier "no LSP output" was never a clean result — that client discarded the server's log messages.
- **GitHub Actions disabled at account level.** The repo API reports `enabled: true`, but every dispatch
  returns `422 Actions has been disabled for this repository`. Only the account owner can enable it at
  <https://github.com/settings/actions>. `.github/workflows/kotlin_check.yml` (free `ubuntu-latest` runner)
  will fire as soon as it is on.
- **Never build on the user's Termux phone.** `/tmp` there is tmpfs (RAM-backed); a 3 GB SDK install
  crashed it once already.

## Known-good state

`feat/bitchord-navbar` at `ac7f8a154`, pushed to `origin` and canary. Working tree clean. Last CI-green
commit is `93c28006a`; everything after it is **statically verified only**.

## Static follow-up — 2026-10-09

- **BitChord navigation port exists and is wired.** `BitChordNavBar.kt` is present (17.6 KB), called by
  `MainActivity.kt:3169`; the searchable Appearance/settings entry uses `NavigationBarBitchordKey`. This
  verifies source integration, not the user's reported crooked-icon appearance on-device.
- **ArchivePool Z13 is present in current `main`.** Manual credential fields in closed Qobuz/Tidal details
  do not carry native `required` validation; matching server errors reopen the relevant details. The
  submit state effect scrolls to and focuses the notice on success and failure, including an action-state
  response rendered after a no-JS reload. Verified by source inspection only, not a production submit.
- **SpatialFlow resource comparison:** ArchiveTune's PCM tap samples every eighth frame and returns before
  analysis when haptics are disabled (`HapticsPcmProcessor.kt:78-95`). The processing engine is released
  from `MusicService.onDestroy`; its per-buffer `Dispatchers.Default` launch pattern matches upstream
  SpatialFlow `PlayerHapticManager.kt` at commit `9a9ef33b80e741ca0549f1f86f400c36a0f107dc`. No
  fork-specific regression was established by source comparison; device CPU/RAM profiling remains open.
- **Lyrics sync offset gap:** `Player.kt` owns the active per-track offset, but SimpMusic's preview renderers
  pass `0`, its fullscreen sheet owns separate local state, and SpatialFlow's overlay passes `0` with a no-op
  change callback. The shared adjustment therefore does not reach those surfaces; wiring is not yet changed.
