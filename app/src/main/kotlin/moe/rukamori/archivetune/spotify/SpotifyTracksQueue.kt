/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * YumaPlayer (2026) | Modified work by MuwMx
 */


package moe.rukamori.archivetune.spotify

import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

class SpotifyTracksQueue(
    private val title: String? = null,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    override val nextPageRepeatsCurrentItem = false
    private val allTracks = initialTracks.toList()

    override suspend fun getInitialStatus(): Queue.Status =
        withContext(Dispatchers.IO) {
            if (allTracks.isEmpty()) {
                return@withContext Queue.Status(title = title, items = emptyList(), mediaItemIndex = 0)
            }

            val targetIndex = startIndex.coerceIn(allTracks.indices)
            val resolvedEntries = resolveTrackEntries(allTracks)
            val resolvedItems = resolvedEntries.map { it.second }
            Queue.Status(
                title = title,
                items = resolvedItems,
                mediaItemIndex = resolvedEntries.indexOfFirst { it.first >= targetIndex }
                    .takeIf { it >= 0 } ?: resolvedItems.lastIndex.coerceAtLeast(0),
            )
        }

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage(): List<MediaItem> = emptyList()

    private suspend fun resolveTrackEntries(tracks: List<SpotifyTrack>): List<Pair<Int, MediaItem>> =
        resolveSpotifyQueueEntries(
            tracks = tracks,
            preloadTrackId = preloadItem?.spotifyTrackId,
            preloadItem = preloadItem?.toMediaItem(),
            resolveTrack = SpotifyPlaybackResolver::resolveToMediaItem,
        )
}
