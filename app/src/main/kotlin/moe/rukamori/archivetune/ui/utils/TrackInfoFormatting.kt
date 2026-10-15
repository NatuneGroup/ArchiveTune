/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import java.util.Locale
import moe.rukamori.archivetune.playback.AndroidAudioOutputFormat
import moe.rukamori.archivetune.playback.DecodedPcmFormat
import moe.rukamori.archivetune.playback.ReportedAudioFormat

internal fun formatTrackDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

internal fun formatReportedBitrate(bitrateBitsPerSecond: Int?): String? {
    if (bitrateBitsPerSecond == null || bitrateBitsPerSecond <= 0) return null
    if (bitrateBitsPerSecond < 1_000) return "$bitrateBitsPerSecond b/s"
    val tenths = (bitrateBitsPerSecond.toLong() + 50L) / 100L
    return "${formatTenths(tenths)} kb/s"
}

internal fun formatReportedSampleRate(sampleRateHz: Int?): String? {
    if (sampleRateHz == null || sampleRateHz <= 0) return null
    val tenths = (sampleRateHz.toLong() + 50L) / 100L
    return "${formatTenths(tenths)} kHz"
}

internal fun formatReportedByteCount(byteCount: Long?): String? {
    if (byteCount == null || byteCount <= 0L) return null
    val units = listOf("B", "KiB", "MiB", "GiB")
    var unitIndex = 0
    var scaled = byteCount.toDouble()
    while (scaled >= 1_024.0 && unitIndex < units.lastIndex) {
        scaled /= 1_024.0
        unitIndex += 1
    }
    val amount = if (unitIndex == 0) {
        String.format(Locale.ROOT, "%.0f", scaled)
    } else {
        String.format(Locale.ROOT, "%.1f", scaled)
    }
    return "$amount ${units[unitIndex]}"
}

internal fun formatReportedLoudness(loudnessDb: Double?): String? {
    if (loudnessDb == null || !loudnessDb.isFinite()) return null
    return String.format(Locale.ROOT, "%.1f dB", loudnessDb)
}

internal fun isYouTubeVideoId(value: String): Boolean =
    value.matches(YOUTUBE_VIDEO_ID)

internal fun decodedPcmForTrack(trackId: String, format: DecodedPcmFormat?): DecodedPcmFormat? =
    format?.takeIf { it.mediaId == trackId }

internal fun reportedAudioFormatForTrack(
    trackId: String,
    format: ReportedAudioFormat?,
): ReportedAudioFormat? = format?.takeIf { it.mediaId == trackId }

internal fun reportedAudioSampleRateHz(format: ReportedAudioFormat?, storedSampleRateHz: Int?): Int? =
    if (format != null) format.sampleRateHz else storedSampleRateHz

internal fun androidAudioOutputFormatForTrack(
    trackId: String,
    format: AndroidAudioOutputFormat?,
): AndroidAudioOutputFormat? = format?.takeIf { it.mediaId == trackId }

private fun formatTenths(tenths: Long): String =
    if (tenths % 10L == 0L) {
        (tenths / 10L).toString()
    } else {
        "${tenths / 10L}.${tenths % 10L}"
    }

private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
