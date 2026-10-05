/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.constants.AudioSourceType

internal enum class AudioAuthenticityProbeAvailability {
    DISABLED,
    AVAILABLE,
    UNKNOWN,
}

internal fun resolveAudioAuthenticityProbeAvailability(
    rejectionEnabled: Boolean,
    pcmTapAvailable: Boolean,
): AudioAuthenticityProbeAvailability =
    when {
        !rejectionEnabled -> AudioAuthenticityProbeAvailability.DISABLED
        !pcmTapAvailable -> AudioAuthenticityProbeAvailability.UNKNOWN
        else -> AudioAuthenticityProbeAvailability.AVAILABLE
    }

internal fun isClaimedLosslessFormat(
    mimeType: String,
    codecs: String,
    label: String,
): Boolean {
    val encodedFormat = "$mimeType $codecs".lowercase()
    val normalizedLabel = label.lowercase().replace('-', '_').replace(' ', '_')
    return listOf("flac", "alac", "wavpack", "pcm", "audio/wav", "audio/wave", "audio/aiff")
        .any { marker -> encodedFormat.contains(marker) } ||
        listOf("lossless", "hi_res", "hires", "high_res", "master", "mqa").any { marker ->
            normalizedLabel.contains(marker)
        } || normalizedLabel.endsWith("_max") || normalizedLabel == "max"
}

internal fun resolveAudioAuthenticitySampleRateHz(
    sampleRateHz: Int?,
): Int? = sampleRateHz?.takeIf { it > 0 }

internal fun resolveAudioOffloadEnabled(
    requested: Boolean,
    crossfadeEnabled: Boolean,
    automixEnabled: Boolean,
    authenticityRejectionEnabled: Boolean,
): Boolean =
    requested && !crossfadeEnabled && !automixEnabled && !authenticityRejectionEnabled

internal fun resolveAudioSourceAttemptTimeoutMs(source: AudioSourceType): Long =
    if (source == AudioSourceType.AMAZON) {
        AudioSourceAttemptTimeouts.AMAZON_STREAM_TRANSFER_MS
    } else {
        AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS
    }

internal fun resolveAudioAuthenticityRetryPositionMs(currentPositionMs: Long): Long =
    currentPositionMs.coerceAtLeast(0L)
