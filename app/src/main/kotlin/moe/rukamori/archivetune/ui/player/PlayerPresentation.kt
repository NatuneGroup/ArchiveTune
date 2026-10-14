/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One dispatch context for both orientations, scoped to player presentation, never audio policy. */
@Immutable
data class PlayerPresentation(
    val sharedBackground: Boolean = false,
    val hideSleeve: Boolean = false,
    val sleeveRadius: Float? = null,
    val swipeArtwork: Boolean = true,
    val showCodec: Boolean = true,
    val showVolume: Boolean = true,
) {
    fun radius(native: Dp): Dp = sleeveRadius?.takeIf { it.isFinite() }?.coerceIn(0f, 64f)?.dp ?: native
}

val LocalPlayerPresentation = staticCompositionLocalOf { PlayerPresentation() }

/** Keep the slot and morph bounds mounted; hide decorative pixels, not full-bleed playing media. */
fun Modifier.ordinarySleeve(hidden: Boolean): Modifier =
    if (hidden) graphicsLayer { alpha = 0f } else this
