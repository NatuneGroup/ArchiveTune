/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.playlist

import moe.rukamori.archivetune.playlist.CrossServicePlaylistImporter.ImportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL-parsing contract for the cross-service playlist importer. These are the
 * pure parts of the importer — no network — so they pin the routing and id
 * extraction that every import depends on.
 */
class CrossServicePlaylistImporterTest {

    // ─── Source detection ─────────────────────────────────────────────────

    @Test
    fun detectsSpotifyWebAndUriForms() {
        assertEquals(
            ImportSource.SPOTIFY,
            CrossServicePlaylistImporter.detectSource(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            ),
        )
        assertEquals(
            ImportSource.SPOTIFY,
            CrossServicePlaylistImporter.detectSource("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"),
        )
    }

    @Test
    fun detectsQobuzAndOtherServices() {
        assertEquals(
            ImportSource.QOBUZ,
            CrossServicePlaylistImporter.detectSource("https://open.qobuz.com/playlist/5966313"),
        )
        assertEquals(
            ImportSource.TIDAL,
            CrossServicePlaylistImporter.detectSource(
                "https://tidal.com/browse/playlist/1c5d01ed-4f05-40c4-bd28-0f73099e8186",
            ),
        )
        assertEquals(
            ImportSource.DEEZER,
            CrossServicePlaylistImporter.detectSource("https://www.deezer.com/us/playlist/908622995"),
        )
        assertEquals(
            ImportSource.APPLE_MUSIC,
            CrossServicePlaylistImporter.detectSource(
                "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb",
            ),
        )
        assertEquals(
            ImportSource.YOUTUBE_MUSIC,
            CrossServicePlaylistImporter.detectSource("https://music.youtube.com/playlist?list=RDCLAK5uy_l"),
        )
    }

    @Test
    fun unknownUrlIsRejectedRatherThanGuessed() {
        assertEquals(
            ImportSource.UNKNOWN,
            CrossServicePlaylistImporter.detectSource("https://example.com/playlist/123"),
        )
    }

    /**
     * Spotify is checked before the generic hosts, but YouTube must still win
     * for its own URLs — a regression here would send YT playlists down the
     * scraping path.
     */
    @Test
    fun youtubeTakesPrecedenceOverGenericMatching() {
        assertEquals(
            ImportSource.YOUTUBE_MUSIC,
            CrossServicePlaylistImporter.detectSource("https://www.youtube.com/playlist?list=PLabc123"),
        )
    }

    // ─── Spotify id extraction ────────────────────────────────────────────

    @Test
    fun extractsSpotifyIdFromWebUrlWithQueryParams() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            CrossServicePlaylistImporter.extractSpotifyPlaylistId(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123&pt=xyz",
            ),
        )
    }

    @Test
    fun extractsSpotifyIdFromUriAndEmbedForms() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            CrossServicePlaylistImporter.extractSpotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"),
        )
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            CrossServicePlaylistImporter.extractSpotifyPlaylistId(
                "https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M",
            ),
        )
    }

    @Test
    fun rejectsSpotifyUrlWithoutPlaylistId() {
        assertNull(CrossServicePlaylistImporter.extractSpotifyPlaylistId("https://open.spotify.com/browse"))
    }

    // ─── Qobuz id extraction ──────────────────────────────────────────────

    @Test
    fun extractsQobuzIdFromShortAndSluggedUrls() {
        assertEquals(
            "5966313",
            CrossServicePlaylistImporter.extractQobuzPlaylistId("https://open.qobuz.com/playlist/5966313"),
        )
        assertEquals(
            "5966313",
            CrossServicePlaylistImporter.extractQobuzPlaylistId(
                "https://www.qobuz.com/us-en/playlists/gems-of-the-week/5966313",
            ),
        )
    }

    @Test
    fun rejectsQobuzUrlWithoutNumericId() {
        assertNull(CrossServicePlaylistImporter.extractQobuzPlaylistId("https://www.qobuz.com/us-en/playlists"))
    }

    // ─── Script-block extraction ──────────────────────────────────────────

    @Test
    fun extractsJsonFromScriptBlockAcrossNewlines() {
        val html =
            """
            <html><body>
            <script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"state":{"data":{"entity":{"name":"Mix"}}}}}}
            </script>
            </body></html>
            """.trimIndent()

        val json = CrossServicePlaylistImporter.extractScriptJson(html, "__NEXT_DATA__")

        assertEquals(
            "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{\"name\":\"Mix\"}}}}}}",
            json,
        )
    }

    @Test
    fun missingScriptBlockReturnsNull() {
        assertNull(
            CrossServicePlaylistImporter.extractScriptJson("<html><body>no data</body></html>", "__NEXT_DATA__"),
        )
    }
}
