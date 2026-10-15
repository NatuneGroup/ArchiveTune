/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
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
 * `MusicService` cuts the playback chain at YouTube, so a source that the merge places *after*
 * YouTube is silently dropped from playback and shows up below the visual end of the order picker.
 * Sources missing from the stored CSV are therefore inserted directly above YouTube rather than
 * appended, which keeps every source reachable for a user whose stored order predates it — and a
 * source can only enter the order by being there: enabling a toggle never rewrites the order.
 */
class SourceOrderTest {
    @Test
    fun blankOrderYieldsDefaults() {
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(null))
        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(""))
    }

    @Test
    fun spotifyAmazonQqAreFirstClassJustAboveYouTube() {
        // Spotify, Amazon and QQ are first-class entries now: in the defaults, just above
        // YouTube (Spotify directly before it), defaulting to off.
        assertEquals(AudioSourceType.YOUTUBE, AudioSourceConfig.DEFAULT_ORDER.last())
        assertEquals(AudioSourceType.SPOTIFY, AudioSourceConfig.DEFAULT_ORDER[AudioSourceConfig.DEFAULT_ORDER.size - 2])
        assertTrue(AudioSourceType.AMAZON in AudioSourceConfig.DEFAULT_ORDER)
        assertTrue(AudioSourceType.QQ in AudioSourceConfig.DEFAULT_ORDER)
        assertTrue(AudioSourceConfig.DEFAULT_ORDER.indexOf(AudioSourceType.SPOTIFY) > AudioSourceConfig.DEFAULT_ORDER.indexOf(AudioSourceType.JIOSAAVN))
        assertFalseByDefault(AudioSourceType.SPOTIFY)
        assertFalseByDefault(AudioSourceType.AMAZON)
        assertFalseByDefault(AudioSourceType.QQ)
        assertTrue(AudioSourceConfig.DEFAULT_ENABLED.getValue(AudioSourceType.TIDAL))
        assertTrue(AudioSourceConfig.DEFAULT_ENABLED.getValue(AudioSourceType.YOUTUBE))
    }

    private fun assertFalseByDefault(source: AudioSourceType) {
        assertEquals("$source must default to off", false, AudioSourceConfig.DEFAULT_ENABLED.getValue(source))
    }

    @Test
    fun legacyOrderGetsNewSourcesAboveYouTube() {
        val merged = AudioSourceConfig.parseOrder("TIDAL,QOBUZ,YOUTUBE")

        assertTrue(merged.indexOf(AudioSourceType.SPOTIFY) < merged.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(merged.indexOf(AudioSourceType.AMAZON) < merged.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(merged.indexOf(AudioSourceType.QQ) < merged.indexOf(AudioSourceType.YOUTUBE))
        assertEquals(
            listOf(
                AudioSourceType.TIDAL,
                AudioSourceType.QOBUZ,
                AudioSourceType.QOBUZ_BACKUP,
                AudioSourceType.DEEZER,
                AudioSourceType.APPLE,
                AudioSourceType.JIOSAAVN,
                AudioSourceType.AMAZON,
                AudioSourceType.QQ,
                AudioSourceType.SPOTIFY,
                AudioSourceType.YOUTUBE,
            ),
            merged,
        )
    }

    @Test
    fun everySourceSurvivesTheYouTubeCut() {
        // The exact assertion that matters: nothing may be stranded after YouTube, because
        // the chain drops everything from YouTube onward.
        val reachable =
            AudioSourceConfig
                .parseOrder("TIDAL,QOBUZ,YOUTUBE")
                .takeWhile { it != AudioSourceType.YOUTUBE }

        assertTrue(AudioSourceType.DEEZER in reachable)
        assertTrue(AudioSourceType.QOBUZ_BACKUP in reachable)
        assertTrue(AudioSourceType.APPLE in reachable)
        assertTrue(AudioSourceType.JIOSAAVN in reachable)
        assertTrue(AudioSourceType.AMAZON in reachable)
        assertTrue(AudioSourceType.QQ in reachable)
        assertTrue(AudioSourceType.SPOTIFY in reachable)
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
        val stored = AudioSourceConfig.DEFAULT_ORDER.joinToString(",") { it.name }
        val merged = AudioSourceConfig.parseOrder(stored)

        assertEquals(stored, merged.joinToString(",") { it.name })
    }

    @Test
    fun enablingASourceNeverMovesYouTubeOrTheOtherEntries() {
        // Regression: toggling a source on used to rewrite the stored order (withSourceAdded),
        // which moved YouTube to the end and promoted every source the user had placed below it.
        // The toggle now flips only its flag, and the merge — read or persisted — never moves a
        // pre-existing entry relative to YouTube or to the other pre-existing entries.
        val orders = listOf("YOUTUBE,TIDAL,QOBUZ", "TIDAL,YOUTUBE,QOBUZ,DEEZER")
        orders.forEach { raw ->
            val before = raw.split(',').map { AudioSourceType.valueOf(it) }
            val merged = AudioSourceConfig.migrateStoredOrder(raw)

            val after = merged.split(',').map { AudioSourceType.valueOf(it) }
            val surviving = after.filter { it in before }
            assertEquals(
                "enabling a source must not reorder $raw",
                before,
                surviving,
            )
        }
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
