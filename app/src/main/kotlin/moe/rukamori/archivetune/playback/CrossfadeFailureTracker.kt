/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

/**
 * What may happen after a fade attempt failed for the current (outgoing, incoming) pair:
 * re-arm the blend while attempts remain, or stop fading and hard-cut to the next song.
 */
internal enum class CrossfadeRetryDecision { RETRY_FADE, HARD_CUT }

/**
 * Pure bookkeeping for one (outgoing, incoming) transition pair:
 *
 * - **Once per pair**: a pair whose transition completed (the incoming song was promoted)
 *   never fades again until the player moves to another song and back.
 * - **Bounded retries**: a failing pair may re-arm the blend only a limited number of times;
 *   after the last failed attempt the answer is [CrossfadeRetryDecision.HARD_CUT] — a plain
 *   cut to the next song, never a fade loop that replays the same segments.
 */
internal class CrossfadeFailureTracker(
    private val maximumFailures: Int = 3,
) {
    private data class TransitionKey(
        val outgoingMediaId: String?,
        val incomingMediaId: String?,
    )

    private var failedTransition: TransitionKey? = null
    private var failureCount = 0
    private var completedTransition: TransitionKey? = null

    init {
        require(maximumFailures > 0)
    }

    /**
     * Records a failed fade attempt. [CrossfadeRetryDecision.RETRY_FADE] while attempts
     * remain, [CrossfadeRetryDecision.HARD_CUT] once the budget is spent.
     */
    fun registerFailure(outgoingMediaId: String?, incomingMediaId: String?): CrossfadeRetryDecision {
        val transition = TransitionKey(outgoingMediaId, incomingMediaId)
        if (failedTransition != transition) {
            failedTransition = transition
            failureCount = 0
        }
        failureCount++
        return if (failureCount < maximumFailures) CrossfadeRetryDecision.RETRY_FADE else CrossfadeRetryDecision.HARD_CUT
    }

    /**
     * Records that the transition for this pair completed (the incoming song was promoted).
     * The pair cannot trigger another blend: the hand-off already happened, and a second one
     * would replay the songs over each other.
     */
    fun registerSuccess(outgoingMediaId: String?, incomingMediaId: String?) {
        completedTransition = TransitionKey(outgoingMediaId, incomingMediaId)
        failedTransition = null
        failureCount = 0
    }

    fun canAttempt(outgoingMediaId: String?, incomingMediaId: String?): Boolean {
        val transition = TransitionKey(outgoingMediaId, incomingMediaId)
        // A repeat-one pair (same id on both sides) is exempt from the once-per-transition
        // rule: replaying the same item IS the requested mode, and its wrap-around transition
        // must keep crossfading.
        if (completedTransition == transition && transition.outgoingMediaId != transition.incomingMediaId) {
            return false
        }
        return failedTransition != transition || failureCount < maximumFailures
    }

    /** The player moved to another song: the bookkeeping applies to a new transition. */
    fun reset() {
        failedTransition = null
        failureCount = 0
        completedTransition = null
    }
}
