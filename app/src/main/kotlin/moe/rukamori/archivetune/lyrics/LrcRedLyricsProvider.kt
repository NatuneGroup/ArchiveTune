/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcRed.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.EnableLrcRedKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object LrcRedLyricsProvider : LyricsProvider {
    override val name = "lrc.red"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcRedKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = getLyrics(id, title, artist, album, duration, isrc = null)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        isrc: String?,
    ): Result<String> =
        runCatching {
            fetchLyrics(title, artist, duration, isrc)
                ?: throw IllegalStateException("No lrc.red lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
        isrc: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            val known = isrc?.let(::documentUrl)
            if (known != null) {
                document(known)?.let { return@withContext it }
            }

            val hit = search(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration)
                ?: return@withContext null
            val found = hit.isrc?.let(::documentUrl)?.takeIf { it != known } ?: return@withContext null
            document(found)
        }

    private suspend fun document(url: String): String? =
        LyricsProviderHttp.get(url)?.let(LyricsPayload::toLyrics)

    private suspend fun search(
        title: String,
        artist: String,
        duration: Int,
    ): Hit? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null
        val url = "https://lrc.red/search.json?q=${LyricsProviderHttp.encode(query)}"
        val body = LyricsProviderHttp.get(url) ?: return null
        val root = LyricsProviderHttp.parseJson(body).asObject() ?: return null
        val hits = root.array("hits") ?: return null
        val parsed = hits.mapNotNull { element ->
            val hit = element.asObject() ?: return@mapNotNull null
            Hit(
                isrc = hit.string("isrc"),
                title = hit.string("title"),
                artist = hit.string("artist"),
                duration = hit.double("duration"),
            )
        }
        return best(parsed, title, artist, duration)
    }

    private fun best(
        hits: List<Hit>,
        title: String,
        artist: String,
        duration: Int,
    ): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val seconds = duration.toDouble()

        fun distance(hit: Hit): Double =
            if (duration > 0 && hit.duration != null) abs(hit.duration - seconds) else 0.0

        return hits
            .filter { hit ->
                val name = hit.title ?: return@filter false
                hit.isrc?.let { documentUrl(it) } != null &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(hit.artist.orEmpty()).any { it in wantedArtists }) &&
                    distance(hit) <= DURATION_TOLERANCE_SECONDS
            }.minByOrNull(::distance)
    }

    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - "))
            .ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extras =
            BRACKETED.findAll(title).joinToString(" ") { it.value } +
                " " + title.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private fun documentUrl(isrc: String): String? =
        isrc.trim().uppercase(Locale.ROOT).takeIf { ISRC.matches(it) }?.let { "$BASE_URL/s/$it.ttml" }

    private data class Hit(
        val isrc: String?,
        val title: String?,
        val artist: String?,
        val duration: Double?,
    )

    private const val DURATION_TOLERANCE_SECONDS = 3.0

    private const val BASE_URL = "https://lrc.red"

    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS =
        Regex(
            """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
            RegexOption.IGNORE_CASE,
        )

    private val VERSION_WORDS =
        setOf(
            "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
            "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
            "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
        )
}
