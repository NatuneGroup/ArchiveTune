/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.CrossfadePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeClockPolicyTest {
    @Test
    fun `smart fade progress follows outgoing media position`() {
        assertEquals(5_000L, CrossfadePolicy.outgoingElapsedMs(12_000L, 17_000L, 8_000L))
    }

    @Test
    fun `outgoing clock elapsed time clamps at both boundaries`() {
        assertEquals(0L, CrossfadePolicy.outgoingElapsedMs(12_000L, 11_000L, 8_000L))
        assertEquals(8_000L, CrossfadePolicy.outgoingElapsedMs(12_000L, 25_000L, 8_000L))
        assertEquals(0L, CrossfadePolicy.outgoingElapsedMs(12_000L, 17_000L, 0L))
    }
}
