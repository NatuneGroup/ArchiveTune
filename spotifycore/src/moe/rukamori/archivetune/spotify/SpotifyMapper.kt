/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyImage
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import java.text.Normalizer

/**
 * Utility object for creating search queries from Spotify track data.
 * The actual mapping to Metrolist MediaMetadata is done in the app module
 * where MediaMetadata class is available.
 */
object SpotifyMapper {
    // Pre-compiled regex patterns for title normalization (avoids re-creation on each call)
    private val FEAT_PATTERN = Regex("\\(feat\\..*?\\)")
    private val FT_PATTERN = Regex("\\(ft\\..*?\\)")
    private val BRACKET_PATTERN = Regex("\\[.*?]")
    private val REMASTER_PATTERN = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val REMIX_PATTERN = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)

    // Soundtrack credits Spotify appends to Indian film songs: `Song (From "Movie")` or
    // `Song - From "Movie"`. YouTube Music titles almost never carry them, so leaving them in the
    // query made the search return nothing and the track unplayable.
    private val FROM_SOUNDTRACK_PATTERN =
        Regex("""\s*[(\[]\s*from\s+[^)\]]+[)\]]""", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX_PATTERN =
        Regex("""\s+-\s+(from\s+["“”'].*|.*\bremaster(ed)?\b.*|mono|stereo)$""", RegexOption.IGNORE_CASE)
    private val FEAT_ANY_PATTERN = Regex("""\s*[(\[]\s*(feat|ft|with)\.?\s.*?[)\]]""", RegexOption.IGNORE_CASE)
    // Keeps letters and digits of every script. An `[a-z0-9]` class deleted Tamil, Hindi,
    // Cyrillic and CJK titles outright, which emptied the normalized title, zeroed both
    // bigram scores and pinned every non-Latin track under the match floor — so those
    // tracks could never be mapped to a playable source.
    private val NON_ALNUM_PATTERN = Regex("[^\\p{L}\\p{N}\\s]")

    // Decomposed accents would otherwise compare unequal to their precomposed spelling
    // ("café" vs "cafe"); NFD plus a combining-mark strip folds them together.
    private val COMBINING_MARKS_PATTERN = Regex("\\p{M}+")
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    private const val NORM_CACHE_MAX_SIZE = 256
    private const val EARLY_EXIT_THRESHOLD = 0.95

    /**
     * LRU cache for normalized strings. Avoids re-running 7 regex replacements
     * on the same Spotify title/artist across multiple candidate comparisons.
     * Bounded to [NORM_CACHE_MAX_SIZE] entries to limit memory usage.
     */
    private val normalizeCache =
        object : LinkedHashMap<String, String>(
            NORM_CACHE_MAX_SIZE,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > NORM_CACHE_MAX_SIZE
        }

    /**
     * LRU cache for pre-computed bigram sets. Avoids re-creating Set<String>
     * on every stringSimilarity call for the same normalized string.
     */
    private val bigramCache =
        object : LinkedHashMap<String, Set<String>>(
            NORM_CACHE_MAX_SIZE,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean = size > NORM_CACHE_MAX_SIZE
        }

    /**
     * Pre-computed data for one side of a match comparison.
     * Created once per Spotify track and reused across all candidates.
     */
    data class PrecomputedTrack(
        val normalizedTitle: String,
        val titleBigrams: Set<String>,
        val normalizedArtist: String,
        val artistBigrams: Set<String>,
        val durationMs: Int,
    )

    /**
     * Builds a YouTube search query from a Spotify track.
     * The query is optimized for finding the matching song on YouTube Music.
     */
    fun buildSearchQuery(track: SpotifyTrack): String {
        val artist =
            track.artists
                .firstOrNull()
                ?.name
                .orEmpty()
        val title = searchTitle(track.name)
        return if (artist.isEmpty()) title else "$artist $title"
    }

    /** [title] without soundtrack credits, featured-artist and remaster suffixes. */
    fun searchTitle(title: String): String =
        title
            .replace(FROM_SOUNDTRACK_PATTERN, "")
            .replace(FEAT_ANY_PATTERN, "")
            .replace(DASH_SUFFIX_PATTERN, "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()
            .ifEmpty { title.trim() }

    /** The best artwork URL Spotify offers for a playlist. */
    fun getPlaylistThumbnail(playlist: SpotifyPlaylist): String? = largestImageUrl(playlist.images)

    /** The best avatar Spotify offers for an artist. Same reasoning as [getPlaylistThumbnail]. */
    fun getArtistThumbnail(artist: SpotifyArtist): String? = largestImageUrl(artist.images)

    /**
     * The best artwork URL from a Spotify track's album art. Same reasoning as
     * [getPlaylistThumbnail].
     */
    fun getTrackThumbnail(track: SpotifyTrack): String? = largestImageUrl(track.album?.images)

    private fun largestImageUrl(images: List<SpotifyImage>?): String? =
        images
            ?.maxByOrNull { it.width ?: 0 }
            ?.url
            ?.takeIf { it.isNotBlank() }

    /**
     * Pre-computes normalized title/artist and their bigrams for a Spotify track.
     * Call once before scoring against multiple candidates to avoid redundant work.
     */
    fun precompute(
        title: String,
        artist: String,
        durationMs: Int,
    ): PrecomputedTrack {
        val normTitle = cachedNormalize(title)
        val normArtist = cachedNormalize(artist)
        return PrecomputedTrack(
            normalizedTitle = normTitle,
            titleBigrams = cachedBigrams(normTitle),
            normalizedArtist = normArtist,
            artistBigrams = cachedBigrams(normArtist),
            durationMs = durationMs,
        )
    }

    /**
     * Computes a match confidence score (0.0 - 1.0) between a Spotify track and
     * a candidate result based on title, artist, and duration similarity.
     */
    fun matchScore(
        spotifyTitle: String,
        spotifyArtist: String,
        spotifyDurationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normSpotifyTitle = cachedNormalize(spotifyTitle)
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normSpotifyArtist = cachedNormalize(spotifyArtist)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore =
            bigramSimilarity(
                normSpotifyTitle,
                cachedBigrams(normSpotifyTitle),
                normCandidateTitle,
                cachedBigrams(normCandidateTitle),
            )
        val artistScore =
            bigramSimilarity(
                normSpotifyArtist,
                cachedBigrams(normSpotifyArtist),
                normCandidateArtist,
                cachedBigrams(normCandidateArtist),
            )

        val durationScore = durationScore(spotifyDurationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /**
     * Scores a candidate against pre-computed Spotify track data.
     * This is the fast path: normalization and bigrams for the Spotify side
     * are computed once and reused across all candidates.
     */
    fun matchScorePrecomputed(
        precomputed: PrecomputedTrack,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore =
            bigramSimilarity(
                precomputed.normalizedTitle,
                precomputed.titleBigrams,
                normCandidateTitle,
                cachedBigrams(normCandidateTitle),
            )
        val artistScore =
            bigramSimilarity(
                precomputed.normalizedArtist,
                precomputed.artistBigrams,
                normCandidateArtist,
                cachedBigrams(normCandidateArtist),
            )

        val durationScore = durationScore(precomputed.durationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /** Threshold above which we consider a match good enough to skip remaining candidates. */
    fun earlyExitThreshold(): Double = EARLY_EXIT_THRESHOLD

    private fun durationScore(
        spotifyDurationMs: Int,
        candidateDurationSec: Int?,
    ): Double {
        if (candidateDurationSec == null || spotifyDurationMs <= 0) return 0.5
        val diff = kotlin.math.abs(spotifyDurationMs / 1000 - candidateDurationSec)
        return when {
            diff <= 2 -> 1.0
            diff <= 5 -> 0.8
            diff <= 10 -> 0.5
            diff <= 30 -> 0.2
            else -> 0.0
        }
    }

    /**
     * Normalizes a title for comparison, with LRU caching.
     */
    private fun cachedNormalize(title: String): String {
        normalizeCache[title]?.let { return it }
        val normalized = normalizeTitle(title)
        normalizeCache[title] = normalized
        return normalized
    }

    /**
     * Returns cached bigrams for a normalized string.
     */
    private fun cachedBigrams(normalized: String): Set<String> {
        bigramCache[normalized]?.let { return it }
        val bigrams = if (normalized.length < 2) emptySet() else normalized.windowed(2).toSet()
        bigramCache[normalized] = bigrams
        return bigrams
    }

    private fun normalizeTitle(title: String): String =
        Normalizer
            .normalize(
                title
                    .lowercase()
                    .replace(FROM_SOUNDTRACK_PATTERN, "")
                    .replace(DASH_SUFFIX_PATTERN, "")
                    .replace(FEAT_PATTERN, "")
                    .replace(FT_PATTERN, "")
                    .replace(BRACKET_PATTERN, "")
                    .replace(REMASTER_PATTERN, "")
                    .replace(REMIX_PATTERN, ""),
                Normalizer.Form.NFD,
            ).replace(COMBINING_MARKS_PATTERN, "")
            .replace(NON_ALNUM_PATTERN, "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()

    /**
     * Dice coefficient using pre-computed bigram sets.
     */
    private fun bigramSimilarity(
        a: String,
        bigramsA: Set<String>,
        b: String,
        bigramsB: Set<String>,
    ): Double {
        if (a == b) return 1.0
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        val intersection = bigramsA.count { it in bigramsB }
        return (2.0 * intersection) / (bigramsA.size + bigramsB.size)
    }
}
