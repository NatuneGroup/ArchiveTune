/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Position-carry maths for the CARRY_TO_PRIMARY hand-off action. */
class CrossfadeCarriedPositionTest {
    private fun carry(
        incomingPositionMs: Long,
        cueMs: Long = 0L,
        targetDurationMs: Long = 121_845L,
        endGuardMs: Long = 150L,
        minCarryMs: Long = CrossfadeHandoffPolicy.MIN_CARRY_MS,
    ): Long? =
        CrossfadeHandoffPolicy.carriedPositionMs(
            incomingPositionMs = incomingPositionMs,
            cueMs = cueMs,
            targetDurationMs = targetDurationMs,
            unsetTime = C.TIME_UNSET,
            endGuardMs = endGuardMs,
            minCarryMs = minCarryMs,
        )

    @Test
    fun plainCarry_keepsTheHeardPosition() {
        assertEquals(4_950L, carry(4_950L))
    }

    @Test
    fun belowTheMinimumCarry_returnsNull() {
        // The caller then seeks to the cue (the song start for a classic fade).
        assertNull(carry(1_499L))
    }

    @Test
    fun cueOffset_minimumCarryIsMeasuredPastTheCue() {
        // heard = 900ms past the cue: below the minimum
        assertNull(carry(incomingPositionMs = 30_900L, cueMs = 30_000L))
        // heard = 3000ms past the cue: carry the ABSOLUTE position, not the heard time
        assertEquals(33_000L, carry(incomingPositionMs = 33_000L, cueMs = 30_000L))
    }

    @Test
    fun carryIsClampedToTheDurationMinusTheEndGuard() {
        assertEquals(121_695L, carry(incomingPositionMs = 121_900L))
    }

    @Test
    fun unknownDurationAppliesNoUpperClamp() {
        assertEquals(121_900L, carry(incomingPositionMs = 121_900L, targetDurationMs = C.TIME_UNSET))
        assertEquals(999_999L, carry(incomingPositionMs = 999_999L, targetDurationMs = 0L))
        assertEquals(999_999L, carry(incomingPositionMs = 999_999L, targetDurationMs = -5L))
    }

    @Test
    fun negativePosition_returnsNull() {
        assertNull(carry(-1L))
    }

    @Test
    fun durationShorterThanTheEndGuard_neverProducesANegativeClamp() {
        assertEquals(0L, carry(incomingPositionMs = 5_000L, targetDurationMs = 100L, endGuardMs = 150L))
    }
}
