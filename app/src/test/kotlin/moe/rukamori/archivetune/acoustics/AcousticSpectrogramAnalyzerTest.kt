/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticSpectrogramAnalyzerTest {
    private val rates = listOf(44_100, 48_000)

    private fun analyze(
        samples: FloatArray,
        rate: Int,
        channels: Int = 1,
        frames: Int = samples.size / channels,
        check: () -> Unit = {},
        progress: (Float) -> Unit = {},
    ) = AcousticSpectrogramAnalyzer.analyze(
        "selected-B", "TIDAL", rate, true, listOf("fixture_source"),
        AcousticPcm(samples, rate, channels, 1_234_000L, frames, true, true), check, progress,
    )

    private fun tone(rate: Int, frames: Int, frequency: Double = 3_000.0) =
        FloatArray(frames) { (0.5 * sin(2 * PI * frequency * it / rate)).toFloat() }

    private fun assertHeat(result: AcousticAuditResult) {
        assertTrue(result.width in 1..AcousticAuditLimits.MAX_HEAT_WIDTH)
        assertEquals(1024, result.height)
        assertEquals(result.width * result.height, result.magnitudeDb.size)
        assertTrue(result.magnitudeDb.size <= AcousticAuditLimits.MAX_HEAT_CELLS)
        assertTrue(result.magnitudeDb.all { it.isFinite() && it in -120f..0f })
        assertTrue("spectral_heuristic_not_provenance" in result.limitations)
        assertTrue("fixture_source" in result.limitations)
        assertTrue("pcm_budget_shortened_excerpt" in result.limitations)
        assertEquals("selected-B", result.mediaId)
        assertEquals("TIDAL", result.sourceLabel)
    }

    @Test
    fun knownToneHasMeasuredRateAndFrequencyWithinOneFftBin() {
        for (rate in rates) {
            val updates = mutableListOf<Float>()
            val result = analyze(tone(rate, rate * 2), rate, progress = { updates += it })
            assertHeat(result)
            val peak = (0 until result.height).maxByOrNull { row ->
                result.magnitudeDb[row * result.width]
            }!!
            assertTrue(abs(peak * rate / 2048.0 - 3_000.0) <= rate / 2048.0)
            assertEquals(rate, result.sampleRate)
            assertEquals(rate / 2f, result.nyquistHz, 0f)
            assertEquals(1_234L, result.startMs)
            assertEquals(2_000L, result.durationMs)
            assertEquals(AcousticAssessment.INCONCLUSIVE, result.assessment)
            assertEquals(1f, updates.last(), 0f)
            assertTrue(updates.zipWithNext().all { (a, b) -> a <= b })
        }
    }

    @Test
    fun fullScaleBinCenteredToneReadsMinus352DbWithHannOffset() {
        // Hann coherent gain puts a full-scale bin-centred sine at A^2/4 of the normalised power (-6.02 dB);
        // the 2.4988 dB window offset makes its display heat about -3.52 dB.
        for (rate in rates) {
            val bin = 100
            val frequency = bin * rate.toDouble() / 2048
            val samples = FloatArray(2048) { sin(2 * PI * frequency * it / rate).toFloat() }
            val result = analyze(samples, rate)
            assertEquals(-3.52f, result.magnitudeDb[bin * result.width], 0.02f)
        }
    }

    @Test
    fun antiPhaseStereoRetainsChannelPowerInsteadOfBecomingSilentMono() {
        for (rate in rates) {
            val mono = tone(rate, rate * 2)
            val stereo = FloatArray(mono.size * 2) { if (it % 2 == 0) mono[it / 2] else -mono[it / 2] }
            val reference = analyze(mono, rate)
            val result = analyze(stereo, rate, channels = 2)
            assertHeat(result)
            assertEquals(2, result.channelCount)
            assertEquals(reference.cutoffHz, result.cutoffHz)
            reference.magnitudeDb.indices.forEach {
                assertEquals(reference.magnitudeDb[it], result.magnitudeDb[it], 0.0001f)
            }
            assertTrue(result.magnitudeDb.maxOrNull()!! > -20f)
        }
    }

    @Test
    fun silenceAndShortActiveExcerptsStayInconclusive() {
        for (rate in rates) {
            val silence = analyze(FloatArray(rate * 2), rate)
            val short = analyze(tone(rate, 2048), rate)
            for (result in listOf(silence, short)) {
                assertHeat(result)
                assertEquals(AcousticAssessment.INCONCLUSIVE, result.assessment)
                assertNull(result.cutoffHz)
                assertTrue("insufficient_active_evidence" in result.limitations)
                assertTrue("unknown" in result.limitations)
            }
            assertTrue(silence.magnitudeDb.all { it == -120f })
            assertEquals(1, short.width)
        }
    }

    @Test
    fun unusedCapacityCannotExtendTheMeasuredIntervalOrSupplyAssessmentEvidence() {
        for (rate in rates) {
            val populated = tone(rate, rate * 2)
            val capacity = FloatArray(AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES) { Float.NaN }
            populated.copyInto(capacity)
            val exact = analyze(populated, rate)
            val oversized = analyze(capacity, rate, frames = populated.size)
            assertEquals(exact.durationMs, oversized.durationMs)
            assertEquals(exact.width, oversized.width)
            assertTrue(exact.magnitudeDb.contentEquals(oversized.magnitudeDb))
            assertEquals(AcousticAssessment.INCONCLUSIVE, oversized.assessment)
        }
    }

    @Test
    fun bandLimitedAndBroadbandEvidenceReportSuspicionNotProvenance() {
        for (rate in rates) {
            val limited = analyze(periodicSpectrum(rate, 16_500), rate)
            val broad = analyze(periodicSpectrum(rate, rate / 2 - 300), rate)
            assertHeat(limited)
            assertHeat(broad)
            assertEquals(AcousticAssessment.SUSPECTED_UPSCALED, limited.assessment)
            assertEquals(AcousticAssessment.NO_SUSPICIOUS_CUTOFF, broad.assessment)
            assertTrue(abs(limited.cutoffHz!! - 16_500f) < 250f)
            assertTrue(broad.cutoffHz!! > 20_000f)
        }
    }

    @Test
    fun heatWidthIsBoundedWithoutChangingThePcmRate() {
        val result = analyze(tone(48_000, 600_000), 48_000)
        assertHeat(result)
        assertEquals(AcousticAuditLimits.MAX_HEAT_WIDTH, result.width)
        assertEquals(48_000, result.sampleRate)
        assertEquals(12_500L, result.durationMs)
    }

    @Test
    fun nonFinitePcmAndInvalidFrameOrByteBoundsAreRejected() {
        for (rate in rates) {
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val samples = tone(rate, 2048).apply { this[17] = invalid }
                rejects { analyze(samples, rate) }
            }
            rejects { analyze(FloatArray(2047), rate) }
            rejects { analyze(FloatArray(2048), rate, frames = 2049) }
            rejects { analyze(FloatArray(2048), rate, frames = AcousticAuditLimits.MAX_PCM_FRAMES + 1) }
        }
        rejects { analyze(FloatArray(2048), 7_999) }
        rejects { analyze(FloatArray(2048), 48_000, channels = 9) }
        rejects {
            analyze(FloatArray(AcousticAuditLimits.MAX_PCM_BYTES / Float.SIZE_BYTES + 1), 48_000, frames = 2048)
        }
    }

    @Test
    fun cancellationDuringFftWindowsStopsWithoutPublishingFinalProgress() {
        var checks = 0
        val updates = mutableListOf<Float>()
        try {
            analyze(tone(48_000, 96_000), 48_000, check = {
                if (++checks == 7) throw CancellationException("fixture")
            }, progress = { updates += it })
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(7, checks)
            assertTrue(updates.isNotEmpty())
            assertTrue(updates.last() < 1f)
        }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Invalid PCM must not produce a result")
    }

    private fun periodicSpectrum(rate: Int, cutoff: Int): FloatArray {
        val size = 8192
        val bins = (16 until size / 2 step 8).filter { it * rate / size <= cutoff }
        val scale = 0.2 / sqrt(bins.size.toDouble())
        val phases = bins.mapIndexed { index, _ -> (index * 0.61803398875) % (2 * PI) }
        val block = FloatArray(size) { frame ->
            var value = 0.0
            for (index in bins.indices) {
                value += sin(2 * PI * bins[index] * frame / size + phases[index])
            }
            (value * scale).toFloat()
        }
        return FloatArray(AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES) { block[it % size] }
    }
}
