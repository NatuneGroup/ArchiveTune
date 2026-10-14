/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.isLossless

enum class StreamQualityTier { HI_RES_LOSSLESS, LOSSLESS, LOSSY }

/**
 * The one classification every quality badge draws from, so the lyrics page and the player page
 * behind it can never disagree about the stream they are both describing.
 */
data class StreamQuality(
    val tier: StreamQualityTier,
    /** The codec under its display name; what a lossy stream is labelled with. */
    val codec: String,
) {
    val isLossless: Boolean get() = tier != StreamQualityTier.LOSSY
}

fun FormatEntity.streamQuality(): StreamQuality {
    val musicBitrateKbps = bitrate / 1000
    val musicSamplingRate = sampleRate ?: 0
    // Some sources report lossless audio under a container MIME type without a FLAC/ALAC codec
    // string, so a CD-or-better bitrate at a CD-or-better rate counts as lossless too.
    val isLossless = isLossless() || (musicBitrateKbps >= 700 && musicSamplingRate >= 44100)
    val isHiRes = musicBitrateKbps >= 2000 && musicSamplingRate >= 96000
    val tier =
        when {
            isHiRes -> StreamQualityTier.HI_RES_LOSSLESS
            isLossless -> StreamQualityTier.LOSSLESS
            else -> StreamQualityTier.LOSSY
        }
    return StreamQuality(tier = tier, codec = codecLabel())
}

@Composable
fun StreamQuality.label(): String =
    when (tier) {
        StreamQualityTier.HI_RES_LOSSLESS -> stringResource(R.string.flamingo_quality_hires_lossless)
        StreamQualityTier.LOSSLESS -> stringResource(R.string.flamingo_quality_lossless)
        StreamQualityTier.LOSSY -> codec
    }
