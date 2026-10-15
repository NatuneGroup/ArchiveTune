/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils.credits

internal enum class TrackCreditRole {
    WRITTEN_BY,
    COMPOSED_BY,
    LYRICS_BY,
}

internal enum class TrackCreditSource {
    VIDEO_DESCRIPTION,
    LYRICS_METADATA,
}

internal data class TrackCredit(
    val role: TrackCreditRole,
    val names: String,
    val source: TrackCreditSource,
)

internal fun trackCreditsFromMetadata(
    videoDescription: String?,
    lyricsMetadata: String?,
): List<TrackCredit> {
    val descriptionCredits = extractDescriptionCredits(videoDescription)
    if (descriptionCredits.isNotEmpty()) return descriptionCredits
    return extractLyricsCredits(lyricsMetadata)
}

private fun extractDescriptionCredits(description: String?): List<TrackCredit> {
    if (description.isNullOrBlank()) return emptyList()
    val credits = LinkedHashMap<Pair<TrackCreditRole, String>, TrackCredit>()
    description.lineSequence().forEach { line ->
        val match = DESCRIPTION_CREDIT.find(line) ?: return@forEach
        val role = roleFor(match.groupValues[1]) ?: return@forEach
        val names = match.groupValues[2].trim().trimEnd('.', ';').trim()
        if (names.isBlank()) return@forEach
        val key = role to names.lowercase()
        if (key !in credits) credits[key] = TrackCredit(role, names, TrackCreditSource.VIDEO_DESCRIPTION)
    }
    return credits.values.toList()
}

private fun extractLyricsCredits(lyrics: String?): List<TrackCredit> {
    if (lyrics.isNullOrBlank()) return emptyList()
    val ttmlCredits = extractTtmlCredits(lyrics)
    if (ttmlCredits.isNotEmpty()) return ttmlCredits

    val names = LinkedHashSet<String>()
    lyrics.lineSequence()
        .map { it.trim() }
        .filter(String::isNotBlank)
        .toList()
        .takeLast(8)
        .forEach { line ->
            val match = TRAILING_WRITER_CREDIT.find(line) ?: return@forEach
            match.groupValues[2].trim().trimEnd('.', ';').trim().takeIf(String::isNotBlank)?.let(names::add)
        }
    return names.takeIf { it.isNotEmpty() }?.let {
        listOf(TrackCredit(TrackCreditRole.WRITTEN_BY, it.joinToString(", "), TrackCreditSource.LYRICS_METADATA))
    }.orEmpty()
}

private fun extractTtmlCredits(lyrics: String): List<TrackCredit> {
    val credits = LinkedHashMap<Pair<TrackCreditRole, String>, TrackCredit>()
    TTML_WRITER_TAG.findAll(lyrics).forEach { match ->
        val role = roleFor(match.groupValues[1]) ?: return@forEach
        val names = match.groupValues[2].trim()
        if (names.isBlank()) return@forEach
        val key = role to names.lowercase()
        if (key !in credits) credits[key] = TrackCredit(role, names, TrackCreditSource.LYRICS_METADATA)
    }
    return credits.values.toList()
}

private fun roleFor(label: String): TrackCreditRole? =
    when {
        label.contains("compos", ignoreCase = true) -> TrackCreditRole.COMPOSED_BY
        label.contains("lyric", ignoreCase = true) || label.equals("lyrics by", ignoreCase = true) -> TrackCreditRole.LYRICS_BY
        label.contains("writ", ignoreCase = true) || label.equals("ar", ignoreCase = true) -> TrackCreditRole.WRITTEN_BY
        else -> null
    }

private val DESCRIPTION_CREDIT =
    Regex(
        """^\s*(written\s+by|songwriters?|writers?|composers?|composed\s+by|lyricists?|lyrics\s+by)\s*[:\-–]\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

private val TRAILING_WRITER_CREDIT =
    Regex(
        """^\s*(?:\[?\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]?\s*)?\[?\s*(written\s+by|songwriters?|writers?|composers?|composed\s+by|lyricists?|lyrics\s+by)\s*\]?\s*[:\-–]\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

private val TTML_WRITER_TAG =
    Regex(
        """<(?:[\w.-]+:)?(songwriter|composer|lyricist|writer|ar)(?:\s[^>]*)?>([^<]+)</(?:[\w.-]+:)?\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
