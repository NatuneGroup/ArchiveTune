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
import org.junit.Assert.assertFalse
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
        // Apple, Amazon, QQ and Spotify have no download resolver; pinning one must not pin the download to
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
        assertNull(DownloadSourceConfig.fromAudioSource(AudioSourceType.SPOTIFY))
    }

    @Test
    fun disabledPlaybackSourceIsSkippedForDownloads() {
        // Downloads keep their own order but skip sources the user turned off for playback
        // (user decision): Qobuz disabled removes it — and a Qobuz pin to it — from the chain.
        val enabled = setOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(DownloadSource.TIDAL),
            DownloadSourceConfig.resolutionChain(
                "QOBUZ,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN,YOUTUBE_MUSIC",
                pinnedSource = null,
                playbackEnabled = enabled,
            ),
        )
        assertEquals(
            listOf(DownloadSource.TIDAL),
            DownloadSourceConfig.resolutionChain(
                "QOBUZ,QOBUZ_BACKUP,TIDAL,DEEZER,JIOSAAVN,YOUTUBE_MUSIC",
                pinnedSource = AudioSourceType.QOBUZ,
                playbackEnabled = enabled,
            ),
        )
        // An enabled pin still replaces the order outright.
        assertEquals(
            listOf(DownloadSource.TIDAL),
            DownloadSourceConfig.resolutionChain(
                "DEEZER,JIOSAAVN,YOUTUBE_MUSIC",
                pinnedSource = AudioSourceType.TIDAL,
                playbackEnabled = enabled,
            ),
        )
        // An enabled YouTube pin still empties the chain so the caller uses the YouTube resolver;
        // with YouTube's playback toggle off the pin yields to the gated order instead.
        assertEquals(
            emptyList<DownloadSource>(),
            DownloadSourceConfig.resolutionChain(
                "TIDAL,YOUTUBE_MUSIC",
                pinnedSource = AudioSourceType.YOUTUBE,
                playbackEnabled = enabled,
            ),
        )
        assertEquals(
            listOf(DownloadSource.TIDAL),
            DownloadSourceConfig.resolutionChain(
                "TIDAL,YOUTUBE_MUSIC",
                pinnedSource = AudioSourceType.YOUTUBE,
                playbackEnabled = setOf(AudioSourceType.TIDAL),
            ),
        )
    }

    @Test
    fun downloadCacheKeysFollowTheGatedChainThenYouTube() {
        // Regression: a complete qobuz:<id> cache entry used to be copied into the download after
        // Qobuz was switched off, and cache hits ignored the download order.
        val enabled = setOf(AudioSourceType.TIDAL, AudioSourceType.DEEZER, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf("deezer:id1", "tidal:id1", "id1"),
            DownloadSourceConfig.downloadCacheKeys("DEEZER,QOBUZ,TIDAL,YOUTUBE_MUSIC,JIOSAAVN", null, enabled, "id1"),
        )
    }

    @Test
    fun downloadCacheKeysBelowYoutubeMusicAreNotReused() {
        val enabled = setOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf("id1"),
            DownloadSourceConfig.downloadCacheKeys("YOUTUBE_MUSIC,TIDAL", null, enabled, "id1"),
        )
    }

    @Test
    fun disabledYouTubeBlocksTheTerminalFallbackAndItsCacheKey() {
        val enabled = setOf(AudioSourceType.TIDAL)

        assertFalse(DownloadSourceConfig.youTubeFallbackAllowed(enabled))
        assertTrue(DownloadSourceConfig.youTubeFallbackAllowed(enabled + AudioSourceType.YOUTUBE))
        assertTrue(DownloadSourceConfig.youTubeFallbackAllowed(null))
        assertEquals(
            listOf("tidal:id1"),
            DownloadSourceConfig.downloadCacheKeys("TIDAL,YOUTUBE_MUSIC", null, enabled, "id1"),
        )
        assertTrue(DownloadSourceConfig.downloadCacheKeys("TIDAL,YOUTUBE_MUSIC", null, emptySet(), "id1").isEmpty())
    }

    @Test
    fun downloadCacheKeysHonourAnEnabledPin() {
        val enabled = setOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf("tidal:id1", "id1"),
            DownloadSourceConfig.downloadCacheKeys("QOBUZ,TIDAL,YOUTUBE_MUSIC", AudioSourceType.TIDAL, enabled, "id1"),
        )
        assertEquals(
            listOf("id1"),
            DownloadSourceConfig.downloadCacheKeys("QOBUZ,TIDAL,YOUTUBE_MUSIC", AudioSourceType.YOUTUBE, enabled, "id1"),
        )
    }
}
