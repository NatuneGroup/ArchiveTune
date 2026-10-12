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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AppleMusicProvider {
    // ── Logging ──────────────────────────────────────────────────────────────────────
    //
    // Routed through GlobalLog so the canvas diagnostic lines show up in the in-app
    // logcat viewer with the proper tag and stay filterable by tag. Plain `println(...)`
    // reaches logcat as `I/System.out:` instead, without the tag, the log level, or the
    // host app's log pipeline.
    //
    // The logger callback is set by the host app (App.kt) on startup, mirroring
    // how PaxsenixLyrics.logger is wired. When null we fall back to plain
    // println so this module stays standalone-testable without the host app.

    private const val LOG_TAG = "AppleMusicCanvas"

    // Log levels mirroring android.util.Log so this pure-JVM module doesn't
    // depend on the Android framework. The host app (App.kt) maps these to
    // android.util.Log when routing through GlobalLog.
    private const val LOG_LEVEL_VERBOSE = 2
    private const val LOG_LEVEL_DEBUG = 3
    private const val LOG_LEVEL_INFO = 4
    private const val LOG_LEVEL_WARN = 5
    private const val LOG_LEVEL_ERROR = 6

    var logger: ((level: Int, tag: String, message: String) -> Unit)? = null

    /**
     * Account tokens supplied by the user on the Apple Music settings page.
     * When a dev (bearer JWT) token is present it REPLACES the scraped/fallback
     * web token; when a media-user-token is present every AMP request carries
     * the `Media-User-Token` header, so lookups run against the user's own
     * account (region + catalog access) instead of anonymous web access.
     * Providers are installed by the app layer (DataStore lives there).
     */
    @Volatile
    var devTokenProvider: (() -> String?)? = null

    @Volatile
    var mediaUserTokenProvider: (() -> String?)? = null

    private object Log {
        fun d(msg: String) {
            val logger = AppleMusicProvider.logger
            if (logger != null) {
                logger(AppleMusicProvider.LOG_LEVEL_DEBUG, AppleMusicProvider.LOG_TAG, msg)
            } else {
                println("${AppleMusicProvider.LOG_TAG}: D: $msg")
            }
        }

        fun w(msg: String) {
            val logger = AppleMusicProvider.logger
            if (logger != null) {
                logger(AppleMusicProvider.LOG_LEVEL_WARN, AppleMusicProvider.LOG_TAG, msg)
            } else {
                println("${AppleMusicProvider.LOG_TAG}: W: $msg")
            }
        }

        fun e(
            t: Throwable,
            msg: String,
        ) {
            val logger = AppleMusicProvider.logger
            if (logger != null) {
                logger(AppleMusicProvider.LOG_LEVEL_ERROR, AppleMusicProvider.LOG_TAG, "$msg: ${t.message}")
            } else {
                println("${AppleMusicProvider.LOG_TAG}: E: $msg")
                t.printStackTrace()
            }
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────────────

    // Web player JWT currently in hand, or null while none has been scraped.
    //
    // Deliberately not seeded with a hardcoded token: a pinned JWT goes stale on Apple's
    // schedule (the one removed here expires 2026-10-22), and a stale token behaves exactly like
    // a live one until every AMP request 401s — the failure then reads as "canvas found nothing"
    // rather than "no credentials". With no token in hand the lookup is skipped, and said so.
    @Volatile
    private var appleMusicToken: String? = null

    // Decoded `exp` of [appleMusicToken] in epoch seconds, or 0 while there is none.
    @Volatile
    private var appleMusicTokenExpAtSec: Long = 0L

    // Last time a scrape was attempted, used to throttle retries while Apple is unreachable.
    @Volatile
    private var appleMusicTokenLastRefreshAtMs: Long = 0L

    // Set when a scrape fails and cleared by the next success, so an unreachable
    // music.apple.com is reported once per outage rather than on every lookup.
    @Volatile
    private var tokenUnavailableReported = false

    private val tokenRefreshMutex = Mutex()

    private const val APPLE_MUSIC_WEB_HOME = "https://music.apple.com/"
    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"
    private const val CACHE_TTL_MS = 1000L * 60 * 60 * 24 // 24 hours

    // Refresh a held token once it is this close to expiry, so a token that is about to die is
    // replaced while there is still time to do it.
    private const val TOKEN_REFRESH_MARGIN_SEC = 60L * 60L * 24L

    // Minimum gap between scrape attempts while Apple is unreachable. A request made during the
    // gap is skipped rather than sent with a token we know is dead.
    private const val TOKEN_REFRESH_THROTTLE_MS = 60_000L
    private const val APPLE_MUSIC_WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    // Storefront derived from the user's Media-User-Token (e.g. "es" for the ES account
    // the user pasted). Cached 24h, resolved via /v1/me/storefront so ES/FR/JP tokens
    // hit the right catalog instead of the hardcoded "us".
    @Volatile private var cachedStorefront: String? = null
    @Volatile private var cachedStorefrontAtMs: Long = 0L
    private val storefrontMutex = Mutex()
    private const val STOREFRONT_TTL_MS = 1000L * 60 * 60 * 24

    private val tokenClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    proxyAuthenticator(okhttp3.Authenticator.JAVA_NET_AUTHENTICATOR)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 10_000
            }
            defaultRequest {
                header("User-Agent", APPLE_MUSIC_WEB_UA)
                header("Accept", "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8")
                header("Accept-Language", "en-US,en;q=0.9")
            }
            expectSuccess = false
        }
    }

    /**
     * Scrapes a fresh Apple Music web player JWT from the JavaScript bundle the landing page
     * loads, and returns it, or null when none could be obtained. Reached through
     * [ensureTokenFresh] and the 401 retry paths, and throttled by the caller.
     */
    private suspend fun refreshToken(): String? =
        tokenRefreshMutex.withLock {
            appleMusicTokenLastRefreshAtMs = System.currentTimeMillis()
            val fresh = scrapeTokenFromWeb()
            if (fresh != null) {
                appleMusicToken = fresh
                appleMusicTokenExpAtSec = AppleWebPlayToken.expSec(fresh)
                tokenUnavailableReported = false
                Log.d("Apple Music token refreshed from web player (exp=${appleMusicTokenExpAtSec}s)")
            } else if (!tokenUnavailableReported) {
                tokenUnavailableReported = true
                Log.w(
                    "Apple Music web player token unavailable — Apple Music lookups are skipped " +
                        "until a scrape succeeds",
                )
            }
            fresh
        }

    /**
     * Returns a usable Apple Music JWT, scraping one when none is held or the held one is within
     * [TOKEN_REFRESH_MARGIN_SEC] of expiry, or null when no live token can be obtained. Callers
     * must skip their request on null: an absent token is reported, a dead one is a silent 401.
     *
     * A user-pasted dev token always wins over anything scraped.
     */
    suspend fun ensureTokenFresh(): String? {
        devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { userDevToken ->
            // An expired user token used to win unconditionally here and 401 every call.
            // Respect its JWT exp: while valid it still wins; once expired fall through to
            // the scraped web token. expSec returns 0 for a malformed token, which counts
            // as usable-unknown rather than expired, so it still wins (never a crash).
            val expSec = AppleWebPlayToken.expSec(userDevToken)
            if (expSec == 0L || expSec > System.currentTimeMillis() / 1000L) return userDevToken
            Log.w("Apple Music user dev token expired (exp=${expSec}s) — falling back to the scraped web token")
        }
        val nowSec = System.currentTimeMillis() / 1000L
        val held = appleMusicToken
        if (held != null && appleMusicTokenExpAtSec - nowSec >= TOKEN_REFRESH_MARGIN_SEC) return held

        // Throttle: a failed scrape costs a round trip, and while Apple is unreachable there is
        // nothing to gain by repeating it per lookup. Waiting out the throttle skips the lookup
        // rather than sending it late with a token that is already dead.
        val sinceLast = System.currentTimeMillis() - appleMusicTokenLastRefreshAtMs
        if (sinceLast in 1..TOKEN_REFRESH_THROTTLE_MS) return validHeldToken(nowSec)

        return refreshToken() ?: validHeldToken(nowSec)
    }

    /** The held token when it is still strictly valid at [nowSec] — a refresh may have failed. */
    private fun validHeldToken(nowSec: Long): String? = appleMusicToken?.takeIf { appleMusicTokenExpAtSec > nowSec }

    /** Non-suspend access to the last scraped web token, when it is still unexpired. */
    fun cachedScrapedDevToken(): String? {
        val token = appleMusicToken ?: return null
        if (token.isBlank()) return null
        val nowSec = System.currentTimeMillis() / 1000L
        if (appleMusicTokenExpAtSec != 0L && appleMusicTokenExpAtSec <= nowSec) return null
        return token
    }

    /**
     * Resolved storefront for the pasted Media-User-Token (ES/JP/…).
     * The ES token the user pasted returns `{"data":[{"id":"es",...}]}` via
     * `/v1/me/storefront` — using that instead of hardcoded "us" makes
     * search and lyrics resolve against the right catalog and pass the
     * token's subscription check.
     *
     * Public because callers outside this module resolve the same account's storefront: one cached
     * answer for the account rather than one lookup per request.
     */
    suspend fun resolveStorefront(): String {
        val media = mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() } ?: return "us"
        val now = System.currentTimeMillis()
        cachedStorefront?.let { if (now - cachedStorefrontAtMs < STOREFRONT_TTL_MS) return it }
        return storefrontMutex.withLock {
            cachedStorefront?.let { if (System.currentTimeMillis() - cachedStorefrontAtMs < STOREFRONT_TTL_MS) return it }
            val fetched = runCatching { fetchStorefrontFromApi() }.getOrNull()
            if (fetched != null) {
                cachedStorefront = fetched
                cachedStorefrontAtMs = System.currentTimeMillis()
                Log.d("Apple Music storefront resolved to $fetched from Media-User-Token")
                fetched
            } else {
                cachedStorefront ?: "us"
            }
        }
    }

    private suspend fun fetchStorefrontFromApi(): String? {
        val token = ensureTokenFresh() ?: return null
        val media = mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val resp = client.get("$AMP_BASE_URL/v1/me/storefront") {
            header("Authorization", "Bearer $token")
            header("Media-User-Token", media)
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", APPLE_MUSIC_WEB_UA)
        }
        if (!resp.status.isSuccess()) {
            Log.w("Apple Music storefront fetch failed: ${resp.status}")
            return null
        }
        val root = resp.body<JsonObject>()
        return root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Liveness probe for the Canvas settings health row: one cheap catalog request that
     * reports whether this device can reach AMP with the token the provider holds.
     */
    suspend fun isHealthy(): Boolean {
        CanvasRequestPolicy.check(CanvasSource.APPLE_MUSIC)
        val token = ensureTokenFresh() ?: return false
        return try {
            val response =
                client.get("$AMP_BASE_URL/v1/catalog/us/charts") {
                    header("Authorization", "Bearer $token")
                    mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { mt -> header("Media-User-Token", mt) }
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                    header("User-Agent", APPLE_MUSIC_WEB_UA)
                    parameter("types", "albums")
                    parameter("limit", "1")
                }
            response.status == HttpStatusCode.OK
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(error, "Apple Music health check failed")
            false
        }
    }

    /** Clears cached storefront so the next lookup re-resolves (call after token change). */
    fun clearStorefrontCache() {
        cachedStorefront = null
        cachedStorefrontAtMs = 0L
    }

    /**
     * Scrapes a live web player JWT: the landing page names the JavaScript bundle, and the bundle
     * carries the token. Apple serves neither the bundle name nor the token location as a
     * contract, so both are read by [AppleWebPlayToken] — which rejects anything that is not a
     * usable `AMPWebPlay` token rather than accepting a lookalike with no readable expiry.
     */
    private suspend fun scrapeTokenFromWeb(): String? =
        try {
            val homeResponse = tokenClient.get(APPLE_MUSIC_WEB_HOME)
            // music.apple.com/ 301s to a localised landing page (e.g. /us/new). Ktor follows that,
            // so only a genuine error status is fatal here.
            if (!homeResponse.status.isSuccess()) {
                Log.w("Apple Music home fetch failed: ${homeResponse.status}")
                return null
            }
            val nowSec = System.currentTimeMillis() / 1000L
            val html = homeResponse.bodyAsText()

            AppleWebPlayToken.select(html, nowSec)?.let { return it }

            val bundleUrls = AppleWebPlayToken.bundleUrls(html)
            if (bundleUrls.isEmpty()) {
                Log.w("Apple Music token: no JS bundle URL found in home HTML")
                return null
            }

            // Try each candidate bundle until one yields a usable token. The entry chunk is
            // normally first, but Apple occasionally moves the token into a vendor chunk.
            for (jsBundleUrl in bundleUrls) {
                val jsResponse = tokenClient.get(jsBundleUrl)
                if (!jsResponse.status.isSuccess()) {
                    Log.w("Apple Music token: JS bundle fetch failed: ${jsResponse.status} ($jsBundleUrl)")
                    continue
                }
                AppleWebPlayToken.select(jsResponse.bodyAsText(), nowSec)?.let { return it }
                Log.w("Apple Music token: no usable AMPWebPlay token in JS bundle $jsBundleUrl")
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(e, "Apple Music token scrape error")
            null
        }

    // ── Networking ───────────────────────────────────────────────────────────────────

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
            install(ContentNegotiation) {
                json(json)
                register(ContentType.Text.JavaScript, KotlinxSerializationConverter(json))
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 25_000
                socketTimeoutMillis = 25_000
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            install(HttpCache)
            expectSuccess = false
        }
    }

    // ── Cache ────────────────────────────────────────────────────────────────────────

    private data class CacheEntry(
        val value: CanvasArtwork?,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private fun cacheKey(
        prefix: String,
        vararg parts: String,
    ): String = "$prefix|" + parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    // ── Public API ───────────────────────────────────────────────────────────────────

    suspend fun getByAlbumArtist(
        album: String,
        artist: String,
        storefront: String = "us",
    ): CanvasArtwork? {
        CanvasRequestPolicy.check(CanvasSource.APPLE_MUSIC)
        Log.d("getByAlbumArtist: album='$album', artist='$artist'")
        val key = cacheKey("sa", album, artist, storefront)
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }
        val result = searchAndFetchMotion(album, artist, album, storefront, "albums")
        if (result != null) cache[key] = CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS)
        return result
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        storefront: String = "us",
        forceRefresh: Boolean = false,
    ): CanvasArtwork? {
        CanvasRequestPolicy.check(CanvasSource.APPLE_MUSIC)
        val key = cacheKey("song", song, artist, album ?: "", storefront)
        if (forceRefresh) {
            cache.remove(key)
        } else {
            cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }
        }
        val result = searchAndFetchMotion(song, artist, album, storefront, "songs", forceRefresh)
        if (result != null) cache[key] = CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS)
        return result
    }

    suspend fun getByAlbumId(
        albumId: String,
        storefront: String = "us",
    ): CanvasArtwork? {
        CanvasRequestPolicy.check(CanvasSource.APPLE_MUSIC)
        val key = cacheKey("id", albumId, storefront)
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }
        val result = fetchMotionArtwork(albumId, storefront, null)
        cache[key] = CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS)
        return result
    }

    // ── Core Logic ───────────────────────────────────────────────────────────────────

    /**
     * Searches via AMP API and tries to fetch motion artwork.
     * This is faster than iTunes search + AMP lookup.
     */
    private suspend fun searchAndFetchMotion(
        term: String,
        artist: String,
        album: String?,
        storefront: String,
        type: String, // "albums" or "songs"
        forceRefresh: Boolean = false,
    ): CanvasArtwork? {
        return runCatching {
            val effectiveStorefront = if (storefront == "us") resolveStorefront() else storefront
            Log.d("searching for $type: $term (album: $album) in $effectiveStorefront (requested $storefront)")
            var query = if (term.contains(artist, ignoreCase = true)) term else "$artist $term"
            if (!album.isNullOrBlank() && !query.contains(album, ignoreCase = true)) query = "$query $album"

            val searchUrl = "$AMP_BASE_URL/v1/catalog/$effectiveStorefront/search"
            var token = ensureTokenFresh() ?: return@runCatching null
            var response =
                client.get(searchUrl) {
                    header("Authorization", "Bearer $token")
                    mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { mt -> header("Media-User-Token", mt) }
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                    header("User-Agent", APPLE_MUSIC_WEB_UA)
                    parameter("term", query)
                    parameter("types", type)
                    parameter("limit", "10")
                    parameter("extend", "editorialVideo")
                    parameter("include", "albums")
                    if (forceRefresh) header("Cache-Control", "no-cache")
                }
            if (response.status == HttpStatusCode.Unauthorized) {
                // Token expired between refresh and use — force-refresh and retry once. A refresh
                // that yields nothing means a second attempt would repeat the same 401.
                Log.w("AMP search returned 401 — force-refreshing token and retrying once")
                token = refreshToken() ?: return@runCatching null
                response =
                    client.get(searchUrl) {
                        header("Authorization", "Bearer $token")
                        mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { mt -> header("Media-User-Token", mt) }
                        header("Origin", "https://music.apple.com")
                        header("Referer", "https://music.apple.com/")
                        header("User-Agent", APPLE_MUSIC_WEB_UA)
                        parameter("term", query)
                        parameter("types", type)
                        parameter("limit", "10")
                        parameter("extend", "editorialVideo")
                        parameter("include", "albums")
                        if (forceRefresh) header("Cache-Control", "no-cache")
                    }
            }
            if (response.status != HttpStatusCode.OK) {
                Log.w("search failed with status ${response.status}")
                return@runCatching null
            }

            val root = response.body<JsonObject>()
            val results =
                root["results"]
                    ?.jsonObject
                    ?.get(type)
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonArray
                    ?: return@runCatching null

            val scoredResults =
                results
                    .mapNotNull { scoreAndFilterItem(it.jsonObject, term, artist, album) }
                    .sortedByDescending { it.first }

            Log.d("Found ${scoredResults.size} scored results for term '$term'")

            for ((score, obj) in scoredResults) {
                if (score < 12) {
                    Log.d("skipping result with low score: $score")
                    continue
                }

                val attributes = obj["attributes"]?.jsonObject ?: continue
                val resultName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val resultArtistName = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
                val itemType = obj["type"]?.jsonPrimitive?.contentOrNull

                val targetAlbumId = resolveAlbumId(obj, attributes, itemType, resultName)
                if (targetAlbumId == null || targetAlbumId.startsWith("pl.")) {
                    Log.d("skipping null or playlist albumId ($targetAlbumId) for $resultName ($resultArtistName)")
                    continue
                }

                Log.d("trying resolve for $targetAlbumId (from $itemType)")

                // Check for immediate motion in search result
                val ev = attributes["editorialVideo"]?.jsonObject
                if (ev != null) {
                    val videoUrls = extractEditorialVideoUrls(ev)
                    if (!videoUrls.animated.isNullOrBlank() || !videoUrls.animatedVertical.isNullOrBlank()) {
                        val name = attributes["name"]?.jsonPrimitive?.contentOrNull
                        val collName = attributes["collectionName"]?.jsonPrimitive?.contentOrNull
                        val resolvedAlbumName = if (itemType == "songs") collName else name
                        Log.d("Found direct editorialVideo for $name (ID: $targetAlbumId)")
                        return@runCatching CanvasArtwork(
                            name = name,
                            artist = resultArtistName,
                            albumId = targetAlbumId,
                            albumName = resolvedAlbumName,
                            animated = videoUrls.animated,
                            animatedVertical = videoUrls.animatedVertical,
                        )
                    }
                }

                // Full lookup with metadata preservation
                val fetched =
                    fetchMotionArtwork(
                        albumId = targetAlbumId,
                        storefront = storefront,
                        fallbackArtist = resultArtistName,
                        titleOverride = if (itemType == "songs") attributes["name"]?.jsonPrimitive?.contentOrNull else null,
                        artistOverride = if (itemType == "songs") resultArtistName else null,
                    )
                if (fetched != null) return@runCatching fetched
            }
            Log.d("no canvas found in resolution/lookup for $term after ${scoredResults.size} results")
            null
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(it, "error in searchAndFetchMotion for $term")
        }.getOrNull()
    }

    private suspend fun fetchMotionArtwork(
        albumId: String,
        storefront: String,
        fallbackArtist: String?,
        titleOverride: String? = null,
        artistOverride: String? = null,
    ): CanvasArtwork? {
        if (albumId.startsWith("pl.")) {
            Log.d("fetchMotionArtwork: ignoring playlist id $albumId")
            return null
        }
        return runCatching {
            val effectiveStorefront = if (storefront == "us") resolveStorefront() else storefront
            Log.d("fetching album $albumId in $effectiveStorefront")
            val albumUrl = "$AMP_BASE_URL/v1/catalog/$effectiveStorefront/albums/$albumId"
            var token = ensureTokenFresh() ?: return@runCatching null
            var response =
                client.get(albumUrl) {
                    header("Authorization", "Bearer $token")
                    mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { mt -> header("Media-User-Token", mt) }
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                    header("User-Agent", APPLE_MUSIC_WEB_UA)
                    parameter("extend", "editorialVideo")
                    parameter("include", "tracks")
                }
            if (response.status == HttpStatusCode.Unauthorized) {
                Log.w("album fetch returned 401 — force-refreshing token and retrying once")
                token = refreshToken() ?: return@runCatching null
                response =
                    client.get(albumUrl) {
                        header("Authorization", "Bearer $token")
                        mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { mt -> header("Media-User-Token", mt) }
                        header("Origin", "https://music.apple.com")
                        header("Referer", "https://music.apple.com/")
                        header("User-Agent", APPLE_MUSIC_WEB_UA)
                        parameter("extend", "editorialVideo")
                        parameter("include", "tracks")
                    }
            }
            if (response.status != HttpStatusCode.OK) {
                Log.w("album fetch failed for $albumId: ${response.status}")
                return@runCatching null
            }

            val root = response.body<JsonObject>()
            val data = root["data"]?.jsonArray
            if (data.isNullOrEmpty()) return@runCatching null

            val albumObj = data.firstOrNull()?.jsonObject ?: return@runCatching null
            val attributes = albumObj["attributes"]?.jsonObject
            val albumName = attributes?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            val artistName = attributes?.get("artistName")?.jsonPrimitive?.contentOrNull ?: fallbackArtist

            val nameLower = albumName.lowercase(Locale.ROOT)
            val isBlacklisted =
                nameLower.contains("playlist") || nameLower.contains("set list") ||
                    nameLower.contains("essentials") || nameLower.contains("dj mix") ||
                    nameLower.contains("mixed") || nameLower.contains("apple music") ||
                    nameLower.contains("today's hits") || nameLower.contains("session")
            if (isBlacklisted) {
                Log.d("fetchMotionArtwork: ignoring blacklisted album '$albumName' ($albumId)")
                return@runCatching null
            }

            val finalTitle = titleOverride ?: albumName
            val finalArtist = artistOverride ?: artistName

            val ev = attributes?.get("editorialVideo")?.jsonObject
            if (ev != null) {
                val videoUrls = extractEditorialVideoUrls(ev)
                if (!videoUrls.animated.isNullOrBlank() || !videoUrls.animatedVertical.isNullOrBlank()) {
                    Log.d("found editorialVideo for $finalTitle (album: $albumName, id: $albumId)")
                    return@runCatching CanvasArtwork(
                        name = finalTitle,
                        artist = finalArtist,
                        albumId = albumId,
                        albumName = albumName,
                        animated = videoUrls.animated,
                        animatedVertical = videoUrls.animatedVertical,
                    )
                }
            }

            Log.d("no editorialVideo for $albumId (available keys: ${attributes?.keys})")
            null
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(it, "error in fetchMotionArtwork for $albumId")
        }.getOrNull()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    /**
     * Scores and filters a single search result item.
     * Returns null if the item should be excluded from consideration.
     */
    private fun scoreAndFilterItem(
        obj: JsonObject,
        term: String,
        artist: String,
        album: String?,
    ): Pair<Int, JsonObject>? {
        val attributes = obj["attributes"]?.jsonObject ?: return null
        val resultArtistName = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
        val resultName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val resultCollectionName = attributes["collectionName"]?.jsonPrimitive?.contentOrNull ?: ""

        val nameLower = resultName.lowercase(Locale.ROOT)
        val collectionLower = resultCollectionName.lowercase(Locale.ROOT)
        val isBlacklisted =
            nameLower.contains("playlist") || nameLower.contains("set list") ||
                collectionLower.contains("playlist") || collectionLower.contains("set list") ||
                nameLower.contains("essentials") || collectionLower.contains("essentials") ||
                collectionLower.contains("dj mix") || collectionLower.contains("mixed") ||
                collectionLower.contains("apple music") || collectionLower.contains("today's hits") ||
                nameLower.contains("session") || collectionLower.contains("session")
        if (isBlacklisted) {
            Log.d("  - Skipping blacklisted result: '$resultName' (Album: '$resultCollectionName')")
            return null
        }

        val artistMatch = resultArtistName.equals(artist, ignoreCase = true)
        val artistFuzzy =
            resultArtistName.contains(artist, ignoreCase = true) ||
                artist.contains(resultArtistName, ignoreCase = true)
        if (!artistFuzzy) return null

        var score = if (artistMatch) 10 else 5

        val nameMatch = resultName.equals(term, ignoreCase = true)
        val nameFuzzy = resultName.contains(term, ignoreCase = true) || term.contains(resultName, ignoreCase = true)
        score +=
            when {
                nameMatch -> 15
                nameFuzzy -> 7
                else -> -10
            }

        // Special editions handling (Deluxe, Expanded, etc.)
        val editionWords = listOf("deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus")
        for (word in editionWords) {
            val inTerm = term.contains(word, ignoreCase = true)
            val inResult = resultName.contains(word, ignoreCase = true)
            score +=
                when {
                    inTerm && inResult -> 5
                    inTerm != inResult && inResult -> -3
                    else -> 0
                }
        }

        // Album matching — very strong signal
        if (!album.isNullOrBlank() && resultCollectionName.isNotBlank()) {
            val albumMatch = resultCollectionName.equals(album, ignoreCase = true)
            val albumFuzzy =
                resultCollectionName.contains(album, ignoreCase = true) ||
                    album.contains(resultCollectionName, ignoreCase = true)
            score +=
                when {
                    albumMatch -> 20
                    albumFuzzy -> 10
                    else -> 0
                }
        }

        Log.d("  - Result: '$resultName' by '$resultArtistName' (Album: '$resultCollectionName', ID: ${obj["id"]}) -> Score: $score")
        return score to obj
    }

    /**
     * Resolves the Apple Music album ID from a search result item.
     * Handles both song and album result types, with URL-parsing as a last resort.
     */
    private fun resolveAlbumId(
        obj: JsonObject,
        attributes: JsonObject,
        itemType: String?,
        resultName: String,
    ): String? {
        if (itemType == "albums") return obj["id"]?.jsonPrimitive?.contentOrNull
        if (itemType != "songs") return null

        val relationships = obj["relationships"]?.jsonObject
        var albumId =
            relationships
                ?.get("albums")
                ?.jsonObject
                ?.get("data")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: attributes["collectionId"]?.jsonPrimitive?.contentOrNull

        // Fallback: parse album ID from the track URL
        // URL format: https://music.apple.com/region/album/name/ID?i=songId
        if (albumId == null) {
            val url = attributes["url"]?.jsonPrimitive?.contentOrNull
            if (url != null) {
                val albumPart = url.substringAfter("/album/", "").substringBefore("?")
                val id = albumPart.substringAfterLast("/", "")
                if (id.isNotBlank() && id.all { it.isDigit() }) albumId = id
            }
        }

        if (albumId == null) Log.d("relationships keys for $resultName: ${relationships?.keys}")
        return albumId
    }

    private data class EditorialVideoUrls(
        val animated: String?,
        val animatedVertical: String?,
    )

    private fun extractEditorialVideoUrls(ev: JsonObject): EditorialVideoUrls {
        fun JsonObject.videoUrl(): String? =
            this["video"]?.jsonPrimitive?.contentOrNull
                ?: this["videoUrl"]?.jsonPrimitive?.contentOrNull
                ?: this["hlsUrl"]?.jsonPrimitive?.contentOrNull
                ?: this["url"]?.jsonPrimitive?.contentOrNull

        val raw = ev["motionDetailRaw"]?.jsonObject?.videoUrl()
        val square = ev["motionDetailSquare"]?.jsonObject?.videoUrl()
        val tall = ev["motionDetailTall"]?.jsonObject?.videoUrl()
        val static = ev["motionDetailStatic"]?.jsonObject?.videoUrl()
        val animated = raw ?: square ?: static ?: tall

        if (animated.isNullOrBlank() && tall.isNullOrBlank()) {
            Log.d("editorialVideo found but no video link in assets: ${ev.keys}")
        }

        return EditorialVideoUrls(
            animated = animated,
            animatedVertical = tall,
        )
    }
}
