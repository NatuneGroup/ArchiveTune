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

## Projects we studied for reference

These are not code dependencies; they informed design decisions and are credited
where their approaches were followed.

- [maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic) — the liquid
  glass backdrop and crossfade scheduling our implementation is based on.
- [Metrolist](https://github.com/Metrolist/Metrolist) — crossfade handoff timing
  and the Listen Together codec.
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
