/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Once-per-transition guard and bounded-retry-then-hard-cut for one (outgoing, incoming)
 * song pair (VAL-XFADE-009): a transition triggers at most once per pair, failed attempts
 * retry a bounded number of times and then hard-cut — never loop.
 */
class CrossfadeFailureTrackerTest {
    private fun tracker() = CrossfadeFailureTracker(maximumFailures = 3)

    @Test
    fun failedAttempts_retryWhileBudgetRemains_thenHardCut() {
        val tracker = tracker()
        assertEquals(CrossfadeRetryDecision.RETRY_FADE, tracker.registerFailure("A", "B"))
        assertEquals(CrossfadeRetryDecision.RETRY_FADE, tracker.registerFailure("A", "B"))
        assertEquals(CrossfadeRetryDecision.HARD_CUT, tracker.registerFailure("A", "B"))
        // The budget is spent: no further fade may be armed for the pair.
        assertFalse(tracker.canAttempt("A", "B"))
    }

    @Test
    fun hardCutNeverLoopsBackIntoAFade() {
        val tracker = tracker()
        repeat(3) { tracker.registerFailure("A", "B") }
        // Even more failures keep answering HARD_CUT — the loop stays cut off.
        assertEquals(CrossfadeRetryDecision.HARD_CUT, tracker.registerFailure("A", "B"))
        assertFalse(tracker.canAttempt("A", "B"))
    }

    @Test
    fun completedTransition_neverFadesTheSamePairAgain() {
        val tracker = tracker()
        tracker.registerSuccess("A", "B")
        assertFalse(tracker.canAttempt("A", "B"))
        // An unrelated pair is unaffected.
        assertTrue(tracker.canAttempt("B", "C"))
    }

    @Test
    fun completedTransition_successIsRecordedEvenAfterFailures() {
        val tracker = tracker()
        tracker.registerFailure("A", "B")
        tracker.registerSuccess("A", "B")
        // The pair handed off: no second blend for it, retries or not.
        assertFalse(tracker.canAttempt("A", "B"))
    }

    @Test
    fun songChange_resetsTheBudgetForTheNextTransition() {
        val tracker = tracker()
        repeat(3) { tracker.registerFailure("A", "B") }
        assertFalse(tracker.canAttempt("A", "B"))
        tracker.reset()
        assertTrue(tracker.canAttempt("A", "B"))
        assertEquals(CrossfadeRetryDecision.RETRY_FADE, tracker.registerFailure("A", "B"))
    }

    @Test
    fun differentPair_getsAFreshBudget() {
        val tracker = tracker()
        repeat(3) { tracker.registerFailure("A", "B") }
        assertTrue(tracker.canAttempt("B", "C"))
        assertEquals(CrossfadeRetryDecision.RETRY_FADE, tracker.registerFailure("B", "C"))
    }

    @Test
    fun repeatOnePair_isExemptFromTheOncePerTransitionRule() {
        val tracker = tracker()
        // Repeat-one crossfades the song into itself on every wrap-around: each cycle is a
        // legitimate new transition of the same (id, id) pair.
        tracker.registerSuccess("A", "A")
        assertTrue(tracker.canAttempt("A", "A"))
    }

    @Test
    fun nullIds_areTrackedLikeAnyOtherPair() {
        val tracker = tracker()
        tracker.registerSuccess(null, "B")
        assertFalse(tracker.canAttempt(null, "B"))
        assertTrue(tracker.canAttempt("A", "B"))
    }
}
