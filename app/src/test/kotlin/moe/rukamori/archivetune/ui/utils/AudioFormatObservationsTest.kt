/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.playback.AndroidAudioOutputFormat
import moe.rukamori.archivetune.playback.ReportedAudioFormat
import moe.rukamori.archivetune.playback.androidAudioOutputFormatForPlayback
import moe.rukamori.archivetune.playback.reportedAudioFormatForPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatObservationsTest {
    @Test
    fun sourceMetadataCannotBeShownForAnotherTrack() {
        val format = ReportedAudioFormat("one", AudioSourceType.QOBUZ, 96_000, 24, null)
        assertEquals(format, reportedAudioFormatForTrack("one", format))
        assertNull(reportedAudioFormatForTrack("two", format))
        assertNull(format.channelCount)
    }

    @Test
    fun sourceMetadataRemainsUnknownAfterSwitchingProvidersForTheSameTrack() {
        val previous = ReportedAudioFormat("one", AudioSourceType.QOBUZ, 96_000, 24, 2)

        assertEquals(previous, reportedAudioFormatForPlayback("one", AudioSourceType.QOBUZ, previous))
        assertEquals(
            ReportedAudioFormat("one", AudioSourceType.TIDAL, null, null, null),
            reportedAudioFormatForPlayback("one", AudioSourceType.TIDAL, previous),
        )
        assertEquals(
            ReportedAudioFormat("one", null, null, null, null),
            reportedAudioFormatForPlayback("one", null, previous),
        )
        assertNull(reportedAudioFormatForPlayback(null, AudioSourceType.QOBUZ, previous))
    }

    @Test
    fun reportedSampleRateDoesNotRequireAStoredFormat() {
        val format = ReportedAudioFormat("one", AudioSourceType.QOBUZ, 96_000, 24, null)

        assertEquals(96_000, reportedAudioSampleRateHz(format, null))
        assertEquals("96 kHz", formatReportedSampleRate(reportedAudioSampleRateHz(format, null)))
    }

    @Test
    fun unknownCurrentSourceRateDoesNotReuseStoredMetadata() {
        val previous = ReportedAudioFormat("one", AudioSourceType.QOBUZ, 96_000, 24, 2)
        val current = reportedAudioFormatForPlayback("one", AudioSourceType.TIDAL, previous)

        assertNull(reportedAudioSampleRateHz(current, 44_100))
        assertEquals(44_100, reportedAudioSampleRateHz(null, 44_100))
    }

    @Test
    fun androidOutputConfigurationCannotBeShownForAnotherTrack() {
        val format = AndroidAudioOutputFormat("one", 48_000, 2, C.ENCODING_PCM_16BIT, false)
        assertEquals(format, androidAudioOutputFormatForTrack("one", format))
        assertNull(androidAudioOutputFormatForTrack("two", format))
        assertEquals(16, format.pcmFormat.bitsPerSample)
        assertFalse(format.offload)
    }

    @Test
    fun customBitPerfectConversionDoesNotExposeThePreConversionConfiguration() {
        val configuration = AudioSink.AudioTrackConfig(
            C.ENCODING_PCM_FLOAT,
            96_000,
            AudioFormat.CHANNEL_OUT_STEREO,
            false,
            false,
            4_096,
        )

        assertEquals(
            AndroidAudioOutputFormat("one", 96_000, 2, C.ENCODING_PCM_FLOAT, false),
            androidAudioOutputFormatForPlayback("one", configuration, bitPerfectOutputActive = false),
        )
        assertNull(androidAudioOutputFormatForPlayback("one", configuration, bitPerfectOutputActive = true))
        assertNull(androidAudioOutputFormatForPlayback(null, configuration, bitPerfectOutputActive = false))
        assertNull(androidAudioOutputFormatForPlayback("one", null, bitPerfectOutputActive = false))
    }

    @Test
    fun floatingOutputIsNotMislabeledAsIntegerPcm() {
        val format = AndroidAudioOutputFormat("one", 96_000, 2, C.ENCODING_PCM_FLOAT, false)
        assertEquals(32, format.pcmFormat.bitsPerSample)
        assertTrue(format.pcmFormat.isFloatingPoint)
    }

    @Test
    fun compressedOffloadDoesNotInventAPcmBitDepth() {
        val format = AndroidAudioOutputFormat("one", 48_000, 2, C.ENCODING_AAC_LC, true)
        assertNull(format.pcmFormat.bitsPerSample)
        assertTrue(format.offload)
    }
}
