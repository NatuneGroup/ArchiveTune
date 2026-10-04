/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.qobuz

import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Streaming/playback provider for user-provided Qobuz-DL proxy instances (squid.wtf / kennyy /
 * arcod-style).
 */
object QobuzAudioProvider {
    private const val USER_AGENT = "ArchiveTune-Android"
    private const val SEARCH_LIMIT = 10
    private const val MIN_MATCH_SCORE = 60
    private const val SEARCH_CACHE_MS = 10 * 60 * 1000L
    private const val STREAM_CACHE_MS = 30 * 60 * 1000L
    private const val FAILURE_CACHE_MS = 10 * 60 * 1000L
    private const val INSTANCE_SOFT_COOLDOWN_MS = 60_000L
    private const val INSTANCE_HARD_COOLDOWN_MS = 600_000L
    private const val AUDIO_FLAC_MIME_TYPE = "audio/flac"
    private const val HEALTH_PROBE_TRACK_ID = "5966783"
    private const val HEALTH_PROBE_FORMAT_ID = 5

    private val STOP_WORDS =
        setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with")
    private val VERSION_TOKENS =
        setOf("remix", "acoustic", "live", "instrumental", "demo", "edit", "mix", "version")

    private const val QOBUZ_API_BASE = "https://www.qobuz.com/api.json/0.2"

    /** A single configured proxy instance. */
    private data class Instance(
        val label: String,
        val baseUrl: String,
    )

    @Volatile
    private var instances: List<Instance> = emptyList()

    @Volatile
    private var tokens: List<QobuzToken> = emptyList()

    val activeInstanceUrls: List<String>
        get() = instances.map { it.baseUrl }

    /** Replaces the active direct-API token list. Duplicates (by token string) are dropped. */
    fun setTokens(newTokens: List<QobuzToken>) {
        val seen = LinkedHashSet<String>()
        tokens = newTokens.filter { it.token.isNotBlank() && seen.add(it.token) }
    }

    /**
     * A resolution backend: either a direct Qobuz API token or a proxy instance. Both expose the same
     * search + download surface so the matching/scoring pipeline is shared.
     */
    private class Backend(
        val id: String,
        val label: String,
        val isToken: Boolean,
        val search: (String) -> JSONArray?,
        val download: (String, Int) -> DownloadResult?,
        val poolId: Long? = null,
        val isPoolPremium: Boolean = false,
    )

    /** The last Qobuz track id that resolved successfully, usable as a health-probe track. */
    @Volatile
    var lastResolvedTrackId: String? = null
        private set

    fun seedProbeTrack(trackId: String) {
        if (lastResolvedTrackId.isNullOrBlank() && trackId.isNotBlank()) {
            lastResolvedTrackId = trackId
        }
    }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    private val healthClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

    private data class CachedSearch(val match: Match?, val expiresAt: Long)

    /** Search metadata carried through to the final cross-provider safety gate. */
    private data class Match(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
    )

    /**
     * Public search candidate metadata — used by the Source chooser's search popup
     * to show Qobuz track results the user can pick from. Mirrors
     * [moe.rukamori.archivetune.tidal.TidalAudioProvider.CandidateMetadata].
     */
    data class CandidateMetadata(
        val trackId: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        /** Album artwork URL from the Qobuz search response (if available). */
        val thumbnailUrl: String? = null,
        /** The backend label that produced this candidate (for diagnostics). */
        val backendLabel: String,
    )

    private data class CachedStream(val stream: DirectStream, val expiresAt: Long)

    private val searchCache = ConcurrentHashMap<String, CachedSearch>()
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val failureCache = ConcurrentHashMap<String, Long>()
    private val instanceCooldownUntilMs = ConcurrentHashMap<String, Long>()

    /** Replaces the active instance list. Invalid/duplicate URLs are dropped. Empty stays empty. */
    fun setInstances(baseUrls: List<String>) {
        val seen = LinkedHashSet<String>()
        instances =
            baseUrls.mapNotNull { raw ->
                val normalized = normalizeInstanceUrl(raw) ?: return@mapNotNull null
                if (!seen.add(normalized)) return@mapNotNull null
                Instance(instanceLabel(normalized), normalized)
            }
    }

    // Community Source Pool discovery feed ({ streaming, api } shape) for Qobuz, derived from the
    // build-time SOURCE_PROVIDER_URL. Empty when no provider is configured.
    private val instanceDiscoverySources: List<String> =
        BuildConfig.SOURCE_PROVIDER_URL
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotEmpty() }
            ?.let { listOf("$it/api/discovery/qobuz") }
            ?: emptyList()

    /**
     * Best-effort discovery of community Qobuz proxy instances from the Source Pool website. Returns
     * newly discovered valid base URLs (may be empty). Never throws; blocking network I/O, so call
     * off the main thread.
     */
    @Volatile
    private var discoveryCache: List<String> = emptyList()

    @Volatile
    private var discoveryCacheExpiresAt: Long = 0L

    private const val DISCOVERY_CACHE_MS = 10 * 60 * 1000L

    fun discoverInstances(): List<String> {
        if (instanceDiscoverySources.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        if (now < discoveryCacheExpiresAt && discoveryCache.isNotEmpty()) return discoveryCache
        val discovered = LinkedHashSet<String>()
        for (source in instanceDiscoverySources) {
            runCatching {
                val builder =
                    Request
                        .Builder()
                        .url(source)
                        .header("User-Agent", USER_AGENT)
                // Present the per-app read key when the pool has gating enabled.
                if (BuildConfig.SOURCE_PROVIDER_KEY.isNotBlank()) {
                    builder.header("Authorization", "Bearer ${BuildConfig.SOURCE_PROVIDER_KEY}")
                }
                val request = builder.get().build()
                healthClient.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    if (!response.isSuccessful) {
                        // A rejected read and a healthy pool with nothing contributed both end up
                        // as zero instances here, so say which one happened — this is the whole
                        // difference between "no Qobuz accounts" and "this build's key is wrong".
                        if (response.code == 401) {
                            Timber
                                .tag("QobuzDiscovery")
                                .w(
                                    "Pool discovery %s rejected as unauthorized (HTTP 401); " +
                                        "SOURCE_PROVIDER_KEY is missing or invalid for this build.",
                                    source,
                                )
                        }
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val obj = JSONObject(body)
                    for (key in listOf("streaming", "instances", "api")) {
                        val arr = obj.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            when (val item = arr.opt(i)) {
                                is String -> normalizeInstanceUrl(item)?.let(discovered::add)
                                is JSONObject ->
                                    (item.optString("url").takeIf { it.isNotBlank() }
                                        ?: item.optString("host").takeIf { it.isNotBlank() })
                                        ?.let { normalizeInstanceUrl(it)?.let(discovered::add) }
                            }
                        }
                    }
                }
            }
        }
        val result = discovered.toList()
        if (result.isNotEmpty()) {
            discoveryCache = result
            discoveryCacheExpiresAt = System.currentTimeMillis() + DISCOVERY_CACHE_MS
        }
        return result
    }

    /** Normalizes an instance URL to `scheme://host[:port]` form, or null when invalid. */
    fun normalizeInstanceUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank() || !url.host.contains('.')) return null
        return withScheme
    }

    private fun instanceLabel(baseUrl: String): String =
        baseUrl.toHttpUrlOrNull()?.host ?: baseUrl

    private fun markInstanceHealthy(baseUrl: String) {
        instanceCooldownUntilMs.remove(baseUrl)
    }

    private fun markInstanceFailed(
        baseUrl: String,
        hardFailure: Boolean,
    ) {
        val cooldownMs = if (hardFailure) INSTANCE_HARD_COOLDOWN_MS else INSTANCE_SOFT_COOLDOWN_MS
        instanceCooldownUntilMs[baseUrl] = System.currentTimeMillis() + cooldownMs
    }

    private fun isInstanceCoolingDown(
        baseUrl: String,
        now: Long,
    ): Boolean = (instanceCooldownUntilMs[baseUrl] ?: 0L) > now

    /**
     * Lightweight reachability probe. Returns round-trip latency in ms, or null when unreachable.
     * Runs blocking network I/O — call off the main thread.
     */
    fun checkInstance(baseUrl: String): Long? {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return null
        val request =
            Request
                .Builder()
                .url("$normalized/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        return runCatching {
            val start = System.currentTimeMillis()
            healthClient.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (response.code in 200..499) System.currentTimeMillis() - start else null
            }
        }.getOrNull()
    }

    /**
     * Deep health probe: runs an actual search + download for [probeTrackId] (or a canned query when
     * blank) and inspects whether the instance returns a real lossless URL or only a preview/sample.
     * Runs blocking network I/O — call off the main thread.
     */
    fun verifyInstance(
        baseUrl: String,
        probeTrackId: String?,
        formatId: Int,
    ): TidalAudioProvider.InstanceHealth {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return TidalAudioProvider.InstanceHealth.UNREACHABLE
        val trackId =
            probeTrackId
                ?.trim()
                .orEmpty()
                .ifBlank {
                    // A search response only proves that the API is reachable. Resolve a real sample
                    // id so the download call below can also verify the backing account entitlement.
                    runCatching { searchTrackId(normalized, "adele hello") }.getOrNull().orEmpty()
                }
        if (trackId.isEmpty()) return TidalAudioProvider.InstanceHealth.UNREACHABLE
        return runCatching {
            when (val result = fetchDownload(normalized, trackId, formatId)) {
                null -> TidalAudioProvider.InstanceHealth.UNREACHABLE
                else -> if (result.isPreview) {
                    TidalAudioProvider.InstanceHealth.PREVIEW_ONLY
                } else {
                    TidalAudioProvider.InstanceHealth.HEALTHY
                }
            }
        }.getOrElse { TidalAudioProvider.InstanceHealth.UNREACHABLE }
    }

    /** Feeds an external health result into the runtime cooldown map used by the resolver. */
    fun applyHealthResult(
        baseUrl: String,
        healthy: Boolean,
    ) {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return
        if (healthy) markInstanceHealthy(normalized) else markInstanceFailed(normalized, hardFailure = true)
    }

    /** Lookup metadata for a track, matching Tidal's [TidalAudioProvider.Query] shape. */
    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        /**
         * When non-null, [resolve] skips the title/artist search ([resolveTrackId] / [bestMatch])
         * and goes straight to [backend.download] with this trackId.
         */
        val directTrackId: String? = null,
    )

    /**
     * Resolves a direct-playable lossless stream for [query] at the requested Qobuz [formatId]
     * (6=CD, 7=hi-res ≤96kHz, 27=max). Returns null when no instance can serve a full track. Runs
     * blocking network I/O — call off the main thread.
     */
    fun resolve(
        query: Query,
        formatId: Int,
    ): DirectStream? =
        AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            resolveWithinAttempt(query, formatId)
        }

    private fun resolveWithinAttempt(
        query: Query,
        formatId: Int,
    ): DirectStream? {
        val backends = orderedBackends()
        if (backends.isEmpty()) {
            Timber.tag("Qobuz").d("resolve skipped: no tokens or instances configured")
            return null
        }
        val now = System.currentTimeMillis()
        val cacheKey = query.cacheKey() + ":" + formatId
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAt > now) {
                Timber.tag("Qobuz").d("stream cache hit for %s", query.title)
                return cached.stream
            }
            streamCache.remove(cacheKey)
        }
        failureCache[cacheKey]?.let { failedUntil ->
            if (failedUntil > now) return null
            failureCache.remove(cacheKey)
        }

        val available = backends.filterNot { isInstanceCoolingDown(it.id, now) }.ifEmpty { backends }
        for (backend in available) {
            if (AudioSourceAttemptScope.current()?.isExpired() == true) break
            // When directTrackId is set (user clicked a specific Qobuz search
            // result), skip the title/artist search entirely and download the
            // exact track. This prevents the resolver from matching a different
            // Qobuz track (different master, deluxe edition, etc.).
            val match = if (query.directTrackId != null) {
                Match(
                    id = query.directTrackId,
                    title = query.title,
                    artist = query.artists.joinToString(", "),
                    album = query.album,
                    durationMs = query.durationMs,
                )
            } else {
                runCatching { resolveTrackId(backend, query) }
                    .onFailure {
                        it.rethrowIfAudioSourceCancelled()
                        if (AudioSourceAttemptScope.current()?.isExpired() != true) {
                            markInstanceFailed(backend.id, hardFailure = it is java.io.IOException)
                        }
                    }
                    .getOrNull() ?: continue
            }
            val trackId = match.id
            val download =
                runCatching { backend.download(trackId, formatId) }
                    .onFailure {
                        it.rethrowIfAudioSourceCancelled()
                        if (AudioSourceAttemptScope.current()?.isExpired() != true) {
                            markInstanceFailed(backend.id, hardFailure = it is java.io.IOException)
                        }
                    }
                    .getOrNull()
            if (download == null) {
                if (AudioSourceAttemptScope.current()?.isExpired() == true) break
                markInstanceFailed(backend.id, hardFailure = false)
                continue
            }
            if (download.isPreview) {
                // Unsubscribed/expired backing account: skip this backend for a while, try the next.
                Timber.tag("Qobuz").w("%s returned preview-only; skipping", backend.label)
                // Report not_premium to the pool, but only for accounts we advertised as premium.
                // Qobuz returns "sample":true for both lapsed subscriptions and unavailable-at-quality tracks,
                // so only marking definitely wrong claims prevents disabling healthy accounts.
                if (backend.isToken && backend.isPoolPremium && backend.poolId != null) {
                    moe.rukamori.archivetune.utils.PoolAccountManager.report("qobuz", "account", backend.poolId, "not_premium")
                }
                markInstanceFailed(backend.id, hardFailure = false)
                continue
            }
            markInstanceHealthy(backend.id)
            lastResolvedTrackId = trackId
            val stream =
                DirectStream(
                    uri = download.url,
                    mimeType = download.mimeType,
                    codecs = download.codecs,
                    contentLength = null,
                    label = "Qobuz ${qualityLabel(formatId)}",
                    source = AudioSourceType.QOBUZ,
                    matchedTitle = match.title,
                    matchedArtist = match.artist,
                    matchedAlbum = match.album,
                    matchedDurationMs = match.durationMs,
                )
            streamCache[cacheKey] = CachedStream(stream, now + STREAM_CACHE_MS)
            Timber.tag("Qobuz").i("resolved \"%s\" via %s [%s]", query.title, backend.label, stream.label)
            return stream
        }
        if (AudioSourceAttemptScope.current()?.isExpired() != true) {
            failureCache[cacheKey] = now + FAILURE_CACHE_MS
        }
        return null
    }

    /**
     * Public search — used by the Source chooser's "Play from" search popup to show Qobuz track
     * results the user can pick from. Mirrors
     * [moe.rukamori.archivetune.tidal.TidalAudioProvider.searchCandidates].
     */
    fun searchCandidates(
        query: String,
        limit: Int = 8,
    ): List<CandidateMetadata> {
        // Auto-populate tokens/instances if they're empty. This matches the
        // logic in MusicService.resolveQobuzStream so the search popup works
        // without the user having to play a Qobuz song first.
        if (tokens.isEmpty()) {
            val poolTokens = runCatching {
                moe.rukamori.archivetune.utils.PoolAccountManager.qobuzAccounts().map {
                    QobuzToken(
                        token = it.token,
                        appId = it.appId,
                        appSecret = it.appSecret,
                        label = "Source Pool",
                        subscription = if (it.premium) "premium" else "",
                        poolId = it.id,
                    )
                }
            }.getOrDefault(emptyList())
            if (poolTokens.isNotEmpty()) {
                setTokens(poolTokens)
                Timber.tag("Qobuz").d("searchCandidates auto-populated %d pool tokens", poolTokens.size)
            }
        }
        if (instances.isEmpty()) {
            val discovered = runCatching { discoverInstances() }.getOrDefault(emptyList())
            if (discovered.isNotEmpty()) {
                setInstances(discovered)
                Timber.tag("Qobuz").d("searchCandidates auto-populated %d discovered instances", discovered.size)
            }
        }
        val backends = orderedBackends()
        if (backends.isEmpty()) {
            Timber.tag("Qobuz").d("searchCandidates: no backends configured (no tokens or instances)")
            return emptyList()
        }
        val wanted = query.titleMatchNormalized()
        if (wanted.isBlank()) return emptyList()
        val out = mutableListOf<CandidateMetadata>()
        for (backend in backends) {
            if (out.size >= limit) break
            runCatching {
                val items = backend.search(query) ?: return@runCatching
                for (index in 0 until items.length()) {
                    if (out.size >= limit) break
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.trackId() ?: continue
                    val rawTitle = item.stringOrNull("title") ?: continue
                    val candidateArtist =
                        item.optJSONObject("performer")?.stringOrNull("name")
                            ?: item.stringOrNull("artist")
                            ?: item.optJSONObject("album")?.optJSONObject("artist")?.stringOrNull("name")
                            ?: ""
                    val candidateAlbum = item.optJSONObject("album")?.stringOrNull("title")
                    val candidateDurationMs = item.longOrNull("duration")?.times(1000L)
                    // Extract thumbnail from the album image field.
                    // The Qobuz API returns album art in multiple possible formats:
                    // 1. album.image as a JSONArray of {size, url} objects
                    // 2. album.image as a JSONObject with small/large/thumbnail keys
                    // 3. album.image as a direct URL string
                    // 4. album.cover_url / album.thumbnail_url as direct strings
                    // 5. item.thumbnail / item.cover as direct strings
                    // We try all of these in order of likelihood.
                    val albumObj = item.optJSONObject("album")
                    val candidateThumbnail = extractQobuzThumbnail(albumObj, item)
                    out.add(
                        CandidateMetadata(
                            trackId = id,
                            title = rawTitle,
                            artist = candidateArtist.takeIf { it.isNotBlank() },
                            album = candidateAlbum,
                            durationMs = candidateDurationMs,
                            thumbnailUrl = candidateThumbnail,
                            backendLabel = backend.label,
                        ),
                    )
                }
            }.onFailure { error ->
                Timber.tag("Qobuz").w(error, "Search backend %s failed for query \"%s\"", backend.label, query)
            }
            // If this backend returned results, stop — we don't merge across backends
            // because the same track id can resolve differently across proxies.
            if (out.isNotEmpty()) break
        }
        return out
    }

    /** Builds the ordered backend list: direct API tokens first (highest fidelity), then proxies. */
    private fun orderedBackends(): List<Backend> {
        val tokenBackends =
            tokens.map { token ->
                Backend(
                    id = "token:${token.id}",
                    label = "Qobuz token ${token.label.ifBlank { token.userId.ifBlank { "account" } }}",
                    isToken = true,
                    search = { q -> searchItemsDirect(token, q) },
                    download = { id, fmt -> fetchDownloadDirect(token, id, fmt) },
                    poolId = token.poolId,
                    isPoolPremium = token.poolId != null && token.subscription.equals("premium", ignoreCase = true),
                )
            }
        val proxyBackends =
            instances.map { instance ->
                Backend(
                    id = instance.baseUrl,
                    label = instance.label,
                    isToken = false,
                    search = { q -> searchItems(instance.baseUrl, q) },
                    download = { id, fmt -> fetchDownload(instance.baseUrl, id, fmt) },
                )
            }
        return tokenBackends + proxyBackends
    }

    private fun qualityLabel(formatId: Int): String =
        when (formatId) {
            6 -> "FLAC"
            7 -> "HI_RES"
            27 -> "MAX"
            else -> "FLAC"
        }

    /**
     * Drops any cached stream/failure entry for [query], forcing the next [resolve] to hit the
     * network.
     *
     * Needed by the download path: proxy URLs are signed with a short `etsp` expiry, so a cached URL
     * can be syntactically fine but already dead. Without this, retrying a download would keep
     * replaying the same expired link until the cache aged out on its own.
     */
    fun invalidate(
        query: Query,
        formatId: Int,
    ) {
        val key = query.cacheKey() + ":" + formatId
        streamCache.remove(key)
        failureCache.remove(key)
    }

    /**
     * Clears the transient (process-lived) failure cache and per-instance cooldowns. Called on app
     * foreground (MainActivity.onStart) so that stale failure entries from a previous session don't
     * prevent Qobuz from being retried.
     */
    fun clearTransientCaches() {
        failureCache.clear()
        instanceCooldownUntilMs.clear()
    }

    // -------------------------------------------------------------------------
    // Search + download
    // -------------------------------------------------------------------------

    /** Resolves the best-matching Qobuz track id for [query] against a single backend. */
    private fun resolveTrackId(
        backend: Backend,
        query: Query,
    ): Match? {
        val now = System.currentTimeMillis()
        val key = backend.id + "|" + query.cacheKey()
        searchCache[key]?.let { cached ->
            if (cached.expiresAt > now) return cached.match
            searchCache.remove(key)
        }
        val searchQuery =
            listOf(query.artists.firstOrNull()?.searchArtist().orEmpty(), query.title.searchTitle())
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { query.title }
        val match = bestMatch(backend, searchQuery, query)
        searchCache[key] = CachedSearch(match, now + SEARCH_CACHE_MS)
        return match
    }

    /** Runs search and scores results against [query], returning the best match above threshold. */
    private fun bestMatch(
        backend: Backend,
        searchQuery: String,
        query: Query,
    ): Match? {
        val items = backend.search(searchQuery) ?: return null
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        var bestId: String? = null
        var bestTitle = ""
        var bestArtist: String? = null
        var bestAlbum: String? = null
        var bestDurationMs: Long? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.trackId() ?: continue
            val rawTitle = item.stringOrNull("title") ?: continue
            val candidateTitle = rawTitle.titleMatchNormalized()
            val candidateArtist =
                item.optJSONObject("performer")?.stringOrNull("name")
                    ?: item.stringOrNull("artist")
                    ?: item.optJSONObject("album")?.optJSONObject("artist")?.stringOrNull("name")
                    ?: ""
            val candidateDurationMs = item.longOrNull("duration")?.times(1000L)
            val candidateAlbum = item.optJSONObject("album")?.stringOrNull("title")
            val score =
                scoreMatch(
                    wantedTitle = wantedTitle,
                    wantedArtists = wantedArtists,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateArtist.normalized(),
                    wantedDurationMs = query.durationMs,
                    candidateDurationMs = candidateDurationMs,
                )
            if (score > bestScore) {
                bestScore = score
                bestId = id
                bestTitle = rawTitle
                bestArtist = candidateArtist.takeIf { it.isNotBlank() }
                bestAlbum = candidateAlbum
                bestDurationMs = candidateDurationMs
            }
        }
        val id = bestId
        return if (bestScore >= MIN_MATCH_SCORE && id != null) {
            Match(id, bestTitle, bestArtist, bestAlbum, bestDurationMs)
        } else {
            null
        }
    }

    /** Health-only search helper: returns any track id for a canned query (or null). */
    private fun searchTrackId(
        baseUrl: String,
        searchQuery: String,
    ): String? {
        val items = searchItems(baseUrl, searchQuery) ?: return null
        for (index in 0 until items.length()) {
            items.optJSONObject(index)?.trackId()?.let { return it }
        }
        return null
    }

    private fun searchItems(
        baseUrl: String,
        searchQuery: String,
    ): JSONArray? {
        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("get-music")
                .addQueryParameter("q", searchQuery)
                .addQueryParameter("offset", "0")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            return root.findTrackItems()
        }
    }

    private data class DownloadResult(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val isPreview: Boolean,
    )

    /** Calls download-music and extracts the direct URL + a best-effort preview flag. */
    private fun fetchDownload(
        baseUrl: String,
        trackId: String,
        formatId: Int,
    ): DownloadResult? {
        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("download-music")
                .addQueryParameter("track_id", trackId)
                .addQueryParameter("quality", formatId.toString())
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val streamUrl = root.findStreamUrl() ?: return null
            val isPreview = root.looksLikePreview()
            val mime =
                when {
                    streamUrl.contains(".flac", ignoreCase = true) -> AUDIO_FLAC_MIME_TYPE
                    streamUrl.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
                    else -> AUDIO_FLAC_MIME_TYPE
                }
            val codecs = if (mime == AUDIO_FLAC_MIME_TYPE) "flac" else "mp4a.40.2"
            return DownloadResult(streamUrl, mime, codecs, isPreview)
        }
    }

    // -------------------------------------------------------------------------
    // Direct Qobuz API (token) — www.qobuz.com/api.json/0.2 with MD5 request signature
    // -------------------------------------------------------------------------

    private fun searchItemsDirect(
        token: QobuzToken,
        searchQuery: String,
    ): JSONArray? {
        val url =
            "$QOBUZ_API_BASE/track/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("query", searchQuery)
                .addQueryParameter("limit", SEARCH_LIMIT.toString())
                .addQueryParameter("app_id", token.appId)
                .build()
        client.newCall(directRequest(url.toString(), token)).withAudioSourceAttemptDeadline().execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                token.poolId?.let { moe.rukamori.archivetune.utils.PoolAccountManager.report("qobuz", "account", it, "dead") }
            }
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            return root.findTrackItems()
        }
    }

    /**
     * Calls track/getFileUrl with the Qobuz MD5 request signature. The signed payload is the
     * concatenation (no separators) of the sorted call params + a unix timestamp + the app secret:
     *   md5("trackgetFileUrl" + "format_id"+fmt + "intent"+"stream" + "track_id"+id + ts + secret)
     */
    private fun fetchDownloadDirect(
        token: QobuzToken,
        trackId: String,
        formatId: Int,
    ): DownloadResult? {
        client.newCall(directDownloadRequest(token, trackId, formatId)).withAudioSourceAttemptDeadline().execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                token.poolId?.let { moe.rukamori.archivetune.utils.PoolAccountManager.report("qobuz", "account", it, "dead") }
            }
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return parseDirectDownload(body)
        }
    }

    private fun directDownloadRequest(
        token: QobuzToken,
        trackId: String,
        formatId: Int,
    ): Request {
        val ts = System.currentTimeMillis() / 1000L
        val sig = md5("trackgetFileUrlformat_id${formatId}intentstreamtrack_id$trackId$ts${token.appSecret}")
        val url =
            "$QOBUZ_API_BASE/track/getFileUrl"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("request_ts", ts.toString())
                .addQueryParameter("request_sig", sig)
                .addQueryParameter("track_id", trackId)
                .addQueryParameter("format_id", formatId.toString())
                .addQueryParameter("intent", "stream")
                .addQueryParameter("app_id", token.appId)
                .build()
        return directRequest(url.toString(), token)
    }

    private fun parseDirectDownload(body: String): DownloadResult? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val streamUrl =
            root.stringOrNull("url")?.takeIf { it.startsWith("http") }
                ?: root.findStreamUrl()
                ?: return null
        // Qobuz sets "sample":true for 30s previews (unsubscribed, or track unavailable at quality).
        val isPreview = root.optBoolean("sample", false) || root.looksLikePreview()
        val apiMime = root.stringOrNull("mime_type")
        val mime =
            when {
                apiMime?.contains("flac", true) == true -> AUDIO_FLAC_MIME_TYPE
                apiMime?.contains("mpeg", true) == true || apiMime?.contains("mp3", true) == true -> "audio/mpeg"
                streamUrl.contains(".flac", true) -> AUDIO_FLAC_MIME_TYPE
                streamUrl.contains(".mp3", true) -> "audio/mpeg"
                else -> AUDIO_FLAC_MIME_TYPE
            }
        val codecs = if (mime == AUDIO_FLAC_MIME_TYPE) "flac" else "mp4a.40.2"
        return DownloadResult(streamUrl, mime, codecs, isPreview)
    }

    private fun directRequest(
        url: String,
        token: QobuzToken,
    ): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-App-Id", token.appId)
            .header("X-User-Auth-Token", token.token)
            .get()
            .build()

    private fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Deep health probe for a direct-API token. This mirrors ArchivePool's account validation:
     * user/get proves the token and reports subscription capabilities, then a subscription-neutral
     * MP3 request proves the app secret can sign stream URLs. Runs blocking I/O off the main thread.
     */
    @Suppress("UNUSED_PARAMETER")
    fun verifyToken(
        token: QobuzToken,
        probeTrackId: String?,
        formatId: Int,
    ): TidalAudioProvider.InstanceHealth =
        runCatching {
            val account = fetchTokenAccountHealth(token)
                ?: return@runCatching TidalAudioProvider.InstanceHealth.UNREACHABLE
            if (!hasValidAppSecret(token)) {
                return@runCatching TidalAudioProvider.InstanceHealth.UNREACHABLE
            }
            if (account.premium) {
                TidalAudioProvider.InstanceHealth.HEALTHY
            } else {
                TidalAudioProvider.InstanceHealth.PREVIEW_ONLY
            }
        }.getOrElse { TidalAudioProvider.InstanceHealth.UNREACHABLE }

    private data class TokenAccountHealth(
        val premium: Boolean,
    )

    private fun fetchTokenAccountHealth(token: QobuzToken): TokenAccountHealth? {
        val url =
            "$QOBUZ_API_BASE/user/get"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("app_id", token.appId)
                .addQueryParameter("user_auth_token", token.token)
                .build()
        client.newCall(directRequest(url.toString(), token)).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val normalized = body.lowercase(Locale.US)
            val valid = normalized.contains("\"id\"") || normalized.contains("credential")
            if (!valid) return null
            val premium =
                Regex("""lossless|hi-res|hires|studio|sublime|"format_id"\s*:\s*(6|7|27)""")
                    .containsMatchIn(normalized)
            return TokenAccountHealth(premium = premium)
        }
    }

    private fun hasValidAppSecret(token: QobuzToken): Boolean {
        client
            .newCall(directDownloadRequest(token, HEALTH_PROBE_TRACK_ID, HEALTH_PROBE_FORMAT_ID))
            .withAudioSourceAttemptDeadline()
            .execute()
            .use { response ->
                val body = response.body?.string().orEmpty().lowercase(Locale.US)
                return !body.contains("invalid request signature") &&
                    !body.contains("invalidrequestsignature")
            }
    }

    // -------------------------------------------------------------------------
    // Defensive JSON extraction (proxy responses vary slightly)
    // -------------------------------------------------------------------------

    /** Finds the track item array across the common response shapes. */
    private fun JSONObject.findTrackItems(): JSONArray? {
        val data = optJSONObject("data") ?: this
        data.optJSONObject("tracks")?.optJSONArray("items")?.let { return it }
        data.optJSONArray("items")?.let { return it }
        data.optJSONArray("tracks")?.let { return it }
        optJSONArray("items")?.let { return it }
        optJSONArray("tracks")?.let { return it }
        return null
    }

    /** Recursively searches for the first plausible stream URL field. */
    private fun JSONObject.findStreamUrl(): String? {
        for (key in listOf("url", "downloadUrl", "download_url", "stream_url", "streamUrl", "link")) {
            stringOrNull(key)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        optJSONObject("data")?.findStreamUrl()?.let { return it }
        val names = keys()
        while (names.hasNext()) {
            val value = opt(names.next())
            when (value) {
                is JSONObject -> value.findStreamUrl()?.let { return it }
                is String -> if (value.startsWith("http") && (value.contains(".flac", true) || value.contains(".mp3", true))) return value
            }
        }
        return null
    }

    /** Best-effort preview/sample detection from the download payload. */
    private fun JSONObject.looksLikePreview(): Boolean {
        val data = optJSONObject("data") ?: this
        if (data.optBoolean("sample", false)) return true
        if (data.optBoolean("preview", false)) return true
        // Some proxies signal an unsubscribed account via a short duration or an explicit flag.
        data.stringOrNull("type")?.let { if (it.equals("preview", true) || it.equals("sample", true)) return true }
        return false
    }

    private fun JSONObject.trackId(): String? =
        stringOrNull("id") ?: longOrNull("id")?.toString() ?: stringOrNull("track_id")

    /** Extracts a thumbnail URL from a Qobuz search result's album object. */
    private fun extractQobuzThumbnail(albumObj: JSONObject?, item: JSONObject): String? {
        if (albumObj != null) {
            // Try album.image
            val imageVal = albumObj.opt("image")
            if (imageVal != null) {
                // Case 1: JSONArray of {size, url} objects
                if (imageVal is org.json.JSONArray) {
                    for (i in 0 until imageVal.length()) {
                        val imgObj = imageVal.optJSONObject(i)
                        val url = imgObj?.stringOrNull("url")
                        if (!url.isNullOrBlank()) return url
                    }
                }
                // Case 2: JSONObject with small/large/thumbnail keys
                if (imageVal is JSONObject) {
                    imageVal.stringOrNull("large")?.let { return it }
                    imageVal.stringOrNull("medium")?.let { return it }
                    imageVal.stringOrNull("small")?.let { return it }
                    imageVal.stringOrNull("thumbnail")?.let { return it }
                    imageVal.stringOrNull("url")?.let { return it }
                }
                // Case 3: direct URL string
                if (imageVal is String && imageVal.startsWith("http")) return imageVal
            }
            // Try album.cover_url / album.thumbnail_url
            albumObj.stringOrNull("cover_url")?.let { return it }
            albumObj.stringOrNull("thumbnail_url")?.let { return it }
            albumObj.stringOrNull("cover")?.let { return it }
        }
        // Try item-level fields
        item.stringOrNull("thumbnail")?.let { return it }
        item.stringOrNull("cover")?.let { return it }
        return null
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key)) optLong(key).takeIf { it > 0L } else null

    // -------------------------------------------------------------------------
    // Matching (self-contained; mirrors Tidal's heuristics)
    // -------------------------------------------------------------------------

    private fun scoreMatch(
        wantedTitle: String,
        wantedArtists: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Int {
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return Int.MIN_VALUE
        // Reject clear version mismatches (live vs studio, remix, instrumental, ...).
        if (hasVersionMismatch(" $wantedTitle ", " $candidateTitle ")) return Int.MIN_VALUE
        val titleOverlap = tokenOverlap(significantTokens(wantedTitle), significantTokens(candidateTitle))
        if (titleOverlap < 0.5) return Int.MIN_VALUE
        var score = (titleOverlap * 100).toInt()
        if (wantedArtists.isNotEmpty() && candidateArtist.isNotBlank()) {
            val artistOverlap =
                wantedArtists.maxOf { tokenOverlap(significantTokens(it), significantTokens(candidateArtist)) }
            score += (artistOverlap * 60).toInt()
        }
        if (wantedDurationMs != null && candidateDurationMs != null) {
            if (durationMatches(wantedDurationMs, candidateDurationMs)) score += 30 else score -= 40
        }
        return score
    }

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun durationMatches(
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Boolean {
        if (wantedDurationMs == null || candidateDurationMs == null) return true
        return abs(wantedDurationMs - candidateDurationMs) <= 45_000L
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean {
        return VERSION_TOKENS.any { token ->
            wanted.contains(" $token ") != candidate.contains(" $token ")
        }
    }

    private fun Query.cacheKey(): String =
        listOf(
            title.normalized(),
            artists.joinToString(",") { it.normalized() },
            album.normalized(),
            durationMs?.div(1000L)?.toString().orEmpty(),
        ).joinToString("::")

    private fun String?.normalized(): String =
        this
            ?.lowercase(Locale.US)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(Regex("\\p{Mn}+"), "")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()

    private fun String.titleMatchNormalized(): String =
        normalized()
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.searchTitle(): String =
        trim()
            .replace(Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.searchArtist(): String =
        trim()
            .substringBefore(',')
            .replace(Regex("\\s+"), " ")
            .trim()
}
