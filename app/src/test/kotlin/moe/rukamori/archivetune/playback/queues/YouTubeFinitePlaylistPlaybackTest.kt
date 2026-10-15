/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import kotlinx.coroutines.test.runTest
import androidx.media3.common.Player
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.*
import org.junit.Test

class YouTubeFinitePlaylistPlaybackTest {
    @Test fun ordinaryPlaylistEntrypointsShareFiniteFactoryWhileRadioAndMixStaySeparate() {
        val endpoint = WatchEndpoint(playlistId = "VLfixture", videoId = "v44", index = 43)
        for (preload in listOf(null, finiteSongs()[43].toMediaMetadata())) {
            val queue = YouTubeQueue.playlist(endpoint, preload) as YouTubePlaylistQueue
            assertEquals("fixture", queue.playlistId)
            assertFalse(queue.confirmedExhausted)
            assertFalse(queue.nextPageRepeatsCurrentItem)
        }
        val fromStart = YouTubeQueue.playlist(endpoint, startFromBeginning = true) as YouTubePlaylistQueue
        assertNull(fromStart.endpoint.videoId)
        assertNull(fromStart.endpoint.index)
        assertTrue(YouTubeQueue.playlist(WatchEndpoint(playlistId = "RDfixture")) is YouTubeQueue)
        assertTrue(YouTubeQueue.playlist(WatchEndpoint(playlistId = "VLRDfixture")) is YouTubeQueue)
        assertTrue(YouTubeQueue.radio(finiteSongs().first().toMediaMetadata()).followAutomixPreview)
        assertTrue((YouTubeQueue.playlist(endpoint, shuffle = true) as YouTubePlaylistQueue).shuffleAfterCompletion)
    }

    @Test fun threePagesRetain135OrderedOccurrencesAndRequireExactlyTwoContinuationFetches() = runTest {
        val source = FiniteFixtureSource()
        val queue = finiteQueue()
        assertEquals(FinitePlaylistPhase.INITIALIZING, queue.snapshot.phase)
        queue.accept(queue.proposeInitial(source))
        assertEquals(45, queue.snapshot.entries.size)
        assertTrue(queue.hasNextPage())
        assertFalse(queue.confirmedExhausted)
        queue.accept(queue.proposeNext(source))
        assertEquals(90, queue.snapshot.entries.size)
        assertFalse(queue.confirmedExhausted)
        queue.accept(queue.proposeNext(source))
        assertEquals(finiteSongs().map { it.setVideoId }, queue.snapshot.entries.map { it.metadata.setVideoId })
        assertEquals(listOf("initial:fixture", "next:p2:fixture", "next:p3:fixture"), source.calls)
        assertTrue(queue.confirmedExhausted)
        assertFalse(queue.hasNextPage())
        assertEquals(3, queue.snapshot.entries.count { it.metadata.id == "duplicate" })
        assertEquals(135, queue.snapshot.entries.map { it.key }.toSet().size)
    }

    @Test fun selected44And45And46RetainPrefixAndSameCurrentOccurrenceWithoutReinsertion() = runTest {
        for (oneBased in listOf(44, 45, 46)) {
            val songs = finiteSongs()
            val selected = songs[oneBased - 1]
            val queue = YouTubePlaylistQueue(selected.endpoint!!, selected.toMediaMetadata())
            val source = FiniteFixtureSource()
            val proposal = queue.proposeInitial(source)
            assertEquals(oneBased - 1, proposal.selectedIndex)
            assertEquals(if (oneBased == 46) 2 else 1, proposal.requestsUsed)
            val current = proposal.entries[proposal.selectedIndex]
            val timeline = mutableListOf(current)
            val inserted = mutableListOf<FinitePlaylistEntry>()
            integrateFinitePreload(proposal.entries, current.key, { it.key },
                insertPrefix = { inserted.addAll(it); timeline.addAll(0, it) },
                appendSuffix = { inserted.addAll(it); timeline.addAll(it) })
            queue.accept(proposal)
            assertSame(current, timeline[oneBased - 1])
            assertEquals(proposal.entries, timeline)
            assertFalse(inserted.any { it.key == current.key })
            assertEquals(oneBased - 1, timeline.takeWhile { it !== current }.size)
            assertFalse(queue.confirmedExhausted)
            assertEquals(1, timeline.count { it.key == current.key })
        }
    }

    @Test fun onlyProvenSetVideoOverlapIsRemovedAndAnonymousRepeatedVideosRemainOccurrences() = runTest {
        val songs = finiteSongs()
        val source = FiniteFixtureSource()
        source.next = { token ->
            when (token) {
                "p2" -> FinitePlaylistChunk(listOf(songs[43]) + songs.subList(45, 90), "p3")
                else -> FinitePlaylistChunk(songs.subList(90, 135), null)
            }
        }
        val complete = FinitePlaylistLoader.complete("fixture", source)
        assertEquals(135, complete.songs.size)
        assertEquals(3, complete.songs.count { it.id == "duplicate" })
        val overlapQueue = finiteQueue()
        overlapQueue.accept(overlapQueue.proposeInitial(source))
        overlapQueue.accept(overlapQueue.proposeNext(source))
        overlapQueue.accept(overlapQueue.proposeNext(source))
        assertEquals(135, overlapQueue.snapshot.entries.size)
        assertEquals(3, overlapQueue.snapshot.entries.count { it.metadata.id == "duplicate" })
        val anonymous = listOf(songs[1], songs[43]).map { it.copy(setVideoId = null, endpoint = null) }
        source.first = finitePage(anonymous, null)
        val queue = finiteQueue()
        val proposal = queue.proposeInitial(source)
        assertEquals(2, proposal.entries.size)
        assertEquals(2, proposal.entries.map { it.key }.toSet().size)
    }

    @Test fun originalSourceLocatorSurvivesMissingRowsAndNeverUsesFilteredDisplayIndex() = runTest {
        val selected = finiteSongs()[45].copy(setVideoId = null)
        val songs = listOf(finiteSongs()[0], selected)
        val source = FiniteFixtureSource(finitePage(songs, null))
        val queue = YouTubePlaylistQueue(selected.endpoint!!.copy(playlistSetVideoId = null), selected.toMediaMetadata())
        val proposal = queue.proposeInitial(source)
        assertEquals(1, proposal.selectedIndex)
        assertEquals(45, proposal.entries[1].originalIndex)
        assertEquals(2, proposal.entries.size)
        val foreign = selected.copy(endpoint = selected.endpoint!!.copy(playlistId = "other"))
        val duplicate = foreign.copy(title = "another occurrence")
        source.first = finitePage(listOf(foreign, duplicate), null)
        expectIncomplete { queue.proposeInitial(source) }
    }

    @Test fun ambiguousAnonymousSelectionFailsButUniqueCompletedVideoCanBeReconciled() = runTest {
        val duplicate = finiteSongs()[1].copy(setVideoId = null, endpoint = null)
        val source = FiniteFixtureSource(finitePage(listOf(duplicate, duplicate), null))
        val queue = YouTubePlaylistQueue(WatchEndpoint(playlistId = "fixture", videoId = duplicate.id), duplicate.toMediaMetadata())
        expectIncomplete { queue.proposeInitial(source) }
        assertEquals(FinitePlaylistPhase.INITIALIZING, queue.snapshot.phase)
        source.first = finitePage(listOf(duplicate), null)
        assertEquals(0, queue.proposeInitial(source).selectedIndex)
    }

    @Test fun advancingEmptyPageDoesNotExhaustAndLaterDataIsFetched() = runTest {
        val source = FiniteFixtureSource(finitePage(emptyList(), "p2"))
        source.next = { token -> if (token == "p2") FinitePlaylistChunk(emptyList(), "p3")
            else FinitePlaylistChunk(listOf(finiteSongs().last()), null) }
        val queue = finiteQueue()
        queue.accept(queue.proposeInitial(source))
        assertTrue(queue.hasNextPage())
        queue.accept(queue.proposeNext(source))
        assertTrue(queue.hasNextPage())
        queue.accept(queue.proposeNext(source))
        assertEquals(listOf("set135"), queue.snapshot.entries.map { it.metadata.setVideoId })
        assertTrue(queue.confirmedExhausted)
        assertEquals(3, source.calls.size)
    }

    @Test fun finitePagingIsIndependentOfAutoloadAndRepeatButRadioRequiresAcceptedExhaustion() = runTest {
        for (autoload in listOf(false, true)) {
            for (repeatMode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
                val source = FiniteFixtureSource()
                val queue = finiteQueue()
                queue.accept(queue.proposeInitial(source))
                assertFalse(finiteRecommendationsAllowed(queue.snapshot.phase, autoload, repeatMode))
                queue.accept(queue.proposeNext(source))
                assertFalse(finiteRecommendationsAllowed(queue.snapshot.phase, autoload, repeatMode))
                queue.accept(queue.proposeNext(source))
                assertEquals(autoload && repeatMode == Player.REPEAT_MODE_OFF,
                    finiteRecommendationsAllowed(queue.snapshot.phase, autoload, repeatMode))
                assertEquals(135, queue.snapshot.entries.size)
            }
        }
        for (phase in listOf(FinitePlaylistPhase.UNKNOWN, FinitePlaylistPhase.INITIALIZING, FinitePlaylistPhase.FAILED)) {
            assertFalse(finiteRecommendationsAllowed(phase, true, Player.REPEAT_MODE_OFF))
        }
    }

    @Test fun playbackFiltersKeepSourceLocatorAndAdvancingFilteredEmptyPageCanReachLaterData() = runTest {
        val source = FiniteFixtureSource()
        val queue = finiteQueue()
        val initial = queue.proposeInitial(source)
        val entries = initial.entries.mapIndexed { index, entry ->
            entry.copy(metadata = when (index) {
                0 -> entry.metadata.copy(explicit = true)
                1 -> entry.metadata.copy(isMusicVideo = true)
                2 -> entry.metadata.copy(artists = listOf(MediaMetadata.Artist("blocked", "Blocked")))
                else -> entry.metadata
            })
        }
        val (eligible, selected) = filterQueueItems(entries, 43) {
            keepPlaybackExplicit(it.metadata, true) && keepPlaybackVideo(it.metadata.isMusicVideo, true) &&
                keepPlaybackArtists(it.metadata, setOf("blocked"))
        }
        assertEquals(42, eligible.size)
        assertEquals(40, selected)
        assertEquals(43, eligible[selected].originalIndex)
        assertEquals("set44", eligible[selected].metadata.setVideoId)
        assertEquals(45, filterQueueItems(entries, 43) {
            keepPlaybackExplicit(it.metadata, false) && keepPlaybackVideo(it.metadata.isMusicVideo, false) &&
                keepPlaybackArtists(it.metadata, emptySet())
        }.first.size)
        queue.accept(initial)
        val second = queue.proposeNext(source)
        val allExplicit = second.entries.map { it.copy(metadata = it.metadata.copy(explicit = true)) }
        assertTrue(filterQueueItems(allExplicit, 0) { keepPlaybackExplicit(it.metadata, true) }.first.isEmpty())
        queue.accept(second)
        assertFalse(queue.confirmedExhausted)
        val third = queue.proposeNext(source)
        assertEquals(45, filterQueueItems(third.entries, 0) { keepPlaybackExplicit(it.metadata, true) }.first.size)
        queue.accept(third)
        assertTrue(queue.confirmedExhausted)
        assertEquals(3, source.calls.size)
    }
}

internal fun finiteSongs(): List<SongItem> = (1..135).map { number ->
    SongItem(
        id = if (number in listOf(2, 44, 91)) "duplicate" else "v$number",
        title = "Track $number", artists = emptyList(), thumbnail = "https://fixture.invalid/$number.jpg",
        setVideoId = "set$number",
        endpoint = WatchEndpoint(videoId = if (number in listOf(2, 44, 91)) "duplicate" else "v$number",
            playlistId = "VLfixture", index = number - 1, playlistSetVideoId = "set$number"),
    )
}

internal fun finitePage(songs: List<SongItem> = finiteSongs().take(45), token: String? = "p2", count: String? = "135") =
    PlaylistPage(PlaylistItem(id = "fixture", title = "Finite fixture", author = null,
        songCountText = count, thumbnail = null, playEndpoint = null, shuffleEndpoint = null,
        radioEndpoint = null, isEditable = false), songs, token, null)

internal class FiniteFixtureSource(var first: PlaylistPage = finitePage()) : FinitePlaylistSource {
    val calls = mutableListOf<String>()
    var initial: suspend () -> PlaylistPage = { first }
    var next: suspend (String) -> FinitePlaylistChunk = { token ->
        when (token) {
            "p2" -> FinitePlaylistChunk(finiteSongs().subList(45, 90), "p3")
            "p3" -> FinitePlaylistChunk(finiteSongs().subList(90, 135), null)
            else -> error("Unexpected fixture token: $token")
        }
    }
    override suspend fun initial(playlistId: String): PlaylistPage {
        calls += "initial:$playlistId"
        return initial()
    }
    override suspend fun continuation(token: String, playlistId: String): FinitePlaylistChunk {
        calls += "next:$token:$playlistId"
        return next(token)
    }
}

internal fun finiteQueue() = YouTubePlaylistQueue(WatchEndpoint(playlistId = "VLfixture"))

internal suspend fun expectIncomplete(block: suspend () -> Unit) {
    try { block(); fail("Incomplete source must not be accepted") }
    catch (_: IncompleteYouTubePlaylistException) { }
}
