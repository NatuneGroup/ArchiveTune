/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Genius.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import moe.rukamori.archivetune.constants.EnableGeniusLyricsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.util.Locale

object GeniusLyricsProvider : LyricsProvider {
    override val name = "Genius"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableGeniusLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatching {
            scrapeLyrics(title.forLyricsSearch(), artist.artistForLyricsSearch())
                ?: throw IllegalStateException("No Genius lyrics for $title — $artist")
        }

    private suspend fun scrapeLyrics(
        title: String,
        artist: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val cleanTitle = cleanQuery(title)
            val cleanArtist = cleanQuery(artist)

            val titleParts =
                if (cleanTitle.contains(TITLE_SEPARATOR)) {
                    TITLE_SEPARATOR.split(cleanTitle, limit = 2)
                } else {
                    null
                }

            val extractedTitle =
                when {
                    titleParts != null && titleParts[0].trim().equals(cleanArtist, ignoreCase = true) ->
                        titleParts[1].trim()
                    titleParts != null && titleParts[1].trim().equals(cleanArtist, ignoreCase = true) ->
                        titleParts[0].trim()
                    titleParts != null && titleParts[0].isNotBlank() && titleParts[1].isNotBlank() ->
                        titleParts[1].trim()
                    else -> cleanTitle
                }

            val extractedArtist =
                when {
                    titleParts != null && cleanArtist.isBlank() -> titleParts[0].trim()
                    else -> cleanArtist
                }

            val titleWithoutBrackets =
                extractedTitle
                    .replace(BRACKETED_CONTENT, " ")
                    .replace(NON_ALPHANUMERIC, " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

            val attempts =
                buildList {
                    if (extractedArtist.isNotBlank() && extractedTitle.isNotBlank()) {
                        add("$extractedArtist $extractedTitle")
                    }
                    if (extractedArtist.isNotBlank() && titleWithoutBrackets.isNotBlank() &&
                        titleWithoutBrackets != extractedTitle
                    ) {
                        add("$extractedArtist $titleWithoutBrackets")
                    }
                    if (titleWithoutBrackets.isNotBlank()) {
                        add(titleWithoutBrackets)
                    } else if (extractedTitle.isNotBlank()) {
                        add(extractedTitle)
                    }
                }.distinct()

            for (query in attempts) {
                val url = searchSongUrl(query, extractedTitle, extractedArtist) ?: continue
                val html = fetchHtml(url) ?: continue
                extractLyricsText(html)?.let { return@withContext it }
            }
            null
        }

    private suspend fun searchSongUrl(
        query: String,
        targetTitle: String,
        targetArtist: String,
    ): String? {
        val url = "$SEARCH_ENDPOINT?q=${LyricsProviderHttp.encode(query)}"
        val body =
            LyricsProviderHttp.get(
                url,
                mapOf("Accept" to "application/json"),
            ) ?: return null
        val root = LyricsProviderHttp.parseJson(body).asObject() ?: return null
        val sections =
            root["response"].asObject()?.array("sections") ?: return null

        val hits =
            sections.firstNotNullOfOrNull { section ->
                val songSection = section.asObject() ?: return@firstNotNullOfOrNull null
                if (songSection.string("type") != "song") return@firstNotNullOfOrNull null
                songSection.array("hits")
            } ?: return null

        val candidates = hits.mapNotNull { hit -> hit.asObject()?.get("result").asObject() }
        val best = bestMatch(candidates, targetTitle, targetArtist) ?: return null
        return best.string("url")?.takeIf { it.startsWith("http") }
    }

    private fun bestMatch(
        candidates: List<JsonObject>,
        targetTitle: String,
        targetArtist: String,
    ): JsonObject? {
        if (candidates.isEmpty()) return null
        val normTitle = targetTitle.lowercase(Locale.ROOT)
        val normArtist = targetArtist.lowercase(Locale.ROOT)

        val scored =
            candidates.mapNotNull { item ->
                val title = item.string("title")?.lowercase(Locale.ROOT) ?: ""
                val artist = item.string("artist_names")?.lowercase(Locale.ROOT) ?: ""

                val titleMatches =
                    normTitle.isNotBlank() &&
                        (title == normTitle || title.contains(normTitle) || normTitle.contains(title))
                val artistMatches =
                    normArtist.isNotBlank() &&
                        (artist == normArtist || artist.contains(normArtist) || normArtist.contains(artist))
                if (!titleMatches && !artistMatches) return@mapNotNull null

                var score = 0
                if (title == normTitle) {
                    score += 50
                } else if (titleMatches) {
                    score += 25
                }
                if (artistMatches) {
                    score += if (artist == normArtist) 40 else 20
                }

                val path = item.string("path").orEmpty()
                if (path.contains("translation", ignoreCase = true) && !normTitle.contains("translation")) score -= 30
                if (path.contains("türkçe", ignoreCase = true) || path.contains("polskie-tlumaczenie")) score -= 40
                if (path.contains("tracklist", ignoreCase = true) || path.contains("album-art", ignoreCase = true)) score -= 50

                if (score <= 0) return@mapNotNull null
                item to score
            }
        return scored.maxByOrNull { it.second }?.first
    }

    private suspend fun fetchHtml(url: String): String? =
        LyricsProviderHttp.get(
            url,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )

    private fun extractLyricsText(html: String): String? {
        val bodies = extractContainerBodies(html)
        if (bodies.isEmpty()) return null

        val text =
            bodies.joinToString("\n") { body -> containerText(body) }.trim()
        if (text.isEmpty()) return null
        return stripArtifacts(text)
    }

    private fun extractContainerBodies(html: String): List<String> {
        val bodies = mutableListOf<String>()
        for (match in LYRICS_CONTAINER.findAll(html)) {
            balancedDivBody(html, match.range.last + 1)?.let(bodies::add)
        }
        if (bodies.isEmpty()) {
            for (match in LEGACY_LYRICS_DIV.findAll(html)) {
                balancedDivBody(html, match.range.last + 1)?.let(bodies::add)
            }
        }
        return bodies
    }

    private fun balancedDivBody(
        html: String,
        from: Int,
    ): String? {
        var depth = 1
        var index = from
        while (index < html.length) {
            val tag = html.indexOf('<', index)
            if (tag < 0) return null
            val after = tag + 1
            when {
                html.regionMatches(after, "div", 0, 3) && !isNameChar(html.getOrNull(after + 3)) -> {
                    depth++
                    index = after + 3
                }
                html.regionMatches(after, "/div", 0, 4) -> {
                    depth--
                    if (depth == 0) return html.substring(from, tag)
                    index = after + 4
                }
                else -> index = after
            }
        }
        return null
    }

    private fun isNameChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '-' || c == '_')

    private fun containerText(body: String): String {
        var text = body
        NOISE_BLOCKS.forEach { pattern -> text = pattern.replace(text, "\n") }
        text = BR_TAG.replace(text, "\n")
        text = PARAGRAPH_TAG.replace(text, "\n")
        text = ANY_TAG.replace(text, "")
        return LyricsPayload.decodeEntities(text)
    }

    private fun stripArtifacts(raw: String): String =
        raw
            .replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .replace('\uFEFF', ' ')
            .replace(YOU_MIGHT_ALSO_LIKE, "")
            .replace(TRAILING_EMBED, "")
            .replace(TRAILING_EMBED_LINE, "")
            .trim()

    private fun cleanQuery(text: String): String {
        val cleaned =
            text
                .replace(DECORATIVE_CHARS, " ")
                .replace(NOISE, " ")
                .replace(PRODUCER_TAGS, " ")
                .substringBefore(" | ")
                .replace(Regex("\\s+"), " ")
                .trim()
        return cleaned.ifBlank { text.trim() }
    }

    private const val SEARCH_ENDPOINT = "https://genius.com/api/search/multi"

    private val TITLE_SEPARATOR by lazy { Regex("""\s+[-–—:]\s+""") }
    private val DECORATIVE_CHARS by lazy { Regex("""[♪♫★☆【】《》「」~_]""") }
    private val PRODUCER_TAGS by lazy { Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$""") }
    private val NOISE by lazy {
        Regex(
            """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
                """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|hd\s+video|music\s+video)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val BRACKETED_CONTENT by lazy { Regex("""\s*[\(\[].*?[\)\]]""") }
    private val NON_ALPHANUMERIC by lazy { Regex("[^\\p{L}\\p{N}\\s]") }
    private val YOU_MIGHT_ALSO_LIKE by lazy { Regex("""\d*You might also like""", RegexOption.IGNORE_CASE) }
    private val TRAILING_EMBED by lazy { Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE) }
    private val TRAILING_EMBED_LINE by lazy { Regex("""(?m)^\d*Embed\s*$""", RegexOption.IGNORE_CASE) }

    private val LYRICS_CONTAINER = Regex("""<div\b[^>]*\bdata-lyrics-container="true"[^>]*>""")
    private val LEGACY_LYRICS_DIV = Regex("""<div\b[^>]*\bclass="[^"]*\blyrics\b[^"]*"[^>]*>""")

    private val NOISE_BLOCKS =
        listOf(
            Regex("""<script\b.*?</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""<style\b.*?</style\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""<button\b.*?</button\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        )
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val PARAGRAPH_TAG = Regex("""</?p\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("""<[^>]+>""")
}
