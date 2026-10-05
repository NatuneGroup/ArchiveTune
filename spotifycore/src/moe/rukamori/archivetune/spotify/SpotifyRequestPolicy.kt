/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

suspend fun <T> spotifyRequestResult(request: suspend () -> T): Result<T> =
    try {
        Result.success(request())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

suspend fun <T> spotifyGraphQlWithRestFallback(
    graphQl: suspend () -> T,
    rest: suspend () -> T,
): T =
    try {
        graphQl()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error is Spotify.SpotifyException && error.statusCode in setOf(401, 403, 429)) throw error
        rest()
    }

fun SpotifyTrack.needsRestMetadata(): Boolean =
    name.isBlank() || artists.none { it.name.isNotBlank() } || durationMs <= 0

fun spotifyRetryAfterSeconds(value: String?, nowMillis: Long): Long? {
    val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    trimmed.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0L } }
    return runCatching {
        val deadline = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        val remaining = (deadline - nowMillis).coerceAtLeast(0L)
        remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
    }.getOrNull()
}

class SpotifyRequestCooldown(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val blockedUntilMillis = AtomicLong(0L)

    fun remainingSeconds(): Long {
        val remaining = (blockedUntilMillis.get() - nowMillis()).coerceAtLeast(0L)
        return remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
    }

    fun recordRateLimit(retryAfter: String?): Long {
        val now = nowMillis()
        val cooldown = rateLimitCooldownMillis(spotifyRetryAfterSeconds(retryAfter, now))
        blockedUntilMillis.accumulateAndGet(now + cooldown, ::maxOf)
        return remainingSeconds()
    }
}
