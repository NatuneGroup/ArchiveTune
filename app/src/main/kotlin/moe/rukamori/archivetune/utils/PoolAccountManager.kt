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
import okhttp3.OkHttpClient
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
     * True when a pool is configured and the user has not opted out; provider-local pool feeds
     * follow the same switch as the accounts.
     */
    fun isPoolEnabled(): Boolean = poolAccountsEnabled && isEnabled

    fun authBackoffActive(): Boolean {
        appContext?.let(PoolAuthBackoff::ensureLoaded)
        return PoolAuthBackoff.active()
    }

    fun notePoolAuthAnswer(status: Int) = PoolAuthBackoff.noteAnswer(status, appContext)

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

    private val rejectedRefreshAt = ConcurrentHashMap<String, Long>()
    private const val REJECT_REFRESH_MIN_INTERVAL_MS = 5L * 60 * 1000L

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

    internal val refreshMutex = Mutex()

    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    /** Fire-and-forget reports survive app lifecycle (SupervisorJob); reports never gate playback. */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when a Source Pool URL is configured, i.e. account discovery is possible. */
    val isEnabled: Boolean
        get() = PoolEndpoints.configuredBaseUrl().isNotBlank()

    private val poolBaseUrl: String
        get() = PoolEndpoints.configuredBaseUrl()

    /**
     * Credentials-only feed of the split pool. The server also serves a combined legacy feed at
     * /api/sources (accounts + instance URLs together) for older app builds; this client asks
     * for the narrow account feed so tokens and instance URLs arrive over separate URLs.
     * Falls back to the legacy URL only when the pool deployment predates the split (404).
     * Every request walks the deployments [PoolEndpoints] orders for it: the build-configured
     * primary first, then the public natune.workers.dev fallback.
     */

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
    internal fun refreshIntervalMs(): Long =
        PoolRefreshPolicy.intervalMs(hasEveryService())

    /**
     * Loads the persisted account cache into memory (cheap, no network). Safe to call repeatedly;
     * only reads the DataStore once. Call early on startup so resolvers have data before the first
     * network [refresh] finishes.
     */
    suspend fun loadCached(context: Context) {
        appContext = context.applicationContext
        syncPoolAccountsEnabled(context)
        if (loadedFromDisk) return
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
                // The persisted backoff is bound to the key that produced it; a changed key starts clean.
                PoolAuthBackoff.load(context, BuildConfig.SOURCE_PROVIDER_KEY.hashCode().toString())
                // The expiry-failure stamp restores with the rest of the pool cache so a
                // restart cannot silently re-enable an unthrottled re-lease storm.
                PoolExpiryReleaser.restoreFailureStamp(context)
                // The persisted cache stores DECRYPTED plaintext JSON (PoolCacheCrypto handles the
                // at-rest layer), so the parse-time decryptor is a pure pass-through.
                val passthrough: (String) -> String? = { raw -> raw }
                cached(context, CACHE_TIDAL_KEY)?.takeIf { it.isNotBlank() }?.let {
                    tidalCache = PoolFeedParser.parseTidal(JSONArray(it), passthrough)
                }
                cached(context, CACHE_QOBUZ_KEY)?.takeIf { it.isNotBlank() }?.let {
                    qobuzCache = PoolFeedParser.parseQobuz(JSONArray(it), passthrough)
                }
                cached(context, CACHE_DEEZER_KEY)?.takeIf { it.isNotBlank() }?.let {
                    deezerCache = PoolFeedParser.parseDeezer(JSONArray(it), passthrough)
                }
                cached(context, CACHE_APPLE_KEY)?.takeIf { it.isNotBlank() }?.let {
                    appleMusicCache = PoolFeedParser.parseAppleMusic(JSONArray(it), passthrough)
                }
                cached(context, CACHE_AMAZON_KEY)?.takeIf { it.isNotBlank() }?.let {
                    amazonCache = PoolFeedParser.parseAmazon(JSONArray(it), passthrough)
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
                fetchFeed(context)

                hasAccounts()
            }
        }

    /**
     * The network half of a pool refresh, run while [refreshMutex] is held by [refresh] or
     * [PoolExpiryReleaser.reLeaseOnExpiry]: waits out the persisted 401 backoff, stamps the
     * attempt up-front (even a failed fetch counts toward the routine TTL throttle), walks the
     * split /api/accounts feed then the legacy /api/sources on 404, and records [lastFeedError].
     * Returns the fetch outcome, or null when no request left the device (no URL configured, or
     * the auth backoff still open).
     */
    internal suspend fun fetchFeed(context: Context): FeedFetch? {
        PoolAuthBackoff.awaitReady()
        if (!isEnabled) {
            // isEnabled already guarantees a URL, so this only guards the impossible.
            lastFeedError = null
            Timber.tag(TAG).d("No Source Pool URL configured; nothing to refresh")
            return null
        }
        if (authBackoffActive()) {
            // A persisted 401 backoff quiets the feed; the cached lease keeps serving.
            Timber.tag(TAG).d("Pool feed auth backoff active; not fetching")
            lastFeedError =
                "Pool rejected the API key (HTTP 401) — further requests paused after repeated rejections."
            return null
        }
        // The read key is baked in at build time; there is no on-device override.
        val readKey = BuildConfig.SOURCE_PROVIDER_KEY
        poolApiKey = readKey.ifBlank { null }
        val attemptAt = System.currentTimeMillis()
        lastRefreshAtMillis = attemptAt
        context.dataStore.edit { it[CACHE_LAST_REFRESH_KEY] = attemptAt }

        var result = fetchAccounts(context, "/api/accounts", readKey)
        // Pool deployment predates the split feed — retry the combined URL.
        if (!result.succeeded && result.code == 404) {
            Timber.tag(TAG).d("/api/accounts unavailable; falling back to legacy /api/sources")
            result = fetchAccounts(context, "/api/sources", readKey)
        }
        // Record why the fetch failed — or clear it on success — so the settings screen can show
        // the reason rather than a generic "failed"; a Boolean cannot carry the distinction.
        lastFeedError =
            when {
                result.succeeded -> null
                // Both the split feed and the legacy combined feed 404'd. That is not an "old pool
                // deployment" — nothing at this host serves the pool API at all (wrong
                // SOURCE_PROVIDER_URL, or a deployment that no longer exists). A missing or
                // invalid read key is a 401, never a 404.
                result.code == 404 -> {
                    Timber.tag(TAG)
                        .e(
                            "No pool API at %s — both /api/accounts and /api/sources returned 404. " +
                                "the configured pool URL points at a host that is not an ArchivePool deployment " +
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
        return result
    }

    /** Outcome of one feed fetch: the parsed JSON on success, plus the HTTP code (0 = network error). */
    internal class FeedFetch(
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
    /**
     * Logs why a feed read failed. HTTP 401 specifically means the pool's read-key enforcement
     * rejected the presented key (revoked, deleted, or from an older pool database) — surfacing it
     * explicitly keeps the failure mode obvious instead of looking like an empty pool.
     */
    private fun logFeedRejection(
        path: String,
        code: Int,
    ) {
        if (code == 401) {
            Timber.tag(TAG).w(
                "Pool account feed rejected the presented key (HTTP 401) — " +
                    "it is revoked, deleted, or predates the pool's current database.",
            )
        } else {
            Timber.tag(TAG).w("Pool account feed %s returned HTTP %d", path, code)
        }
    }

    /** Suppresses repeat reports inside [REPORT_DEDUPE_WINDOW_MS] and prunes stale stamps. */
    private fun recentlyReported(dedupeKey: String): Boolean {
        val now = System.currentTimeMillis()
        val lastReported = reportDedupe[dedupeKey]
        if (lastReported != null && now - lastReported < REPORT_DEDUPE_WINDOW_MS) return true
        reportDedupe[dedupeKey] = now
        reportDedupe.entries.removeIf { (_, ts) -> now - ts > REPORT_DEDUPE_WINDOW_MS }
        return false
    }
    /**
     * Applies one feed reply: non-200 is logged and reported by code, and a 200 with all-empty
     * lists keeps the previous cache so a transient server bug cannot vanish every provider
     * mid-playback.
     */
    private suspend fun applyFeedReply(
        context: Context,
        path: String,
        reply: Pair<Int, String>?,
        readKey: String,
    ): FeedFetch {
        if (reply == null || reply.first != 200) {
            logFeedRejection(path, reply?.first ?: 0)
            return FeedFetch(null, reply?.first ?: 0)
        }
        val root = JSONObject(reply.second)
        val decryptor = PoolFeedParser.decryptorFor(root, readKey)
        val tidal = PoolFeedParser.parseTidal(PoolFeedParser.accountsArray(root, "tidal"), decryptor)
        val qobuz = PoolFeedParser.parseQobuz(PoolFeedParser.accountsArray(root, "qobuz"), decryptor)
        val deezer = PoolFeedParser.parseDeezer(PoolFeedParser.accountsArray(root, "deezer"), decryptor)
        val apple = PoolFeedParser.parseAppleMusic(PoolFeedParser.accountsArray(root, "apple-music"), decryptor)
        val amazon = PoolFeedParser.parseAmazon(PoolFeedParser.accountsArray(root, "amazon-music"), decryptor)
        if (!isPoolEnabled()) return FeedFetch(root, 200)
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
        return FeedFetch(root, 200)
    }
    private suspend fun fetchAccounts(
        context: Context,
        path: String,
        readKey: String,
    ): FeedFetch {
        val reply = PoolEndpoints.get(client, path, readKey)
        return try {
            applyFeedReply(context, path, reply, readKey)
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
        if (id == null || !isPoolEnabled() || authBackoffActive()) return

        // Deduplicate reports ~10 min: one dead token is hit by every racing resolver at once.
        if (recentlyReported("$service:$id:$reportType")) return

        reportScope.launch {
            PoolAuthBackoff.awaitReady()
            if (!isPoolEnabled() || authBackoffActive()) return@launch
            runCatching {
                val body =
                    JSONObject()
                        .put("service", service)
                        .put("kind", kind)
                        .put("id", id)
                        .put("report", reportType)
                        .toString()
                val readKey = poolApiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.SOURCE_PROVIDER_KEY
                val reply = PoolEndpoints.postReport(client, "/api/report", readKey, body)
                if (reply == null || reply.first !in 200..299) {
                    Timber.tag(TAG).d("Pool report %s/%s/%s returned HTTP %d", service, reportType, id, reply?.first ?: 0)
                    return@runCatching
                }
                val responseBody = reply.second
                if (responseBody.isNotBlank()) {
                    val root = JSONObject(responseBody)
                    if (root.optBoolean("ok", false) && kind == "account" && readKey.isNotBlank()) {
                        mergeReplacement(root, service, id, readKey)
                    }
                }
            }.onFailure { Timber.tag(TAG).w(it, "Pool report failed") }
        }
    }

    /**
     * Forced feed refresh after a provider really rejected a leased credential (401/403), bounded
     * to one per [REJECT_REFRESH_MIN_INTERVAL_MS] per service; fire-and-forget like [report].
     */
    fun refreshOnRejection(
        context: Context,
        service: String,
    ) {
        // Atomic check+stamp: a bare get+put let every concurrent rejection launch a re-lease.
        val now = System.currentTimeMillis()
        synchronized(rejectedRefreshAt) {
            if (now - (rejectedRefreshAt[service] ?: 0L) < REJECT_REFRESH_MIN_INTERVAL_MS) return
            rejectedRefreshAt[service] = now
        }
        reportScope.launch {
            runCatching { refresh(context.applicationContext, force = true) }
                .onFailure { Timber.tag(TAG).w(it, "Pool re-lease after %s rejection failed", service) }
        }
    }

    /**
     * Accounts array for one service, tolerant of both feed shapes: the canonical
     * `{ "tidal": { "accounts": [...] } }` and a bare `{ "tidal": [...] }`. Null when absent.
     */
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
        val decryptor = PoolFeedParser.decryptorFor(replacementObj, readKey)
        val replacementArr = replacementObj.optJSONObject(service)?.optJSONArray("accounts") ?: JSONArray()

        refreshMutex.withLock {
            if (!isPoolEnabled()) return@withLock
            when (service) {
                "tidal" -> tidalCache = PoolFeedParser.mergeList(tidalCache, deadId, TidalPoolAccount::id, replacementArr, PoolFeedParser::parseTidal, decryptor) ?: return@withLock
                "qobuz" -> qobuzCache = PoolFeedParser.mergeList(qobuzCache, deadId, QobuzPoolAccount::id, replacementArr, PoolFeedParser::parseQobuz, decryptor) ?: return@withLock
                "deezer" -> deezerCache = PoolFeedParser.mergeList(deezerCache, deadId, DeezerPoolAccount::id, replacementArr, PoolFeedParser::parseDeezer, decryptor) ?: return@withLock
                "apple-music" -> appleMusicCache = PoolFeedParser.mergeList(appleMusicCache, deadId, AppleMusicPoolAccount::id, replacementArr, PoolFeedParser::parseAppleMusic, decryptor) ?: return@withLock
                "amazon-music" -> amazonCache = PoolFeedParser.mergeList(amazonCache, deadId, AmazonPoolAccount::id, replacementArr, PoolFeedParser::parseAmazon, decryptor) ?: return@withLock
                else -> return@withLock
            }
            persist(ctx, tidalCache, qobuzCache, deezerCache, appleMusicCache, amazonCache)
        }
    }
}
