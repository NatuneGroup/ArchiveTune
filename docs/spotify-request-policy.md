# Spotify request, rate-limit, and quota review

Reviewed on 2026-10-05 against the current app code, Spotify's documentation, and
public client implementations. This supplements the earlier
[Spotify/source/Pool verification](spotify-source-pool-verification.md).

## Findings

A 429 is not proof of malformed headers or an expired user session. Spotify's
Web API documents rolling request-rate limits and `Retry-After`; its July 2026
development-mode changes also define a separate `QUOTA_EXCEEDED` response and
shared quota across an owner's development client IDs. Adding arbitrary headers
or more client IDs does not promise more quota.

Independent GitHub clients have reported widespread public Web API 429s with
shared first-party authorization. This is supporting context, not proof of the
exact reason for ArchiveTune's earlier REST response: that bounded check did not
retain or classify its error body.

ArchiveTune already sends Bearer authorization, JSON content type, WebPlayer
platform, Origin and Referer headers on its Pathfinder route. A bounded check
using the supplied session and this header pattern succeeded:

- Cookie refresh: HTTP 200, non-anonymous token.
- `profileAttributes`: HTTP 200, expected profile object, no GraphQL errors.
- `libraryV3`: HTTP 200, valid library envelope and one-item page.
- `searchDesktop`: HTTP 200, two tracks with complete title, artist and duration
  metadata and reported content-rating fields.

The earlier direct `/v1/me` probe was not the app's primary profile path: the app
already tries GraphQL first. The later checks made no REST, playback, Pool, Neon,
or Discord requests, and sent no retries. Account values and tokens stayed in an
ignored private workspace; no profile details or playlist names were published.

Some public anonymous clients supply `client-token` and app-version headers.
That does not establish those headers as the cause of this user's failure: the
existing authenticated profile, library and search request pattern worked without
them. No invented client token, spoofed client ID, embedded client secret or
quota-evasion mechanism was added.

## Fixed request behavior

- A successful GraphQL track search no longer always requests REST `/tracks`.
  Complete title/artist/duration metadata is sufficient for the existing mapper;
  an absent optional ISRC alone does not trigger another request. Incomplete
  metadata retains the existing best-effort enrichment path.
- Reported explicit flags and recording identifiers are preserved from GraphQL
  when available. Missing ISRC remains unknown, not inferred.
- Profile/search cancellation and HTTP 401, 403 or 429 do not dispatch a second
  REST fallback. Persisted-query/schema failures retain the existing fallback.
- GraphQL and REST have separate shared cooldown gates. A GraphQL 429 stops
  automatic same-call retries; later callers honor the gate. REST cooldown does
  not disable working GraphQL reads.
- `Retry-After` supports seconds and HTTP-date forms. Rounding avoids retrying
  early; the existing thirty-second safety floor and twenty-four-hour cap remain.
  Clamping before milliseconds conversion avoids integer overflow.
- The documented REST `QUOTA_EXCEEDED` body is distinguished from a transient
  throttle, retained during the gate, and does not schedule history's short retry.
  Unknown error bodies are not attributed to quota exhaustion.

Reducing avoidable requests cannot manufacture access or quota Spotify does not
grant. REST-only features such as history and recommendations may still be limited.
An app-owned Web API integration would require the owner's registered application,
appropriate user authorization/scopes and quota review; no such configuration or
credential migration was introduced in this fix.

## Verification and limits

- 445 app unit tests across 70 suites passed, including request dispatch,
  cancellation, hydration eligibility, independent cooldowns, `Retry-After`, quota
  classification and metadata fixtures.
- GMS mobile and TV universal debug assembly passed.
- No dependency, database, signing, source-chain, submodule or UI-layout change.
- Authenticated backend reads succeeded, but this is not a completed library
  player interaction, download, audible playback or physical-device test.

## References

- [Spotify request-rate limits](https://developer.spotify.com/documentation/web-api/concepts/rate-limits)
- [July 2026 quota changes](https://developer.spotify.com/documentation/web-api/references/changes/july-2026)
- [Spotify's quota announcement](https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates)
- [User-profile authorization and responses](https://developer.spotify.com/documentation/web-api/reference/get-current-users-profile)
- [Independent shared-client quota report and fix](https://github.com/ots-downloader/onthespot/pull/332)
- [Independent WebPlayer request implementation](https://github.com/iTsMaaT/discord-player-spotify/blob/master/src/internal/spotify.ts)

GitHub implementations are reference material, not authority to bypass Spotify's
authorization or rate limits. Public/internal endpoint availability can change;
the existing integration is not represented as an officially guaranteed API.
