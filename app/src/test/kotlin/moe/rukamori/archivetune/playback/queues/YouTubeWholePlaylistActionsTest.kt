/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.UserQueuedKind
import moe.rukamori.archivetune.playback.userQueueSkipCount
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeWholePlaylistActionsTest {
    @Test fun wholeAddAndPlayNextCommitAll135EvenWhenFirstPageIsAlreadyVisible() = runTest {
        for (kind in listOf(UserQueuedKind.ADD_TO_QUEUE, UserQueuedKind.PLAY_NEXT)) {
            val visible = finitePage().songs
            assertEquals(45, visible.size)
            val timeline = mutableListOf("playing", "prior-next", "manual-add", "old-tail")
            val upcoming = listOf(UserQueuedKind.PLAY_NEXT, UserQueuedKind.ADD_TO_QUEUE)
            val source = FiniteFixtureSource()
            var commits = 0
            withCompleteYouTubePlaylist("fixture", source) { page ->
                val insertion = 1 + userQueueSkipCount(upcoming, kind)
                timeline.addAll(insertion, page.songs.map { it.setVideoId!! })
                commits++
            }
            val at = if (kind == UserQueuedKind.PLAY_NEXT) 2 else 3
            assertEquals(finiteSongs().map { it.setVideoId }, timeline.subList(at, at + 135))
            assertEquals(listOf("playing", "prior-next", "manual-add", "old-tail"), timeline.filterNot { it.startsWith("set") })
            assertEquals(1, commits)
            assertEquals(3, source.calls.size)
        }
    }

    @Test fun wholeActionsFailureAndStallLeaveOldPlaybackUntouched() = runTest {
        for (failure in listOf("transport", "stall", "malformed")) {
            val timeline = mutableListOf("old playing", "old tail")
            val source = FiniteFixtureSource()
            source.next = { when (failure) {
                "transport" -> throw IllegalStateException("synthetic failure")
                "stall" -> FinitePlaylistChunk(emptyList(), "p2")
                else -> FinitePlaylistChunk(emptyList(), null)
            } }
            try { withCompleteYouTubePlaylist("fixture", source) { page -> timeline.addAll(page.songs.map { it.id }) }; fail("Expected incomplete source") }
            catch (_: IncompleteYouTubePlaylistException) { }
            catch (_: IllegalStateException) { assertEquals("transport", failure) }
            assertEquals(listOf("old playing", "old tail"), timeline)
        }
    }

    @Test fun completeShuffleIsPermutationOfEveryOccurrenceNotFirstPageOrRadio() = runTest {
        val source = FiniteFixtureSource()
        withCompleteYouTubePlaylist("fixture", source) { page ->
            val entries = page.songs.mapIndexed { index, song ->
                FinitePlaylistEntry(song.toFixtureMetadata(), "fixture:set:${song.setVideoId}", index)
            }
            val queue = YouTubePlaylistQueue(WatchEndpoint(playlistId = "fixture"),
                snapshot = FinitePlaylistSnapshot(entries = entries, phase = FinitePlaylistPhase.EXHAUSTED), shuffled = true)
            val proposal = queue.proposeInitial(source)
            val permutation = queue.orderedEntries(proposal)
            assertEquals(135, permutation.size)
            assertEquals(entries.map { it.key }.toSet(), permutation.map { it.key }.toSet())
            assertEquals(3, permutation.count { it.metadata.id == "duplicate" })
            assertTrue(queue.confirmedExhausted)
            assertEquals(0, proposal.requestsUsed)
        }
        assertEquals(3, source.calls.size)
    }

    @Test fun cancellationDuringCompleteShuffleNeverInvokesPlayerCommit() = runTest {
        val source = FiniteFixtureSource()
        val gate = CompletableDeferred<Unit>()
        val healthy = source.next
        source.next = { token -> gate.await(); healthy(token) }
        var playback = "old playback"
        val job = launch { withCompleteYouTubePlaylist("fixture", source) { playback = "replacement" } }
        runCurrent()
        assertEquals("old playback", playback)
        job.cancelAndJoin()
        gate.complete(Unit)
        assertEquals("old playback", playback)
    }

    @Test fun manualInsertionPolicyKeepsPlayNextAheadOfAddedQueueBlock() {
        assertEquals(1, userQueueSkipCount(listOf(UserQueuedKind.PLAY_NEXT, UserQueuedKind.ADD_TO_QUEUE), UserQueuedKind.PLAY_NEXT))
        assertEquals(2, userQueueSkipCount(listOf(UserQueuedKind.PLAY_NEXT, UserQueuedKind.ADD_TO_QUEUE), UserQueuedKind.ADD_TO_QUEUE))
    }

    @Test fun delayedWholeShuffleCannotReplaceNewerPlaybackOrCommitBeforeCompletion() = runTest {
        for (invalidateOperation in listOf(false, true)) {
            val source = FiniteFixtureSource()
            val release = CompletableDeferred<Unit>()
            val healthy = source.next
            source.next = { token -> release.await(); healthy(token) }
            var operation = 3L
            var queueGeneration = 5L
            val ownership = PlaylistActionTicket(operation, queueGeneration)
            var playback = "old playback"
            val job = launch {
                withCompleteYouTubePlaylist("fixture", source) {
                    if (ownership.isCurrent(operation, queueGeneration)) playback = "stale replacement"
                }
            }
            runCurrent()
            assertEquals("old playback", playback)
            if (invalidateOperation) operation++ else queueGeneration++
            playback = "new playback"
            release.complete(Unit)
            job.join()
            assertEquals("new playback", playback)
        }
    }
}
