/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.sin
import moe.rukamori.archivetune.constants.AutomixPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactAutomixAnalyzerTest {
    @Test
    fun invalidAndSilentAudioIsRejected() {
        assertNull(CompactAutomixAnalyzer.analyze(floatArrayOf(), 1.0, AutomixPerformanceMode.EFFICIENT))
        assertNull(CompactAutomixAnalyzer.analyze(floatArrayOf(0f), Double.NaN, AutomixPerformanceMode.EFFICIENT))
        assertNull(CompactAutomixAnalyzer.analyze(FloatArray(SAMPLE_RATE), 1.0, AutomixPerformanceMode.EFFICIENT))
        assertNull(CompactAutomixAnalyzer.analyze(FloatArray(SAMPLE_RATE), 601.0, AutomixPerformanceMode.EFFICIENT))
    }

    @Test
    fun shortTrackDoesNotClaimTempoOrKey() {
        val samples = FloatArray(6 * SAMPLE_RATE) { index ->
            sin(2.0 * PI * 440.0 * index / SAMPLE_RATE).toFloat()
        }

        val features = CompactAutomixAnalyzer.analyze(samples, 6.0, AutomixPerformanceMode.EFFICIENT)

        assertNotNull(features)
        val result = requireNotNull(features)
        assertEquals(0.0, result.bpm, 0.0)
        assertTrue(result.downbeats.isEmpty())
        assertEquals("", result.key)
        assertEquals(0.0, result.keyConfidence, 0.0)
    }

    @Test
    fun tempoAndBeatGridRespectConfidenceAndRangeBounds() {
        val features = CompactAutomixAnalyzer.analyze(pulseTrack(40, 120), 40.0, AutomixPerformanceMode.BALANCED)

        assertNotNull(features)
        val result = requireNotNull(features)
        assertTrue(result.bpm == 0.0 || result.bpm in 40.0..220.0)
        if (result.bpm > 0.0) assertTrue(result.beatConfidence >= 0.15)
        if (result.downbeats.isNotEmpty()) assertTrue(result.beatConfidence >= 0.30)
    }

    @Test
    fun variableTempoCannotClaimBeatmatchConfidence() {
        val samples = pulseTrack(20, 100) + pulseTrack(20, 160)
        val features = CompactAutomixAnalyzer.analyze(samples, 40.0, AutomixPerformanceMode.BALANCED)

        assertNotNull(features)
        assertTrue(requireNotNull(features).beatConfidence < 0.55)
    }

    @Test
    fun resamplingRejectsOversizedResultsBeforeAllocation() {
        assertNull(CompactAutomixAnalyzer.resample(FloatArray(600), 1.0, 22_050.0))
    }

    @Test
    fun analysisAndResamplingPropagateCancellation() {
        var analysisChecks = 0
        try {
            CompactAutomixAnalyzer.analyze(
                samples = FloatArray(SAMPLE_RATE),
                durationSeconds = 1.0,
                performanceMode = AutomixPerformanceMode.EFFICIENT,
                checkActive = {
                    analysisChecks += 1
                    if (analysisChecks == 2) throw CancellationException()
                },
            )
            throw AssertionError("Expected analysis cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, analysisChecks)
        }

        var resampleChecks = 0
        try {
            CompactAutomixAnalyzer.resample(FloatArray(1_000), 11_025.0, 22_050.0) {
                resampleChecks += 1
                if (resampleChecks == 2) throw CancellationException()
            }
            throw AssertionError("Expected resampling cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, resampleChecks)
        }
    }

    private fun pulseTrack(durationSeconds: Int, bpm: Int): FloatArray {
        val beatFrames = (SAMPLE_RATE * 60.0 / bpm).toInt()
        val pulseFrames = SAMPLE_RATE / 10
        return FloatArray(durationSeconds * SAMPLE_RATE) { index ->
            val beatPhase = index % beatFrames
            if (beatPhase >= pulseFrames) {
                0f
            } else {
                val envelope = 1.0 - beatPhase.toDouble() / pulseFrames
                (0.8 * sin(2.0 * PI * 880.0 * beatPhase / SAMPLE_RATE) * envelope).toFloat()
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 11_025
    }
}
