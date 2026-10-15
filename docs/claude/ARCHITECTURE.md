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

Pool account payloads use an Android-Keystore-encrypted cache (`PoolCacheCrypto`),
separate from personal sign-in preferences, not plaintext Pool storage. Cached payloads are not portable
credentials across reinstall/device transfer; the feed repopulates them. Personal credential
preferences and legacy migration are separate concerns: do not infer their encryption or backup
policy from Pool encryption. The Playback Pool toggle gates feeds/discovery/reports and
provider-local caches without overwriting personal accounts. An APK-delivery/read key is
extractable, not a server administrator/session/database secret; server-side authorization
remains essential.

## Bounded acoustic graph (implemented; execution evidence in PR/Actions)

`acoustics/` owns read-only input resolution, bounded decoding/FFT and memory-only results.
`AcousticQualitySection` and `AcousticQualityDialog` share the Developer and player/song-menu
surfaces. Opening is idle; Run/Cancel/Retry owns work. Ordinary seekable file/content and
supported clear current-source snapshots or complete contiguous source-bound cache are usable;
unsupported/nonseekable/DRM inputs remain honestly unavailable. Cancellation releases owned
resources; target/generation checks reject stale results. No playback resolver, queue,
source-rejection setting or persistent quality badge is mutated.

Heat, cutoff and decoded format describe the sampled rendition, not studio provenance or
physical DAC output. Silence/short/uncertain samples remain inconclusive. The graph adds no
Oboe route or native engine.

## DSP and retained native source

Maintenance pins: Oboe 1.11.0, Signalsmith Linear 0.6.5, libusb 1.0.30; Stretch 1.4.0
and soxr 0.1.3 remain. Existing Media3/AAudio/LastWave/Tryptify/float-DSP switches,
defaults, presets, source policy and bit-perfect bypass remain separate. libusb/soxr retain
Android runtime/header/build/license closure, not complete standalone upstream tooling/tests:
see [libusb subset](../../third_party/libusb/README) and [soxr subset](../../third_party/soxr/README).
The removed unreachable decent USB island is not the active USB output; its
[MIT license](../../audio/decent-usb-audio-driver/LICENSE), credit and app optional USB-host
feature remain. Persisted preset/state formats are not dead with an unused builder.

## Listen Together recovery and design references

Connection and pending room requests have bounded deadlines (historical design: 60/90 seconds);
decoded responses, cancellation and connection generations guard completion. Keep manual recovery,
protobuf-only Meowery (no JSON/client-capabilities frame), JSON-only chat and server-side host
restrictions. Historical 2026-10-06 synthetic checks received `room_created` from Hugging Face
and `host_not_allowed` from Meowery; these were not Android synchronized-playback validation.
The local delayed-retry fix snapshots socket, connection and room-request generations,
then checks the still-pending deadline before sending. Independent source review and confirmed
fixes are complete; actual CI is recorded in the candidate PR/Actions. This does not establish
synchronized playback or host eligibility.

Spotify Home's Yuma-derived geometry was recorded at fork `04ef0f333`, reference YumaPlayer
`28aef61`: two track rows, 240dp track tiles, 150dp artwork cards, 16dp gutters, reference
8dp quick-grid corners and 0.5dp border. The historical correction used a low-alpha 0.5dp
highlight, fixed 2dp refraction and no default shadow rather than the generic 24dp control
effect. Shared Suki material has since evolved; these pins preserve design provenance, not
current pixel parity. Preserve independent glass/blur/older-Android fallbacks and source routing.

## Integrated language, AI and request ownership

The shared lyrics path preserves semantic Unicode joiners and source spacing. Japanese
phonetics prefer provider language metadata or kana context; ambiguous Han is not assumed
Japanese just because a pack is installed. Korean phonetic working copies use NFC without
rewriting source lyrics. Provider-aligned words win; unaligned readings fall back to a line.
SimpMusic shares the built-in/provider/AI policy and shapes timed text as one source-spaced
layout. Script-family exclusions are conservative, not exact language identification.

Enhanced/V2/SimpMusic use full-settings/text AI eligibility before suppressing fallback.
Every active translation surface signals target changes even with automation off; automatic
calls retain exclusion gates. DeepL target mapping is explicit and romanization is unsupported.
Only complete validated AI output is accepted; failures remain retryable, cache identities are
opaque and configuration-bound, and obsolete work cannot publish to a changed lyrics target.

`JapaneseLanguagePackManager` owns IO validation and serialized install/cancel/remove.
Primary system-device region JP, no usable pack and no off/removal/prior-attempt flag permit
one persisted foreground consideration; offline consumes it, while manual retry remains.
No geolocation, startup wait or background watcher is added. The pinned IPADIC JAR is data,
not executable downloaded code. The real pinned-data/production-tokenizer fixture is integrated;
at the 2026-10-15 pre-CI checkpoint it was unexecuted. Consult candidate PR/Actions for actual execution.

Downloader requests own UUID staging directories, latches and transport/probe cleanup.
Spotify refresh, sign-in and sign-out serialize credential persistence and session invalidation
under the same mutex; catalog/streaming separation and playback source policy remain intact.
These source-level ownership changes are not proof of real-network or device behavior.
