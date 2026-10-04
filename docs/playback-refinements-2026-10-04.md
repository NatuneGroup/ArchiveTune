# Playback refinements — verified checkpoint, 2026-10-04

Follow-up to merged PR #179, based on `canary`. Checked items below describe
verified implementation or build evidence, not authenticated-provider or
physical-device QA. This is a checkpoint, not a claim that every request is done.

## Verification completed

- [x] Full `:app:testGmsMobileUniversalDebugUnitTest`: **378 passed, zero failed,
  zero errors, zero skipped**.
- [x] `:app:assembleGmsMobileUniversalDebug` and
  `:app:assembleGmsTvUniversalDebug` completed successfully.
- [x] Mobile APK contains `arm64-v8a` and `x86_64`; TV APK contains those plus
  `armeabi-v7a` and `x86`. TV manifest relaxes touchscreen, faketouch and microphone
  requirements. Minimum Android API remains 26.
- [x] Neither standard APK contains `.onnx` models or ONNX native libraries.
- [x] With `-Pautomix=true`, arm64 asset/native merge tasks completed. The two
  models total **13,587,458 bytes** and the arm64 ONNX runtime is present. This
  verifies staging, not a full optional-model APK or on-device inference.
- [x] Changed XML parses, `git diff --check` passes, submodule pins are unchanged,
  and no protected signing files or workflow fallbacks were introduced.
- [x] Current mobile debug APK installed successfully in the Android 35 emulator.
  Its private Pool/client-key fields were checked as blank without printing keys.

## Playback and signal analysis

- [x] Unknown-length cached prefixes are no longer treated as complete files.
  Download completion checks reject empty/truncated known-length responses;
  downloader timeout/cancellation removes incomplete temporary data.
- [ ] Reproduce the reported half-silent downloaded song with the affected source
  and device. The supplied log does not establish that incident's cause.
- [x] Crossfade clock/failure policies, bounded natural-tail transition planning,
  seek clamping and source-attempt deadline/cancellation contracts have regression
  coverage. Provider attempts and the multi-source chain are bounded; YouTube
  remains the final fallback, not a replacement routing path.
- [ ] Verify audible crossfades, Tidal-first resolution latency and fallback with
  authenticated providers on a real device.
- [ ] Verify the reported seek/background silence against actual audio output.
  Unit tests do not prove device AudioTrack or service-lifecycle behavior.
- [x] Experimental opt-in PCM analysis, ordinary-lossless and high-resolution
  categories, cache rejection and position-preserving source retry are wired.
  Short/silent/inconclusive samples remain unknown. Spectral suspicion is not
  proof of an upscaled master or its provenance.
- [ ] Validate false-positive/false-negative behavior against a representative,
  authorized recording collection and authenticated streams.
- [x] Compact AutoMix analysis is cancellation-aware; its source-aware cache and
  transition planning are bounded and covered by tests.
- [ ] Validate transition quality and optional native analysis on-device.
- [x] Diagnostic export includes fork/provider settings and credential redaction.
- [ ] Capture a fresh device export for the reported playback failures.

## Player, lyrics, Home and metadata

- [x] Dedicated seek glyphs were removed from the affected player controls;
  artwork double-tap uses the shared five-second seek policy. Existing optional
  progressive seeking remains preference-controlled.
- [x] BitChord uses the shared lyrics drawable instead of its custom quote vector.
- [x] Compact mini-player and bottom-pill options are selectable/searchable without
  replacing Apple Music's coordinated style.
- [x] Embedded lyric paging uses measured visual lines and preserves complete
  text/Unicode boundaries. The first page begins at the cue start, with word-timed
  page changes where timing is available. Lyric background layering was adjusted.
- [ ] Check every player style's tap regions, long lyrics, lyric animation and
  background appearance with fresh running-app screenshots/interactions.
- [x] Home's source-switch target remains discoverable for YouTube-only setups;
  unavailable Spotify routes to its integration settings. Target-selection tests
  pass. Spotify cards use the existing fork's optional glass surface primitives.
- [ ] Verify the complete YouTube ↔ Spotify flow and signed-in Spotify/glass
  appearance. Full Yuma/Muzo visual parity is not asserted.
- [x] Track Info & Specs separates overview/explicit writer credits from reported
  source format values, with parser/formatting tests. Non-YouTube IDs do not fetch
  YouTube statistics. Missing credits stay missing rather than being invented.
- [ ] Validate the tabs and Apple Music presentation visually. Actual output rate
  and bit depth are not measured by this screen; it says so explicitly.
- [x] Requested upstream areas and deliberate exclusions are documented in
  [the upstream review](upstream-review-2026-10-04.md). Current `4nx3b/dev` uses
  Muzo Home, not Yuma. No wholesale upstream tree replacement was performed.
- [ ] Any additional feature ports and a complete visual-parity pass remain
  separate work; the inventory does not claim every upstream feature was imported.
- [ ] Install/start the TV APK on affected 32-bit and 64-bit physical TVs. ABI and
  manifest checks do not prove universal TV compatibility.

## UI verification limitation

The fresh Android 35 ATD software emulator has no KVM. It installed the current
mobile APK, but launches were killed as startup ANRs while the main thread was
inside native DEX verification/loading, before the app UI became usable. ATD
drawing was disabled; enabling it did not resolve the startup ANR. Debug
precompilation was attempted but did not complete before the emulator session
ended. No app code or test expectations were weakened to hide this limitation.

The captured images show the emulator's empty launcher, not a verified player or
Home result. **No positive visual or interaction QA is claimed.** The generated
local audio fixture was not played. Physical-device and authenticated-source
checks above therefore remain unchecked.

## ArchivePool and Vercel handoff

- [x] Recovered original Pool performance branch at `499114a`, including submit
  fix `cdf96f9`; original checkout remains clean and unpushed.
- [x] Bounded Postgres pools, public-only persistent caching and optional Firewall
  SDK rate limiting are present. Encrypted credential leases remain private and
  `no-store`; neither keys nor decrypted leases enter public caches/artifacts.
- [x] Follow-up patch fixes admin/report mutation invalidation, including ignored
  `not_premium` reports and instance snapshot refreshes. It was edited in a
  tracked-files-only workspace copy, not by bypassing the sibling editor boundary.
- [x] Final Pool copy: **11 tests passed**, TypeScript check passed, production
  build passed. The build emits an existing nonfatal `/submit` cookies/dynamic
  rendering warning. The follow-up patch applies cleanly to `499114a`.
- [x] Project-scoped Vercel CLI **62.2.0** installed and `--version` verified.
- [ ] Apply/publish the Pool follow-up in its own repository. The handoff patch is
  a deliverable, not an assertion that the original branch was changed or pushed.
- [ ] Production WAF rule, pooled connection URL, Fluid Compute settings and
  deployment remain untouched. Vercel token/project authorization is unavailable;
  the configured MCP connection needs administrator reauthorization.

## Regression-fixture corrections

Coverage was retained, not loosened. Timeout tests now distinguish OkHttp's
`InterruptedIOException("timeout")` with a `"Canceled"` cause from real caller
cancellation. The lyric cue-start assertion exposed and fixed a one-millisecond
implementation error. The resampling cancellation fixture now uses a valid
duration under the existing ten-minute analysis cap. The PCM fixture was
normalized before 16-bit conversion: its prior peak was 3.0122 and wrapped into
broadband distortion; the corrected peak is 0.7877 and preserves the intended
band-limited signal.
