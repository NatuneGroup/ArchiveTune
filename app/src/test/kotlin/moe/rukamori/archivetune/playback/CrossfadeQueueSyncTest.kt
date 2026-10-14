/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Queue-sync planning for the crossfade promotion: the incoming player's snapshot queue must
 * follow removals, insertions and reorders of the live queue without ever replacing the
 * playing item.
 */
class CrossfadeQueueSyncTest {
    private fun plan(
        liveIds: List<String>,
        liveTargetIndex: Int,
        incomingIds: List<String>,
        incomingCurrentIndex: Int,
    ) = CrossfadeQueueSync.plan(
        liveIds = liveIds,
        liveTargetIndex = liveTargetIndex,
        incomingIds = incomingIds,
        incomingCurrentIndex = incomingCurrentIndex,
    )

    @Test
    fun alreadyInSync_producesAnEmptyPlan() {
        val result =
            plan(
                liveIds = listOf("a", "b", "c", "d"),
                liveTargetIndex = 1,
                incomingIds = listOf("a", "b", "c", "d"),
                incomingCurrentIndex = 1,
            )
        assertEquals(1, result?.incomingCurrentIndex)
        assertNull(result?.beforeIds)
        assertNull(result?.afterIds)
    }

    @Test
    fun itemRemovedAfterTheTarget_isPlannedForTheAfterRange() {
        val result =
            plan(
                liveIds = listOf("a", "b", "d"),
                liveTargetIndex = 1,
                incomingIds = listOf("a", "b", "c", "d"),
                incomingCurrentIndex = 1,
            )
        assertEquals(listOf("d"), result?.afterIds)
        assertNull(result?.beforeIds)
    }

    @Test
    fun itemInsertedBeforeTheTarget_isPlannedForTheBeforeRange() {
        val result =
            plan(
                liveIds = listOf("x", "a", "b", "c"),
                liveTargetIndex = 2,
                incomingIds = listOf("a", "b", "c"),
                incomingCurrentIndex = 1,
            )
        assertEquals(listOf("x", "a"), result?.beforeIds)
        assertNull(result?.afterIds)
    }

    @Test
    fun reorderOfUpcomingItems_isPlannedForTheAfterRange() {
        val result =
            plan(
                liveIds = listOf("a", "b", "d", "c"),
                liveTargetIndex = 1,
                incomingIds = listOf("a", "b", "c", "d"),
                incomingCurrentIndex = 1,
            )
        assertEquals(listOf("d", "c"), result?.afterIds)
    }

    @Test
    fun targetMissingFromTheLiveQueue_returnsNull() {
        assertNull(
            plan(
                liveIds = listOf("a", "c"),
                liveTargetIndex = 5,
                incomingIds = listOf("a", "b", "c"),
                incomingCurrentIndex = 1,
            ),
        )
        assertNull(
            plan(
                liveIds = emptyList(),
                liveTargetIndex = 0,
                incomingIds = listOf("a", "b"),
                incomingCurrentIndex = 1,
            ),
        )
    }

    @Test
    fun incomingCurrentItemNotTheTarget_returnsNull() {
        assertNull(
            plan(
                liveIds = listOf("a", "b", "c"),
                liveTargetIndex = 1,
                incomingIds = listOf("a", "x", "c"),
                incomingCurrentIndex = 1,
            ),
        )
    }

    @Test
    fun duplicateMediaIds_areResolvedByIndexNotIdentity() {
        // The same song queued twice (b): only the second occurrence is the target.
        val result =
            plan(
                liveIds = listOf("a", "b", "c", "b", "e"),
                liveTargetIndex = 3,
                incomingIds = listOf("a", "b", "c", "b", "d"),
                incomingCurrentIndex = 3,
            )
        // before range [a, b, c] unchanged (null); after range loses "d", gains "e"
        assertNull(result?.beforeIds)
        assertEquals(listOf("e"), result?.afterIds)
    }

    @Test
    fun incomingCurrentIndexOutOfRange_returnsNull() {
        assertNull(
            plan(
                liveIds = listOf("a", "b"),
                liveTargetIndex = 1,
                incomingIds = listOf("a", "b"),
                incomingCurrentIndex = 9,
            ),
        )
    }

    @Test
    fun lastUpcomingItemRemoved_plansAnEmptyAfterRangeNotNoChange() {
        // Regression: prepared [A, B, C] with incoming B; removing C during the fade left the
        // desired after-range empty, which used to read as "unchanged" and C reappeared.
        val result =
            plan(
                liveIds = listOf("a", "b"),
                liveTargetIndex = 1,
                incomingIds = listOf("a", "b", "c"),
                incomingCurrentIndex = 1,
            )
        assertEquals(emptyList<String>(), result?.afterIds)
        assertNull(result?.beforeIds)
        assertEquals(1, result?.syncedCurrentIndex)
    }

    @Test
    fun playedPrefixRemoved_plansAnEmptyBeforeRangeAndShiftsTheCurrentIndex() {
        // Regression: prepared [P, A, B], incoming B at index 2; removing P leaves live [A, B]
        // with B at 1. The promotion must read the playing item at the shifted index.
        val result =
            plan(
                liveIds = listOf("a", "b"),
                liveTargetIndex = 1,
                incomingIds = listOf("p", "a", "b"),
                incomingCurrentIndex = 2,
            )
        assertEquals(listOf("a"), result?.beforeIds)
        assertEquals(1, result?.syncedCurrentIndex)

        val emptied =
            plan(
                liveIds = listOf("b", "c"),
                liveTargetIndex = 0,
                incomingIds = listOf("p", "b", "c"),
                incomingCurrentIndex = 1,
            )
        assertEquals(emptyList<String>(), emptied?.beforeIds)
        assertNull(emptied?.afterIds)
        assertEquals(0, emptied?.syncedCurrentIndex)
    }

    @Test
    fun insertionBeforeTheTarget_shiftsTheSyncedCurrentIndexForward() {
        val result =
            plan(
                liveIds = listOf("x", "a", "b", "c"),
                liveTargetIndex = 2,
                incomingIds = listOf("a", "b", "c"),
                incomingCurrentIndex = 1,
            )
        assertEquals(2, result?.syncedCurrentIndex)
    }
}
