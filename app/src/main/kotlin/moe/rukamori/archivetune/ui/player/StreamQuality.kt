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
import moe.rukamori.archivetune.playback.isClaimedLosslessFormat

enum class StreamQualityTier {
    HI_RES_LOSSLESS,
    LOSSLESS,
    LOSSY,

    /** Declared lossless with no observed evidence yet; shown as declared, never as Lossless. */
    UNVERIFIED,
}

/**
 * The one classification every quality badge draws from, so the lyrics page and the player page
 * behind it can never disagree about the stream they are both describing.
 */
data class StreamQuality(
    val tier: StreamQualityTier,
    /** The codec under its display name; what a lossy or unverified stream is labelled with. */
    val codec: String,
) {
    val isLossless: Boolean get() = tier == StreamQualityTier.LOSSLESS || tier == StreamQualityTier.HI_RES_LOSSLESS
}

/**
 * Lossless and Hi-Res need observed evidence: the decoder's input format during playback, stored in the
 * evidence columns. A declared codec, MIME, bitrate, sample rate or tier name never earns either label.
 * Hi-Res is lossless with an observed bit depth above 16 or an observed sample rate above 48 kHz.
 */
fun FormatEntity.streamQuality(): StreamQuality {
    val observedMime = evidenceMimeType.orEmpty()
    val observedCodecs = evidenceCodecs.orEmpty()
    if (observedMime.isBlank() && observedCodecs.isBlank()) {
        return StreamQuality(
            tier = if (isLossless()) StreamQualityTier.UNVERIFIED else StreamQualityTier.LOSSY,
            codec = codecLabel(),
        )
    }
    val tier =
        when {
            !isClaimedLosslessFormat(observedMime, observedCodecs) -> StreamQualityTier.LOSSY
            (evidenceBitDepth ?: 0) > 16 || (evidenceSampleRate ?: 0) > 48_000 -> StreamQualityTier.HI_RES_LOSSLESS
            else -> StreamQualityTier.LOSSLESS
        }
    return StreamQuality(
        tier = tier,
        codec = copy(mimeType = observedMime, codecs = observedCodecs).codecLabel(),
    )
}

@Composable
fun StreamQuality.label(): String =
    when (tier) {
        StreamQualityTier.HI_RES_LOSSLESS -> stringResource(R.string.flamingo_quality_hires_lossless)
        StreamQualityTier.LOSSLESS -> stringResource(R.string.flamingo_quality_lossless)
        StreamQualityTier.LOSSY -> codec
        StreamQualityTier.UNVERIFIED -> stringResource(R.string.flamingo_quality_unverified, codec)
    }
