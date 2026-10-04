/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils.audioauthenticity

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAuthenticityPcmProcessorTest {
    @Test
    fun pcmIsPassedThroughAndOptedInCutoffIsReported() {
        val resultLatch = CountDownLatch(1)
        var result: AudioAuthenticityProcessorResult? = null
        val processor =
            AudioAuthenticityPcmProcessor(
                playbackContextProvider = {
                    AudioAuthenticityPlaybackContext(
                        playbackKey = "track:source-attempt-1",
                        sourceId = "tidal",
                        rejectSuspectedUpscaled = true,
                        advertisedSampleRateHz = SAMPLE_RATE_HZ,
                        claimedLossless = true,
                    )
                },
                resultListener = {
                    result = it
                    resultLatch.countDown()
                },
            )
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE_HZ, 1, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush()

        val pcm = bandLimitedPcm()
        val input = ByteBuffer.allocateDirect(pcm.size * 2).order(ByteOrder.nativeOrder())
        pcm.forEach { input.putShort((it * Short.MAX_VALUE).toInt().toShort()) }
        input.flip()
        val expected = ByteArray(input.remaining())
        input.duplicate().get(expected)

        processor.queueInput(input)

        val output = processor.output
        val actual = ByteArray(output.remaining())
        output.get(actual)
        assertArrayEquals(expected, actual)
        assertTrue(resultLatch.await(10, TimeUnit.SECONDS))
        assertEquals("track:source-attempt-1", result?.playbackKey)
        assertEquals("tidal", result?.sourceId)
        assertEquals(AudioAuthenticityVerdict.SUSPECTED_UPSCALED, result?.assessment?.verdict)
        assertTrue(result?.shouldReject == true)
        processor.reset()
    }

    @Test
    fun optOutDoesNotAnalyzeOrReject() {
        val resultLatch = CountDownLatch(1)
        val processor =
            AudioAuthenticityPcmProcessor(
                playbackContextProvider = {
                    AudioAuthenticityPlaybackContext(
                        playbackKey = "track:source-attempt-2",
                        sourceId = "qobuz",
                        rejectSuspectedUpscaled = false,
                        advertisedSampleRateHz = SAMPLE_RATE_HZ,
                        claimedLossless = true,
                    )
                },
                resultListener = { resultLatch.countDown() },
            )
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE_HZ, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        val pcm = bandLimitedPcm()
        val input = ByteBuffer.allocateDirect(pcm.size * 2).order(ByteOrder.nativeOrder())
        pcm.forEach { input.putShort((it * Short.MAX_VALUE).toInt().toShort()) }
        input.flip()

        processor.queueInput(input)

        assertTrue(!resultLatch.await(100, TimeUnit.MILLISECONDS))
        processor.reset()
    }

    @Test
    fun unsupportedPcmFormatReportsUnknownWithoutRejecting() {
        var result: AudioAuthenticityProcessorResult? = null
        val processor =
            AudioAuthenticityPcmProcessor(
                playbackContextProvider = {
                    AudioAuthenticityPlaybackContext(
                        playbackKey = "track:unsupported-format",
                        sourceId = "tidal",
                        rejectSuspectedUpscaled = true,
                        advertisedSampleRateHz = SAMPLE_RATE_HZ,
                        claimedLossless = true,
                    )
                },
                resultListener = { result = it },
            )
        val unsupported = AudioProcessor.AudioFormat(SAMPLE_RATE_HZ, 1, C.ENCODING_PCM_8BIT)

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(unsupported))
        assertEquals("track:unsupported-format", result?.playbackKey)
        assertEquals("tidal", result?.sourceId)
        assertEquals(AudioAuthenticityVerdict.UNKNOWN, result?.assessment?.verdict)
        assertTrue(result?.shouldReject == false)
        processor.reset()
    }

    private fun bandLimitedPcm(): FloatArray {
        val windowSize = AudioAuthenticityAnalyzer.ANALYSIS_WINDOW_FRAMES
        val window = FloatArray(windowSize)
        val bins = (16..windowSize / 2 step 8)
            .filter { it * SAMPLE_RATE_HZ / windowSize <= CD_BAND_EDGE_HZ }
        val phases = bins.mapIndexed { index, _ -> (index * 0.61803398875) % (2.0 * PI) }
        val scale = 0.8f / bins.size
        for (sampleIndex in window.indices) {
            var sample = 0.0
            for (toneIndex in bins.indices) {
                sample += sin((2.0 * PI * bins[toneIndex] * sampleIndex / windowSize) + phases[toneIndex])
            }
            window[sampleIndex] = sample.toFloat() * scale
        }
        return FloatArray(windowSize * MIN_WINDOWS) { window[it % windowSize] }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 96_000
        const val CD_BAND_EDGE_HZ = 22_050
        const val MIN_WINDOWS = 16
    }
}
