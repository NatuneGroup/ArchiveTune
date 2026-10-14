# Prioritized backlog

## Playback and performance

- Collect Perfetto/Media3 analytics for the reported second-track stop and compare crossfade, source resolution, decoder, cache, artwork, and thermal costs.
- Review upstream baseline-profile and preload-loop fixes individually against fork invariants.
- Validate Tidal instance recovery and Apple Music Widevine failure messaging so a failed source falls through without leaving a stalled player.

## Apple Music experience

- Split the current global experience flag into independent playlist, player-menu, lyrics-menu, Home, Library, and navigation options.
- Implement a non-locking Apple Music preset that writes those independent options and does not overwrite later manual choices.
- Add Apple-style Home/library parity and Spotify Recommended for Today where the data source supports it.
- Evaluate upstream public catalog search separately from authenticated Apple playback.

## Spotify and ecosystem

- Expand read-only Recently Played import/presentation only if users need more than the provider limit.
- Do not embed SpotifySync’s hidden WebView playback without an explicit privacy, battery, account, and licensing decision.
- Review Stash playlist/library sync ideas for a user-visible, cancellable, authenticated flow.

## Release

- Complete physical Nightly QA, thermal comparison, update installation, and provider fallback checks.
- Promote `dev` to `main` only after QA evidence is recorded in the active PR.
