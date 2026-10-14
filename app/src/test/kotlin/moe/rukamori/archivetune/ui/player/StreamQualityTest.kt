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
    )

    @Test
    fun `hi-res needs 2000 kbps and 96 kHz`() {
        val quality = format("audio/flac", "flac", 2_000_000, 96_000).streamQuality()
        assertEquals(StreamQualityTier.HI_RES_LOSSLESS, quality.tier)
        assertTrue(quality.isLossless)
    }

    @Test
    fun `flac below the hi-res line is plain lossless`() {
        assertEquals(
            StreamQualityTier.LOSSLESS,
            format("audio/flac", "flac", 1_999_000, 96_000).streamQuality().tier,
        )
        assertEquals(
            StreamQualityTier.LOSSLESS,
            format("audio/flac", "flac", 2_400_000, 88_200).streamQuality().tier,
        )
        assertEquals(
            StreamQualityTier.LOSSLESS,
            format("audio/flac", "flac", 900_000, 44_100).streamQuality().tier,
        )
    }

    @Test
    fun `lossless bitrate at a CD rate counts as lossless without a lossless codec string`() {
        assertEquals(
            StreamQualityTier.LOSSLESS,
            format("audio/mp4", "", 1_411_000, 44_100).streamQuality().tier,
        )
    }

    @Test
    fun `lossy streams are labelled by codec`() {
        val opus = format("audio/webm", "opus", 160_000, 48_000).streamQuality()
        assertEquals(StreamQualityTier.LOSSY, opus.tier)
        assertFalse(opus.isLossless)
        assertEquals("OPUS", opus.codec)

        val aac = format("audio/mp4", "mp4a.40.2", 256_000, 44_100).streamQuality()
        assertEquals(StreamQualityTier.LOSSY, aac.tier)
        assertEquals("AAC", aac.codec)
    }

    @Test
    fun `missing sample rate never reaches lossless by bitrate alone`() {
        assertEquals(
            StreamQualityTier.LOSSY,
            format("audio/mp4", "mp4a.40.2", 2_500_000, null).streamQuality().tier,
        )
    }
}
