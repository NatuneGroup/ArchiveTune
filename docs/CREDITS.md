# Credits

ArchiveTune is a fork of [rukamori/ArchiveTune](https://github.com/rukamori/ArchiveTune)
(GPL-3.0), maintained by vossgraves as **NatuneGroup**. This file records the
upstream projects and the people behind them whose work this app carries,
adapted, or studies. Everything below is GPL-3.0 unless stated otherwise.

## Upstreams this fork descends from

| Project | Author | What we take from it |
| --- | --- | --- |
| [rukamori/ArchiveTune](https://github.com/rukamori/ArchiveTune) | Rukamori | The base application: player, library, queue, playback service, settings shell. Most files still carry the Rukamori header. |
| [rukamori/core](https://github.com/rukamori/core) → [vossgraves/core](https://github.com/vossgraves/core) | Rukamori, forked by vossgraves | The InnerTube client (`core` submodule). A NewPipeExtractor-based fork that deliberately omits `NetworkGatekeeper`. |
| [rukamori/IconPack](https://github.com/rukamori/IconPack) · [rukamori/morideobfuscator](https://github.com/rukamori/morideobfuscator) | Rukamori | Icon assets and the media obfuscator (submodules). |

## Projects whose work we port or adapt

| Project | Author | What we take from it |
| --- | --- | --- |
| [YumaPlayer](https://github.com/MuwMx/YumaPlayer) | [MuwMx](https://github.com/MuwMx) | The **opening animation** (`ui/component/splash/*`), the **Spotify integration** (catalog, home feed, playlist queue, profile cache), and the **glass surfaces** (`GlassSurface`, `GlassScaffold`). Ported file by file with the origin named in each header. |
| [4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune) | 4nx3b | Navigation-bar and pill appearance work, the **BitChord navigation bar** (`ui/component/BitChordNavBar.kt`), player-style refinements, the playlist UI, and several performance fixes (frosted-backdrop gating, activity-level refresh rate, menu z-order). |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | kushagrasinghx | The original floating bottom bar whose glyph set, glass edge, stretch-squash selection pill and drag gesture the navigation bar reproduces. |
| [adam-adrian/ArchiveTune](https://github.com/adam-adrian/ArchiveTune) | adam-adrian | The Editorial (V10) player adaptations. |
| [4nx3b/lyrics](https://github.com/4nx3b/lyrics) | 4nx3b | The `lyrics` submodule: enhanced/word-synced LRC parsing and the lyrics providers. |
| [amll-dev/applemusic-like-lyrics](https://github.com/amll-dev/applemusic-like-lyrics) | amll-dev | **Parameter ports, not code ports** — `ui/component/LyricsScrollSpring.kt` (interval-adaptive scroll spring policy) and `ui/component/LyricsEmphasize.kt` (per-character last-word emphasize) reproduce upstream's tuning constants and mapping. Upstream is **AGPL-3.0-only**; this fork is GPL-3.0. Both files state the provenance in their headers. **The owner must decide** whether to keep these (relicensing the derived files AGPL-3.0 and documenting it), replace the constants with independently-derived ones, or drop the two techniques — an AGPL-derived work cannot be distributed as GPL-3.0-only. No AGPL source was copied. |

## Audio engine, USB output and native code

The Oct 10–11 2026 audio-engine ports landed with many files carrying no header.
Headers were added afterwards by comparing each file with the same-named file in
the candidate upstream (line similarity: 49 of 56 matched ≥0.7, 36 of them ≥0.95;
3 sit between 0.5 and 0.65 and 4 are under 0.3 — rewritten for this app, credit
kept). Per-file lines read `Portions © <author>`.

| Project | Author | Licence | What we carry |
| --- | --- | --- | --- |
| [tryptz/Tryptify](https://github.com/tryptz/Tryptify) | tryptz | **none published** | The `tf.monochrome.*` Kotlin layer (39 files): DSP manager, mix bus, parametric/AutoEq, USB router and driver wrapper, stretch/resample processors, `SpectrumAnalyzerTap`. |
| [tryptz/tryptify-audio-core](https://github.com/tryptz/tryptify-audio-core) | tryptz | GPL-3.0 | The native side under `app/src/main/cpp/tryptify` (`dsp_engine`, snapins, `libusb_uac_driver`, WSOLA stretch). |
| [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) | Clash-Projects | GPL-3.0 | `com.lastwave.*` Kotlin (9 files) and `app/src/main/cpp/lastwave`. |
| [4nx3b/ArchiveTune](https://github.com/4nx3b/ArchiveTune) | 4nx3b | GPL-3.0 | `playback/dsp/*`, `di/LastwaveModule.kt`, `EqModels.kt`. |
| decent-usb-audio-driver | Marcelo Silva | MIT ([retained LICENSE](../audio/decent-usb-audio-driver/LICENSE)) | Previously vendored USB audio module; unreachable implementation removed, license and attribution retained. |
| [libusb](https://libusb.info) · [SoX Resampler](https://sourceforge.net/projects/soxr/) | libusb / soxr authors | LGPL-2.1 (`COPYING`, `COPYING.LGPL`) | Vendored in `third_party/`, built into the native library. |
| [signalsmith-stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) · [linear](https://github.com/Signalsmith-Audio/linear) | Signalsmith Audio | MIT (`LICENSE-*.txt`) | Vendored headers for independent pitch shift. |

**Open licensing question.** `tryptz/Tryptify` had no `LICENSE` file when this was
checked (Oct 2026), so by default nothing in it may be redistributed. The native
code is covered by `tryptify-audio-core` (GPL-3.0), but the 39 `tf.monochrome.*`
Kotlin files exist only in the unlicensed app repository. Before the next
release, either get a written licence (GPL-3.0 or compatible) from tryptz or
confirm the files come from `tryptify-audio-core`; otherwise replace them.

Files written for this fork carry the standard ArchiveTune banner with
`Portions © vossgraves`.

## Projects we studied for reference

These are not code dependencies; they informed design decisions and are credited
where their approaches were followed.

- [maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic) — the liquid
  glass backdrop and crossfade scheduling our implementation is based on.
- [Metrolist](https://github.com/Metrolist/Metrolist) — crossfade handoff timing
  and the Listen Together codec.
- [brahmkshatriya/echo-spotify-extension](https://github.com/brahmkshatriya/echo-spotify-extension) — read
  to confirm how Spotify's web player lists recently played contexts and resolves them with
  `fetchEntitiesForRecentlyPlayed`. The parsing in `SpotifyPlayContexts.kt` is written for this
  app from the live response shape; no source was copied (the repository publishes no licence).
- [NewPipe / InnerTube](https://github.com/TeamNewPipe/NewPipeExtractor) — the
  InnerTube request lineage behind `core`.

## Fork-only work

Multi-source audio routing (Tidal, Qobuz, Deezer, Apple Music, Amazon, QQ Music,
JioSaavn, Discord, Telegram), the ArchivePool credential service, the single
pill-style system shared by the navigation bar, mini player and search pill,
the playlist-insertion policy, offline read-window correctness, the redacted
diagnostics exporter, and the Listen Together server list.

## A note on contributions

If you contribute and your work is derived from any of the projects above,
say so in the pull request. Ported files keep their original header and gain a
`Portions © <author>` line, and this file is updated with the port.
