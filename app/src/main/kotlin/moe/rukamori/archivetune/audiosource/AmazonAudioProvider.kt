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
 *
 * Amazon Music source via a user-configured instance. This mirrors the monochrome.tf web player
 * exactly: a metadata lookup against an instance's Amazon track endpoint returns a `stream_url`
 * (a CENC-encrypted fragmented MP4) plus a raw content key. The stream is downloaded and decrypted
 * on-device by [AmazonCencDecryptor] (the web player offloads decryption to the browser's
 * ClearKey/EME stack, which this app lacks).
 *
 * Two protocol generations are supported because hosts in this ecosystem vary:
 *   - gen-2   `GET {base}/api/v2/track/?...&intent=stream&quality=...` (`unified` shape)
 *   - gen-1.5 `GET {base}/api/track/?track=&artist=&album=&duration=&quality=`
 * The gen-2 route is tried first; a 404 means the route is absent on that host, so we fall back to
 * the gen-1.5 route.
 *
 * IMPORTANT — instances are commonly gated behind Cloudflare Turnstile. Requests require either a
 * configured `bypass_token` (set by the instance operator) or a Turnstile JWT obtained interactively
 * via [moe.rukamori.archivetune.ui.screens.settings.AmazonTurnstileActivity]. Without one, the
 * instance rejects the request (HTTP 401/428) and the caller falls through to the next source.
 *
 * No default instance ships: the list starts empty and the user adds their own, exactly like Tidal.
 */

package moe.rukamori.archivetune.audiosource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.AudioSourceType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object AmazonAudioProvider {
    // Historical reference only — the ecosystem has no reliable public default (amz.geeked.wtf is
    // DNS-dead, amz.binimum.org's data routes demand an operator secret the app cannot obtain), so
    // the app ships with an EMPTY instance list and the user supplies their own, like Tidal.
    // const val DEFAULT_INSTANCE = "https://amz.geeked.wtf"

    // Cloudflare Turnstile config used by the official monochrome.tf web player. The sitekey is
    // domain-locked to monochrome.tf, so the challenge must be solved from that origin (see
    // AmazonTurnstileActivity, which renders the widget via loadDataWithBaseURL on that host).
    const val TURNSTILE_SITEKEY = "0x4AAAAAADgxqF6QVMm0GLHH"
    const val TURNSTILE_ORIGIN = "https://monochrome.tf/"

    // Turnstile JWTs mint with a ~1h lifetime; we treat them as valid for a little less to leave a
    // safety margin for clock skew and in-flight requests.
    const val TURNSTILE_JWT_TTL_MS = 60L * 60L * 1000L
    const val TURNSTILE_JWT_SAFETY_MS = 5L * 60L * 1000L

    /** Playback kind the resolved payload pointed at — used only for logging/labels. */
    private const val TAG = "AmazonAudio"

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
            .build()

    /**
     * "Needs (re)authorization" signal for the settings UI. Set to a timestamp the moment an
     * instance answers a track request with HTTP 401/428 (bad/expired JWT, or Turnstile
     * (re)verification required); cleared is not attempted here — the settings screen just reads it
     * to decide whether to nag the user. One-shot status only, deliberately not a StateFlow: nothing
     * else consumes it and PreferenceStore is the config surface.
     */
    @Volatile
    var lastAuthFailureAt: Long = 0L
        private set

    /**
     * Exchanges a solved Cloudflare Turnstile response token for the instance's access JWT, exactly
     * as the web player does: `POST {instance}/api/auth/turnstile` with `{cf_turnstile_response}`,
     * returning the `access_token` field. The returned JWT is sent as the `X-Turnstile-JWT` header
     * on subsequent track/stream requests. Returns null on failure.
     */
    suspend fun exchangeTurnstileToken(
        cfTurnstileResponse: String,
        instanceBaseUrl: String,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    instanceBaseUrl.trimEnd('/').toHttpUrl()
                        .newBuilder()
                        .addPathSegment("api")
                        .addPathSegment("auth")
                        .addPathSegment("turnstile")
                        .build()
                val bodyJson = JSONObject().put("cf_turnstile_response", cfTurnstileResponse).toString()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (ArchiveTune)")
                        .header("Origin", TURNSTILE_ORIGIN)
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful || body.isBlank()) {
                        Timber.tag(TAG).w("Turnstile exchange failed: HTTP %d", response.code)
                        return@use null
                    }
                    JSONObject(body).optString("access_token").ifBlank { null }
                }
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
                Timber.tag(TAG).w(it, "Turnstile exchange error")
                null
            }
        }

    /**
     * Resolves a fully-playable local stream from track metadata, matching exactly what the
     * monochrome.tf web player does for Amazon Music:
     *   1. Try `GET {instance}/api/v2/track/?...&intent=stream&quality=` (gen-2). If it 404s the
     *      route is absent on this host, so fall back to `GET {instance}/api/track/?...` (gen-1.5),
     *      which returns a `stream_url` plus a `decryption_key`.
     *   2. Download the encrypted stream and decrypt it on-device via [AmazonCencDecryptor] (the web
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
        cacheDir: java.io.File,
        instanceBaseUrl: String,
        bypassToken: String? = null,
        quality: String = "HD",
        turnstileJwt: String? = null,
    ): DirectStream? =
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.AMAZON_STREAM_TRANSFER_MS) {
            withContext(Dispatchers.IO) {
            val artist = artists.firstOrNull().orEmpty()
            if (title.isBlank() || artist.isBlank()) {
                Timber.tag(TAG).d("Missing title/artist; skipping Amazon.")
                return@withContext null
            }
            if (bypassToken.isNullOrBlank() && turnstileJwt.isNullOrBlank()) {
                Timber.tag(TAG).d("No Turnstile JWT / bypass token; skipping Amazon (authorize in settings).")
                return@withContext null
            }
            val base = instanceBaseUrl.trim().trimEnd('/')
            if (base.isBlank()) return@withContext null

            // Gen-2 first, gen-1.5 on 404.
            val resolved =
                AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
                    fetchGen2(base, title, artist, album, durationMs, quality, bypassToken, turnstileJwt)
                        ?: fetchGen1(base, title, artist, album, durationMs, quality, bypassToken, turnstileJwt)
                } ?: return@withContext null

            val streamUrl = resolved.streamUrl
            val keyHex = resolved.keyHex
            val codec = resolved.codec
            val bitDepth = resolved.bitDepth
            val sampleRate = resolved.sampleRate

            return@withContext downloadAndPrepare(
                streamUrl = streamUrl,
                keyHex = keyHex,
                codecHint = codec,
                qualityLabel = resolved.qualityLabel,
                bitDepth = bitDepth,
                sampleRate = sampleRate,
                matchedTitle = resolved.matchedTitle ?: title,
                matchedArtist = resolved.matchedArtist ?: artist,
                matchedAlbum = resolved.matchedAlbum ?: album,
                matchedDurationMs = resolved.matchedDurationMs ?: durationMs,
                cacheDir = cacheDir,
            )
            }
        } ?: null

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
    )

    /**
     * Gen-2 request: `GET {base}/api/v2/track/?...&intent=stream&quality=`.
     * Returns null when the route is absent (404) or a non-auth failure occurs, and null on an
     * auth failure so the caller falls through. Auth headers/params are set by [attachAuth].
     */
    private fun fetchGen2(
        base: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        quality: String,
        bypassToken: String?,
        turnstileJwt: String?,
    ): ResolvedStream? {
        val mapped = mapQualityGen2(quality)
        val urlBuilder =
            "$base/api/v2/track/".toHttpUrl()
                .newBuilder()
                .addQueryParameter("track", title)
                .addQueryParameter("artist", artist)
                .addQueryParameter("album", album.orEmpty())
                .addQueryParameter("duration", durationMs?.let { (it / 1000).toString() } ?: "")
                .addQueryParameter("intent", "stream")
                .addQueryParameter("quality", mapped)
        if (!bypassToken.isNullOrBlank()) urlBuilder.addQueryParameter("bypass_token", bypassToken)
        val request =
            Request
                .Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (ArchiveTune)")
                .header("Origin", TURNSTILE_ORIGIN)
                .apply { if (!turnstileJwt.isNullOrBlank()) header("X-Turnstile-JWT", turnstileJwt) }
                .get()
                .build()
        return runCatching {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> {
                        Timber.tag(TAG).d("gen-2 route absent (404); falling back to gen-1.5")
                        null
                    }

                    response.code == 401 || response.code == 428 -> {
                        noteAuthFailure(response.code)
                        null
                    }

                    !response.isSuccessful || body.isBlank() -> {
                        Timber.tag(TAG).w("gen-2 track lookup failed: HTTP %d", response.code)
                        null
                    }

                    else -> parseGen2(body, mapped)
                }
            }
        }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
            Timber.tag(TAG).w(it, "gen-2 track lookup error for \"%s\"", title)
            null
        }
    }

    /**
     * Gen-1.5 request: `GET {base}/api/track/?track=&artist=&album=&duration=&quality=`.
     * Returns null on any failure (including auth) — it never throws — so the caller can
     * treat it as an absent result and fall through.
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
            "$base/api/track/".toHttpUrl()
                .newBuilder()
                .addQueryParameter("track", title)
                .addQueryParameter("artist", artist)
                .addQueryParameter("album", album.orEmpty())
                .addQueryParameter("duration", durationMs?.let { (it / 1000).toString() } ?: "")
                .addQueryParameter("quality", mapped)
        if (!bypassToken.isNullOrBlank()) urlBuilder.addQueryParameter("bypass_token", bypassToken)
        val request =
            Request
                .Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (ArchiveTune)")
                .header("Origin", TURNSTILE_ORIGIN)
                .apply { if (!turnstileJwt.isNullOrBlank()) header("X-Turnstile-JWT", turnstileJwt) }
                .get()
                .build()
        return runCatching {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 || response.code == 428 -> {
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

    /** Parses the gen-2 `playback[]` envelope, picking the first audio/direct-style entry. */
    private fun parseGen2(
        body: String,
        requestedQuality: String,
    ): ResolvedStream? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val playback = root.optJSONArray("playback") ?: return null
        for (i in 0 until playback.length()) {
            val entry = playback.optJSONObject(i) ?: continue
            if (!entry.optString("kind").equals("audio", ignoreCase = true)) continue
            val delivery = entry.optString("delivery").lowercase()
            if (delivery !in setOf("direct", "dash", "hls")) continue
            val url = entry.optString("url").ifBlank { null } ?: continue
            val codec = entry.optString("codec").ifBlank { null }?.lowercase()
            val bitDepth = entry.optInt("bit_depth", 0).takeIf { it > 0 }
            val sampleRate = entry.optInt("sample_rate_hz", 0).takeIf { it > 0 }
            val qualityLabel =
                entry.optString("quality").ifBlank { requestedQuality }
            val keyHex =
                entry.optJSONObject("encryption")
                    ?.optJSONObject("key")
                    ?.optString("value")
                    ?.ifBlank { null }
            val track = root.optJSONObject("track")
            return ResolvedStream(
                streamUrl = url,
                keyHex = keyHex,
                codec = codec,
                qualityLabel = qualityLabel,
                bitDepth = bitDepth,
                sampleRate = sampleRate,
                matchedTitle = track?.optString("title")?.ifBlank { null },
                matchedArtist =
                    track
                        ?.optJSONArray("artists")
                        ?.let { arr -> if (arr.length() > 0) arr.optString(0).ifBlank { null } else null },
                matchedAlbum = track?.optString("album")?.ifBlank { null },
                matchedDurationMs = null,
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
                    bitDepth = q.optInt("bitDepth", 0).takeIf { it > 0 }
                    sampleRate = q.optInt("sampleRate", 0).takeIf { it > 0 }
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
            matchedTitle = payload.optString("title").ifBlank { null },
            matchedArtist = payload.optString("artist").ifBlank { null },
            matchedAlbum = payload.optString("album").ifBlank { null },
            matchedDurationMs = payload.optLong("duration", 0L).let { if (it > 0) it * 1000 else null },
        )
    }

    /** Records a 401/428 as "needs authorization" for the settings UI, and logs it. */
    private fun noteAuthFailure(code: Int) {
        lastAuthFailureAt = System.currentTimeMillis()
        Timber.tag(TAG).w("Amazon requires (re)authorization: HTTP %d", code)
    }

    /** Downloads the (possibly encrypted) stream and, if a key is present, decrypts it to a local file. */
    private fun downloadAndPrepare(
        streamUrl: String,
        keyHex: String?,
        codecHint: String?,
        qualityLabel: String,
        bitDepth: Int?,
        sampleRate: Int?,
        matchedTitle: String,
        matchedArtist: String,
        matchedAlbum: String?,
        matchedDurationMs: Long?,
        cacheDir: java.io.File,
    ): DirectStream? {
        val bytes =
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (ArchiveTune)")
                        .get()
                        .build()
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w("Amazon stream download failed: HTTP %d", response.code)
                        return null
                    }
                    response.body?.bytes()
                }
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
                Timber.tag(TAG).w(it, "Amazon stream download error")
                null
            } ?: return null

        val outCodec: String
        if (keyHex.isNullOrBlank()) {
            // Unencrypted (rare): play as-is.
            outCodec = codecHint ?: "flac"
        } else {
            val decoded = AmazonCencDecryptor.decryptInPlace(bytes, keyHex)
            if (decoded == null) {
                Timber.tag(TAG).w("Amazon CENC decryption failed; skipping.")
                return null
            }
            outCodec = decoded
        }

        val cacheRoot = java.io.File(cacheDir, "amazon").apply { mkdirs() }
        val file = java.io.File(cacheRoot, "amz_${abs(streamUrl.hashCode())}_$qualityLabel.mp4")
        runCatching { file.writeBytes(bytes) }.getOrElse {
            Timber.tag(TAG).w(it, "Failed writing decrypted Amazon file")
            return null
        }
        Timber.tag(TAG).d("Amazon stream ready (%s, %d bytes) -> %s", outCodec, file.length(), file.name)
        val label = buildLabel(qualityLabel, outCodec, bitDepth, sampleRate)
        return DirectStream(
            uri = android.net.Uri.fromFile(file).toString(),
            mimeType = "audio/mp4",
            codecs = outCodec,
            contentLength = file.length(),
            label = label,
            source = AudioSourceType.AMAZON,
            matchedTitle = matchedTitle,
            matchedArtist = matchedArtist,
            matchedAlbum = matchedAlbum,
            matchedDurationMs = matchedDurationMs,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
        )
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

    /** Maps the app's Amazon quality tier to gen-2 codes (HI_RES_LOSSLESS / LOSSLESS / ...). */
    private fun mapQualityGen2(quality: String): String =
        when (quality.trim().uppercase()) {
            "ULTRA_HD", "HI_RES_LOSSLESS", "AUTO", "ADAPTIVE", "DOLBY_ATMOS" -> "HI_RES_LOSSLESS"
            "HD", "LOSSLESS" -> "LOSSLESS"
            "STANDARD", "HIGH" -> "HIGH"
            "LOW" -> "LOW"
            "NORMAL" -> "NORMAL"
            else -> "LOSSLESS"
        }

    /** Maps the app's Amazon quality tier to gen-1.5 codes (UHD / HD / SD_*; see FACTSHEET). */
    private fun mapQualityGen1(quality: String): String =
        when (quality.trim().uppercase()) {
            "ULTRA_HD", "HI_RES_LOSSLESS", "AUTO", "ADAPTIVE", "DOLBY_ATMOS" -> "UHD"
            "HD", "LOSSLESS" -> "HD"
            "STANDARD", "HIGH" -> "SD_HIGH"
            "LOW" -> "SD_LOW"
            "NORMAL" -> "SD_MEDIUM"
            else -> "HD"
        }

    /** Extracts the raw CENC content key from a gen-1.5 payload, checking all known field names. */
    private fun extractDecryptionKey(payload: JSONObject): String? =
        payload.optString("decryption_key").ifBlank { null }
            ?: payload.optString("decryptionKey").ifBlank { null }
            ?: payload.optJSONObject("decryption")?.optString("key")?.ifBlank { null }
            ?: payload.optJSONObject("drm")?.optString("decryption_key")?.ifBlank { null }
            ?: payload.optJSONObject("drm")?.optString("decryptionKey")?.ifBlank { null }

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