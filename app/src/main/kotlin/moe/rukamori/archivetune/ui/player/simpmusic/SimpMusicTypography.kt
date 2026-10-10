/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * The SimpMusic player style's type.
 *
 * SimpMusic (github.com/maxrave-dev/SimpMusic, GPL-3.0) renders its whole UI in
 * Poppins Medium (composeResources/font/poppins_medium.ttf, shipped here as
 * res/font/simpmusic_poppins.ttf) with weights expressed per style rather than
 * per font file. Its ui/theme/Typo.kt builds a Typography on that family with
 * fixed sizes/weights; this is that mapping, one-for-one, for the styles the
 * player screen reads. Only the simpmusic package uses it; the rest of the app
 * keeps its own typography.
 *
 * SimpMusic's typo() also bakes text COLORS into its styles (pure white titles,
 * #A8A8A8 body) because its player is force-dark. Here only the metrics travel
 * through MaterialTheme.typography — color stays explicit at every call site,
 * which the existing port already does.
 */

package moe.rukamori.archivetune.ui.player.simpmusic

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import moe.rukamori.archivetune.R

val SimpMusicPoppins: FontFamily = FontFamily(Font(R.font.simpmusic_poppins))

val SimpMusicTypography: Typography =
    Typography(

        titleSmall =
            TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SimpMusicPoppins,
            ),
        titleMedium =
            TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SimpMusicPoppins,
            ),
        titleLarge =
            TextStyle(
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SimpMusicPoppins,
            ),
        bodySmall =
            TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = SimpMusicPoppins,
            ),
        bodyMedium =
            TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = SimpMusicPoppins,
            ),
        bodyLarge =
            TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = SimpMusicPoppins,
            ),
        displayLarge =
            TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = SimpMusicPoppins,
            ),
        headlineMedium =
            TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SimpMusicPoppins,
            ),
        headlineLarge =
            TextStyle(
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SimpMusicPoppins,
            ),
        labelMedium =
            TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SimpMusicPoppins,
            ),
        labelSmall =
            TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SimpMusicPoppins,
            ),
    )

/**
 * The line height to measure against, for a style that may not declare one.
 *
 * [TextStyle.lineHeight] is [androidx.compose.ui.unit.TextUnit.Unspecified] unless the style sets
 * it, and every style in [SimpMusicTypography] sets only a font size, weight and family. An
 * unspecified unit cannot be converted: Compose's `Density.toPx(TextUnit)` checks that the unit is
 * Sp and throws `IllegalStateException: Only Sp can convert to Px` from the frame that reads it,
 * which is how the player crashed on any track with synced lyrics. Falling back to the style's own
 * font size keeps the measured band derived from something real.
 */
internal fun TextStyle.resolvedLineHeight(): androidx.compose.ui.unit.TextUnit =
    lineHeight.takeOrElse { fontSize }
