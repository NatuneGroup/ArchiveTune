/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.MacroSubtitlesResponse
import moe.rukamori.archivetune.musixmatch.models.MatcherTrack
import moe.rukamori.archivetune.musixmatch.models.decodeMacroSubtitlesResponse
import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import moe.rukamori.archivetune.musixmatch.models.RichSyncResponse
import moe.rukamori.archivetune.musixmatch.models.SubtitleLine
import moe.rukamori.archivetune.musixmatch.models.TokenResponse
import java.util.Locale

/** Native Musixmatch provider ported from Spicetify's lyrics-plus `ProviderMusixmatch.js`. */
object Musixmatch {
    private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mac-ios-v2.0"

    var logger: ((String) -> Unit)? = null

    private val jsonFormat by lazy {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    proxyAuthenticator(okhttp3.Authenticator.JAVA_NET_AUTHENTICATOR)
                }
            }
            install(ContentNegotiation) { json(jsonFormat) }
            install(HttpTimeout) {
                requestTimeoutMillis = 20000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 20000
            }
            expectSuccess = false
        }
    }

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        duration: Int = -1,
    ): Result<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return Result.failure(IllegalArgumentException("Song title and artist are required"))
        }

        return try {
            val token = getToken()
            val lyrics = fetchLyricsWithToken(cleanTitle, cleanArtist, album, duration, token)
            if (lyrics != null) {
                Result.success(lyrics)
            } else {
                // One retry after a forced token refresh — covers the case where the cached token
                // was invalidated server-side between requests.
                val refreshed = refreshTokenAfterInvalid(token)
                val retry = fetchLyricsWithToken(cleanTitle, cleanArtist, album, duration, refreshed)
                if (retry != null) {
                    Result.success(retry)
                } else {
                    Result.failure(IllegalStateException("Lyrics unavailable from Musixmatch"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String? = null,
        duration: Int = -1,
        callback: (String) -> Unit,
    ) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return

        val token =
            try {
                getToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.invoke("Musixmatch getAllLyrics: token fetch failed: ${e.message}")
                return
            }

        val macro = fetchMacro(cleanTitle, cleanArtist, album, duration, token) ?: return
        val track = macro.message.body.macroCalls.matcherTrackGet?.message?.body?.track ?: return
        val instrumental = track.instrumental == 1
        val hasRichSync = track.hasRichSync == 1

        if (instrumental) {
            callback("♪ Instrumental ♪")
            return
        }

        // Cooperative cancellation between the richsync / subtitle / plain-lyrics
        // branches — mirrors the pattern used by LrcLib.getAllLyrics so a cancelled
        // fetch aborts promptly at branch boundaries, not just at suspend points.
        currentCoroutineContext().ensureActive()

        if (hasRichSync) {
            val ttml = fetchRichSyncTtml(track, token)
            if (ttml != null) {
                callback(ttml)
            }
        }

        currentCoroutineContext().ensureActive()

        val subtitleBody = macro.message.body.macroCalls.trackSubtitlesGet?.message?.body?.subtitleList?.subtitleList
            ?.firstOrNull()?.subtitle?.subtitleBody
        if (!subtitleBody.isNullOrBlank()) {
            val lrc = subtitleBodyToLrc(subtitleBody)
            if (lrc.isNotBlank()) callback(lrc)
        }

        currentCoroutineContext().ensureActive()

        val lyricsBody = macro.message.body.macroCalls.trackLyricsGet?.message?.body?.lyrics?.lyricsBody
        if (!lyricsBody.isNullOrBlank()) {
            callback(lyricsBody.trim())
        }
    }

    private suspend fun fetchLyricsWithToken(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        token: String,
    ): String? {
        val macro = fetchMacro(title, artist, album, duration, token) ?: return null
        val track = macro.message.body.macroCalls.matcherTrackGet?.message?.body?.track ?: return null

        if (track.instrumental == 1) {
            return "♪ Instrumental ♪"
        }

        if (track.hasRichSync == 1) {
            val ttml = fetchRichSyncTtml(track, token)
            if (ttml != null) {
                logger?.invoke("Musixmatch returning TTML richsync, lines=${ttml.count { it == '\n' }}")
                return ttml
            }
        }

        val subtitleBody = macro.message.body.macroCalls.trackSubtitlesGet?.message?.body?.subtitleList?.subtitleList
            ?.firstOrNull()?.subtitle?.subtitleBody
        if (!subtitleBody.isNullOrBlank()) {
            val lrc = subtitleBodyToLrc(subtitleBody)
            if (lrc.isNotBlank()) {
                logger?.invoke("Musixmatch returning LRC subtitle, lines=${lrc.count { it == '\n' }}")
                return lrc
            }
        }

        val lyricsBody = macro.message.body.macroCalls.trackLyricsGet?.message?.body?.lyrics?.lyricsBody
        if (!lyricsBody.isNullOrBlank()) {
            logger?.invoke("Musixmatch returning plain lyrics, length=${lyricsBody.length}")
            return lyricsBody.trim()
        }

        return null
    }

    private suspend fun getToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedToken?.let { return it }
        }
        return tokenMutex.withLock {
            if (!forceRefresh) {
                cachedToken?.let { return it }
            }
            val token = fetchTokenFromMusixmatch()
            cachedToken = token
            token
        }
    }

    private suspend fun refreshTokenAfterInvalid(previousToken: String): String =
        tokenMutex.withLock {
            cachedToken?.takeIf { it != previousToken } ?: run {
                val token = fetchTokenFromMusixmatch()
                cachedToken = token
                token
            }
        }

    private suspend fun fetchTokenFromMusixmatch(): String {
        val response =
            client.get("${BASE_URL}token.get") {
                parameter("app_id", APP_ID)
                musixmatchHeaders()
            }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Musixmatch token.get HTTP ${response.status.value}")
        }
        val body = response.bodyAsText()
        val parsed = jsonFormat.decodeFromString<TokenResponse>(body)
        val header = parsed.message.header
        if (header.statusCode != 200) {
            throw IllegalStateException("Musixmatch token.get api status ${header.statusCode}")
        }
        val token = parsed.message.body.userToken
        if (token.isNullOrBlank()) {
            throw IllegalStateException("Musixmatch token.get returned empty user_token")
        }
        logger?.invoke("Musixmatch token fetched ok, len=${token.length}")
        return token
    }

    private suspend fun fetchMacro(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        token: String,
    ): MacroSubtitlesResponse? {
        val response =
            client.get("${BASE_URL}macro.subtitles.get") {
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("q_track", title)
                parameter("q_artist", artist)
                if (!album.isNullOrBlank()) parameter("q_album", album)
                if (duration > 0) {
                    parameter("q_duration", duration)
                    parameter("f_subtitle_length", duration)
                }
                parameter("usertoken", token)
                parameter("part", "track_lyrics_translation_status,track_structure,track_performer_tagging")
                musixmatchHeaders()
            }
        if (!response.status.isSuccess()) {
            logger?.invoke("Musixmatch macro.subtitles HTTP ${response.status.value}")
            return null
        }
        val body = response.bodyAsText()
        val parsed =
            try {
                decodeMacroSubtitlesResponse(jsonFormat, body)
            } catch (e: Exception) {
                logger?.invoke("Musixmatch macro.subtitles parse failed: ${e.message}")
                return null
            } ?: run {
                logger?.invoke("Musixmatch macro.subtitles returned non-JSON or empty body")
                return null
            }
        val header = parsed.message.header
        if (header.statusCode != 200) {
            logger?.invoke("Musixmatch macro.subtitles api status=${header.statusCode}, mode=${header.mode}")
            return null
        }
        return parsed
    }

    private suspend fun fetchRichSyncTtml(
        track: MatcherTrack,
        token: String,
    ): String? {
        val commontrackId = track.commontrackId ?: return null
        val trackLength = track.trackLength ?: -1
        val response =
            client.get("${BASE_URL}track.richsync.get") {
                parameter("format", "json")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                if (trackLength > 0) {
                    parameter("f_subtitle_length", trackLength)
                    parameter("q_duration", trackLength)
                }
                parameter("commontrack_id", commontrackId)
                parameter("usertoken", token)
                musixmatchHeaders()
            }
        if (!response.status.isSuccess()) {
            logger?.invoke("Musixmatch track.richsync HTTP ${response.status.value}")
            return null
        }
        val body = response.bodyAsText()
        val parsed =
            try {
                jsonFormat.decodeFromString<RichSyncResponse>(body)
            } catch (e: Exception) {
                logger?.invoke("Musixmatch track.richsync parse failed: ${e.message}")
                return null
            }
        val header = parsed.message.header
        if (header.statusCode != 200) {
            logger?.invoke("Musixmatch track.richsync api status=${header.statusCode}")
            return null
        }
        val richsyncBody = parsed.message.body.richsync?.richsyncBody
        if (richsyncBody.isNullOrBlank()) return null
        val lines =
            try {
                jsonFormat.decodeFromString<List<RichSyncLine>>(richsyncBody)
            } catch (e: Exception) {
                logger?.invoke("Musixmatch richsync_body decode failed: ${e.message}")
                return null
            }
        if (lines.isEmpty()) return null
        return MusixmatchTtml.richSyncToTtml(lines)
    }

    internal fun subtitleBodyToLrc(subtitleBody: String): String {
        if (subtitleBody.isBlank()) return ""
        val lines =
            try {
                jsonFormat.decodeFromString<List<SubtitleLine>>(subtitleBody)
            } catch (e: Exception) {
                return ""
            }
        if (lines.isEmpty()) return ""
        val builder = StringBuilder()
        for (line in lines) {
            val text = line.text?.trim().orEmpty()
            val total = line.time?.total ?: 0.0
            builder.append(formatLrcTime(total))
            builder.append(text)
            builder.append('\n')
        }
        return builder.toString().trimEnd('\n')
    }

    private fun formatLrcTime(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val total = (safe * 1000.0).toLong()
        val minutes = (total / 60000).toInt()
        val secs = ((total % 60000) / 1000).toInt()
        val centis = ((total % 1000) / 10).toInt()
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, secs, centis)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.musixmatchHeaders() {
        headers.append(HttpHeaders.Accept, "application/json")
        headers.append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
        headers.append("X-Cookie", "x-mxm-token-guid=")
        headers.append("x-mxm-app-version", "10.1.1")
        headers.append("X-User-Agent", "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0")
    }
}
