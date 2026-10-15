/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlayContextsTest {
    private val body =
        """
        {"playContexts":[
          {"uri":"spotify:album:bbbbbbbbbbbbbbbbbbbbbb","lastPlayedTime":1789394596537,"lastPlayedTrackUri":"spotify:track:t2"},
          {"uri":"spotify:playlist:aaaaaaaaaaaaaaaaaaaaaa","lastPlayedTime":1791142987985,"lastPlayedTrackUri":"spotify:track:t1"},
          {"uri":"spotify:show:cccccccccccccccccccccc","lastPlayedTime":1791000000000,"lastPlayedTrackUri":"spotify:episode:e1"},
          {"uri":"spotify:artist:dddddddddddddddddddddd","lastPlayedTime":1791100000000},
          {"uri":"spotify:playlist:eeeeeeeeeeeeeeeeeeeeee","lastPlayedTime":0,"lastPlayedTrackUri":"spotify:track:t3"},
          "not an object"
        ]}
        """.trimIndent()

    @Test
    fun playsComeBackNewestFirstAndRowsWithoutATrackAreDropped() {
        val plays = parseRecentPlayContexts(body)

        assertEquals(listOf("spotify:track:t1", "spotify:track:t2"), plays.map { it.trackUri })
        assertEquals(1_791_142_987_985L, plays.first().playedAtMillis)
    }

    @Test
    fun aBodyThatIsNotAHistoryYieldsNoPlaysRatherThanAnError() {
        assertTrue(parseRecentPlayContexts("not json").isEmpty())
        assertTrue(parseRecentPlayContexts("{}").isEmpty())
        assertTrue(parseRecentPlayContexts("""{"playContexts":{}}""").isEmpty())
    }

    @Test
    fun theStampIsIsoUtcAndParsesBackToTheSameCursor() {
        val history =
            parseRecentPlayContexts(body).toPlayHistory(
                mapOf("spotify:track:t1" to SpotifyTrack(id = "t1", uri = "spotify:track:t1")),
            )

        assertEquals(1, history.size)
        assertEquals("2026-10-04T19:43:07.985Z", history.single().playedAt)
        assertEquals(1_791_142_987_985L, history.newestPlayedAtMillis())
    }

    @Test
    fun aPlayWhoseTrackCouldNotBeResolvedIsLeftOut() {
        val history = parseRecentPlayContexts(body).toPlayHistory(emptyMap())

        assertTrue(history.isEmpty())
    }

    @Test
    fun theLookupKeepsTracksAndSkipsEverythingElse() {
        val response =
            Json
                .parseToJsonElement(
                    """
                    {"data":{"lookup":[
                      {"__typename":"TrackResponseWrapper","_uri":"spotify:track:t1","data":{
                        "__typename":"Track",
                        "albumOfTrack":{"coverArt":{"sources":[{"height":300,"url":"https://i.scdn.co/image/x","width":300}]},"name":"An Album","uri":"spotify:album:alb"},
                        "artists":{"items":[{"profile":{"name":"An Artist"},"uri":"spotify:artist:art"}]},
                        "contentRating":{"label":"EXPLICIT"},
                        "name":"A Song","uri":"spotify:track:t1"}},
                      {"__typename":"TrackResponseWrapper","_uri":"spotify:track:t2","data":{"__typename":"NotFound"}},
                      {"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:p","data":{"__typename":"Playlist"}}
                    ]}}
                    """.trimIndent(),
                ).jsonObject

        val tracks = Spotify.parseRecentlyPlayedLookup(response)

        assertEquals(setOf("spotify:track:t1"), tracks.keys)
        val track = tracks.getValue("spotify:track:t1")
        assertEquals("t1", track.id)
        assertEquals("A Song", track.name)
        assertEquals("An Artist", track.artists.single().name)
        assertEquals("An Album", track.album?.name)
        assertEquals("https://i.scdn.co/image/x", track.album?.images?.single()?.url)
        assertTrue(track.explicit)
    }

    @Test
    fun anEmptyOrMalformedLookupResolvesNothing() {
        assertTrue(Spotify.parseRecentlyPlayedLookup(Json.parseToJsonElement("{}").jsonObject).isEmpty())
        assertTrue(
            Spotify.parseRecentlyPlayedLookup(Json.parseToJsonElement("""{"data":{"lookup":[1,"x"]}}""").jsonObject).isEmpty(),
        )
    }
}
