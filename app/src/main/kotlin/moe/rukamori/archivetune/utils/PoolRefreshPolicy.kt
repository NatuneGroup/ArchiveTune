/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

object PoolRefreshPolicy {
    const val FULL_CACHE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    const val PARTIAL_CACHE_INTERVAL_MS = 5L * 60L * 60L * 1000L

    fun intervalMs(hasEveryService: Boolean): Long =
        if (hasEveryService) FULL_CACHE_INTERVAL_MS else PARTIAL_CACHE_INTERVAL_MS

    fun latestAttemptAtMs(inMemoryAtMs: Long, persistedAtMs: Long): Long =
        maxOf(0L, inMemoryAtMs, persistedAtMs)

    fun shouldRefresh(
        nowMs: Long,
        lastAttemptAtMs: Long,
        intervalMs: Long,
        force: Boolean,
    ): Boolean {
        if (force || lastAttemptAtMs <= 0L) return true
        return nowMs >= lastAttemptAtMs && nowMs - lastAttemptAtMs >= intervalMs
    }

    /**
     * Gate for an expiry-driven re-lease. The routine cache TTL ([shouldRefresh] on the last
     * attempt) exists to keep a healthy cache cheap — it must NOT gate a re-lease for a credential
     * that is dead on arrival, or expired tokens would sit in service for hours while their
     * guaranteed 401s get reported as account death. The only throttle that path has is one full
     * [intervalMs] measured from the last FAILED attempt, so a down or rejecting pool is still not
     * pounded by every expired token in the fleet.
     */
    fun shouldAttemptExpiryReleas(
        nowMs: Long,
        lastFailedAttemptAtMs: Long,
        intervalMs: Long,
    ): Boolean = shouldRefresh(nowMs, lastFailedAttemptAtMs, intervalMs, force = false)

    /**
     * The failure stamp a fetch-owning re-lease attempt writes: only a rescue that actually cleared
     * the expiry clears it (0L); anything short — a failed fetch, a bounded wait that timed out, or
     * a cancelled fetch — stamps [nowMs] so later plays do not retry the slow pool unthrottled
     * inside this expiry episode.
     */
    fun expiryReleasFailureStamp(cleared: Boolean, nowMs: Long): Long = if (cleared) 0L else nowMs
}
