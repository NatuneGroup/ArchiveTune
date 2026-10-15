/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.C

data class DecodedPcmFormat(
    val mediaId: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
) {
    val bitsPerSample: Int?
        get() =
            when (encoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
                else -> null
            }

    val isFloatingPoint: Boolean
        get() = encoding == C.ENCODING_PCM_FLOAT
}
