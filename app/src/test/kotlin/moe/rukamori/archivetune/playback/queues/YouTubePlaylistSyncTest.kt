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
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlaylistSyncTest {
    @Test fun incompleteRemoteNeverTruncatesExistingLocalPlaylistAndRetryCommitsCompleteOrder() = runTest {
        for (failure in listOf("failure", "stall", "malformed")) {
            var local = listOf("existing1", "existing2", "existing3")
            val original = local
            val source = FiniteFixtureSource()
            val healthy = source.next
            source.next = { when (failure) {
                "failure" -> throw IllegalStateException("synthetic transport failure")
                "stall" -> FinitePlaylistChunk(emptyList(), "p2")
                else -> FinitePlaylistChunk(emptyList(), null)
            } }
            var commits = 0
            try { withCompleteYouTubePlaylist("fixture", source) { page -> local = page.songs.map { it.setVideoId!! }; commits++ }; fail("Expected failure") }
            catch (_: IncompleteYouTubePlaylistException) { }
            catch (_: IllegalStateException) { assertEquals("failure", failure) }
            assertSame(original, local)
            assertEquals(0, commits)
            source.next = healthy
            withCompleteYouTubePlaylist("fixture", source) { page -> local = page.songs.map { it.setVideoId!! }; commits++ }
            assertEquals(finiteSongs().map { it.setVideoId }, local)
            assertEquals(135, local.size)
            assertEquals(1, commits)
        }
    }

    @Test fun cancelledSyncCannotEnterStoreCommitAndFullRetryCan() = runTest {
        val source = FiniteFixtureSource()
        val gate = CompletableDeferred<Unit>()
        val healthy = source.next
        source.next = { token -> gate.await(); healthy(token) }
        var local = listOf("complete old local playlist")
        val job = launch { withCompleteYouTubePlaylist("fixture", source) { local = it.songs.map { song -> song.setVideoId!! } } }
        runCurrent()
        assertEquals(listOf("complete old local playlist"), local)
        job.cancelAndJoin()
        gate.complete(Unit)
        assertEquals(listOf("complete old local playlist"), local)
        source.next = healthy
        withCompleteYouTubePlaylist("fixture", source) { local = it.songs.map { song -> song.setVideoId!! } }
        assertEquals(finiteSongs().map { it.setVideoId }, local)
    }

    @Test fun advancingEmptyPagesMustReachConfirmedCompletionBeforeAnyStoreReplacement() = runTest {
        val source = FiniteFixtureSource()
        var commits = 0
        source.next = { token ->
            assertEquals(0, commits)
            if (token == "p2") FinitePlaylistChunk(emptyList(), "p3")
            else FinitePlaylistChunk(finiteSongs().drop(45), null)
        }
        withCompleteYouTubePlaylist("fixture", source) { page ->
            assertEquals(finiteSongs().map { it.setVideoId }, page.songs.map { it.setVideoId })
            commits++
        }
        assertEquals(1, commits)
        assertEquals(3, source.calls.size)
    }

    @Test fun requestCeilingCannotCommitAnAdvancingButIncompleteRemotePlaylist() = runTest {
        val source = FiniteFixtureSource(finitePage(token = "1"))
        source.next = { token -> FinitePlaylistChunk(emptyList(), (token.toInt() + 1).toString()) }
        var commits = 0
        expectIncomplete { withCompleteYouTubePlaylist("fixture", source) { commits++ } }
        assertEquals(0, commits)
        assertEquals(200, source.calls.size)
    }
}
