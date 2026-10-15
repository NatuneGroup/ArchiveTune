/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyQueueTrackResolverTest {
    @Test
    fun selectedTrackUsesItsPreloadWithoutAnotherSearch() = runTest {
        val requests = mutableListOf<String>()
        val entries = resolveSpotifyQueueEntries(
            tracks = tracks(3),
            preloadTrackId = "1",
            preloadItem = "selected",
        ) { track ->
            requests += track.id
            track.id
        }

        assertEquals(listOf(0 to "0", 1 to "selected", 2 to "2"), entries)
        assertEquals(listOf("0", "2"), requests)
    }

    @Test
    fun failedAndUnavailableTracksDoNotDiscardOtherEntriesOrTheirIndexes() = runTest {
        val entries = resolveSpotifyQueueEntries(tracks(4)) { track ->
            when (track.id) {
                "1" -> error("Unavailable")
                "2" -> null
                else -> track.id
            }
        }

        assertEquals(listOf(0 to "0", 3 to "3"), entries)
    }

    @Test
    fun searchesAreBoundedAndResultsRetainPlaylistOrder() = runTest {
        var active = 0
        var maximumActive = 0
        val entries = resolveSpotifyQueueEntries(tracks(11)) { track ->
            active++
            maximumActive = maxOf(maximumActive, active)
            delay((12 - track.id.toInt()).toLong())
            active--
            track.id
        }

        assertEquals(4, maximumActive)
        assertEquals((0..10).map { it to it.toString() }, entries)
    }

    @Test
    fun aStalledLookupTimesOutWithoutBlockingTheRemainingQueue() = runTest {
        val entries = resolveSpotifyQueueEntries(tracks(2)) { track ->
            if (track.id == "0") delay(30_000L)
            track.id
        }

        assertEquals(listOf(1 to "1"), entries)
    }

    @Test(expected = CancellationException::class)
    fun callerCancellationIsNotConvertedToAnUnavailableTrack() = runTest {
        resolveSpotifyQueueEntries(tracks(1)) { throw CancellationException("Canceled") }
        Unit
    }

    private fun tracks(count: Int): List<SpotifyTrack> = (0 until count).map { SpotifyTrack(id = it.toString()) }
}
