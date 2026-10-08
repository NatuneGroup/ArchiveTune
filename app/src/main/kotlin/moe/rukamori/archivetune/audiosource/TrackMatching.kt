/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.audiosource

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Shared heuristics for deciding whether a catalog search hit is actually the track we asked for.
 */
internal object TrackMatching {
    /** Minimum score for a candidate to be accepted at all. */
    const val MIN_MATCH_SCORE = 60

    /** Ignored when comparing token sets, since they carry no identifying information. */
    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with")

    /**
     * Words that distinguish otherwise identically-titled recordings. A mismatch on any of these is
     * treated as a hard rejection, because serving a live take for a studio track is worse than
     * serving nothing and falling through to the next source.
     */
    private val VERSION_TOKENS =
        setOf("remix", "acoustic", "live", "instrumental", "demo", "edit", "mix", "version")

    /** Runtimes this far apart are still considered the same recording. */
    private const val DURATION_TOLERANCE_MS = 45_000L

    /**
     * Scores [candidateTitle]/[candidateArtist] against what we wanted. Returns [Int.MIN_VALUE] for a
     * hard rejection; otherwise higher is better and callers should require [MIN_MATCH_SCORE].
     */
    fun score(
        wantedTitle: String,
        wantedArtists: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Int {
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return Int.MIN_VALUE
        if (hasVersionMismatch(" $wantedTitle ", " $candidateTitle ")) return Int.MIN_VALUE
        val titleOverlap = tokenOverlap(significantTokens(wantedTitle), significantTokens(candidateTitle))
        if (titleOverlap < 0.5) return Int.MIN_VALUE
        var score = (titleOverlap * 100).toInt()
        if (wantedArtists.isNotEmpty() && candidateArtist.isNotBlank()) {
            val artistOverlap =
                wantedArtists.maxOf { tokenOverlap(significantTokens(it), significantTokens(candidateArtist)) }
            score += (artistOverlap * 60).toInt()
        }
        if (wantedDurationMs != null && candidateDurationMs != null) {
            // A duration agreement is weak evidence but a disagreement is strong counter-evidence, so
            // the penalty deliberately outweighs the bonus.
            if (durationMatches(wantedDurationMs, candidateDurationMs)) score += 30 else score -= 40
        }
        return score
    }

    /** What the player asked for. */
    data class Target(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    /**
     * One search hit from a provider's catalog, normalized so [best] can rank hits from any source.
     * [id] is the provider's own track identifier and is opaque here.
     */
    data class Candidate(
        val id: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    /**
     * Picks the highest-scoring candidate that clears [MIN_MATCH_SCORE], or null when none does.
     *
     * Returning null is the intended outcome for a track the catalog does not carry: the caller falls
     * through to the next source rather than playing a wrong recording. Note this is only the
     * provider-internal choice of "which hit is best" — the playback layer still re-checks the winner
     * with [TitleMatch], so a provider cannot bypass the shared safety gate by scoring loosely here.
     */
    fun best(
        target: Target,
        candidates: List<Candidate>,
    ): Candidate? =
        candidates
            .asSequence()
            .map { candidate ->
                candidate to
                    score(
                        wantedTitle = normalizeTitle(target.title),
                        wantedArtists = target.artists.map { normalize(it) },
                        candidateTitle = normalizeTitle(candidate.title),
                        candidateArtist = normalize(candidate.artists.firstOrNull()),
                        wantedDurationMs = target.durationMs,
                        candidateDurationMs = candidate.durationMs,
                    )
            }.filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        // Divide by the larger set so that a candidate padded with extra words cannot score a perfect
        // overlap just by containing every wanted token.
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    fun durationMatches(
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Boolean {
        if (wantedDurationMs == null || candidateDurationMs == null) return true
        return abs(wantedDurationMs - candidateDurationMs) <= DURATION_TOLERANCE_MS
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean = VERSION_TOKENS.any { token -> wanted.contains(" $token ") != candidate.contains(" $token ") }

    /** Lowercased, accent-stripped, punctuation-collapsed form used for all token comparisons. */
    fun normalize(value: String?): String =
        value
            ?.lowercase(Locale.US)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(Regex("\\p{Mn}+"), "")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()

    /** Strips featured-artist and edition noise that catalogs disagree about. */
    fun normalizeTitle(value: String): String =
        normalize(value)
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Cleans a title for use as a search query, keeping original casing and diacritics. */
    fun searchTitle(value: String): String =
        value
            .trim()
            .replace(Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE),
                "",
            ).replace(Regex("\\s+"), " ")
            .trim()

    /** Primary artist only; catalogs list collaborators inconsistently. */
    fun searchArtist(value: String): String =
        value
            .trim()
            .substringBefore(',')
            .replace(Regex("\\s+"), " ")
            .trim()
}
