/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.spotify

import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

internal const val SPOTIFY_LIKED_SONGS_ID = "__spotify_liked_songs__"

/** Spotify's AI DJ, which shows up on the home feed shaped like a playlist and is not one. */
const val SPOTIFY_DJ_PLAYLIST_ID = "37i9dQZF1EYkqdzj48dyYq"

/** True for the DJ tile, whether it arrives as a bare id or a `spotify:playlist:…` uri. */
fun isSpotifyDj(idOrUri: String): Boolean = idOrUri.substringAfterLast(":") == SPOTIFY_DJ_PLAYLIST_ID

class SpotifyPlaylistQueue(
    private val playlistId: String,
    private val title: String? = null,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    override val nextPageRepeatsCurrentItem = false
    private val allTracks = mutableListOf<SpotifyTrack>()
    private var resolveOffset = 0
    private var apiFetchOffset = 0
    private var apiTotal = 0
    private var apiHasMore = true

    override suspend fun getInitialStatus(): Queue.Status =
        withContext(Dispatchers.IO) {
            if (initialTracks.isNotEmpty()) {
                allTracks += initialTracks
                apiTotal = initialTracks.size
                apiFetchOffset = apiTotal
                apiHasMore = false
            } else {
                fetchNextApiPage()
            }

            while (startIndex >= allTracks.size && apiHasMore) {
                fetchNextApiPage()
            }

            if (allTracks.isEmpty()) {
                return@withContext Queue.Status(title = title, items = emptyList(), mediaItemIndex = 0)
            }

            val targetIndex = startIndex.coerceIn(allTracks.indices)
            val resolvedEntries = resolveTrackEntries(allTracks)
            val resolvedItems = resolvedEntries.map { it.second }

            resolveOffset = allTracks.size
            if (resolvedItems.isEmpty()) {
                return@withContext Queue.Status(title = title, items = emptyList(), mediaItemIndex = 0)
            }

            Queue.Status(
                title = title,
                items = resolvedItems,
                mediaItemIndex =
                    resolvedEntries
                        .indexOfFirst { it.first >= targetIndex }
                        .takeIf { it >= 0 }
                        ?: resolvedItems.lastIndex,
            )
        }

    override fun hasNextPage(): Boolean = resolveOffset < allTracks.size || apiHasMore

    override suspend fun nextPage(): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (resolveOffset >= allTracks.size && apiHasMore) {
                fetchNextApiPage()
            }
            if (resolveOffset >= allTracks.size) return@withContext emptyList()

            val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(allTracks.size)
            val batch = allTracks.subList(resolveOffset, end)
            resolveOffset = end
            resolveTracks(batch)
        }

    private suspend fun resolveTracks(tracks: List<SpotifyTrack>): List<MediaItem> = resolveTrackEntries(tracks).map { it.second }

    private suspend fun resolveTrackEntries(tracks: List<SpotifyTrack>): List<Pair<Int, MediaItem>> =
        resolveSpotifyQueueEntries(
            tracks = tracks,
            preloadTrackId = preloadItem?.spotifyTrackId,
            preloadItem = preloadItem?.toMediaItem(),
            resolveTrack = SpotifyPlaybackResolver::resolveToMediaItem,
        )

    private suspend fun fetchNextApiPage() {
        if (!apiHasMore) return
        if (playlistId == SPOTIFY_LIKED_SONGS_ID) {
            val page = Spotify.likedSongs(limit = SPOTIFY_PAGE_SIZE, offset = apiFetchOffset).getOrThrow()
            apiTotal = page.total
            allTracks += page.items.mapNotNull { it.track.takeUnless(SpotifyTrack::isLocal) }
            apiFetchOffset += page.items.size
            apiHasMore = apiFetchOffset < apiTotal
            return
        }
        val result =
            Spotify
                .playlistTracks(
                    playlistId = playlistId,
                    limit = SPOTIFY_PAGE_SIZE,
                    offset = apiFetchOffset,
                ).getOrThrow()
        apiTotal = result.total
        val fetched = result.items.mapNotNull { it.track?.takeUnless(SpotifyTrack::isLocal) }
        allTracks += fetched
        apiFetchOffset += result.items.size
        apiHasMore = apiFetchOffset < apiTotal
    }

    companion object {
        private const val SPOTIFY_PAGE_SIZE = 50
        private const val RESOLVE_BATCH_SIZE = 4
    }
}
