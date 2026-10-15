/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.audiosource

import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONObject

/**
 * Per-song Amazon "Play from" pick, stored as one JSON object keyed by song id.
 *
 * Amazon instances only resolve by metadata (no track-id route), so the row a user tapped is kept
 * as its title/artist/album/duration rather than an id. JSON instead of the `id=value;` CSV used for
 * the Qobuz ids because titles routinely contain `;` and `=`.
 */
val SongSourceAmazonPickKey = stringPreferencesKey("songSourceAmazonPick")

data class AmazonTrackPick(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
)

object SongSourceAmazonPick {
    fun parse(raw: String?): Map<String, AmazonTrackPick> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, AmazonTrackPick>()
        root.keys().forEach { songId ->
            val entry = root.optJSONObject(songId) ?: return@forEach
            val title = entry.optString("title").trim()
            val artist = entry.optString("artist").trim()
            if (songId.isBlank() || title.isEmpty() || artist.isEmpty()) return@forEach
            out[songId] =
                AmazonTrackPick(
                    title = title,
                    artist = artist,
                    album = entry.optString("album").trim().ifEmpty { null },
                    durationMs = entry.optLong("durationMs", 0L).takeIf { it > 0L },
                )
        }
        return out
    }

    fun serialize(map: Map<String, AmazonTrackPick>): String {
        if (map.isEmpty()) return ""
        val root = JSONObject()
        map.forEach { (songId, pick) ->
            root.put(
                songId,
                JSONObject().apply {
                    put("title", pick.title)
                    put("artist", pick.artist)
                    pick.album?.let { put("album", it) }
                    pick.durationMs?.let { put("durationMs", it) }
                },
            )
        }
        return root.toString()
    }

    fun get(
        raw: String?,
        songId: String,
    ): AmazonTrackPick? = parse(raw)[songId]

    /** Returns the updated raw string with [songId] set to [pick], or cleared when [pick] is null. */
    fun withOverride(
        raw: String?,
        songId: String,
        pick: AmazonTrackPick?,
    ): String {
        val map = LinkedHashMap(parse(raw))
        if (pick == null) map.remove(songId) else map[songId] = pick
        return serialize(map)
    }
}
