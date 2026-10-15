/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.extensions.ExtraIsMusicVideo
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?
    val nextPageRepeatsCurrentItem: Boolean
        get() = true

    suspend fun getInitialStatus(): Status

    fun shouldExpandToFullQueueWhenAutoLoadMoreDisabled(): Boolean = false

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                filterItems { keepPlaybackExplicit(it.metadata, true) }
            } else {
                this
            }

        fun filterVideo(enabled: Boolean = true) =
            if (enabled) {
                filterItems { keepPlaybackVideo(it.mediaMetadata.extras?.getBoolean(ExtraIsMusicVideo, false), true) }
            } else {
                this
            }

        fun filterBlockedArtists(blockedArtistIds: Set<String>) =
            if (blockedArtistIds.isEmpty()) {
                this
            } else {
                filterItems { !it.hasBlockedArtist(blockedArtistIds) }
            }

        private inline fun filterItems(keep: (MediaItem) -> Boolean): Status {
            if (items.isEmpty()) return this
            val (filteredItems, filteredIndex) = filterQueueItems(items, mediaItemIndex, keep)
            return copy(items = filteredItems, mediaItemIndex = filteredIndex)
        }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            !keepPlaybackExplicit(it.metadata, true)
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideo(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            !keepPlaybackVideo(it.mediaMetadata.extras?.getBoolean(ExtraIsMusicVideo, false), true)
        }
    } else {
        this
    }

fun List<MediaItem>.filterBlockedArtists(blockedArtistIds: Set<String>) =
    if (blockedArtistIds.isEmpty()) {
        this
    } else {
        filterNot { it.hasBlockedArtist(blockedArtistIds) }
    }

fun MediaItem.hasBlockedArtist(blockedArtistIds: Set<String>): Boolean =
    !keepPlaybackArtists(metadata, blockedArtistIds)

internal fun keepPlaybackExplicit(metadata: MediaMetadata?, hide: Boolean): Boolean =
    !hide || metadata?.explicit != true

internal fun keepPlaybackVideo(isVideo: Boolean?, hide: Boolean): Boolean = !hide || isVideo != true

internal fun keepPlaybackArtists(metadata: MediaMetadata?, blocked: Set<String>): Boolean =
    metadata?.artists?.any { it.id != null && it.id in blocked } != true

internal inline fun <T> filterQueueItems(items: List<T>, current: Int, keep: (T) -> Boolean): Pair<List<T>, Int> {
    if (items.isEmpty()) return emptyList<T>() to 0
    val currentIndex = current.coerceIn(items.indices)
    var filteredIndex = 0
    val filtered = buildList(items.size) {
        items.forEachIndexed { index, item ->
            if (keep(item)) {
                if (index < currentIndex) filteredIndex++
                add(item)
            }
        }
    }
    return filtered to if (filtered.isEmpty()) 0 else filteredIndex.coerceIn(filtered.indices)
}
