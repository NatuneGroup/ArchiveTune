/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unauthenticated api.github.com allows 60 requests per hour per IP. When a response says the
 * budget is spent, the updater must stand down until the reset epoch and serve caches instead —
 * and a refusal without the limit headers must not trigger a backoff at all.
 */
class GitHubRateLimitTest {
    private val resetEpochSec = 1_760_000_000L
    private val resetEpochMs = resetEpochSec * 1000L
    private val beforeReset = resetEpochMs - 1
    private val afterReset = resetEpochMs

    @Test
    fun rateLimited403BlocksUntilReset() {
        val until =
            GitHubRateLimit.backoffUntil(
                statusCode = 403,
                rateLimitRemainingHeader = "0",
                rateLimitResetHeader = resetEpochSec.toString(),
                nowMs = beforeReset,
            )

        assertEquals(resetEpochMs, until)
        assertTrue(GitHubRateLimit.isBlocked(until!!, nowMs = beforeReset))
        assertFalse(GitHubRateLimit.isBlocked(until, nowMs = afterReset))
    }

    @Test
    fun rateLimited429BlocksUntilReset() {
        val until =
            GitHubRateLimit.backoffUntil(
                statusCode = 429,
                rateLimitRemainingHeader = "0",
                rateLimitResetHeader = resetEpochSec.toString(),
                nowMs = beforeReset,
            )

        assertEquals(resetEpochMs, until)
        assertTrue(GitHubRateLimit.isBlocked(until!!, nowMs = beforeReset))
        assertFalse(GitHubRateLimit.isBlocked(until, nowMs = afterReset))
    }

    @Test
    fun noBackoffWhenRemainingHeaderIsAbsent() {
        assertNull(
            GitHubRateLimit.backoffUntil(
                statusCode = 403,
                rateLimitRemainingHeader = null,
                rateLimitResetHeader = resetEpochSec.toString(),
                nowMs = beforeReset,
            ),
        )
    }

    @Test
    fun noBackoffWhenRemainingIsNotZero() {
        assertNull(
            GitHubRateLimit.backoffUntil(
                statusCode = 403,
                rateLimitRemainingHeader = "5",
                rateLimitResetHeader = resetEpochSec.toString(),
                nowMs = beforeReset,
            ),
        )
    }

    @Test
    fun noBackoffOnSuccessStatus() {
        assertNull(
            GitHubRateLimit.backoffUntil(
                statusCode = 200,
                rateLimitRemainingHeader = "0",
                rateLimitResetHeader = resetEpochSec.toString(),
                nowMs = beforeReset,
            ),
        )
    }

    @Test
    fun missingResetHeaderStillBlocksForAShortWindow() {
        val until =
            GitHubRateLimit.backoffUntil(
                statusCode = 429,
                rateLimitRemainingHeader = "0",
                rateLimitResetHeader = null,
                nowMs = 5_000L,
            )

        assertTrue(until!! > 5_000L)
        assertTrue(GitHubRateLimit.isBlocked(until, nowMs = 5_001L))
    }

    @Test
    fun nothingIsBlockedWithNoRecordedBackoff() {
        assertFalse(GitHubRateLimit.isBlocked(blockedUntilMs = 0L, nowMs = 1L))
    }
}
