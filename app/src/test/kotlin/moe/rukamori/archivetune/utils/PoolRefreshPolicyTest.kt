/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolRefreshPolicyTest {
    @Test
    fun partialAndFullCacheIntervalsAreBounded() {
        assertEquals(5L * 60L * 60L * 1000L, PoolRefreshPolicy.intervalMs(hasEveryService = false))
        assertEquals(24L * 60L * 60L * 1000L, PoolRefreshPolicy.intervalMs(hasEveryService = true))
    }

    @Test
    fun emptyOrFailedFetchAttemptsStillUseTheThrottle() {
        val lastAttempt = 10_000L
        val interval = PoolRefreshPolicy.PARTIAL_CACHE_INTERVAL_MS

        assertFalse(PoolRefreshPolicy.shouldRefresh(lastAttempt + interval - 1L, lastAttempt, interval, force = false))
        assertTrue(PoolRefreshPolicy.shouldRefresh(lastAttempt + interval, lastAttempt, interval, force = false))
    }

    @Test
    fun clearingCredentialsOrRestartingDoesNotResetTheLatestAttempt() {
        assertEquals(20_000L, PoolRefreshPolicy.latestAttemptAtMs(10_000L, 20_000L))
        assertEquals(20_000L, PoolRefreshPolicy.latestAttemptAtMs(0L, 20_000L))
        assertEquals(20_000L, PoolRefreshPolicy.latestAttemptAtMs(20_000L, 0L))
        assertEquals(0L, PoolRefreshPolicy.latestAttemptAtMs(-1L, -1L))
    }

    @Test
    fun forceBypassesTheThrottle() {
        val lastAttempt = 10_000L

        assertTrue(PoolRefreshPolicy.shouldRefresh(lastAttempt + 1L, lastAttempt, PoolRefreshPolicy.FULL_CACHE_INTERVAL_MS, force = true))
    }

    @Test
    fun clockRollbackDoesNotTriggerAnEarlyRefresh() {
        assertFalse(PoolRefreshPolicy.shouldRefresh(9_999L, 10_000L, PoolRefreshPolicy.PARTIAL_CACHE_INTERVAL_MS, force = false))
    }

    @Test
    fun expiryReleasIsNotBlockedByTheRoutineCacheThrottle() {
        // A credential dead on arrival must not wait out the 5–24h TTL the routine refresh uses —
        // that throttle keeps a healthy cache cheap, it is not what gates an expiry re-lease.
        assertTrue(
            PoolRefreshPolicy.shouldAttemptExpiryReleas(
                10_000L,
                0L,
                PoolRefreshPolicy.PARTIAL_CACHE_INTERVAL_MS,
            ),
        )
        assertTrue(
            PoolRefreshPolicy.shouldAttemptExpiryReleas(
                10_000L,
                0L,
                PoolRefreshPolicy.FULL_CACHE_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun failedExpiryReleasEarnsOneFullIntervalBeforeRetry() {
        val failedAt = 1_000_000L
        val interval = PoolRefreshPolicy.PARTIAL_CACHE_INTERVAL_MS
        // Inside the interval after a failure the next expiry re-lease is suppressed...
        assertFalse(PoolRefreshPolicy.shouldAttemptExpiryReleas(failedAt + 1L, failedAt, interval))
        assertFalse(
            PoolRefreshPolicy.shouldAttemptExpiryReleas(
                failedAt + interval - 1L,
                failedAt,
                interval,
            ),
        )
        // ...and allowed again once it passes — the failure throttle is the only bound that path
        // has.
        assertTrue(
            PoolRefreshPolicy.shouldAttemptExpiryReleas(failedAt + interval, failedAt, interval),
        )
    }

    @Test
    fun clockRollbackSuppressesExpiryReleas() {
        assertFalse(
            PoolRefreshPolicy.shouldAttemptExpiryReleas(
                999L,
                1_000L,
                PoolRefreshPolicy.PARTIAL_CACHE_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun failedTimedOutOrCancelledReleasStampsNowAndOnlyARescueClearsIt() {
        // Every way a fetch-owning re-lease can end short of clearing the expiry — a rejected
        // fetch, the bounded wait timing out, or the fetch being cancelled — counts as this
        // episode's failed attempt and earns the throttle, so later plays do not retry the slow
        // pool unthrottled.
        assertEquals(
            5_000L,
            PoolRefreshPolicy.expiryReleasFailureStamp(cleared = false, nowMs = 5_000L),
        )
        assertEquals(
            60_000L,
            PoolRefreshPolicy.expiryReleasFailureStamp(cleared = false, nowMs = 60_000L),
        )
        // Only a rescue that actually landed clears the stamp.
        assertEquals(
            0L,
            PoolRefreshPolicy.expiryReleasFailureStamp(cleared = true, nowMs = 5_000L),
        )
    }
}
