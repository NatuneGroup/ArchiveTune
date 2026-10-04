/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure, JVM-testable decision logic for crossfades. Player orchestration lives in
 * [MusicService]; every deterministic rule lives here so it can be unit tested.
 */
object CrossfadePolicy {
    fun outgoingElapsedMs(
        startPositionMs: Long,
        currentPositionMs: Long,
        durationMs: Long,
    ): Long {
        if (startPositionMs < 0L || currentPositionMs <= startPositionMs || durationMs <= 0L) return 0L
        return minOf(currentPositionMs - startPositionMs, durationMs)
    }

    /** Equal-power fade: constant perceived loudness across the overlap. */
    fun outgoingGain(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return cos(clamped.toDouble() * (PI / 2.0)).toFloat()
    }

    fun incomingGain(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return sin(clamped.toDouble() * (PI / 2.0)).toFloat()
    }

    fun outgoingVolume(
        progress: Float,
        baseVolume: Float,
        maxGain: Float,
    ): Float = (baseVolume * outgoingGain(progress)).coerceIn(0f, maxGain)

    fun incomingVolume(
        progress: Float,
        baseVolume: Float,
        maxGain: Float,
    ): Float = (baseVolume * incomingGain(progress)).coerceIn(0f, maxGain)

    /**
     * Resolves the target queue index for a crossfade: repeat-one crossfades back into the
     * current item, anything else targets the next item. Returns null when there is no valid
     * target (unset, out of range, or same-index non-repeat transition).
     */
    fun resolveTargetIndex(
        repeatOne: Boolean,
        currentIndex: Int,
        nextIndex: Int,
        itemCount: Int,
        unsetIndex: Int,
    ): Int? {
        if (itemCount <= 0 || currentIndex !in 0 until itemCount) return null
        val target = if (repeatOne) currentIndex else nextIndex
        if (target == unsetIndex || target !in 0 until itemCount) return null
        if (!repeatOne && target == currentIndex) return null
        return target
    }

    /**
     * Clamps the configured crossfade duration into a usable range for the current track.
     * Returns null when the track is too short (or of unknown duration) to crossfade.
     */
    fun effectiveDurationMs(
        requestedMs: Long,
        trackDurationMs: Long,
        endGuardMs: Long,
        minDurationMs: Long,
        unsetTime: Long,
    ): Long? {
        if (trackDurationMs == unsetTime || trackDurationMs <= 0L) return null
        val maxDuration = trackDurationMs - endGuardMs
        if (maxDuration < minDurationMs) return null
        return requestedMs.coerceIn(minDurationMs, maxDuration)
    }

    /** State snapshot for the incoming-player readiness decision. */
    data class ReadinessSnapshot(
        val isReady: Boolean,
        val isIdle: Boolean,
        val isEnded: Boolean,
        val hasError: Boolean,
        val bufferedEnough: Boolean,
    )

    /** True only when the incoming player is ready AND has buffered enough audio. */
    fun isReadyForFadeStart(snapshot: ReadinessSnapshot): Boolean =
        !snapshot.hasError && !snapshot.isEnded && snapshot.isReady && snapshot.bufferedEnough

    /** True when the readiness wait must abort (terminal state). */
    fun mustAbortReadiness(snapshot: ReadinessSnapshot): Boolean = snapshot.hasError || snapshot.isEnded

    /** State snapshot for the audio-advancement decision. */
    data class AudioAdvancementSnapshot(
        val isReady: Boolean,
        val isPlaying: Boolean,
        val hasError: Boolean,
        val positionMs: Long,
        val previousPositionMs: Long,
    )

    /** True only when the play position actually advanced between polls. */
    fun hasAudioAdvanced(snapshot: AudioAdvancementSnapshot): Boolean =
        !snapshot.hasError &&
            snapshot.isReady &&
            snapshot.isPlaying &&
            snapshot.positionMs > snapshot.previousPositionMs

    /** State snapshot for the promotion decision at fade completion. */
    data class PromotionSnapshot(
        val generationMatches: Boolean,
        val targetIndex: Int,
        val hasError: Boolean,
        val isIdle: Boolean,
        val isEnded: Boolean,
        val unsetIndex: Int,
    )

    /**
     * The incoming player may be promoted only when the crossfade generation is still current
     * (no cancel/skip happened), the target item still exists in its queue, and the player is
     * not in a terminal state. When this is false, the outgoing player must remain
     * authoritative and must NOT be released.
     */
    fun mayPromote(snapshot: PromotionSnapshot): Boolean =
        snapshot.generationMatches &&
            snapshot.targetIndex != snapshot.unsetIndex &&
            !snapshot.hasError &&
            !snapshot.isIdle &&
            !snapshot.isEnded

    fun shouldResumeAfterEnded(
        playbackRequested: Boolean,
        hasNextItem: Boolean,
        repeatOne: Boolean,
        suppressAutoPlayback: Boolean,
    ): Boolean =
        playbackRequested &&
            !suppressAutoPlayback &&
            (hasNextItem || repeatOne)
}
