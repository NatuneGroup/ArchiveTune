/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLibraryViewModelTest {
    @Test
    fun failedRefreshPreservesCachedItems() = runTest {
        val state = MutableStateFlow(SpotifyLibrarySectionState(items = listOf("cached")))

        loadSpotifySection(state, force = true) {
            error("429 Too Many Requests")
        }

        assertEquals(listOf("cached"), state.value.items)
        assertEquals("429 Too Many Requests", state.value.errorMessage)
    }
}
