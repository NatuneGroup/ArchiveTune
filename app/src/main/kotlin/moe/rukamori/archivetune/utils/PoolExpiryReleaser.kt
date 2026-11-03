/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.tidal.TidalAccountManager
import timber.log.Timber

/**
 * The awaited expiry re-lease for the community Source Pool: when a cached Tidal credential is
 * already dead on arrival, resolvers hand the rescue to [reLeaseOnExpiry] BEFORE spending it —
 * one bounded, single-flight fetch, shared with [PoolAccountManager.refresh] through
 * [PoolAccountManager.refreshMutex]. A failed attempt — including a bounded wait that timed out
 * or was cancelled — stamps [lastExpiryReleasFailureAtMs], persisted in the pool cache keys so a
 * restart cannot silently re-enable an unthrottled re-lease storm; the stamp clears only after a
 * rescue that actually landed.
 */
internal object PoolExpiryReleaser {

    private const val TAG = "PoolAccounts"

    /** Cap on one awaited expiry re-lease; a resolver must not park on the pool for longer. */
    private const val EXPIRY_RELEAS_TIMEOUT_MS = 8_000L

    private val CACHE_EXPIRY_RELEAS_FAILURE_KEY =
        longPreferencesKey("poolAccountsExpiryReleasFailureAtMs")

    /**
     * Throttle for the awaited expiry re-lease ([reLeaseOnExpiry]): stamped when an awaited
     * re-lease failed to clear the expiry, so a down/rejecting pool is not pounded by every
     * expired token in the fleet. Unlike the routine 5–24h cache TTL — which exists to keep a
     * healthy cache cheap — this is the ONLY bound on expiry re-leases; a successful one leaves
     * no stamp at all.
     */
    @Volatile private var lastExpiryReleasFailureAtMs = 0L

    /**
     * Merges the persisted failure stamp into memory. Called from [PoolAccountManager.loadCached]
     * after the auth backoff is loaded, so the stamp rides the same restore path as the rest of
     * the pool cache before any gate consults it.
     */
    suspend fun restoreFailureStamp(context: Context) {
        lastExpiryReleasFailureAtMs =
            PoolRefreshPolicy.latestAttemptAtMs(
                lastExpiryReleasFailureAtMs,
                context.dataStore.getAsync(CACHE_EXPIRY_RELEAS_FAILURE_KEY) ?: 0L,
            )
    }

    /**
     * Awaited, bounded, single-flight re-lease fired when the credential a resolver is about to
     * spend is already expired. [stillExpired] is re-checked under
     * [PoolAccountManager.refreshMutex] so concurrent callers share one fetch: a caller arriving
     * behind a successful re-lease sees fresh credentials and returns true without fetching; a
     * caller arriving behind a FAILED one honours the failure stamp instead of fetching again.
     *
     * Unlike [PoolAccountManager.refresh]'s routine 5–24h cache TTL — which exists to keep a
     * healthy cache cheap and must NOT leave dead tokens in service for hours — this path is
     * gated only by its own failures ([expiryReleasAllowed]): one full interval after an attempt
     * that could not clear the expiry, and nothing after a success. The whole wait is capped at
     * [timeoutMs]; a timeout while another caller is mid-fetch just returns false (the running
     * fetch stamps its own failure). Returns true only when the expiry was actually cleared.
     * Cancellation propagates.
     */
    suspend fun reLeaseOnExpiry(
        context: Context,
        timeoutMs: Long = EXPIRY_RELEAS_TIMEOUT_MS,
        stillExpired: () -> Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            PoolAccountManager.loadCached(context)
            if (
                !PoolAccountManager.isEnabled ||
                !PoolAccountManager.isPoolEnabled() ||
                !stillExpired() ||
                !expiryReleasAllowed()
            ) {
                return@withContext false
            }
            withTimeoutOrNull(timeoutMs) {
                PoolAccountManager.refreshMutex.withLock {
                    when {
                        !PoolAccountManager.isPoolEnabled() -> false
                        // A concurrent caller's re-lease just landed — share it, don't refetch.
                        !stillExpired() -> true
                        // Its attempt failed inside this expiry episode — share the throttle too.
                        !expiryReleasAllowed() -> false
                        else -> fetchFeedAndCheckExpiry(context, stillExpired)
                    }
                }
            } == true
        }

    /**
     * Fetches the feed (caller holds [PoolAccountManager.refreshMutex]), then reports whether
     * the expiry actually cleared. A fetch that still leaves expired credentials — a failed
     * request, an auth-backoff skip, or a server leasing stale tokens — counts as a failure for
     * the throttle: the expiry is not resolvable right now.
     */
    private suspend fun fetchFeedAndCheckExpiry(
        context: Context,
        stillExpired: () -> Boolean,
    ): Boolean {
        var cleared = false
        try {
            PoolAccountManager.fetchFeed(context)
            cleared = !stillExpired()
        } finally {
            // This finally survives the bounded wait cancelling the fetch: a timed-out or
            // otherwise failed attempt is still THIS expiry episode's failed re-lease, so the
            // stamp must land or later plays retry the slow pool unthrottled. NonCancellable
            // keeps the write — including the persist hop — alive past the fetch's
            // cancellation; the stamp clears only after a rescue that actually landed.
            withContext(NonCancellable) {
                setExpiryReleasFailureAtMs(
                    context,
                    PoolRefreshPolicy.expiryReleasFailureStamp(
                        cleared,
                        System.currentTimeMillis(),
                    ),
                )
            }
        }
        return cleared
    }

    /**
     * Records the expiry re-lease failure stamp in memory and in the pool cache. Persistence is
     * best-effort: the in-memory stamp binds this process either way; a failed write just means
     * a restart forgets the throttle (the load merges whatever is on disk).
     */
    private suspend fun setExpiryReleasFailureAtMs(
        context: Context,
        atMs: Long,
    ) {
        lastExpiryReleasFailureAtMs = atMs
        try {
            context.dataStore.edit { prefs ->
                if (atMs <= 0L) {
                    prefs.remove(CACHE_EXPIRY_RELEAS_FAILURE_KEY)
                } else {
                    prefs[CACHE_EXPIRY_RELEAS_FAILURE_KEY] = atMs
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to persist the expiry re-lease failure stamp")
        }
    }

    /**
     * Coroutine entry point for "re-lease before trying": when a cached Tidal pool token is
     * already expired, fire the awaited [reLeaseOnExpiry] (shared with any concurrent caller)
     * and return the freshest account list — the freshly leased set when it lands, else the
     * cached one. A caller cancellation aborts the wait AND its fetch; it is never masked by a
     * fallback to the cached leases.
     */
    suspend fun rescuedTidalAccountsSuspend(
        context: Context,
    ): List<PoolAccountManager.TidalPoolAccount> {
        val cached = PoolAccountManager.tidalAccounts()
        if (cached.none { TidalAccountManager.isAccessTokenExpired(it.token) }) return cached
        try {
            reLeaseOnExpiry(context) {
                PoolAccountManager.tidalAccounts().any {
                    TidalAccountManager.isAccessTokenExpired(it.token)
                }
            }
        } catch (error: CancellationException) {
            // Caller cancellation — the coroutine was asked to stop; it must not keep resolving
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Tidal expiry re-lease failed; using cached accounts")
        }
        return PoolAccountManager.tidalAccounts().ifEmpty { cached }
    }

    /**
     * Blocking bridge for callers pinned to a plain thread (the Media3 loader path): delegates
     * to [rescuedTidalAccountsSuspend] inside [runBlocking], so inner cancellation surfaces as
     * [CancellationException] and a thread interrupt as [InterruptedException] — both rethrown,
     * never masked by a stale-lease fallback.
     */
    fun rescuedTidalAccounts(context: Context): List<PoolAccountManager.TidalPoolAccount> {
        try {
            return runBlocking(Dispatchers.IO) { rescuedTidalAccountsSuspend(context) }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            throw error
        }
    }

    /** True while no failed expiry re-lease sits within one routine refresh interval of now. */
    private fun expiryReleasAllowed(): Boolean =
        PoolRefreshPolicy.shouldAttemptExpiryReleas(
            System.currentTimeMillis(),
            lastExpiryReleasFailureAtMs,
            PoolAccountManager.refreshIntervalMs(),
        )
}
