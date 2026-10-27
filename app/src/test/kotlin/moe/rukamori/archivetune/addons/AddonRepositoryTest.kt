/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.rukamori.archivetune.db.entities.isLossless
import moe.rukamori.archivetune.ui.player.qualityBadgeText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AddonRepositoryTest {
    @Test
    fun parseManifestReadsDeclaredEndpoints() {
        val manifest =
            parseAddonManifest(
                """{"id":"demo","name":"Demo","searchEndpoint":"/search","streamEndpoint":"/stream/{id}"}""",
            )

        assertEquals("demo", manifest.id)
        assertEquals("/search", manifest.searchEndpoint)
        assertEquals("/stream/{id}", manifest.streamEndpoint)
    }

    @Test
    fun parseBitChordManifestReadsResourcesAndDefaultSettings() {
        val manifest =
            parseAddonManifest(
                """{"id":"com.example.addon","name":"Example","resources":["search","stream"],"settings":[{"key":"region","default":"US"},{"key":"quality","default":"normal","options":[{"value":"lossless"},{"value":"high"}]}]}""",
            )

        assertEquals("com.example.addon", manifest.id)
        assertEquals("search", manifest.resources.first())
        assertEquals("US", (manifest.settings.first().defaultValue as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("lossless", (manifest.settings.last().options.first().value as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun bitChordSearchResponseReadsTracksArtworkDurationAndDirectStream() {
        val response =
            Json.decodeFromString<AddonSearchResponse>(
                """{"tracks":[{"id":"tidal:42","title":"Track","artist":"Artist","duration":212,"artworkURL":"https://cdn.example.test/cover.jpg","streamURL":"https://cdn.example.test/track.flac","format":"flac","audioQuality":"LOSSLESS"}]}""",
            )
        val track = response.tracks.single()

        assertEquals("Track", track.title)
        assertEquals("https://cdn.example.test/cover.jpg", track.displayArtworkUrl)
        assertEquals(212_000L, track.displayDurationMs)
        assertEquals("https://cdn.example.test/track.flac", track.streamURL)
    }

    @Test
    fun bitChordTrackDurationAcceptsFractionalSeconds() {
        val response =
            Json.decodeFromString<AddonSearchResponse>(
                """{"tracks":[{"id":"track","title":"Track","artist":"Artist","duration":212.5}]}""",
            )

        assertEquals(212_500L, response.tracks.single().displayDurationMs)
    }

    @Test
    fun parseManifestRejectsStreamEndpointWithoutTrackPlaceholder() {
        assertThrows(IllegalArgumentException::class.java) {
            parseAddonManifest(
                """{"id":"demo","name":"Demo","searchEndpoint":"/search","streamEndpoint":"/stream"}""",
            )
        }
    }

    @Test
    fun flacResponseMapsToLosslessHighResolutionFormat() {
        val format =
            AddonStreamResponse(
                url = "https://cdn.example.test/track.flac",
                codecs = "FLAC",
                bitrate = 2_304_000,
                sampleRate = 96_000,
                contentLength = 42_000_000,
            ).toFormatEntity("addon:demo:track")

        assertEquals("audio/flac", format.mimeType)
        assertEquals("FLAC", format.codecs)
        assertEquals(2_304_000, format.bitrate)
        assertEquals(96_000, format.sampleRate)
        assertTrue(format.isLossless())
        assertEquals("Hi-Res Lossless", format.qualityBadgeText())
    }

    @Test
    fun mp3ResponsePreservesBitrateForHighQualityBadge() {
        val format =
            AddonStreamResponse(
                url = "https://cdn.example.test/track",
                codecs = "MP3",
                bitrate = 320_000,
            ).toFormatEntity("addon:demo:track")

        assertEquals("audio/mpeg", format.mimeType)
        assertEquals("MP3", format.codecs)
        assertEquals(320_000, format.bitrate)
        assertEquals("Hi-Quality", format.qualityBadgeText())
    }

    @Test
    fun bitChordQualityTextMapsToFlacHiResBadge() {
        val format =
            AddonStreamResponse(
                url = "https://cdn.example.test/track",
                format = "flac",
                quality = "Tidal · Hi-Res FLAC 24-bit / 96 kHz",
            ).toFormatEntity("addon:demo:track")

        assertEquals("FLAC", format.codecs)
        assertEquals(96_000, format.sampleRate)
        assertEquals("Hi-Res Lossless", format.qualityBadgeText())
    }

    @Test
    fun bitChordMp3QualityTextMapsToHighQualityBadge() {
        val format =
            AddonStreamResponse(
                url = "https://cdn.example.test/track",
                format = "mp3",
                quality = "320kbps",
            ).toFormatEntity("addon:demo:track")

        assertEquals("MP3", format.codecs)
        assertEquals(320_000, format.bitrate)
        assertEquals("Hi-Quality", format.qualityBadgeText())
    }

    @Test
    fun bitChordDashTransportIsNotReplacedByTheAudioCodecMimeType() {
        val stream =
            AddonStreamResponse(
                url = "https://cdn.example.test/dash/track",
                format = "flac",
                manifest = "dash",
            )

        assertEquals("audio/flac", stream.toFormatEntity("addon:demo:track").mimeType)
        assertEquals("application/dash+xml", stream.playbackMimeType("audio/flac"))
    }

    @Test
    fun bitChordStreamAcceptsDoubleFieldsAndAlternateDashAndEncryptedFields() {
        val stream =
            Json.decodeFromString<AddonStreamResponse>(
                """{"url":"https://cdn.example.test/dash/track","format":"dash","codec":"flac","bitrate":2304000.0,"sampleRate":96000.0,"bitDepth":24.0,"encrypted":"widevine"}""",
            )

        assertEquals(2_304_000, stream.toFormatEntity("addon:demo:track").bitrate)
        assertEquals(96_000, stream.toFormatEntity("addon:demo:track").sampleRate)
        assertEquals("application/dash+xml", stream.playbackMimeType("audio/flac"))
        assertTrue(stream.isEncrypted())
    }
}