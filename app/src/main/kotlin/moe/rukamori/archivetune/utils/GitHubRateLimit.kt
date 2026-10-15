/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

/**
 * Pure decision for GitHub's primary API rate limit. Unauthenticated api.github.com allows 60
 * requests per hour per IP, and a shared or CGNAT address burns that fast. When a response says
 * the budget is spent (403/429 with `X-RateLimit-Remaining: 0`), the updater records the reset
 * epoch and makes no further api.github.com call until it passes — caches are served meanwhile.
 * The update-feed manifest on github.com is not API-metered and is never blocked by this.
 */
internal object GitHubRateLimit {
    /**
     * The moment (epoch ms) until which api.github.com must be left alone, or null when this
     * response must not trigger a backoff. A 403/429 without the remaining-header (or with a
     * non-zero budget) is an ordinary refusal, not a spent budget.
     */
    fun backoffUntil(
        statusCode: Int,
        rateLimitRemainingHeader: String?,
        rateLimitResetHeader: String?,
        nowMs: Long,
    ): Long? {
        if (statusCode != 403 && statusCode != 429) return null
        val remaining = rateLimitRemainingHeader?.trim()?.toIntOrNull() ?: return null
        if (remaining != 0) return null
        val resetEpochSec = rateLimitResetHeader?.trim()?.toLongOrNull()
        return resetEpochSec?.let { it * 1000L } ?: (nowMs + DEFAULT_BACKOFF_MS)
    }

    fun isBlocked(
        blockedUntilMs: Long,
        nowMs: Long,
    ): Boolean = nowMs < blockedUntilMs

    /** Only when a rate-limited response omits `X-RateLimit-Reset`; GitHub normally sends it. */
    private const val DEFAULT_BACKOFF_MS: Long = 60_000L
}
