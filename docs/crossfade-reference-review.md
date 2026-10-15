# Crossfade reference review

Reviewed on 2026-10-04 against pinned Kotlin player sources.

## Findings and changes

- The regular crossfade advanced from elapsed wall time while Automix used outgoing media position. Playback-speed changes therefore stretched or compressed regular fades relative to the track. Both paths now accumulate outgoing media-position deltas only while the incoming player is ready and playing. Progress freezes during a stall; after a 250 ms grace, recovery restores the outgoing gain and allows a bounded retry.
- Promotion previously accepted any non-terminal secondary, including a buffering player or one that had already advanced beyond the intended queue item. Promotion now requires the target to be the secondary's current item and, while playback is requested, requires it to be ready and playing. Failed handoffs restore the outgoing player's volume and end behavior, resume through the existing ended-playback policy when allowed, and retain bounded retries.
- Existing guards remain in place: crossfade and Automix are disabled in Listen Together rooms; bit-perfect USB output is only activated when crossfade and Automix preferences are off; audio offload is disabled for crossfade/Automix; seeks and manual skips cancel stale work; a successful promotion schedules the following transition.

## Pinned references

- Metrolist source SHA: `f758c86de9278481bcead7b0120786ca46398251`. [`MusicService.kt`](https://github.com/MetrolistGroup/Metrolist/blob/f758c86d/app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt#L4791-L4817) schedules by media position and reschedules on seeks; [the fade and swap](https://github.com/MetrolistGroup/Metrolist/blob/f758c86d/app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt#L4881-L4975) accounts for playback speed.
- [SimpMusic app](https://github.com/maxrave-dev/SimpMusic/tree/126c0fd3) SHA: `126c0fd3 0216dabc 09a949eb c65291ea 279a0d7f`. Its `core` gitlink matches core SHA `2cb77b8d c37d3dc9 982a9573 04225ebc 1d97bfdd`. [`CrossfadeExoPlayerAdapter.kt`](https://github.com/maxrave-dev/core/blob/2cb77b8d/media/media3/src/main/java/com/maxrave/media3/exoplayer/CrossfadeExoPlayerAdapter.kt#L2257-L2283) scales the remaining overlap by speed, uses equal-power gains at [lines 2333-2458](https://github.com/maxrave-dev/core/blob/2cb77b8d/media/media3/src/main/java/com/maxrave/media3/exoplayer/CrossfadeExoPlayerAdapter.kt#L2333-L2458), and commits the incoming item for mid-fade user actions at [lines 2007-2044](https://github.com/maxrave-dev/core/blob/2cb77b8d/media/media3/src/main/java/com/maxrave/media3/exoplayer/CrossfadeExoPlayerAdapter.kt#L2007-L2044).
- Stash source SHA: `1a37fdc7 ee655f9e 9d38e5c1 93c988e9 ab976a5d`. [`CrossfadeEngine.kt`](https://github.com/rawnaldclark/Stash/blob/1a37fdc7/core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeEngine.kt#L168-L238) primes a spare player and role-swaps after the overlap; [`StashPlaybackService.kt`](https://github.com/rawnaldclark/Stash/blob/1a37fdc7/core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt#L805-L843) prepares ahead and waits for fade-length buffering.

ArchiveTune retains its existing equal-power curve, secondary-player promotion, resolver chain, and source routing; no reference player architecture was copied wholesale.
