/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.resolveSeekByTargetMs
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekByPolicyTest {
    @Test
    fun `seek delta clamps at the start and known duration`() {
        assertEquals(0L, resolveSeekByTargetMs(2_000L, -5_000L, 10_000L))
        assertEquals(10_000L, resolveSeekByTargetMs(8_000L, 5_000L, 10_000L))
    }

    @Test
    fun `unknown duration permits forward seek and large deltas saturate`() {
        assertEquals(8_000L, resolveSeekByTargetMs(3_000L, 5_000L, -1L))
        assertEquals(Long.MAX_VALUE, resolveSeekByTargetMs(Long.MAX_VALUE, 1L, -1L))
        assertEquals(0L, resolveSeekByTargetMs(0L, Long.MIN_VALUE, -1L))
    }
}
