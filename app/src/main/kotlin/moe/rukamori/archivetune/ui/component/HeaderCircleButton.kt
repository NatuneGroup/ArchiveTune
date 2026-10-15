/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val HeaderCircleButtonSize = 44.dp

private const val StockContainerAlpha = 0.92f
private const val StockBorderAlpha = 0.45f

/**
 * One round control in a page header, drawn on its own so the header reads as separate pieces (an
 * avatar, a title, a settings button) rather than one strip.
 *
 * With a [backdrop] it is Liquid Glass, the same material as the compact bottom controls. Without
 * one it is a solid theme-coloured circle with a hairline edge, so it still reads on a pale page.
 */
@Composable
fun HeaderCircleButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    backdrop: PlatformBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val surface =
        if (liquidGlassEligible(backdrop)) {
            Modifier.liquidGlass(
                backdrop = backdrop!!,
                shape = CircleShape,
                interactive = true,
                baseColor = base,
            )
        } else {
            Modifier
                .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                .background(base.copy(alpha = StockContainerAlpha), CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = StockBorderAlpha),
                    shape = CircleShape,
                )
        }
    Box(
        modifier =
            modifier
                .size(HeaderCircleButtonSize)
                .then(surface)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
