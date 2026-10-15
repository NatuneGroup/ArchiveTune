# Amazon Music — how playback works and what it needs

Amazon playback resolves through a self-hosted **Amazon Music Stream API** instance that the user
adds in Integration → Amazon Music. No instance is shipped: the list starts empty, exactly like the
Tidal instance list, because the ecosystem has no working public default. An unconfigured source
resolves nothing and playback falls through to the next source.

The protocol follows the monochrome.tf web player, which removed its Amazon support on 2026-09-11
(commit `0ee81a6` "Comply with C&D" in `monochrome-music/monochrome`). Its gateway
(`music-api.geeked.wtf`, also answering as `amz.geeked.wtf`) still runs, but its gen-2 health lists
only `mono` and `tidal` sources, and its gen-1.5 routes proxy an upstream that no longer answers.

## How a track plays

1. `AmazonAudioProvider.resolveByMetadata` asks the instance for the track by metadata
   (title / every credited artist / album / duration). Two route generations are supported because
   hosts vary:
   - gen-2 `GET {base}/api/v2/track/?…&intent=stream&quality=…` (the `playback[]` envelope), which
     only accepts the operator API key as `Authorization: Bearer`, and
   - gen-1.5 `GET {base}/api/track/?track=&artist=&…` (the `stream_url` shape), which takes the
     operator token as `bypass_token` or a Turnstile JWT alone.
   Gen-2 is asked first when an API key is set. A `404` without the gen-2 envelope (route absent)
   or an authorization refusal falls back to gen-1.5; a `404` that carries the envelope means the
   track was not found. A `429` backs the host off for its `Retry-After`. Only `direct` audio
   entries whose `source` is `amazon` are used: the unified API also serves other catalogues.
2. The returned metadata is gated with `TitleMatch` before anything is downloaded. Missing fields
   stay missing; the query's values are never substituted for them.
3. The response carries a stream URL plus a raw CENC content key. The provider streams the bytes to
   a file and decrypts them on-device through a memory mapping with `AmazonCencDecryptor` (the web
   player offloads this to the browser's ClearKey/EME stack; we have no EME, so we do it
   ourselves). Only the `cenc` (AES-CTR) scheme is accepted. The protection boxes (`sinf`, `pssh`,
   `senc`/`saiz`/`saio`, `seig` sample groups) are renamed to `free`, so Media3 sees a clear
   fragmented MP4. The playback layer gets the local file through the ordinary `DirectStream` path.
   Decrypted files live in `cache/amazon/`, pruned oldest-first past 300 MB.
4. The playback layer gates the result again, and falls through to the next source on a miss.

## Authorization

Instances are commonly gated behind Cloudflare Turnstile. Two ways to satisfy them, both in the
Amazon settings screen:

- **Authorize with Turnstile** — opens `AmazonTurnstileActivity`, which renders the widget on the
  web player's own origin in a WebView, exchanges the solved token for the instance's JWT, and
  stores the JWT with the expiry its `exp` claim states (~1 h). Re-run it when the status row says
  it expired. The widget uses the web player's sitekey, so the token only verifies on hosts that
  share the web player's Turnstile secret.
- **Bypass token** — the instance operator's API key / `bypass_token`: a Bearer API key on gen-2,
  a query parameter on gen-1.5. This is the way into a self-hosted instance.

Without either, the resolver declines every track (that is the "Never authorized" state in
settings).

## Adding an instance

Instance URLs are entered one per line in the settings screen and tried in order, first success
wins. Any deployment of the Amazon Music Stream API shape works; the app probes `/health` for the
source-check row so a dead host is reported as such rather than silently failing.

## Instances from the pool

The ArchivePool serves `amazon-music` hosts as well, so the source works without the user typing a
URL: the accounts feed carries each pooled instance's `baseUrl` in plain text and its material
(`bypassToken`, `turnstileJwt`) encrypted, with the JWT's expiry also plain text so a stale token can
be skipped rather than spent on a call the instance must reject.

`AmazonInstances.merge` is what the resolver and the settings screen both read:

- the user's own entries first, then the pooled ones, de-duplicated by normalised URL;
- material never crosses hosts — a bypass token belongs to the operator who issued it and a solved
  Turnstile JWT to the origin that handed out the challenge, so the user's token is not offered to a
  pooled host (nor the pool's to the user's);
- a pooled host that arrives with no usable material is skipped, and one whose only JWT has expired
  is skipped until the pool refreshes it.

The settings screen shows how many pooled instances stand behind the user's own, and the source
check probes the same assembled list, so "Amazon is ready" describes what playback will actually do.
A pool deployment that has collected no Amazon host yet is the normal state for a fresh deployment —
the user's own instance is then the only way in.
