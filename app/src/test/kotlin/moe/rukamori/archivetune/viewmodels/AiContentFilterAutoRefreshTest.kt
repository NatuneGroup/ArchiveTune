/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening a settings page must not refresh the AI content filter more than once per interval. */
class AiContentFilterAutoRefreshTest {
    private val interval = AI_CONTENT_FILTER_AUTO_REFRESH_INTERVAL_MS
    private val now = 10L * interval

    @Test
    fun intervalIsSixHours() {
        assertEquals(6L * 60 * 60 * 1000, interval)
    }

    @Test
    fun neverRefreshed_refreshes() {
        assertTrue(shouldAutoRefreshAiContentFilter(now, lastRefreshAtMs = 0L))
    }

    @Test
    fun withinInterval_skips() {
        assertFalse(shouldAutoRefreshAiContentFilter(now, lastRefreshAtMs = now))
        assertFalse(shouldAutoRefreshAiContentFilter(now, lastRefreshAtMs = now - interval + 1))
    }

    @Test
    fun intervalElapsed_refreshes() {
        assertTrue(shouldAutoRefreshAiContentFilter(now, lastRefreshAtMs = now - interval))
        assertTrue(shouldAutoRefreshAiContentFilter(now, lastRefreshAtMs = now - 2 * interval))
    }

    @Test
    fun clockMovedBackwards_refreshes() {
        assertTrue(shouldAutoRefreshAiContentFilter(now, lastRefreshAtMs = now + 1))
    }

    @Test
    fun customInterval_isHonoured() {
        assertFalse(shouldAutoRefreshAiContentFilter(nowMs = 1_500L, lastRefreshAtMs = 1_000L, intervalMs = 1_000L))
        assertTrue(shouldAutoRefreshAiContentFilter(nowMs = 2_000L, lastRefreshAtMs = 1_000L, intervalMs = 1_000L))
    }
}
