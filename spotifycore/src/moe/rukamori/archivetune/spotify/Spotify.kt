/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.spotify

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyImage
import moe.rukamori.archivetune.spotify.models.SpotifyPaging
import moe.rukamori.archivetune.spotify.models.SpotifyPlayHistory
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistOwner
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef
import moe.rukamori.archivetune.spotify.models.SpotifyRecommendations
import moe.rukamori.archivetune.spotify.models.SpotifySavedTrack
import moe.rukamori.archivetune.spotify.models.SpotifySearchResult
import moe.rukamori.archivetune.spotify.models.SpotifySimpleAlbum
import moe.rukamori.archivetune.spotify.models.SpotifySimpleArtist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.spotify.models.SpotifyUser

/**
 * Spotify API client that uses the internal GraphQL API (api-partner.spotify.com) for most
 * operations, falling back to the public REST API (api.spotify.com/v1/) only for endpoints without
 * a GraphQL equivalent (top tracks/artists, recommendations, related artists).
 */
object Spotify {
    @Volatile
    var accessToken: String? = null

    private val graphQlCooldown = SpotifyRequestCooldown()

    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"

    private fun randomUserAgent(): String {
        val osOptions =
            arrayOf(
                "Windows NT 10.0; Win64; x64",
                "Macintosh; Intel Mac OS X 10_15_7",
                "X11; Linux x86_64",
            )
        val chromeBase = 140
        val chromeMajor = chromeBase - (0..4).random()
        val chromePatch = (0..499).random()
        val os = osOptions.random()
        return "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.$chromePatch.0 Safari/537.36"
    }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    private val restClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                url("https://api.spotify.com/v1/")
                header("User-Agent", randomUserAgent())
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
            }
            expectSuccess = false
        }
    }

    private val gqlClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            defaultRequest {
                header("User-Agent", randomUserAgent())
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("Accept", "application/json")
            }
            expectSuccess = false
        }
    }

    class SpotifyException(
        val statusCode: Int,
        override val message: String,
        val retryAfterSec: Long = 0,
        cause: Throwable? = null,
        val quotaExceeded: Boolean = false,
    ) : Exception(message, cause)

    @Volatile
    var logger: ((level: String, message: String) -> Unit)? = null

    private fun log(
        level: String,
        message: String,
    ) {
        logger?.invoke(level, message)
    }

    // ── JSON navigation helpers ──────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonObject
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.str(key: String): String? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.int(key: String): Int? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.arr(key: String): JsonArray? =
        try {
            this[key]?.takeIf { it !is JsonNull }?.jsonArray
        } catch (_: Exception) {
            null
        }

    // ── GraphQL core ─────────────────────────────────────────────────────

    /**
     * Callback invoked when a GQL hash is rejected (PersistedQueryNotFound).
     * The app module sets this to trigger a remote hash refresh.
     */
    @Volatile
    var onHashExpired: ((operationName: String) -> Unit)? = null

    private suspend fun graphqlPost(
        operationName: String,
        variables: JsonObject = buildJsonObject {},
    ): JsonObject {
        val token =
            accessToken ?: throw SpotifyException(401, "Not authenticated").also {
                log("E", "GQL $operationName — no token")
            }

        val primaryHash = SpotifyHashProvider.getHash(operationName)
        val hashCandidates =
            buildList {
                add(primaryHash)
                SpotifyHashProvider.getPreviousHash(operationName)?.let { prev ->
                    if (prev != primaryHash) add(prev)
                }
            }

        for ((hashIdx, sha256Hash) in hashCandidates.withIndex()) {
            val body = buildGqlBody(operationName, sha256Hash, variables)
            val result = executeGqlRequest(operationName, token, body)

            if (result.isPersistedQueryNotFound) {
                if (hashIdx < hashCandidates.lastIndex) {
                    log("W", "GQL $operationName hash rejected, trying previous_hash")
                    continue
                }
                log("E", "GQL $operationName all known hashes rejected, triggering remote refresh")
                onHashExpired?.invoke(operationName)
                throw SpotifyException(412, "PersistedQueryNotFound for $operationName — hash may have rotated")
            }

            return result.json!!
        }

        throw SpotifyException(412, "No valid hash found for $operationName")
    }

    private fun buildGqlBody(
        operationName: String,
        sha256Hash: String,
        variables: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("variables", variables)
            put("operationName", operationName)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", sha256Hash)
                }
            }
        }

    private class GqlResult(
        val json: JsonObject?,
        val isPersistedQueryNotFound: Boolean,
    )

    private suspend fun executeGqlRequest(
        operationName: String,
        token: String,
        body: JsonObject,
    ): GqlResult {
        val blockedSeconds = graphQlCooldown.remainingSeconds()
        if (blockedSeconds > 0L) {
            throw SpotifyException(429, "Rate limited", retryAfterSec = blockedSeconds, quotaExceeded = graphQlCooldown.quotaExceeded)
        }
        log("D", "GQL POST $operationName")

        val response =
            gqlClient.post(GQL_URL) {
                header("Authorization", "Bearer $token")
                setBody(
                    TextContent(
                        body.toString(),
                        ContentType.Application.Json.withParameter("charset", "UTF-8"),
                    ),
                )
            }

        log("D", "GQL POST $operationName -> ${response.status.value}")

        if (response.status == HttpStatusCode.Unauthorized) {
            throw SpotifyException(401, "Token expired or invalid")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            val quotaExceeded = isSpotifyQuotaExceededResponse(response.bodyAsText())
            val retryAfter = graphQlCooldown.recordRateLimit(response.headers["Retry-After"], quotaExceeded)
            log("W", "GQL $operationName -> 429, GraphQL calls are blocked for ${retryAfter}s")
            throw SpotifyException(
                429,
                if (quotaExceeded) "Rate limited: Spotify API quota exceeded" else "Rate limited",
                retryAfterSec = retryAfter,
                quotaExceeded = quotaExceeded,
            )
        }
        if (response.status == HttpStatusCode.PreconditionFailed) {
            return GqlResult(json = null, isPersistedQueryNotFound = true)
        }
        if (response.status.value !in 200..299) {
            val bodyText = response.bodyAsText()
            log("E", "GQL $operationName FAILED: ${response.status.value} — ${bodyText.take(200)}")
            throw SpotifyException(response.status.value, "GraphQL error ${response.status.value}: $bodyText")
        }

        val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject

        val errors = responseJson.arr("errors")
        if (errors != null && errors.isNotEmpty()) {
            val errorMsg = errors[0].jsonObject.str("message") ?: "Unknown GraphQL error"
            if (errorMsg.contains("PersistedQueryNotFound", ignoreCase = true)) {
                return GqlResult(json = null, isPersistedQueryNotFound = true)
            }
            log("E", "GQL $operationName returned error: $errorMsg")
            throw SpotifyException(400, "GraphQL: $errorMsg")
        }

        return GqlResult(json = responseJson, isPersistedQueryNotFound = false)
    }

    // ── REST core (fallback for endpoints without GQL equivalent) ────────

    /**
     * Wall-clock deadline shared by every `api.spotify.com` call, set from a 429's `Retry-After`.
     *
     * Spotify's Web API limit is per **app**, not per endpoint or per user — the docs count "the
     * number of calls that your app makes ... in a rolling 30 second window" — so a 429 on one
     * endpoint means the next call to any of them is likely to be refused too. Gating the whole REST
     * surface on the first 429 is what stops the app spending the rest of its budget on calls that
     * cannot succeed; GraphQL (`api-partner`) is unaffected, which is why everything with a GQL
     * equivalent uses it.
     *
     * The docs also admit a few endpoints carry a custom limit of their own: a 429 does not say which
     * limit it hit, so this gate can occasionally be broader than necessary — which is the safe
     * direction.
     *
     * The gate is the only 429 retry policy there is: a request that finds the gate armed is not
     * sent, and a 429 arms it for at least the documented window, so retrying inside the window
     * cannot succeed and is not attempted.
     */
    private val restCooldown = SpotifyRequestCooldown()

    private fun restCooldownSecondsRemaining(): Long =
        restCooldown.remainingSeconds()

    private suspend inline fun <reified T> authenticatedGet(
        endpoint: String,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val token =
            accessToken ?: throw SpotifyException(401, "Not authenticated").also {
                log("E", "REST $endpoint — no token")
            }

        val blockedSec = restCooldownSecondsRemaining()
        if (blockedSec > 0) {
            log("W", "REST $endpoint — skipping, app-wide REST cooldown has ${blockedSec}s left")
            throw SpotifyException(429, "Rate limited", retryAfterSec = blockedSec, quotaExceeded = restCooldown.quotaExceeded)
        }

        log("D", "REST GET $endpoint")
        val response =
            restClient.get(endpoint) {
                header("Authorization", "Bearer $token")
                block()
            }
        log("D", "REST GET $endpoint -> ${response.status.value}")

        if (response.status == HttpStatusCode.Unauthorized) {
            throw SpotifyException(401, "Token expired or invalid")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            val quotaExceeded = isSpotifyQuotaExceededResponse(response.bodyAsText())
            val remainingSec = restCooldown.recordRateLimit(response.headers["Retry-After"], quotaExceeded)
            log("W", "REST $endpoint -> 429, every REST call is blocked for ${remainingSec}s")
            throw SpotifyException(
                429,
                if (quotaExceeded) "Rate limited: Spotify API quota exceeded" else "Rate limited",
                retryAfterSec = remainingSec,
                quotaExceeded = quotaExceeded,
            )
        }
        if (response.status.value !in 200..299) {
            val bodyText = response.bodyAsText()
            log("E", "REST $endpoint FAILED: ${response.status.value} — ${bodyText.take(200)}")
            throw SpotifyException(response.status.value, "Spotify API error ${response.status.value}: $bodyText")
        }
        return response.body()
    }

    // ── GQL response converters ──────────────────────────────────────────

    private fun parseGqlImage(source: JsonObject): SpotifyImage? {
        val url = source.str("url") ?: return null
        return SpotifyImage(url = url, height = source.int("height"), width = source.int("width"))
    }

    private fun parseGqlImages(sources: JsonArray?): List<SpotifyImage> =
        sources?.mapNotNull { parseGqlImage(it.jsonObject) } ?: emptyList()

    /** The URL of the largest image in a GraphQL `sources` array. */
    private fun largestGqlSourceUrl(sources: JsonArray?): String? {
        val entries = sources?.mapNotNull { it.jsonObject.takeIf { obj -> obj.str("url") != null } }
        if (entries.isNullOrEmpty()) return null
        val widest = entries.maxByOrNull { it.int("width") ?: 0 }
        return (widest ?: entries.first()).str("url")
    }

    private fun parseGqlSimpleArtist(artistObj: JsonObject): SpotifySimpleArtist? {
        val uri = artistObj.str("uri") ?: return null
        return SpotifySimpleArtist(
            id = uri.substringAfterLast(":"),
            name = artistObj.obj("profile")?.str("name") ?: "",
            uri = uri,
        )
    }

    /**
     * Parses the common track data structure shared across multiple GQL operations (fetchPlaylist,
     * fetchLibraryTracks, queryArtistOverview, etc.).
     */
    private fun parseGqlTrack(
        trackData: JsonObject,
        albumOverride: SpotifySimpleAlbum? = null,
        uriOverride: String? = null,
    ): SpotifyTrack {
        val uri =
            uriOverride
                ?: trackData.str("uri")
                ?: trackData.str("_uri")
                ?: ""
        val trackId = uri.substringAfterLast(":")

        val artists =
            trackData.obj("artists")?.arr("items")?.mapNotNull { elem ->
                parseGqlSimpleArtist(elem.jsonObject)
            } ?: emptyList()

        val album =
            albumOverride ?: run {
                val albumData = trackData.obj("albumOfTrack")
                val albumUri = albumData?.str("uri") ?: ""
                val albumId = albumUri.substringAfterLast(":")
                SpotifySimpleAlbum(
                    id = albumId,
                    name = albumData?.str("name") ?: "",
                    images = parseGqlImages(albumData?.obj("coverArt")?.arr("sources")),
                    uri = albumUri.ifEmpty { null },
                )
            }

        return SpotifyTrack(
            id = trackId,
            name = trackData.str("name") ?: "",
            artists = artists,
            album = album,
            durationMs = parseGqlTrackDurationMs(trackData),
            explicit = spotifyReportedExplicit(trackData),
            externalIds = spotifyReportedExternalIds(trackData),
            uri = uri.ifEmpty { null },
        )
    }

    /**
     * Extracts track duration in ms from GQL track payload.
     * Tries multiple keys because different operations may return duration
     * as nested (duration.totalMilliseconds) or flat (durationMs / duration_ms).
     */
    private fun parseGqlTrackDurationMs(trackData: JsonObject): Int {
        trackData.obj("duration")?.int("totalMilliseconds")?.let { if (it > 0) return it }
        trackData.int("durationMs")?.let { if (it > 0) return it }
        trackData.int("duration_ms")?.let { if (it > 0) return it }
        // Some APIs return duration in seconds
        trackData.int("duration")?.let { sec -> if (sec > 0) return sec * 1000 }
        return 0
    }

    /**
     * Flattens the nested `images.items[].sources[]` structure used by
     * playlists in the GQL response.
     */
    private fun parseGqlPlaylistImages(imagesObj: JsonObject?): List<SpotifyImage> =
        imagesObj?.arr("items")?.flatMap { imageGroup ->
            parseGqlImages(imageGroup.jsonObject.arr("sources"))
        } ?: emptyList()

    // ── User Profile (GQL with REST fallback) ──────────────────────────

    suspend fun me(): Result<SpotifyUser> =
        spotifyRequestResult {
            spotifyGraphQlWithRestFallback(
                graphQl = {
                    val response = graphqlPost(operationName = "profileAttributes")
                    val profile = response.obj("data")?.obj("me")?.obj("profile")
                        ?: throw SpotifyException(500, "Invalid profileAttributes response")
                    val uri = profile.str("uri") ?: ""
                    SpotifyUser(
                        id = uri.substringAfterLast(":"),
                        displayName = profile.str("name"),
                        email = null,
                        images = parseGqlImages(profile.obj("avatar")?.arr("sources")),
                    )
                },
                rest = { authenticatedGet<SpotifyUser>("me") },
            )
        }

    // ── Playlists (GQL: libraryV3) ──────────────────────────────────────

    suspend fun myPlaylists(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylist>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Playlists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    // Ask Spotify to return every leaf playlist regardless of folder
                    // nesting. Without flatten=true the response only contains root-
                    // level items: top-level playlists plus FolderResponseWrapper
                    // entries whose contents are never expanded — the parser below
                    // ignores non-PlaylistResponseWrapper items, so anything inside
                    // a folder would otherwise be invisible (issues #46, #78).
                    put("flatten", true)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", false)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val playlists =
                libraryData.arr("items")?.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    if (wrapper.str("__typename") != "PlaylistResponseWrapper") return@mapNotNull null
                    parsePlaylistWrapper(wrapper)
                } ?: emptyList()

            SpotifyPaging(
                items = playlists,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    // ── Library hierarchy (GQL: libraryV3, folders preserved) ───────────

    /**
     * Returns one level of the user's library tree. When [folderUri] is null the response is the
     * library root: top-level playlists plus folder containers. When [folderUri] is set, Spotify
     * treats that folder as the root and returns its direct children (which may include
     * sub-folders).
     */
    suspend fun myLibraryNode(
        folderUri: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<moe.rukamori.archivetune.spotify.models.SpotifyLibraryItem>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Playlists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    // flatten=false preserves folder boundaries; folderUri scopes
                    // the response to a single level (null = root).
                    put("flatten", false)
                    putJsonArray("expandedFolders") {}
                    if (folderUri != null) put("folderUri", folderUri) else put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", true)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val rawItems = libraryData.arr("items").orEmpty()
            // Diagnostic: dump every wrapper's __typename so we can spot any
            // schema variation Spotify ships (the names have shifted historically).
            // Trim once we're confident the recognized set is stable.
            log("D", "myLibraryNode(folder=$folderUri): ${rawItems.size} raw items")
            val typeCounts = mutableMapOf<String, Int>()
            rawItems.forEach { itemElem ->
                val wrapper = itemElem.jsonObject.obj("item")
                val tn = wrapper?.str("__typename") ?: "<missing>"
                typeCounts[tn] = (typeCounts[tn] ?: 0) + 1
            }
            log("D", "myLibraryNode: typename counts = $typeCounts")

            val items =
                rawItems.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    when {
                        typeName == "PlaylistResponseWrapper" || typeName.contains("Playlist", ignoreCase = true) -> {
                            parsePlaylistWrapper(wrapper)
                                ?.let {
                                    moe.rukamori.archivetune.spotify.models.SpotifyLibraryItem
                                        .Playlist(it)
                                }
                        }

                        typeName == "FolderResponseWrapper" || typeName.contains("Folder", ignoreCase = true) -> {
                            parseFolderWrapper(wrapper)
                                ?.let {
                                    moe.rukamori.archivetune.spotify.models.SpotifyLibraryItem
                                        .Folder(it)
                                }
                                ?: run {
                                    // Folder typename matched but parsing returned null —
                                    // likely a shape we don't know. Dump the keys so we
                                    // can update parseFolderWrapper.
                                    log(
                                        "W",
                                        "myLibraryNode: failed to parse folder wrapper, keys=${wrapper.keys}, dataKeys=${wrapper.obj(
                                            "data",
                                        )?.keys}",
                                    )
                                    null
                                }
                        }

                        else -> {
                            log("D", "myLibraryNode: skipping unknown __typename='$typeName'")
                            null
                        }
                    }
                }

            SpotifyPaging(
                items = items,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    private fun parsePlaylistWrapper(wrapper: JsonObject): SpotifyPlaylist? {
        val data = wrapper.obj("data") ?: return null
        if (data.str("__typename") != "Playlist") return null
        val playlistUri = wrapper.str("_uri") ?: return null
        val playlistId = playlistUri.substringAfterLast(":")
        val ownerData = data.obj("ownerV2")?.obj("data")
        val ownerId = ownerData?.str("uri")?.substringAfterLast(":") ?: ownerData?.str("id") ?: ""
        return SpotifyPlaylist(
            id = playlistId,
            name = data.str("name") ?: "",
            description = data.str("description"),
            images = parseGqlPlaylistImages(data.obj("images")),
            owner =
                SpotifyPlaylistOwner(
                    id = ownerId,
                    displayName = ownerData?.str("name"),
                    uri = ownerData?.str("uri"),
                ),
            tracks = SpotifyPlaylistTracksRef(total = parsePlaylistTrackCount(data)),
            uri = playlistUri,
        )
    }

    private fun parsePlaylistTrackCount(data: JsonObject): Int? =
        data.obj("content")?.int("totalCount")
            ?: data.obj("contents")?.int("totalCount")
            ?: data.obj("tracks")?.int("totalCount")
            ?: data.obj("tracksV2")?.int("totalCount")
            ?: data.int("totalCount")
            ?: data.int("trackCount")
            ?: data.int("numTracks")

    private fun parseFolderWrapper(wrapper: JsonObject): moe.rukamori.archivetune.spotify.models.SpotifyLibraryFolder? {
        val uri = wrapper.str("_uri") ?: return null
        // Spotify has shipped this object under several shapes over time; the name
        // and child count have lived in `data` and at the root of the wrapper.
        // Try both so we don't break on a future field reshuffle.
        val name =
            wrapper.obj("data")?.str("name")
                ?: wrapper.str("name")
                ?: return null
        val total =
            wrapper.obj("data")?.int("totalLength")
                ?: wrapper.obj("data")?.int("numberOfItems")
                ?: wrapper.int("totalLength")
                ?: 0
        return moe.rukamori.archivetune.spotify.models.SpotifyLibraryFolder(
            uri = uri,
            name = name,
            totalChildren = total,
        )
    }

    // ── Library Artists (GQL: libraryV3 with Artists filter) ───────────

    suspend fun myArtists(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyArtist>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Artists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    put("flatten", false)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", true)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val artists =
                libraryData.arr("items")?.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    if (!typeName.contains("Artist", ignoreCase = true)) return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null

                    val artistUri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
                    val artistId = artistUri.substringAfterLast(":")

                    val name =
                        data.obj("profile")?.str("name")
                            ?: data.str("name")
                            ?: return@mapNotNull null

                    val images =
                        data
                            .obj("visuals")
                            ?.obj("avatarImage")
                            ?.arr("sources")
                            ?.let { parseGqlImages(it) }
                            ?: emptyList()

                    SpotifyArtist(
                        id = artistId,
                        name = name,
                        images = images,
                        uri = artistUri,
                    )
                } ?: emptyList()

            SpotifyPaging(
                items = artists,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
                rawItemCount = libraryData.arr("items")?.size ?: 0,
            )
        }

    // ── Library Albums (GQL: libraryV3 with Albums filter) ─────────────

    /**
     * The user's saved albums, straight from the same libraryV3 query [myArtists] uses — only the
     * filter differs. Written out rather than folded into one parameterised helper because the two
     * responses shape their item wrappers differently: an artist carries `profile.name` and an
     * avatar image, an album carries a name, its artists and cover art, and the union of both
     * inside one mapper reads worse than the duplication.
     */
    suspend fun myAlbums(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyAlbum>> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("filters") { add("Albums") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    put("flatten", false)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", true)
                }

            val response =
                graphqlPost(
                    operationName = "libraryV3",
                    variables = vars,
                )

            val libraryData =
                response.obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")

            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val albums =
                libraryData.arr("items")?.mapNotNull { itemElem ->
                    val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                    val typeName = wrapper.str("__typename") ?: ""
                    if (!typeName.contains("Album", ignoreCase = true)) return@mapNotNull null
                    val data = wrapper.obj("data") ?: return@mapNotNull null

                    val albumUri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
                    val albumId = albumUri.substringAfterLast(":")
                    val name = data.str("name") ?: return@mapNotNull null

                    val images =
                        data
                            .obj("coverArt")
                            ?.arr("sources")
                            ?.let { parseGqlImages(it) }
                            ?: emptyList()

                    val artists =
                        data
                            .obj("artists")
                            ?.arr("items")
                            ?.mapNotNull { parseGqlSimpleArtist(it.jsonObject) }
                            .orEmpty()

                    SpotifyAlbum(
                        id = albumId,
                        name = name,
                        artists = artists,
                        images = images,
                        uri = albumUri,
                    )
                } ?: emptyList()

            SpotifyPaging(
                items = albums,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
                rawItemCount = libraryData.arr("items")?.size ?: 0,
            )
        }

    // ── Playlist detail (GQL: fetchPlaylist) ────────────────────────────

    /**
     * A playlist's header fields and one page of its tracks, as one `fetchPlaylist` read returns
     * them.
     *
     * The operation carries both — [playlist] parses its header and [playlistTracks] its content —
     * so a screen that needs both can spend one round trip instead of two on the same document.
     */
    data class SpotifyPlaylistDetail(
        val playlist: SpotifyPlaylist,
        val tracks: SpotifyPaging<SpotifyPlaylistTrack>,
    )

    private fun fetchPlaylistVariables(
        playlistId: String,
        limit: Int,
        offset: Int,
        watchFeedEntrypoint: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", limit)
            put("enableWatchFeedEntrypoint", watchFeedEntrypoint)
        }

    suspend fun playlist(playlistId: String): Result<SpotifyPlaylist> =
        runCatching {
            val response =
                graphqlPost(
                    operationName = "fetchPlaylist",
                    variables = fetchPlaylistVariables(playlistId, limit = 25, offset = 0, watchFeedEntrypoint = true),
                )

            val playlist =
                response.obj("data")?.obj("playlistV2")
                    ?: throw SpotifyException(500, "Invalid fetchPlaylist response")

            parsePlaylistV2(playlistId, playlist)
        }

    /**
     * The playlist's header fields and its first page of tracks from a single response.
     *
     * The variables are the track reader's, so the page arrives exactly as [playlistTracks]
     * delivers it, and the header fields ride along because the persisted query — not its
     * arguments — selects them. A response missing either part fails here under the message the
     * reader that owns that part uses, so nothing reports a shape problem as an empty playlist.
     */
    suspend fun playlistDetail(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPlaylistDetail> =
        runCatching {
            val response =
                graphqlPost(
                    operationName = "fetchPlaylist",
                    variables = fetchPlaylistVariables(playlistId, limit, offset, watchFeedEntrypoint = false),
                )

            val playlist =
                response.obj("data")?.obj("playlistV2")
                    ?: throw SpotifyException(500, "Invalid fetchPlaylist response")
            val content =
                playlist.obj("content")
                    ?: throw SpotifyException(500, "No content in fetchPlaylist response")

            SpotifyPlaylistDetail(
                playlist = parsePlaylistV2(playlistId, playlist),
                tracks = parsePlaylistTracksPage(content, limit, offset),
            )
        }

    suspend fun playlistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylistTrack>> =
        runCatching {
            val response =
                graphqlPost(
                    operationName = "fetchPlaylist",
                    variables = fetchPlaylistVariables(playlistId, limit, offset, watchFeedEntrypoint = false),
                )

            val content =
                response.obj("data")?.obj("playlistV2")?.obj("content")
                    ?: throw SpotifyException(500, "No content in fetchPlaylist response")

            parsePlaylistTracksPage(content, limit, offset)
        }

    private fun parsePlaylistV2(
        playlistId: String,
        playlist: JsonObject,
    ): SpotifyPlaylist {
        val ownerData = playlist.obj("ownerV2")?.obj("data")
        val ownerUri = ownerData?.str("uri") ?: ""

        val images =
            playlist.obj("images")?.arr("items")?.firstOrNull()?.let {
                parseGqlImages(it.jsonObject.arr("sources"))
            } ?: emptyList()

        return SpotifyPlaylist(
            id = playlistId,
            name = playlist.str("name") ?: "",
            description = playlist.str("description"),
            images = images,
            owner =
                SpotifyPlaylistOwner(
                    id = ownerUri.substringAfterLast(":"),
                    displayName = ownerData?.str("name"),
                    uri = ownerUri.ifEmpty { null },
                ),
            tracks = SpotifyPlaylistTracksRef(total = parsePlaylistTrackCount(playlist)),
            collaborative = (playlist.obj("members")?.arr("items")?.size ?: 0) > 1,
        )
    }

    private fun parsePlaylistTracksPage(
        content: JsonObject,
        limit: Int,
        offset: Int,
    ): SpotifyPaging<SpotifyPlaylistTrack> {
        val tracks =
            content.arr("items")?.mapNotNull { elem ->
                val itemWrapper = elem.jsonObject.obj("itemV2") ?: return@mapNotNull null
                val itemData = itemWrapper.obj("data") ?: return@mapNotNull null
                val wrapperUri = itemWrapper.str("_uri") ?: itemWrapper.str("uri")
                val uid = elem.jsonObject.str("uid") ?: itemWrapper.str("uid")
                SpotifyPlaylistTrack(
                    track = parseGqlTrack(itemData, uriOverride = wrapperUri),
                    uid = uid,
                )
            } ?: emptyList()

        return SpotifyPaging(
            items = tracks,
            total = content.int("totalCount") ?: 0,
            limit = limit,
            offset = offset,
            rawItemCount = content.arr("items")?.size ?: 0,
        )
    }

    // ── Playlist Mutations (GQL) ──────────────────────────────────────

    /**
     * Adds tracks to a Spotify playlist via GQL mutation.
     * @param playlistId Playlist ID (without the `spotify:playlist:` prefix).
     * @param trackUris Full Spotify URIs, e.g. `["spotify:track:abc123"]`.
     */
    suspend fun addTracksToPlaylist(
        playlistId: String,
        trackUris: List<String>,
    ): Result<Unit> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("playlistUri", "spotify:playlist:$playlistId")
                    putJsonArray("playlistItemUris") {
                        trackUris.forEach { add(it) }
                    }
                    putJsonObject("newPosition") {
                        put("moveType", "BOTTOM_OF_PLAYLIST")
                        put("fromUid", JsonNull)
                    }
                }
            log("D", "addTracksToPlaylist: sending mutation for $playlistId with ${trackUris.size} tracks, vars=$vars")
            kotlinx.coroutines.withTimeout(20_000L) {
                graphqlPost(
                    operationName = "addToPlaylist",
                    variables = vars,
                )
            }
            log("D", "addTracksToPlaylist: added ${trackUris.size} tracks to $playlistId")
        }

    /**
     * Removes tracks from a Spotify playlist via GQL mutation.
     * Requires the playlist-scoped [uid] for each item
     * (returned by fetchPlaylist in each content item).
     */
    suspend fun removeTracksFromPlaylist(
        playlistId: String,
        items: List<PlaylistItemRef>,
    ): Result<Unit> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("playlistUri", "spotify:playlist:$playlistId")
                    putJsonArray("uids") {
                        items.forEach { item -> add(item.uid) }
                    }
                }
            graphqlPost(
                operationName = "removeFromPlaylist",
                variables = vars,
            )
            log("D", "removeTracksFromPlaylist: removed ${items.size} items from $playlistId")
        }

    /**
     * Moves items within a Spotify playlist via GQL mutation.
     * [uids] are playlist-scoped item identifiers returned by fetchPlaylist.
     * [beforeUid] is the uid of the item the moved items should be placed before,
     * or null to move to the end of the playlist.
     */
    suspend fun moveItemsInPlaylist(
        playlistId: String,
        uids: List<String>,
        beforeUid: String?,
    ): Result<Unit> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("playlistUri", "spotify:playlist:$playlistId")
                    putJsonArray("uids") {
                        uids.forEach { add(it) }
                    }
                    putJsonObject("newPosition") {
                        if (beforeUid != null) {
                            put("moveType", "BEFORE_UID")
                            put("fromUid", beforeUid)
                        } else {
                            put("moveType", "BOTTOM_OF_PLAYLIST")
                            put("fromUid", JsonNull)
                        }
                    }
                }
            graphqlPost(
                operationName = "moveItemsInPlaylist",
                variables = vars,
            )
            log("D", "moveItemsInPlaylist: moved ${uids.size} items (before=$beforeUid) in $playlistId")
        }

    /**
     * Renames a playlist and/or updates its description via GQL mutation.
     */
    suspend fun editPlaylistAttributes(
        playlistId: String,
        newName: String? = null,
        newDescription: String? = null,
    ): Result<Unit> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("playlistUri", "spotify:playlist:$playlistId")
                    if (newName != null) put("newName", newName)
                    if (newDescription != null) put("newDescription", newDescription)
                }
            graphqlPost(
                operationName = "editPlaylistAttributes",
                variables = vars,
            )
            log("D", "editPlaylistAttributes: updated $playlistId (name=$newName)")
        }

    /**
     * Reference to a specific item inside a playlist, needed for removal/reorder.
     */
    data class PlaylistItemRef(
        val uri: String,
        val uid: String,
    )

    // ── Liked Songs (GQL: fetchLibraryTracks) ───────────────────────────

    suspend fun likedSongs(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifySavedTrack>> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                }

            val response =
                graphqlPost(
                    operationName = "fetchLibraryTracks",
                    variables = vars,
                )

            val tracksData =
                response
                    .obj("data")
                    ?.obj("me")
                    ?.obj("library")
                    ?.obj("tracks")
                    ?: throw SpotifyException(500, "Invalid fetchLibraryTracks response")

            val savedTracks =
                tracksData.arr("items")?.mapNotNull { elem ->
                    val trackWrapper = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    val trackData = trackWrapper.obj("data") ?: return@mapNotNull null
                    val wrapperUri = trackWrapper.str("_uri") ?: trackWrapper.str("uri")
                    SpotifySavedTrack(track = parseGqlTrack(trackData, uriOverride = wrapperUri))
                } ?: emptyList()

            SpotifyPaging(
                items = savedTracks,
                total = tracksData.int("totalCount") ?: 0,
                limit = limit,
                offset = offset,
                rawItemCount = tracksData.arr("items")?.size ?: 0,
            )
        }

    // ── Library Mutations (GQL: addToLibrary / removeFromLibrary) ──────

    /**
     * Saves tracks/albums/playlists to the user's Spotify library (like).
     * @param uris Full Spotify URIs, e.g. `["spotify:track:abc123"]`.
     */
    suspend fun addToLibrary(uris: List<String>): Result<Unit> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("libraryItemUris") {
                        uris.forEach { add(it) }
                    }
                }
            graphqlPost(
                operationName = "addToLibrary",
                variables = vars,
            )
            log("D", "addToLibrary: added ${uris.size} items")
        }

    /**
     * Removes tracks/albums/playlists from the user's Spotify library (unlike).
     * @param uris Full Spotify URIs, e.g. `["spotify:track:abc123"]`.
     */
    suspend fun removeFromLibrary(uris: List<String>): Result<Unit> =
        runCatching {
            val vars =
                buildJsonObject {
                    putJsonArray("libraryItemUris") {
                        uris.forEach { add(it) }
                    }
                }
            graphqlPost(
                operationName = "removeFromLibrary",
                variables = vars,
            )
            log("D", "removeFromLibrary: removed ${uris.size} items")
        }

    // ── Top Tracks (REST fallback — no GQL equivalent) ──────────────────

    suspend fun topTracks(
        timeRange: String = "medium_term",
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyTrack>> =
        runCatching {
            authenticatedGet("me/top/tracks") {
                parameter("time_range", timeRange)
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    // ── Recently played (REST — no GQL equivalent) ──────────────────────

    /**
     * The user's play history, most recent first. Spotify caps this at the last
     * [SPOTIFY_HISTORY_WINDOW] plays and pages it by timestamp cursor rather than offset, so there is
     * no `offset` here and no way to reach further back — the endpoint simply does not offer it.
     *
     * [afterMillis] is that cursor: a Unix millisecond timestamp, exclusive. Passing the newest play
     * the caller already holds asks for the plays since it, which is what keeps a refresh from
     * re-reading the whole window. `after` and `before` are mutually exclusive server-side, which is
     * why only one of them is ever sent.
     */
    suspend fun recentlyPlayed(
        limit: Int = SPOTIFY_HISTORY_WINDOW,
        afterMillis: Long? = null,
    ): Result<SpotifyPaging<SpotifyPlayHistory>> =
        runCatching {
            authenticatedGet("me/player/recently-played") {
                parameter("limit", limit.coerceIn(1, SPOTIFY_HISTORY_WINDOW))
                afterMillis?.let { parameter("after", it) }
            }
        }

    // ── Top Artists (REST fallback — no GQL equivalent) ─────────────────

    suspend fun topArtists(
        timeRange: String = "medium_term",
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyArtist>> =
        runCatching {
            authenticatedGet("me/top/artists") {
                parameter("time_range", timeRange)
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    // ── Recommendations (REST fallback — no GQL equivalent) ─────────────

    suspend fun recommendations(
        seedTrackIds: List<String> = emptyList(),
        seedArtistIds: List<String> = emptyList(),
        seedGenres: List<String> = emptyList(),
        limit: Int = 50,
    ): Result<SpotifyRecommendations> =
        runCatching {
            authenticatedGet("recommendations") {
                if (seedTrackIds.isNotEmpty()) parameter("seed_tracks", seedTrackIds.joinToString(","))
                if (seedArtistIds.isNotEmpty()) parameter("seed_artists", seedArtistIds.joinToString(","))
                if (seedGenres.isNotEmpty()) parameter("seed_genres", seedGenres.joinToString(","))
                parameter("limit", limit)
            }
        }

    // ── Search (GQL: searchDesktop) ────────────────���────────────────────

    suspend fun search(
        query: String,
        types: List<String> = listOf("track"),
        limit: Int = 20,
        offset: Int = 0,
    ): Result<SpotifySearchResult> =
        spotifyRequestResult {
            spotifyGraphQlWithRestFallback(
                graphQl = { hydrateSearchTracks(searchGraphQl(query, types, limit, offset)) },
                rest = { searchRest(query, types, limit, offset) },
            )
        }

    private suspend fun hydrateSearchTracks(result: SpotifySearchResult): SpotifySearchResult {
        val tracks = result.tracks?.items.orEmpty()
        if (tracks.isEmpty()) return result
        val ids = tracks.filter(SpotifyTrack::needsRestMetadata).mapNotNull { it.id.takeIf(String::isNotBlank) }.distinct()
        if (ids.isEmpty()) return result

        val hydrated =
            try {
                authenticatedGet<SpotifyTracksResponse>("tracks") {
                    parameter("ids", ids.joinToString(","))
                }.tracks
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // Search remains useful when Spotify's REST detail endpoint is unavailable; the GQL
                // payload still contains the identity fields needed by the UI and mapper.
                log("W", "Spotify track detail hydration failed: ${error.message}")
                return result
        }
        if (hydrated.isEmpty()) return result
        val byId = hydrated.associateBy { it.id }
        val currentTracks = result.tracks ?: return result
        return result.copy(
            tracks = currentTracks.copy(items = tracks.map { byId[it.id] ?: it }),
        )
    }

    private suspend fun searchGraphQl(
        query: String,
        types: List<String>,
        limit: Int,
        offset: Int,
    ): SpotifySearchResult {
        val requestedTypes = types.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val includeAllTypes = requestedTypes.isEmpty()
        val vars =
            buildJsonObject {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
                put("includeAuthors", false)
            }

        val response =
            graphqlPost(
                operationName = "searchDesktop",
                variables = vars,
            )

        val searchData =
            response.obj("data")?.obj("searchV2")
                ?: throw SpotifyException(500, "Invalid searchDesktop response")

        val tracksSection = searchData.obj("tracksV2")
        val trackItems =
            if (includeAllTypes || "track" in requestedTypes) {
                tracksSection?.arr("items")?.mapNotNull { elem ->
                    val raw = elem.jsonObject
                    val itemWrapper = raw.obj("item") ?: raw
                    val wrapperType = itemWrapper.str("__typename")
                    val data = itemWrapper.obj("data") ?: itemWrapper
                    val dataType = data.str("__typename")
                    if (wrapperType != null && !wrapperType.contains("Track", ignoreCase = true)) return@mapNotNull null
                    if (dataType != null && !dataType.equals("Track", ignoreCase = true)) return@mapNotNull null
                    val wrapperUri = itemWrapper.str("_uri") ?: itemWrapper.str("uri") ?: data.str("uri")
                    parseGqlTrack(data, uriOverride = wrapperUri)
                } ?: emptyList()
            } else {
                emptyList()
            }

        val albumsSection = searchData.obj("albumsV2")
        val albumItems =
            if (includeAllTypes || "album" in requestedTypes) {
                albumsSection?.arr("items")?.mapNotNull { elem ->
                    val raw = elem.jsonObject
                    val wrapper = raw.obj("item") ?: raw
                    val data = wrapper.obj("data") ?: wrapper
                    val wrapperType = wrapper.str("__typename")
                    val dataType = data.str("__typename")
                    if (wrapperType != null && !wrapperType.contains("Album", ignoreCase = true)) return@mapNotNull null
                    if (dataType != null && !dataType.equals("Album", ignoreCase = true)) return@mapNotNull null
                    parseGqlSearchAlbum(data)
                } ?: emptyList()
            } else {
                emptyList()
            }

        val artistsSection = searchData.obj("artists")
        val artistItems =
            if (includeAllTypes || "artist" in requestedTypes) {
                artistsSection?.arr("items")?.mapNotNull { elem ->
                    val raw = elem.jsonObject
                    val wrapper = raw.obj("item") ?: raw
                    val data = wrapper.obj("data") ?: wrapper
                    val wrapperType = wrapper.str("__typename")
                    val dataType = data.str("__typename")
                    if (wrapperType != null && !wrapperType.contains("Artist", ignoreCase = true)) return@mapNotNull null
                    if (dataType != null && !dataType.equals("Artist", ignoreCase = true)) return@mapNotNull null
                    parseGqlSearchArtist(data)
                } ?: emptyList()
            } else {
                emptyList()
            }

        val playlistsSection = searchData.obj("playlists")
        val playlistItems =
            if (includeAllTypes || "playlist" in requestedTypes) {
                playlistsSection?.arr("items")?.mapNotNull { elem ->
                    val raw = elem.jsonObject
                    val wrapper = raw.obj("item") ?: raw
                    val data = wrapper.obj("data") ?: wrapper
                    val wrapperType = wrapper.str("__typename")
                    val dataType = data.str("__typename")
                    if (wrapperType != null && !wrapperType.contains("Playlist", ignoreCase = true)) return@mapNotNull null
                    if (dataType != null && !dataType.equals("Playlist", ignoreCase = true)) return@mapNotNull null
                    parseGqlSearchPlaylist(data)
                } ?: emptyList()
            } else {
                emptyList()
            }

        val result =
            SpotifySearchResult(
                tracks =
                    if (includeAllTypes || "track" in requestedTypes) {
                        SpotifyPaging(
                            items = trackItems,
                            total = tracksSection?.int("totalCount") ?: 0,
                            limit = limit,
                            offset = offset,
                        )
                    } else {
                        null
                    },
                albums =
                    if (includeAllTypes || "album" in requestedTypes) {
                        SpotifyPaging(items = albumItems, total = albumsSection?.int("totalCount") ?: 0, limit = limit, offset = offset)
                    } else {
                        null
                    },
                artists =
                    if (includeAllTypes || "artist" in requestedTypes) {
                        SpotifyPaging(items = artistItems, total = artistsSection?.int("totalCount") ?: 0, limit = limit, offset = offset)
                    } else {
                        null
                    },
                playlists =
                    if (includeAllTypes || "playlist" in requestedTypes) {
                        SpotifyPaging(
                            items = playlistItems,
                            total = playlistsSection?.int("totalCount") ?: 0,
                            limit = limit,
                            offset = offset,
                        )
                    } else {
                        null
                    },
            )

        // A successful HTTP response with an unrecognised schema is indistinguishable from a real
        // empty result to callers. Let REST repair that case instead of showing a false "no results".
        if (query.isNotBlank() && !result.hasItems()) {
            throw SpotifyException(502, "Spotify search returned no parseable results")
        }
        return result
    }

    private suspend fun searchRest(
        query: String,
        types: List<String>,
        limit: Int,
        offset: Int,
    ): SpotifySearchResult =
        authenticatedGet("search") {
            parameter("q", query)
            parameter("type", types.joinToString(",").ifBlank { "track" })
            parameter("limit", limit.coerceIn(1, 50))
            parameter("offset", offset.coerceAtLeast(0))
        }

    private fun SpotifySearchResult.hasItems(): Boolean =
        tracks?.items?.isNotEmpty() == true ||
            albums?.items?.isNotEmpty() == true ||
            artists?.items?.isNotEmpty() == true ||
            playlists?.items?.isNotEmpty() == true

    private fun parseGqlSearchAlbum(data: JsonObject): SpotifyAlbum {
        val uri = data.str("uri") ?: ""
        return SpotifyAlbum(
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            albumType = data.str("type")?.lowercase(),
            artists =
                data.obj("artists")?.arr("items")?.mapNotNull {
                    parseGqlSimpleArtist(it.jsonObject)
                } ?: emptyList(),
            images = parseGqlImages(data.obj("coverArt")?.arr("sources")),
            releaseDate = data.obj("date")?.int("year")?.toString(),
            uri = uri.ifEmpty { null },
        )
    }

    private fun parseGqlSearchArtist(data: JsonObject): SpotifyArtist {
        val uri = data.str("uri") ?: ""
        return SpotifyArtist(
            id = uri.substringAfterLast(":"),
            name = data.obj("profile")?.str("name") ?: "",
            images = parseGqlImages(data.obj("visuals")?.obj("avatarImage")?.arr("sources")),
            uri = uri.ifEmpty { null },
        )
    }

    private fun parseGqlSearchPlaylist(data: JsonObject): SpotifyPlaylist {
        val uri = data.str("uri") ?: ""
        val ownerData = data.obj("ownerV2")?.obj("data")
        val ownerUri = ownerData?.str("uri") ?: ""

        return SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            description = data.str("description"),
            images = parseGqlPlaylistImages(data.obj("images")),
            owner =
                SpotifyPlaylistOwner(
                    id = ownerUri.substringAfterLast(":"),
                    displayName = ownerData?.str("name"),
                    uri = ownerUri.ifEmpty { null },
                ),
            uri = uri.ifEmpty { null },
        )
    }

    // ── Browse: New Releases (GQL: queryWhatsNewFeed) ───────────────────

    suspend fun newReleases(
        limit: Int = 20,
        offset: Int = 0,
    ): Result<NewReleasesResponse> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                    put("onlyUnPlayedItems", false)
                    putJsonArray("includedContentTypes") { add("ALBUM") }
                }

            val response =
                graphqlPost(
                    operationName = "queryWhatsNewFeed",
                    variables = vars,
                )

            val feedData =
                response.obj("data")?.obj("whatsNewFeedItems")
                    ?: throw SpotifyException(500, "Invalid queryWhatsNewFeed response")

            val pagingInfo = feedData.obj("pagingInfo")

            val albums =
                feedData.arr("items")?.mapNotNull { elem ->
                    val content = elem.jsonObject.obj("content") ?: return@mapNotNull null
                    if (content.str("__typename") != "AlbumResponseWrapper") return@mapNotNull null
                    val data = content.obj("data") ?: return@mapNotNull null
                    if (data.str("__typename") != "Album") return@mapNotNull null

                    val uri = data.str("uri") ?: return@mapNotNull null
                    SpotifyAlbum(
                        id = uri.substringAfterLast(":"),
                        name = data.str("name") ?: "",
                        albumType = data.str("albumType")?.lowercase(),
                        artists =
                            data.obj("artists")?.arr("items")?.mapNotNull {
                                parseGqlSimpleArtist(it.jsonObject)
                            } ?: emptyList(),
                        images = parseGqlImages(data.obj("coverArt")?.arr("sources")),
                        releaseDate = data.obj("date")?.str("isoString"),
                        uri = uri,
                    )
                } ?: emptyList()

            NewReleasesResponse(
                albums =
                    SpotifyPaging(
                        items = albums,
                        total = feedData.int("totalCount") ?: 0,
                        limit = pagingInfo?.int("limit") ?: limit,
                        offset = pagingInfo?.int("offset") ?: offset,
                    ),
            )
        }

    // ── Home feed (GQL: home) ──────────────────────────────────────────
    //
    // Returns the fully personalized Spotify home: Daily Mix, Discover Weekly,
    // Release Radar, "Jump back in", "More like <artist>", daylist, etc.
    // Shape matches open.spotify.com landing page, one request for ~21 sections.

    suspend fun home(
        sectionItemsLimit: Int = 10,
        timeZone: String =
            java.util.TimeZone
                .getDefault()
                .id,
    ): Result<moe.rukamori.archivetune.spotify.models.SpotifyHomeFeed> =
        runCatching {
            log("D", "spotifyHome: GQL home() request — timeZone=$timeZone limit=$sectionItemsLimit")
            val vars =
                buildJsonObject {
                    put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
                    put("timeZone", timeZone)
                    put("sp_t", "")
                    put("facet", "")
                    put("sectionItemsLimit", sectionItemsLimit)
                    put("includeEpisodeContentRatingsV2", false)
                }

            val response =
                graphqlPost(
                    operationName = "home",
                    variables = vars,
                )

            val homeData =
                response.obj("data")?.obj("home")
                    ?: run {
                        log("E", "spotifyHome: GQL response has no data.home — keys=${response.obj("data")?.keys}")
                        throw SpotifyException(500, "Invalid home response")
                    }

            val greeting = homeData.obj("greeting")?.str("transformedLabel")
            log("D", "spotifyHome: GQL home() OK greeting='$greeting'")

            val sectionElements =
                homeData
                    .obj("sectionContainer")
                    ?.obj("sections")
                    ?.arr("items")
                    ?: run {
                        log("W", "spotifyHome: no sectionContainer.sections.items in response")
                        return@runCatching moe.rukamori.archivetune.spotify.models.SpotifyHomeFeed(
                            greeting = greeting,
                            sections = emptyList(),
                        )
                    }

            log("D", "spotifyHome: parsing ${sectionElements.size} raw sections")
            val sections =
                sectionElements.mapNotNull { elem ->
                    parseHomeSection(elem.jsonObject)
                }
            log("D", "spotifyHome: parsed ${sections.size}/${sectionElements.size} sections successfully")

            moe.rukamori.archivetune.spotify.models.SpotifyHomeFeed(
                greeting = greeting,
                sections = sections,
            )
        }

    private fun parseHomeSection(sectionObj: JsonObject): moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedSection? {
        val sectionData = sectionObj.obj("data") ?: return null
        val typename = sectionData.str("__typename") ?: return null
        val titleObj = sectionData.obj("title")
        val title =
            titleObj?.str("transformedLabel")
                ?: titleObj?.str("translatedBaseText")
                ?: titleObj?.str("text")

        val sectionItems = sectionObj.obj("sectionItems")
        val totalCount = sectionItems?.int("totalCount") ?: 0
        val itemElements = sectionItems?.arr("items") ?: return null

        val items =
            itemElements.mapNotNull { itemElem ->
                parseHomeItem(itemElem.jsonObject)
            }

        // Named, and counted against what came in: a section that arrives with tiles and leaves
        // with fewer says exactly which wrapper was thrown away, which is the only way to find a
        // tile that never appears. Spotify renames these periodically.
        if (items.size != itemElements.size) {
            val seen =
                itemElements.mapNotNull {
                    it.jsonObject
                        .obj("content")
                        ?.str("__typename")
                }
            log("W", "parseHomeSection('$title'): kept ${items.size}/${itemElements.size} — wrappers=$seen")
        }

        if (items.isEmpty()) return null

        return moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedSection(
            sectionUri = sectionObj.str("uri") ?: "",
            title = title,
            typename = typename,
            totalCount = totalCount,
            items = items,
        )
    }

    private fun parseHomeItem(itemObj: JsonObject): moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem? {
        val content = itemObj.obj("content") ?: return null
        val wrapper = content.str("__typename") ?: return null
        val data = content.obj("data") ?: return null

        return when (wrapper) {
            // PseudoPlaylist is how Spotify ships the tiles that are not really playlists — DJ,
            // Liked Songs, daylist. Same uri/name/images shape as a playlist, so it parses the
            // same way; whether the app can DO anything with one is decided at the tap, not here.
            // Dropping them meant those tiles silently never appeared at all.
            "PlaylistResponseWrapper", "PseudoPlaylistResponseWrapper" -> parseHomePlaylist(data)
            "AlbumResponseWrapper" -> parseHomeAlbum(data)
            "ArtistResponseWrapper" -> parseHomeArtist(data)
            else -> {
                // Logged rather than dropped in silence: a tile that vanishes leaves no trace to
                // debug from, and Spotify has renamed these wrappers before.
                log("D", "parseHomeItem: unhandled content __typename='$wrapper'")
                null
            }
        }
    }

    private fun parseHomePlaylist(data: JsonObject): moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem.Playlist? {
        val uri = data.str("uri") ?: return null
        val imageItem =
            data
                .obj("images")
                ?.arr("items")
                ?.firstOrNull()
                ?.jsonObject
        val imageUrl = largestGqlSourceUrl(imageItem?.arr("sources"))
        val colorHex = imageItem?.obj("extractedColors")?.obj("colorDark")?.str("hex")
        val madeFor =
            data
                .arr("attributes")
                ?.firstOrNull { it.jsonObject.str("key") == "madeFor.username" }
                ?.jsonObject
                ?.str("value")

        return moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem.Playlist(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            description = data.str("description"),
            format = data.str("format"),
            totalCount = data.obj("content")?.int("totalCount") ?: 0,
            imageUrl = imageUrl,
            extractedColorHex = colorHex,
            ownerName = data.obj("ownerV2")?.obj("data")?.str("name"),
            madeForUsername = madeFor,
        )
    }

    private fun parseHomeAlbum(data: JsonObject): moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem.Album? {
        val uri = data.str("uri") ?: return null
        val artists =
            data.obj("artists")?.arr("items")?.mapNotNull {
                parseGqlSimpleArtist(it.jsonObject)
            } ?: emptyList()
        val imageUrl = largestGqlSourceUrl(data.obj("coverArt")?.arr("sources"))

        return moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem.Album(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = data.str("name") ?: "",
            albumType = data.str("type")?.lowercase(),
            artists = artists,
            imageUrl = imageUrl,
        )
    }

    private fun parseHomeArtist(data: JsonObject): moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem.Artist? {
        val uri = data.str("uri") ?: return null
        val profile = data.obj("profile")
        val imageUrl = largestGqlSourceUrl(data.obj("visuals")?.obj("avatarImage")?.arr("sources"))
        return moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem.Artist(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = profile?.str("name") ?: "",
            imageUrl = imageUrl,
        )
    }

    // ── Albums (GQL: getAlbum) ──────────────────────────────────────────

    suspend fun album(albumId: String): Result<SpotifyAlbum> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:album:$albumId")
                    put("locale", "")
                    put("offset", 0)
                    put("limit", 50)
                }

            val response =
                graphqlPost(
                    operationName = "getAlbum",
                    variables = vars,
                )

            val albumData =
                response.obj("data")?.obj("albumUnion")
                    ?: throw SpotifyException(500, "Invalid getAlbum response")

            val artists =
                albumData.obj("artists")?.arr("items")?.mapNotNull {
                    parseGqlSimpleArtist(it.jsonObject)
                } ?: emptyList()

            val albumImages = parseGqlImages(albumData.obj("coverArt")?.arr("sources"))

            val albumSimple =
                SpotifySimpleAlbum(
                    id = albumId,
                    name = albumData.str("name") ?: "",
                    images = albumImages,
                    releaseDate = albumData.obj("date")?.str("isoString"),
                    albumType = albumData.str("type")?.lowercase(),
                    artists = artists,
                    uri = "spotify:album:$albumId",
                )

            val tracksData = albumData.obj("tracksV2")
            val trackItems =
                tracksData?.arr("items")?.mapNotNull { elem ->
                    val trackObj = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    parseGqlTrack(trackObj, albumOverride = albumSimple)
                } ?: emptyList()

            SpotifyAlbum(
                id = albumId,
                name = albumData.str("name") ?: "",
                albumType = albumData.str("type")?.lowercase(),
                artists = artists,
                images = albumImages,
                releaseDate = albumData.obj("date")?.str("isoString"),
                totalTracks = tracksData?.int("totalCount") ?: 0,
                tracks = SpotifyPaging(items = trackItems, total = tracksData?.int("totalCount") ?: 0),
                uri = "spotify:album:$albumId",
            )
        }

    // ── Artists (GQL: queryArtistOverview) ───────────────────────────────

    suspend fun artist(artistId: String): Result<SpotifyArtist> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            SpotifyArtist(
                id = artistId,
                name = artistData.obj("profile")?.str("name") ?: "",
                images = parseGqlImages(artistData.obj("visuals")?.obj("avatarImage")?.arr("sources")),
                uri = "spotify:artist:$artistId",
            )
        }

    suspend fun artistTopTracks(
        artistId: String,
        market: String = "US",
    ): Result<ArtistTopTracksResponse> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            val topTracksItems =
                artistData
                    .obj("discography")
                    ?.obj("topTracks")
                    ?.arr("items") ?: JsonArray(emptyList())

            val tracks =
                topTracksItems.mapNotNull { elem ->
                    val trackObj = elem.jsonObject.obj("track") ?: return@mapNotNull null
                    parseGqlTrack(trackObj)
                }

            ArtistTopTracksResponse(tracks = tracks)
        }

    /**
     * Extracts related artists from the GQL queryArtistOverview endpoint.
     * This avoids the rate-limited REST /related-artists endpoint entirely.
     */
    suspend fun artistRelatedArtists(artistId: String): Result<List<SpotifyArtist>> =
        runCatching {
            val vars =
                buildJsonObject {
                    put("uri", "spotify:artist:$artistId")
                    put("locale", "")
                }

            val response =
                graphqlPost(
                    operationName = "queryArtistOverview",
                    variables = vars,
                )

            val artistData =
                response.obj("data")?.obj("artistUnion")
                    ?: throw SpotifyException(500, "Invalid queryArtistOverview response")

            val relatedItems =
                artistData
                    .obj("relatedContent")
                    ?.obj("relatedArtists")
                    ?.arr("items")
                    ?: JsonArray(emptyList())

            relatedItems.mapNotNull { elem ->
                val uri = elem.jsonObject.str("uri") ?: return@mapNotNull null
                val id = uri.substringAfterLast(":")
                val name = elem.jsonObject.obj("profile")?.str("name") ?: return@mapNotNull null
                val images =
                    parseGqlImages(
                        elem.jsonObject
                            .obj("visuals")
                            ?.obj("avatarImage")
                            ?.arr("sources"),
                    )
                SpotifyArtist(id = id, name = name, images = images, uri = uri)
            }
        }

    // ── Related Artists (REST fallback) ─────────────────────────────────

    suspend fun relatedArtists(artistId: String): Result<RelatedArtistsResponse> =
        runCatching {
            authenticatedGet("artists/$artistId/related-artists")
        }

    fun isAuthenticated(): Boolean = accessToken != null
}

@kotlinx.serialization.Serializable
data class ArtistTopTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)

@kotlinx.serialization.Serializable
data class RelatedArtistsResponse(
    val artists: List<SpotifyArtist> = emptyList(),
)

@kotlinx.serialization.Serializable
data class NewReleasesResponse(
    val albums: SpotifyPaging<SpotifyAlbum>? = null,
)

@kotlinx.serialization.Serializable
private data class SpotifyTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)
