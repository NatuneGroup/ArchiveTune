/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.CrossfadeFailureTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeFailureTrackerTest {
    @Test
    fun `retries are bounded per outgoing and incoming transition`() {
        val tracker = CrossfadeFailureTracker(maximumFailures = 3)

        assertTrue(tracker.registerFailure("outgoing", "incoming"))
        assertTrue(tracker.registerFailure("outgoing", "incoming"))
        assertFalse(tracker.registerFailure("outgoing", "incoming"))
        assertFalse(tracker.canAttempt("outgoing", "incoming"))
        assertFalse(tracker.registerFailure("outgoing", "incoming"))
    }

    @Test
    fun `different target or reset starts a new failure budget`() {
        val tracker = CrossfadeFailureTracker(maximumFailures = 2)

        assertTrue(tracker.registerFailure("outgoing", "first"))
        assertFalse(tracker.registerFailure("outgoing", "first"))
        assertTrue(tracker.registerFailure("outgoing", "second"))
        assertTrue(tracker.canAttempt("outgoing", "second"))
        tracker.reset()
        assertTrue(tracker.registerFailure("outgoing", "first"))
    }
}
