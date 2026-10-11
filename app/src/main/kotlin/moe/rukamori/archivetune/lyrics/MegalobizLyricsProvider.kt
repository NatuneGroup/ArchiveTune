/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Megalobiz.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.constants.EnableMegalobizLyricsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.util.Locale
import kotlin.math.abs

object MegalobizLyricsProvider : LyricsProvider {
    override val name = "Megalobiz"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableMegalobizLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatching {
            fetchLyrics(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration)
                ?: throw IllegalStateException("No Megalobiz lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (query.isEmpty()) return@withContext null

            val key = appKey()
            if (key != null) {
                searchAndConvert(query, title, artist, duration, key)?.let { return@withContext it }
            }

            val fresh = refreshAppKey() ?: return@withContext null
            if (fresh == key) return@withContext null
            searchAndConvert(query, title, artist, duration, fresh)
        }

    private suspend fun searchAndConvert(
        query: String,
        title: String,
        artist: String,
        duration: Int,
        key: String,
    ): String? {
        val url =
            "$API_BASE/lrcs?search=${LyricsProviderHttp.encode(query)}" +
                "&pageSize=$PAGE_SIZE"
        val body =
            LyricsProviderHttp.get(
                url,
                mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"),
            ) ?: return null
        val root = LyricsProviderHttp.parseJson(body).asObject() ?: return null
        val models = root.array("models") ?: return null
        if (models.isEmpty()) return null

        val sheets =
            models.mapNotNull { element ->
                val sheet = element.asObject() ?: return@mapNotNull null
                val lines = sheet["content"].asObject()?.array("lines") ?: return@mapNotNull null
                Sheet(
                    title = sheet.string("title").orEmpty(),
                    artist = sheet.string("artist").orEmpty(),
                    length = sheet.double("length"),
                    lines = lines,
                )
            }
        val best = best(sheets, title, artist, duration) ?: return null
        return toLrc(best.lines)
    }

    private fun best(
        sheets: List<Sheet>,
        title: String,
        artist: String,
        duration: Int,
    ): Sheet? {
        val wantedTitle = normalized(title)
        val wantedArtists = artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }

        fun score(sheet: Sheet): Int {
            val haystack = normalized("${sheet.title} ${sheet.artist}")
            if (wantedTitle.isEmpty() || haystack.isEmpty()) return 0

            var score = 0
            when {
                normalized(sheet.title) == wantedTitle -> score += 20
                haystack.contains(wantedTitle) -> score += 10
                else -> return 0
            }
            if (wantedArtists.isNotEmpty()) {
                when {
                    wantedArtists.any { it.length > 2 && haystack.contains(it) } -> score += 15
                    else -> score -= 10
                }
            }
            if (duration > 0 && sheet.length != null) {
                val distance = abs(sheet.length - duration)
                score +=
                    when {
                        distance <= 3.0 -> 10
                        distance <= 10.0 -> 5
                        else -> 0
                    }
            }
            return score
        }

        return sheets.map { it to score(it) }
            .filter { (_, score) -> score >= MINIMUM_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun toLrc(lines: List<JsonElement>): String? {
        val builder = StringBuilder()
        lines.forEach { element ->
            val line = element.asObject() ?: return@forEach
            val time = line.long("time") ?: return@forEach
            val text = (line["text"] as? JsonPrimitive)?.contentOrNull ?: ""
            builder
                .append('[')
                .append(formatStamp(time))
                .append(']')
                .append(text)
                .append('\n')
        }
        val lrc = builder.toString()
        return lrc.takeIf { LRC_LINE.containsMatchIn(it) }
    }

    private fun formatStamp(ms: Long): String {
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centis = (ms % 1000L) / 10L
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, centis)
    }

    private fun normalized(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private suspend fun appKey(): String? = cachedAppKey.get() ?: appKeyMutex.withLock {
        cachedAppKey.get() ?: DEFAULT_APP_KEY.also { cachedAppKey.set(it) }
    }

    private suspend fun refreshAppKey(): String? {
        val now = System.currentTimeMillis()
        if (now - lastScrapeAttemptMs < SCRAPE_COOLDOWN_MS) return null
        lastScrapeAttemptMs = now

        return appKeyMutex.withLock {
            val page = LyricsProviderHttp.get(SITE_LRC_PAGE) ?: return@withLock null
            val chunks = SCRIPT_PATH.findAll(page).map { it.value }.distinct().take(MAX_SCRAPED_CHUNKS)
            for (path in chunks) {
                val script = LyricsProviderHttp.get("$SITE_HOST$path") ?: continue
                if (!script.contains(API_HOST)) continue
                val key = APP_KEY.find(script)?.value ?: continue
                cachedAppKey.set(key)
                return@withLock key
            }
            null
        }
    }

    private data class Sheet(
        val title: String,
        val artist: String,
        val length: Double?,
        val lines: List<JsonElement>,
    )

    private const val API_HOST = "api.megalobiz.com"
    private const val API_BASE = "https://$API_HOST/api/v1"
    private const val SITE_HOST = "https://megalobiz.com"
    private const val SITE_LRC_PAGE = "$SITE_HOST/lrc"

    private const val PAGE_SIZE = 8
    private const val MINIMUM_MATCH_SCORE = 10

    private const val DEFAULT_APP_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJhcHBfaWQiOjEsImlhdCI6MTc5MDQ1NzA4MH0." +
            "2bIxON27e030cTRsC8GOTafnd5wU_LjnxWI-iEUfYw0"

    private const val SCRAPE_COOLDOWN_MS = 5 * 60_000L
    private const val MAX_SCRAPED_CHUNKS = 8

    @Volatile
    private var lastScrapeAttemptMs = 0L

    private val appKeyMutex = Mutex()
    private val cachedAppKey = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS =
        Regex(
            """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
            RegexOption.IGNORE_CASE,
        )
    private val LRC_LINE = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    private val SCRIPT_PATH = Regex("""/_next/static/chunks/[A-Za-z0-9._-]+\.js""")
    private val APP_KEY = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
}
