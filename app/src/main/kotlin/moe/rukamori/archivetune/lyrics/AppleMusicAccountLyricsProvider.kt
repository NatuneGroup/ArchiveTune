/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.constants.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber

/**
 * Apple Music lyrics via the user's own Media-User-Token.
 * Uses the official amp-api.music.apple.com lyrics endpoint discovered
 * with the ES test account: `/v1/catalog/{storefront}/songs/{id}/lyrics`.
 * The requested catalog id is resolved via search with the same tokens,
 * and the TTML is converted to LRC. No pool, no Paxsenix.
 */
object AppleMusicAccountLyricsProvider : LyricsProvider {
    override val name = "Apple Music"

    override fun isEnabled(context: Context): Boolean {
        // Enabled when the user pasted a Media-User-Token (0.Ap…) OR a shared pool account is
        // available. The dev JWT is optional: without one the web player token is scraped.
        val token = context.dataStore[AppleMusicMediaUserTokenKey]?.trim().orEmpty()
        if (token.isNotBlank()) return true
        return PoolAccountManager.appleMusicAccounts().isNotEmpty()
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = runCatching {
        val ttml = fetchTtml(title, artist)
            ?: throw IllegalStateException("No Apple Music lyrics for $title — $artist")
        ttmlToLrc(ttml)
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }

    // ── Network ──
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 12_000
                requestTimeoutMillis = 18_000
                socketTimeoutMillis = 18_000
            }
            // Shared by every AMP call; the token headers stay per request, where their values are.
            defaultRequest {
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
                header("User-Agent", UA)
            }
            expectSuccess = false
        }
    }

    private const val AMP_BASE = "https://amp-api.music.apple.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private suspend fun fetchTtml(title: String, artist: String): String? {
        // Resolve Apple Music song id via search with the user's tokens. The bearer is the pasted
        // dev JWT when there is one, otherwise a scraped web player token — and when neither is
        // available we return nothing rather than sending a request that can only 401.
        val token = AppleMusicProvider.ensureTokenFresh() ?: return null
        val mediaToken = AppleMusicProvider.mediaUserTokenProvider?.invoke()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val storefront = AppleMusicProvider.resolveStorefront()

        val query = if (title.contains(artist, ignoreCase = true)) title else "$artist $title"
        val searchResp = client.get("$AMP_BASE/v1/catalog/$storefront/search") {
            header("Authorization", "Bearer $token")
            header("Media-User-Token", mediaToken)
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", "5")
        }
        if (!searchResp.status.isSuccess()) {
            Timber.tag("AppleMusicLyrics").w("search failed ${searchResp.status}")
            return null
        }
        val root = searchResp.body<JsonObject>()
        val songs = root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray ?: return null
        val best = songs.firstOrNull()?.jsonObject ?: return null
        val songId = best["id"]?.jsonPrimitive?.contentOrNull ?: return null

        // Try syllable-lyrics first (word sync), fall back to lyrics (line sync).
        for (endpoint in listOf("syllable-lyrics", "lyrics")) {
            val resp = client.get("$AMP_BASE/v1/catalog/$storefront/songs/$songId/$endpoint") {
                header("Authorization", "Bearer $token")
                header("Media-User-Token", mediaToken)
            }
            if (!resp.status.isSuccess()) continue
            val payload = resp.bodyAsText().trimStart()
            // A body that is not markup is the JSON envelope; a body that is markup is the TTML
            // document itself. Matching "<tt" inside JSON would hand the envelope back as lyrics,
            // but so would accepting any "<": an HTML error page served 2xx is not lyrics.
            if (payload.startsWith("<tt") || payload.startsWith("<?xml")) return payload
            val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            val ttml =
                body["data"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("attributes")
                    ?.jsonObject
                    ?.get("ttml")
                    ?.jsonPrimitive
                    ?.contentOrNull
            if (!ttml.isNullOrBlank()) return ttml
        }
        return null
    }

    private fun ttmlToLrc(ttml: String): String {
        // TTML <p begin="27.395" end="28.960">I been tryna call</p> -> [00:27.39]I been tryna call
        // Also supports word-level <span> – we flatten to line text.
        val pRegex = Regex("""<p[^>]*begin="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        for (m in pRegex.findAll(ttml)) {
            val begin = m.groupValues[1]
            var text = m.groupValues[2]
            // Strip inner spans but keep text
            text = text.replace(Regex("""<span[^>]*>"""), "")
                .replace("</span>", " ")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()
                .replace(Regex("""\s+"""), " ")
            if (text.isBlank()) continue
            val sec = parseTimeSec(begin)
            sb.append(formatLrc(sec)).append(text).append('\n')
        }
        if (sb.isEmpty()) {
            // Fallback: extract any text between tags if no <p> found
            return ttml.replace(Regex("""<[^>]+>"""), "\n").trim()
        }
        return sb.toString().trimEnd()
    }

    private fun parseTimeSec(raw: String): Double {
        // "27.395" or "1:00.964" or "1:02:03.123"
        val parts = raw.split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toDouble()
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                else -> 0.0
            }
        } catch (_: Exception) { 0.0 }
    }

    private fun formatLrc(sec: Double): String {
        val totalMs = (sec * 1000).toLong()
        val min = totalMs / 60000
        val secPart = (totalMs % 60000) / 1000
        val ms = totalMs % 1000
        return String.format("[%02d:%02d.%02d]", min, secPart, ms / 10)
    }
}
