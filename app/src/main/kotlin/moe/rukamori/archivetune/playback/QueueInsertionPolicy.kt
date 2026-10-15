/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal enum class UserQueuedKind {
    PLAY_NEXT,
    ADD_TO_QUEUE,
}

internal const val EXTRA_USER_QUEUED_KIND = "archivetune.userQueuedKind"

/**
 * How many of the upcoming songs, in play order, a new [adding] goes after.
 *
 * [upcoming] holds the kind of each hand-queued song right after the current one, ending at the
 * first song nobody queued by hand: the radio or autoplay tail is never skipped. The block reads
 * `[Play next…][Add to queue…]`, so Play next lines up behind earlier Play next songs but still
 * ahead of Add to queue songs, and Add to queue goes after the whole block (#172).
 */
internal fun userQueueSkipCount(
    upcoming: List<UserQueuedKind>,
    adding: UserQueuedKind,
): Int =
    when (adding) {
        UserQueuedKind.PLAY_NEXT -> upcoming.takeWhile { it == UserQueuedKind.PLAY_NEXT }.size
        UserQueuedKind.ADD_TO_QUEUE -> upcoming.size
    }
