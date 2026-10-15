/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * ArchiveTune (2026) | Modified work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * YumaPlayer (2026) | Original work by MuwMix
 */


package moe.rukamori.archivetune.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.ui.screens.settings.SettingsAnimations
import moe.rukamori.archivetune.ui.screens.settings.SettingsDimensions

enum class YumaSegmentPosition { Single, First, Middle, Last }

fun yumaSegmentPosition(
    index: Int,
    count: Int,
): YumaSegmentPosition =
    when {
        count <= 1 -> YumaSegmentPosition.Single
        index == 0 -> YumaSegmentPosition.First
        index == count - 1 -> YumaSegmentPosition.Last
        else -> YumaSegmentPosition.Middle
    }

/**
 * Top/bottom stroke opacity per position, so a stacked group reads as one pane of glass lit from
 * above rather than as separate cards: only the group's first row gets the bright top edge.
 */
private fun yumaSegmentAlphas(position: YumaSegmentPosition): Pair<Float, Float> =
    when (position) {
        YumaSegmentPosition.Single -> 0.20f to 0.04f
        YumaSegmentPosition.First -> 0.20f to 0.08f
        YumaSegmentPosition.Middle -> 0.08f to 0.08f
        YumaSegmentPosition.Last -> 0.08f to 0.04f
    }

fun Modifier.glassBorder(
    shape: Shape,
    strokeWidth: Dp = SettingsDimensions.GlassBorderThickness,
    topAlpha: Float = 0.20f,
    bottomAlpha: Float = 0.04f,
    baseColor: Color = Color.White,
): Modifier =
    if (baseColor.isSpecified && baseColor != Color.Transparent) {
        border(
            width = strokeWidth,
            brush =
                Brush.verticalGradient(
                    0.0f to baseColor.copy(alpha = topAlpha),
                    1.0f to baseColor.copy(alpha = bottomAlpha),
                ),
            shape = shape,
        )
    } else {
        this
    }

@Composable
fun Modifier.yumaGlassCard(
    shape: Shape = RoundedCornerShape(SettingsDimensions.GlassCornerRadius),
    backgroundColor: Color = LocalYumaColors.current.glassBackground,
    borderColor: Color = LocalYumaColors.current.glassBorder,
    strokeWidth: Dp = SettingsDimensions.GlassBorderThickness,
    position: YumaSegmentPosition = YumaSegmentPosition.Single,
    topAlpha: Float? = null,
    bottomAlpha: Float? = null,
): Modifier {
    val (defaultTop, defaultBottom) = yumaSegmentAlphas(position)
    return clip(shape)
        .background(backgroundColor, shape)
        .glassBorder(
            shape = shape,
            strokeWidth = strokeWidth,
            topAlpha = topAlpha ?: defaultTop,
            bottomAlpha = bottomAlpha ?: defaultBottom,
            baseColor = borderColor,
        )
}

@Composable
fun Modifier.yumaClickable(
    enabled: Boolean = true,
    pressedScale: Float = SettingsAnimations.PressScale,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }

    if (LocalAnimationsDisabled.current || !enabled) {
        return clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }

    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = SettingsAnimations.pressSpring(),
        label = "yumaClickScale",
    )

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}
