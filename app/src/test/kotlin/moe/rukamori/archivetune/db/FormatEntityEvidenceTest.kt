/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db

import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.evidenceSurvivesRedeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared-write decision that keeps observed decoder evidence bound to the rendition it was
 * observed on: re-declaring the same rendition keeps evidence, any other rendition of the media id
 * drops it, and the observation write mirrors this comparison as its SQL guard.
 */
class FormatEntityEvidenceTest {
    private fun row(
        mimeType: String,
        codecs: String,
        sampleRate: Int?,
        evidenceMimeType: String? = null,
        evidenceCodecs: String? = null,
        evidenceSampleRate: Int? = null,
        evidenceBitDepth: Int? = null,
    ) = FormatEntity(
        id = "media",
        itag = 0,
        mimeType = mimeType,
        codecs = codecs,
        bitrate = 1_141,
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
    fun `re-declaring the same rendition keeps observed evidence`() {
        assertTrue(
            evidenceSurvivesRedeclaration(
                row(mimeType = "audio/flac", codecs = "flac", sampleRate = 44_100),
                mimeType = "audio/flac",
                codecs = "flac",
                sampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun `a lossy re-declaration of a media id that played flac drops evidence`() {
        val flacRow = row(mimeType = "audio/flac", codecs = "flac", sampleRate = 44_100)
        assertFalse(
            evidenceSurvivesRedeclaration(
                flacRow,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                sampleRateHz = 44_100,
            ),
        )
        assertFalse(
            evidenceSurvivesRedeclaration(
                flacRow,
                mimeType = "audio/flac",
                codecs = "flac",
                sampleRateHz = 96_000,
            ),
        )
    }

    @Test
    fun `an unknown rate re-declared as unknown keeps evidence`() {
        // The provider reports no rate on either declaration, so the rendition identity is unchanged.
        assertTrue(
            evidenceSurvivesRedeclaration(
                row(mimeType = "audio/flac", codecs = "flac", sampleRate = null),
                mimeType = "audio/flac",
                codecs = "flac",
                sampleRateHz = null,
            ),
        )
    }

    @Test
    fun `a declared rate that differs from the row drops evidence`() {
        assertFalse(
            evidenceSurvivesRedeclaration(
                row(mimeType = "audio/flac", codecs = "flac", sampleRate = null),
                mimeType = "audio/flac",
                codecs = "flac",
                sampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun `a re-declaration that stops reporting a rate drops evidence measured at the old rate`() {
        assertFalse(
            evidenceSurvivesRedeclaration(
                row(mimeType = "audio/flac", codecs = "flac", sampleRate = 96_000),
                mimeType = "audio/flac",
                codecs = "flac",
                sampleRateHz = null,
            ),
        )
    }

    @Test
    fun `creating a row carries no evidence`() {
        assertFalse(
            evidenceSurvivesRedeclaration(
                null,
                mimeType = "audio/flac",
                codecs = "flac",
                sampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun `a codecs variation alone invalidates observed evidence`() {
        val aacRow = row(mimeType = "audio/mp4", codecs = "mp4a.40.2", sampleRate = 48_000)
        assertFalse(
            evidenceSurvivesRedeclaration(
                aacRow,
                mimeType = "audio/mp4",
                codecs = "mp4a.40",
                sampleRateHz = 48_000,
            ),
        )
    }

    @Test
    fun `standalone codec label matches the entity label`() {
        // "alac" in an MP4 container keeps the observed-evidence reader and the declared badge on
        // one naming convention without the whole-entity copy.
        val entity =
            row(
                mimeType = "audio/mp4; codecs=\"alac\"",
                codecs = "alac",
                sampleRate = 48_000,
            )
        assertEquals(entity.codecLabel(), codecLabel(entity.mimeType, entity.codecs))
        assertEquals("PCM", codecLabel("audio/raw", ""))
        assertEquals("AAC", codecLabel("audio/mp4", "mp4a.40.2"))
        assertEquals("MP4", codecLabel("audio/mp4", ""))
    }
}
