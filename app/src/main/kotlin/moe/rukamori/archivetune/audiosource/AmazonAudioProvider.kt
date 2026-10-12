/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Amazon Music source via a user-configured instance, following the monochrome.tf web player (its
 * Amazon support was removed in September 2026): a metadata lookup against an instance's track
 * endpoint returns a `stream_url` (a CENC-encrypted fragmented MP4) plus a raw content key. The
 * stream is downloaded and decrypted on-device by [AmazonCencDecryptor] (the web player offloads
 * decryption to the browser's ClearKey/EME stack, which this app lacks).
 * Two protocol generations are supported because hosts in this ecosystem vary:
 * - gen-2   `GET {base}/api/v2/track/?...&intent=stream&quality=...` (`unified` shape), which
 *   requires the operator API key as `Authorization: Bearer`
 * - gen-1.5 `GET {base}/api/track/?track=&artist=&album=&duration=&quality=`, which takes the same
 *   operator token as `bypass_token`, or a Turnstile JWT alone
 * Gen-2 is tried first when there is an API key; a route-level 404 or an authorization refusal
 * falls back to gen-1.5.
 * IMPORTANT — instances are commonly gated behind Cloudflare Turnstile. Requests require either a
 * configured operator token or a Turnstile JWT obtained interactively via
 * [moe.rukamori.archivetune.ui.screens.settings.AmazonTurnstileActivity]. Without one, the
 * instance rejects the request (HTTP 401/428) and the caller falls through to the next source.
 * No default instance ships: the list starts empty and the user adds their own, exactly like Tidal.
 */



package moe.rukamori.archivetune.audiosource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.AudioSourceType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import moe.rukamori.archivetune.utils.withAppProxy

object AmazonAudioProvider {
    // No public default ships. The web player's gateway (music-api.geeked.wtf, also answering as
    // amz.geeked.wtf) no longer lists Amazon among its gen-2 sources, and its gen-1.5 routes proxy an
    // upstream (amz.binimum.org) that has been unreachable for months, so the instance list starts
    // EMPTY and the user supplies their own, like Tidal.

    // Cloudflare Turnstile config used by the official monochrome.tf web player. The sitekey is
    // domain-locked to monochrome.tf, so the challenge must be solved from that origin (see
    // AmazonTurnstileActivity, which renders the widget via loadDataWithBaseURL on that host). A
    // token solved for this sitekey only verifies against the secret paired with it, so only hosts
    // that share the web player's Turnstile setup can exchange it; a self-hosted instance with its
    // own Turnstile widget needs its operator token instead.
    const val TURNSTILE_SITEKEY = "0x4AAAAAADgxqF6QVMm0GLHH"
    const val TURNSTILE_ORIGIN = "https://monochrome.tf/"

    // Turnstile JWTs mint with a ~1h lifetime; we treat them as valid for a little less to leave a
    // safety margin for clock skew and in-flight requests.
    const val TURNSTILE_JWT_TTL_MS = 60L * 60L * 1000L
    const val TURNSTILE_JWT_SAFETY_MS = 5L * 60L * 1000L

    private const val TAG = "AmazonAudio"

    private const val USER_AGENT = "Mozilla/5.0 (ArchiveTune)"

    /** An Origin header is scheme://host with no path, so the base URL's slash has to go. */
    private val ORIGIN_HEADER = TURNSTILE_ORIGIN.trimEnd('/')

    /**
     * Budget for the metadata lookup, both route generations together. The instance answers only
     * once it has the stream ready, which the web player allowed 15-20 s for; whatever is left of the
     * source attempt goes to the download.
     */
    private const val LOOKUP_TIMEOUT_MS = 15_000L

    /**
     * Decrypted tracks are pruned oldest-first past this much, the way the QQ Music source prunes
     * its own. Stream URLs are single-use, so every resolve writes a new file and an unbounded
     * directory would only grow.
     */
    private const val CACHE_LIMIT_BYTES = 300L * 1024 * 1024

    /** The newest files are what is playing and what was preloaded; pruning never takes them. */
    private const val CACHE_KEEP_NEWEST = 3

    /** A `.part` younger than this may still be downloading for another resolve. */
    private const val PART_FILE_STALE_MS = 10L * 60L * 1000L

    private const val RATE_LIMIT_DEFAULT_MS = 60_000L
    private const val RATE_LIMIT_MAX_MS = 15L * 60L * 1000L

    // The instance prepares the CENC stream server-side (fetching + packaging from Amazon) before
    // the track endpoint responds, and the subsequent full-file download can be tens of MB. So we
    // keep a short connect timeout (to fail fast on dead hosts) but a generous read timeout, and no
    // overall callTimeout (an overall cap would truncate large, legitimately-slow lossless downloads).
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .withAppProxy()
            .build()

    /**
     * "Needs (re)authorization" signal for the settings UI. Set to a timestamp the moment an
     * instance answers a track request with HTTP 401/403/428 (bad/expired JWT or API key, or
     * Turnstile (re)verification required); cleared is not attempted here — the settings screen just
     * reads it to decide whether to nag the user. One-shot status only, deliberately not a
     * StateFlow: nothing else consumes it and PreferenceStore is the config surface.
     */
    @Volatile
    var lastAuthFailureAt: Long = 0L
        private set

    /** Instance base URL -> epoch ms until which it asked (HTTP 429) not to be called. */
    private val rateLimitedUntil = ConcurrentHashMap<String, Long>()

    /**
     * Exchanges a solved Cloudflare Turnstile response token for the instance's access JWT:
     * `POST {instance}/api/auth/turnstile`, answered with `access_token`. The token travels under
     * both names the web player has used, `cf_turnstile_response` (gen-1.5, and what the live
     * gateway still requires) and `turnstile_token` (the gen-2 client), and the operator API key, when
     * there is one, goes along as `Authorization: Bearer`, which gen-2 demands on every call. The
     * returned JWT is sent as the `X-Turnstile-JWT` header on track requests. Returns null on failure.
     */
    suspend fun exchangeTurnstileToken(
        cfTurnstileResponse: String,
        instanceBaseUrl: String,
        apiToken: String? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    instanceBaseUrl.trim().trimEnd('/').toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addPathSegment("api")
                        ?.addPathSegment("auth")
                        ?.addPathSegment("turnstile")
                        ?.build()
                        ?: return@runCatching null
                val bodyJson =
                    JSONObject()
                        .put("cf_turnstile_response", cfTurnstileResponse)
                        .put("turnstile_token", cfTurnstileResponse)
                        .toString()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Origin", ORIGIN_HEADER)
                        .header("Accept", "application/json")
                        .apply { if (!apiToken.isNullOrBlank()) header("Authorization", "Bearer $apiToken") }
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful || body.isBlank()) {
                        Timber.tag(TAG).w("Turnstile exchange failed: HTTP %d", response.code)
                        return@use null
                    }
                    val json = JSONObject(body)
                    listOf("access_token", "jwt", "token").firstNotNullOfOrNull { field ->
                        (json.opt(field) as? String)?.trim()?.ifBlank { null }
                    }
                }
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
                Timber.tag(TAG).w(it, "Turnstile exchange error")
                null
            }
        }

    /** The `exp` claim of [jwt] in epoch ms, or null when it is not a JWT or carries no expiry. */
    fun jwtExpiresAtMs(jwt: String): Long? {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        return runCatching {
            val claims = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
            JSONObject(claims).optLong("exp", 0L).takeIf { it > 0L }?.times(1000L)
        }.getOrNull()
    }

    /**
     * Resolves a fully-playable local stream from track metadata, matching what the monochrome.tf
     * web player did for Amazon Music:
     *   1. With an operator API key, try `GET {instance}/api/v2/track/?...&intent=stream&quality=`
     *      (gen-2). A route-level 404 or an authorization refusal moves on to
     *      `GET {instance}/api/track/?...` (gen-1.5), which returns a `stream_url` plus a
     *      `decryption_key`. Without an API key gen-2 cannot answer, so gen-1.5 is asked directly.
     *   2. Gate the returned metadata with [TitleMatch] before downloading anything.
     *   3. Download the encrypted stream and decrypt it on-device via [AmazonCencDecryptor] (the web
     *      player offloads this to the browser's ClearKey/EME stack; we have no EME so we do it
     *      ourselves), writing a plain FLAC/Opus-in-MP4 to [cacheDir].
     *
     * Returns a [DirectStream] pointing at the decrypted local file, or null if no match is found or
     * the instance rejects the request (e.g. Turnstile 401/428) so the caller can fall through.
     * `trustedDirectId` is set by the caller, not here.
     */
    suspend fun resolveByMetadata(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        cacheDir: File,
        instanceBaseUrl: String,
        bypassToken: String? = null,
        quality: String = "HD",
        turnstileJwt: String? = null,
    ): DirectStream? {
        val resolved =
            findMatchingStream(title, artists, album, durationMs, instanceBaseUrl, bypassToken, quality, turnstileJwt)
                ?: return null
        // The search above shares the source chain's budget; the download does not, so a slow
        // connection still gets the file that matched instead of a fallback.
        return AudioSourceAttemptScope.withinTransfer {
            withContext(Dispatchers.IO) { downloadAndPrepare(resolved, cacheDir) }
        }
    }

    private suspend fun findMatchingStream(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        instanceBaseUrl: String,
        bypassToken: String?,
        quality: String,
        turnstileJwt: String?,
    ): ResolvedStream? =
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.WHOLE_FILE_SOURCE_SEARCH_MS) {
            withContext(Dispatchers.IO) {
            // The web player sent every credited artist, and the instance matches against all of them.
            val artist = artists.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            if (title.isBlank() || artist.isBlank()) {
                Timber.tag(TAG).d("Missing title/artist; skipping Amazon.")
                return@withContext null
            }
            if (bypassToken.isNullOrBlank() && turnstileJwt.isNullOrBlank()) {
                Timber.tag(TAG).d("No Turnstile JWT / bypass token; skipping Amazon (authorize in settings).")
                return@withContext null
            }
            val base = instanceBaseUrl.trim().trimEnd('/')
            if (base.toHttpUrlOrNull() == null) {
                Timber.tag(TAG).w("Not an http(s) instance URL: %s", base)
                return@withContext null
            }
            val limitedUntil = rateLimitedUntil[base] ?: 0L
            if (limitedUntil > System.currentTimeMillis()) {
                Timber.tag(TAG).d("%s asked to back off; skipping it for now", base)
                return@withContext null
            }

            val resolved =
                AudioSourceAttemptScope.within(LOOKUP_TIMEOUT_MS) {
                    val gen2 =
                        if (bypassToken.isNullOrBlank()) {
                            Lookup.TryOlderRoute
                        } else {
                            fetchGen2(base, title, artist, album, durationMs, quality, bypassToken, turnstileJwt)
                        }
                    when (gen2) {
                        is Lookup.Found -> gen2.stream
                        Lookup.TryOlderRoute ->
                            fetchGen1(base, title, artist, album, durationMs, quality, bypassToken, turnstileJwt)
                        Lookup.Declined -> null
                    }
                } ?: return@withContext null

            // A lossless file is tens of megabytes, and the playback layer's gate would only reject a
            // wrong recording after all of it had arrived. The metadata is the instance's own; the
            // query's values are never substituted for missing ones, or the gate would compare the
            // query with itself.
            val match =
                TitleMatch.evaluate(
                    wantedTitle = title,
                    wantedArtists = artists,
                    wantedAlbum = album,
                    wantedDurationMs = durationMs,
                    stream = resolved.toDirectStream(uri = resolved.streamUrl, codec = resolved.codec.orEmpty(), contentLength = null),
                )
            if (!match.accepted) {
                Timber.tag(TAG).i("Amazon match for \"%s\" rejected before download: %s", title, match.reason)
                return@withContext null
            }

            return@withContext resolved
            }
        }

    /** A normalized instance response across both protocol generations. */
    private data class ResolvedStream(
        val streamUrl: String,
        val keyHex: String?,
        val codec: String?,
        val qualityLabel: String,
        val bitDepth: Int?,
        val sampleRate: Int?,
        val matchedTitle: String?,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val matchedDurationMs: Long?,
    ) {
        fun toDirectStream(
            uri: String,
            codec: String,
            contentLength: Long?,
        ): DirectStream =
            DirectStream(
                uri = uri,
                mimeType = "audio/mp4",
                codecs = codec,
                contentLength = contentLength,
                label = buildLabel(qualityLabel, codec, bitDepth, sampleRate),
                source = AudioSourceType.AMAZON,
                matchedTitle = matchedTitle,
                matchedArtist = matchedArtist,
                matchedAlbum = matchedAlbum,
                matchedDurationMs = matchedDurationMs,
                bitDepth = bitDepth,
                sampleRate = sampleRate,
            )
    }

    private sealed interface Lookup {
        data class Found(
            val stream: ResolvedStream,
        ) : Lookup

        /** Gen-2 is absent on the host, or refused credentials that gen-1.5 accepts differently. */
        data object TryOlderRoute : Lookup

        data object Declined : Lookup
    }

    /**
     * Gen-2 request: `GET {base}/api/v2/track/?...&intent=stream&quality=`, authorized by the
     * operator API key as `Authorization: Bearer` (the only credential gen-2 accepts; the Turnstile
     * JWT rides along when there is one). A 404/502 that still carries the gen-2 envelope means the
     * track was not found, not that the route is missing.
     */
    private fun fetchGen2(
        base: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        quality: String,
        apiToken: String,
        turnstileJwt: String?,
    ): Lookup {
        val mapped = mapQualityGen2(quality)
        val urlBuilder = "$base/api/v2/track/".toHttpUrlOrNull()?.newBuilder() ?: return Lookup.Declined
        urlBuilder.addQueryParameter("track", title).addQueryParameter("artist", artist)
        // Unlike gen-1.5, absent fields are left out rather than sent empty, as the web player did.
        album?.trim()?.takeIf { it.isNotEmpty() }?.let { urlBuilder.addQueryParameter("album", it) }
        durationSeconds(durationMs)?.let { urlBuilder.addQueryParameter("duration", it.toString()) }
        urlBuilder.addQueryParameter("intent", "stream").addQueryParameter("quality", mapped)
        val request =
            Request
                .Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN_HEADER)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $apiToken")
                .apply { if (!turnstileJwt.isNullOrBlank()) header("X-Turnstile-JWT", turnstileJwt) }
                .get()
                .build()
        return runCatching {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                val body = response.body.string()
                when (response.code) {
                    429 -> {
                        noteRateLimited(base, response.header("Retry-After"))
                        Lookup.Declined
                    }

                    401, 403, 428 -> {
                        Timber.tag(TAG).d("gen-2 refused authorization (HTTP %d); trying gen-1.5", response.code)
                        Lookup.TryOlderRoute
                    }

                    404, 502 ->
                        if (isGen2Envelope(body)) {
                            Timber.tag(TAG).d("gen-2 could not resolve the track (HTTP %d)", response.code)
                            Lookup.Declined
                        } else {
                            Timber.tag(TAG).d("gen-2 route absent (HTTP %d); falling back to gen-1.5", response.code)
                            Lookup.TryOlderRoute
                        }

                    else ->
                        if (!response.isSuccessful || body.isBlank()) {
                            Timber.tag(TAG).w("gen-2 track lookup failed: HTTP %d", response.code)
                            Lookup.Declined
                        } else {
                            parseGen2(body, mapped)?.let { Lookup.Found(it) } ?: Lookup.Declined
                        }
                }
            }
        }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
            Timber.tag(TAG).w(it, "gen-2 track lookup error for \"%s\"", title)
            Lookup.Declined
        }
    }

    /**
     * Gen-1.5 request: `GET {base}/api/track/?track=&artist=&album=&duration=&quality=`, authorized
     * by `bypass_token` or the `X-Turnstile-JWT` header. Returns null on any failure (including
     * auth) — it never throws — so the caller can treat it as an absent result and fall through.
     */
    private fun fetchGen1(
        base: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        quality: String,
        bypassToken: String?,
        turnstileJwt: String?,
    ): ResolvedStream? {
        val mapped = mapQualityGen1(quality)
        val urlBuilder =
            "$base/api/track/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("track", title)
                ?.addQueryParameter("artist", artist)
                ?.addQueryParameter("album", album.orEmpty())
                ?.addQueryParameter("duration", durationSeconds(durationMs)?.toString().orEmpty())
                ?.addQueryParameter("quality", mapped)
                ?: return null
        if (!bypassToken.isNullOrBlank()) urlBuilder.addQueryParameter("bypass_token", bypassToken)
        val request =
            Request
                .Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN_HEADER)
                .header("Accept", "application/json")
                .apply { if (!turnstileJwt.isNullOrBlank()) header("X-Turnstile-JWT", turnstileJwt) }
                .get()
                .build()
        return runCatching {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                val body = response.body.string()
                when {
                    response.code == 429 -> {
                        noteRateLimited(base, response.header("Retry-After"))
                        null
                    }

                    response.code == 401 || response.code == 403 || response.code == 428 -> {
                        noteAuthFailure(response.code)
                        null
                    }

                    !response.isSuccessful || body.isBlank() -> {
                        Timber.tag(TAG).w("gen-1.5 track lookup failed: HTTP %d", response.code)
                        null
                    }

                    else -> parseGen1(body, mapped)
                }
            }
        }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
            Timber.tag(TAG).w(it, "gen-1.5 track lookup error for \"%s\"", title)
            null
        }
    }

    private fun isGen2Envelope(body: String): Boolean {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return root.has("schema_version") || root.has("playback") || root.has("sources")
    }

    /** Parses the gen-2 `playback[]` envelope, picking the first directly downloadable Amazon entry. */
    private fun parseGen2(
        body: String,
        requestedQuality: String,
    ): ResolvedStream? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val schemaVersion = root.optString("schema_version")
        val schemaMajor = schemaVersion.substringBefore('.')
        if (schemaMajor.isNotEmpty() && schemaMajor != "1" && schemaMajor != "2") {
            Timber.tag(TAG).w("Unsupported gen-2 schema version %s", schemaVersion)
            return null
        }
        val playback = root.optJSONArray("playback") ?: return null
        val track = root.optJSONObject("track")
        for (i in 0 until playback.length()) {
            val entry = playback.optJSONObject(i) ?: continue
            if (!entry.optString("kind").equals("audio", ignoreCase = true)) continue
            // Only a whole file can be downloaded and decrypted here; dash/hls entries are manifests.
            if (!entry.optString("delivery").equals("direct", ignoreCase = true)) continue
            // The unified API also serves other catalogues (`mono`, `tidal`); that audio is not
            // Amazon's and must not be played or labelled as such.
            val source = entry.optString("source").ifBlank { root.optString("selected_source") }
            if (source.isNotBlank() && !source.equals("amazon", ignoreCase = true)) continue
            val url = entry.optString("url").takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } ?: continue
            val quality = entry.optString("quality").ifBlank { requestedQuality }
            return ResolvedStream(
                streamUrl = url,
                keyHex = extractDecryptionKey(entry),
                codec = gen2Codec(entry, quality),
                qualityLabel = quality,
                bitDepth = firstPositiveInt(entry, "bit_depth", "bitDepth"),
                sampleRate = firstPositiveInt(entry, "sample_rate_hz", "sampleRateHz", "sample_rate", "sampleRate"),
                matchedTitle = track?.let(::titleOf),
                matchedArtist = track?.let(::artistNames),
                matchedAlbum = track?.let(::albumName),
                matchedDurationMs = track?.optLong("duration_ms", 0L)?.takeIf { it > 0L },
            )
        }
        return null
    }

    /** Parses the gen-1.5 unwrapped payload. */
    private fun parseGen1(
        body: String,
        requestedQuality: String,
    ): ResolvedStream? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val payload =
            when {
                root.has("stream_url") -> root
                root.optJSONObject("data")?.has("stream_url") == true -> root.getJSONObject("data")
                root.optJSONObject("track")?.has("stream_url") == true -> root.getJSONObject("track")
                root.optJSONObject("result")?.has("stream_url") == true -> root.getJSONObject("result")
                else -> root
            }
        val streamUrl = payload.optString("stream_url").ifBlank { null } ?: return null
        val codec = extractCodec(payload)
        val selectedQuality = payload.optString("quality_selected").ifBlank { requestedQuality }
        var bitDepth: Int? = null
        var sampleRate: Int? = null
        payload.optJSONArray("available_qualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                if (q.optString("quality") == selectedQuality) {
                    bitDepth = firstPositiveInt(q, "bitDepth", "bit_depth")
                    sampleRate = firstPositiveInt(q, "sampleRate", "sample_rate", "sample_rate_hz")
                    break
                }
            }
        }
        return ResolvedStream(
            streamUrl = streamUrl,
            keyHex = extractDecryptionKey(payload),
            codec = codec,
            qualityLabel = selectedQuality,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            matchedTitle = titleOf(payload),
            matchedArtist = artistNames(payload),
            matchedAlbum = albumName(payload),
            matchedDurationMs = durationMsOf(payload.optDouble("duration", 0.0)),
        )
    }

    private fun titleOf(obj: JSONObject): String? =
        ((obj.opt("title") as? String) ?: (obj.opt("name") as? String))?.trim()?.ifBlank { null }

    /** `artists` as strings or `{name}` objects (both shapes are served), else a plain `artist`. */
    private fun artistNames(obj: JSONObject): String? {
        obj.optJSONArray("artists")?.let { arr ->
            val names =
                (0 until arr.length()).mapNotNull { i -> nameOf(arr.opt(i), "name", "title") }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return nameOf(obj.opt("artist"), "name")
    }

    private fun albumName(obj: JSONObject): String? = nameOf(obj.opt("album"), "title", "name")

    /** A plain string, or the first non-blank of [keys] when the value is an object. */
    private fun nameOf(
        value: Any?,
        vararg keys: String,
    ): String? {
        val name =
            if (value is JSONObject) {
                keys.firstNotNullOfOrNull { key -> value.optString(key).ifBlank { null } }
            } else {
                value as? String
            }
        return name?.trim()?.ifBlank { null }
    }

    /** Durations arrive in seconds or milliseconds depending on the host; anything past ~2.8 h is ms. */
    private fun durationMsOf(value: Double): Long? =
        when {
            value.isNaN() || value <= 0.0 -> null
            value > 10_000.0 -> value.toLong()
            else -> (value * 1000.0).toLong()
        }

    private fun durationSeconds(durationMs: Long?): Long? = durationMs?.takeIf { it > 0L }?.let { (it + 500L) / 1000L }

    private fun firstPositiveInt(
        obj: JSONObject,
        vararg keys: String,
    ): Int? = keys.firstNotNullOfOrNull { key -> obj.optInt(key, 0).takeIf { it > 0 } }

    /** Records a 401/403/428 as "needs authorization" for the settings UI, and logs it. */
    private fun noteAuthFailure(code: Int) {
        lastAuthFailureAt = System.currentTimeMillis()
        Timber.tag(TAG).w("Amazon requires (re)authorization: HTTP %d", code)
    }

    /** Honours a 429's Retry-After (seconds; a date or nothing falls back to a minute), capped. */
    private fun noteRateLimited(
        base: String,
        retryAfter: String?,
    ) {
        val waitMs =
            (retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.times(1000L) ?: RATE_LIMIT_DEFAULT_MS)
                .coerceAtMost(RATE_LIMIT_MAX_MS)
        rateLimitedUntil[base] = System.currentTimeMillis() + waitMs
        Timber.tag(TAG).w("%s is rate limiting; backing off for %d s", base, waitMs / 1000L)
    }

    /** Downloads the (possibly encrypted) stream to a file and, if a key is present, decrypts it there. */
    private suspend fun downloadAndPrepare(
        resolved: ResolvedStream,
        cacheDir: File,
    ): DirectStream? {
        val cacheRoot = File(cacheDir, "amazon").apply { mkdirs() }
        pruneCache(cacheRoot)
        val safeQuality = resolved.qualityLabel.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        val name = "amz_${System.currentTimeMillis()}_${abs(resolved.streamUrl.hashCode())}_$safeQuality.mp4"
        val file = File(cacheRoot, name)
        val part = File(cacheRoot, "$name.part")
        if (!download(resolved.streamUrl, part)) {
            part.delete()
            return null
        }

        val keyHex = resolved.keyHex
        val outCodec =
            if (keyHex.isNullOrBlank()) {
                // Unencrypted (rare): play as-is.
                resolved.codec ?: "flac"
            } else {
                decryptFile(part, keyHex)
            }
        if (outCodec == null) {
            Timber.tag(TAG).w("Amazon CENC decryption failed; skipping.")
            part.delete()
            return null
        }
        if (!part.renameTo(file)) {
            Timber.tag(TAG).w("Could not move the decrypted Amazon file into place")
            part.delete()
            return null
        }
        Timber.tag(TAG).d("Amazon stream ready (%s, %d bytes) -> %s", outCodec, file.length(), file.name)
        return resolved.toDirectStream(
            uri = android.net.Uri.fromFile(file).toString(),
            codec = outCodec,
            contentLength = file.length(),
        )
    }

    /**
     * Streams the body to [target] rather than into memory: a hi-res track runs to a couple of
     * hundred megabytes, more than the default heap of many phones.
     */
    private suspend fun download(
        url: String,
        target: File,
    ): Boolean =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            client.newCall(request).withAudioSourceAttemptDeadline().executeCancellable().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Amazon stream download failed: HTTP %d", response.code)
                    return@use false
                }
                response.body.byteStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                true
            }
        }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
            Timber.tag(TAG).w(it, "Amazon stream download error")
            false
        }

    /** Decrypts [file] through a read-write mapping, so the track never has to fit on the heap. */
    private fun decryptFile(
        file: File,
        keyHex: String,
    ): String? =
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0L, channel.size())
                AmazonCencDecryptor.decryptInPlace(mapped, keyHex)?.also { mapped.force() }
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "Could not open the downloaded Amazon stream for decryption")
            null
        }

    /**
     * Keeps the directory under [CACHE_LIMIT_BYTES], oldest first, never touching the newest
     * [CACHE_KEEP_NEWEST] tracks or a `.part` that another resolve may still be writing.
     */
    private fun pruneCache(directory: File) {
        val now = System.currentTimeMillis()
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        files
            .filter { it.name.endsWith(".part") && now - it.lastModified() > PART_FILE_STALE_MS }
            .forEach { it.delete() }
        val tracks = files.filterNot { it.name.endsWith(".part") }.sortedBy { it.lastModified() }
        var total = tracks.sumOf { it.length() }
        for (stale in tracks.dropLast(CACHE_KEEP_NEWEST)) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= stale.length()
            stale.delete()
        }
    }

    /** e.g. "Amazon Music UHD (flac 24/48)"; drops missing pieces rather than guessing. */
    private fun buildLabel(
        qualityLabel: String,
        codec: String?,
        bitDepth: Int?,
        sampleRate: Int?,
    ): String {
        val bits = StringBuilder()
        codec?.takeIf { it.isNotBlank() }?.let { bits.append(it) }
        bitDepth?.let { bits.append(if (bits.isEmpty()) "" else " ").append(it) }
        val khz = sampleRate?.let { it / 1000.0 }
        if (khz != null) {
            bits.append(if (bits.isEmpty()) "" else "/").append(if (khz % 1.0 == 0.0) khz.toInt() else khz)
        }
        return if (bits.isEmpty()) {
            "Amazon Music $qualityLabel"
        } else {
            "Amazon Music $qualityLabel (${bits})"
        }
    }

    /**
     * Maps the app's Amazon quality tier to the gen-2 request tokens, which are the web player's
     * shared (Tidal-style) tier names; the instance translates them to Amazon's UHD/HD/SD tiers.
     */
    private fun mapQualityGen2(quality: String): String =
        when (quality.trim().uppercase()) {
            "ULTRA_HD", "HI_RES_LOSSLESS", "AUTO", "ADAPTIVE", "DOLBY_ATMOS" -> "HI_RES_LOSSLESS"
            "HD", "LOSSLESS" -> "LOSSLESS"
            "STANDARD", "HIGH" -> "HIGH"
            "LOW" -> "LOW"
            "NORMAL" -> "NORMAL"
            else -> "LOSSLESS"
        }

    /** Maps the app's Amazon quality tier to Amazon's own tier names (UHD / HD / SD_*), as gen-1.5 takes them. */
    private fun mapQualityGen1(quality: String): String =
        when (quality.trim().uppercase()) {
            "ULTRA_HD", "HI_RES_LOSSLESS", "AUTO", "ADAPTIVE", "DOLBY_ATMOS" -> "UHD"
            "HD", "LOSSLESS" -> "HD"
            "STANDARD", "HIGH" -> "SD_HIGH"
            "LOW" -> "SD_LOW"
            "NORMAL" -> "SD_MEDIUM"
            else -> "HD"
        }

    /** The raw CENC content key, under any of the field names either generation has used. */
    private fun extractDecryptionKey(payload: JSONObject): String? {
        val decryption = payload.optJSONObject("decryption")
        val drm = payload.optJSONObject("drm")
        return sequenceOf(
            payload.opt("decryption_key"),
            payload.opt("decryptionKey"),
            payload.optJSONObject("encryption")?.optJSONObject("key")?.opt("value"),
            decryption?.optJSONObject("key")?.opt("value"),
            decryption?.opt("key"),
            drm?.opt("decryption_key"),
            drm?.opt("decryptionKey"),
        ).mapNotNull { (it as? String)?.trim()?.ifBlank { null } }.firstOrNull()
    }

    /**
     * The gen-2 instance has been seen labelling UHD FLAC as `aac`, so the tier decides first, as in
     * the web player. Only the unencrypted path uses this; a decrypted file reports its own `frma`.
     */
    private fun gen2Codec(
        entry: JSONObject,
        quality: String,
    ): String? {
        val tier = quality.trim().uppercase()
        return when {
            Regex("^(UHD|HD|HI_RES_LOSSLESS|LOSSLESS)(_|$)").containsMatchIn(tier) -> "flac"
            Regex("^(SD|HIGH|LOW)(_|$)").containsMatchIn(tier) -> "opus"
            else -> entry.optString("codec").ifBlank { null }?.lowercase()
        }
    }

    /** Reads the codec (flac/opus) for the selected quality, falling back to the top-level codec. */
    private fun extractCodec(payload: JSONObject): String? {
        val selected = payload.optString("quality_selected")
        val available = payload.optJSONArray("available_qualities")
        if (available != null && selected.isNotBlank()) {
            for (i in 0 until available.length()) {
                val q = available.optJSONObject(i) ?: continue
                if (q.optString("quality") == selected) {
                    q.optString("codec").ifBlank { null }?.let { return it.lowercase() }
                }
            }
        }
        return payload.optString("codec").ifBlank { null }?.lowercase()
    }
}
