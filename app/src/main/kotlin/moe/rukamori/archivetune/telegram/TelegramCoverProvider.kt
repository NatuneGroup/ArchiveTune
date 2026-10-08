/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Resolves a high-resolution cover-art URL for a Telegram track from its title/performer, using
 * the public iTunes Search API (no key required). Telegram's own embedded album covers are tiny
 * (~320px) and look blurry when shown full-screen, so we prefer a proper catalogue cover and fall
 * back to the Telegram thumbnail only when nothing is found. Results (including misses) are cached
 * in-memory to avoid repeat lookups.
 */



package moe.rukamori.archivetune.telegram

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TelegramCoverProvider {
    private const val SEARCH_ENDPOINT = "https://itunes.apple.com/search"

    // Optional sentinel used as the cached value for a confirmed miss.
    private const val MISS = ""

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Bound for [cache]. Nothing else evicts from it, and its keys are track metadata, so a long
     * session across a large Telegram library would otherwise keep one entry per track ever seen
     * for the life of the process. Crude like the AI romanisation cache, and enough: the cost of
     * dropping an entry is one iTunes lookup.
     */
    private const val MAX_CACHE_ENTRIES = 512

    /**
     * Returns a high-resolution cover URL for the given track metadata, or null if none is found.
     * Blocking network call — invoke from a background dispatcher.
     */
    fun coverUrl(
        title: String,
        artist: String?,
    ): String? {
        val cleanedTitle = cleanTitle(title)
        if (cleanedTitle.isBlank()) return null
        val key = "${cleanedTitle.lowercase(Locale.US)}|${artist?.lowercase(Locale.US).orEmpty()}"
        cache[key]?.let { return it.takeIf { c -> c != MISS } }

        val resolved = runCatching { lookup(cleanedTitle, artist) }.getOrNull()
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = resolved ?: MISS
        return resolved
    }

    private fun lookup(
        title: String,
        artist: String?,
    ): String? {
        val term = listOfNotNull(artist?.takeIf(String::isNotBlank), title).joinToString(" ")
        val url =
            SEARCH_ENDPOINT +
                "?term=" + URLEncoder.encode(term, "UTF-8") +
                "&entity=song&limit=1&media=music"
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "ArchiveTune")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val artwork = results.getJSONObject(0).optString("artworkUrl100").takeIf(String::isNotBlank)
                ?: return null
            // iTunes returns a 100x100 thumbnail; swap the size segment for a large square cover.
            return artwork.replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/600x600bb.jpg")
        }
    }

    /** Strips common noise (bracketed tags, "official video", track numbers) to improve matching. */
    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("^\\s*\\d+\\s*[.\\-]\\s*"), "") // leading track number
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun clearCache() = cache.clear()
}
