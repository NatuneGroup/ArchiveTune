/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.player

import android.media.MediaCodecList
import timber.log.Timber
import java.util.Locale

/** How the user's video-quality choice is encoded. */
object VideoQualityPreference {
    /** Sentinel for the "Data saver" mode. Negative so it cannot collide with a real height. */
    const val DATA_SAVER = -1

    /** Sentinel for the "High quality" mode — highest the device can decode. */
    const val HIGH_QUALITY = -2

    /** Ceiling applied in Auto mode. */
    const val AUTO_HEIGHT_CEILING = 1080

    /** Ceiling applied in Data saver mode. */
    const val DATA_SAVER_HEIGHT_CEILING = 480

    /** True when [preference] is an exact height picked from the Advanced list. */
    fun isExactHeight(preference: Int?): Boolean = preference != null && preference > 0

    /**
     * The tallest resolution [preference] permits on this device.
     *
     * Every branch is clamped by [VideoDecoderCapabilities.maxSupportedHeight] so a stored
     * preference can never ask for a stream the device cannot decode — which matters for exact
     * heights especially, since a preference saved on one device can be restored from a backup on
     * a weaker one.
     */
    fun ceilingFor(preference: Int?): Int {
        val deviceMax = VideoDecoderCapabilities.maxSupportedHeight()
        val requested =
            when (preference) {
                null -> AUTO_HEIGHT_CEILING
                DATA_SAVER -> DATA_SAVER_HEIGHT_CEILING
                HIGH_QUALITY -> deviceMax
                else -> preference
            }
        return minOf(requested, deviceMax)
    }
}

/**
 * What resolution this device's video decoders can actually handle.
 *
 * Queried once from [MediaCodecList] and cached for the process. Used as the hard ceiling on every
 * quality choice so "High quality" means "the best this phone can play" rather than "the best
 * YouTube listed" — offering a 4K stream to a decoder that maxes out at 1080p produces a black
 * surface or a hard decoder error, not a graceful downgrade.
 */
object VideoDecoderCapabilities {
    /**
     * Used when the codec query fails or reports nothing usable (an OEM with a broken
     * `MediaCodecList`, for instance).
     */
    private const val FALLBACK_MAX_HEIGHT = 2160

    /**
     * Codecs YouTube serves adaptive video in. AV1 and VP9 carry the high-resolution formats;
     * AVC/HEVC are here because a device whose only 4K decoder is AVC still counts as 4K-capable.
     */
    private val VIDEO_MIME_TYPES =
        setOf(
            "video/av01",
            "video/x-vnd.on2.vp9",
            "video/hevc",
            "video/avc",
        )

    @Volatile
    private var cachedMaxHeight: Int? = null

    /** Tallest decodable frame height, e.g. 2160 on a 4K-capable device. Never returns 0. */
    fun maxSupportedHeight(): Int =
        cachedMaxHeight ?: probeMaxSupportedHeight().also { probed ->
            cachedMaxHeight = probed
            Timber.tag("VideoDecoder").d("Max decodable video height: %dp", probed)
        }

    private fun probeMaxSupportedHeight(): Int =
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .asSequence()
                        .map { type -> info to type.lowercase(Locale.US) }
                }.filter { (_, mime) -> mime in VIDEO_MIME_TYPES }
                .mapNotNull { (info, mime) ->
                    // getCapabilitiesForType throws on some OEM codec entries that advertise a
                    // type they cannot describe, so each lookup is guarded individually rather
                    // than letting one bad codec discard the whole probe.
                    runCatching {
                        info.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper
                    }.getOrNull()
                }.maxOrNull()
        }.getOrElse { error ->
            Timber.tag("VideoDecoder").w(error, "MediaCodecList probe failed")
            null
        }?.takeIf { it > 0 } ?: FALLBACK_MAX_HEIGHT
}

/**
 * Format a video height (in px) as a human-readable label.
 *
 * `4320 → "4320p (8K)"`, `2160 → "2160p (4K)"`, `1440 → "1440p (QHD)"`, `1080 → "1080p (FHD)"`,
 * `720 → "720p (HD)"`, `480 → "480p (SD)"`, anything else → bare `"<h>p"`.
 */
internal fun formatHeightLabel(height: Int): String {
    val qualityName =
        when (height) {
            4320 -> " (8K)"
            2160 -> " (4K)"
            1440 -> " (QHD)"
            1080 -> " (FHD)"
            720 -> " (HD)"
            480 -> " (SD)"
            else -> ""
        }
    return "${height}p$qualityName"
}
