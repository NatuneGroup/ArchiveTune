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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.BuildConfig
import timber.log.Timber

/**
 * Pool-wide auth backoff, shared by every ArchivePool transport: an HTTP 401 means the build's
 * read key is missing or invalid, and retrying only bills more rejections until a new key ships.
 * Each rejection doubles the quiet window ([INITIAL_MS] up to [MAX_MS]); a 2xx clears it, and a
 * changed baked read key clears it on the next [load]. While [active] is true no pool request
 * leaves — feeds, discovery and reports alike — and callers keep serving whatever they already
 * cached: the leased credentials authenticate to their providers, not to the pool.
 */
internal object PoolAuthBackoff {

    private const val INITIAL_MS = 15L * 60 * 1000L
    private const val MAX_MS = 24L * 60 * 60 * 1000L

    private val UNTIL_KEY = longPreferencesKey("poolAuthBackoffUntilMs")
    private val WINDOW_KEY = longPreferencesKey("poolAuthBackoffWindowMs")
    private val KEY_HASH_KEY = stringPreferencesKey("poolAuthBackoffKeyHash")

    @Volatile
    private var untilMs = 0L

    @Volatile
    private var windowMs = 0L

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loadRequested = false

    @Volatile
    private var contextHint: Context? = null

    /** Completed once the persisted window has been read — or its read failed, counted inactive. */
    private val ready = CompletableDeferred<Unit>()

    /**
     * True once the persisted window is loaded. The getter also kicks [ensureLoaded] through the
     * context hint, so synchronous discovery paths can gate on it without a suspend hop.
     */
    val isReady: Boolean
        get() {
            contextHint?.let(::ensureLoaded)
            return ready.isCompleted
        }

    /** True while a persisted 401 backoff window is open. */
    fun active(): Boolean {
        contextHint?.let(::ensureLoaded)
        return untilMs != 0L && System.currentTimeMillis() < untilMs
    }

    /**
     * Suspends until the persisted window is read, capped at [timeoutMs] so a slow DataStore
     * read cannot stall a resolver; gates then use whatever state was loaded in time.
     */
    suspend fun awaitReady(timeoutMs: Long = 2_000L) {
        contextHint?.let(::ensureLoaded)
        withTimeoutOrNull(timeoutMs) { ready.await() }
    }

    /**
     * Kicks off [load] once per process, from whichever caller asks first. Gate checks cannot
     * suspend for a DataStore read, so the first gate check fires the load and every later check
     * sees the persisted window — startup discovery no longer races ahead of it.
     */
    fun ensureLoaded(context: Context) {
        val appContext = context.applicationContext
        contextHint = appContext
        if (loadRequested) return
        loadRequested = true
        persistScope.launch {
            runCatching {
                load(appContext, BuildConfig.SOURCE_PROVIDER_KEY.hashCode().toString())
            }.onFailure {
                // An unreadable window is treated as inactive, but a later call may retry the load.
                untilMs = 0L
                windowMs = 0L
                loadRequested = false
            }
            ready.complete(Unit)
        }
    }

    /**
     * Records one definitive pool answer: a 401 doubles the quiet window, any 2xx clears it.
     * Persistence is best-effort — pass the context when one is around; the in-memory window
     * binds this process either way.
     */
    fun noteAnswer(
        status: Int,
        context: Context?,
    ) {
        when {
            status == 401 -> {
                windowMs = if (windowMs <= 0L) INITIAL_MS else (windowMs * 2).coerceAtMost(MAX_MS)
                untilMs = System.currentTimeMillis() + windowMs
                persist(context)
            }
            status in 200..299 -> {
                if (untilMs == 0L && windowMs == 0L) return
                untilMs = 0L
                windowMs = 0L
                persist(context)
            }
        }
    }

    /**
     * Loads the persisted window once per process. The backoff is bound to the read key that
     * produced it: a build shipping a different [keyHash] starts clean instead of sitting out
     * up to a day of another key's rejections.
     */
    suspend fun load(
        context: Context,
        keyHash: String,
    ) {
        val storedHash = context.dataStore.getAsync(KEY_HASH_KEY)
        if (storedHash != null && storedHash != keyHash) {
            untilMs = 0L
            windowMs = 0L
            persist(context)
        } else {
            untilMs = maxOf(untilMs, context.dataStore.getAsync(UNTIL_KEY) ?: 0L)
            windowMs = maxOf(windowMs, context.dataStore.getAsync(WINDOW_KEY) ?: 0L)
        }
    }

    private fun persist(context: Context?) {
        context ?: return
        val until = untilMs
        val window = windowMs
        val keyHash = BuildConfig.SOURCE_PROVIDER_KEY.hashCode().toString()
        persistScope.launch {
            runCatching {
                context.dataStore.edit { prefs ->
                    if (until <= 0L) {
                        prefs.remove(UNTIL_KEY)
                        prefs.remove(WINDOW_KEY)
                        prefs.remove(KEY_HASH_KEY)
                    } else {
                        prefs[UNTIL_KEY] = until
                        prefs[WINDOW_KEY] = window
                        prefs[KEY_HASH_KEY] = keyHash
                    }
                }
            }.onFailure { Timber.tag("PoolAccounts").w(it, "Failed to persist pool auth backoff") }
        }
    }
}
