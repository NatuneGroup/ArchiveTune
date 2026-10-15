/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import moe.rukamori.archivetune.playback.smart.TrackAnalysis
import moe.rukamori.archivetune.playback.smart.TransitionTrackInfo
import moe.rukamori.archivetune.playback.smart.TransitionTier
import moe.rukamori.archivetune.playback.smart.assessTransitionTier
import moe.rukamori.archivetune.playback.smart.planTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTransitionPolicyTest {
    @Test
    fun lowConfidenceTempoUsesPlainCrossfade() {
        val current = analysis(bpm = 120.0, confidence = 0.10)
        val next = analysis(bpm = 122.0, confidence = 0.15)

        val verdict = assessTransitionTier(current, next)

        assertEquals(TransitionTier.PLAIN_CROSSFADE, verdict.tier)
        assertTrue(verdict.reasons.contains("beat-confidence"))
    }

    @Test
    fun compatibleConfidentTemposAllowBeatmatchedTier() {
        val current = analysis(bpm = 120.0, confidence = 0.90)
        val next = analysis(bpm = 123.0, confidence = 0.85)

        assertEquals(TransitionTier.BEATMATCHED, assessTransitionTier(current, next).tier)
    }

    @Test
    fun incompatibleConfidentTemposUseConservativeDjTier() {
        val current = analysis(bpm = 120.0, confidence = 0.90)
        val next = analysis(bpm = 150.0, confidence = 0.85)

        assertEquals(TransitionTier.DJ_ASSISTED, assessTransitionTier(current, next).tier)
    }

    @Test
    fun outOfRangeTempoCannotAuthorizeBeatmatching() {
        val current = analysis(bpm = 20.0, confidence = 1.0)
        val next = analysis(bpm = 120.0, confidence = 1.0)

        assertEquals(TransitionTier.PLAIN_CROSSFADE, assessTransitionTier(current, next).tier)
    }

    @Test
    fun detectedNaturalFadeUsesItsOwnBoundedWindow() {
        val current = analysis(bpm = 120.0, confidence = 0.10).copy(
            trackId = "outgoing",
            duration = 180.0,
            audibleStartTime = 2.0,
            introEndTime = 12.0,
            contentEndTime = 179.0,
            finalFadeOnsetTime = 171.0,
        )
        val next = analysis(bpm = 122.0, confidence = 0.15).copy(trackId = "incoming", duration = 170.0)

        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("outgoing", 180_000L),
            nextTrack = TransitionTrackInfo("incoming", 170_000L),
            currentTime = 160.0,
            duration = 180.0,
            fadeSeconds = 3.0,
            minFadeSeconds = 1.0,
            mode = moe.rukamori.archivetune.playback.smart.CrossfadeMode.SMART,
        )

        assertEquals(171.0, plan.transitionStart, 0.001)
        assertEquals(179.0, plan.transitionEnd, 0.001)
        assertEquals(8.0, plan.fadeSeconds, 0.001)
        assertEquals("before-natural-fade", plan.reason)
    }

    @Test
    fun naturalFadeDetectionCannotStartBeforeTheIntroOrOverrunContentEnd() {
        val current = analysis(bpm = 120.0, confidence = 0.10).copy(
            trackId = "outgoing",
            duration = 180.0,
            introEndTime = 12.0,
            contentEndTime = 179.0,
            finalFadeOnsetTime = 11.0,
        )
        val next = analysis(bpm = 122.0, confidence = 0.15).copy(trackId = "incoming", duration = 170.0)

        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("outgoing", 180_000L),
            nextTrack = TransitionTrackInfo("incoming", 170_000L),
            duration = 180.0,
            fadeSeconds = 3.0,
            minFadeSeconds = 1.0,
            mode = moe.rukamori.archivetune.playback.smart.CrossfadeMode.SMART,
        )

        assertEquals(176.0, plan.transitionStart, 0.001)
        assertEquals(179.0, plan.transitionEnd, 0.001)
    }

    @Test
    fun naturalTailFadeIsCappedAtTheAutomaticMaximum() {
        val current = analysis(bpm = 120.0, confidence = 0.10).copy(
            trackId = "outgoing",
            duration = 180.0,
            introEndTime = 12.0,
            contentEndTime = 179.0,
            finalFadeOnsetTime = 149.0,
        )
        val next = analysis(bpm = 122.0, confidence = 0.15).copy(trackId = "incoming", duration = 170.0)

        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("outgoing", 180_000L),
            nextTrack = TransitionTrackInfo("incoming", 170_000L),
            duration = 180.0,
            fadeSeconds = 3.0,
            minFadeSeconds = 1.0,
            mode = moe.rukamori.archivetune.playback.smart.CrossfadeMode.SMART,
        )

        assertEquals(167.0, plan.transitionStart, 0.001)
        assertEquals(179.0, plan.transitionEnd, 0.001)
        assertEquals(12.0, plan.fadeSeconds, 0.001)
    }

    @Test
    fun confidentBeatTransitionStillUsesTheNaturalTailEnd() {
        val current = analysis(bpm = 120.0, confidence = 0.90).copy(
            trackId = "outgoing",
            duration = 180.0,
            introEndTime = 12.0,
            contentEndTime = 179.0,
            finalFadeOnsetTime = 171.0,
        )
        val next = analysis(bpm = 123.0, confidence = 0.90).copy(trackId = "incoming", duration = 170.0)

        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("outgoing", 180_000L),
            nextTrack = TransitionTrackInfo("incoming", 170_000L),
            duration = 180.0,
            fadeSeconds = 3.0,
            minFadeSeconds = 1.0,
            mode = moe.rukamori.archivetune.playback.smart.CrossfadeMode.SMART,
        )

        assertTrue(plan.transitionStart >= 171.0)
        assertEquals(179.0, plan.transitionEnd, 0.001)
        assertTrue(plan.fadeSeconds <= 8.0)
    }

    private fun analysis(bpm: Double, confidence: Double) =
        TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            bpm = bpm,
            beatInterval = 60.0 / bpm,
            beatConfidence = confidence,
        )
}
