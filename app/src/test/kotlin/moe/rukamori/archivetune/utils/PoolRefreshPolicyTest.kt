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
}
