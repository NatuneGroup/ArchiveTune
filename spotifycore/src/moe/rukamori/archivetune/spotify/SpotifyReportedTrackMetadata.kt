/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import moe.rukamori.archivetune.spotify.models.SpotifyExternalIds

fun spotifyReportedExplicit(track: JsonObject): Boolean {
    val rating = track["contentRating"] as? JsonObject
    return (track["explicit"] as? JsonPrimitive)?.booleanOrNull == true ||
        (rating?.get("label") as? JsonPrimitive)?.content.equals("EXPLICIT", ignoreCase = true)
}

fun spotifyReportedExternalIds(track: JsonObject): SpotifyExternalIds? {
    val values = track["externalIds"] as? JsonObject ?: track["external_ids"] as? JsonObject ?: return null
    fun value(name: String): String? =
        (values[name] as? JsonPrimitive)?.takeIf { it.isString && it.content != "null" }?.content?.takeIf(String::isNotBlank)
    val ids = SpotifyExternalIds(isrc = value("isrc"), ean = value("ean"), upc = value("upc"))
    return ids.takeIf { it.isrc != null || it.ean != null || it.upc != null }
}
