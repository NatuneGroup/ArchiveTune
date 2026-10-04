/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.applemusic

import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.AppleWebPlayToken
import moe.rukamori.archivetune.constants.AppleMusicQuality
import moe.rukamori.archivetune.utils.PoolAccountManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Apple Music full-track playback source. */
object AppleMusicAudioProvider {
    private const val TAG = "AppleMusicSource"

    private const val AMP_BASE = "https://amp-api.music.apple.com"
    private const val WEB_PLAYBACK_URL =
        "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Storefront resolved from the Media-User-Token (`/v1/me/storefront`), cached 24 h. */
    private const val STOREFRONT_TTL_MS = 24 * 60 * 60 * 1000L

    /**
     * KEYFORMAT of the Widevine #EXT-X-KEY line — the standard Widevine system id as a URN. The
     * same playlist also lists FairPlay and PlayReady keys, which are not usable on Android here.
     */
    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    @Volatile private var cachedStorefront: String? = null
    @Volatile private var cachedStorefrontAtMs = 0L
    private val storefrontMutex = Mutex()

    /** Resolved storefront for the token (es/jp/…); "us" when it cannot be determined. */
    suspend fun resolveStorefront(): String {
        val now = System.currentTimeMillis()
        cachedStorefront?.let { if (now - cachedStorefrontAtMs < STOREFRONT_TTL_MS) return it }
        return storefrontMutex.withLock {
            val stillFresh = cachedStorefront?.takeIf { now - cachedStorefrontAtMs < STOREFRONT_TTL_MS }
            if (stillFresh != null) return@withLock stillFresh
            val media = mediaUserToken()?.takeIf { it.isNotBlank() } ?: return@withLock cachedStorefront ?: "us"
            val dev = usableDevToken() ?: return@withLock cachedStorefront ?: "us"
            fetchedStorefront(media, dev)?.let { fetched ->
                cachedStorefront = fetched
                cachedStorefrontAtMs = System.currentTimeMillis()
                return@withLock fetched
            }
            cachedStorefront ?: "us"
        }
    }

    private suspend fun fetchedStorefront(mediaToken: String, devToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request.Builder()
                        .url("$AMP_BASE/v1/me/storefront")
                        .header("Authorization", "Bearer $devToken")
                        .header("Media-User-Token", mediaToken)
                        .header("Origin", "https://music.apple.com")
                        .header("Referer", "https://music.apple.com/")
                        .header("User-Agent", UA)
                        .get()
                        .build()
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "storefront fetch failed: %d".format(response.code))
                        return@use null
                    }
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    root["data"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                }
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrNull()
        }

    fun devToken(): String? = AppleMusicProvider.devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }

    /**
     * A dev token that is actually usable: the user's stored token while unexpired,
     * otherwise the last scraped web token. An expired user token used to flow into the
     * search, the stream build and the Widevine licence callback — every call 401'd and
     * the source fell back to YouTube opus even with a logged-in account and pool accounts.
     * expSec returns 0 for a malformed token, which counts as usable-unknown rather than
     * expired, so it still wins here instead of crashing.
     */
    fun usableDevToken(): String? {
        val userToken = devToken()
        if (userToken != null) {
            val expSec = AppleWebPlayToken.expSec(userToken)
            if (expSec == 0L || expSec > System.currentTimeMillis() / 1000L) return userToken
        }
        AppleMusicProvider.cachedScrapedDevToken()?.let { return it }
        return userToken
    }

    fun mediaUserToken(): String? = AppleMusicProvider.mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }

    /** True when both tokens are present — the source cannot resolve anything otherwise. */
    fun isAvailable(): Boolean = usableDevToken() != null && mediaUserToken() != null

    /** Thrown by [searchSongIds]/[webPlayback] on 401/403 — the media-user-token is dead. */
    private class AuthException : Exception("apple media-user-token rejected (401/403)")

    /** One entry of the account ring: a media-user-token plus its pool id (null = personal). */
    private data class RingEntry(val token: String, val poolId: Long?)

    @Volatile
    private var ring: List<RingEntry> = emptyList()

    @Volatile
    private var ringBuiltAt = 0L

    /** Sticky index of the last account that resolved successfully; rotation starts here. */
    @Volatile
    private var ringIndex = 0

    /**
     * The account ring: the personal media-user-token first (when signed in), then the shared
     * pool accounts premium-first — mirroring how the Qobuz/Deezer resolvers walk their token
     * lists. Cached for a minute so per-call rebuilds don't hammer the preference stores.
     */
    private fun accountRing(): List<RingEntry> {
        val now = System.currentTimeMillis()
        val cached = ring
        if (cached.isNotEmpty() && now - ringBuiltAt < 60_000L) return cached
        val personal = mediaUserToken()
        val pool = PoolAccountManager.appleMusicAccounts().map { RingEntry(it.mediaUserToken, it.id) }
        val built = ((personal?.let { listOf(RingEntry(it, null)) } ?: emptyList()) + pool)
            .distinctBy { it.token }
        ring = built
        ringBuiltAt = now
        if (ringIndex >= built.size) ringIndex = 0
        return built
    }

    /**
     * One playable Apple Music candidate. [playlistUrl] serves the HLS playlist whose
     * segments are byteranges of the single encrypted fMP4 at [mediaUrl].
     */
    data class AppleMusicStream(
        val songId: String,
        val playlistUrl: String,
        val mediaUrl: String,
        val licenseUrl: String,
        val keyIdHex: String?,
        val drmUri: String,
        val flavor: String,
        val contentLength: Long?,
        val matchedTitle: String,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val matchedDurationMs: Long?,
    )

    /**
     * Search the catalog and resolve every plausible candidate to a playable stream. The
     * caller applies the shared metadata-match gate ([moe.rukamori.archivetune.audiosource
     * .TitleMatch]) to pick the winner, so candidates are returned in search-rank order.
     * Empty when the tokens are missing/unauthorized or nothing resolved.
     */
    suspend fun resolveCandidates(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        quality: AppleMusicQuality = AppleMusicQuality.LOSSLESS,
    ): List<AppleMusicStream> =
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            withContext(Dispatchers.IO) {
            // ensureTokenFresh first: it returns an unexpired user token as-is and otherwise the
            // held or a re-scraped web token. usableDevToken() alone would hand back an expired
            // user token whenever nothing had been scraped yet, and every call would 401.
            val devToken =
                AppleMusicProvider.ensureTokenFresh() ?: usableDevToken() ?: return@withContext emptyList()
            val ringEntries = accountRing()
            if (ringEntries.isEmpty()) return@withContext emptyList()

            // Walk the ring starting at the sticky winner; a 401/403 advances to the next
            // account (reporting dead pool entries) until one resolves or all fail.
            for (attempt in ringEntries.indices) {
                val index = (ringIndex + attempt) % ringEntries.size
                val entry = ringEntries[index]
                val streams =
                    runCatching {
                        resolveWithToken(entry.token, devToken, title, artists, quality)
                    }.getOrElse { error ->
                        error.rethrowIfAudioSourceCancelled()
                        if (error !is AuthException) {
                            Log.w(TAG, "resolve failed: ${error.message}")
                            return@withContext emptyList()
                        }
                        Log.w(TAG, "media-user-token rejected — rotating account ${index + 1}/${ringEntries.size}")
                        if (entry.poolId != null) {
                            PoolAccountManager.report(
                                service = "apple-music",
                                kind = "account",
                                id = entry.poolId,
                                reportType = "dead",
                            )
                        }
                        null
                    }
                if (streams != null) {
                    ringIndex = index // sticky winner — next playback starts here
                    return@withContext streams
                }
            }
            emptyList()
            }
        } ?: emptyList()

    /** One resolve pass with a specific media-user-token. Throws [AuthException] on 401/403. */
    private suspend fun resolveWithToken(
        mediaToken: String,
        devToken: String,
        title: String,
        artists: List<String>,
        quality: AppleMusicQuality,
    ): List<AppleMusicStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val storefront = resolveStorefront()

                val query = if (title.contains(artists.firstOrNull().orEmpty(), ignoreCase = true)) {
                    title
                } else {
                    "${artists.firstOrNull().orEmpty()} $title".trim()
                }
                val songIds = searchSongIds(query, storefront, devToken, mediaToken)
                if (songIds.isEmpty()) return@runCatching emptyList()

                // Resolve up to the first few candidates; stop early once we have three
                // playable ones (webPlayback is the expensive call).
                val out = mutableListOf<AppleMusicStream>()
                for (id in songIds.take(5)) {
                    if (out.size >= 3) break
                    webPlayback(id, devToken, mediaToken, storefront, quality)?.let { out += it }
                }
                out
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse { error ->
                // Auth failures must propagate to the rotation loop — swallowing them here
                // would pin the ring to a dead account.
                if (error is AuthException) throw error
                Log.w(TAG, "resolve failed: ${error.message}")
                emptyList()
            }
        }

    /** Top catalog song ids for [query], in Apple's search-rank order. */
    private fun searchSongIds(
        query: String,
        storefront: String,
        devToken: String,
        mediaToken: String,
    ): List<String> {
        val url =
            "$AMP_BASE/v1/catalog/$storefront/search".toHttpUrl()
                .newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("types", "songs")
                .addQueryParameter("limit", "5")
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $devToken")
                .header("Media-User-Token", mediaToken)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", UA)
                .get()
                .build()
        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) throw AuthException()
                Log.w(TAG, "search failed: %d".format(response.code))
                return emptyList()
            }
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val songs =
                root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
                    ?: return emptyList()
            return songs.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }
    }

    /** Resolve one catalog id through the web-playback endpoint to a playable playlist. */
    private fun webPlayback(
        songId: String,
        devToken: String,
        mediaToken: String,
        storefront: String,
        quality: AppleMusicQuality,
    ): AppleMusicStream? {
        val body = """{"salableAdamId":$songId,"language":"en-us"}""".toRequestBody(JSON_MEDIA)
        val request =
            Request.Builder()
                .url(WEB_PLAYBACK_URL)
                .post(body)
                .header("Authorization", "Bearer $devToken")
                .header("Media-User-Token", mediaToken)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", UA)
                .build()
        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) throw AuthException()
                Log.w(TAG, "webPlayback failed for %s: %d".format(songId, response.code))
                return null
            }
            val root = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
                .getOrNull() ?: return null
            val song = root["songList"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val licenseUrl = song["hls-key-server-url"]?.jsonPrimitive?.contentOrNull
            val assets = song["assets"]?.jsonArray ?: return null

            // Pick the highest-bitrate ctrp (AES-CTR) asset. cbcp flavors are FairPlay
            // (`skd://` keys) and cannot be decrypted on Android.
            data class Asset(val flavor: String, val url: String, val kbps: Int)

            val candidates =
                assets.mapNotNull { element ->
                    val asset = element.jsonObject
                    val flavor = asset["flavor"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = asset["URL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!flavor.contains("ctrp")) return@mapNotNull null
                    val kbps = Regex("(\\d+)$").find(flavor)?.groupValues?.last()?.toIntOrNull() ?: 0
                    Asset(flavor, url, kbps)
                }.sortedByDescending { it.kbps }
            // `candidates` is sorted best-first. A lossless request with no lossless tier must fall
            // back to the BEST asset Apple offered, never the worst: it silently played 64 kbps
            // when 256 kbps was sitting right there. An AAC request with only lossless tiers goes the
            // other way, to the smallest, so it never pulls hi-res for a user who asked for AAC.
            val asset = when (quality) {
                AppleMusicQuality.AAC ->
                    candidates.firstOrNull { it.kbps <= 320 } ?: candidates.lastOrNull()
                AppleMusicQuality.LOSSLESS ->
                    candidates.firstOrNull { it.kbps in 321..1411 } ?: candidates.firstOrNull()
                AppleMusicQuality.HI_RES_LOSSLESS -> candidates.firstOrNull()
            } ?: return null

            // The asset URL serves the HLS playlist; segments are byteranges of one mp4.
            val playlistUrl = asset.url
            val parsed = parsePlaylist(playlistUrl) ?: return null
            val metadata = song["metadata"] as? JsonObject
            return AppleMusicStream(
                songId = songId,
                playlistUrl = playlistUrl,
                mediaUrl = parsed.mediaUrl,
                licenseUrl = licenseUrl ?: WEB_PLAYBACK_URL.replace("webPlayback", "acquireWebPlaybackLicense"),
                keyIdHex = parsed.keyIdHex,
                flavor = asset.flavor,
                contentLength = parsed.contentLength,
                matchedTitle = metadata?.title() ?: songId,
                matchedArtist = metadata?.artist(),
                matchedAlbum = metadata?.album(),
                matchedDurationMs = metadata?.durationMs(),
                drmUri = parsed.drmUri,
            )
        }
    }

    private fun JsonObject.title(): String? = this["title"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.artist(): String? =
        this["artistName"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.album(): String? =
        this["albumName"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.durationMs(): Long? =
        this["trackDuration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private class ParsedPlaylist(
        val mediaUrl: String,
        val keyIdHex: String?,
        val drmUri: String,
        val contentLength: Long?,
    )

    /**
     * Fetch the playlist and derive the absolute mp4 URL. The playlist's segments are
     * byteranges of a single fMP4 next to it; the EXT-X-KEY `data:` URI carries the KID
     * (verified to equal the tenc default_KID), and `#EXT-X-MAP` names the mp4 file.
     */
    private fun parsePlaylist(playlistUrl: String): ParsedPlaylist? {
        val request = Request.Builder().url(playlistUrl).header("User-Agent", UA).get().build()
        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "playlist fetch failed: %d".format(response.code))
                return null
            }
            val text = response.body?.string().orEmpty()
            var keyIdHex: String? = null
            var drmUri: String? = null
            var mediaName: String? = null
            val dataLines = mutableListOf<String>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                when {
                    line.startsWith("#EXT-X-KEY") && keyIdHex == null -> {
                        // A playlist carries one #EXT-X-KEY per DRM system: FairPlay (an skd://
                        // URI), PlayReady, and Common Encryption (Widevine).
                        //
                        // Apple no longer emits KEYFORMAT on the ctrp playlists it serves. Verified
                        // 2026-09-29 against a live playlist: its only key line is
                        //   #EXT-X-KEY:METHOD=ISO-23001-7,URI="data:;base64,<16 bytes>"
                        // with no KEYFORMAT attribute whatsoever. Gating on the Widevine URN
                        // therefore matched nothing, every candidate looked keyless, and no Apple
                        // track could ever play. The URN is still accepted when present.
                        //
                        // The KID is identified by shape instead. That payload is exactly the
                        // 16-byte tenc default_KID — confirmed by parsing the same track's fMP4
                        // init segment, whose tenc box carries the identical bytes — while
                        // FairPlay's skd:// URI has no data: payload and PlayReady's is a WRM
                        // header rather than 16 bytes, so both fall out at the size check below.
                        val keyFormat =
                            Regex("KEYFORMAT=\"?([^\",]+)?").find(line)?.groupValues?.get(1)
                        val isOtherDrm =
                            keyFormat != null &&
                                !keyFormat.contains(WIDEVINE_KEYFORMAT, ignoreCase = true)
                        if (!isOtherDrm) {
                            // The RAW data: URI is what Apple's license exchange expects as `uri`.
                            Regex("URI=\"([^\"]+)\"").find(line)?.let { match -> drmUri = match.groupValues[1] }
                            Regex("URI=\"data:[^\"]*base64,([^\"]+)\"").find(line)?.let { match ->
                                keyIdHex =
                                    runCatching {
                                        java.util.Base64.getDecoder().decode(match.groupValues[1].trim())
                                    }.getOrNull()
                                        ?.takeIf { it.size == AppleMusicVirtualStream.KID_BYTES }
                                        ?.joinToString("") { "%02x".format(it) }
                            }
                        }
                    }
                    line.startsWith("#EXT-X-MAP") && mediaName == null -> {
                        Regex("URI=\"([^\"]+)\"").find(line)?.let { match -> mediaName = match.groupValues[1] }
                    }
                    !line.startsWith("#") && line.isNotBlank() -> dataLines += line
                }
            }
            // Prefer the EXT-X-MAP name; segments reference the same file.
            val name = mediaName ?: dataLines.firstOrNull() ?: return null
            // A candidate without the 16-byte Widevine KID can never survive
            // AppleMusicVirtualStream.build (throws kid=absent). Skipping it here
            // lets resolveWithToken try the next song id instead of letting one
            // poisoned candidate win the match gate and fail the whole resolve.
            val kid = keyIdHex
            val uri = drmUri
            if (kid == null || uri.isNullOrBlank()) {
                Log.w(TAG, "playlist has no Widevine KID (kid=${keyIdHex ?: "absent"}); skipping candidate")
                return null
            }
            val mediaUrl = playlistUrl.substringBeforeLast('/').trimEnd('/') + "/" + name
            return ParsedPlaylist(mediaUrl, kid, uri, null)
        }
    }
}
