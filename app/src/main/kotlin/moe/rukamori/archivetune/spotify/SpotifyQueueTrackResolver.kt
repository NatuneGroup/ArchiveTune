/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

internal suspend fun <T : Any> resolveSpotifyQueueEntries(
    tracks: List<SpotifyTrack>,
    preloadTrackId: String? = null,
    preloadItem: T? = null,
    resolveTrack: suspend (SpotifyTrack) -> T?,
): List<Pair<Int, T>> =
    buildList {
        tracks.chunked(4).forEachIndexed { chunkIndex, chunk ->
            val offset = chunkIndex * 4
            val entries =
                coroutineScope {
                    chunk.mapIndexed { index, track ->
                        async {
                            val item =
                                if (preloadItem != null && track.id.isNotBlank() && track.id == preloadTrackId) {
                                    preloadItem
                                } else {
                                    try {
                                        withTimeoutOrNull(15_000L) { resolveTrack(track) }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            item?.let { offset + index to it }
                        }
                    }.awaitAll().filterNotNull()
                }
            addAll(entries)
        }
    }
