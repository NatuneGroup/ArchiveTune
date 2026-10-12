/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.lastfm.models.Authentication
import moe.rukamori.archivetune.lastfm.models.LastFmError
import moe.rukamori.archivetune.lastfm.models.RecentTracksResponse
import moe.rukamori.archivetune.lastfm.models.TopArtistsResponse
import moe.rukamori.archivetune.lastfm.models.TopAlbumsResponse
import moe.rukamori.archivetune.lastfm.models.TopTracksResponse
import moe.rukamori.archivetune.lastfm.models.TrackInfoResponse
import moe.rukamori.archivetune.lastfm.models.TokenResponse
import moe.rukamori.archivetune.lastfm.models.UserInfoResponse
import java.net.URI
import java.security.MessageDigest

object LastFM {
    const val DEFAULT_API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    const val LIBREFM_API_ENDPOINT = "https://libre.fm/2.0/"
    const val FALLBACK_COMPAT_API_KEY = "archivetune"
    const val FALLBACK_COMPAT_SECRET = "archivetune"

    data class RuntimeConfig(
        val endpoint: String,
        val apiKey: String,
        val secret: String,
        val sessionKey: String?,
    )

    @Volatile
    private var runtimeConfig =
        RuntimeConfig(
            endpoint = DEFAULT_API_ENDPOINT,
            apiKey = "",
            secret = "",
            sessionKey = null,
        )

    var sessionKey: String?
        get() = runtimeConfig.sessionKey
        set(value) {
            runtimeConfig = runtimeConfig.copy(sessionKey = value)
        }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    enum class MobileSessionAuthMode {
        PASSWORD,

        // GNU FM (Libre.fm) only accepts the pre-2.0 md5(username + md5(password)) token here.
        LEGACY_AUTH_TOKEN,
    }

    /** One play for `track.scrobble`; [timestamp] is the UTC second playback started. */
    data class ScrobbleSubmission(
        val artist: String,
        val track: String,
        val timestamp: Long,
        val album: String? = null,
        val albumArtist: String? = null,
        val trackNumber: Int? = null,
        val duration: Int? = null,
        val chosenByUser: Boolean = true,
    )

    /**
     * Last.fm answers `status="ok"` for a batch even when it drops entries, and only says so per
     * entry in `ignoredMessage`. Ignored entries are final: re-sending them is ignored again.
     */
    data class ScrobbleBatchResult(
        val accepted: Int,
        val ignored: Int,
        val ignoredCodes: List<Int>,
    )

    const val MAX_SCROBBLES_PER_BATCH = 50

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun Map<String, String>.apiSig(secret: String): String {
        val sorted = toSortedMap()
        val toHash = sorted.entries.joinToString("") { it.key + it.value } + secret
        return toHash.md5()
    }

    private fun HttpRequestBuilder.lastfmParams(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json",
    ) {
        headers.append(HttpHeaders.UserAgent, "ArchiveTune (https://github.com/rukamori/ArchiveTune)")
        val paramsForSig =
            mutableMapOf(
                "method" to method,
                "api_key" to apiKey,
            ).apply {
                sessionKey?.let { put("sk", it) }
                putAll(extra)
            }
        val apiSig = paramsForSig.apiSig(secret)

        setBody(
            FormDataContent(
                Parameters.build {
                    paramsForSig.forEach { (key, value) -> append(key, value) }
                    append("api_sig", apiSig)
                    append("format", format)
                },
            ),
        )
    }

    suspend fun getToken() =
        runCatching {
            postAndDecode<TokenResponse>(
                method = "auth.getToken",
            )
        }

    suspend fun getSession(token: String) =
        runCatching {
            postAndDecode<Authentication>(
                method = "auth.getSession",
                extra = mapOf("token" to token),
            )
        }

    fun getAuthUrl(token: String): String {
        val config = runtimeConfig
        return if (config.endpoint == LIBREFM_API_ENDPOINT) {
            "https://libre.fm/api/auth?api_key=${config.apiKey}&token=$token"
        } else {
            "https://www.last.fm/api/auth/?api_key=${config.apiKey}&token=$token"
        }
    }

    suspend fun getMobileSession(
        username: String,
        password: String,
        authMode: MobileSessionAuthMode = MobileSessionAuthMode.PASSWORD,
    ) = runCatching {
        val credentials =
            when (authMode) {
                MobileSessionAuthMode.PASSWORD -> mapOf("password" to password)
                MobileSessionAuthMode.LEGACY_AUTH_TOKEN ->
                    mapOf("authToken" to (username.lowercase() + password.md5()).md5())
            }
        postAndDecode<Authentication>(
            method = "auth.getMobileSession",
            extra = mapOf("username" to username) + credentials,
        )
    }

    /**
     * [code] is the Last.fm API error code when [isApiError], otherwise the HTTP status of a
     * response that carried no API error body (a proxy or server outage page).
     */
    class LastFmException(
        val code: Int,
        override val message: String,
        val httpStatus: Int? = null,
        val isApiError: Boolean = true,
    ) : Exception(message) {
        override fun toString(): String = "LastFmException(code=$code, httpStatus=$httpStatus, apiError=$isApiError, message=$message)"
    }

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
        albumArtist: String? = null,
    ) = runCatching {
        postAndRead(
            method = "track.updateNowPlaying",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                    albumArtist?.takeIf { it.isNotBlank() }?.let { put("albumArtist", it) }
                    trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it.toString()) }
                    duration?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
                },
        )
    }

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = scrobbleBatch(
        listOf(
            ScrobbleSubmission(
                artist = artist,
                track = track,
                timestamp = timestamp,
                album = album,
                trackNumber = trackNumber,
                duration = duration,
            ),
        ),
    )

    suspend fun scrobbleBatch(submissions: List<ScrobbleSubmission>) =
        runCatching {
            require(submissions.size in 1..MAX_SCROBBLES_PER_BATCH)
            val responseText =
                postAndRead(
                    method = "track.scrobble",
                    sessionKey = requireSessionKey(),
                    extra =
                        buildMap {
                            submissions.forEachIndexed { index, item ->
                                put("artist[$index]", item.artist)
                                put("track[$index]", item.track)
                                put("timestamp[$index]", item.timestamp.toString())
                                item.album?.takeIf { it.isNotBlank() }?.let { put("album[$index]", it) }
                                item.albumArtist?.takeIf { it.isNotBlank() }?.let { put("albumArtist[$index]", it) }
                                item.trackNumber?.takeIf { it > 0 }?.let { put("trackNumber[$index]", it.toString()) }
                                item.duration?.takeIf { it > 0 }?.let { put("duration[$index]", it.toString()) }
                                if (item.chosenByUser) put("chosenByUser[$index]", "1")
                            }
                        },
                )
            parseScrobbleBatchResult(responseText, submissions.size)
        }

    suspend fun setLoveStatus(
        artist: String,
        track: String,
        love: Boolean,
    ) = runCatching {
        postAndRead(
            method = if (love) "track.love" else "track.unlove",
            sessionKey = requireSessionKey(),
            extra = mapOf("artist" to artist, "track" to track),
        )
        Unit
    }

    private fun parseScrobbleBatchResult(
        responseText: String,
        submitted: Int,
    ): ScrobbleBatchResult {
        val fallback = ScrobbleBatchResult(accepted = submitted, ignored = 0, ignoredCodes = emptyList())
        return runCatching {
            val scrobbles = json.parseToJsonElement(responseText).jsonObject["scrobbles"] as? JsonObject ?: return fallback
            // One entry arrives as an object, several as an array.
            val entries =
                when (val entry = scrobbles["scrobble"]) {
                    is JsonArray -> entry.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(entry)
                    else -> emptyList()
                }
            val ignoredCodes =
                entries.mapNotNull { entry ->
                    val ignored = entry["ignoredMessage"] as? JsonObject ?: return@mapNotNull null
                    ignored["code"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it != 0 }
                }
            val attr = scrobbles["@attr"] as? JsonObject
            val ignored = attr?.get("ignored")?.jsonPrimitive?.content?.toIntOrNull() ?: ignoredCodes.size
            val accepted = attr?.get("accepted")?.jsonPrimitive?.content?.toIntOrNull() ?: (submitted - ignored)
            ScrobbleBatchResult(accepted = accepted, ignored = ignored, ignoredCodes = ignoredCodes)
        }.getOrDefault(fallback)
    }

    // --- Dashboard data fetchers -------------------------------------------

    /**
     * Fetches the user's profile info (playcount, registration date,
     * avatar, etc.) for the Last.fm dashboard. Requires the user to be
     * logged in (a session key is required by the dashboard contract,
     * though the underlying Last.fm API endpoint technically allows
     * unauthenticated reads; we keep it consistent with the rest of
     * the dashboard).
     */
    suspend fun getUserInfo(username: String) =
        runCatching {
            postAndDecode<UserInfoResponse>(
                method = "user.getInfo",
                extra = mapOf("user" to username),
            ).user
        }

    /**
     * Fetches the user's recent listening history for the dashboard.
     * Limit defaults to 50 (Last.fm's max per page).
     */
    suspend fun getRecentTracks(
        username: String,
        limit: Int = 50,
        page: Int = 1,
    ) = runCatching {
        postAndDecode<RecentTracksResponse>(
            method = "user.getRecentTracks",
            extra =
                buildMap {
                    put("user", username)
                    put("limit", limit.toString())
                    put("page", page.toString())
                },
        )
    }

    /**
     * Fetches the user's all-time top tracks for the dashboard.
     */
    suspend fun getTopTracks(
        username: String,
        period: String = "overall",
        limit: Int = 50,
        page: Int = 1,
    ) = runCatching {
        postAndDecode<TopTracksResponse>(
            method = "user.getTopTracks",
            extra =
                buildMap {
                    put("user", username)
                    put("period", period)
                    put("limit", limit.toString())
                    put("page", page.toString())
                },
        )
    }

    suspend fun getTopArtists(
        username: String,
        period: String = "overall",
        limit: Int = 50,
        page: Int = 1,
    ) = runCatching {
        postAndDecode<TopArtistsResponse>(
            method = "user.getTopArtists",
            extra =
                buildMap {
                    put("user", username)
                    put("period", period)
                    put("limit", limit.toString())
                    put("page", page.toString())
                },
        )
    }

    suspend fun getTopAlbums(
        username: String,
        period: String = "overall",
        limit: Int = 50,
        page: Int = 1,
    ) = runCatching {
        postAndDecode<TopAlbumsResponse>(
            method = "user.getTopAlbums",
            extra =
                buildMap {
                    put("user", username)
                    put("period", period)
                    put("limit", limit.toString())
                    put("page", page.toString())
                },
        )
    }

    /** Fetches the community tags for a track shown in the dashboard overflow menu. */
    suspend fun getTrackInfo(
        artist: String,
        track: String,
    ) = runCatching {
        postAndDecode<TrackInfoResponse>(
            method = "track.getInfo",
            extra = mapOf("artist" to artist, "track" to track),
        ).track
    }

    fun initialize(
        apiKey: String,
        secret: String,
    ) {
        configure(
            endpoint = runtimeConfig.endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = runtimeConfig.sessionKey,
        )
    }

    fun configure(
        endpoint: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = runtimeConfig.sessionKey,
    ) {
        runtimeConfig =
            RuntimeConfig(
                endpoint = normalizeEndpoint(endpoint),
                apiKey = apiKey,
                secret = secret,
                sessionKey = sessionKey,
            )
    }

    fun currentConfig(): RuntimeConfig = runtimeConfig

    fun isInitialized(): Boolean = runtimeConfig.apiKey.isNotEmpty() && runtimeConfig.secret.isNotEmpty()

    fun normalizeEndpoint(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https")
        require(!uri.host.isNullOrBlank())
        require(uri.query == null && uri.fragment == null)

        val path =
            uri.path
                ?.takeIf { it.isNotBlank() && it != "/" }
                ?.trimEnd('/')
                ?: "/2.0"

        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "$path/",
            null,
            null,
        ).toString()
    }

    // isApiError = false: a locally missing key must not be mistaken for the server revoking one.
    private fun requireSessionKey(): String = runtimeConfig.sessionKey ?: throw LastFmException(9, "Session key missing", isApiError = false)

    private suspend inline fun <reified T> postAndDecode(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): T =
        json.decodeFromString(
            postAndRead(
                method = method,
                sessionKey = sessionKey,
                extra = extra,
            ),
        )

    private suspend fun postAndRead(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val config = runtimeConfig
        val response =
            client.post(config.endpoint) {
                lastfmParams(
                    method = method,
                    apiKey = config.apiKey,
                    secret = config.secret,
                    sessionKey = sessionKey,
                    extra = extra,
                )
            }

        val responseText = response.bodyAsText()
        val httpStatus = response.status.value.takeUnless { response.status.isSuccess() }
        // Last.fm sends API errors (invalid session, rate limit) with 4xx/5xx statuses, so the body
        // has to be read before the status or code 9 would surface as a bare 403.
        apiErrorOrNull(responseText)?.let { error ->
            throw LastFmException(error.error, error.message, httpStatus)
        }
        if (httpStatus != null) {
            throw LastFmException(httpStatus, response.status.description, httpStatus, isApiError = false)
        }
        return responseText
    }

    private fun apiErrorOrNull(responseText: String): LastFmError? {
        val body = runCatching { json.parseToJsonElement(responseText) as? JsonObject }.getOrNull() ?: return null
        val code = body["error"]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: return null
        val message = body["message"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        return LastFmError(code, message)
    }

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
}
