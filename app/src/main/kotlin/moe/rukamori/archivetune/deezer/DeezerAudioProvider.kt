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

package moe.rukamori.archivetune.deezer

import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.audiosource.TrackMatching
import moe.rukamori.archivetune.utils.PoolAccountManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves playable Deezer streams for a track, mirroring the shape of
 * [moe.rukamori.archivetune.qobuz.QobuzAudioProvider] so the two are interchangeable at the call
 * site.
 */
object DeezerAudioProvider {
    /**
     * Catalogue metadata for a track, with no playable stream attached. Returned by [lookup] and kept
     * separate from [Resolved] because callers that only enrich stored metadata (artwork, album name,
     * ISRC) must not have to satisfy the streaming contract.
     */
    data class Metadata(
        val trackId: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val previewUrl: String?,
        val coverUrl: String?,
    )

    private const val TAG = "Deezer"
    private const val MIN_MATCH_SCORE = 0.55
    private const val SEARCH_LIMIT = 10
    private const val SEARCH_CACHE_MS = 10 * 60 * 1000L
    private const val STREAM_CACHE_MS = 30 * 60 * 1000L
    private const val FAILURE_CACHE_MS = 10 * 60 * 1000L

    /** Deezer expires stream URLs well before this, but sessions are reused across resolves. */
    private const val SESSION_TTL_MS = 45 * 60 * 1000L

    private const val GATEWAY = "https://www.deezer.com/ajax/gw-light.php"
    private const val MEDIA_ENDPOINT = "https://media.deezer.com/v1/get_url"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private const val MIME_FLAC = "audio/flac"
    private const val MIME_MPEG = "audio/mpeg"

    const val FORMAT_FLAC = "FLAC"
    const val FORMAT_MP3_320 = "MP3_320"
    const val FORMAT_MP3_128 = "MP3_128"

    /** Deezer's tiers, best first. Ordering matters: a resolve offers the requested tier and all below. */
    private val FORMAT_TIERS = listOf(FORMAT_FLAC, FORMAT_MP3_320, FORMAT_MP3_128)

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

    /**
     * A manually captured ARL, set by the Deezer login screen and mirrored from DataStore on startup.
     * Held here rather than in [PoolAccountManager] because that cache is replaced wholesale on every
     * pool refresh and is gated behind `isEnabled`, either of which would drop a user's own account.
     */
    @Volatile
    private var manualAccount: PoolAccountManager.DeezerPoolAccount? = null

    /**
     * Registers (or clears, on null/blank) the manually signed-in account. Cheap and idempotent, so
     * call sites can push the current value without tracking whether it changed.
     */
    fun setManualArl(
        arl: String?,
        premium: Boolean = false,
    ) {
        val trimmed = arl?.trim()
        val next =
            if (trimmed.isNullOrEmpty()) {
                null
            } else {
                // masterSecret stays null: only the pool serves an override, and DeezerCrypto falls
                // back to the salt the app ships with. id null = a manual account, never reported.
                PoolAccountManager.DeezerPoolAccount(id = null, arl = trimmed, premium = premium)
            }
        val previous = manualAccount
        manualAccount = next
        // Sessions are keyed by ARL, so a changed or removed credential must not keep serving from a
        // session established under the old one.
        if (previous?.arl != next?.arl) {
            previous?.let { sessions.remove(it.arl) }
            // Failures were recorded under the old credential: a new sign-in (or sign-out) must not
            // inherit another credential's 'unresolvable' verdict for up to FAILURE_CACHE_MS.
            failureCache.clear()
        }
    }

    /** True when at least one credential (manual or pooled) is available. Cheap: no network. */
    fun hasAccounts(): Boolean = manualAccount != null || PoolAccountManager.deezerAccounts().isNotEmpty()

    /**
     * What credentials exist right now, split by origin. Exists so diagnostics and settings copy can
     * tell a user with their own ARL apart from one relying on the shared pool — reading
     * [PoolAccountManager.deezerAccounts] directly gets that wrong and reports "no Deezer accounts"
     * to somebody who just signed in successfully.
     */
    data class AccountAvailability(
        val manual: Boolean,
        val manualPremium: Boolean,
        val pooled: Int,
        val pooledPremium: Int,
    ) {
        val total: Int get() = pooled + if (manual) 1 else 0
    }

    /** Cheap snapshot of [AccountAvailability]; no network. */
    fun accountAvailability(): AccountAvailability {
        val manual = manualAccount
        val pooled = PoolAccountManager.deezerAccounts().filter { it.arl != manual?.arl }
        return AccountAvailability(
            manual = manual != null,
            manualPremium = manual?.premium == true,
            pooled = pooled.size,
            pooledPremium = pooled.count { it.premium },
        )
    }

    /**
     * Verifies the credential that [resolve] would reach for first, so a diagnostic can report
     * whether the ARL is actually still good rather than just that one is stored. Blocking network
     * I/O; returns null when there is no credential or the gateway rejects it.
     */
    fun verifyPreferredAccount(): AccountInfo? = accounts().firstOrNull()?.let { verifyArl(it.arl) }

    /**
     * Every usable credential, manual first so a user's own (likely paid) account is tried before
     * shared pool entries.
     */
    private fun accounts(): List<PoolAccountManager.DeezerPoolAccount> {
        val pooled = PoolAccountManager.deezerAccounts()
        val manual = manualAccount ?: return pooled
        // Drop a pooled duplicate so one bad credential cannot be attempted twice per resolve.
        return listOf(manual) + pooled.filter { it.arl != manual.arl }
    }

    /** What a manual sign-in learns about the account behind an ARL. */
    data class AccountInfo(
        val name: String,
        val lossless: Boolean,
    )

    /**
     * Checks an ARL against the gateway and reports the account behind it, or null when the cookie is
     * not a usable credential. Runs blocking network I/O.
     *
     * The login screen needs this because Deezer hands out an `arl` cookie to anonymous visitors too:
     * without verifying, a user who closed the page before signing in would be told they were signed
     * in and then get silence on every track.
     */
    fun verifyArl(arl: String): AccountInfo? =
        runCatching {
            val json = gateway(arl.trim(), apiToken = "", method = "deezer.getUserData", payload = null)
            val user = json.optJSONObject("results")?.optJSONObject("USER") ?: return@runCatching null
            // An anonymous or expired ARL still returns HTTP 200, just with USER_ID 0.
            if (user.optLong("USER_ID", 0L) == 0L) return@runCatching null
            val options = user.optJSONObject("OPTIONS")
            val name =
                sequenceOf(
                    user.optString("BLOG_NAME"),
                    user.optString("FIRSTNAME"),
                    user.optString("EMAIL"),
                ).firstOrNull { it.isNotBlank() } ?: "Deezer"
            AccountInfo(
                name = name,
                lossless =
                    options?.optBoolean("web_lossless", false) == true ||
                        options?.optBoolean("mobile_lossless", false) == true,
            )
        }.onFailure { Timber.tag(TAG).w(it, "ARL verification failed") }
            .getOrNull()

    /** An authenticated gateway session derived from one pooled ARL. */
    private data class Session(
        val arl: String,
        val apiToken: String,
        val licenseToken: String,
        val lossless: Boolean,
        val masterSecret: String?,
        val establishedAt: Long,
    )

    /**
     * A resolved Deezer stream, in this provider's own shape rather than the playback layer's
     * `DirectStream`.
     */
    data class Resolved(
        val uri: String,
        val mimeType: String,
        val codecs: String,
        val contentLength: Long?,
        val label: String,
        val matchedTitle: String,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val matchedDurationMs: Long?,
        val sampleRate: Int?,
        val bitDepth: Int?,
    )

    private class CachedStream(
        val stream: Resolved,
        val expiresAt: Long,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val searchCache = ConcurrentHashMap<String, Pair<TrackMatching.Candidate?, Long>>()
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val failureCache = ConcurrentHashMap<String, Long>()

    /** Track id of the most recent successful resolve, used by settings screens as a health probe. */
    @Volatile
    var lastResolvedTrackId: String? = null
        private set

    /** Mirrors the Tidal/Qobuz query shape so callers can switch providers without reshaping input. */
    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        /**
         * When non-null, [matchTrack] first resolves the exact recording via Deezer's public
         * `track/isrc:{isrc}` endpoint and only falls back to the title/artist search if that
         * finds nothing. An ISRC names one recording, so this avoids the fuzzy search picking a
         * different take (live/remaster/cover) for a catalogue-imported track.
         */
        val isrc: String? = null,
    )

    private fun Query.cacheKey(): String =
        listOf(mediaId, title.lowercase(), artists.joinToString(",").lowercase(), album.orEmpty().lowercase())
            .joinToString("|")

    /**
     * True when a resolve can reach anything at all: an account (manual or pooled) or an API
     * instance (user-added or from the pool's instance feed). Playback guards use this; features
     * that need a gateway session themselves (lyrics) use [hasAccounts].
     */
    fun hasBackends(): Boolean = accounts().isNotEmpty() || DeezerInstances.hasInstances()

    /** Drops the cached stream for [query] at [format], forcing the next resolve to refetch. */
    fun invalidate(
        query: Query,
        format: String,
    ) {
        val key = query.cacheKey() + ":" + format
        streamCache.remove(key)
        failureCache.remove(key)
    }

    /**
     * Resolves a playable stream for [query] at [format] (`FLAC`, `MP3_320` or `MP3_128` — see
     * `DeezerAudioQuality.toFormatName()`), degrading to the next tier down when the account's plan
     * does not cover the requested one.
     */
    fun resolve(
        query: Query,
        format: String,
    ): Resolved? =
        AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            resolveWithinAttempt(query, format)
        }

    private fun resolveWithinAttempt(
        query: Query,
        format: String,
    ): Resolved? {
        val accounts = accounts()
        if (accounts.isEmpty() && !DeezerInstances.hasInstances()) {
            Timber.tag(TAG).d("resolve skipped: no accounts and no API instances")
            return null
        }

        val now = System.currentTimeMillis()
        val cacheKey = query.cacheKey() + ":" + format
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAt > now) return cached.stream
            streamCache.remove(cacheKey)
        }
        failureCache[cacheKey]?.let { failedUntil ->
            if (failedUntil > now) return null
            failureCache.remove(cacheKey)
        }

        // Accounts with a paid plan come first so a FLAC request has the best chance of being served
        // at full quality. An account whose plan cannot cover the requested tier is NOT skipped: it
        // degrades to the highest tier it does have, so a pool of free accounts still plays the track
        // instead of the whole source going silent.
        val ordered = accounts.sortedByDescending { it.premium }
        for (account in ordered) {
            if (AudioSourceAttemptScope.current()?.isExpired() == true) break
            val session =
                runCatching { session(account) }
                    .onFailure {
                        it.rethrowIfAudioSourceCancelled()
                        Timber.tag(TAG).w(it, "session failed for pooled account")
                    }
                    .getOrNull()
            if (session == null) {
                if (AudioSourceAttemptScope.current()?.isExpired() == true) break
                continue
            }

            val match =
                runCatching { matchTrack(session, query) }
                    .onFailure {
                        it.rethrowIfAudioSourceCancelled()
                        Timber.tag(TAG).w(it, "search failed")
                    }
                    .getOrNull()
            if (match == null) {
                if (AudioSourceAttemptScope.current()?.isExpired() == true) break
                continue
            }
            val trackId = match.id

            val media =
                runCatching { requestUrl(session, trackId, format) }
                    .onFailure {
                        it.rethrowIfAudioSourceCancelled()
                        Timber.tag(TAG).w(it, "get_url failed for track %s", trackId)
                    }
                    .getOrNull()
            if (media == null) {
                if (AudioSourceAttemptScope.current()?.isExpired() == true) break
                // The session may have gone stale mid-resolve; drop it so the next attempt re-auths.
                sessions.remove(account.arl)
                continue
            }

            lastResolvedTrackId = trackId
            val stream =
                Resolved(
                    uri = DeezerCrypto.buildUri(media.url, trackId, session.masterSecret),
                    mimeType = if (media.flac) MIME_FLAC else MIME_MPEG,
                    // MP3 is mpeg-layer-3, not AAC; naming it "mp4a" would mislead the extractor.
                    codecs = if (media.flac) "flac" else "mp3",
                    contentLength = media.contentLength,
                    // Report the tier actually served, not the one requested, so the player does not
                    // claim FLAC for a track that degraded to MP3.
                    label =
                        when (media.format.uppercase()) {
                            FORMAT_FLAC -> "Deezer FLAC"
                            FORMAT_MP3_320 -> "Deezer MP3 320"
                            FORMAT_MP3_128 -> "Deezer MP3 128"
                            else -> "Deezer"
                        },
                    matchedTitle = match.title,
                    matchedArtist = match.artists.firstOrNull(),
                    matchedAlbum = match.album,
                    matchedDurationMs = match.durationMs,
                    // Deezer's gateway does not report either, and FLAC here is always CD-quality, so
                    // report the known 16/44.1 for FLAC and leave MP3 to the consumer's tier heuristic.
                    sampleRate = if (media.flac) 44_100 else null,
                    bitDepth = if (media.flac) 16 else null,
                )
            streamCache[cacheKey] = CachedStream(stream, System.currentTimeMillis() + STREAM_CACHE_MS)
            return stream
        }

        // Accounts first (they report the exact tier), then the API-instance tier, which serves
        // already-decrypted audio from the instance's own accounts.
        if (AudioSourceAttemptScope.current()?.isExpired() == true) return null
        resolveViaInstance(query)?.let { stream ->
            streamCache[cacheKey] = CachedStream(stream, System.currentTimeMillis() + STREAM_CACHE_MS)
            return stream
        }

        if (AudioSourceAttemptScope.current()?.isExpired() != true) {
            failureCache[cacheKey] = System.currentTimeMillis() + FAILURE_CACHE_MS
        }
        return null
    }

    /**
     * Resolves through a Deezer API instance. The recording is identified with Deezer's public
     * catalogue (ISRC when the queue item carries one, otherwise a scored search), so no account
     * is needed on this device; the instance receives only an ISRC or a public track id.
     */
    private fun resolveViaInstance(query: Query): Resolved? {
        if (!DeezerInstances.hasInstances()) return null
        // Resolves the pool feed (at most every 30 min) before any catalogue lookup, so a user with
        // no reachable instance does not pay a public-API round trip on every track.
        if (DeezerInstances.instances().isEmpty()) return null
        val match: TrackMatching.Candidate?
        val isrc: String?
        val byIsrc =
            query.isrc?.takeIf { it.isNotBlank() }?.let { code ->
                runCatching { lookupByIsrc(code) }.getOrNull()
            }
        if (byIsrc != null) {
            match = byIsrc
            isrc = query.isrc
        } else {
            val metadata =
                runCatching { kotlinx.coroutines.runBlocking { lookup(query) } }
                    .onFailure { Timber.tag(TAG).w(it, "public lookup failed") }
                    .getOrNull() ?: return null
            match =
                TrackMatching.Candidate(
                    id = metadata.trackId,
                    title = metadata.title,
                    artists = listOfNotNull(metadata.artist),
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                )
            isrc = metadata.isrc
        }
        val stream = DeezerInstances.resolve(isrc = isrc, trackId = match.id) ?: return null
        lastResolvedTrackId = match.id
        return Resolved(
            // Plain https: the instance already decrypted it, so it must NOT go through the
            // deezer:// Blowfish data source.
            uri = stream.url,
            mimeType = if (stream.flac) MIME_FLAC else MIME_MPEG,
            codecs = if (stream.flac) "flac" else "mp3",
            contentLength = stream.contentLength,
            label = if (stream.flac) "Deezer FLAC (API)" else "Deezer MP3 (API)",
            matchedTitle = match.title,
            matchedArtist = match.artists.firstOrNull(),
            matchedAlbum = match.album,
            matchedDurationMs = match.durationMs,
            sampleRate = if (stream.flac) 44_100 else null,
            bitDepth = if (stream.flac) 16 else null,
        )
    }

    /** Returns a live session for [account], reusing a cached one until it ages out. */
    private fun session(account: PoolAccountManager.DeezerPoolAccount): Session {
        val now = System.currentTimeMillis()
        sessions[account.arl]?.let { if (now - it.establishedAt < SESSION_TTL_MS) return it }

        val json = gateway(account.arl, apiToken = "", method = "deezer.getUserData", payload = null)
        val results = json.optJSONObject("results")
        val user = requireNotNull(results?.optJSONObject("USER")) { "no USER in session payload" }
        // An invalid or expired ARL still returns HTTP 200 with USER_ID 0, so this is the real check.
        if (user.optLong("USER_ID", 0L) == 0L) {
            // Playback just learned the pooled credential is dead; tell the pool so it stops being
            // leased to other users before the next server-side sweep. Fire-and-forget.
            PoolAccountManager.report("deezer", "account", account.id, "dead")
            throw IllegalStateException("ARL rejected by gateway")
        }

        val options = requireNotNull(user.optJSONObject("OPTIONS")) { "no OPTIONS in session payload" }
        val licenseToken = options.optString("license_token")
        require(licenseToken.isNotBlank()) { "no license_token in session" }

        val apiToken = results?.optString("checkForm").orEmpty()
        require(apiToken.isNotBlank()) { "no api token in session" }

        val lossless =
            options.optBoolean("web_lossless", false) ||
                options.optBoolean("mobile_lossless", false)
        // A pool entry flagged premium but without lossless entitlement would make the pool lease it
        // first for FLAC requests everywhere; the truth is the opposite. Tell the pool once per
        // session so its ordering stops preferring this account. Fire-and-forget.
        if (account.premium && !lossless) {
            PoolAccountManager.report("deezer", "account", account.id, "not_premium")
        }

        val session =
            Session(
                arl = account.arl,
                apiToken = apiToken,
                licenseToken = licenseToken,
                // Entitlement lives in flat OPTIONS booleans. Check both the web and mobile flags: a
                // plan can carry lossless on one surface only, and either is enough for us to ask for
                // FLAC. The pool's own `premium` hint only orders attempts; this is the real gate.
                lossless = lossless,
                masterSecret = account.masterSecret,
                establishedAt = now,
            )
        sessions[account.arl] = session
        return session
    }

    private fun gateway(
        arl: String,
        apiToken: String,
        method: String,
        payload: JSONObject?,
    ): JSONObject {
        val url =
            GATEWAY
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("method", method)
                .addQueryParameter("input", "3")
                .addQueryParameter("api_version", "1.0")
                .addQueryParameter("api_token", apiToken)
                .build()
        val body = (payload ?: JSONObject()).toString().toRequestBody(JSON_MEDIA)
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "arl=$arl")
                .post(body)
                .build()
        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("gateway HTTP ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------------

    /** Finds the Deezer track id that best matches [query], or null when nothing scores high enough. */
    private fun matchTrack(
        session: Session,
        query: Query,
    ): TrackMatching.Candidate? {
        val key = query.cacheKey()
        val now = System.currentTimeMillis()
        searchCache[key]?.let { (candidate, expiresAt) ->
            if (expiresAt > now) return candidate
            searchCache.remove(key)
        }

        // Exact-recording fast path: an ISRC identifies one recording, so when the queue item
        // carried one, ask Deezer for that recording directly instead of guessing from a text
        // search. A hit is authoritative (no scoring); a miss falls through to the search below,
        // because Deezer does not carry every label's ISRC.
        query.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            val exact =
                runCatching { lookupByIsrc(isrc) }
                    .onFailure { Timber.tag(TAG).w(it, "isrc lookup failed for %s", isrc) }
                    .getOrNull()
            if (exact != null) {
                searchCache[key] = exact to (now + SEARCH_CACHE_MS)
                return exact
            }
        }

        val terms = listOf(query.title) + query.artists.take(1)
        val payload =
            JSONObject()
                .put("query", terms.joinToString(" ").trim())
                .put("start", 0)
                .put("nb", SEARCH_LIMIT)
        val json = gateway(session.arl, session.apiToken, "search.music", payload)
        val data = json.optJSONObject("results")?.optJSONArray("data") ?: JSONArray()

        val candidates =
            (0 until data.length()).mapNotNull { i ->
                val obj = data.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("SNG_ID").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TrackMatching.Candidate(
                    id = id,
                    title = obj.optString("SNG_TITLE"),
                    artists = listOfNotNull(obj.optString("ART_NAME").takeIf { it.isNotBlank() }),
                    album = obj.optString("ALB_TITLE").takeIf { it.isNotBlank() },
                    // Deezer reports duration in whole seconds.
                    durationMs = obj.optLong("DURATION", 0L).takeIf { it > 0L }?.times(1000L),
                )
            }

        val best =
            TrackMatching.best(
                target =
                    TrackMatching.Target(
                        title = query.title,
                        artists = query.artists,
                        album = query.album,
                        durationMs = query.durationMs,
                    ),
                candidates = candidates,
            )
        searchCache[key] = best to (now + SEARCH_CACHE_MS)
        return best
    }

    /**
     * Resolves an exact recording by ISRC through Deezer's public API (`GET /track/isrc:{isrc}`),
     * which needs no session. Returns the matching [TrackMatching.Candidate], or null when Deezer
     * has no track for the ISRC (the endpoint answers HTTP 200 with an `error` object in that case).
     * The numeric `id` it returns is the same track id the gateway's SNG_ID uses, so [requestUrl]
     * can stream it directly.
     */
    private fun lookupByIsrc(isrc: String): TrackMatching.Candidate? {
        val req =
            Request
                .Builder()
                .url("https://api.deezer.com/track/isrc:${java.net.URLEncoder.encode(isrc, "UTF-8")}")
                .header("Accept", "application/json")
                .build()
        client.newCall(req).withAudioSourceAttemptDeadline().execute().use { res ->
            if (!res.isSuccessful) return null
            val obj = JSONObject(res.body?.string() ?: return null)
            // Not-found and rate-limit responses come back as { "error": { … } } with HTTP 200.
            if (obj.has("error")) return null
            val id = obj.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: return null
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
            val artist = obj.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
            val album = obj.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() }
            val durationMs = obj.optLong("duration", 0L).takeIf { it > 0L }?.times(1000L)
            return TrackMatching.Candidate(
                id = id,
                title = title,
                artists = listOfNotNull(artist),
                album = album,
                durationMs = durationMs,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stream URL
    // ---------------------------------------------------------------------------------------------

    private class Media(
        val url: String,
        val flac: Boolean,
        /** The tier the gateway served, which may be lower than the one requested. */
        val format: String,
        val contentLength: Long?,
    )

    /**
     * Exchanges a track id for a CDN URL via the media endpoint.
     *
     * Deezer needs the track's per-format `TRACK_TOKEN` (not the numeric id) here, so this makes a
     * second gateway call to fetch it before asking for the URL.
     */
    private fun requestUrl(
        session: Session,
        trackId: String,
        format: String,
    ): Media? {
        val trackJson =
            gateway(
                session.arl,
                session.apiToken,
                "song.getData",
                JSONObject().put("sng_id", trackId),
            )
        val trackToken = trackJson.optJSONObject("results")?.optString("TRACK_TOKEN").orEmpty()
        if (trackToken.isBlank()) return null

        // Offer the requested tier and everything below it in one request, so a track with no lossless
        // master (or an account without the entitlement) still plays at the best available quality
        // instead of failing the resolve. FLAC is dropped when the plan does not cover it, otherwise
        // the gateway would answer with an empty media list.
        val requested = if (format == FORMAT_FLAC && !session.lossless) FORMAT_MP3_320 else format
        val formats = FORMAT_TIERS.dropWhile { it != requested }.ifEmpty { listOf(FORMAT_MP3_320, FORMAT_MP3_128) }
        val formatArray = JSONArray()
        formats.forEach { tier ->
            formatArray.put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", tier))
        }
        val mediaArray =
            JSONArray().put(
                JSONObject()
                    .put("type", "FULL")
                    .put("formats", formatArray),
            )
        val cipherPayload =
            JSONObject()
                .put("license_token", session.licenseToken)
                .put("media", mediaArray)
                .put("track_tokens", JSONArray().put(trackToken))

        val request =
            Request
                .Builder()
                .url(MEDIA_ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "arl=${session.arl}")
                .post(cipherPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

        val json =
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("get_url HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty())
            }

        val first = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val media = first.optJSONArray("media")?.optJSONObject(0) ?: return null
        val source = media.optJSONArray("sources")?.optJSONObject(0) ?: return null
        val url = source.optString("url").takeIf { it.isNotBlank() } ?: return null
        // The tier the gateway actually served, which may be lower than the one requested.
        val servedFormat = media.optString("format")
        val flac = servedFormat.equals(FORMAT_FLAC, ignoreCase = true)

        // song.getData carries a per-format size (FILESIZE_FLAC, FILESIZE_MP3_320, ...). The encrypted
        // stream is byte-for-byte the same length as the decrypted one because BF_CBC_STRIPE adds no
        // padding, so this size is valid for the decrypted output and saves a HEAD probe. Null is fine:
        // DirectStream treats an unknown length as "ask the CDN".
        val sizeField = "FILESIZE_${servedFormat.uppercase()}"
        val results = trackJson.optJSONObject("results")
        val contentLength =
            results?.optString(sizeField)?.toLongOrNull()?.takeIf { it > 0L }
                ?: results?.optString("FILESIZE")?.toLongOrNull()?.takeIf { it > 0L }

        return Media(url = url, flac = flac, format = servedFormat, contentLength = contentLength)
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

    /**
     * Searches Deezer's public catalogue for [query] and returns the best-matching track's metadata.
     * Returns null when no match is found or the Deezer API is unreachable.
     *
     * Unlike [resolve] this hits Deezer's public API and needs no account, so it returns catalogue
     * metadata only and never a playable URL. Used for artwork and album-name fallback on downloaded
     * songs, where a missing field is worth a cheap unauthenticated lookup but silence is not.
     */
    suspend fun lookup(query: Query): Metadata? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val q = buildSearchQuery(query)
                val req =
                    Request
                        .Builder()
                        .url("https://api.deezer.com/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=10")
                        .header("Accept", "application/json")
                        .build()
                client.newCall(req).withAudioSourceAttemptDeadline().execute().use { res ->
                    if (!res.isSuccessful) {
                        Timber.tag(TAG).w("search HTTP %d for '%s'", res.code, q)
                        return@use null
                    }
                    val body = res.body?.string() ?: return@use null
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: return@use null
                    if (data.length() == 0) return@use null

                    val candidates =
                        (0 until data.length()).mapNotNull { i ->
                            val obj = data.optJSONObject(i) ?: return@mapNotNull null
                            val title = obj.optString("title").ifBlank { return@mapNotNull null }
                            val artist = obj.optJSONObject("artist")?.optString("name")?.ifBlank { null }
                            val album = obj.optJSONObject("album")?.optString("title")?.ifBlank { null }
                            val durationSec = obj.optLong("duration", 0L).takeIf { it > 0 }
                            val isrc = obj.optString("isrc").ifBlank { null }
                            val preview = obj.optString("preview").ifBlank { null }
                            val cover = obj.optJSONObject("album")?.optString("cover_big")?.ifBlank { null }
                            val score = scoreCandidate(query, title, artist, album, durationSec)
                            Metadata(
                                trackId = obj.optLong("id").toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                isrc = isrc,
                                durationMs = durationSec?.times(1000L),
                                previewUrl = preview,
                                coverUrl = cover,
                            ) to score
                        }
                    val best = candidates.maxByOrNull { it.second } ?: return@use null
                    if (best.second < MIN_MATCH_SCORE) {
                        Timber.tag(TAG).d("best candidate score %.2f below threshold for '%s'", best.second, q)
                        return@use null
                    }
                    best.first
                }
            }.getOrNull()
        }

    /** Free-text catalogue search, returning up to [limit] hits in Deezer's own relevance order. */
    suspend fun searchCandidates(
        term: String,
        limit: Int = SEARCH_LIMIT,
    ): List<Metadata> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
                val req =
                    Request
                        .Builder()
                        .url("https://api.deezer.com/search?q=$encoded&limit=$limit")
                        .header("Accept", "application/json")
                        .build()
                client.newCall(req).withAudioSourceAttemptDeadline().execute().use { res ->
                    if (!res.isSuccessful) {
                        Timber.tag(TAG).w("searchCandidates HTTP %d for '%s'", res.code, trimmed)
                        return@use emptyList<Metadata>()
                    }
                    val data =
                        JSONObject(res.body?.string() ?: return@use emptyList<Metadata>())
                            .optJSONArray("data") ?: return@use emptyList<Metadata>()
                    (0 until data.length()).mapNotNull { i ->
                        val obj = data.optJSONObject(i) ?: return@mapNotNull null
                        val title = obj.optString("title").ifBlank { return@mapNotNull null }
                        Metadata(
                            trackId = obj.optLong("id").toString(),
                            title = title,
                            artist = obj.optJSONObject("artist")?.optString("name")?.ifBlank { null },
                            album = obj.optJSONObject("album")?.optString("title")?.ifBlank { null },
                            isrc = obj.optString("isrc").ifBlank { null },
                            durationMs = obj.optLong("duration", 0L).takeIf { it > 0 }?.times(1000L),
                            previewUrl = obj.optString("preview").ifBlank { null },
                            coverUrl = obj.optJSONObject("album")?.optString("cover_big")?.ifBlank { null },
                        )
                    }
                }
            }.onFailure { Timber.tag(TAG).w(it, "searchCandidates failed for '%s'", trimmed) }
                .getOrDefault(emptyList())
        }
    }

    private fun buildSearchQuery(query: Query): String {
        val parts = mutableListOf<String>()
        query.artists.firstOrNull()?.takeIf(String::isNotBlank)?.let { parts.add("artist:\"$it\"") }
        parts.add("track:\"${query.title}\"")
        query.album?.takeIf(String::isNotBlank)?.let { parts.add("album:\"$it\"") }
        return parts.joinToString(" ")
    }

    private fun scoreCandidate(
        query: Query,
        candidateTitle: String,
        candidateArtist: String?,
        candidateAlbum: String?,
        candidateDurationSec: Long?,
    ): Double {
        val titleScore = normalizedSimilarity(query.title, candidateTitle)
        val artistScore =
            query.artists.firstOrNull()?.let { a ->
                candidateArtist?.let { c -> normalizedSimilarity(a, c) }
            } ?: 0.0
        val albumScore =
            query.album?.let { q ->
                candidateAlbum?.let { c -> normalizedSimilarity(q, c) }
            } ?: 0.5
        val durationScore =
            query.durationMs?.let { qd ->
                candidateDurationSec?.let { cs ->
                    val cd = cs * 1000L
                    val diff = kotlin.math.abs(qd - cd)
                    when {
                        diff < 2_000L -> 1.0
                        diff < 5_000L -> 0.85
                        diff < 10_000L -> 0.6
                        else -> 0.2
                    }
                }
            } ?: 0.5

        return titleScore * 0.45 + artistScore * 0.30 + albumScore * 0.15 + durationScore * 0.10
    }

    private fun normalizedSimilarity(a: String, b: String): Double {
        val na = a.lowercase().trim().replace(Regex("[^a-z0-9 ]"), "")
        val nb = b.lowercase().trim().replace(Regex("[^a-z0-9 ]"), "")
        if (na == nb) return 1.0
        if (na.isBlank() || nb.isBlank()) return 0.0
        val sa = na.split(" ").toSet()
        val sb = nb.split(" ").toSet()
        val inter = sa.intersect(sb).size.toDouble()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    // ---------------------------------------------------------------------------------------------
    // Lyrics
    // ---------------------------------------------------------------------------------------------

    /**
     * Fetches Deezer lyrics for a track via the gateway `song.getLyrics` method, using an available
     * account session. Returns the time-synced LRC when present, falling back to plain text.
     * Returns failure when no account, no match, or no lyrics are available.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): Result<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val account = accounts().firstOrNull()
                    ?: throw java.io.IOException("no Deezer account for lyrics")
                val session = session(account)

                val query = Query(mediaId = "lyrics", title = title, artists = listOfNotNull(artist), album = album, durationMs = durationMs)
                val match = lookup(query) ?: throw java.io.IOException("no Deezer match for lyrics")

                val payload = JSONObject().put("song_id", match.trackId.toLong())
                val json = gateway(session.arl, session.apiToken, method = "song.getLyrics", payload = payload)
                val lyricsObj = json.optJSONObject("results")?.optJSONObject("lyrics")
                    ?: throw java.io.IOException("no lyrics in gateway response")
                val synced = lyricsObj.optString("text_time_synced").takeIf { it.isNotBlank() }
                val plain = lyricsObj.optString("text").takeIf { it.isNotBlank() }
                synced ?: plain ?: throw java.io.IOException("empty lyrics")
            }
        }
}
