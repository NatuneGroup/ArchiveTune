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
    when (source) {
        // Covers only their search: the whole-file download after a match runs on its own
        // deadline (AudioSourceAttemptScope.withinTransfer), so a slow link is not cut off.
        AudioSourceType.AMAZON, AudioSourceType.QQ -> AudioSourceAttemptTimeouts.WHOLE_FILE_SOURCE_SEARCH_MS
        // A cold resolve chains a token, device registration, file lookup and key request.
        AudioSourceType.SPOTIFY -> AudioSourceAttemptTimeouts.SPOTIFY_RESOLVE_MS
        else -> AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS
    }

internal fun resolveAudioAuthenticityRetryPositionMs(currentPositionMs: Long): Long =
    currentPositionMs.coerceAtLeast(0L)
