/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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

fun isSpotifyQuotaExceededResponse(body: String): Boolean =
    runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject
        val error = root?.get("error") as? JsonObject
        (error?.get("status") as? JsonPrimitive)?.intOrNull == 429 &&
            (error?.get("reason") as? JsonPrimitive)?.content == "QUOTA_EXCEEDED"
    }.getOrDefault(false)

class SpotifyRequestCooldown(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private data class State(val untilMillis: Long = 0L, val quotaExceeded: Boolean = false)
    private val state = AtomicReference(State())

    val quotaExceeded: Boolean
        get() = state.get().let { it.quotaExceeded && it.untilMillis > nowMillis() }

    fun remainingSeconds(): Long {
        val remaining = (state.get().untilMillis - nowMillis()).coerceAtLeast(0L)
        return remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
    }

    fun recordRateLimit(retryAfter: String?, quotaExceeded: Boolean = false): Long {
        val now = nowMillis()
        val cooldown = rateLimitCooldownMillis(spotifyRetryAfterSeconds(retryAfter, now))
        state.updateAndGet { previous ->
            State(
                untilMillis = maxOf(previous.untilMillis, now + cooldown),
                quotaExceeded = quotaExceeded || (previous.quotaExceeded && previous.untilMillis > now),
            )
        }
        return remainingSeconds()
    }
}
