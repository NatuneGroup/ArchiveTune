/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMetadataPolicyTest {
    private val reference =
        DirectStream(
            uri = "https://example.invalid/audio.flac",
            mimeType = "audio/flac",
            codecs = "flac",
            contentLength = null,
            label = "Qobuz HI_RES",
            source = AudioSourceType.QOBUZ,
            matchedTitle = "Known recording",
            matchedArtist = "Known artist",
            matchedAlbum = "Known album",
            matchedDurationMs = 200_000,
        )

    private fun evaluate(
        stream: DirectStream = reference,
        wantedDuration: Long? = 200_000,
        wantedTitle: String = "Known recording",
        wantedArtists: List<String> = listOf("Known artist"),
        wantedAlbum: String? = "Known album",
    ): TitleMatch.Result =
        TitleMatch.evaluate(wantedTitle, wantedArtists, wantedAlbum, wantedDuration, stream)

    @Test
    fun pcmBitrateUsesReportedMetadataRatherThanTheQualityLabel() {
        val cd = reference.copy(sampleRate = 44_100, bitDepth = 16)
        assertEquals(1_411_200, cd.pcmBitrateOrNull())
        assertEquals(705_600, cd.pcmBitrateOrNull(channels = 1))
        assertEquals(4_233_600, cd.pcmBitrateOrNull(channels = 6))
    }

    @Test
    fun unknownOrNonPositivePcmMetadataRemainsUnknown() {
        assertNull(reference.pcmBitrateOrNull())
        listOf(null, 0, -1).forEach { invalid ->
            assertNull(reference.copy(sampleRate = invalid, bitDepth = 24).pcmBitrateOrNull())
            assertNull(reference.copy(sampleRate = 48_000, bitDepth = invalid).pcmBitrateOrNull())
        }
    }

    @Test
    fun authoritativeCatalogIdsDoNotRequireGuessedMatchingMetadata() {
        val result =
            evaluate(
                reference.copy(trustedDirectId = true, matchedTitle = null, matchedArtist = null),
            )
        assertTrue(result.accepted)
        assertEquals("trusted catalog id", result.reason)
    }

    @Test
    fun durationConfidenceChangesAtDocumentedBoundariesButNotBeforeTheHardGate() {
        listOf(3_000L to 1.0, 3_001L to 0.8, 10_000L to 0.8, 10_001L to 0.6, 15_000L to 0.6)
            .forEach { (difference, expected) ->
                val result = evaluate(reference.copy(matchedDurationMs = 200_000 + difference))
                assertTrue(result.accepted)
                assertEquals(expected, result.duration!!, 0.000001)
            }
        assertFalse(evaluate(reference.copy(matchedDurationMs = 215_001)).accepted)
    }

    @Test
    fun unknownOrNonPositiveDurationsAreNotInvented() {
        listOf<Long?>(null, 0, -1).forEach { invalid ->
            val missingCandidate = evaluate(reference.copy(matchedDurationMs = invalid))
            val missingWanted = evaluate(wantedDuration = invalid)
            assertNull(missingCandidate.duration)
            assertNull(missingWanted.duration)
            assertTrue(missingCandidate.accepted)
            assertTrue(missingWanted.accepted)
        }
    }

    @Test
    fun missingArtistEvidenceUsesTheStrictTitleFallback() {
        listOf<String?>(null, "   ").forEach { artist ->
            val result = evaluate(reference.copy(matchedArtist = artist))
            assertTrue(result.accepted)
            assertNull(result.artist)
            assertEquals("strict title fallback", result.reason)
        }
        val missingWanted = evaluate(wantedArtists = emptyList())
        assertTrue(missingWanted.accepted)
        assertNull(missingWanted.artist)
        val differentTitle =
            evaluate(reference.copy(matchedTitle = "Unrelated", matchedArtist = null))
        assertFalse(differentTitle.accepted)
        assertEquals("artist metadata unavailable", differentTitle.reason)
    }

    @Test
    fun unusableArtistTokensAreRejectedRatherThanTreatedAsAMatch() {
        assertEquals("artist mismatch", evaluate(reference.copy(matchedArtist = "///")).reason)
        assertEquals("artist mismatch", evaluate(wantedArtists = listOf("///")).reason)
    }

    @Test
    fun topicArtistSuffixesAndFeaturedArtistListsDoNotHideTheMatchingArtist() {
        val result =
            evaluate(reference.copy(matchedArtist = "Another artist; Known artist - Topic"))
        assertTrue(result.accepted)
        assertEquals(1.0, result.artist!!, 0.000001)
    }

    @Test
    fun unrelatedTitlesFailEvenWhenOtherMetadataMatches() {
        val result = evaluate(reference.copy(matchedTitle = "zzzzzzzzzz"))
        assertFalse(result.accepted)
        assertEquals("title mismatch", result.reason)
    }

    @Test
    fun aShortSharedPrefixCannotOverrideAWeakCompositeScore() {
        val result =
            evaluate(
                reference.copy(
                    matchedTitle = "abc" + "z".repeat(200),
                    matchedDurationMs = null,
                    matchedAlbum = null,
                ),
                wantedTitle = "abc",
                wantedAlbum = null,
            )
        assertFalse(result.accepted)
        assertEquals("composite score too low", result.reason)
    }

    @Test
    fun aTwoCharacterSubstringIsNotEnoughToBypassTheTitleGate() {
        val result =
            evaluate(reference.copy(matchedTitle = "ab" + "z".repeat(200)), wantedTitle = "ab")
        assertFalse(result.accepted)
        assertEquals("title mismatch", result.reason)
    }

    @Test
    fun requestedLiveVersionsCannotBeReplacedByStudioRecordings() {
        val result = evaluate(wantedTitle = "Known recording (Live)")
        assertFalse(result.accepted)
        assertEquals("version mismatch", result.reason)
    }

    @Test
    fun multilingualTitleSegmentsMatchInBothDirectionsWithoutErasingTheOriginal() {
        assertEquals(1.0, TitleMatch.ratio("夜に駆ける - Into The Night", "Into The Night"), 0.000001)
        assertEquals(1.0, TitleMatch.ratio("Into The Night", "夜に駆ける - Into The Night"), 0.000001)
        assertEquals("deja vu", TitleMatch.normalize("Déjà Vu (feat. Guest)"))
    }

    @Test
    fun similarityHandlesMissingTinyAndTransposedTitlesConservatively() {
        assertEquals(0.0, TitleMatch.ratio(null, ""), 0.000001)
        assertEquals(0.0, TitleMatch.ratio("a", "b"), 0.000001)
        assertEquals(0.0, TitleMatch.ratio("aaaa", "zzzz"), 0.000001)
        val transposed = TitleMatch.ratio("martha", "marhta")
        assertTrue(transposed > 0.9 && transposed < 1.0)
    }
}
