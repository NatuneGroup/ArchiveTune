/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.playback.stream

import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.innertube.PlaybackAuthState

enum class StreamPurpose {
    PLAYBACK,
    DOWNLOAD,
}

enum class StreamSource {
    YT_DLP,
    NATIVE_INNERTUBE,
    NEWPIPE,
    INNERTUBE_X,
}

data class AudioStreamRequest(
    val mediaId: String,
    val playlistId: String? = null,
    val quality: AudioQuality,
    val networkMetered: Boolean,
    val purpose: StreamPurpose,
    val authState: PlaybackAuthState,
    val pinnedFormatId: Int? = null,
)

data class ResolvedAudioStream(
    val url: String,
    val requestHeaders: Map<String, String>,
    val formatId: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long,
    val expiresAtMs: Long,
    val authFingerprint: String,
    val source: StreamSource,
    val runtimeVersion: String? = null,
    val title: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
    val loudnessDb: Double? = null,
    val perceptualLoudnessDb: Double? = null,
    val playbackTrackingUrl: String? = null,
)

/** The bare codec list of an RFC 6381 mime type such as `audio/webm; codecs="opus"`. */
internal fun String.codecsFromMimeType(): String =
    substringAfter("codecs=", "").removeSurrounding("\"").substringBefore("\"")
