/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.scrobbling

import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastFmTrackMetadataTest {
    private fun metadata(
        title: String,
        vararg artists: String,
        album: String? = null,
        duration: Int = 200,
    ) = MediaMetadata(
        id = "id",
        title = title,
        artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = duration,
        album = album?.let { MediaMetadata.Album(id = "album", title = it) },
    )

    @Test
    fun `sends only the primary artist`() {
        val track = LastFmTrackMetadata.from(metadata("Song", "First", "Second"), 200)!!
        assertEquals("First", track.artist)
    }

    @Test
    fun `strips topic channel suffix from artist`() {
        val track = LastFmTrackMetadata.from(metadata("Song", "Artist - Topic"), 200)!!
        assertEquals("Artist", track.artist)
    }

    @Test
    fun `strips upload labels but keeps recording qualifiers`() {
        assertEquals(
            "Song",
            LastFmTrackMetadata.from(metadata("Song (Official Music Video)", "A"), 200)!!.track,
        )
        assertEquals(
            "Song",
            LastFmTrackMetadata.from(metadata("Song [Lyric Video]", "A"), 200)!!.track,
        )
        assertEquals(
            "Song (Live) (feat. B)",
            LastFmTrackMetadata.from(metadata("Song (Live) (feat. B)", "A"), 200)!!.track,
        )
        assertEquals(
            "Song (Remix)",
            LastFmTrackMetadata.from(metadata("Song (Remix) (Official Audio)", "A"), 200)!!.track,
        )
    }

    @Test
    fun `keeps a title that is only a label`() {
        assertEquals("(Audio)", LastFmTrackMetadata.from(metadata("(Audio)", "A"), 200)!!.track)
    }

    @Test
    fun `rejects plays without an artist and drops unknown durations`() {
        assertNull(LastFmTrackMetadata.from(metadata("Song"), 200))
        assertNull(LastFmTrackMetadata.from(metadata("Song", "A"), -1)!!.durationSeconds)
        assertNull(LastFmTrackMetadata.from(metadata("Song", "A", album = " "), 200)!!.album)
    }
}
