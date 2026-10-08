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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the download chain, which decides *which* source a download fetches from.
 *
 * The bug these encode: the chain used to skip YouTube Music wherever the user put it, so a
 * YouTube-first order still downloaded from the first lossless source that resolved — a different
 * source, and often a different recording, than playback had picked. YouTube Music now terminates
 * the chain the way YouTube terminates playback's, and a per-song "Play from" pin replaces the
 * order entirely.
 */
class DownloadSourceChainTest {
    @Test
    fun youtubeMusicFirstLeavesNoLosslessSourceToTry() {
        val order = "YOUTUBE_MUSIC,QOBUZ,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN"

        assertTrue(DownloadSourceConfig.fetchChain(order).isEmpty())
        assertTrue(DownloadSourceConfig.resolutionChain(order, null).isEmpty())
    }

    @Test
    fun sourcesBelowYoutubeMusicAreNeverReached() {
        assertEquals(
            listOf(DownloadSource.QOBUZ),
            DownloadSourceConfig.fetchChain("QOBUZ,YOUTUBE_MUSIC,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN"),
        )
    }

    @Test
    fun losslessPlacedAboveYoutubeMusicOverridesIt() {
        assertEquals(
            listOf(DownloadSource.QOBUZ, DownloadSource.TIDAL),
            DownloadSourceConfig.fetchChain("QOBUZ,TIDAL,YOUTUBE_MUSIC,QOBUZ_BACKUP,DEEZER,JIOSAAVN"),
        )
    }

    @Test
    fun blankOrderFallsBackToTheDefaultsWithoutYoutubeMusic() {
        assertEquals(
            listOf(
                DownloadSource.QOBUZ,
                DownloadSource.QOBUZ_BACKUP,
                DownloadSource.TIDAL,
                DownloadSource.DEEZER,
                DownloadSource.JIOSAAVN,
            ),
            DownloadSourceConfig.fetchChain(""),
        )
    }

    @Test
    fun perSongPinReplacesTheOrder() {
        assertEquals(
            listOf(DownloadSource.TIDAL),
            DownloadSourceConfig.resolutionChain(
                "QOBUZ,QOBUZ_BACKUP,DEEZER,JIOSAAVN,YOUTUBE_MUSIC",
                AudioSourceType.TIDAL,
            ),
        )
    }

    @Test
    fun pinToYoutubeLeavesNoLosslessSourceToTry() {
        assertTrue(
            DownloadSourceConfig
                .resolutionChain(
                    "QOBUZ,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN,YOUTUBE_MUSIC",
                    AudioSourceType.YOUTUBE,
                ).isEmpty(),
        )
    }

    @Test
    fun playbackOnlyPinFallsBackToTheOrder() {
        // Apple, Amazon and QQ have no download resolver; pinning one must not pin the download to
        // a source it can never resolve.
        assertEquals(
            DownloadSourceConfig.fetchChain("QOBUZ,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN,YOUTUBE_MUSIC"),
            DownloadSourceConfig.resolutionChain(
                "QOBUZ,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN,YOUTUBE_MUSIC",
                AudioSourceType.APPLE,
            ),
        )
        assertNull(DownloadSourceConfig.fromAudioSource(AudioSourceType.APPLE))
        assertNull(DownloadSourceConfig.fromAudioSource(AudioSourceType.AMAZON))
        assertNull(DownloadSourceConfig.fromAudioSource(AudioSourceType.QQ))
    }
}
