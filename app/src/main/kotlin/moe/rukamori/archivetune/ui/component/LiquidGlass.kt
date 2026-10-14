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

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import moe.rukamori.archivetune.LocalBlurPolicy
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.ui.component.sukisu.SukisuInteractiveHighlight
import moe.rukamori.archivetune.ui.component.sukisu.rememberSukisuSpecularHighlight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Alias so call sites can refer to a stable type name regardless of the backdrop impl. */
typealias PlatformBackdrop = LayerBackdrop

/** Legacy tuning read by the player's ordinary frosted container, not refractive material. */
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

/** Geometry and interaction remain with the caller; only optical treatment is shared. */
enum class LiquidGlassRole {
    Surface, NavigationBar, SelectionLens, Card, ActionMenu,
}

internal fun liquidGlassAllowed(
    masterEnabled: Boolean,
    platformBlurAllowed: Boolean,
    sourceAvailable: Boolean,
    visible: Boolean,
): Boolean = masterEnabled && platformBlurAllowed && sourceAvailable && visible

internal fun liquidGlassRefractionHeight(requestedPx: Float, minDimensionPx: Float): Float =
    minOf(requestedPx.coerceAtLeast(0f), minDimensionPx.coerceAtLeast(0f) / 2f)

/** Owners still decide source visibility and local style/stock-colour eligibility. */
@Composable
fun liquidGlassEligible(source: Backdrop?, visible: Boolean = true): Boolean =
    liquidGlassAllowed(
        masterEnabled = rememberLiquidGlassEnabled(),
        platformBlurAllowed = LocalBlurPolicy.current.renderEffect,
        sourceAvailable = source != null,
        visible = visible,
    )

/** The single optical adapter, including combined navigation backdrops. Never records content. */
@Composable
fun Modifier.liquidGlassMaterial(
    backdrop: Backdrop?,
    shape: Shape = CircleShape,
    role: LiquidGlassRole = LiquidGlassRole.Surface,
    interactive: Boolean = false,
    baseColor: Color = Color.Unspecified,
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    progress: () -> Float = { 1f },
    highlight: (() -> Highlight)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier {
    if (!liquidGlassEligible(backdrop)) return background(fallbackColor, shape)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val specular = rememberSukisuSpecularHighlight(
        if (role == LiquidGlassRole.SelectionLens) 90f else -45f,
    )
    // Static directional light is deliberate: list items must not own tilt listeners.
    val motion = !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f
    val scope = rememberCoroutineScope()
    val spotlight = if (interactive && motion) remember(scope) { SukisuInteractiveHighlight(scope) } else null
    DisposableEffect(spotlight) { onDispose { spotlight?.cancel() } }
    val tint = when (role) {
        LiquidGlassRole.ActionMenu -> Color.Black.copy(alpha = 0.30f)
        LiquidGlassRole.Card -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
        LiquidGlassRole.NavigationBar -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
        LiquidGlassRole.SelectionLens -> (if (dark) Color.White else Color.Black).copy(alpha = 0.1f)
        LiquidGlassRole.Surface -> (if (dark) Color.Black else Color.White).copy(alpha = 0.28f)
    }
    // Remember only the optical node, never the caller's layout/semantics modifier chain.
    val material = remember(backdrop, shape, role, baseColor, tint, dark, motion, specular, progress, highlight, layerBlock, onDrawSurface) {
        Modifier.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                val p = if (motion) progress().coerceIn(0f, 1f) else 0f
                if (role != LiquidGlassRole.SelectionLens) {
                    padding = maxOf(padding, 40.dp.toPx())
                    vibrancy()
                    blur(if (role == LiquidGlassRole.ActionMenu) 20.dp.toPx() else 4.dp.toPx())
                }
                val height = when (role) {
                    LiquidGlassRole.SelectionLens -> 10.dp.toPx() * p
                    LiquidGlassRole.Card -> 2.dp.toPx()
                    LiquidGlassRole.ActionMenu -> 16.dp.toPx()
                    else -> 24.dp.toPx()
                }
                lens(
                    refractionHeight = liquidGlassRefractionHeight(height, size.minDimension),
                    refractionAmount = when (role) {
                        LiquidGlassRole.SelectionLens -> 14.dp.toPx() * p
                        LiquidGlassRole.Card -> 2.dp.toPx()
                        LiquidGlassRole.ActionMenu -> 40.dp.toPx()
                        else -> size.minDimension / 4f
                    },
                    depthEffect = role == LiquidGlassRole.SelectionLens,
                    chromaticAberration = role == LiquidGlassRole.SelectionLens,
                )
            },
            highlight = {
                if (motion) highlight?.invoke() ?: specular.value.copy(alpha = 0.75f)
                else specular.value.copy(alpha = 0.75f)
            },
            shadow = {
                if (role == LiquidGlassRole.NavigationBar) {
                    Shadow(radius = 10.dp, color = Color.Black, alpha = if (dark) 0.2f else 0.1f)
                } else null
            },
            layerBlock = { if (motion) layerBlock?.invoke(this) },
            onDrawBehind = if (baseColor != Color.Unspecified) { { drawRect(baseColor) } } else null,
            onDrawSurface = { if (onDrawSurface != null) onDrawSurface.invoke(this) else drawRect(tint) },
        )
    }
    return then(material).then(spotlight?.let { it.modifier.then(it.gestureModifier) } ?: Modifier)
}

@Composable
fun Modifier.liquidGlass(
    backdrop: PlatformBackdrop,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    baseColor: Color = Color.Unspecified,
): Modifier = liquidGlassMaterial(
    backdrop = backdrop,
    shape = shape,
    interactive = interactive,
    baseColor = baseColor,
)

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
    tint: Color = MaterialTheme.colorScheme.onSurface,
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
    tint: Color = MaterialTheme.colorScheme.onSurface,
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
