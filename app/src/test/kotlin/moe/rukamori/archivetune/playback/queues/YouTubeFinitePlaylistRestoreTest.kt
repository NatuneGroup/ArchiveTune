/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.extensions.toContinuationQueue
import moe.rukamori.archivetune.extensions.toPersistQueue
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.models.QueueData
import moe.rukamori.archivetune.models.QueueType
import org.junit.Assert.*
import org.junit.Test

class YouTubeFinitePlaylistRestoreTest {
    @Test fun snapshotsAfterPageOneAndTwoResumeTo135WithoutLosingManualPositionOrShuffleOrder() = runTest {
        for (pages in listOf(1, 2)) {
            val source = FiniteFixtureSource()
            val queue = finiteQueue()
            queue.accept(queue.proposeInitial(source))
            if (pages == 2) queue.accept(queue.proposeNext(source))
            val manual = finiteSongs().last().toFixtureMetadata().copy(id = "manual", setVideoId = null)
            val timeline = queue.snapshot.entries.map { it.metadata }.toMutableList().apply { add(3, manual) }
            val keys = queue.snapshot.entries.map { it.key as String? }.toMutableList().apply { add(3, null) }
            val traversal = timeline.indices.reversed().toList()
            val saved = queue.toPersistQueue("Saved", timeline, 44, 87_321L, keys).copy(
                shuffleOrder = traversal, manualQueueKinds = timeline.indices.map { if (it == 3) 1 else null })
            val restored = roundTripFinite(saved)
            assertEquals(saved.items, restored.items)
            assertEquals(saved.queueData, restored.queueData)
            assertEquals(saved.mediaItemIndex, restored.mediaItemIndex)
            assertEquals(saved.queueType::class, restored.queueType::class)
            val restoredData = restored.queueData as QueueData.YouTubePlaylistData
            assertEquals(queue.snapshot, restoredData.snapshot)
            assertEquals(keys, restoredData.timelineOccurrences)
            assertEquals(traversal, restored.shuffleOrder)
            assertEquals(87_321L, restored.position)
            assertEquals("set44", restored.items[restored.mediaItemIndex].setVideoId)
            assertEquals(1, restored.manualQueueKinds!![3])
            val resumed = restored.toContinuationQueue() as YouTubePlaylistQueue
            source.calls.clear()
            while (resumed.hasNextPage()) resumed.accept(resumed.proposeNext(source))
            assertEquals(135, resumed.snapshot.entries.size)
            assertEquals(135, resumed.snapshot.entries.map { it.key }.toSet().size)
            assertEquals(3, resumed.snapshot.entries.count { it.metadata.id == "duplicate" })
            assertEquals(if (pages == 1) listOf("next:p2:fixture", "next:p3:fixture") else listOf("next:p3:fixture"), source.calls)
            assertEquals(saved.items, restored.items)
            assertEquals(traversal, restored.shuffleOrder)
            assertTrue(resumed.confirmedExhausted)
        }
    }

    @Test fun failedSnapshotRoundTripsLastAcceptedTokenAndCanRetryWithoutDuplicatingKnownEntries() = runTest {
        val queue = finiteQueue()
        val source = FiniteFixtureSource()
        queue.accept(queue.proposeInitial(source))
        queue.fail()
        val saved = queue.toPersistQueue("Saved", queue.snapshot.entries.map { it.metadata }, 1, 123L,
            queue.snapshot.entries.map { it.key })
        val restored = roundTripFinite(saved).toContinuationQueue() as YouTubePlaylistQueue
        assertEquals(FinitePlaylistPhase.FAILED, restored.snapshot.phase)
        assertEquals("p2", restored.snapshot.continuation)
        restored.accept(restored.proposeNext(source))
        assertEquals(90, restored.snapshot.entries.size)
        assertEquals(90, restored.snapshot.entries.map { it.key }.toSet().size)
    }

    @Test fun existingNonfiniteRecordShapesDecodeAndRadioRetainsItsQueueKind() {
        for (record in listOf(PersistQueue("List", emptyList(), 0, 123L),
            PersistQueue("Radio", emptyList(), 0, 456L, QueueType.YOUTUBE,
                QueueData.YouTubeData(videoId = "video", playlistId = "RDfixture", followAutomixPreview = true)),
            PersistQueue("Album", emptyList(), 0, 789L, QueueType.YOUTUBE_ALBUM_RADIO,
                QueueData.YouTubeAlbumRadioData("album", continuation = "token")))) {
            val decoded = roundTripFinite(record)
            assertEquals(record.items, decoded.items)
            assertEquals(record.queueData, decoded.queueData)
            assertEquals(record.position, decoded.position)
            assertEquals(record.queueType::class, decoded.queueType::class)
            assertNull(decoded.manualQueueKinds)
            assertNull(decoded.shuffleOrder)
            if (record.queueType is QueueType.YOUTUBE) assertTrue(decoded.toContinuationQueue() is YouTubeQueue)
        }
    }

    @Test fun legacyFiniteRecordRestoresUnknownAndRetainsSavedTimelineForStagedRecovery() {
        val items = finiteSongs().take(45).map { it.toFixtureMetadata() }
        val record = roundTripFinite(PersistQueue("Legacy", items, 43, 91_234L,
            QueueType.YOUTUBE, QueueData.YouTubeData(playlistId = "VLfixture")))
        val queue = record.toContinuationQueue() as YouTubePlaylistQueue
        assertEquals(FinitePlaylistPhase.UNKNOWN, queue.snapshot.phase)
        assertEquals(items, queue.legacyItems)
        assertEquals(43, record.mediaItemIndex)
        assertEquals(91_234L, record.position)
        assertFalse(queue.confirmedExhausted)
    }

    @Test fun legacyUniqueWindowInsertsMissingPrefixAndSuffixAndMapsPlayingOccurrence() = runTest {
        val source = completeEntries()
        val saved = source.subList(43, 46).map { it.metadata }
        val aligned = alignLegacyFinitePlaylist(saved, source)!!
        assertEquals(source, aligned.entries)
        assertEquals(listOf(43, 44, 45), aligned.savedIndices)
        assertSame(saved[1], aligned.entries[aligned.savedIndices[1]].metadata)
    }

    @Test fun legacyAnonymousDuplicatesSourceRemovalAndReorderingAreNotGuessed() = runTest {
        val source = completeEntries()
        val anonymousDuplicate = source[1].metadata.copy(setVideoId = null)
        assertNull(alignLegacyFinitePlaylist(listOf(anonymousDuplicate), source))
        assertNull(alignLegacyFinitePlaylist(listOf(source[45].metadata, source[43].metadata), source))
        assertNull(alignLegacyFinitePlaylist(listOf(source[43].metadata), source.filterNot { it.key == source[43].key }))
        assertNull(alignLegacyFinitePlaylist(listOf(source[43].metadata, source[43].metadata), source))
    }

    @Test fun legacyOnlyPositivelyMarkedManualEntriesMayBeUnmatched() = runTest {
        val source = completeEntries()
        val manual = source[0].metadata.copy(id = "manual", setVideoId = null)
        val saved = listOf(source[43].metadata, manual, source[44].metadata)
        assertNull(alignLegacyFinitePlaylist(saved, source))
        val aligned = alignLegacyFinitePlaylist(saved, source, setOf(1))!!
        assertSame(manual, aligned.entries[aligned.savedIndices[1]].metadata)
        assertEquals("manual:1", aligned.entries[aligned.savedIndices[1]].key)
        assertEquals(source.map { it.key }, aligned.entries.filterNot { it.key.startsWith("manual:") }.map { it.key })
    }

    @Test fun restoredShuffleHistoryAndManualIndicesRemainBeforeFreshPagePermutation() {
        val previous = listOf(3, 0, 2, 1, 4)
        val extended = appendFiniteShuffleOrder(previous, 5, 50)
        assertEquals(previous, extended.take(5))
        assertEquals((5 until 50).toSet(), extended.drop(5).toSet())
        assertEquals(50, extended.size)
        assertEquals(50, extended.toSet().size)
        assertEquals(previous, appendFiniteShuffleOrder(previous, 5, 5))
    }

    @Test fun failedLegacyRehydrationLeavesSavedTimelineAndPositionUnknownRatherThanExhausted() = runTest {
        val savedItems = finiteSongs().take(45).map { it.toFixtureMetadata() }
        val record = PersistQueue("Legacy", savedItems, 43, 87_321L, QueueType.YOUTUBE,
            QueueData.YouTubeData(playlistId = "fixture"))
        val queue = record.toContinuationQueue() as YouTubePlaylistQueue
        val before = queue.snapshot
        val source = FiniteFixtureSource()
        source.next = { throw IllegalStateException("synthetic failure") }
        try { queue.proposeInitial(source); fail("Expected failed rehydration") }
        catch (_: IllegalStateException) { }
        assertSame(before, queue.snapshot)
        assertEquals(FinitePlaylistPhase.UNKNOWN, queue.snapshot.phase)
        assertSame(savedItems, queue.legacyItems)
        assertEquals(87_321L, record.position)
        assertEquals(43, record.mediaItemIndex)
        assertFalse(queue.confirmedExhausted)
    }

    private suspend fun completeEntries(): List<FinitePlaylistEntry> {
        val queue = finiteQueue()
        val source = FiniteFixtureSource()
        queue.accept(queue.proposeInitial(source))
        while (queue.hasNextPage()) queue.accept(queue.proposeNext(source))
        return queue.snapshot.entries
    }
}

internal fun roundTripFinite(queue: PersistQueue): PersistQueue {
    val bytes = ByteArrayOutputStream().use { buffer ->
        ObjectOutputStream(buffer).use { it.writeObject(queue) }
        buffer.toByteArray()
    }
    return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as PersistQueue }
}
