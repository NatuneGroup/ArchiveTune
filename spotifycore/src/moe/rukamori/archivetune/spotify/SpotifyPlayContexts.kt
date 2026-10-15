/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.spotify

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.spotify.models.SpotifyPlayHistory
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

/**
 * One row of the web player's own play history: the last track played inside one context (a
 * playlist, album or artist page), and when.
 *
 * This is what `spclient.wg.spotify.com/recently-played/v3` knows. It is not the per-play log of
 * `me/player/recently-played`: a context that was played ten times since the last read shows up once,
 * as its latest track. It exists because that REST endpoint answers the web player's token with
 * `429 API rate limit exceeded` on the first call — the limit is shared by every user of the client
 * — while this endpoint, the one open.spotify.com's own "recently played" shelf reads, still answers.
 */
data class SpotifyContextPlay(
    val trackUri: String,
    val playedAtMillis: Long,
)

private const val TRACK_URI_PREFIX = "spotify:track:"

/**
 * The plays in a `recently-played/v3` body, newest first.
 *
 * Contexts that carry no track, or whose last play was an episode, are dropped: the history shows
 * tracks, and an episode URI cannot be hydrated through the track lookup. Never throws on a
 * malformed row — one odd entry must not blank the list.
 */
fun parseRecentPlayContexts(body: String): List<SpotifyContextPlay> {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
    val contexts = root["playContexts"] as? JsonArray ?: return emptyList()
    return contexts
        .mapNotNull { element ->
            val context = element as? JsonObject ?: return@mapNotNull null
            val trackUri =
                (context["lastPlayedTrackUri"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf { it.startsWith(TRACK_URI_PREFIX) && it.length > TRACK_URI_PREFIX.length }
                    ?: return@mapNotNull null
            val playedAt =
                (context["lastPlayedTime"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
                    ?: return@mapNotNull null
            SpotifyContextPlay(trackUri = trackUri, playedAtMillis = playedAt)
        }.sortedByDescending { it.playedAtMillis }
}

/**
 * [SpotifyPlayHistory] rows for [this], resolving each track URI through [tracks].
 *
 * A play whose track the lookup could not resolve (removed from the catalogue, region-locked) is
 * left out rather than shown as a blank row. The stamp is ISO-8601 UTC, the same shape the REST
 * endpoint returns, so the cache, the merge and the screens treat both sources alike.
 */
fun List<SpotifyContextPlay>.toPlayHistory(tracks: Map<String, SpotifyTrack>): List<SpotifyPlayHistory> =
    mapNotNull { play ->
        val track = tracks[play.trackUri] ?: return@mapNotNull null
        SpotifyPlayHistory(track = track, playedAt = Instant.ofEpochMilli(play.playedAtMillis).toString())
    }
