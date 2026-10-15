/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.models

import moe.rukamori.archivetune.playback.queues.FinitePlaylistSnapshot
import java.io.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** Shared with MusicService's file adapter; keep the existing Java object-stream format. */
internal object PlaybackPersistenceCodec {
    fun read(input: InputStream): Any = ObjectInputStream(input).use { it.readObject() }

    fun write(output: OutputStream, payload: Serializable) {
        ObjectOutputStream(output).use {
            it.writeObject(payload)
            it.flush()
        }
    }
}

data class PersistQueue(
    val title: String?,
    val items: List<MediaMetadata>,
    val mediaItemIndex: Int,
    val position: Long,
    val queueType: QueueType = QueueType.LIST,
    val queueData: QueueData? = null,
    val shuffleOrder: List<Int>? = null,
    val manualQueueKinds: List<Int?>? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed class QueueType : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    object LIST : QueueType() {
        private const val serialVersionUID = 1L
    }

    object YOUTUBE : QueueType() {
        private const val serialVersionUID = 1L
    }

    object YOUTUBE_PLAYLIST : QueueType() {
        private const val serialVersionUID = 1L
    }

    object YOUTUBE_ALBUM_RADIO : QueueType() {
        private const val serialVersionUID = 1L
    }

    object LOCAL_ALBUM_RADIO : QueueType() {
        private const val serialVersionUID = 1L
    }
}

sealed class QueueData : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    data class YouTubeData(
        val videoId: String? = null,
        val playlistId: String? = null,
        val endpointParams: String? = null,
        val followAutomixPreview: Boolean = false,
    ) : QueueData() {
        companion object {
            private const val serialVersionUID = 2L
        }
    }

    data class YouTubeAlbumRadioData(
        val playlistId: String,
        val albumSongCount: Int = 0,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false,
    ) : QueueData() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class YouTubePlaylistData(
        val playlistId: String,
        val videoId: String? = null,
        val endpointParams: String? = null,
        val endpointIndex: Int? = null,
        val playlistSetVideoId: String? = null,
        val snapshot: FinitePlaylistSnapshot,
        val timelineOccurrences: List<String?>,
        val needsReconciliation: Boolean = false,
    ) : QueueData() {
        companion object { private const val serialVersionUID = 1L }
    }

    data class LocalAlbumRadioData(
        val albumId: String,
        val startIndex: Int = 0,
        val playlistId: String? = null,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false,
    ) : QueueData() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
