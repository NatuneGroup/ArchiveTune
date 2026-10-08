/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.constants

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the rule every Library screen now reads its source through.
 *
 * The Library hands its sections one resolved source, so this decides what all of them read at
 * once. Spotify resolves to YTM while no session is signed in and switched on, which is what keeps
 * the mixes, playlists and artists rails off an account that is not there — the empty states they
 * fall back to only mean something if the source they were handed can be read at all. YTM is never
 * affected: it has no session to be missing.
 */
class LibrarySourceTest {
    @Test
    fun unavailableSpotifyReportsYtm() {
        assertEquals(LibrarySource.YTM, LibrarySource.SPOTIFY.resolved(spotifyAvailable = false))
    }

    @Test
    fun availableSpotifyIsHonoured() {
        assertEquals(LibrarySource.SPOTIFY, LibrarySource.SPOTIFY.resolved(spotifyAvailable = true))
    }

    @Test
    fun ytmIsUnaffected() {
        assertEquals(LibrarySource.YTM, LibrarySource.YTM.resolved(spotifyAvailable = false))
    }
}
