/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.applemusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import kotlin.math.abs

object AppleMusicPlaybackResolver {
    private const val MIN_MATCH_SCORE = 12

    suspend fun resolveTrack(track: AppleMusicSearchItem.Track): SongItem? =
        withContext(Dispatchers.IO) {
            val query =
                listOf(track.artist, track.title)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            val searchResult =
                YouTube
                    .search(
                        query = query,
                        filter = YouTube.SearchFilter.FILTER_SONG,
                        useAccountContext = false,
                    ).getOrNull()
                    ?: YouTube
                        .search(
                            query = query,
                            filter = YouTube.SearchFilter.FILTER_SONG,
                        ).getOrNull()
                    ?: return@withContext null

            val candidates =
                searchResult.items
                    .filterIsInstance<SongItem>()
                    .distinctBy { it.id }
            if (candidates.isEmpty()) return@withContext null

            candidates
                .map { candidate -> candidate to matchScore(candidate, track) }
                .filter { (_, score) -> score >= MIN_MATCH_SCORE }
                .maxByOrNull { (_, score) -> score }
                ?.first
        }

    private fun matchScore(
        candidate: SongItem,
        track: AppleMusicSearchItem.Track,
    ): Int {
        var score = 0

        val candidateTitle = candidate.title
        val title = track.title
        if (candidateTitle.equals(title, ignoreCase = true)) {
            score += 20
        } else if (candidateTitle.contains(title, ignoreCase = true) || title.contains(candidateTitle, ignoreCase = true)) {
            score += 10
        }

        val artist = track.artist.trim()
        if (artist.isNotEmpty()) {
            val candidateArtists = candidate.artists.joinToString(" ") { it.name }
            if (candidateArtists.equals(artist, ignoreCase = true)) {
                score += 15
            } else if (candidateArtists.contains(artist, ignoreCase = true) || artist.contains(candidateArtists, ignoreCase = true)) {
                score += 8
            }
        }

        if (track.durationMs > 0) {
            val candidateDuration = candidate.duration
            if (candidateDuration != null) {
                val diff = abs(candidateDuration * 1000L - track.durationMs)
                if (diff < 3000) {
                    score += 10
                } else if (diff < 10000) {
                    score += 5
                }
            }
        }

        return score
    }
}
