/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LyricsV2
import moe.rukamori.archivetune.ui.player.simpmusic.SimpMusicLyrics
import moe.rukamori.archivetune.utils.rememberEnumPreference

/**
 * The full-screen lyrics renderer the Lyrics settings select — [LyricsEnhanced] (word sweep,
 * tap-to-seek, auto-scroll) unless the user picked another mode. The shared lyrics page and
 * Bitchord's lyrics window both draw through this, so the mode-to-renderer mapping exists once.
 */
@Composable
fun SelectedLyrics(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
) {
    val lyricsMode by rememberEnumPreference(LyricsModeKey, LyricsMode.ENHANCED)
    when (lyricsMode) {
        LyricsMode.ENHANCED ->
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
            )

        LyricsMode.SPOTIFY ->
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
                spotifyStyle = true,
            )

        LyricsMode.V2 ->
            LyricsV2(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
            )

        LyricsMode.SIMPMUSIC ->
            SimpMusicLyrics(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColorOverride,
            )
    }
}
