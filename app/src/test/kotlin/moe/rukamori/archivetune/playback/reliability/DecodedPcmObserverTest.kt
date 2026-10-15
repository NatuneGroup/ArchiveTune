/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.reliability

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import moe.rukamori.archivetune.playback.DecodedPcmFormat
import moe.rukamori.archivetune.playback.DecodedPcmObserver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecodedPcmObserverTest {
    @Test
    fun observationUsesDecodedFormatAndPreservesEveryByte() {
        val observed = mutableListOf<AudioProcessor.AudioFormat?>()
        val processor = DecodedPcmObserver(observed::add)
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(format, processor.configure(format))
        processor.flush()
        val samples = byteArrayOf(0, 0, 0, 63, 0, 0, 0, -65)
        val input = ByteBuffer.allocateDirect(samples.size).order(ByteOrder.nativeOrder()).put(samples)
        input.flip()

        processor.queueInput(input)

        val outputBuffer = processor.output
        val output = ByteArray(outputBuffer.remaining())
        outputBuffer.get(output)
        assertArrayEquals(samples, output)
        assertEquals(listOf(null, format), observed)
        processor.reset()
        assertNull(observed.last())
    }

    @Test
    fun emptyInputDoesNotClaimAnObservedSignal() {
        val observed = mutableListOf<AudioProcessor.AudioFormat?>()
        val processor = DecodedPcmObserver(observed::add)
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        processor.queueInput(ByteBuffer.allocateDirect(0))

        assertEquals(listOf<AudioProcessor.AudioFormat?>(null), observed)
    }

    @Test
    fun sameFormatIsReportedOnceUntilThePipelineIsFlushed() {
        val observed = mutableListOf<AudioProcessor.AudioFormat?>()
        val processor = DecodedPcmObserver(observed::add)
        val format = AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        repeat(2) {
            val input = ByteBuffer.allocateDirect(2).apply {
                putShort(5)
                flip()
            }
            processor.queueInput(input)
            processor.output
        }
        assertEquals(1, observed.count { it == format })

        processor.flush()
        val input = ByteBuffer.allocateDirect(2).apply {
            putShort(5)
            flip()
        }
        processor.queueInput(input)

        assertEquals(2, observed.count { it == format })
    }

    @Test
    fun integerAndFloatFormatsAreDistinctAndUnknownEncodingStaysUnknown() {
        val integer = DecodedPcmFormat("local:integer", 44_100, 2, C.ENCODING_PCM_24BIT)
        val floating = DecodedPcmFormat("local:float", 48_000, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(24, integer.bitsPerSample)
        assertFalse(integer.isFloatingPoint)
        assertEquals(32, floating.bitsPerSample)
        assertTrue(floating.isFloatingPoint)
        assertNull(DecodedPcmFormat("unknown", 48_000, 2, C.ENCODING_INVALID).bitsPerSample)
    }

    @Test
    fun aNewTrackWithTheSamePcmFormatIsObservedAgain() {
        var mediaId = "first"
        val observed = mutableListOf<AudioProcessor.AudioFormat?>()
        val processor = DecodedPcmObserver(observed::add, playbackKeyProvider = { mediaId })
        val format = AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        repeat(2) { index ->
            mediaId = if (index == 0) "first" else "second"
            val input = ByteBuffer.allocateDirect(2).apply {
                putShort(5)
                flip()
            }
            processor.queueInput(input)
            processor.output
        }

        assertEquals(listOf(null, format, format), observed)
    }
}
