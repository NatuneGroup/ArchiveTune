/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback

import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.constants.AudioSourceType

internal data class CachedAudioMetadata(
    val source: AudioSourceType,
    val sampleRateHz: Int?,
    val claimedLossless: Boolean,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
) {
    fun toMutations(): ContentMetadataMutations =
        ContentMetadataMutations()
            .set(VERSION_KEY, 1L)
            .set(SOURCE_KEY, source.name)
            .set(SAMPLE_RATE_KEY, sampleRateHz?.toLong() ?: -1L)
            .set(LOSSLESS_KEY, if (claimedLossless) 1L else 0L)
            .set(BIT_DEPTH_KEY, bitDepth?.toLong() ?: -1L)
            .set(CHANNEL_COUNT_KEY, channelCount?.toLong() ?: -1L)

    companion object {
        private const val VERSION_KEY = "archivetune:audio:version"
        private const val SOURCE_KEY = "archivetune:audio:source"
        private const val SAMPLE_RATE_KEY = "archivetune:audio:sample_rate"
        private const val LOSSLESS_KEY = "archivetune:audio:claimed_lossless"
        private const val BIT_DEPTH_KEY = "archivetune:audio:bit_depth"
        private const val CHANNEL_COUNT_KEY = "archivetune:audio:channel_count"

        fun from(stream: DirectStream): CachedAudioMetadata =
            CachedAudioMetadata(
                source = stream.source,
                sampleRateHz = resolveAudioAuthenticitySampleRateHz(stream.sampleRate),
                claimedLossless = isClaimedLosslessFormat(stream.mimeType, stream.codecs, stream.label),
                bitDepth = stream.bitDepth?.takeIf { it in 1..64 },
            )

        fun read(metadata: ContentMetadata, expectedSource: AudioSourceType): CachedAudioMetadata? {
            if (metadata.get(VERSION_KEY, -1L) != 1L || metadata.get(SOURCE_KEY, "") != expectedSource.name) return null
            val rate = metadata.get(SAMPLE_RATE_KEY, -1L).takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
            val depth = metadata.get(BIT_DEPTH_KEY, -1L).takeIf { it in 1L..64L }?.toInt()
            val channels = metadata.get(CHANNEL_COUNT_KEY, -1L).takeIf { it in 1L..32L }?.toInt()
            return CachedAudioMetadata(expectedSource, rate, metadata.get(LOSSLESS_KEY, 0L) == 1L, depth, channels)
        }
    }
}
