/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.math.abs

/**
 * Drift-correction decision for the crossfade handoff.
 *
 * This is the one piece of upstream 15.0.0's crossfade policy the fork lacked:
 * equal-power gains and position-advanced checks already exist in
 * [CrossfadePolicy] (outgoingGain/incomingGain, hasAudioAdvanced) and the live
 * MusicService drives through those, so this file carries only the genuinely
 * new drift guard. Upstream wires it at the crossfade handoff to re-seek the
 * secondary player when the two players drift apart mid-fade.
 */
internal fun needsCorrectiveCrossfadeSeek(
    primaryPositionMs: Long,
    secondaryPositionMs: Long,
    maximumDriftMs: Long,
): Boolean {
    require(maximumDriftMs >= 0L)
    return abs(primaryPositionMs - secondaryPositionMs) > maximumDriftMs
}

/** Observable state of the incoming (secondary) crossfade player, abstracted for the pure policy. */
internal enum class CrossfadeIncomingState { READY_PLAYING, READY_PAUSED, BUFFERING, IDLE, ENDED, ERROR }

/** What a session-player listener should do to the secondary player. */
internal enum class CrossfadeSecondaryCommand { NONE, PLAY, PAUSE }

/** What the crossfade hand-off should do with the incoming song after a failure or stall. */
internal enum class CrossfadeHandoffAction { CONTINUE, PROMOTE, CARRY_TO_PRIMARY, RESTORE_OUTGOING }

/** Live-state snapshot the hand-off decision is made from. Built by MusicService. */
internal data class CrossfadeHandoffSnapshot(
    /** The live playback intent: false means a real user pause (or audio-focus loss). */
    val playbackRequested: Boolean,
    /** The outgoing song has ended, or is parked at its end-of-item pause. */
    val outgoingRanOut: Boolean,
    /** 0..1 progress of the volume ramp. */
    val progress: Float,
    val incomingState: CrossfadeIncomingState,
    /** The incoming player's current item is still the song this fade was armed for. */
    val incomingItemMatchesTarget: Boolean,
    /** Media time the incoming song has actually been heard, measured from its cue point. */
    val incomingHeardMs: Long,
    /** How long the incoming player has not been advancing (0 while it advances). */
    val stallElapsedMs: Long,
)

/**
 * Pure, JVM-testable hand-off decision for crossfades and automix.
 *
 * The commit rule: once the incoming song is the dominant voice (the outgoing song ran out,
 * or the fade is at least half done) it must never be restarted from its beginning. A healthy
 * incoming player is promoted; an unusable one hands its position to the primary player
 * (CARRY_TO_PRIMARY) so the song resumes where the listener left it, through the normal
 * source chain. Before the commit point the outgoing song is still audible, so restoring it
 * is safe.
 */
internal object CrossfadeHandoffPolicy {
    /**
     * From this fade progress on, the incoming song is clearly the dominant voice
     * (incoming gain sin(π/4) ≈ 0.71) and a failure must not restart it.
     */
    const val COMMIT_PROGRESS = 0.5f

    /**
     * Below this much heard time, restarting the incoming song at its cue is
     * indistinguishable from carrying the position, so the carry is refused.
     */
    const val MIN_CARRY_MS = 1_500L

    /** Stall grace before an uncommitted fade restores the outgoing song. */
    const val UNCOMMITTED_STALL_GRACE_MS = 750L

    /**
     * Streamed and DRM sources re-buffer far slower than local ones; their uncommitted
     * stall grace is wider so a slow source is not torn down mid-blend.
     */
    const val UNCOMMITTED_STALL_GRACE_STREAMED_MS = 2_500L

    /**
     * Once the handoff is committed, a stalled incoming player is waited out up to this
     * limit before it is promoted as-is (it resumes from its position when its buffer fills).
     */
    const val COMMITTED_STALL_LIMIT_MS = 8_000L

    fun uncommittedStallGraceMs(streamedOrDrmSource: Boolean): Long =
        if (streamedOrDrmSource) UNCOMMITTED_STALL_GRACE_STREAMED_MS else UNCOMMITTED_STALL_GRACE_MS

    /** A player in one of these states can take over playback as-is (buffering included). */
    fun isPromotableIncoming(state: CrossfadeIncomingState): Boolean =
        state != CrossfadeIncomingState.ERROR &&
            state != CrossfadeIncomingState.IDLE &&
            state != CrossfadeIncomingState.ENDED

    /** True once the incoming song must never be restarted: the outgoing song ended, or the fade is at least half done. */
    fun isCommitted(snapshot: CrossfadeHandoffSnapshot): Boolean =
        snapshot.outgoingRanOut || snapshot.progress >= COMMIT_PROGRESS

    fun decide(
        snapshot: CrossfadeHandoffSnapshot,
        uncommittedStallGraceMs: Long = UNCOMMITTED_STALL_GRACE_MS,
    ): CrossfadeHandoffAction {
        if (!snapshot.incomingItemMatchesTarget) return CrossfadeHandoffAction.RESTORE_OUTGOING
        if (!isPromotableIncoming(snapshot.incomingState)) {
            // Terminal incoming (error, idle or ended): it can no longer produce audio, so its
            // position is carried to the primary player, or the outgoing song is restored. Once
            // committed, "restore" must still not lose the heard time on a sane carry.
            return if (isCommitted(snapshot) && snapshot.incomingHeardMs >= MIN_CARRY_MS) {
                CrossfadeHandoffAction.CARRY_TO_PRIMARY
            } else {
                CrossfadeHandoffAction.RESTORE_OUTGOING
            }
        }
        if (!snapshot.playbackRequested) return CrossfadeHandoffAction.CONTINUE
        if (snapshot.outgoingRanOut) return CrossfadeHandoffAction.PROMOTE
        if (snapshot.incomingState == CrossfadeIncomingState.READY_PLAYING) {
            return CrossfadeHandoffAction.CONTINUE
        }
        return when {
            !isCommitted(snapshot) && snapshot.stallElapsedMs >= uncommittedStallGraceMs -> {
                CrossfadeHandoffAction.RESTORE_OUTGOING
            }
            isCommitted(snapshot) && snapshot.stallElapsedMs >= COMMITTED_STALL_LIMIT_MS -> {
                CrossfadeHandoffAction.PROMOTE
            }
            else -> CrossfadeHandoffAction.CONTINUE
        }
    }

    /**
     * Position the primary player must resume the target song at when the incoming player
     * cannot be promoted. Returns null when there is nothing worth carrying (below the
     * minimum carry, or a nonsensical position): the caller then seeks to the cue instead,
     * which for a classic fade is the song's start.
     *
     * [incomingPositionMs] is the incoming player's absolute media position; the minimum
     * carry is measured past [cueMs], because that is the part of the song the listener
     * actually heard. [targetDurationMs] of [unsetTime] (or <= 0) applies no upper clamp.
     */
    fun carriedPositionMs(
        incomingPositionMs: Long,
        cueMs: Long,
        targetDurationMs: Long,
        unsetTime: Long,
        endGuardMs: Long,
        minCarryMs: Long = MIN_CARRY_MS,
    ): Long? {
        if (incomingPositionMs < 0L) return null
        if (incomingPositionMs - cueMs.coerceAtLeast(0L) < minCarryMs) return null
        val upperMs =
            if (targetDurationMs == unsetTime || targetDurationMs <= 0L) {
                Long.MAX_VALUE
            } else {
                (targetDurationMs - endGuardMs).coerceAtLeast(0L)
            }
        return incomingPositionMs.coerceIn(0L, upperMs)
    }
}

/**
 * Pure decisions for the session-player listeners, so the "no pause" rule for the secondary
 * player is testable. The session player's `isPlaying` turning false during a blend is either
 * the outgoing player's own end-of-item pause or a transient buffering stall — never a reason
 * to pause the incoming player (4nx3b 26b407c39). Real user pauses are mirrored by
 * `onPlayWhenReadyChanged` through the live playback intent instead.
 */
internal object CrossfadeListenerPolicy {
    fun onSessionIsPlayingChanged(
        isPlaying: Boolean,
        crossfading: Boolean,
        handoffInProgress: Boolean,
    ): CrossfadeSecondaryCommand =
        if (crossfading && !handoffInProgress && isPlaying) CrossfadeSecondaryCommand.PLAY else CrossfadeSecondaryCommand.NONE
}

/** Result of [CrossfadeQueueSync.plan]: the two replacement ranges for the incoming player's queue. */
internal data class CrossfadeQueueSyncPlan(
    /** Index of the playing (target) item in the incoming player. Never replaced. */
    val incomingCurrentIndex: Int,
    /** Ids to write into incoming[0 until incomingCurrentIndex]; empty leaves that range alone. */
    val beforeIds: List<String>,
    /** Ids to write into incoming[incomingCurrentIndex + 1 until count]; empty leaves it alone. */
    val afterIds: List<String>,
)

/**
 * Pure queue-sync planning for the crossfade promotion: reshapes the incoming player's
 * snapshot queue to match the live queue without ever replacing the playing item, so
 * removals, insertions and reorders made while the fade was armed survive the promotion.
 * Ranges are positional (index-based), so duplicate media ids (repeat-one, the same song
 * queued twice) are handled naturally.
 */
internal object CrossfadeQueueSync {
    /**
     * Returns the sync plan, or null when it cannot be built safely: the live target index is
     * out of range (the target was removed from the live queue) or the incoming player's
     * current item is not the target song.
     */
    fun plan(
        liveIds: List<String>,
        liveTargetIndex: Int,
        incomingIds: List<String>,
        incomingCurrentIndex: Int,
    ): CrossfadeQueueSyncPlan? {
        if (liveTargetIndex !in liveIds.indices) return null
        if (incomingCurrentIndex !in incomingIds.indices) return null
        if (incomingIds[incomingCurrentIndex] != liveIds[liveTargetIndex]) return null

        val beforeIds = liveIds.take(liveTargetIndex)
        val afterIds = liveIds.drop(liveTargetIndex + 1)
        val beforeUnchanged = beforeIds == incomingIds.take(incomingCurrentIndex)
        val afterUnchanged = afterIds == incomingIds.drop(incomingCurrentIndex + 1)
        return CrossfadeQueueSyncPlan(
            incomingCurrentIndex = incomingCurrentIndex,
            beforeIds = if (beforeUnchanged) emptyList() else beforeIds,
            afterIds = if (afterUnchanged) emptyList() else afterIds,
        )
    }
}
