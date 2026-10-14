# Architecture and protected boundaries

## Playback

`MusicService` owns the Media3 player, notification/session lifecycle, queue persistence, audio focus, wake lock, route recovery, source switching, and crossfade. `CrossfadePolicy` and `CrossfadeHandoffPolicy` are pure decision logic and are unit tested.

The primary player uses a bounded load control and network wake mode. Crossfade uses a separately prepared player only when explicitly enabled. Offload is disabled during crossfade because overlapping players and audio processing are not compatible with the low-power path. Secondary-player promotion is generation guarded; failed promotion leaves the outgoing player authoritative.

Crossfade and automix hand-offs are decided by `CrossfadeHandoffPolicy` (`CONTINUE`, `PROMOTE`, `CARRY_TO_PRIMARY`, `RESTORE_OUTGOING`). Each transition runs once per song pair and the outgoing song is silenced at hand-over. Once the incoming song is dominant (the outgoing song ran out, or the fade is at least half done) it is never restarted from 0: a healthy incoming player is promoted, and an unusable one hands its position to the primary player. Restores never rewind, and `CrossfadeFailureTracker` turns repeated failures into a bounded hard cut instead of another fade. When the outgoing player ends during an incomplete fade, the service clears `pauseAtEndOfMediaItems` and continues only when playback had been requested, so an intentional pause is respected.

Stream errors are handled through bounded URL/cache invalidation, source re-resolution, codec recovery, and optional next-item skipping. Do not turn a terminal error into an unbounded retry loop.

## Sources

The shared `audiosource` contract resolves Tidal, Qobuz (plus its backup), Deezer, Apple Music,
Amazon, QQ Music, JioSaavn and YouTube streams. Amazon plays through a self-hosted instance — the
user's own, then any the pool serves, each host carrying only its own authorization material — and
QQ plays from the user's own signed-in account. Spotify's catalog account serves metadata only;
the opt-in, experimental Spotify streaming source plays on a second, streaming-only account and
never on the catalog account (`docs/spotify-streaming.md`). Amazon, QQ and Spotify are all members
of `AudioSourceConfig.DEFAULT_ORDER`, placed just above YouTube (`… AMAZON, QQ, SPOTIFY, YOUTUBE`),
and all three default to off; Spotify is additionally active only while a streaming account is
signed in. Like every other source they resolve through `resolveMultiSourceDataSpec`. One ordered, gated chain (`audiosource/PlaybackSourceChain`) decides which sources may be tried, and normal play, prefetch, crossfade/automix, Cast, per-song pins, the cache shortcut and downloads all use it, so a source the user turned off is never tried. Telegram uses
independent `telegram://` routing. Tidal progressive DASH uses `tidal-dash://`. YouTube is the final
fallback and is itself a toggle: turning it off leaves the resolver with no terminal fallback beyond
the standard no-stream error.

No new provider may bypass `resolveMultiSourceDataSpec`, replace the source priority contract, or
introduce a REST/WS path to koiverse domains.

## Performance

SpatialFlow's player (a second Media3 player, 50–60 s buffering, a 250 ms progress loop and a simpler linear crossfade handoff) is a useful comparison, not a source to copy wholesale: this fork already has stronger readiness, generation, cache, codec, audio-route and promotion safeguards, so measure before borrowing anything. Before changing buffer sizes or adding audio processors, measure transition latency, resolver calls, cache hit rate, decoder state, dropped frames, CPU, radio use, and thermal status. Low-end validation must cover crossfade, canvas/artwork, lyrics, and audio offload independently.

## Data and privacy

Spotify history synchronization is read-only and uses the existing authenticated Recently Played request. It is opt-in, appears in the History source list only when enabled, and never launches Spotify playback or a background WebView.

Community Source Pool credentials (the pool's accounts, plus the user's own Tidal/Qobuz/Deezer tokens) live in ordinary Preferences DataStore, not Android Keystore, and the backup rules exclude only the ExoPlayer cache, the download directory and `exoplayer_internal.db` — so preferences, pool tokens included, are eligible for cloud backup and device transfer. The pool's `POOL_CLIENT_KEY` ships in the APK, so it stops accidental disclosure of the feed, not a determined user; the security control that matters is server-side (independent 32-byte keys and `READ_KEYS_ENFORCED` on the pool deployment).
