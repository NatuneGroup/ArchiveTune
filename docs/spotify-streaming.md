# Spotify streaming source (experimental)

`AudioSourceType.SPOTIFY` plays audio from Spotify itself. It is ported from 4nx3b's fork
(`4nx3b/dev`, commits `3b138b559` through `4d183f803`), which in turn ported the web-player
pipeline from Stopify and the desktop pipeline from echo-spotify-extension. It is opt-in, off by
default, and sits in `AudioSourceConfig.DEFAULT_ORDER` like every other source — just above
YouTube — but counts as enabled only while the streaming switch is on and a streaming account is
signed in.

## Two accounts, never one

The catalog integration (home, library, search, playlist import, history) signs in once and keeps
its session in `SpotifySpDcKey` / `SpotifyAccessTokenKey`, with the global `Spotify.accessToken`.
Streaming uses a **second** account:

| | Catalog | Streaming |
|---|---|---|
| Preferences | `SpotifySpDcKey`, `SpotifySpKeyKey`, `SpotifyAccessToken*` | `SpotifyStreamSpDcKey`, `SpotifyStreamSpKeyKey`, `SpotifyStreamAccessToken*`, `SpotifyStreamUserIdKey`, `SpotifyStreamAccountNameKey` |
| Token holder | `Spotify.accessToken` (spotifycore global) | `SpotifyStreamingAccount.accessToken()` (never touches the global) |
| Sign-in | `SpotifyLoginSheet` in the default WebView profile | `SpotifyLoginSheet(isolatedProfile = "spotify-streaming")` |
| Settings | Integration → Spotify | Integration → Spotify streaming (`settings/spotify_streaming`) |

Streaming registers a virtual Connect device ("ArchiveTune Player"), issues play commands and
reports plays (start, 30 s, finish). None of that may land on the listener's real library, which is
why the account is separate.

Separation is enforced, not just suggested:

- `SpotifyStreamingAccount.signIn` refuses a cookie identical to the catalog cookie, and compares the
  two accounts' user ids (GraphQL `profileAttributes`, REST `/v1/me` as fallback). If the catalog is
  signed in but cannot be read, the sign-in is refused rather than assumed different.
- On every token refresh, `collidesWithCatalog` re-checks once per catalog session (using only the
  catalog's existing, still-valid token; it never mints or stores one). If the catalog has since
  been signed in with the streaming account, no streaming token is returned and the source falls
  through.
- The catalog token is read only for that comparison. Search, device registration, play commands,
  license requests and reports all use the streaming token.

### Cookie isolation in the sign-in WebView

When the installed WebView reports `WebViewFeature.MULTI_PROFILE` (via `androidx.webkit`), the streaming sign-in runs in
its own WebView profile: separate cookie jar and storage. The profile's cookies and storage are
cleared before the page loads and the cookies again after capture, and the default profile, with
every other provider's sign-in, is not touched.

Without multi-profile support the sheet falls back to the shared jar: it wipes it before loading
(the catalog sign-in does the same on every open) and wipes it again after capture, so the
streaming session is not left behind for the next sign-in. Credentials only ever live in DataStore.

## Resolve methods

`SpotifyStreamResolveMethodKey` (`SpotifyStreamResolveMethod`):

- **STOPIFY** (default) — `SpotifyWebStreamer`: Dealer WebSocket → device registration →
  connect-state play command → manifest file id → storage-resolve CDN URL. The CDN file is
  Widevine-CENC fMP4 AAC 128. The key comes from Spotify's web-player license endpoint through
  `SpotifyStreamDrm` (provisioning goes to Google's server via `HttpMediaDrmCallback`). Needs
  Widevine on the device.
- **ECHO** — `spotify/echo/`: desktop device authorization from the streaming `sp_dc` →
  extended-metadata audio file list → storage-resolve → PlayPlay key. `EchoDecryptingDataSource`
  AES-CTR decrypts the CDN bytes (Ogg Vorbis 320/160/96 or FLAC). Falls back to STOPIFY when it
  cannot resolve. `SpotifyDesktopAuth` fetches the current desktop client version string from
  `loadspot.pages.dev/versions.json` once per process (validated by regex; a fixed default is used
  otherwise).

A queue item that came from a Spotify catalog import carries `MediaMetadata.spotifyTrackId`; that id
is played directly and trusted. Anything else is looked up with `searchDesktop` on the streaming
account, scored on title, artist and duration (minimum score 5).

## Wiring in MusicService

- Chain: every playback path derives its source chain from one `PlaybackSourceSnapshot`
  (`audiosource/PlaybackSourceChain.kt`) built from the stored order plus the per-source toggles.
  SPOTIFY is part of that snapshot and is enabled only when `SpotifyStreamEnabledKey` is on **and**
  a streaming account is signed in (`isSpotifyStreamingConfigured`). Its default position is just
  above YouTube; the user reorders it in "Preferred sources", and switching the toggle on or off
  never changes the order.
- Data sources: `echo-spotify://` is routed by both `SchemeRoutingDataSource` and
  `ResolvedSchemeRoutingDataSource` to `EchoDecryptingDataSource`, decrypted below the player cache,
  and bypasses the YouTube resolver. STOPIFY URLs are plain HTTPS on `*.spotifycdn.com`; the
  `mediaOkHttpClient` interceptor adds the open.spotify.com UA/Origin/Referer.
- DRM: the item's source is only known once its data source resolves, after the media source
  factory has asked for a DRM manager. So while streaming is configured, every media source gets a
  Spotify Widevine manager (Apple items keep theirs). Clear streams never request a key. One manager
  per media source factory, because the crossfade player builds its own and a manager binds to one
  playback looper.
- Cache: both methods share the `spotify:<mediaId>` cache key but write different bytes (encrypted
  MP4 vs plain Ogg/FLAC). `alignSpotifyCacheWithMethod` tags the cache entry with its method in the
  cache's content metadata and drops it when the method changes.
- Reporting: `updateSpotifyPlaybackReporting` runs per media-item transition, only for items whose
  selected source is SPOTIFY and that resolved through STOPIFY.

## Known limits

- Neither method has been verified on a device in this fork. Both depend on undocumented Spotify
  endpoints, hard-coded client versions and (for ECHO) PlayPlay key derivation that Spotify can
  change at any time.
- First resolution can be slow (Dealer connect + manifest wait, or the desktop authorization flow)
  and may run into the shared per-source attempt timeout; later tracks reuse the session.
- Not a download source (`DownloadSourceConfig.fromAudioSource(SPOTIFY) == null`) and not castable.
- Using this source is against Spotify's terms of use, and the streaming account can be suspended.
  The settings screen says so.
