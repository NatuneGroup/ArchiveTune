/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey

val NavigationBarGlassGlowKey = booleanPreferencesKey("navigationBarGlassGlow")

val NavigationBarGlassGlowIntensityKey = floatPreferencesKey("navigationBarGlassGlowIntensity")

const val NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT = 1f

val LocalBottomUiCompactFraction = compositionLocalOf<State<Float>> {
    mutableStateOf(0f)
}

val CompactControlSize = 56.dp

val CompactControlGap = 12.dp

private val GlowEdgeStrokeWidth = 0.75.dp

fun Modifier.glassGlowOverlay(
    strength: Float,
    shape: Shape,
): Modifier =
    if (strength <= 0.01f) {
        this
    } else {
        // The outline, stroke and brushes depend only on size and strength, so they are built
        // once per size change instead of on every draw. Strength scales the colour stops rather
        // than the draw alpha: paint alpha clamps at 1, which would make every intensity above
        // 100% look the same.
        drawWithCache {
            fun glow(alpha: Float) = Color.White.copy(alpha = (alpha * strength).coerceIn(0f, 1f))
            val outline = shape.createOutline(size, layoutDirection, this)
            val corner =
                when (val r = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius) {
                    null -> CornerRadius(size.height / 2f)
                    else -> CornerRadius(r.x, r.y)
                }
            val stroke = GlowEdgeStrokeWidth.toPx()
            val edgeBrush = Brush.verticalGradient(listOf(glow(0.27f), glow(0.02f)))
            val sheenBrush =
                Brush.verticalGradient(
                    0f to glow(0.10f),
                    0.45f to Color.Transparent,
                    1f to glow(0.05f),
                )
            val baseBrush =
                Brush.verticalGradient(
                    0.72f to Color.Transparent,
                    1f to glow(0.14f * 0.7f),
                )
            val edgeStyle = Stroke(stroke)
            val edgeTopLeft = Offset(stroke / 2f, stroke / 2f)
            val edgeSize = Size(size.width - stroke, size.height - stroke)

            onDrawWithContent {
                drawContent()
                drawRoundRect(
                    brush = edgeBrush,
                    topLeft = edgeTopLeft,
                    size = edgeSize,
                    cornerRadius = corner,
                    style = edgeStyle,
                )
                drawRoundRect(brush = sheenBrush, cornerRadius = corner)
                drawRoundRect(brush = baseBrush, cornerRadius = corner)
            }
        }
    }

@Composable
fun CompactControlCircle(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    backdrop: PlatformBackdrop?,
    glowStrength: Float,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val useGlass = backdrop != null
    if (useGlass) {
        Box(
            modifier =
                modifier
                    .size(CompactControlSize)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = CircleShape,
                        interactive = false,
                        baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .glassGlowOverlay(glowStrength, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        // The Material 3 counterpart of the glass circle: an opaque tonal surface in the same tone
        // as the mini player capsule beside it, with no see-through fill or glass edge.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                modifier
                    .size(CompactControlSize)
                    // Ahead of the clip: a shadow inside the clipped bounds would never show.
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
