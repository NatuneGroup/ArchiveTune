/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import java.time.Instant
import moe.rukamori.archivetune.spotify.models.SpotifyPlayHistory

/** The most plays `me/player/recently-played` returns at once, and so the ceiling of the window. */
const val SPOTIFY_HISTORY_WINDOW = 50

/**
 * How long a 429 keeps us off the history endpoint when Spotify sends no usable `Retry-After`.
 *
 * Spotify documents its Web API limit as a rolling 30-second window, so a shorter wait cannot clear
 * it: retrying inside the window is what turns one 429 into a loop.
 */
const val SPOTIFY_RATE_LIMIT_FALLBACK_MS = 30_000L

/**
 * Longest wait a `Retry-After` may impose; anything past it is a stuck header, not an answer.
 *
 * Spotify does send day-long values: Stash — the other Kotlin client that logs in with `sp_dc` +
 * TOTP, like this one — records `Retry-After: 86400` on the public Web API for exactly this token
 * class. A day is therefore honoured rather than clamped, since retrying inside a block cannot
 * succeed and, with a cached window, waiting costs nothing.
 */
const val SPOTIFY_RATE_LIMIT_MAX_MS = 24 * 60 * 60 * 1000L

/**
 * Longest wait a history read will sit out before asking again.
 *
 * A short `Retry-After` is worth obeying — the plays are that far away, and the alternative is an
 * empty screen — but a day-long one is not: no screen should hold a coroutine open for hours, and a
 * read with a cached window has nothing to gain by it.
 */
const val SPOTIFY_HISTORY_RETRY_MAX_WAIT_MS = 60_000L

/**
 * How stale a delta read's cursor may get before the next read takes the whole window instead.
 *
 * A play Spotify inserts *behind* a cursor we already returned is invisible to `after` forever —
 * Listory's collector hit exactly that and stopped trusting cursors for new listens — so the delta
 * is healed on this interval. Against the five-minute cache that is one window read in six.
 */
const val SPOTIFY_HISTORY_FULL_READ_INTERVAL_MS = 30 * 60 * 1000L

/**
 * Epoch millis of the newest play in the list, or null when no entry carries a usable timestamp.
 *
 * This is the endpoint's `after` cursor: it turns "re-read the last [SPOTIFY_HISTORY_WINDOW] plays"
 * into "ask for the plays since what we already hold", which is what makes a refresh one delta read.
 */
fun List<SpotifyPlayHistory>.newestPlayedAtMillis(): Long? =
    asSequence()
        .mapNotNull { it.playedAtMillis() }
        .maxOrNull()

/**
 * Epoch millis for a `played_at` stamp (`2026-09-20T12:34:56.789Z`), or null when it is absent or
 * unparseable. Null is not an error: a play with no timestamp still belongs in the history, it just
 * cannot anchor a cursor.
 */
fun playedAtMillis(playedAt: String): Long? =
    runCatching { Instant.parse(playedAt).toEpochMilli() }.getOrNull()

/** [playedAtMillis] for this play's own stamp. */
fun SpotifyPlayHistory.playedAtMillis(): Long? = playedAt?.let { stamp -> playedAtMillis(stamp) }

/**
 * Identifies one play: the track, plus when it happened.
 *
 * `played_at` has millisecond precision and two plays of one track never share a millisecond, so the
 * pair is unique. A play with no timestamp falls back to the track alone, which at worst merges
 * duplicate rows of that track instead of duplicating them.
 */
private fun SpotifyPlayHistory.playKey(): String =
    (track?.uri ?: track?.id ?: track?.name.orEmpty()) + "@" + playedAt.orEmpty()

/**
 * [newer] merged in front of [cached], newest first, deduplicated, capped at [limit].
 *
 * A delta read returns only the plays after the cursor, so this is what the caller shows instead: the
 * rows a full window read would have produced, from a request that carried only the new plays.
 * [newer] wins a tie because it came from Spotify most recently and so carries the fresher track
 * metadata; an undated play sorts last rather than being dropped.
 */
fun mergePlayHistory(
    newer: List<SpotifyPlayHistory>,
    cached: List<SpotifyPlayHistory>,
    limit: Int = SPOTIFY_HISTORY_WINDOW,
): List<SpotifyPlayHistory> {
    val seen = HashSet<String>(newer.size + cached.size)
    val merged = ArrayList<SpotifyPlayHistory>(newer.size + cached.size)
    for (item in newer.asSequence() + cached.asSequence()) {
        if (seen.add(item.playKey())) merged += item
    }
    return merged
        .sortedByDescending { it.playedAtMillis() ?: Long.MIN_VALUE }
        .take(limit)
}

/**
 * Whether a read should take the whole window rather than a delta.
 *
 * False only when there is a cursor to delta from and a full read has run recently; see
 * [SPOTIFY_HISTORY_FULL_READ_INTERVAL_MS] for why the second condition exists. A caller that has
 * never taken a full read passes 0 for [lastFullReadAtMillis], which reads as "long ago" — the right
 * answer for a cold start and for a process that restarted holding a disk cache.
 */
fun needsFullHistoryRead(
    newestPlayedAtMillis: Long?,
    lastFullReadAtMillis: Long,
    nowMillis: Long,
    intervalMillis: Long = SPOTIFY_HISTORY_FULL_READ_INTERVAL_MS,
): Boolean =
    newestPlayedAtMillis == null ||
        nowMillis - newestPlayedAtMillis > intervalMillis ||
        nowMillis - lastFullReadAtMillis > intervalMillis

/**
 * How long to stay off the history endpoint after a 429.
 *
 * A usable `Retry-After` wins, because only Spotify knows when the window clears; the documented
 * 30-second window is the floor, so a short header cannot shorten the wait. See
 * [SPOTIFY_RATE_LIMIT_MAX_MS] for the one value that is not honoured.
 */
fun rateLimitCooldownMillis(retryAfterSec: Long?): Long {
    val fromHeader = retryAfterSec?.takeIf { it > 0 }?.let { it.coerceAtMost(SPOTIFY_RATE_LIMIT_MAX_MS / 1_000L) * 1_000L }
    return when {
        fromHeader == null -> SPOTIFY_RATE_LIMIT_FALLBACK_MS
        fromHeader > SPOTIFY_RATE_LIMIT_MAX_MS -> SPOTIFY_RATE_LIMIT_MAX_MS
        else -> maxOf(fromHeader, SPOTIFY_RATE_LIMIT_FALLBACK_MS)
    }
}

/**
 * How long the one retry of a rate-limited history read should wait, or null when it must not be
 * retried at all.
 *
 * Rows already on screen are never waited for: they are the reader's answer, so holding the refresh
 * open gains nothing.
 */
fun historyRetryWaitMillis(
    retryAfterSec: Long?,
    hasCachedRows: Boolean,
    quotaExceeded: Boolean = false,
): Long? {
    if (hasCachedRows || quotaExceeded) return null
    if (retryAfterSec == null || retryAfterSec <= 0) return null
    return rateLimitCooldownMillis(retryAfterSec).takeIf { it <= SPOTIFY_HISTORY_RETRY_MAX_WAIT_MS }
}
