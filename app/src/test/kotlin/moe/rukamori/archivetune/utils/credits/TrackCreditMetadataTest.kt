/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils.credits

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCreditMetadataTest {
    @Test
    fun descriptionParserKeepsOnlyExplicitWriterMetadata() {
        val credits = trackCreditsFromMetadata(
            videoDescription = "Official video\nWritten by: Ada Lovelace\nFilmed in London",
            lyricsMetadata = null,
        )

        assertEquals(
            listOf(TrackCredit(TrackCreditRole.WRITTEN_BY, "Ada Lovelace", TrackCreditSource.VIDEO_DESCRIPTION)),
            credits,
        )
    }

    @Test
    fun descriptionRolesAreNormalizedAndDuplicateRowsAreRemoved() {
        val credits = trackCreditsFromMetadata(
            videoDescription = "Songwriters: Ada\nComposed by: Grace\nSongwriters: Ada",
            lyricsMetadata = null,
        )

        assertEquals(
            listOf(
                TrackCredit(TrackCreditRole.WRITTEN_BY, "Ada", TrackCreditSource.VIDEO_DESCRIPTION),
                TrackCredit(TrackCreditRole.COMPOSED_BY, "Grace", TrackCreditSource.VIDEO_DESCRIPTION),
            ),
            credits,
        )
    }

    @Test
    fun ttmlWriterTagsAreExtractedInOrderWithoutDuplicates() {
        val lyrics = "<ttm:songwriter>Ada</ttm:songwriter><composer>Grace</composer><writer>Ada</writer>"

        assertEquals(
            listOf(
                TrackCredit(TrackCreditRole.WRITTEN_BY, "Ada", TrackCreditSource.LYRICS_METADATA),
                TrackCredit(TrackCreditRole.COMPOSED_BY, "Grace", TrackCreditSource.LYRICS_METADATA),
            ),
            trackCreditsFromMetadata(null, lyrics),
        )
    }

    @Test
    fun trailingLyricCreditLinesAreUsedWhenTtmlMetadataIsAbsent() {
        val lyrics = "First line\nSecond line\n[01:02.3] Written by: Ada / Grace"

        assertEquals(
            listOf(TrackCredit(TrackCreditRole.WRITTEN_BY, "Ada / Grace", TrackCreditSource.LYRICS_METADATA)),
            trackCreditsFromMetadata(null, lyrics),
        )
    }

    @Test
    fun descriptionCreditsTakePrecedenceOverLyricFallback() {
        val credits = trackCreditsFromMetadata(
            videoDescription = "Written by: Ada",
            lyricsMetadata = "<songwriter>Grace</songwriter>",
        )

        assertEquals(
            listOf(TrackCredit(TrackCreditRole.WRITTEN_BY, "Ada", TrackCreditSource.VIDEO_DESCRIPTION)),
            credits,
        )
    }
}
