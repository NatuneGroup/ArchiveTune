# Spotify, playback sources, Discord, and ArchivePool verification

Reviewed on 2026-10-05. This records verified code and local checks, not a claim
that every authenticated provider or the production website has been exercised.

## Spotify library playback

The supplied recording shows resolved tracks stalled or paused at 0:00. The
device log also records explicit pauses and Spotify REST rate limiting; it does
not establish a single cause for every recorded stall.

The queue implementation had independently reproducible defects:

- Playlist initialization searched the selected track again instead of reusing
  its preload, with up to twenty concurrent lookups.
- A failed lookup could abort resolution of the entire queue.
- Queue extension dropped the first new Spotify track as though it were a
  repeated YouTube radio item.
- An outstanding page could be appended after another queue had replaced it.

Both Spotify queue types now share four-at-a-time, time-bounded resolution,
preserve playlist indexes and order, reuse the selected preload, isolate missing
tracks, and propagate caller cancellation. Download resolution uses the same
bounded policy. Spotify continuation pages retain their first track, and stale
queue responses are discarded. An unresolved selected track reports an error
rather than silently choosing a different track.

Spotify remains catalog-only. Track identification still enters the established
multi-source chain, with YouTube as the final enabled fallback.

The supplied backup passed ZIP CRC and SQLite integrity checks. Its database is
version 32; its private library records were not published. It contains no Spotify
sign-in cookie or access token and has no Spotify-specific song columns. The
Spotify-named setting in it is a lyrics preference, not login information.
Existing Discord/provider credentials were inspected only in an ignored private
workspace; their values, the database, recording, and raw logs are not published.

Library state, pagination, history windows, search/release mapping, queue behavior,
and source-selection policies have offline regression coverage. Authenticated
Spotify profile, playlists, liked songs, albums, artists, history, imports,
downloads, and actual playback still require a current signed-in session and
running-device verification. The backup cannot establish those results.

## Playback source wiring

All nine `AudioSourceType` entries have preferences and resolver dispatch. The
central Amazon/QQ switches now insert an enabled source into priority order before
YouTube, matching their detail screens. Disabled sources are no longer selectable
merely because an earlier match or pin exists. Cached online URLs also respect
the source flag and authenticity rejection state.

| Source | Default | Credential or route prerequisite |
| --- | --- | --- |
| Tidal | On | Personal or enabled Pool account, or configured healthy instance |
| Qobuz | Off | Personal/Pool credentials or configured instance |
| Qobuz backup | Off | Existing configured mirror route |
| Deezer | Off | Personal ARL, enabled Pool account, or configured instance |
| Apple Music | Off | Authorized media-user/developer tokens and supported stream route |
| Amazon Music | Off | Configured authorized instance and supported playable response |
| QQ Music | Off | Authorized session and entitlement for the requested tier |
| JioSaavn | Off | Existing public catalog/stream resolver |
| YouTube | On | Existing bounded client selection, then Echo last resort |

The generic playback routes and per-provider catalog-search UI are different
capabilities. Some catalog filters explicitly report an unavailable search
backend; that alone does not mean the playback resolver is absent. Spotify is
not an audio-source enum, and Telegram retains its independent `telegram://`
route. No DRM bypass or new provider transport was introduced.

Every source flag is covered by selection/order tests. Actual account playback,
source switching, fallback, entitlement, and downloaded-track reproduction are
unverified; dispatch and compilation are not evidence of audible playback.

## Pool toggle and credential separation

The Integration Pool switch is always visible. A build without the required Pool
configuration shows it unavailable rather than implying a working feed.

Pool OFF gates account getters/refresh, Pool discovery, reports, and provider-local
Pool caches. It invalidates resolved URL caches without deleting personal sign-in
preferences or downloaded audio. Credentials are removed from the separate
Keystore-encrypted Pool cache; retry timestamps survive that removal and restart.
Failed or empty account-feed attempts therefore retain their five-hour cooldown;
complete caches use twenty-four hours. Source-enable switches no longer force an
unthrottled refresh. Explicit manual refresh retains its existing force behavior.

This is credential-source separation inside the app, not a promise of exclusive
provider accounts. ArchivePool's sticky leases are intentionally non-exclusive;
different keys can receive the same donated account. Credential responses remain
encrypted and `private, no-store`, and are not placed in shared public caches.

APK client-delivery/read keys are extractable. They must never be reused as
administrator, session-signing, database, or at-rest encryption secrets.

## Discord authentication and now playing

The legacy presence path sent user OAuth tokens to raw Gateway IDENTIFY and
received authentication failures. The compared `rukamori/dev` path uses the same
approach; it is not a supported fix.

The raw Gateway implementation is removed. PKCE OAuth/profile linking remains,
expired or unverifiable saved authorization is not treated as connected, and a
failed refresh cannot fall back to an expired token. Settings distinguish account
linking from Rich Presence availability. Playback does not start auth or Gateway
retry loops when the official SDK is unavailable. Settings-only backups exclude
Discord refresh tokens, expiry, and profile fields as account data. Pool credential
caches are likewise excluded; the user's Pool opt-out choice remains a setting.

Actual per-user presence is still unavailable: the repository does not contain
the official Discord Social SDK Android integration. The application owner must
enable Social SDK in their Developer Portal application, register the existing
`discord-<APP_ID>:/authorize/callback` redirect, obtain the official Android
artifact, and review/integrate its native API and distribution terms. No client
secret belongs in the APK. Enabling a Portal setting alone does not implement the
missing native bridge.

A dedicated bot is possible, but its activity belongs to the bot, not each
listener's Discord profile. A channel/webhook can display an opt-in now-playing
message. A bot does not substitute for per-user Rich Presence, and no self-bot
workaround is supported.

The Social SDK runs on-device and does not require a permanently hosted bot or
Neon writes for track updates. A small Cloudflare Durable Object bot can be viable,
but a permanent outgoing Gateway socket cannot hibernate and free-tier usage is
not guaranteed. Vercel function/WebSocket lifetimes are not an appropriate basis
for assuming a free, permanent Gateway daemon; use it for finite OAuth/webhook
requests, subject to its plan terms.

References:

- [Discord mobile SDK support](https://docs.discord.com/developers/discord-social-sdk/core-concepts/mobile)
- [Mobile account linking](https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-on-mobile)
- [Supported Rich Presence](https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence)
- [Self-bot policy](https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots)
- [Durable Object pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [WebSocket hibernation limits](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [Vercel WebSockets](https://vercel.com/docs/functions/websockets)

## ArchivePool and Neon

Only the available local ArchivePool snapshot was changed. The current
repository/license adjustments could not be verified, and the configured MCP
connection requires administrator reauthorization. No production database,
migration, deployment, key rotation, or load test was performed.

The local follow-up adds signup/report throttling before database-backed checks,
per-service SQL limits, session-secret validation before partial account creation,
deployment documentation, and a reproducible test command. Existing session-key
bytes are preserved. It retains the prior coalesced/public Next Data Cache,
bounded Postgres pool, and cache invalidation fixes. Redis was not added: credential
leases must not be cached, and the existing shared public-data cache should be
measured before adding another service.

Owner review before deployment:

- Preserve the current server-only `SESSION_SECRET`; configure one if absent.
  Do not rotate it unintentionally or reuse an APK client key.
- Use the provider's pooled `DATABASE_URL` and review per-instance connection
  limits against actual Neon capacity.
- For distributed throttling, publish the documented Vercel rule before setting
  `POOL_FIREWALL_RATE_LIMIT_ID`. Without it, process-local counters are not a
  global quota. Plan availability and production rules require owner review.
- Compare the supplied current-file handoff to the latest repository before
  applying it. Confirm the owner's intended license and README against that
  repository; no license was selected or silently replaced.

Static upstream provider application constants are not donated account tokens.
Their authorization/confidentiality still needs owner review; they were not
represented as private operator keys or rotated on the owner's behalf. A Qobuz
extractor test that used a real provider application secret now uses synthetic
hex fixtures. Existing public history is not erased.

## Verification and remaining gates

- 432 Android unit tests across 69 suites: zero failures, errors, or skips.
- GMS mobile and TV universal debug assembly passed.
- 19 ArchivePool cache/rate/session/SQL-limit tests, TypeScript and Next production
  build passed using a non-production database fixture.
- Go tests, build, vet and formatting passed; live database tests were not run.
- Backup integrity and exclusion checks passed; restored credential values are
  absent from current tracked source files.

Fresh software-emulator attempts booted but stalled or became unavailable during
ADB installation. No completed current player/settings interaction or positive
appearance evidence was established. Device installation/upgrade, authenticated
Spotify/provider playback, source selection in the running player, Pool behavior
on a real device, and official Discord presence remain release-QA gates, not
results implied by these local checks.
