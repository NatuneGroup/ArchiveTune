/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import moe.rukamori.archivetune.db.entities.FormatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {
    private fun format(
        mimeType: String,
        codecs: String,
        bitrate: Int,
        sampleRate: Int?,
        evidenceMimeType: String? = null,
        evidenceCodecs: String? = null,
        evidenceSampleRate: Int? = null,
        evidenceBitDepth: Int? = null,
    ) = FormatEntity(
        id = "id",
        itag = 0,
        mimeType = mimeType,
        codecs = codecs,
        bitrate = bitrate,
        sampleRate = sampleRate,
        contentLength = 0L,
        loudnessDb = null,
        playbackUrl = null,
        evidenceMimeType = evidenceMimeType,
        evidenceCodecs = evidenceCodecs,
        evidenceSampleRate = evidenceSampleRate,
        evidenceBitDepth = evidenceBitDepth,
    )

    @Test
    fun `declared lossless without observed evidence is unverified`() {
        val quality = format("audio/flac", "flac", 2_000_000, 96_000).streamQuality()
        assertEquals(StreamQualityTier.UNVERIFIED, quality.tier)
        assertFalse(quality.isLossless)
        assertEquals("FLAC", quality.codec)
    }

    @Test
    fun `bitrate and declared rate never earn lossless or hi-res`() {
        assertEquals(StreamQualityTier.LOSSY, format("audio/mp4", "", 2_500_000, 96_000).streamQuality().tier)
        assertEquals(StreamQualityTier.LOSSY, format("audio/mp4", "", 1_411_000, 44_100).streamQuality().tier)
        assertEquals(StreamQualityTier.LOSSY, format("audio/eac3", "ec-3", 768_000, 48_000).streamQuality().tier)
    }

    @Test
    fun `observed flac is lossless and hi-res needs depth above 16 or rate above 48 kHz`() {
        fun observed(bitDepth: Int?, rate: Int) =
            format(
                mimeType = "audio/flac",
                codecs = "flac",
                bitrate = 0,
                sampleRate = rate,
                evidenceMimeType = "audio/flac",
                evidenceCodecs = "flac",
                evidenceSampleRate = rate,
                evidenceBitDepth = bitDepth,
            ).streamQuality()

        assertEquals(StreamQualityTier.LOSSLESS, observed(16, 44_100).tier)
        assertEquals(StreamQualityTier.LOSSLESS, observed(16, 48_000).tier)
        assertEquals(StreamQualityTier.LOSSLESS, observed(null, 44_100).tier)
        assertEquals(StreamQualityTier.HI_RES_LOSSLESS, observed(24, 44_100).tier)
        assertEquals(StreamQualityTier.HI_RES_LOSSLESS, observed(16, 96_000).tier)
        assertTrue(observed(16, 44_100).isLossless)
        assertEquals("FLAC", observed(16, 44_100).codec)
    }

    @Test
    fun `observed lossy codec wins over a declared lossless one`() {
        val quality = format(
            mimeType = "audio/flac",
            codecs = "flac",
            bitrate = 0,
            sampleRate = 44_100,
            evidenceMimeType = "audio/webm",
            evidenceCodecs = "opus",
            evidenceSampleRate = 48_000,
        ).streamQuality()
        assertEquals(StreamQualityTier.LOSSY, quality.tier)
        assertEquals("OPUS", quality.codec)
    }

    @Test
    fun `observed raw pcm from a wav container is lossless`() {
        val quality = format(
            mimeType = "audio/mp4",
            codecs = "",
            bitrate = 0,
            sampleRate = null,
            evidenceMimeType = "audio/raw",
            evidenceSampleRate = 44_100,
            evidenceBitDepth = 16,
        ).streamQuality()
        assertEquals(StreamQualityTier.LOSSLESS, quality.tier)
    }

    @Test
    fun `declared lossy streams keep their codec name`() {
        val opus = format("audio/webm", "opus", 160_000, 48_000).streamQuality()
        assertEquals(StreamQualityTier.LOSSY, opus.tier)
        assertFalse(opus.isLossless)
        assertEquals("OPUS", opus.codec)

        val aac = format("audio/mp4", "mp4a.40.2", 256_000, 44_100).streamQuality()
        assertEquals(StreamQualityTier.LOSSY, aac.tier)
        assertEquals("AAC", aac.codec)
    }
}
