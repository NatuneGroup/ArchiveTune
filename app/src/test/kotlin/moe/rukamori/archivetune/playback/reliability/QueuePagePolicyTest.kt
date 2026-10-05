/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.queues.appendableQueuePage
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePagePolicyTest {
    @Test
    fun spotifyContinuationKeepsTheFirstNewTrack() {
        assertEquals(listOf("one", "two"), appendableQueuePage(listOf("one", "two"), repeatsCurrentItem = false))
    }

    @Test
    fun overlappingRadioContinuationKeepsItsExistingDeduplication() {
        assertEquals(listOf("next"), appendableQueuePage(listOf("current", "next"), repeatsCurrentItem = true))
    }

    @Test
    fun emptyContinuationIsSafeForBothContracts() {
        assertEquals(emptyList<String>(), appendableQueuePage(emptyList<String>(), repeatsCurrentItem = false))
        assertEquals(emptyList<String>(), appendableQueuePage(emptyList<String>(), repeatsCurrentItem = true))
    }
}
