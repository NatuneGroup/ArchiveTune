# Upstream review — 2026-10-04

## Snapshot and limits

This is a request-scoped comparison, not a claim of feature or runtime parity.

- Reviewed refs: `origin/canary` at `d25e86a11e8a`, `4nx3b/dev` at `8346b1750e29` (2026-10-03), `rukamori/dev` at `577d7f1a90b3` (2026-09-30), and this thread's `HEAD` at `ed420bc6deb7`.
- `origin/canary` and `4nx3b/dev` have multiple merge bases. Their whole-tree three-dot diff is not a reliable feature inventory; compare the named files and commits below. Do not tree-replace one branch with the other.
- The shared worktree already contains broad, uncommitted follow-up changes. “Fork baseline” below means the committed `origin/canary` tree; in-flight files are not counted as completed or verified.
- No YumaPlayer or Stash source checkout was found under `/tmp/hoplite/workspace`. Local Yuma attributions/integration code and Stash mentions are not substitutes for reviewing those repositories.

## Feature matrix

### Spotify Home, source switching, and Liquid Glass

- **4nx3b/dev:** Its Home implementation is `home/HomeRepository.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/HomeSourceSwitcher.kt`, `ui/screens/MuzoHomeSections.kt`, and `spotify/SpotifyHomeSection.kt` / `SpotifyHomeViewModel.kt`. The current tree contains Muzo home components, not a YumaPlayer Home port. Its Home switcher covers YouTube and Spotify.
- **Fork baseline:** `HomeSourceSwitcher.kt` supports YouTube, Spotify, and QQ with persisted active-home-source selection. `AGENTS.md` keeps playback-provider priority independent from Home selection and makes Spotify catalog-only. `docs/claude/SETTINGS.md` says the Apple Music experience preference is under Appearance → Theme → Interface style, but Apple-style YouTube/Spotify Home variants are not shipped in the baseline.
- **Disposition:** Port the requested Yuma presentation only from a reviewed Yuma source; do not treat Muzo as its implementation or replace the fork's source picker. Keep YouTube/Spotify/QQ routing and catalog-only Spotify behavior. Wire Glass to its own existing preference rather than inferring it from the Apple Music experience toggle.
- **Gap:** YumaPlayer is referenced locally, but its repository was not available for this review; exact visual and behavioral parity is unverified.

### Suspected upscaled audio versus bit-perfect output

- **4nx3b/dev:** `bb4d33e59` and `playback/dsp/BitPerfectRuntime.kt`, `BitPerfectGateProcessor.kt`, and `BitPerfectSwitchingAudioSink.kt` address output-path/sample-rate integrity and avoid app-level resampling. This is output-chain status, not a source-signal classifier for an upscaled or transcoded lossless file.
- **Fork baseline:** It also has bit-perfect output code (`playback/BitPerfectAudioOutputProvider.kt`, `BitPerfectUsbOutput.kt`). The October 4 scope requires signal analysis, confidence/unknown states, and no metadata-only verdicts (`docs/playback-refinements-2026-10-04.md`).
- **Disposition:** Keep output bit-perfect status distinct from source authenticity. Do not claim that a lossless format tag proves the recording is genuine lossless.
- **Gap:** Stash was not cloned. Its local mentions concern track matching or playlist/library-sync ideas, not evidence for an upscaling detector. The authenticity implementation currently in the shared dirty worktree was not independently validated in this documentation review.

### TV installation compatibility

- **4nx3b/dev:** TV packaging is defined by `app/build.gradle.kts` and `app/src/tv/AndroidManifest.xml`; no documentation establishes that its build solves the reported install failure.
- **Fork baseline/docs:** `docs/tv.md` documents a GMS-only `gmsTvUniversal` variant and says Fire TV installation issues remain undiagnosed. This thread's committed `HEAD` adds `armeabi-v7a` and `x86` TV ABIs and marks touchscreen, faketouch, and microphone optional in the TV manifest.
- **Disposition:** Keep the fork's signing identity and inspect the packaged manifest/ABI set. The current commit is a plausible compatibility repair, not proof that installation succeeds on the reporter's device.
- **Gap:** The device model, install error, APK/channel, and physical install result are not established by the docs. `docs/tv.md` does not yet describe the committed 32-bit/optional-hardware change.

### Offline downloads and truncated local playback

- **4nx3b/dev and fork baseline:** Both have `playback/DownloadUtil.kt`, `ExoDownloadService.kt`, and `PRDownloaderDataSource.kt`. The fork baseline additionally has `ChunkedDataSource.kt`, `ResumingDownloader.kt`, and `playback/smart/LocalAudioSource.kt`.
- **Fork invariant:** `docs/extraction.md` explicitly says the Echo playback fallback is not used for downloads. The offline “first half only” report is not explained by this or other documentation.
- **Disposition:** Compare complete download length, local-file bounds/EOF handling, and playback reads; do not route downloaded files through a streaming fallback as a presumed fix.
- **Gap:** No supplied document isolates the truncation cause. The shared worktree is changing these data-source files, so its current behavior still needs focused regression verification.

### Five-second double-tap seeking

- **Reference:** `rukamori/dev` uses `ui/player/Thumbnail.kt` for artwork double-tap seeking: left/right halves seek by five seconds, clamp to the track, and reverse sides in RTL. Progressive repeat and haptics are optional.
- **Fork baseline:** The same gesture is already present in `origin/canary`'s `Thumbnail.kt`. Separately, `PlayerSeekSkip.kt` exposes explicit five-second rewind/forward buttons and is used by player screens.
- **Disposition:** Preserve the artwork gesture and remove the dedicated skip buttons where the request calls for double-tap-only controls. This is a control-presentation change, not a missing seek action.
- **Gap:** Per-style visual/interaction parity still requires checking every player surface; a shared thumbnail gesture does not prove every style uses that thumbnail.

### Crossfade and the identified AutoMix fade defects

- **Current 4nx3b/dev:** `playback/MusicService.kt` prepares a secondary player, checks readiness and audio advancement, and generation-checks promotion. `CrossfadePolicy.kt` and `CrossfadePolicyTest.kt` cover policy math; failure tracking is bounded.
- **Confirmed historical defects, fixed on current `4nx3b/dev`:** `26b407c39` corrected session-player pause handling that could pause the secondary player mid-blend. `93e448f45` corrected smart fades driven by the incoming clock with a 150 ms end guard, which could cut the outgoing tail; the fixed path follows the outgoing clock and waits for its natural end before promotion. `8b07ec284` changed fixed structural mix-out timing to use the track's detected fade onset, avoiding blends that start at the wrong point.
- **Fork baseline:** `docs/claude/ARCHITECTURE.md` documents a separately prepared player, generation-guarded promotion, and bounded recovery. The fork adds `CrossfadeHandoffPolicy.kt` and `CrossfadeHandoffPolicyTest.kt`; retain these guards rather than transplanting `MusicService.kt` wholesale.
- **Disposition:** Port only the current fixed timing behavior after checking it against the fork lifecycle. Add regression coverage for user pause during blend, clock/rate drift, seek/cancel, failed incoming player, and natural-end promotion.
- **Gap:** The current upstream code does not substantiate a remaining crossfade defect by static inspection alone. Its cited fixes are recent, and the matching commits do not establish device-level or service-lifecycle test coverage. Runtime behavior remains unverified.

### Bottom navigation pill and mini-player styles

- **4nx3b/dev:** The relevant pieces are `ui/component/FloatingNavigationToolbar.kt`, `FrostedHeaderPill.kt`, `ui/player/MiniPlayer.kt`, and `ui/screens/settings/NavigationBarSettings.kt` / `PlayerSettings.kt`. The mini-player redesign is in `f97f1c28d`.
- **Fork baseline:** It already has those toolbar/mini-player surfaces and a fork-specific `ui/component/PillStyle.kt` that coordinates pill geometry/material by role. The upstream tree does not have that shared `PillStyle.kt` abstraction.
- **Disposition:** Adapt the requested new toolbar/miniplayer behavior into the fork's existing style model and settings. Keep the Apple Music experience preference atomic as required by `AGENTS.md`; keep bottom-pill and mini-player controls separately selectable where requested. Do not import upstream's settings redesign or replace the fork's pill coordinator.
- **Gap:** Documentation establishes the existing preference boundaries, not a visual match; fresh UI evidence is still needed.

### AutoMix and storage

- **4nx3b/dev:** `80cfbee09` removes the earlier BitChord/native/JNI/ONNX implementation and adds a clean-room Kotlin engine under `playback/automix/`. `AutoMixAnalyzer.kt` decodes at reduced analysis rates without retaining PCM, stores feature-analysis JSON under `filesDir/automix_analysis`, and uses an LRU limit of 600 entries. `AutoMixPlanner.kt` selects gapless, beat-matched DJ blend, filter ride, or equal-power fallback. Recent fixes are in `8b07ec284`, `93e448f45`, and `26b407c39`.
- **Fork baseline:** The committed `origin/canary` tree does not have this `playback/automix/` engine. Its documented crossfade policies are fork-specific. The current dirty worktree contains separate `playback/smart/` analysis work; it is not the same implementation.
- **Disposition:** Review the clean-room analyzer/planner, not the superseded BitChord port, before adapting selected algorithms. Keep the fork Python-free and preserve its crossfade/source boundaries.
- **Gap:** A 600-entry limit is a count limit, not a demonstrated byte quota; no storage-size benchmark or AutoMix analyzer/planner test file was found in the current upstream test tree. Lower storage use is plausible from removing native models and PCM retention, but not quantified.

### Embedded lyrics and long lines

- **4nx3b/dev:** Current lyrics/player work includes `ui/player/LyricsScreen.kt`, style-specific renderers, lyrics providers, and YouLyPlus word-merge work. These do not by themselves establish a fix for the fork's BitChord embedded strip.
- **Fork baseline/docs:** `docs/lyrics.md` describes Enhanced/V2 full-screen modes, compact BitChord and SimpMusic strips, shared `SweptLyricLine` timing, and a two-line reservation to reduce truncation/layout shifts. It does not guarantee that every long lyric line is shown without ellipsis.
- **Disposition:** Apply wrapping/splitting and animation changes to the embedded surface while retaining provider-independent parsing and whole-line highlighting when word timing is absent. Do not replace the fork's lyrics provider set wholesale.
- **Gap:** The reported long-line truncation and “sandwiched” background effect remain UI issues requiring targeted interaction and screenshot checks.

### Track Info, specs, and credits

- **4nx3b/dev:** `26b407c39` introduced `ui/utils/TrackInfoSheet.kt` from `ui/menu/PlayerMenu.kt`. Later, `11f21ddd0` removed that sheet and its separate player-menu item, merging overview/spec data into the current `ui/utils/ShowMediaInfo.kt` Details popup. The current popup includes duration, bit depth, channels, quality tier, and—when the displayed track is playing—live playback/provider/stream/normalization/DAC information. `b9b3f2aff` adds YouTube description credits and lyric-writer fallback in `ShowMediaInfo.kt` and `ui/component/LyricsEnhanced.kt`.
- **Fork baseline:** `origin/canary` already has `ShowMediaInfo.kt`, `models/MediaMetadata.kt`, `playback/AudioTagger.kt`, and `ui/player/FormatBadge.kt`, but not the current upstream database-backed specs/credit rows. The shared dirty worktree already contains local `TrackInfoFormatting.kt`, `TrackCreditMetadata.kt`, and tests; those are in-flight fork code, not committed parity.
- **Disposition:** Keep the fork's data/formatting contract and provider-independent UI; port behavior, not whole files. Verify credit precedence/deduplication and ensure unavailable fields remain absent rather than guessed.
- **Gap:** Current-run verification of the in-flight local implementation is outside this review.

### Icons, playback-source resolution, diagnostics, and other scope

- **BitChord icons:** The request concerns visual geometry; repository docs define no canonical shape or “rounded” measurements. Review the actual vector/composable geometry and validate a fresh screenshot rather than inferring correctness from upstream feature names.
- **Playback-source/Tidal behavior:** `AGENTS.md` requires `resolveMultiSourceDataSpec`, independent provider priority, Telegram/Tidal scheme routing, and YouTube as the final fallback. `docs/instance-racing.md` documents configured-instance priority, health-verified discovery, and cooldown behavior. No document establishes the cause of the reported Tidal delay or seek/background silence; upstream provider/engine code is not a drop-in replacement for these fork contracts.
- **Diagnostics:** This thread's `HEAD` adds fork-aware redacted diagnostic export (`logcat/DiagnosticReport.kt`, `DiagnosticRedaction.kt`, and `DiagnosticRedactionTest.kt`). The commit is code evidence, not proof of a fresh export/device test.
- **ArchivePool/Vercel:** These are not `4nx3b/dev` features; they belong to the separate ArchivePool repository. No Pool/Vercel behavior is reviewed here.

## Documentation caveats

- `docs/fork-divergence.md` is explicitly a snapshot at `4nx3b/dev` `28e0eb37b`; its file counts and “missing upstream capability” lists are stale against `8346b1750e29`. In particular, use `docs/extraction.md` for the later Echo resolver status rather than the older extraction-gap note.
- `docs/tv.md` predates this thread's committed 32-bit ABI/optional-hardware manifest update.
- `docs/claude/SETTINGS.md` and `docs/lyrics.md` describe the committed baseline, not the shared uncommitted October 4 UI work.
- None of these documents supplies device/provider/production verification for the reported TV install, downloaded-song truncation, crossfade, Tidal delay, or seek/background silence.
