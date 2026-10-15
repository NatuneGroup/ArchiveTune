/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.canvas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import java.util.concurrent.ConcurrentHashMap

/** Fetches Spotify Canvas looping videos for the currently playing song. */
object SpotifyCanvasProvider {
    /**
     * Fallback resolver base URL. The full URL is `$BASE_URL?id=<videoId>`.
     */
    private const val BASE_URL = "https://mlc-ytify.kouzu.in/api/canvas"

    /** Spotify's Canvas endpoint. Speaks protobuf — see [SpotifyCanvazProtocol]. */
    private const val CANVAZ_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"

    private const val CACHE_TTL_MS = 60L * 60 * 1000 // 1 hour

    /**
     * Supplies a Spotify access token, or null when the user has no Spotify
     * session. Set by the host app (see `App.kt`); left null in tests and in
     * standalone use of this module.
     */
    @Volatile
    var tokenProvider: (suspend () -> String?)? = null

    /**
     * Maps the currently playing song to a `spotify:track:<id>` URI, or null when
     * it can't be identified on Spotify. Set by the host app.
     */
    @Volatile
    var trackUriResolver: (suspend (videoId: String, title: String?, artist: String?) -> String?)? = null

    /**
     * Supplies extra community/self-hosted resolver base URLs to try after Spotify's own endpoint
     * and before the built-in one. Set by the host app from the user's preference; left null in
     * tests and standalone use.
     */
    @Volatile
    var extraResolverEndpointsProvider: (suspend () -> List<String>)? = null

    /** Optional diagnostic sink, mirroring [AppleMusicProvider.logger]. */
    @Volatile
    var logger: ((message: String) -> Unit)? = null

    private fun log(message: String) {
        logger?.invoke(message)
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    proxyAuthenticator(okhttp3.Authenticator.JAVA_NET_AUTHENTICATOR)
                }
            }
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            install(HttpCache)
            // The x-request-source: muzo header is REQUIRED on every kouzu.in
            // request — without it the server rate-limits the client aggressively.
            defaultRequest {
                header("x-request-source", "muzo")
                header("User-Agent", "ArchiveTune-Android")
                header("Accept", "application/json")
            }
            expectSuccess = false
        }
    }

    /**
     * Separate client for Spotify's Canvas endpoint.
     *
     * Deliberately not [client]: that one's `defaultRequest` block pins
     * `x-request-source: muzo` and `Accept: application/json` for the kouzu.in
     * resolver. Sending the muzo header to Spotify would be wrong, and the JSON
     * Accept header would fight the protobuf one this endpoint needs.
     */
    private val spotifyClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    proxyAuthenticator(okhttp3.Authenticator.JAVA_NET_AUTHENTICATOR)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    private data class CacheEntry(
        val value: CanvasArtwork?,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Looks up the Spotify Canvas for the song identified by [videoId] (the YouTube Music video ID
     * of the currently playing song). [songTitle] and [artistName] are used only to identify the
     * song on Spotify for the official endpoint; the fallback resolver keys off [videoId] alone.
     */
    suspend fun getByVideoId(
        videoId: String,
        songTitle: String? = null,
        artistName: String? = null,
    ): CanvasArtwork? {
        // Policy gate first: wifi-only / low-data / source selection must stop
        // new lookups before any cache read or network call.
        CanvasRequestPolicy.check(CanvasSource.SPOTIFY)
        if (videoId.isBlank()) return null

        cache[videoId]?.let { entry ->
            if (entry.expiresAtMs > System.currentTimeMillis()) return entry.value
            cache.remove(videoId)
        }

        // Source 1: Spotify's own Canvas endpoint.
        val official =
            try {
                fetchOfficialCanvas(videoId, songTitle, artistName)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                log("Official Spotify Canvas lookup failed for $videoId: ${throwable.message}")
                null
            }
        if (official != null) {
            cache[videoId] = CacheEntry(official, System.currentTimeMillis() + CACHE_TTL_MS)
            return official
        }

        // Source 2..N: JSON resolvers, keyed by YouTube video id. The user's own
        // endpoints come first (they are the ones that might actually have data — see
        // [extraResolverEndpointsProvider]), then the built-in kouzu.in resolver.
        val extraEndpoints =
            try {
                extraResolverEndpointsProvider?.invoke().orEmpty()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                log("Failed to read extra canvas resolvers: ${throwable.message}")
                emptyList()
            }
        val resolverEndpoints = (extraEndpoints + BASE_URL).distinct()

        var anyResolverReachable = false
        for (endpoint in resolverEndpoints) {
            val artwork =
                try {
                    val response =
                        client.get(endpoint) {
                            parameter("id", videoId)
                        }
                    if (response.status != HttpStatusCode.OK) {
                        // A clean "no canvas for this track" answer — the resolver works, it
                        // just has nothing. Keep going, but remember that it answered so a
                        // negative result can be cached at the end.
                        anyResolverReachable = true
                        log("Canvas resolver $endpoint returned ${response.status.value} for $videoId")
                        continue
                    }
                    anyResolverReachable = true
                    val body: JsonObject = response.body()
                    parseCanvasArtwork(body, videoId)
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    // Unreachable / unparseable: try the next resolver, and do not cache a
                    // negative result on its account.
                    log("Canvas resolver $endpoint failed for $videoId: ${throwable.message}")
                    null
                }
            if (artwork != null) {
                cache[videoId] = CacheEntry(artwork, System.currentTimeMillis() + CACHE_TTL_MS)
                return artwork
            }
        }

        // Only cache "no canvas" when at least one source actually answered; otherwise a
        // transient outage would suppress lookups for the next hour.
        if (anyResolverReachable) {
            cache[videoId] = CacheEntry(null, System.currentTimeMillis() + CACHE_TTL_MS)
        }
        return null
    }

    /**
     * Liveness probe for the Canvas settings health row.
     *
     * Spotify Canvas needs a usable session and nothing else, so this only asks the host
     * app's token provider. Note the provider may mint a fresh token over the network
     * when the stored one is stale — the probe itself issues no HTTP, but treat the
     * result as session state, not as a network-free check.
     */
    suspend fun isHealthy(): Boolean =
        try {
            tokenProvider?.invoke()?.isNotBlank() == true
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            log("Spotify Canvas health check failed: ${throwable.message}")
            false
        }

    /**
     * Asks Spotify directly for the canvas of the current song.
     *
     * Returns null (without caching) when the host app hasn't wired up a token /
     * track resolver, when the user has no Spotify session, when the song can't
     * be matched on Spotify, or when Spotify has no canvas for the track.
     */
    private suspend fun fetchOfficialCanvas(
        videoId: String,
        songTitle: String?,
        artistName: String?,
    ): CanvasArtwork? {
        val resolveTrackUri = trackUriResolver ?: return null
        val provideToken = tokenProvider ?: return null

        val token = provideToken()?.takeIf { it.isNotBlank() } ?: return null
        val trackUri =
            resolveTrackUri(videoId, songTitle, artistName)?.takeIf { it.isNotBlank() } ?: return null

        val response =
            spotifyClient.post(CANVAZ_URL) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/x-protobuf")
                header("Content-Type", "application/x-protobuf")
                header("User-Agent", "ArchiveTune-Android")
                setBody(SpotifyCanvazProtocol.encodeRequest(listOf(trackUri)))
            }
        if (response.status != HttpStatusCode.OK) {
            log("Spotify canvaz returned ${response.status.value} for $trackUri")
            return null
        }

        val entries = SpotifyCanvazProtocol.decodeResponse(response.body<ByteArray>())
        val canvasUrl =
            entries
                .firstOrNull { it.entityUri == trackUri && !it.url.isNullOrBlank() }
                ?.url
                ?: entries.firstNotNullOfOrNull { entry -> entry.url?.takeIf { it.isNotBlank() } }
                ?: return null

        log("Spotify canvaz resolved $trackUri → $canvasUrl")
        return CanvasArtwork(
            name = songTitle,
            artist = artistName,
            // Stable placeholder so CanvasArtworkPlaybackCache dedupes correctly.
            albumId = "yt:$videoId",
            albumName = null,
            static = null,
            animated = null,
            animatedVertical = null,
            videoUrl = canvasUrl,
            videoUrlVertical = canvasUrl,
        )
    }

    private fun parseCanvasArtwork(
        body: JsonObject,
        videoId: String,
    ): CanvasArtwork? {
        // The resolver may either return the canvas fields at the top level or nested
        // under a `data` / `result` envelope. Handle both.
        val payload = body["data"]?.jsonObject ?: body["result"]?.jsonObject ?: body

        val videoUrl =
            payload["url"]?.jsonPrimitive?.contentOrNull
                ?: payload["canvas_url"]?.jsonPrimitive?.contentOrNull
                ?: payload["video_url"]?.jsonPrimitive?.contentOrNull
                ?: payload["canvas"]?.jsonPrimitive?.contentOrNull
                ?: return null
        if (videoUrl.isBlank()) return null

        val songName =
            payload["song"]?.jsonPrimitive?.contentOrNull
                ?: payload["name"]?.jsonPrimitive?.contentOrNull
                ?: payload["title"]?.jsonPrimitive?.contentOrNull
                ?: payload["track"]?.jsonPrimitive?.contentOrNull

        val artistName =
            payload["artist"]?.jsonPrimitive?.contentOrNull
                ?: payload["artists"]?.jsonPrimitive?.contentOrNull
                ?: payload["author"]?.jsonPrimitive?.contentOrNull

        return CanvasArtwork(
            name = songName,
            artist = artistName,
            // Use the YouTube videoId as a stable albumId placeholder so the cache key
            // machinery in CanvasArtworkPlaybackCache dedupes correctly.
            albumId = "yt:$videoId",
            albumName = null,
            static = null,
            animated = null,
            animatedVertical = null,
            videoUrl = videoUrl,
            videoUrlVertical = videoUrl,
        )
    }
}
