/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.search

import moe.rukamori.archivetune.constants.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchNavigationTest {
    @Test
    fun spotifyProviderIsEncodedAlongsideQuery() {
        val route = onlineSearchResultRoute("Beyoncé / live", SearchProvider.SPOTIFY)

        assertTrue(route.endsWith("?provider=SPOTIFY"))
        assertEquals("Beyoncé / live", decodeOnlineSearchQuery(route.substringAfter("search/").substringBefore("?")))
    }

    @Test
    fun youtubeRemainsTheDefaultForExistingCallers() {
        assertTrue(onlineSearchResultRoute("query").endsWith("?provider=YOUTUBE"))
    }
}
