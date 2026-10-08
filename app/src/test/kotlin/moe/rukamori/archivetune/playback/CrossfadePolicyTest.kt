/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadePolicyTest {
    @Test
    fun equalPowerFadeEndpoints() {
        assertEquals(1f, CrossfadePolicy.outgoingGain(0f), 0.0001f)
        assertEquals(0f, CrossfadePolicy.outgoingGain(1f), 0.0001f)
        assertEquals(0f, CrossfadePolicy.incomingGain(0f), 0.0001f)
        assertEquals(1f, CrossfadePolicy.incomingGain(1f), 0.0001f)
    }

    @Test
    fun equalPowerFadeKeepsConstantPower() {
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f).forEach { progress ->
            val out = CrossfadePolicy.outgoingGain(progress)
            val into = CrossfadePolicy.incomingGain(progress)
            assertEquals(
                "outgoing^2 + incoming^2 must stay 1 (equal power) at $progress",
                1f,
                out * out + into * into,
                0.001f,
            )
        }
        assertEquals(
            Math.sqrt(0.5).toFloat(),
            CrossfadePolicy.outgoingGain(0.5f),
            0.001f,
        )
    }

    @Test
    fun volumeCurvesRespectBaseVolumeAndClamp() {
        assertEquals(0.8f, CrossfadePolicy.outgoingVolume(0f, 0.8f, 1.414f), 0.001f)
        assertEquals(0.8f, CrossfadePolicy.incomingVolume(1f, 0.8f, 1.414f), 0.001f)
        assertEquals(1.414f, CrossfadePolicy.incomingVolume(1f, 2.0f, 1.414f), 0.001f)
        assertEquals(0f, CrossfadePolicy.incomingVolume(0f, 0.8f, 1.414f), 0.001f)
    }

    @Test
    fun targetResolutionRepeatOne() {
        assertEquals(
            2,
            CrossfadePolicy.resolveTargetIndex(
                repeatOne = true,
                currentIndex = 2,
                nextIndex = 3,
                itemCount = 10,
                unsetIndex = -1,
            ),
        )
    }

    @Test
    fun targetResolutionRepeatAllAndOff() {
        assertEquals(
            3,
            CrossfadePolicy.resolveTargetIndex(
                repeatOne = false,
                currentIndex = 2,
                nextIndex = 3,
                itemCount = 10,
                unsetIndex = -1,
            ),
        )
        // repeat-all wraps around: next of last track is 0
        assertEquals(
            0,
            CrossfadePolicy.resolveTargetIndex(
                repeatOne = false,
                currentIndex = 9,
                nextIndex = 0,
                itemCount = 10,
                unsetIndex = -1,
            ),
        )
    }

    @Test
    fun targetResolutionRejectsInvalidTargets() {
        // queue end (no next)
        assertNull(CrossfadePolicy.resolveTargetIndex(false, 9, -1, 10, -1))
        // same-index non-repeat transition
        assertNull(CrossfadePolicy.resolveTargetIndex(false, 2, 2, 10, -1))
        // out of range
        assertNull(CrossfadePolicy.resolveTargetIndex(false, 2, 10, 10, -1))
        // empty queue
        assertNull(CrossfadePolicy.resolveTargetIndex(true, 0, 0, 0, -1))
    }

    @Test
    fun effectiveDurationIsClamped() {
        assertEquals(
            5_000L,
            CrossfadePolicy.effectiveDurationMs(5_000L, 200_000L, 150L, 500L, -1L)!!,
        )
        // requested longer than the track allows: clamped to track end minus guard
        assertEquals(
            9_850L,
            CrossfadePolicy.effectiveDurationMs(20_000L, 10_000L, 150L, 500L, -1L)!!,
        )
        // tiny requested duration clamps up to the minimum
        assertEquals(
            500L,
            CrossfadePolicy.effectiveDurationMs(100L, 200_000L, 150L, 500L, -1L)!!,
        )
        // unknown duration: no crossfade
        assertNull(CrossfadePolicy.effectiveDurationMs(5_000L, -1L, 150L, 500L, -1L))
        // track too short for even the minimum: no crossfade
        assertNull(CrossfadePolicy.effectiveDurationMs(5_000L, 600L, 150L, 500L, -1L))
    }

    @Test
    fun readinessRequiresReadyAndBuffered() {
        fun snapshot(
            ready: Boolean,
            idle: Boolean,
            ended: Boolean,
            error: Boolean,
            buffered: Boolean,
        ) = CrossfadePolicy.ReadinessSnapshot(ready, idle, ended, error, buffered)

        assertTrue(CrossfadePolicy.isReadyForFadeStart(snapshot(true, false, false, false, true)))
        assertFalse(CrossfadePolicy.isReadyForFadeStart(snapshot(true, false, false, false, false)))
        assertFalse(CrossfadePolicy.isReadyForFadeStart(snapshot(false, false, false, false, true)))
        assertTrue(CrossfadePolicy.mustAbortReadiness(snapshot(false, false, true, false, true)))
        assertTrue(CrossfadePolicy.mustAbortReadiness(snapshot(false, false, false, true, true)))
        assertFalse(CrossfadePolicy.mustAbortReadiness(snapshot(false, true, false, false, false)))
    }

    @Test
    fun audioAdvancementRequiresPositionMovement() {
        fun snapshot(
            ready: Boolean,
            playing: Boolean,
            error: Boolean,
            position: Long,
            previous: Long,
        ) = CrossfadePolicy.AudioAdvancementSnapshot(ready, playing, error, position, previous)

        // advancing
        assertTrue(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, false, 1_250L, 1_100L)))
        // same position: not yet audible
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, false, 1_100L, 1_100L)))
        // STATE_READY + isPlaying alone prove nothing without position movement
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, false, 0L, 0L)))
        // paused: no advancement
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, false, false, 1_250L, 1_100L)))
        // error: never valid
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, true, 1_250L, 1_100L)))
    }

    @Test
    fun promotionRequiresCurrentGenerationAndHealthyIncomingPlayer() {
        fun snapshot(
            generationMatches: Boolean,
            targetIndex: Int,
            incomingItemMatchesTarget: Boolean = true,
            error: Boolean = false,
            idle: Boolean = false,
            ended: Boolean = false,
            ready: Boolean = true,
            playing: Boolean = true,
            playbackRequested: Boolean = true,
        ) = CrossfadePolicy.PromotionSnapshot(
            generationMatches = generationMatches,
            targetIndex = targetIndex,
            incomingItemMatchesTarget = incomingItemMatchesTarget,
            hasError = error,
            isIdle = idle,
            isEnded = ended,
            isReady = ready,
            isPlaying = playing,
            playbackRequested = playbackRequested,
            unsetIndex = -1,
        )

        // normal automatic crossfade completion: promote
        assertTrue(CrossfadePolicy.mayPromote(snapshot(true, 3)))
        // stale operation (cancelled by manual next/prev/seek/pause/stop): never promote
        assertFalse(CrossfadePolicy.mayPromote(snapshot(false, 3)))
        // incoming player error: keep the outgoing player authoritative
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, error = true)))
        // incoming idle/ended (e.g. readiness timeout): refuse the destructive handoff
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, idle = true)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, ended = true)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, incomingItemMatchesTarget = false)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, ready = false)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, playing = false)))
        assertTrue(
            CrossfadePolicy.mayPromote(
                snapshot(true, 3, playing = false, playbackRequested = false),
            ),
        )
        // target vanished from the queue (queue replacement): refuse
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, -1)))
    }

    @Test
    fun endedPlaybackResumesOnlyWhenPlaybackWasRequestedAndAQueueTargetExists() {
        assertTrue(
            CrossfadePolicy.shouldResumeAfterEnded(
                playbackRequested = true,
                hasNextItem = true,
                repeatOne = false,
                suppressAutoPlayback = false,
            ),
        )
        assertTrue(
            CrossfadePolicy.shouldResumeAfterEnded(
                playbackRequested = true,
                hasNextItem = false,
                repeatOne = true,
                suppressAutoPlayback = false,
            ),
        )
        assertFalse(
            CrossfadePolicy.shouldResumeAfterEnded(
                playbackRequested = false,
                hasNextItem = true,
                repeatOne = false,
                suppressAutoPlayback = false,
            ),
        )
        assertFalse(
            CrossfadePolicy.shouldResumeAfterEnded(
                playbackRequested = true,
                hasNextItem = true,
                repeatOne = false,
                suppressAutoPlayback = true,
            ),
        )
    }
}
