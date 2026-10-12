# Architecture and protected boundaries

## Playback

`MusicService` owns the Media3 player, notification/session lifecycle, queue persistence, audio focus, wake lock, route recovery, source switching, and crossfade. `CrossfadePolicy` is pure decision logic and is unit tested.

The primary player uses a bounded load control and network wake mode. Crossfade uses a separately prepared player only when explicitly enabled. Offload is disabled during crossfade because overlapping players and audio processing are not compatible with the low-power path. Secondary-player promotion is generation guarded; failed promotion leaves the outgoing player authoritative.

When an outgoing player reaches `STATE_ENDED` during an incomplete fade, the service clears `pauseAtEndOfMediaItems` and resumes the next queue item only when playback had been requested and a valid target exists. This prevents the reported second-song stop without forcing playback after an intentional pause.

Stream errors are handled through bounded URL/cache invalidation, source re-resolution, codec recovery, and optional next-item skipping. Do not turn a terminal error into an unbounded retry loop.

## Sources

The shared `audiosource` contract resolves Tidal, Qobuz (plus its backup), Deezer, Apple Music,
Amazon, QQ Music, JioSaavn and YouTube streams. Amazon plays through a self-hosted instance — the
user's own, then any the pool serves, each host carrying only its own authorization material — and
QQ plays from the user's own signed-in account. Spotify's catalog integration stays metadata-only;
the opt-in, experimental Spotify streaming source plays on a second, streaming-only account and
never on the catalog account (`docs/spotify-streaming.md`). Telegram uses
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
