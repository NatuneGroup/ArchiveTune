/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.playback.UserQueuedKind.ADD_TO_QUEUE
import moe.rukamori.archivetune.playback.UserQueuedKind.PLAY_NEXT
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueInsertionPolicyTest {
    @Test
    fun noHandQueuedSongs_insertsStraightAfterTheCurrentSong() {
        assertEquals(0, userQueueSkipCount(emptyList(), PLAY_NEXT))
        assertEquals(0, userQueueSkipCount(emptyList(), ADD_TO_QUEUE))
    }

    @Test
    fun playNext_goesBehindEarlierPlayNextSongs() {
        assertEquals(1, userQueueSkipCount(listOf(PLAY_NEXT), PLAY_NEXT))
        assertEquals(3, userQueueSkipCount(listOf(PLAY_NEXT, PLAY_NEXT, PLAY_NEXT), PLAY_NEXT))
    }

    @Test
    fun playNext_staysAheadOfAddToQueueSongs() {
        assertEquals(0, userQueueSkipCount(listOf(ADD_TO_QUEUE, ADD_TO_QUEUE), PLAY_NEXT))
        assertEquals(2, userQueueSkipCount(listOf(PLAY_NEXT, PLAY_NEXT, ADD_TO_QUEUE), PLAY_NEXT))
    }

    @Test
    fun addToQueue_goesBehindTheWholeHandQueuedBlock() {
        assertEquals(1, userQueueSkipCount(listOf(ADD_TO_QUEUE), ADD_TO_QUEUE))
        assertEquals(3, userQueueSkipCount(listOf(PLAY_NEXT, ADD_TO_QUEUE, ADD_TO_QUEUE), ADD_TO_QUEUE))
    }

    @Test
    fun successiveAdds_keepTheOrderTheyWereAddedIn() {
        val upcoming = mutableListOf<UserQueuedKind>()
        val adds = listOf(ADD_TO_QUEUE, PLAY_NEXT, ADD_TO_QUEUE, PLAY_NEXT)
        adds.forEach { kind -> upcoming.add(userQueueSkipCount(upcoming, kind), kind) }
        assertEquals(listOf(PLAY_NEXT, PLAY_NEXT, ADD_TO_QUEUE, ADD_TO_QUEUE), upcoming)
    }
}
