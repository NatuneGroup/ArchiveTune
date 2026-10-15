/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import moe.rukamori.archivetune.constants.HomeSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSourceSwitcherTest {
    @Test
    fun youtubeAndSpotifySwitchInBothDirections() {
        val active = listOf(HomeSource.YOUTUBE, HomeSource.SPOTIFY)
        assertEquals(HomeSource.SPOTIFY, homeSwitchTarget(HomeSource.YOUTUBE, active))
        assertEquals(HomeSource.YOUTUBE, homeSwitchTarget(HomeSource.SPOTIFY, active))
    }

    @Test
    fun unavailableStoredSourceSwitchesAwayFromTheDisplayedFallback() {
        assertEquals(
            HomeSource.SPOTIFY,
            homeSwitchTarget(HomeSource.QQ, listOf(HomeSource.YOUTUBE, HomeSource.SPOTIFY)),
        )
    }

    @Test
    fun youtubeOnlyStillOffersSpotifySetup() {
        assertEquals(HomeSource.SPOTIFY, homeSwitchTarget(HomeSource.YOUTUBE, listOf(HomeSource.YOUTUBE)))
        assertEquals(HomeSource.SPOTIFY, homeSwitchTarget(HomeSource.YOUTUBE, emptyList()))
    }

    @Test
    fun spotifyOnlyOffersYoutube() {
        assertEquals(HomeSource.YOUTUBE, homeSwitchTarget(HomeSource.SPOTIFY, listOf(HomeSource.SPOTIFY)))
    }
}
