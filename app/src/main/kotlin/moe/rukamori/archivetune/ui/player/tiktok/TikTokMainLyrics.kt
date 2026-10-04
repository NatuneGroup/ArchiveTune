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

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.TikTokMainLyricsEnabledKey
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * Karaoke caption strip for the TikTok player's main screen. Renders ONLY the
 * active line — bigger and bolder than the old scrolling strip — together with
 * its per-word phonetic (romanisation) and its translation, animated by the
 * enhanced lyrics library's word-timed sweep. Left-aligned with the song info
 * block (and its "recently played" queue pill); previous and upcoming lines are
 * never composed.
 *
 * The caller (TikTokSongPage) owns the slot: it reserves [TikTokMainLyricsHeight]
 * whenever the feature is enabled — for every page and whether or not the
 * current song has synced lyrics — so the artwork box above never changes size
 * when lyrics load, appear or change between songs. This composable simply
 * fills that slot (or composes nothing when there is nothing to show).
 */
@Composable
internal fun TikTokMainLyrics(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    val enabled by rememberPreference(TikTokMainLyricsEnabledKey, false)
    if (!enabled) return

    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    val hasSyncedLyrics =
        remember(currentLyrics, mediaMetadata?.id) {
            val text =
                currentLyrics
                    ?.takeIf { it.id == mediaMetadata?.id }
                    ?.lyrics
                    ?.trim()
            text != null &&
                text.isNotBlank() &&
                (LyricsUtils.isTtml(text) || LyricsUtils.isLineSyncedLrc(text))
        }
    if (!hasSyncedLyrics) return

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart,
    ) {
        LyricsEnhanced(
            sliderPositionProvider = sliderPositionProvider,
            lyricsSyncOffset = lyricsSyncOffset,
            singleActiveLine = true,
            textColorOverride = Color.White,
            textSizeOverride = TikTokMainLyricsTextSizeSp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal val TikTokMainLyricsHeight = 168.dp
private const val TikTokMainLyricsTextSizeSp = 24f
