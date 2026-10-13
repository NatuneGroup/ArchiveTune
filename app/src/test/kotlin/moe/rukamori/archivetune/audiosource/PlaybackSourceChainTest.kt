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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one shared source chain: order and toggles come from a single snapshot, and every
 * playback path (normal play, prefetch, crossfade/automix, Cast, pins, the cache shortcut)
 * derives the same list from it.
 */
class PlaybackSourceChainTest {
    private fun snapshot(
        rawOrder: String?,
        vararg enabled: AudioSourceType,
    ) = PlaybackSourceSnapshot(
        order = AudioSourceConfig.parseOrder(rawOrder),
        enabled = enabled.toSet(),
    )

    @Test
    fun baseChainFollowsTheUserOrderAndTheToggles() {
        val prefs = snapshot("SPOTIFY,TIDAL,YOUTUBE,QOBUZ", AudioSourceType.SPOTIFY, AudioSourceType.TIDAL, AudioSourceType.QOBUZ)

        assertEquals(listOf(AudioSourceType.SPOTIFY, AudioSourceType.TIDAL), PlaybackSourceChain.baseChain(prefs))
    }

    @Test
    fun disabledSourcesAreSkippedInTheUserOrder() {
        val prefs = snapshot("SPOTIFY,TIDAL,YOUTUBE,QOBUZ", AudioSourceType.TIDAL)

        assertEquals(listOf(AudioSourceType.TIDAL), PlaybackSourceChain.baseChain(prefs))
    }

    @Test
    fun chainIsCutAtYouTubeWhereverTheUserPutIt() {
        val youtubeFirst = snapshot("YOUTUBE,TIDAL,QOBUZ", AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertTrue(PlaybackSourceChain.baseChain(youtubeFirst).isEmpty())
    }

    @Test
    fun spotifyCountsAsEnabledOnlyWhenSignedIn() {
        val flags =
            AudioSourceType.entries.associateWith { it == AudioSourceType.SPOTIFY } +
                mapOf(AudioSourceType.TIDAL to true, AudioSourceType.YOUTUBE to true)

        val signedOut = PlaybackSourceChain.enabledSet(flags, spotifySignedIn = false)
        assertFalse(AudioSourceType.SPOTIFY in signedOut)
        assertTrue(AudioSourceType.TIDAL in signedOut)

        val signedIn = PlaybackSourceChain.enabledSet(flags, spotifySignedIn = true)
        assertTrue(AudioSourceType.SPOTIFY in signedIn)
    }

    @Test
    fun enabledPinIsTheWholeConfiguredChain() {
        val prefs = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.QOBUZ),
            PlaybackSourceChain.configuredChain(prefs, override = AudioSourceType.QOBUZ, rejected = emptySet()),
        )
    }

    @Test
    fun disabledPinFallsThroughToTheBaseChain() {
        val prefs = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.TIDAL),
            PlaybackSourceChain.configuredChain(prefs, override = AudioSourceType.QOBUZ, rejected = emptySet()),
        )
    }

    @Test
    fun enabledYouTubePinEmptiesTheChain() {
        val prefs = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertTrue(PlaybackSourceChain.configuredChain(prefs, override = AudioSourceType.YOUTUBE, rejected = emptySet()).isEmpty())
    }

    @Test
    fun disabledYouTubePinFallsThroughToTheBaseChain() {
        val prefs = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.TIDAL)

        assertEquals(
            listOf(AudioSourceType.TIDAL),
            PlaybackSourceChain.configuredChain(prefs, override = AudioSourceType.YOUTUBE, rejected = emptySet()),
        )
    }

    @Test
    fun rejectedYouTubePinFallsThroughToTheBaseChain() {
        val prefs = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.TIDAL),
            PlaybackSourceChain.configuredChain(prefs, override = AudioSourceType.YOUTUBE, rejected = setOf(AudioSourceType.YOUTUBE)),
        )
    }

    @Test
    fun directQobuzPickWithQobuzDisabledGivesTheBaseChain() {
        // Regression (D4): an exact Qobuz pick used to bypass the toggle and keep playing Qobuz
        // after the user turned the source off.
        val disabled = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.TIDAL),
            PlaybackSourceChain.configuredChain(disabled, override = AudioSourceType.QOBUZ, rejected = emptySet()),
        )

        val enabled = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.QOBUZ),
            PlaybackSourceChain.configuredChain(enabled, override = AudioSourceType.QOBUZ, rejected = emptySet()),
        )
    }

    @Test
    fun directQobuzBackupPickWithQobuzBackupDisabledGivesTheBaseChain() {
        val prefs = snapshot("TIDAL,QOBUZ_BACKUP,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.TIDAL),
            PlaybackSourceChain.configuredChain(prefs, override = AudioSourceType.QOBUZ_BACKUP, rejected = emptySet()),
        )
    }

    @Test
    fun noPinGivesTheBaseChain() {
        val prefs = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.QOBUZ),
            PlaybackSourceChain.configuredChain(prefs, override = null, rejected = emptySet()),
        )
    }

    @Test
    fun cacheKeyOrderFollowsTheUserOrderAndSkipsDisabledSources() {
        val prefs = snapshot("TIDAL,QOBUZ,DEEZER,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf("tidal:id1", "qobuz:id1", "id1"),
            PlaybackSourceChain.cacheKeyOrder(prefs, "id1"),
        )
    }

    @Test
    fun disabledDeezerAndYouTubeLeaveOutTheirCacheKeys() {
        val prefs = snapshot("DEEZER,TIDAL,YOUTUBE", AudioSourceType.TIDAL)

        assertEquals(listOf("tidal:id1"), PlaybackSourceChain.cacheKeyOrder(prefs, "id1"))
    }

    @Test
    fun cacheProbeKeysMatchTheDownloadPrefixes() {
        // Drift guard: downloads write under DownloadSourceConfig's prefixes; the playback cache
        // probe must look under exactly the same names for the same source.
        val mediaId = "abc123"
        assertEquals("tidal:$mediaId", PlaybackSourceChain.cacheProbeKey(AudioSourceType.TIDAL, mediaId))
        assertEquals("qobuz:$mediaId", PlaybackSourceChain.cacheProbeKey(AudioSourceType.QOBUZ, mediaId))
        assertEquals("qobuz_backup:$mediaId", PlaybackSourceChain.cacheProbeKey(AudioSourceType.QOBUZ_BACKUP, mediaId))
        assertEquals("deezer:$mediaId", PlaybackSourceChain.cacheProbeKey(AudioSourceType.DEEZER, mediaId))
        assertEquals("jiosaavn:$mediaId", PlaybackSourceChain.cacheProbeKey(AudioSourceType.JIOSAAVN, mediaId))
        assertEquals(mediaId, PlaybackSourceChain.cacheProbeKey(AudioSourceType.YOUTUBE, mediaId))

        DownloadSourceConfig.CACHE_KEY_PREFIXES.forEach { prefix ->
            val source =
                requireNotNull(
                    AudioSourceType.entries.firstOrNull { PlaybackSourceChain.cacheProbeKey(it, mediaId) == "$prefix$mediaId" },
                ) { "no playback cache probe key matches the download prefix $prefix" }
            val downloadSource = requireNotNull(DownloadSourceConfig.fromAudioSource(source))
            assertEquals("$prefix$mediaId", "${downloadSource.name.lowercase()}:$mediaId")
        }
    }

    @Test
    fun cacheShortCircuitIsAllowedOnlyWhenNoSourcePrecedesYouTube() {
        val onlyYouTube = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.YOUTUBE)

        assertTrue(PlaybackSourceChain.cacheShortCircuitAllowed(onlyYouTube))

        // Regression (D7): JioSaavn resolves real streams and must bypass the ephemeral
        // YouTube player cache like the lossless sources do.
        val onlyJioSaavn = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.JIOSAAVN, AudioSourceType.YOUTUBE)

        assertFalse(PlaybackSourceChain.cacheShortCircuitAllowed(onlyJioSaavn))

        val tidalEnabled = snapshot("TIDAL,QOBUZ,YOUTUBE", AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertFalse(PlaybackSourceChain.cacheShortCircuitAllowed(tidalEnabled))
    }
}
