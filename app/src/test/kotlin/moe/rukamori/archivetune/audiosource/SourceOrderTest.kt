/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the source-order merge, which decides whether a source is reachable at all.
 *
 * `MusicService.sourceResolutionChain` cuts the playback chain at YouTube, so a source that the merge
 * places *after* YouTube is silently dropped from playback and shows up below the visual end of the
 * order picker. Sources missing from the stored CSV are therefore inserted directly above YouTube
 * rather than appended, which keeps Deezer, Qobuz backup and JioSaavn reachable for a user whose
 * stored order predates them.
 */
class SourceOrderTest {
    @Test
    fun blankOrderYieldsDefaults() {
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(null))
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(""))
    }

    @Test
    fun legacyOrderGetsNewSourcesAboveYouTube() {
        val merged = AudioSourceConfig.parseOrder("TIDAL,QOBUZ,YOUTUBE")

        assertEquals(
            listOf(
                AudioSourceType.TIDAL,
                AudioSourceType.QOBUZ,
                AudioSourceType.QOBUZ_BACKUP,
                AudioSourceType.DEEZER,
                AudioSourceType.APPLE,
                AudioSourceType.JIOSAAVN,
                AudioSourceType.YOUTUBE,
            ),
            merged,
        )
    }

    @Test
    fun everySourceSurvivesTheYouTubeCut() {
        // The exact assertion that matters: nothing may be stranded after YouTube, because
        // sourceResolutionChain() drops everything from YouTube onward.
        val reachable = AudioSourceConfig.parseOrder("TIDAL,QOBUZ,YOUTUBE").takeWhile { it != AudioSourceType.YOUTUBE }

        assertTrue(AudioSourceType.DEEZER in reachable)
        assertTrue(AudioSourceType.QOBUZ_BACKUP in reachable)
        assertTrue(AudioSourceType.APPLE in reachable)
        assertTrue(AudioSourceType.JIOSAAVN in reachable)
    }

    @Test
    fun userPlacementOfYouTubeIsPreserved() {
        val merged = AudioSourceConfig.parseOrder("YOUTUBE,TIDAL,QOBUZ")

        // New sources go directly above YouTube rather than being appended past the end,
        // and stored sources keep their relative order.
        assertTrue(merged.indexOf(AudioSourceType.DEEZER) < merged.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(merged.indexOf(AudioSourceType.TIDAL) > merged.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(merged.indexOf(AudioSourceType.QOBUZ) > merged.indexOf(AudioSourceType.TIDAL))
        assertEquals(AudioSourceConfig.DEFAULT_ORDER.size, merged.size)
    }

    @Test
    fun completeOrderIsReturnedUnchanged() {
        val stored = "JIOSAAVN,APPLE,DEEZER,QOBUZ_BACKUP,QOBUZ,TIDAL,YOUTUBE"
        val merged = AudioSourceConfig.parseOrder(stored)

        assertEquals(stored, merged.joinToString(",") { it.name })
    }

    @Test
    fun optInSourcesCanBeEnabledAndRestoredWithoutDuplicates() {
        val withAmazon = AudioSourceConfig.withSourceAdded(null, AudioSourceType.AMAZON)
        val withQq = AudioSourceConfig.withSourceAdded(withAmazon, AudioSourceType.QQ)
        val order = AudioSourceConfig.parseOrder(withQq)

        assertTrue(order.indexOf(AudioSourceType.AMAZON) < order.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(order.indexOf(AudioSourceType.QQ) < order.indexOf(AudioSourceType.YOUTUBE))
        assertEquals(withQq, AudioSourceConfig.withSourceAdded(withQq, AudioSourceType.AMAZON))
        assertEquals(withQq, AudioSourceConfig.withSourceAdded(withQq, AudioSourceType.QQ))
    }

    @Test
    fun unknownAndDuplicateEntriesAreIgnored() {
        val merged = AudioSourceConfig.parseOrder("TIDAL,NOT_A_SOURCE,tidal, youtube ")

        assertEquals(AudioSourceConfig.DEFAULT_ORDER.size, merged.size)
        assertEquals(AudioSourceConfig.DEFAULT_ORDER.size, merged.distinct().size)
        assertEquals(AudioSourceType.TIDAL, merged.first())
        assertEquals(AudioSourceType.YOUTUBE, merged.last())
    }

    @Test
    fun downloadOrderGetsNewSourcesAboveYouTubeMusic() {
        val merged = DownloadSourceConfig.parseOrder("QOBUZ,TIDAL,YOUTUBE_MUSIC")

        assertTrue(merged.indexOf(DownloadSource.DEEZER) < merged.indexOf(DownloadSource.YOUTUBE_MUSIC))
        assertTrue(merged.indexOf(DownloadSource.QOBUZ_BACKUP) < merged.indexOf(DownloadSource.YOUTUBE_MUSIC))
        assertTrue(merged.indexOf(DownloadSource.JIOSAAVN) < merged.indexOf(DownloadSource.YOUTUBE_MUSIC))
        assertEquals(DownloadSourceConfig.DEFAULT_ORDER.size, merged.size)
    }

    @Test
    fun downloadBlankOrderYieldsDefaults() {
        assertEquals(DownloadSourceConfig.DEFAULT_ORDER, DownloadSourceConfig.parseOrder(null))
        assertEquals(DownloadSourceConfig.DEFAULT_ORDER, DownloadSourceConfig.parseOrder(""))
    }
}
