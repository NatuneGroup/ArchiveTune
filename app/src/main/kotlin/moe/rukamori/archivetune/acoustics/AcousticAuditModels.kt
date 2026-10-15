/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import moe.rukamori.archivetune.constants.AudioSourceType

data class AcousticAuditTarget(val mediaId: String, val title: String, val artists: String = "")

enum class AcousticAuditStatus {
    IDLE, RESOLVING, DECODING, ANALYZING, COMPLETE, UNAVAILABLE, FAILED, CANCELLED,
}

enum class AcousticAssessment {
    INCONCLUSIVE, NO_SUSPICIOUS_CUTOFF, SUSPECTED_UPSCALED,
}

data class AcousticAuditResult(
    val mediaId: String,
    val sourceLabel: String,
    val sampleRate: Int,
    val channelCount: Int,
    val startMs: Long,
    val durationMs: Long,
    val cutoffHz: Float?,
    val nyquistHz: Float,
    val width: Int,
    val height: Int,
    // Index = frequencyRow * width + timeColumn; row zero is the lowest frequency.
    val magnitudeDb: FloatArray,
    val assessment: AcousticAssessment,
    val limitations: List<String>,
)

data class AcousticAuditState(
    val status: AcousticAuditStatus = AcousticAuditStatus.IDLE,
    val progress: Float? = null,
    val result: AcousticAuditResult? = null,
    val message: String? = null,
)

object AcousticAuditLimits {
    const val SAMPLE_SECONDS = 20
    const val MAX_PCM_BYTES = 16 * 1024 * 1024
    const val MAX_PCM_FRAMES = 192_000 * SAMPLE_SECONDS
    const val MAX_HEAT_WIDTH = 512
    const val MAX_HEAT_HEIGHT = 1024
    const val MAX_HEAT_CELLS = MAX_HEAT_WIDTH * MAX_HEAT_HEIGHT
    const val DEADLINE_MS = 45_000L
    const val STALL_MS = 5_000L
    const val NETWORK_CALL_MS = 2_000L
    const val MAX_READ_BYTES = 32L * 1024 * 1024
    const val MAX_READ_CHUNK = 64 * 1024
}

// Internal source identity may contain a signed URI. Never put it in state, logs or exceptions.
internal class AcousticSourceSnapshot(
    val mediaId: String,
    val source: AudioSourceType,
    val cacheKey: String,
    val uri: String?,
    val expiresAtMs: Long,
    val contentLength: Long?,
    val advertisedSampleRate: Int?,
    val claimedLossless: Boolean,
) {
    fun matches(other: AcousticSourceSnapshot?): Boolean =
        other != null && mediaId == other.mediaId && source == other.source &&
            cacheKey == other.cacheKey && uri == other.uri && expiresAtMs == other.expiresAtMs &&
            contentLength == other.contentLength && advertisedSampleRate == other.advertisedSampleRate &&
            claimedLossless == other.claimedLossless
}

internal class AcousticUnavailable(val code: String) : Exception()

internal class AcousticPcm(
    val samples: FloatArray,
    val sampleRate: Int,
    val channelCount: Int,
    val startUs: Long,
    val frames: Int,
    val truncated: Boolean,
    val isFloat: Boolean,
)
