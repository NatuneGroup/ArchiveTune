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

/**
 * No-rewind restore for the crossfade hand-off (VAL-XFADE-009): the restore seek never
 * rewinds a finished song to an already-played point, and the blend's cue is never applied
 * to a song the fade was not armed for.
 */
class CrossfadeRestoreSeekTest {
    private fun seek(
        liveIds: List<String>,
        targetMediaId: String? = "B",
        plannedTargetIndex: Int = 1,
        currentIndex: Int = 0,
        repeatOne: Boolean = false,
        cueMs: Long = 0L,
    ): CrossfadeRestoreSeek? =
        CrossfadeHandoffPolicy.restoreSeek(
            targetMediaId = targetMediaId,
            plannedTargetIndex = plannedTargetIndex,
            liveIds = liveIds,
            currentIndex = currentIndex,
            repeatOne = repeatOne,
            cueMs = cueMs,
            unsetIndex = C.INDEX_UNSET,
        )

    @Test
    fun plannedIndexStillLive_entersTheTargetAtTheCue() {
        assertEquals(CrossfadeRestoreSeek(1, 30_000L), seek(liveIds = listOf("A", "B", "C"), cueMs = 30_000L))
    }

    @Test
    fun stalePlan_resolvesTheTargetWhereItMovedLater() {
        // A queue edit shifted the target from index 1 to index 2.
        assertEquals(CrossfadeRestoreSeek(2, 30_000L), seek(liveIds = listOf("A", "X", "B"), cueMs = 30_000L))
    }

    @Test
    fun stalePlanPointingAtThePlayingCopy_neverRewindsTheOutgoingSong() {
        // The same song is queued twice and the stale plan points at the already-played
        // copy the outgoing player sits on: the restore must advance to the next copy.
        val result = seek(liveIds = listOf("B", "B", "C"), targetMediaId = "B", plannedTargetIndex = 0, currentIndex = 0)
        assertEquals(CrossfadeRestoreSeek(1, 0L), result)
    }

    @Test
    fun restoreNeverResolvesToTheCurrentIndexForANonRepeatFade() {
        val result = seek(liveIds = listOf("A", "A"), targetMediaId = "A", plannedTargetIndex = 0, currentIndex = 0)
        assertEquals(1, result?.index)
    }

    @Test
    fun targetGone_returnsNullInsteadOfApplyingTheStaleCueToAnotherSong() {
        // The blend's cue belongs to song B; when B no longer exists it must not position
        // song C mid-track — the caller makes a plain hard cut to the next item instead.
        assertNull(seek(liveIds = listOf("A", "C"), targetMediaId = "B", plannedTargetIndex = 1))
    }

    @Test
    fun targetMediaIdNull_returnsNull() {
        assertNull(seek(liveIds = listOf("A", "C"), targetMediaId = null))
    }

    @Test
    fun repeatOne_replaysTheSameItemAtTheCue() {
        assertEquals(
            CrossfadeRestoreSeek(0, 45_000L),
            seek(
                liveIds = listOf("A"),
                targetMediaId = "A",
                plannedTargetIndex = 0,
                currentIndex = 0,
                repeatOne = true,
                cueMs = 45_000L,
            ),
        )
    }

    @Test
    fun classicFade_cueZero_entersTheTargetAtZero() {
        assertEquals(CrossfadeRestoreSeek(1, 0L), seek(liveIds = listOf("A", "B", "C"), cueMs = 0L))
    }

    @Test
    fun plannedIndexUnset_fallsBackToTheScan() {
        assertEquals(
            CrossfadeRestoreSeek(2, 0L),
            seek(liveIds = listOf("A", "X", "B"), targetMediaId = "B", plannedTargetIndex = C.INDEX_UNSET),
        )
    }
}
