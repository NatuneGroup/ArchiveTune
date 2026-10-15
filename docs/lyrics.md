# Lyrics

## Parsing

One parser for everyone: `lyrics/LyricsUtils` (`parseLyrics`, `parseTtml`,
`isLineSyncedLrc`, `isTtml`, `findCurrentLineIndex`, `insertInstrumentalBreaks`).
A format either works in every renderer or in none. Providers live in `lyrics/`
and are selected in Lyrics settings.

Word-level timing only exists where the provider supplies it (TTML and the
rich-sync providers). Everything else is line-synced, and a renderer must degrade
to whole-line highlighting rather than pretending.

## Language and AI follow-up (implemented and source-reviewed)

Preserve raw Unicode, semantic ZWJ/ZWNJ, combining marks and source spacing. Korean
phonetic conversion uses an NFC working copy, not rewritten lyrics. Japanese routing
uses provider metadata or kana context, not pack presence as proof that Han is Japanese.
Provider-aligned phonetic words take precedence; AI or unaligned/split-token readings
fall back to line phonetics rather than proportional or duplicated word readings.
SimpMusic shares the built-in/provider/AI policy and uses one shaped, source-spaced
timed-text layout. Script-family exclusions are conservative, not exact detection.

Enhanced/V2/SimpMusic check full-settings/text AI eligibility before suppressing fallback.
All six translation surfaces invalidate changed lyrics targets even when auto-translation
is off; automatic calls keep exclusion checks. DeepL maps supported targets explicitly
and never owns romanization. Complete validated output alone can be saved/cached;
failed/partial output is retryable, with settings-bound opaque cache identities and stale
target/configuration guards.

Optional Japanese IPADIC data remains manually installable/cancellable/removable.
One persisted foreground consideration for primary system-device locale region JP may
download over Wi-Fi/cellular when there is no usable pack and no explicit off/removal or prior attempt.
Offline consumes the consideration; manual retry remains. No IP lookup, UI-language change,
startup wait or continuous watcher is added. The 13,343,016-byte pinned data/digest and
off-main tokenizer readiness check are retained. Focused regression fixtures, including real
pinned-data/production-tokenizer coverage, are integrated. At the 2026-10-15 pre-CI checkpoint
they were unexecuted; consult candidate PR/Actions for actual execution. Independent source
review and confirmed fixes are complete; no all-language or device QA is claimed.

## Renderers

| Renderer | Where |
|---|---|
| `ui/component/LyricsEnhanced.kt` | The default full-screen renderer; also drawn inside SimpMusic's lyrics card for every mode except SIMPMUSIC |
| `ui/component/LyricsV2.kt` | Alternate full-screen renderer |
| `ui/player/simpmusic/SimpMusicLyrics.kt` | The SIMPMUSIC lyrics *mode* — a user-selectable style, reachable from the full-screen surface and the card |

`LyricsMode` (settings) picks between these. Do not delete `SimpMusicLyrics`
thinking it is player-style chrome — it is a lyrics style a user can select.

## The two compact surfaces

Both players carry a small "current line" strip, and they are *not* the same shape:

- **Bitchord**: `CurrentLyricLine` in `ui/player/bitchord/BitChordLyrics.kt` — one
  strip directly above the scrubber, with a chevron into the full lyrics page.
- **SimpMusic**: `SimpMusicLyricLine` in `ui/player/simpmusic/SimpMusicPlayer.kt` —
  a 48dp centred band between the artwork and the title row. Its height feeds the
  artwork sizing arithmetic in the hero layout, so it is fixed on purpose.

SimpMusic *also* has a 300dp lyrics card further down the page
(`SimpMusicLyricsCard`), below the fold with the artist and track-info cards. That
is a different surface again, and it follows the global `LyricsModeKey`: the
SimpMusic renderer when the mode is SIMPMUSIC, `LyricsEnhanced` for every other
mode (the card is a preview, and the other renderers' karaoke sweep needs a full
screen to read as anything but flicker). The style's own fullscreen lyrics sheet
(`SimpMusicFullscreenLyricsSheet`) follows the same rule.

## The word sweep

`SweptLyricLine` draws a line as two stacked copies — dim underneath, bright on top
clipped to however much has been sung — plus an optional blurred third copy for the
glow. The clip is recomputed in the draw phase, so a frame costs a clip and a redraw
rather than a recomposition.

It is fed by `rememberLyricClock`, which carries the player's twice-a-second position
report forward on the frame clock so the sweep does not step.

**Both compact surfaces share this renderer.** That is a deliberate exception to the
player-style self-containment rule (2026-09-01, amended 2026-09-07): SimpMusic's band
used to highlight whole lines off the same word timings Bitchord swept through, and
a second copy of the sweep is a second thing to fix whenever a provider changes shape.
Everything else in each style stays its own.

## Two things that were wrong, so they do not come back

- **Clock gating.** The sweep must be driven by `PlayerConnection.isAudioAdvancing`
  (Media3's real `isPlaying`), never `PlayerConnection.isPlaying` — the latter is
  `playWhenReady && state != ENDED`, which stays true right through a buffer and
  through focus suppression, so lyrics ran on while nothing was audible. `isPlaying`
  stays as it is for play/pause affordances, which should flip on tap.
- **Truncation.** A one-line strip with `TextOverflow.Ellipsis` ended most lyrics in
  a "…". The strips reserve two lines and hold that height whether they fill it or
  not; growing to fit would move the controls underneath every time the line changed.
