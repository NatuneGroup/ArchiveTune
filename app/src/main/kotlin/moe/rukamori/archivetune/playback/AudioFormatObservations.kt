/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.exoplayer.audio.AudioSink
import moe.rukamori.archivetune.constants.AudioSourceType

internal fun localAudioObservationMediaId(
    sessionMediaId: String?,
    localMediaId: String?,
    playbackType: Int,
): String? = sessionMediaId?.takeIf { playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL && it == localMediaId }

data class ReportedAudioFormat(
    val mediaId: String,
    val source: AudioSourceType?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channelCount: Int?,
)

internal fun reportedAudioFormatForPlayback(
    mediaId: String?,
    source: AudioSourceType?,
    format: ReportedAudioFormat?,
): ReportedAudioFormat? {
    if (mediaId == null) return null
    return format?.takeIf { it.mediaId == mediaId && it.source == source }
        ?: ReportedAudioFormat(mediaId, source, null, null, null)
}

data class AndroidAudioOutputFormat(
    val mediaId: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val offload: Boolean,
) {
    val pcmFormat: DecodedPcmFormat
        get() = DecodedPcmFormat(mediaId, sampleRateHz, channelCount, encoding)
}

internal fun androidAudioOutputFormatForPlayback(
    mediaId: String?,
    configuration: AudioSink.AudioTrackConfig?,
    bitPerfectOutputActive: Boolean,
): AndroidAudioOutputFormat? {
    if (bitPerfectOutputActive || mediaId == null || configuration == null) return null
    return AndroidAudioOutputFormat(
        mediaId,
        configuration.sampleRate,
        Integer.bitCount(configuration.channelConfig),
        configuration.encoding,
        configuration.offload,
    )
}
