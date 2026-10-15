/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.MediaInfo

/** A YouTube video id. Anything else cannot resolve here, whatever else it is. */
private val YouTubeId = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * The editorial side of a track — description, uploader, subscriber and view counts.
 *
 * Two copies of this fetch existed: one inside [ShowMediaInfo], so the only way to read a song's
 * description was to open that bottom sheet, and one inside the SimpMusic player for its
 * below-the-fold cards. They had already drifted — SimpMusic checked the id shape first and the
 * sheet did not, so on a Tidal, Qobuz, Spotify or local track the sheet spent a network round trip
 * to be told the id means nothing to YouTube. The gate is kept and both surfaces now share it.
 *
 * Null while loading, on failure, and for an id this cannot serve. No caller has a useful
 * distinction to draw between "not yet" and "never" — a card with nothing to say hides either way.
 */
@Composable
fun rememberMediaInfo(videoId: String): MediaInfo? {
    var info by remember(videoId) { mutableStateOf<MediaInfo?>(null) }
    LaunchedEffect(videoId) {
        info =
            if (!YouTubeId.matches(videoId)) {
                null
            } else {
                runCatching { YouTube.getMediaInfo(videoId).getOrNull() }.getOrNull()
            }
    }
    return info
}
