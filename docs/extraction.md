# YouTube stream extraction

YouTube is the last source tried; everything here is dead code for a Tidal, Deezer,
Qobuz or Telegram track. See [AGENTS.md](../AGENTS.md) for the multi-source contract.

## The chain

Playback (`MusicService.resolvePlaybackDataSpec`) and downloads (`DownloadUtil.resolveYouTubeDownload`)
resolve YouTube through the same live chain, each tier tried only after the previous one produced
no stream:

1. **Shipped resolver** — `YTPlayerUtils.playerResponseForPlayback` (playback, honouring Manual
   client mode) or `playerResponseForDownload` (downloads). The compiled InnerTube core with
   BotGuard/QuickJS PO tokens; the hot path, documented under *Client selection* below.
2. **`InnerTuneXStreamRepository`** — the `com.github.MetrolistGroup.innertubex` library's own
   extraction stack, adapted, not forked. It asks for clients whose response carries a plain URL
   and refuses SABR, byte-range-only and HLS-manifest results, which the player cannot fetch.
3. **`NewPipeStreamRepository`** — NewPipe's own YouTube stream extractor (MetrolistExtractor, the
   artifact `:core` already ships, through core's single NewPipe downloader), then, if that yields
   nothing, an anonymous player response minted through NewPipe's JavaScript player.
4. **Echo** (`echo/EchoStreamResolver`) — the very last resort, see below.

`playback/stream/YouTubeFailsafeChain.recover` runs steps 2–4. The extractor-tier order lives in
`ResolveAudioStreamUseCase.shippedFallbackTiers` and nowhere else. Each extractor tier is bounded at
12 s (`YouTubeFailsafeChain.TIER_TIMEOUT_MS`) and echo at 20 s, and caller cancellation always stops
the chain. Each tier logs one line under `PlaybackDiagnostics`:
`YouTube tier=<shipped|innertubex|newpipe|echo> resolved=<bool> elapsedMs=<n> [reason=<type>]`.

`InvalidPlaybackLoginContextException` and `LoginRequiredForPlaybackException` skip steps 2–4:
both are actionable by the listener, and no other extractor can fix them. When every tier fails,
the shipped resolver's original failure is rethrown so the error mapping keeps its message.

Every tier hands back a `YTPlayerUtils.PlaybackData`, so the URL cache, format persistence,
expiry and request headers downstream do not change. The headers come from the stream URL's own
`c`/`cver` parameters through `StreamClientUtils.resolveRequestProfile`, which covers every client
the fallback tiers produce URLs for (InnerTubeX's automatic direct clients `TVHTML5`,
`TVHTML5_SIMPLY`, `WEB_REMIX`, `WEB_CREATOR`, `WEB_EMBEDDED_PLAYER`; NewPipe's `ANDROID_VR`;
the anonymous `ANDROID_MUSIC`/`IOS`/`WEB_REMIX` players). The extractor tiers resolve as an
anonymous visitor, and for downloads they prefer MP4/AAC (jaudiotagger cannot tag WebM). Low-data
mode passes `LOW` quality to every tier.

`ResolveAudioStreamUseCase.invoke` (used by `NextStreamPreloader`) wraps the same stack with its own
cache keyed by `(mediaId, quality, metered, purpose, authFingerprint, pinnedFormatId)` and adds
**`YtdlnisStreamRepository`** (an external yt-dlp plugin APK through `ytdlp/CompactYtDlp.kt`, the way
YTDLnis does) after NewPipe; the plugin is optional, so it is not part of the live chain above.
`ResolvedAudioStream.source` records which tier won (`NATIVE_INNERTUBE`, `INNERTUBE_X`,
`NEWPIPE`, `YT_DLP`).

## The cipher seam

`StreamUrlExtractor` (`playback/stream/`) is the app-side contract for the cipher step:
mint a playable URL for a format the caller has already chosen, plus the JavaScript player's
signature timestamp. `NewPipeStreamUrlExtractor` is its implementation and the only file that
touches core's `NewPipeUtils`; `di/ExtractorModule` binds it for the injectable tiers, and the
player-response pipelines (`YTPlayerUtils`, `VideoArtworkPlayer`, `EchoStreamResolver`) call it
directly because they are static call sites. Swapping the extractor backend therefore changes
one binding and one object — which is also where the `org.schabi.newpipe.extractor` question
(core's dependency block) is decided.

The embedded Python/Chaquopy yt-dlp layer was removed in 2026-08 and must not come
back; the external plugin above is a different thing and is fine.

## Client selection

`YTPlayerUtils.STREAM_FALLBACK_CLIENTS` is tried in order: `VISIONOS`,
`ANDROID_VR`, `WEB_REMIX`, `WEB`, `MWEB`, `WEB_CREATOR`. Playback asks for
`WEB_REMIX` first (`NativeStreamRepository`), which is the YouTube Music client.

Resilience already built in, before adding more:

- **Last-successful-client fast path** (`lastSuccessfulClientKey`) — the client that
  worked last is tried first.
- **Per-video, per-client backoff** — a client that fails with 403/404/410/416 is
  benched for 10 minutes (`FAILED_CLIENT_BACKOFF_MS`).
- **Bot-detection repair** — `repairAuthStateAfterBotDetection` invalidates tokens,
  refreshes `visitorData`/`dataSyncId`, remints and retries; there is also an
  IP-rotation refresh path.
- **Quality-ladder retry** — HIGHEST → HIGH → LOW.
- **Age-gate bypass clients** appended last, only when the content setting allows it.
- **Typed failures** — `LoginRequiredForPlaybackException`,
  `BotDetectionPlaybackException`, `BadStreamPlayerResponseException`.

## Audio format choice

`selectAudioFormatCandidates` filters to audio formats that have a usable URL or
cipher, then:

- `HIGHEST` (and `AUTO` off a metered network) sorts by **highest bitrate, uncapped**.
- `HIGH`/`LOW` prefer the best format *at or under* the target (160k/70k), falling
  back to the cheapest one above it.
- Ties break on codec rank, then sample rate.

**On "high-res" / itag 774:** there is nothing to add. Playback already asks the
YouTube Music client and already takes the highest bitrate on offer, so 774 is
picked whenever it is in the response. It usually is not — that format is tied to
a Premium entitlement, not to anything the client does. The itag and bitrate that
actually played are shown in the media-info sheet (`ui/utils/ShowMediaInfo.kt`), so
check there before assuming the picker is at fault.

Browser-side tricks that harvest 774 out of an authenticated web session do not
port: they work by letting Google's own player JS run in a real DOM with real
cookies, which a native client has no equivalent of.

## PO tokens

`ui/screens/settings/PoTokenScreen.kt` and `PoTokenExtractionActivity.kt` own the
UI; minting is BotGuard/QuickJS inside the core. Tokens are video-scoped and feed
the client selection above. `AutoChoosePlaybackClientKey` gates automatic client
choice — manual selection must stay available.

## The `echo/` resolver — the fallback behind the shipped path

18 files under `app/src/main/kotlin/moe/rukamori/archivetune/echo/` plus five assets, ported from
4nx3b. `YouTubeFailsafeChain` runs it for playback and downloads as the **last resort**, never as
the primary: the shipped resolver, InnerTubeX and NewPipe are tried first and echo is only reached
once all of them have produced no stream, so the worst case echo can cause is the same failure a
moment later.

`EchoStreamResolver.playerResponseForPlayback` returns the same `YTPlayerUtils.PlaybackData` the
shipped path returns, so nothing downstream changes. Two exceptions bypass it (and the extractor
tiers) entirely — `InvalidPlaybackLoginContextException` and `LoginRequiredForPlaybackException`.
Both are actionable by the listener and neither is something a different extractor can fix, so
echo would only replace a message that can be acted on with one that cannot. When echo does run
and also fails, the *original* throwable is rethrown so the error mapping below it still produces
the meaningful message. `CipherDeobfuscator.ensureInitialized` loads echo's config stores on first
use rather than at app start.

What it adds over the shipped path: the player script is parsed with a real JavaScript parser
(`assets/solver/meriyah.js`) and regenerated with `astring.js`, so the n-parameter transform can be
recovered from whatever YouTube ships rather than matched against a pattern that a player change
breaks. `player_configs.json` and `player_dates.json` seed the known-good configurations.

Three of their core APIs do not exist in `vossgraves/core`, so the port adapts rather than pulling
the submodule along:

| Theirs | Here |
|---|---|
| `YouTube.newPipePlayer(videoId, response)` re-resolves the player response through NewPipe | No such entry point; the `?: streamPlayerResponse` fallback was already the only outcome, so the response is used directly |
| `AdaptiveFormat.isOriginal` marks the undubbed audio track | Field absent from this fork's `PlayerResponse`; every audio format stays a candidate |
| `YouTube.getNewPipeStreamUrls(videoId)` lists every `(itag, url)` pair | `NewPipeStreamUrlExtractor.streamUrl(format, videoId)` immediately above already resolves the same format through NewPipe, so the second pass had nothing to add |

Downloads reach echo through the same chain, but echo only mirrors the playback entry point: it
selects the best playable format, not an MP4 one, so a download that only echo could resolve may be
stored as WebM.
