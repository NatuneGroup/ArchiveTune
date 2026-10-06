# Spotify Home and Listen Together regression follow-up

Reviewed on 2026-10-06.

## Spotify Home provenance and correction

Spotify Home's geometry was ported from YumaPlayer: two track rows, 240 dp track
tiles, 150 dp artwork cards, and 16 dp gutters. Commit `04ef0f333` records that
comparison. The optional glass treatment was fork-specific, not a pixel-matched
copy of YumaPlayer, and the earlier work did not establish visual parity.

The pinned YumaPlayer reference at `28aef61` uses an 8 dp quick-grid corner radius,
a 0.5 dp glass border, and a translucent material surface. ArchiveTune's Home
instead used the generic control effect with a 24 dp refraction height,
refraction proportional to card size, and the backdrop library's default shadow.
That creates a materially stronger frame around cards than the reference.

Home now has its own effect: a 0.5 dp low-alpha highlight, fixed 2 dp refraction,
no default shadow, and a translucent material tint. The Yuma-derived geometry is
unchanged. The Liquid Glass preference, Disable Blur preference, older-Android
fallback, and non-glass surfaces are preserved. Player and navigation glass are
not changed by this fix.

This is a targeted correction, not a claim that the entire current YumaPlayer
modular UI or every pixel has been imported.

## Listen Together loading and recovery

Create/join requests previously had no application-level response deadline.
Connection/server errors were not consumed by the pending-room UI, and old
WebSocket callbacks could mutate the state of a replacement connection. The UI
also connected before queueing its intended room action.

Connection attempts now have a 60-second deadline and room requests a 90-second
deadline. Failure resets pending loading and displays the reason. Requests are
serialized, decoded responses are required before completing their deadline,
and cancellation/manual reconnect invalidates stale work. A malformed response
cannot silently remove timeout protection. Established session metadata remains
available for the existing manual recovery path.

Server selection and codecs are unchanged. The Meowery remains protobuf-only;
no JSON or `client_capabilities` frame is sent to it. Chat remains JSON-server-only,
and playback resolution/catch-up behavior is not rewritten.

## Bounded protocol checks

- Hugging Face Sync and The Meowery accepted a WebSocket handshake. The ViviMusic
  Render server's handshake failed from this sandbox during the check; this is
  not a claim of a permanent global outage.
- A synthetic JSON create request on Hugging Face Sync received `room_created`.
  The test sent `leave_room` and closed its socket immediately afterward.
- A synthetic protobuf create request on The Meowery received `host_not_allowed`.
  The app now surfaces that restriction and suggests choosing another server to
  host. No hosting policy is bypassed. This test does not prove every authorized
  client/account is denied hosting.
- No real room was joined, no peers invited, no chat/playback frames sent, and no
  user credentials, room codes, or session tokens were published.

These are protocol checks, not end-to-end synchronized audio playback on Android.

## Verification and remaining gates

- 453 unit tests across 71 suites passed; zero failures, errors, or skips.
- GMS mobile and TV universal debug APKs assembled successfully.
- Deadline, stale-callback, cancellation, overlapping-request, invalid-response,
  terminal-error, and existing protobuf regressions passed.
- `git diff --check` passed. No dependency, signing, application ID, database,
  submodule, provider chain, server list, or schema change.

The software-only Android emulator booted, but package/precompile operations
stalled, screenshots were incomplete, and the emulator later aborted. No usable
fresh Spotify Home screenshot or completed Listen Together UI interaction was
established. The historical attached image is a player reference, not current
Home or Listen Together evidence. Appearance with glass on/off, retry and cancel
interactions, host approval, and synchronized playback still require running-device
QA before claiming completion or exact visual parity.
