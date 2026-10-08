/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.utils

/** Parses the user's list of extra Spotify Canvas resolver endpoints. */
object CanvasResolverEndpoints {
    private const val MAX_ENDPOINTS = 8

    /**
     * Splits raw multi-line input into resolver base URLs, in the order given.
     *
     * The list is capped: each entry costs one network round trip per song with no canvas,
     * and a runaway paste should not turn every track change into dozens of requests.
     */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .split('\n', ',')
            .map { it.trim().trimEnd('/') }
            .filter { candidate ->
                candidate.startsWith("http://", ignoreCase = true) ||
                    candidate.startsWith("https://", ignoreCase = true)
            }.distinct()
            .take(MAX_ENDPOINTS)
    }

    /** Normalises for storage: one endpoint per line, blank/invalid entries removed. */
    fun serialize(endpoints: List<String>): String = endpoints.joinToString("\n")
}
