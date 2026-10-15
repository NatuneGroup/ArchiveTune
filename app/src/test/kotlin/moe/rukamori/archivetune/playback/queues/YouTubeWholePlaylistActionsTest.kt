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
                insertUserQueuedItems(
                    page.songs.map { it.setVideoId!! }, kind, true, 0, timeline.size, false,
                    upcomingKinds = { upcoming }, markQueued = { it },
                    append = { fail("Manual ordering must use indexed insertion") },
                    insert = { index, items, _, _ -> timeline.addAll(index, items) },
                    prepare = {},
                )
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

    @Test fun intentionalSelectedSubsetInsertsOnlySelectedOccurrencesThroughDirectQueueBoundary() {
        val selection = listOf(finiteSongs()[1], finiteSongs()[43], finiteSongs()[90])
        for (kind in UserQueuedKind.entries) {
            val timeline = mutableListOf("playing", "prior-next", "manual-add", "finite-tail")
            val marked = mutableListOf<String>()
            var prepares = 0
            insertUserQueuedItems(
                selection, kind, true, 0, timeline.size, false,
                upcomingKinds = { listOf(UserQueuedKind.PLAY_NEXT, UserQueuedKind.ADD_TO_QUEUE) },
                markQueued = { marked += it.setVideoId!!; it },
                append = { fail("Manual ordering must use indexed insertion") },
                insert = { index, items, skip, shuffled ->
                    assertFalse(shuffled)
                    assertEquals(if (kind == UserQueuedKind.PLAY_NEXT) 1 else 2, skip)
                    assertSame(selection[0], items[0])
                    assertEquals(3, items.count { it.id == "duplicate" })
                    timeline.addAll(index, items.map { it.setVideoId!! })
                },
                prepare = { prepares++ },
            )
            val index = if (kind == UserQueuedKind.PLAY_NEXT) 2 else 3
            assertEquals(listOf("set2", "set44", "set91"), timeline.subList(index, index + 3))
            assertEquals(listOf("set2", "set44", "set91"), marked)
            assertEquals(7, timeline.size)
            assertEquals(1, prepares)
        }
    }

    @Test fun manualSettingOffAddAppendsUnmarkedSubsetWithoutConsultingManualOrShuffleBlock() {
        val selection = listOf("set44", "set91")
        for (shuffle in listOf(false, true)) {
            val timeline = mutableListOf("playing", "prior-next", "manual-add", "finite-tail")
            var prepares = 0
            insertUserQueuedItems(
                selection, UserQueuedKind.ADD_TO_QUEUE, false, 0, timeline.size, shuffle,
                upcomingKinds = { error("Setting off must not inspect the manual block") },
                markQueued = { error("Setting off must not mark new items as manual") },
                append = { assertSame(selection, it); timeline.addAll(it) },
                insert = { _, _, _, _ -> fail("Setting-off Add must append, including when shuffled") },
                prepare = { prepares++ },
            )
            assertEquals(listOf("playing", "prior-next", "manual-add", "finite-tail", "set44", "set91"), timeline)
            assertEquals(1, prepares)
        }
    }

    @Test fun manualSettingOffPlayNextInsertsUnmarkedSubsetImmediatelyAfterCurrent() {
        val selection = listOf("set44", "set91")
        for (shuffle in listOf(false, true)) {
            val timeline = mutableListOf("earlier", "playing", "prior-next", "manual-add", "finite-tail")
            var prepares = 0
            insertUserQueuedItems(
                selection, UserQueuedKind.PLAY_NEXT, false, 1, timeline.size, shuffle,
                upcomingKinds = { error("Setting off must not inspect the manual block") },
                markQueued = { error("Setting off must not mark new items as manual") },
                append = { fail("Play next must use indexed insertion") },
                insert = { index, items, skip, shuffled ->
                    assertSame(selection, items)
                    assertEquals(2, index)
                    assertEquals(0, skip)
                    assertEquals(shuffle, shuffled)
                    timeline.addAll(index, items)
                },
                prepare = { prepares++ },
            )
            assertEquals(listOf("earlier", "playing", "set44", "set91", "prior-next", "manual-add", "finite-tail"), timeline)
            assertEquals(1, prepares)
        }
    }

    @Test fun emptySelectedSubsetDoesNotMutateOrPreparePlayback() {
        insertUserQueuedItems<String>(
            emptyList(), UserQueuedKind.ADD_TO_QUEUE, false, 0, 4, true,
            upcomingKinds = { error("Empty selection must not inspect the queue") },
            markQueued = { error("Empty selection must not mark items") },
            append = { fail("Empty selection must not append") },
            insert = { _, _, _, _ -> fail("Empty selection must not insert") },
            prepare = { fail("Empty selection must not prepare") },
        )
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
