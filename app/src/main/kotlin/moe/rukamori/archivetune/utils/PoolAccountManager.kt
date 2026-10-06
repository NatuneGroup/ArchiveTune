/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.audiosource.AmazonInstance
import moe.rukamori.archivetune.audiosource.AmazonInstances
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.constants.UsePoolAccountsKey
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
 * Fetches shared premium **accounts** (not just instances) from the community Source Pool website's
 * `/api/sources` endpoint and caches their credentials for the Tidal/Qobuz resolvers.
 */
object PoolAccountManager {
    /**
     * Mirrors [UsePoolAccountsKey] (default ON). When OFF, every account getter answers an empty
     * list, so resolvers fall through to the user's own accounts or YouTube, and [refresh] does
     * not touch the network. Re-read from the store in [loadCached] (which [refresh] calls first);
     * App's preference collector keeps it current when the switch changes in between.
     */
    @Volatile
    private var poolAccountsEnabled: Boolean = true

    /** Called by App's preference collector whenever [UsePoolAccountsKey] changes. */
    fun setPoolAccountsEnabled(enabled: Boolean) {
        val changed = poolAccountsEnabled != enabled
        poolAccountsEnabled = enabled
        if (!changed) return
        if (enabled) {
            loadedFromDisk = false
        } else {
            tidalCache = emptyList()
            qobuzCache = emptyList()
            deezerCache = emptyList()
            appleMusicCache = emptyList()
            amazonCache = emptyList()
            loadedFromDisk = true
        }
    }

    suspend fun applyPoolAccountsEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        setPoolAccountsEnabled(enabled)
        if (!isPoolEnabled()) clearCachedPoolAccounts(context)
    }

    suspend fun clearCachedPoolAccounts(context: Context) {
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                tidalCache = emptyList()
                qobuzCache = emptyList()
                deezerCache = emptyList()
                appleMusicCache = emptyList()
                amazonCache = emptyList()
                loadedFromDisk = true
                try {
                    context.dataStore.edit { prefs ->
                        lastRefreshAtMillis = PoolRefreshPolicy.latestAttemptAtMs(
                            lastRefreshAtMillis,
                            prefs[CACHE_LAST_REFRESH_KEY] ?: 0L,
                        )
                        prefs.remove(CACHE_TIDAL_KEY)
                        prefs.remove(CACHE_QOBUZ_KEY)
                        prefs.remove(CACHE_DEEZER_KEY)
                        prefs.remove(CACHE_APPLE_KEY)
                        prefs.remove(CACHE_AMAZON_KEY)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Failed to clear cached pool accounts")
                }
                AppleMusicProvider.clearStorefrontCache()
            }
        }
    }

    /**
     * True when a pool is configured and the user has not opted out. Instance tiers that fetch
     * their own feeds (Deezer's `/api/instances/deezer`) follow the same switch as the accounts.
     */
    fun isPoolEnabled(): Boolean = poolAccountsEnabled && isEnabled

    private suspend fun syncPoolAccountsEnabled(context: Context) {
        val enabled = context.dataStore.getAsync(UsePoolAccountsKey) ?: true
        val wasEnabled = poolAccountsEnabled
        setPoolAccountsEnabled(enabled)
        if (wasEnabled && !enabled) clearCachedPoolAccounts(context)
    }

    private const val TAG = "PoolAccounts"

    private val CACHE_TIDAL_KEY = stringPreferencesKey("poolTidalAccounts")
    private val CACHE_QOBUZ_KEY = stringPreferencesKey("poolQobuzAccounts")
    private val CACHE_DEEZER_KEY = stringPreferencesKey("poolDeezerAccounts")
    private val CACHE_APPLE_KEY = stringPreferencesKey("poolAppleMusicAccounts")
    private val CACHE_AMAZON_KEY = stringPreferencesKey("poolAmazonAccounts")
    private val CACHE_LAST_REFRESH_KEY = longPreferencesKey("poolAccountsLastRefreshAtMillis")

    /** Last resolved read key, so fire-and-forget /api/report calls use the same identity. */
    @Volatile
    private var poolApiKey: String? = null

    /** Application context for DataStore access in report merge operations. Not Service-scoped. */
    @Volatile
    private var appContext: Context? = null

    /** Why the last pool fetch failed, or null when it succeeded (or no pool URL is configured). */
    @Volatile
    var lastFeedError: String? = null
        private set

    /** Report deduplication: "$service:$id:$type" → timestamp. Suppresses duplicate reports within ~10 minutes. */
    private val reportDedupe = ConcurrentHashMap<String, Long>()
    private const val REPORT_DEDUPE_WINDOW_MS = 10 * 60 * 1000L

    /** A shared Tidal subscriber token contributed to the pool. */
    data class TidalPoolAccount(
        val id: Long?,
        val token: String,
        val refreshToken: String?,
        val countryCode: String?,
        val premium: Boolean,
    )

    /** A shared Qobuz subscriber credential. [appSecret] is required to sign stream URLs. */
    data class QobuzPoolAccount(
        val id: Long?,
        val token: String,
        val appId: String,
        val appSecret: String,
        val premium: Boolean,
    )

    /**
     * A shared Deezer subscriber credential.
     *
     * Deezer authenticates with the `arl` cookie rather than a bearer token, and [premium] false means
     * the account has no lossless entitlement, so it can still serve MP3 but will refuse FLAC.
     */
    data class DeezerPoolAccount(
        val id: Long?,
        val arl: String,
        val premium: Boolean,
        /** Optional override for the Blowfish key salt; null means use the salt the app ships with. */
        val masterSecret: String? = null,
    )

    /**
     * A shared Apple Music credential. [mediaUserToken] is the personal `0.Ap…` token from the
     * contributor's Apple Music web session; it unlocks user-scoped AMP API calls (lyrics,
     * personal storefront) and — with an active subscription — full-track playback via the
     * web-playback endpoint. The dev (Bearer) JWT is NOT pooled: the app self-scrapes one.
     */
    data class AppleMusicPoolAccount(
        val id: Long?,
        val mediaUserToken: String,
        val premium: Boolean,
    )

    /**
     * A pooled Amazon Music entry. The pool carries two tiers for this service and both land here:
     * the account tier (an Amazon Music web-session artifact in [session]) and the instance tier
     * ([baseUrl] plus the material that host accepts). Playback goes through the instance tier —
     * this fork's provider talks to a self-hosted instance, which is what [baseUrl],
     * [bypassToken] and [turnstileJwt] describe — while [session] stays for the account tier the
     * pool also collects.
     */
    data class AmazonPoolAccount(
        val id: Long?,
        val session: String?,
        val premium: Boolean,
        /** Instance base URL; null for account-tier entries. */
        val baseUrl: String? = null,
        val bypassToken: String? = null,
        val turnstileJwt: String? = null,
        /** Epoch millis, parsed from the pool's plaintext ISO-8601 expiry; null when absent. */
        val turnstileJwtExpiresAtMs: Long? = null,
    )

    @Volatile
    private var tidalCache: List<TidalPoolAccount> = emptyList()

    @Volatile
    private var qobuzCache: List<QobuzPoolAccount> = emptyList()

    @Volatile
    private var deezerCache: List<DeezerPoolAccount> = emptyList()

    /**
     * The pooled Apple Music accounts. Replacing them drops the canvas storefront cache whenever
     * the leased account changes: that cache is keyed on the Media-User-Token, so it would
     * otherwise keep resolving against the previous account for up to its 24h TTL.
     */
    @Volatile
    private var appleMusicCache: List<AppleMusicPoolAccount> = emptyList()
        set(value) {
            // The account the providers lease is the premium-first one appleMusicAccounts() returns.
            val leased = appleMusicAccounts().firstOrNull()?.mediaUserToken
            field = value
            if (appleMusicAccounts().firstOrNull()?.mediaUserToken != leased) {
                AppleMusicProvider.clearStorefrontCache()
            }
        }

    @Volatile
    private var amazonCache: List<AmazonPoolAccount> = emptyList()

    /**
     * When the pool was last fetched over the network, in epoch millis (0 = never fetched).
     *
     * Exposed so the settings refresh row can tell "this tap actually fetched" from "this tap was
     * throttled" — without it the row reported a refresh that never happened.
     */
    @Volatile
    var lastRefreshAtMillis = 0L
        private set

    @Volatile
    private var loadedFromDisk = false

    private val refreshMutex = Mutex()

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

    /** Fire-and-forget reports survive app lifecycle (SupervisorJob); reports never gate playback. */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when a Source Pool URL is configured, i.e. account discovery is possible. */
    val isEnabled: Boolean
        get() = BuildConfig.SOURCE_PROVIDER_URL.isNotBlank()

    private val poolBaseUrl: String?
        get() =
            BuildConfig.SOURCE_PROVIDER_URL
                .trim()
                .trimEnd('/')
                .takeIf { it.isNotEmpty() }

    /**
     * Credentials-only feed of the split pool. The server also serves a combined legacy feed at
     * /api/sources (accounts + instance URLs together) for older app builds; this client asks
     * for the narrow account feed so tokens and instance URLs arrive over separate URLs.
     * Falls back to the legacy URL only when the pool deployment predates the split (404).
     */
    private val accountsUrl: String? get() = poolBaseUrl?.let { "$it/api/accounts" }

    private val legacySourcesUrl: String? get() = poolBaseUrl?.let { "$it/api/sources" }

    /** How long an account that just failed to resolve is tried last for. */
    private const val ACCOUNT_COOLDOWN_MS = 10 * 60 * 1000L

    /** "service:id" -> the time it becomes worth trying first again. */
    private val accountCooldownUntil = ConcurrentHashMap<String, Long>()

    private fun cooldownKey(service: String, id: Long?) = "$service:${id ?: 0L}"

    private fun isCoolingDown(service: String, id: Long?): Boolean {
        val key = cooldownKey(service, id)
        val until = accountCooldownUntil[key] ?: return false
        if (until > System.currentTimeMillis()) return true
        accountCooldownUntil.remove(key, until)
        return false
    }

    /**
     * Records that an account failed to resolve, so it sorts last for [ACCOUNT_COOLDOWN_MS].
     *
     * Deliberately a demotion rather than a removal: with a thin pool every account may be cooling
     * down at once, and a list that went empty would fall straight through to the lossy YouTube
     * path. Sorted last still means tried — just after everything with a better recent record.
     */
    fun noteAccountFailure(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        accountCooldownUntil[cooldownKey(service, id)] = System.currentTimeMillis() + ACCOUNT_COOLDOWN_MS
    }

    /** Records that an account resolved, clearing any cooldown from an earlier blip. */
    fun noteAccountSuccess(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        accountCooldownUntil.remove(cooldownKey(service, id))
    }

    /**
     * Accounts that resolved recently first, premium before free within each group. Callers try
     * them in order, so this is what keeps a known-bad account from costing every skip its timeout.
     */
    private fun <T> ordered(
        service: String,
        accounts: List<T>,
        idOf: (T) -> Long?,
        premiumOf: (T) -> Boolean,
    ): List<T> = accounts.sortedWith(compareBy({ isCoolingDown(service, idOf(it)) }, { !premiumOf(it) }))

    fun tidalAccounts(): List<TidalPoolAccount> =
        if (isPoolEnabled()) ordered("tidal", tidalCache, { it.id }, { it.premium }) else emptyList()

    fun qobuzAccounts(): List<QobuzPoolAccount> =
        if (isPoolEnabled()) ordered("qobuz", qobuzCache, { it.id }, { it.premium }) else emptyList()

    fun deezerAccounts(): List<DeezerPoolAccount> =
        if (isPoolEnabled()) ordered("deezer", deezerCache, { it.id }, { it.premium }) else emptyList()

    fun appleMusicAccounts(): List<AppleMusicPoolAccount> =
        if (isPoolEnabled()) appleMusicCache.sortedByDescending { it.premium } else emptyList()

    fun amazonAccounts(): List<AmazonPoolAccount> =
        if (isPoolEnabled()) ordered("amazon-music", amazonCache, { it.id }, { it.premium }) else emptyList()

    /**
     * The pooled instances the Amazon provider can play through: entries that name a host and carry
     * material, premium first and cooling-down ones last. Account-tier entries (a session with no
     * host) are skipped; whether a Turnstile JWT is still worth sending is decided by
     * [AmazonInstances.merge], which owns that rule.
     */
    fun amazonInstances(): List<AmazonInstance> =
        if (!isPoolEnabled()) {
            emptyList()
        } else {
            ordered("amazon-music", amazonCache, { it.id }, { it.premium })
                .mapNotNull { account ->
                    val baseUrl = account.baseUrl ?: return@mapNotNull null
                    AmazonInstance(
                        baseUrl = baseUrl,
                        bypassToken = account.bypassToken,
                        turnstileJwt = account.turnstileJwt,
                        turnstileJwtExpiresAtMs = account.turnstileJwtExpiresAtMs,
                        fromPool = true,
                    )
                }
        }

    fun hasAccounts(): Boolean =
        tidalCache.isNotEmpty() || qobuzCache.isNotEmpty() || deezerCache.isNotEmpty() ||
            appleMusicCache.isNotEmpty() || amazonCache.isNotEmpty()

    /**
     * True when every pooled service *that something can actually play* has at least one account.
     *
     * Amazon stays out even though its provider now plays through pooled instances: a user can add
     * their own instance, so an empty Amazon cache means "no pooled host collected yet" rather than
     * "nothing plays". Folding it in would downgrade every deployment that has not collected one —
     * plausibly most of them, indefinitely — from the 24h [refreshIntervalMs] to the 15-minute
     * partial-pool one, for a service that already works without the pool.
     */
    private fun hasEveryService(): Boolean =
        tidalCache.isNotEmpty() && qobuzCache.isNotEmpty() && deezerCache.isNotEmpty() && appleMusicCache.isNotEmpty()

    /**
     * How long a non-forced [refresh] may be skipped for. Full caches are re-read once a day; a
     * cache that is still missing a service is retried far more eagerly so that service can appear
     * without the user having to hunt for the manual refresh button.
     */
    private fun refreshIntervalMs(): Long =
        PoolRefreshPolicy.intervalMs(hasEveryService())

    /**
     * Loads the persisted account cache into memory (cheap, no network). Safe to call repeatedly;
     * only reads the DataStore once. Call early on startup so resolvers have data before the first
     * network [refresh] finishes.
     */
    suspend fun loadCached(context: Context) {
        syncPoolAccountsEnabled(context)
        if (loadedFromDisk) return
        appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            if (!isPoolEnabled()) {
                loadedFromDisk = true
                return@withContext
            }
            runCatching {
                lastRefreshAtMillis = PoolRefreshPolicy.latestAttemptAtMs(
                    lastRefreshAtMillis,
                    context.dataStore.getAsync(CACHE_LAST_REFRESH_KEY) ?: 0L,
                )
                // The persisted cache stores DECRYPTED plaintext JSON (PoolCacheCrypto handles the
                // at-rest layer), so the parse-time decryptor is a pure pass-through.
                val passthrough: (String) -> String? = { raw -> raw }
                cached(context, CACHE_TIDAL_KEY)?.takeIf { it.isNotBlank() }?.let {
                    tidalCache = parseTidal(JSONArray(it), passthrough)
                }
                cached(context, CACHE_QOBUZ_KEY)?.takeIf { it.isNotBlank() }?.let {
                    qobuzCache = parseQobuz(JSONArray(it), passthrough)
                }
                cached(context, CACHE_DEEZER_KEY)?.takeIf { it.isNotBlank() }?.let {
                    deezerCache = parseDeezer(JSONArray(it), passthrough)
                }
                cached(context, CACHE_APPLE_KEY)?.takeIf { it.isNotBlank() }?.let {
                    appleMusicCache = parseAppleMusic(JSONArray(it), passthrough)
                }
                cached(context, CACHE_AMAZON_KEY)?.takeIf { it.isNotBlank() }?.let {
                    amazonCache = parseAmazon(JSONArray(it), passthrough)
                }
                loadedFromDisk = true
                Timber.tag(TAG).d(
                    "Loaded cached accounts: tidal=%d qobuz=%d deezer=%d apple=%d amazon=%d",
                    tidalCache.size,
                    qobuzCache.size,
                    deezerCache.size,
                    appleMusicCache.size,
                    amazonCache.size,
                )
            }.onFailure { Timber.tag(TAG).w(it, "Failed to load cached pool accounts") }
        }
    }

    /**
     * Fetches `/api/sources`, decrypts credentials, and refreshes the in-memory + persisted caches.
     * Returns true when the cache is populated (either freshly fetched or already warm). Throttled
     * unless [force] is set. Never throws.
     */
    suspend fun refresh(
        context: Context,
        force: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            if (!isEnabled) return@withContext false
            loadCached(context)
            if (!isPoolEnabled()) return@withContext false

            val now = System.currentTimeMillis()
            if (!PoolRefreshPolicy.shouldRefresh(now, lastRefreshAtMillis, refreshIntervalMs(), force)) {
                return@withContext hasAccounts()
            }

            refreshMutex.withLock {
                if (!isPoolEnabled()) return@withLock false
                // Re-check the throttle inside the lock in case another caller just refreshed.
                if (!PoolRefreshPolicy.shouldRefresh(
                        System.currentTimeMillis(),
                        lastRefreshAtMillis,
                        refreshIntervalMs(),
                        force,
                    )
                ) {
                    return@withLock hasAccounts()
                }
                val url = accountsUrl ?: legacySourcesUrl
                if (url == null) {
                    // isEnabled already guarantees a URL, so this only guards the impossible.
                    lastFeedError = null
                    Timber.tag(TAG).d("No Source Pool URL configured; nothing to refresh")
                } else {
                    // The read key is baked in at build time; there is no on-device override.
                    val readKey = BuildConfig.SOURCE_PROVIDER_KEY
                    poolApiKey = readKey.ifBlank { null }
                    val attemptAt = System.currentTimeMillis()
                    lastRefreshAtMillis = attemptAt
                    context.dataStore.edit { it[CACHE_LAST_REFRESH_KEY] = attemptAt }

                    var result = fetchAccounts(context, url, readKey)
                    // Pool deployment predates the split feed — retry the combined URL.
                    if (!result.succeeded && result.code == 404 && url == accountsUrl && legacySourcesUrl != null) {
                        Timber.tag(TAG).d("/api/accounts unavailable; falling back to legacy /api/sources")
                        result = fetchAccounts(context, legacySourcesUrl!!, readKey)
                    }
                    // Record why the fetch failed — or clear it on success — so the settings screen
                    // can show the reason rather than a generic "failed". refresh()'s own Boolean
                    // cannot carry this: it reports whether anything is cached, not whether the
                    // network call actually worked.
                    lastFeedError =
                        when {
                            result.succeeded -> null
                            // Both the split feed and the legacy combined feed 404'd. That is not
                            // an "old pool deployment" — nothing at this host serves the pool API
                            // at all (wrong SOURCE_PROVIDER_URL, or a deployment that no longer
                            // exists). A missing or invalid read key is a 401, never a 404.
                            result.code == 404 -> {
                                Timber.tag(TAG).e(
                                    "No pool API at %s — both /api/accounts and /api/sources returned 404. " +
                                        "SOURCE_PROVIDER_URL points at a host that is not an ArchivePool deployment " +
                                        "(this is NOT a read-key problem; a bad key answers 401).",
                                    poolBaseUrl,
                                )
                                "No pool API at $poolBaseUrl (HTTP 404) — that URL is not an ArchivePool deployment."
                            }
                            result.code == 401 ->
                                "Pool rejected the API key (HTTP 401) — SOURCE_PROVIDER_KEY is missing, revoked, " +
                                    "or issued by a different deployment."
                            result.code == 0 -> "Could not reach $poolBaseUrl — network error."
                            else -> "Pool feed returned HTTP ${result.code}."
                        }
                } // else (pool URL configured)

                hasAccounts()
            }
        }

    /** Outcome of one feed fetch: the parsed JSON on success, plus the HTTP code (0 = network error). */
    private class FeedFetch(
        val json: JSONObject?,
        val code: Int,
    ) {
        val succeeded: Boolean get() = code == 200 && json != null
    }

    /**
     * Fetches and parses one credentials feed (/api/accounts, or the accounts half of the legacy
     * /api/sources). Applies the parsed accounts to the in-memory caches and persists them
     * encrypted. Returns the HTTP status so the caller can distinguish 401 (key rejected) from
     * 404 (feed not deployed) from a network failure (0). Never throws.
     */
    private suspend fun fetchAccounts(
        context: Context,
        url: String,
        readKey: String,
    ): FeedFetch {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "ArchiveTune-Android")
                // v2 of the pool feed protocol: the server encrypts sensitive fields with a key
                // DERIVED from the read key below (SHA-256 of a domain-separated string), so this
                // app needs only the source-provider key — no separately-distributed
                // POOL_CLIENT_KEY has to match the deployment. Older servers ignore the header.
                .header("X-Pool-Client", "v2")
        if (readKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $readKey")
        }
        return try {
            client.newCall(builder.get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    // HTTP 401 specifically means the pool's read-key enforcement rejected the
                    // presented key (revoked, deleted, or from an older pool database). Surface
                    // that explicitly so the failure mode is obvious in logs instead of looking
                    // like an empty pool.
                    if (response.code == 401) {
                        Timber.tag(TAG).w(
                            "Pool account feed rejected the presented key (HTTP 401) — " +
                                "it is revoked, deleted, or predates the pool's current database.",
                        )
                    } else {
                        Timber.tag(TAG).w("Pool account feed %s returned HTTP %d", url, response.code)
                    }
                    return@use FeedFetch(null, response.code)
                }
                val root = JSONObject(response.body?.string().orEmpty())
                val decryptor = decryptorFor(root, readKey)
                val tidal = parseTidal(accountsArray(root, "tidal"), decryptor)
                val qobuz = parseQobuz(accountsArray(root, "qobuz"), decryptor)
                val deezer = parseDeezer(accountsArray(root, "deezer"), decryptor)
                val apple = parseAppleMusic(accountsArray(root, "apple-music"), decryptor)
                val amazon = parseAmazon(accountsArray(root, "amazon-music"), decryptor)
                if (!isPoolEnabled()) return@use FeedFetch(root, 200)
                // Don't overwrite the in-memory cache with an empty list when the pool returns a
                // 200 with a partial/empty response (rate-limit, transient server bug, captive-portal
                // interception, malformed JSON): every provider would vanish from playback until a
                // force-stop and re-open. Only update the cache when at least one list is
                // non-empty. Otherwise keep the previous (non-empty) cache so playback keeps working.
                val allEmpty = tidal.isEmpty() && qobuz.isEmpty() && deezer.isEmpty() && apple.isEmpty() && amazon.isEmpty()
                if (allEmpty && hasAccounts()) {
                    Timber
                        .tag(TAG)
                        .w("Pool returned empty account lists — keeping existing cache to avoid mid-playback source disappearance")
                } else {
                    tidalCache = tidal
                    qobuzCache = qobuz
                    deezerCache = deezer
                    appleMusicCache = apple
                    amazonCache = amazon
                    persist(context, tidal, qobuz, deezer, apple, amazon)
                }
                Timber.tag(TAG).i(
                    "Pool accounts refreshed: tidal=%d qobuz=%d deezer=%d apple=%d amazon=%d",
                    tidal.size,
                    qobuz.size,
                    deezer.size,
                    apple.size,
                    amazon.size,
                )
                FeedFetch(root, 200)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Pool account refresh failed")
            FeedFetch(null, 0)
        }
    }

    private suspend fun persist(
        context: Context,
        tidal: List<TidalPoolAccount>,
        qobuz: List<QobuzPoolAccount>,
        deezer: List<DeezerPoolAccount>,
        apple: List<AppleMusicPoolAccount>,
        amazon: List<AmazonPoolAccount>,
    ) {
        val tidalJson =
            JSONArray().apply {
                tidal.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("token", it.token)
                            .put("refreshToken", it.refreshToken)
                            .put("countryCode", it.countryCode)
                            .put("premium", it.premium),
                    )
                }
            }.toString()
        val qobuzJson =
            JSONArray().apply {
                qobuz.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("token", it.token)
                            .put("appId", it.appId)
                            .put("appSecret", it.appSecret)
                            .put("premium", it.premium),
                    )
                }
            }.toString()
        val deezerJson =
            JSONArray().apply {
                deezer.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("arl", it.arl)
                            .put("masterSecret", it.masterSecret)
                            .put("premium", it.premium),
                    )
                }
            }.toString()
        val appleJson =
            JSONArray().apply {
                apple.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("token", it.mediaUserToken)
                            .put("premium", it.premium),
                    )
                }
            }.toString()
        val amazonJson =
            JSONArray().apply {
                amazon.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("session", it.session)
                            .put("premium", it.premium)
                            .put("baseUrl", it.baseUrl)
                            .put("bypassToken", it.bypassToken)
                            .put("turnstileJwt", it.turnstileJwt)
                            .put("turnstileJwtExpiresAtMs", it.turnstileJwtExpiresAtMs),
                    )
                }
            }.toString()
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[CACHE_TIDAL_KEY] = PoolCacheCrypto.encrypt(tidalJson)
                prefs[CACHE_QOBUZ_KEY] = PoolCacheCrypto.encrypt(qobuzJson)
                prefs[CACHE_DEEZER_KEY] = PoolCacheCrypto.encrypt(deezerJson)
                prefs[CACHE_APPLE_KEY] = PoolCacheCrypto.encrypt(appleJson)
                prefs[CACHE_AMAZON_KEY] = PoolCacheCrypto.encrypt(amazonJson)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to persist pool accounts") }
    }

    private suspend fun cached(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>): String? {
        val raw = context.dataStore.getAsync(key)?.takeIf { it.isNotBlank() } ?: return null
        return PoolCacheCrypto.decrypt(raw) ?: raw
    }

    /**
     * Reports playback-time observations back to the pool (`dead` / `not_premium`) so entries that
     * fail for real users stop being leased without waiting for the next server sweep — and so the
     * pool's database is not hit repeatedly by every app probing every credential. Fire-and-forget:
     * a report must never break playback or block a resolver, hence non-suspend + own scope.
     * Manual accounts (id == null) are never reported.
     */
    fun report(
        service: String,
        kind: String,
        id: Long?,
        reportType: String,
    ) {
        if (id == null || !isPoolEnabled()) return
        val base = poolBaseUrl ?: return

        // Deduplicate reports within ~10 minutes to avoid spamming the server when one dead
        // token is hit by multiple resolvers (e.g., LosslessStreamResolver racing all pool accounts).
        val dedupeKey = "$service:$id:$reportType"
        val now = System.currentTimeMillis()
        val lastReported = reportDedupe[dedupeKey]
        if (lastReported != null && now - lastReported < REPORT_DEDUPE_WINDOW_MS) {
            return
        }
        reportDedupe[dedupeKey] = now

        // Prune old entries to prevent unbounded map growth (~10min window).
        reportDedupe.entries.removeIf { (_, ts) -> now - ts > REPORT_DEDUPE_WINDOW_MS }

        reportScope.launch {
            if (!isPoolEnabled()) return@launch
            runCatching {
                val body =
                    JSONObject()
                        .put("service", service)
                        .put("kind", kind)
                        .put("id", id)
                        .put("report", reportType)
                        .toString()
                val builder =
                    Request
                        .Builder()
                        .url("$base/api/report")
                        .header("User-Agent", "ArchiveTune-Android")
                        .header("X-Pool-Client", "v2")
                        .post(body.toRequestBody(JSON_MEDIA))
                val readKey = poolApiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.SOURCE_PROVIDER_KEY
                if (readKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $readKey")
                }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).d("Pool report %s/%s/%s returned HTTP %d", service, reportType, id, response.code)
                        return@use
                    }
                    val responseBody = response.body?.string().orEmpty()
                    if (responseBody.isNotBlank()) {
                        val root = JSONObject(responseBody)
                        if (root.optBoolean("ok", false) && kind == "account" && readKey.isNotBlank()) {
                            mergeReplacement(root, service, id, readKey)
                        }
                    }
                }
            }.onFailure { Timber.tag(TAG).w(it, "Pool report failed") }
        }
    }

    /**
     * Accounts array for one service, tolerant of both feed shapes: the canonical
     * `{ "tidal": { "accounts": [...] } }` (mirrors the legacy /api/sources account half) and a
     * bare `{ "tidal": [...] } }. Null when the service is absent or empty.
     */
    private fun accountsArray(root: JSONObject, service: String): JSONArray? =
        root.optJSONObject(service)?.optJSONArray("accounts") ?: root.optJSONArray(service)

    /** Decrypts a sensitive field with the feed's decryptor. Blank/failed decryptions yield null. */
    private fun field(
        obj: JSONObject,
        key: String,
        decryptor: (String) -> String?,
    ): String? {
        val raw = obj.optString(key, "").takeIf { it.isNotBlank() } ?: return null
        val decoded = decryptor(raw)?.takeIf { it.isNotBlank() }
        if (decoded == null && PoolCrypto.isEncrypted(raw)) {
            Timber.tag(TAG).w("Dropped encrypted pool field %s because no available key could decrypt it", key)
        }
        return decoded
    }

    /** Builds the decryptor lambda for a feed response, selecting the indicated encryption scheme. */
    private fun decryptorFor(root: JSONObject, readKey: String): (String) -> String? {
        val derivedKey = PoolCrypto.deriveClientKey(readKey)
        val encryptionScheme = root.optString("encryption", "")
        return { raw ->
            if (encryptionScheme == "read-key") {
                PoolCrypto.maybeDecryptWith(raw, derivedKey) ?: PoolCrypto.maybeDecrypt(raw)
            } else {
                PoolCrypto.maybeDecrypt(raw) ?: PoolCrypto.maybeDecryptWith(raw, derivedKey)
            }
        }
    }

    /**
     * Merges a replacement credential into the cache after a dead-token report, under the refresh mutex
     * to ensure atomicity with persist(). Replaces the dead element in-place or drops it when no
     * replacement is available. The replacement shape matches /api/accounts exactly.
     */
    private suspend fun mergeReplacement(
        root: JSONObject,
        service: String,
        deadId: Long,
        readKey: String,
    ) {
        if (!isPoolEnabled()) return
        val ctx = appContext ?: return
        val replacementObj = root.optJSONObject("replacement") ?: return
        val decryptor = decryptorFor(replacementObj, readKey)
        val replacementArr = replacementObj.optJSONObject(service)?.optJSONArray("accounts") ?: JSONArray()

        refreshMutex.withLock {
            if (!isPoolEnabled()) return@withLock
            when (service) {
                "tidal" -> tidalCache = mergeList(tidalCache, deadId, TidalPoolAccount::id, replacementArr, ::parseTidal, decryptor) ?: return@withLock
                "qobuz" -> qobuzCache = mergeList(qobuzCache, deadId, QobuzPoolAccount::id, replacementArr, ::parseQobuz, decryptor) ?: return@withLock
                "deezer" -> deezerCache = mergeList(deezerCache, deadId, DeezerPoolAccount::id, replacementArr, ::parseDeezer, decryptor) ?: return@withLock
                "apple-music" -> appleMusicCache = mergeList(appleMusicCache, deadId, AppleMusicPoolAccount::id, replacementArr, ::parseAppleMusic, decryptor) ?: return@withLock
                "amazon-music" -> amazonCache = mergeList(amazonCache, deadId, AmazonPoolAccount::id, replacementArr, ::parseAmazon, decryptor) ?: return@withLock
                else -> return@withLock
            }
            persist(ctx, tidalCache, qobuzCache, deezerCache, appleMusicCache, amazonCache)
        }
    }

    /**
     * Replaces the dead-id element of [cacheList] with the parsed replacement, preserving order.
     * Null return means "nothing to persist": the dead entry is no longer cached (a refresh landed
     * while the report was in flight, so that cache is authoritative) or the replacement failed to
     * parse. An empty [replacementArr] drops the dead entry with no substitute.
     */
    private fun <T> mergeList(
        cacheList: List<T>,
        deadId: Long,
        idOf: (T) -> Long?,
        replacementArr: JSONArray,
        parse: (JSONArray?, (String) -> String?) -> List<T>,
        decryptor: (String) -> String?,
    ): List<T>? {
        val deadIndex = cacheList.indexOfFirst { idOf(it) == deadId }
        if (deadIndex < 0) return null

        if (replacementArr.length() == 0) {
            return cacheList.filterIndexed { idx, _ -> idx != deadIndex }
        }

        val replacementEntry = parse(replacementArr, decryptor).firstOrNull() ?: return null

        // Replacement already cached (a concurrent report raced to the same one) — just drop the dead entry.
        return if (cacheList.any { idOf(it) == idOf(replacementEntry) }) {
            cacheList.filterIndexed { idx, _ -> idx != deadIndex }
        } else {
            cacheList.mapIndexed { idx, entry -> if (idx == deadIndex) replacementEntry else entry }
        }
    }

    private fun parseTidal(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<TidalPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<TidalPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val token = field(obj, "token", decryptor) ?: continue
            out +=
                TidalPoolAccount(
                    id = entryId(obj),
                    token = token,
                    refreshToken = field(obj, "refreshToken", decryptor),
                    countryCode = field(obj, "countryCode", decryptor),
                    premium = obj.optBoolean("premium", false),
                )
        }
        return out
    }

    private fun parseQobuz(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<QobuzPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<QobuzPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val token = field(obj, "token", decryptor) ?: continue
            val appId = field(obj, "appId", decryptor) ?: continue
            // Without an app secret the app cannot sign Qobuz stream URLs, so such an account is
            // useless for playback and is skipped rather than cached as a dead entry.
            val appSecret = field(obj, "appSecret", decryptor) ?: continue
            out +=
                QobuzPoolAccount(
                    id = entryId(obj),
                    token = token,
                    appId = appId,
                    appSecret = appSecret,
                    premium = obj.optBoolean("premium", false),
                )
        }
        return out
    }

    private fun parseDeezer(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<DeezerPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<DeezerPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val arl = field(obj, "arl", decryptor) ?: continue
            out +=
                DeezerPoolAccount(
                    id = entryId(obj),
                    arl = arl,
                    premium = obj.optBoolean("premium", false),
                    masterSecret = field(obj, "masterSecret", decryptor),
                )
        }
        return out
    }

    private fun parseAmazon(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<AmazonPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<AmazonPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            // Either tier is useful on its own: the account tier carries a session, the instance
            // tier a base URL. An entry with neither is not something playback can use.
            val baseUrl = obj.optString("baseUrl", "").trim().ifBlank { null }
            val session = field(obj, "session", decryptor)
            val turnstileJwtExpiresAtMs =
                obj.optLong("turnstileJwtExpiresAtMs", 0L).takeIf { it > 0L }
                    ?: parseIsoMillis(obj.optString("turnstileJwtExpiresAt", ""))
            if (baseUrl == null && session == null) continue
            out +=
                AmazonPoolAccount(
                    id = entryId(obj),
                    session = session,
                    premium = obj.optBoolean("premium", false),
                    baseUrl = baseUrl,
                    bypassToken = field(obj, "bypassToken", decryptor),
                    turnstileJwt = field(obj, "turnstileJwt", decryptor),
                    turnstileJwtExpiresAtMs = turnstileJwtExpiresAtMs,
                )
        }
        return out
    }

    /** Plaintext pool timestamps (the JWT expiry) as epoch millis; unparseable ones read as absent. */
    private fun parseIsoMillis(raw: String): Long? =
        raw.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }

    /** Pool entry id from the accounts feed (positive when present); null for manual/legacy entries. */
    private fun entryId(obj: JSONObject): Long? =
        obj.optLong("id", 0L).takeIf { it > 0L }

    private fun parseAppleMusic(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<AppleMusicPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<AppleMusicPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val token = field(obj, "token", decryptor) ?: continue
            if (!token.startsWith("0.")) continue // media-user-tokens always start with "0."
            out +=
                AppleMusicPoolAccount(
                    id = entryId(obj),
                    mediaUserToken = token,
                    premium = obj.optBoolean("premium", true),
                )
        }
        return out
    }
}
