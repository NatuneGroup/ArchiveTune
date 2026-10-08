/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.sponsorblock

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The segment kinds SponsorBlock publishes.
 *
 * [MUSIC_OFFTOPIC] is the one that matters most here: on a music video it marks the non-music
 * stretches, which is the whole reason a music player would ask at all. The rest are carried so a
 * user who wants them can have them, and default to off.
 */
enum class SponsorBlockCategory(
    val apiName: String,
) {
    SPONSOR("sponsor"),
    SELFPROMO("selfpromo"),
    INTERACTION("interaction"),
    INTRO("intro"),
    OUTRO("outro"),
    PREVIEW("preview"),
    MUSIC_OFFTOPIC("music_offtopic"),
    FILLER("filler"),
    ;

    companion object {
        fun fromApiName(value: String): SponsorBlockCategory? = entries.find { it.apiName == value }

        /**
         * What a fresh install skips: the non-music stretches of a music video, and nothing else.
         * Every other category is about talking over video content this app does not show.
         */
        val Defaults = setOf(MUSIC_OFFTOPIC)
    }
}

/** One stretch to skip, in milliseconds. */
data class SponsorBlockSegment(
    val category: SponsorBlockCategory,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
internal data class SponsorBlockApiSegment(
    @SerialName("videoID") val videoId: String = "",
    @SerialName("segment") val segment: List<Double> = emptyList(),
    @SerialName("category") val category: String = "",
    @SerialName("actionType") val actionType: String = "skip",
)

/**
 * Accepts only a plain https origin, so a mistyped or hostile value cannot redirect lookups
 * somewhere with a path, a query, or embedded credentials. Returns null when the value is unusable
 * and the caller should fall back to [SPONSORBLOCK_DEFAULT_API_URL].
 */
fun normalizeSponsorBlockApiUrl(raw: String?): String? {
    val trimmed = raw?.trim()?.removeSuffix("/").orEmpty()
    if (trimmed.isEmpty()) return null
    val url = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    if (!url.scheme.equals("https", ignoreCase = true)) return null
    if (url.host.isNullOrBlank()) return null
    if (!url.userInfo.isNullOrBlank()) return null
    if (!url.query.isNullOrBlank() || !url.fragment.isNullOrBlank()) return null
    if (!url.path.isNullOrBlank() && url.path != "/") return null
    return "https://${url.host}" + if (url.port > 0) ":${url.port}" else ""
}

const val SPONSORBLOCK_DEFAULT_API_URL = "https://sponsor.ajay.app"
