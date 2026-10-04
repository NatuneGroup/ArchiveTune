/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.audiosource.authenticity

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptDeadline
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimedOutException
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityAssessment
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityAnalyzer
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityPolicy
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class AudioAuthenticityAnalyzerTest {
    @Test
    fun bandLimitedHighResolutionSignalIsFlaggedAsSuspectedNotProven() {
        val assessment = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = periodicSignal(sampleRateHz = SAMPLE_RATE_HZ, cutoffHz = CD_BAND_EDGE_HZ),
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
            claimedLossless = true,
        )

        assertEquals(AudioAuthenticityVerdict.SUSPECTED_UPSCALED, assessment.verdict)
        assertTrue(assessment.confidence >= AudioAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE)
        assertEquals(CD_BAND_EDGE_HZ, assessment.cutoffFrequencyHz)
    }

    @Test
    fun broadSpectrumHighResolutionSignalHasNoCdBandCutoff() {
        val assessment = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = periodicSignal(sampleRateHz = SAMPLE_RATE_HZ, cutoffHz = 40_000),
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
            claimedLossless = true,
        )

        assertEquals(AudioAuthenticityVerdict.NO_SUSPICIOUS_CUTOFF, assessment.verdict)
    }

    @Test
    fun lowBandwidthLegitimateLikeSignalRemainsUnknown() {
        val assessment = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = periodicSignal(sampleRateHz = SAMPLE_RATE_HZ, cutoffHz = 14_000),
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
            claimedLossless = true,
        )

        assertEquals(AudioAuthenticityVerdict.UNKNOWN, assessment.verdict)
    }

    @Test
    fun silenceAndShortSamplesRemainUnknown() {
        val silence = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = FloatArray(WINDOW_SIZE * MIN_WINDOWS),
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
            claimedLossless = true,
        )
        val short = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = FloatArray(WINDOW_SIZE * 4),
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
            claimedLossless = true,
        )

        assertEquals(AudioAuthenticityVerdict.UNKNOWN, silence.verdict)
        assertEquals(AudioAuthenticityVerdict.UNKNOWN, short.verdict)
    }

    @Test
    fun nonFinitePcmSamplesRemainUnknown() {
        val samples = periodicSignal(sampleRateHz = SAMPLE_RATE_HZ, cutoffHz = CD_BAND_EDGE_HZ)
        for (windowIndex in 0 until MIN_WINDOWS) {
            samples[windowIndex * WINDOW_SIZE] = Float.NaN
        }

        val assessment = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = samples,
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
            claimedLossless = true,
        )

        assertEquals(AudioAuthenticityVerdict.UNKNOWN, assessment.verdict)
    }

    @Test
    fun standardRateLosslessCutoffIsFlaggedAsSuspectedNotProven() {
        val sampleRateHz = 44_100
        val assessment = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = periodicSignal(sampleRateHz = sampleRateHz, cutoffHz = 16_500),
            pcmSampleRateHz = sampleRateHz,
            channelCount = 1,
            advertisedSampleRateHz = sampleRateHz,
            claimedLossless = true,
        )

        assertEquals(AudioAuthenticityVerdict.SUSPECTED_UPSCALED, assessment.verdict)
        assertTrue(assessment.confidence >= AudioAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE)
        assertEquals(16_500, assessment.cutoffFrequencyHz)
    }

    @Test
    fun unclaimedLossySignalRemainsUnknown() {
        val assessment = AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = periodicSignal(sampleRateHz = SAMPLE_RATE_HZ, cutoffHz = CD_BAND_EDGE_HZ),
            pcmSampleRateHz = SAMPLE_RATE_HZ,
            channelCount = 1,
            advertisedSampleRateHz = SAMPLE_RATE_HZ,
        )

        assertEquals(AudioAuthenticityVerdict.UNKNOWN, assessment.verdict)
    }

    @Test
    fun rejectionRequiresOptInAndHighConfidence() {
        val suspected = AudioAuthenticityAssessment(
            verdict = AudioAuthenticityVerdict.SUSPECTED_UPSCALED,
            confidence = AudioAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE,
        )

        assertFalse(AudioAuthenticityPolicy.shouldReject(suspected, rejectSuspectedUpscaled = false))
        assertTrue(AudioAuthenticityPolicy.shouldReject(suspected, rejectSuspectedUpscaled = true))
        assertFalse(
            AudioAuthenticityPolicy.shouldReject(
                suspected.copy(confidence = AudioAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE - 0.01),
                rejectSuspectedUpscaled = true,
            ),
        )
        assertFalse(
            AudioAuthenticityPolicy.shouldReject(
                suspected.copy(verdict = AudioAuthenticityVerdict.UNKNOWN),
                rejectSuspectedUpscaled = true,
            ),
        )
    }

    @Test
    fun sourceAttemptDeadlineCannotBeExtendedByAChild() {
        var nowNanos = 0L
        val parent = AudioSourceAttemptDeadline.start(100, nanoTime = { nowNanos })
        val child = parent.child(500)

        nowNanos += 60_000_000L

        assertEquals(40L, parent.remainingMillis())
        assertEquals(40L, child.remainingMillis())
    }

    @Test
    fun sourceAttemptScopePropagatesCallerCancellation() = runBlocking {
        val job = launch {
            AudioSourceAttemptScope.withinSuspending(1_000) { awaitCancellation() }
        }
        yield()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }

    @Test
    fun providerAttemptDeadlineReturnsNoResultWhenExceeded() = runBlocking {
        val result =
            AudioSourceAttemptScope.withinSuspending(10) {
                delay(100)
                "late"
            }

        assertNull(result)
    }

    @Test
    fun activeAttemptDeadlineCapsOkHttpCallTimeout() {
        var nowNanos = 0L
        val deadline = AudioSourceAttemptDeadline.start(200, nanoTime = { nowNanos })
        val call =
            OkHttpClient().newCall(
                Request.Builder().url("http://localhost/").build(),
            )

        AudioSourceAttemptScope.withDeadline(deadline) {
            nowNanos = TimeUnit.MILLISECONDS.toNanos(57)
            val boundedCall = call.withAudioSourceAttemptDeadline()
            assertEquals(TimeUnit.MILLISECONDS.toNanos(143), boundedCall.timeout().timeoutNanos())

            nowNanos = TimeUnit.MILLISECONDS.toNanos(200)
            try {
                call.withAudioSourceAttemptDeadline()
            } catch (_: AudioSourceAttemptTimedOutException) {
                return@withDeadline
            }
            throw AssertionError("Expected an already-expired source deadline to fail before execution")
        }
    }

    @Test
    fun canceledHttpCallsPropagateButOrdinaryTimeoutsDoNot() {
        val cancelled = IOException("Canceled")
        try {
            cancelled.rethrowIfAudioSourceCancelled()
        } catch (error: CancellationException) {
            assertEquals(cancelled, error.cause)
            return
        }
        throw AssertionError("Expected source cancellation to propagate")
    }

    @Test
    fun normalSocketTimeoutDoesNotBecomeCallerCancellation() {
        SocketTimeoutException("timeout").rethrowIfAudioSourceCancelled()
    }

    @Test
    fun callTimeoutWithCanceledCauseDoesNotBecomeCallerCancellation() {
        InterruptedIOException("timeout")
            .apply { initCause(IOException("Canceled")) }
            .rethrowIfAudioSourceCancelled()
    }

    @Test
    fun wrappedSocketTimeoutWithCanceledCauseDoesNotBecomeCallerCancellation() {
        val timeout = SocketTimeoutException("timeout").apply { initCause(IOException("Canceled")) }
        IOException("Provider failed", timeout).rethrowIfAudioSourceCancelled()
    }

    @Test(expected = CancellationException::class)
    fun explicitCancellationStillPropagatesWhenNestedInTimeout() {
        SocketTimeoutException("timeout")
            .apply { initCause(CancellationException("Track changed")) }
            .rethrowIfAudioSourceCancelled()
    }

    private fun periodicSignal(sampleRateHz: Int, cutoffHz: Int): FloatArray {
        val block = FloatArray(WINDOW_SIZE)
        val bins = (16..WINDOW_SIZE / 2 step 8)
            .filter { it * sampleRateHz / WINDOW_SIZE <= cutoffHz }
        val phases = bins.mapIndexed { index, _ -> (index * 0.61803398875) % (2.0 * Math.PI) }
        val scale = (0.2 / kotlin.math.sqrt(bins.size.toDouble())).toFloat()
        for (sampleIndex in block.indices) {
            var value = 0.0
            for (toneIndex in bins.indices) {
                val bin = bins[toneIndex]
                value += kotlin.math.sin(
                    (2.0 * Math.PI * bin * sampleIndex / WINDOW_SIZE) + phases[toneIndex],
                )
            }
            block[sampleIndex] = value.toFloat() * scale
        }
        return FloatArray(block.size * MIN_WINDOWS) { index -> block[index % block.size] }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 96_000
        const val CD_BAND_EDGE_HZ = 22_050
        const val WINDOW_SIZE = 8_192
        const val MIN_WINDOWS = 16
    }
}
