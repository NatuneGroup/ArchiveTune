/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.CrossfadePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `fade clock follows media time and freezes while incoming audio is stalled`() {
        val afterFastPlayback =
            CrossfadePolicy.advanceFadeElapsedMs(
                elapsedMs = 0L,
                previousPositionMs = 10_000L,
                currentPositionMs = 12_000L,
                durationMs = 5_000L,
                incomingAdvancing = true,
            )
        assertEquals(2_000L, afterFastPlayback)

        val whileStalled =
            CrossfadePolicy.advanceFadeElapsedMs(
                elapsedMs = afterFastPlayback,
                previousPositionMs = 12_000L,
                currentPositionMs = 14_000L,
                durationMs = 5_000L,
                incomingAdvancing = false,
            )
        assertEquals(afterFastPlayback, whileStalled)

        val afterSlowPlaybackResumes =
            CrossfadePolicy.advanceFadeElapsedMs(
                elapsedMs = whileStalled,
                previousPositionMs = 14_000L,
                currentPositionMs = 14_500L,
                durationMs = 5_000L,
                incomingAdvancing = true,
            )
        assertEquals(2_500L, afterSlowPlaybackResumes)
    }

    @Test
    fun `incoming stall aborts only after grace while playback is requested`() {
        assertFalse(
            CrossfadePolicy.shouldAbortForIncomingStall(
                playbackRequested = true,
                incomingAdvancing = false,
                stallElapsedMs = 249L,
                maximumStallMs = 250L,
            ),
        )
        assertTrue(
            CrossfadePolicy.shouldAbortForIncomingStall(
                playbackRequested = true,
                incomingAdvancing = false,
                stallElapsedMs = 250L,
                maximumStallMs = 250L,
            ),
        )
        assertFalse(
            CrossfadePolicy.shouldAbortForIncomingStall(
                playbackRequested = false,
                incomingAdvancing = false,
                stallElapsedMs = 500L,
                maximumStallMs = 250L,
            ),
        )
        assertFalse(
            CrossfadePolicy.shouldAbortForIncomingStall(
                playbackRequested = true,
                incomingAdvancing = true,
                stallElapsedMs = 500L,
                maximumStallMs = 250L,
            ),
        )
    }
}
