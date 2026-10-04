/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal class CrossfadeFailureTracker(
    private val maximumFailures: Int = 3,
) {
    private data class TransitionKey(
        val outgoingMediaId: String?,
        val incomingMediaId: String?,
    )

    private var failedTransition: TransitionKey? = null
    private var failureCount = 0

    init {
        require(maximumFailures > 0)
    }

    fun registerFailure(outgoingMediaId: String?, incomingMediaId: String?): Boolean {
        val transition = TransitionKey(outgoingMediaId, incomingMediaId)
        if (failedTransition != transition) {
            failedTransition = transition
            failureCount = 0
        }
        failureCount++
        return failureCount < maximumFailures
    }

    fun canAttempt(outgoingMediaId: String?, incomingMediaId: String?): Boolean =
        failedTransition != TransitionKey(outgoingMediaId, incomingMediaId) || failureCount < maximumFailures

    fun reset() {
        failedTransition = null
        failureCount = 0
    }
}
