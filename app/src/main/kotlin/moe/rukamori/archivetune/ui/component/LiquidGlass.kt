/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Liquid glass / backdrop blur effect, ported from SimpMusic
 * (https://github.com/maxrave-dev/SimpMusic) and simplified for the
 * Android-only ArchiveTune build. The original KMP expect/actual
 * pattern is collapsed into a single file because ArchiveTune does
 * not have a JVM/iOS target.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as Material3IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Alias so call sites can refer to a stable type name regardless of the backdrop impl. */
typealias PlatformBackdrop = LayerBackdrop

/**
 * The liquid-glass knobs a frosted container reads.
 *
 * This fork has no user-facing Liquid Glass tuning surface — [liquidGlass] hard-codes its blur and
 * lens — so this carries only what the Flamingo player's frosted container actually reads. STOCK
 * reproduces exactly what the modifier does today: no backdrop vibrancy (saturation 1.0) and the
 * modifier's own blur factor. Keeping the numbers behind one object means the ported player reads
 * the same defaults as everything else instead of inventing a second, diverging set, and leaves a
 * single place to grow a real tuning surface later.
 */
@Stable
data class LiquidGlassTuning(
    val blurFactor: Float = 1f,
    val backdropVibrancy: Boolean = false,
) {
    /** Colour controls saturation for the sampled backdrop; vibrancy pushes it past 1. */
    val saturation: Float = if (backdropVibrancy) 1.7f else 1.0f

    companion object {
        val STOCK = LiquidGlassTuning()
    }
}

/** Defaults to [LiquidGlassTuning.STOCK], so a host that provides no tuning still renders. */
val LocalLiquidGlassTuning = compositionLocalOf { LiquidGlassTuning.STOCK }

/**
 * Records the content of the composable it is called on into a [LayerBackdrop]
 * that downstream [liquidGlass] modifiers can sample from. Mirrors SimpMusic's
 * `rememberBackdrop` + `Modifier.layerBackdrop` pair.
 */
@Composable
fun rememberBackdrop(color: Color): PlatformBackdrop =
    rememberLayerBackdrop {
        drawRect(color)
        drawContent()
    }

fun Modifier.layerBackdrop(backdrop: PlatformBackdrop): Modifier = this.layerBackdrop(backdrop)

/**
 * App-content [LayerBackdrop] used by the Liquid Glass mini player and the Liquid Glass navigation
 * bar.
 */
val LocalLiquidGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/** Applies the SimpMusic liquid-glass effect to any element. */
@Composable
fun Modifier.liquidGlass(
    backdrop: PlatformBackdrop,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    baseColor: Color = Color.Unspecified,
): Modifier {
    val isDark = isSystemInDarkTheme()
    // Memoize the entire drawBackdrop modifier chain so it isn't rebuilt on every
    // recomposition. The chain depends only on (backdrop, shape, interactive, baseColor,
    // isDark) — all stable across scroll-driven recompositions of the host screen. Without
    // this memo, every recomposition rebuilt the kyant effect stack and re-installed the
    // RuntimeShader on the GraphicsLayer, which was the dominant cause of "lag when
    // switching pages" (the new page's first frames all paid that GPU setup cost while the
    // user was already trying to scroll).
    return remember(backdrop, shape, interactive, baseColor, isDark) {
        this.drawBackdrop(
            backdrop = backdrop,
            effects = {
                val l = 0f
                vibrancy()
                blur(
                    if (l > 0f) {
                        lerp(8f.dp.toPx(), 16f.dp.toPx(), l)
                    } else {
                        lerp(8f.dp.toPx(), 2f.dp.toPx(), -l)
                    },
                )
                lens(24f.dp.toPx(), size.minDimension / 4f, false)
            },
            onDrawBackdrop = { drawBackdrop ->
                drawBackdrop()
            },
            shape = { shape },
            onDrawBehind =
                if (baseColor != Color.Unspecified) {
                    { drawRect(baseColor) }
                } else {
                    null
                },
            onDrawSurface = {
                val luminanceAnimation = 0.5f
                val darken = lerp(
                    0.12f,
                    0.5f,
                    ((luminanceAnimation - 0.3f) / 0.5f).coerceIn(0f, 1f),
                )
                drawRect((if (isDark) Color.Black else Color.White).copy(alpha = darken))
            },
        )
    }
}

/**
 * A liquid-glass surface wrapping arbitrary [content] (e.g. a pill of icon buttons). Thin
 * convenience over [liquidGlass]; pure common code.
 */
@Composable
fun LiquidGlassContainer(
    backdrop: PlatformBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    interactive: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlass(backdrop, shape, interactive),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * A rounded-rect liquid-glass pill that hosts a row of icon buttons — the SimpMusic "heart + more"
 * cluster that floats at the top-end of the album / artist / playlist header. The pill is 48dp tall
 * with a 24dp corner radius and uses the same `Modifier.liquidGlass` effect as the circular back
 * button.
 */
@Composable
fun LiquidGlassActionPill(
    backdrop: PlatformBackdrop,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .height(48.dp)
                .liquidGlass(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(24.dp),
                    interactive = interactive,
                ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Convenience wrapper around [LiquidGlassContainer] for the common single-icon
 * case (e.g. the circular back button shared by the detail screens).
 *
 * Accepts a [Painter] (e.g. from `painterResource(R.drawable.arrow_back)`)
 * because ArchiveTune's existing iconography uses painter resources, not
 * ImageVectors. This keeps call sites unchanged.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: PlatformBackdrop,
    painter: Painter,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = Color.White,
    contentDescription: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
    ) {
        Material3IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tint,
            )
        }
    }
}

/**
 * ImageVector overload — kept for parity with SimpMusic's API.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: PlatformBackdrop,
    imageVector: ImageVector,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = Color.White,
    contentDescription: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
    ) {
        Material3IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = tint,
            )
        }
    }
}
