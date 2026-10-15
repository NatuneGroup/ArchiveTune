/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver rejects any candidate scoring below `SpotifyPlaybackResolver.MIN_MATCH_THRESHOLD`
 * (0.6), so these tests assert against that floor rather than against raw similarity: what matters
 * is whether a real match clears the bar and a wrong song does not.
 */
class SpotifyMapperNormalizationTest {
    private val matchFloor = 0.6

    private fun score(
        spotifyTitle: String,
        spotifyArtist: String,
        spotifyDurationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double =
        SpotifyMapper.matchScore(
            spotifyTitle = spotifyTitle,
            spotifyArtist = spotifyArtist,
            spotifyDurationMs = spotifyDurationMs,
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            candidateDurationSec = candidateDurationSec,
        )

    @Test
    fun tamilTrackMatchesNativeScriptCandidate() {
        val score =
            score(
                spotifyTitle = "காதல் தீ ஆசை",
                spotifyArtist = "Anirudh Ravichander",
                spotifyDurationMs = 215_000,
                candidateTitle = "காதல் தீ ஆசை",
                candidateArtist = "Anirudh Ravichander",
                candidateDurationSec = 215,
            )

        assertTrue(
            "Tamil track scored $score, below the $matchFloor mapping floor",
            score >= matchFloor,
        )
    }

    @Test
    fun hindiTrackMatchesNativeScriptCandidate() {
        val score =
            score(
                spotifyTitle = "ले चलो घूम चलें",
                spotifyArtist = "Arijit Singh",
                spotifyDurationMs = 232_000,
                candidateTitle = "ले चलो घूम चलें",
                candidateArtist = "Arijit Singh",
                candidateDurationSec = 232,
            )

        assertTrue(
            "Hindi track scored $score, below the $matchFloor mapping floor",
            score >= matchFloor,
        )
    }

    @Test
    fun japaneseTrackMatchesNativeScriptCandidate() {
        val score =
            score(
                spotifyTitle = "アイドル",
                spotifyArtist = "YOASOBI",
                spotifyDurationMs = 180_000,
                candidateTitle = "アイドル",
                candidateArtist = "YOASOBI",
                candidateDurationSec = 180,
            )

        assertTrue(
            "Japanese track scored $score, below the $matchFloor mapping floor",
            score >= matchFloor,
        )
    }

    @Test
    fun accentedLatinFoldsOntoUnaccentedCandidate() {
        val score =
            score(
                spotifyTitle = "Café del Mar",
                spotifyArtist = "Armin van Helden",
                spotifyDurationMs = 338_000,
                candidateTitle = "Cafe del Mar",
                candidateArtist = "Armin van Helden",
                candidateDurationSec = 338,
            )

        assertTrue(
            "Accent-folded match scored $score, below the $matchFloor mapping floor",
            score >= matchFloor,
        )
    }

    @Test
    fun wrongSongStaysBelowFloorForNonLatinLibrary() {
        // The floor exists so Spotify-exclusive tracks are not silently substituted with a
        // same-vibes song; keeping non-Latin text comparable must not have widened it.
        val score =
            score(
                spotifyTitle = "காதல் தீ ஆசை",
                spotifyArtist = "Anirudh Ravichander",
                spotifyDurationMs = 215_000,
                candidateTitle = "Believer",
                candidateArtist = "Imagine Dragons",
                candidateDurationSec = 205,
            )

        assertTrue(
            "Unrelated song scored $score, at or above the $matchFloor mapping floor",
            score < matchFloor,
        )
    }

    @Test
    fun romanizedCandidateForNonLatinTitleStaysBelowFloor() {
        // A romanized YouTube upload is not provably the same recording; without a transliteration
        // step it must stay rejected rather than guessed at.
        val score =
            score(
                spotifyTitle = "காதல் தீ ஆசை",
                spotifyArtist = "Anirudh Ravichander",
                spotifyDurationMs = 215_000,
                candidateTitle = "Kaadhal The Aasai",
                candidateArtist = "Anirudh Ravichander",
                candidateDurationSec = 215,
            )

        assertTrue(
            "Romanized candidate scored $score, at or above the $matchFloor mapping floor",
            score < matchFloor,
        )
    }

    @Test
    fun soundtrackCreditIsDroppedFromTheSearchQuery() {
        assertEquals("Solai Malai Oram", SpotifyMapper.searchTitle("Solai Malai Oram (From \"Villupaattukaran\")"))
        assertEquals("Hukum", SpotifyMapper.searchTitle("Hukum - From \"Jailer\""))
        assertEquals("Muthamizhe Muthamizhea", SpotifyMapper.searchTitle("Muthamizhe Muthamizhea (From Raman Abdullah)"))
        assertEquals("Yesterday", SpotifyMapper.searchTitle("Yesterday - Remastered 2009"))
        assertEquals("Acoustic Version", SpotifyMapper.searchTitle("Acoustic Version"))
    }

    @Test
    fun soundtrackCreditDoesNotLowerTheMatchScore() {
        val score =
            score(
                spotifyTitle = "Solai Malai Oram (From \"Villupaattukaran\")",
                spotifyArtist = "S. P. Balasubrahmanyam",
                spotifyDurationMs = 270_000,
                candidateTitle = "Solai Malai Oram",
                candidateArtist = "S. P. Balasubrahmanyam",
                candidateDurationSec = 270,
            )

        assertTrue("Soundtrack-credited title scored $score", score >= matchFloor)
    }
}
