/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import moe.rukamori.archivetune.constants.FloatingBarJunctionCornerRadius
import moe.rukamori.archivetune.constants.FloatingBarOuterCornerRadius
import moe.rukamori.archivetune.constants.FloatingBarStandaloneCornerRadius
import moe.rukamori.archivetune.constants.FloatingNavigationBarBottomPadding
import moe.rukamori.archivetune.constants.FloatingNavigationBarHorizontalPadding
import moe.rukamori.archivetune.constants.FloatingNavigationBarMaxWidth
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_OPACITY_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_TRANSPARENCY_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_WIDTH_DEFAULT
import moe.rukamori.archivetune.constants.NavigationBarBottomPadding
import moe.rukamori.archivetune.constants.NavigationBarCornerRadiusKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarHorizontalPadding
import moe.rukamori.archivetune.constants.NavigationBarMaxWidth
import moe.rukamori.archivetune.constants.NavigationBarOpacityKey
import moe.rukamori.archivetune.constants.NavigationBarStyle
import moe.rukamori.archivetune.constants.NavigationBarStyleKey
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarTransparencyKey
import moe.rukamori.archivetune.constants.NavigationBarWidthKey
import moe.rukamori.archivetune.constants.CompactBottomPillsKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

// Frosted backdrop blur radius, in px (RenderEffect works in raw pixels). Shared by the navigation
// bar and the mini player's frosted background so the two read as one material.
const val PillFrostBlurRadiusPx = 60f

// How much of the blurred backdrop shows through the opaque base. The base is always fully opaque
// and the blurred content is composited on top at this alpha, so page brightness can only ever
// modulate the surface by this fraction — and an empty backdrop layer simply yields a solid pill.
const val PillFrostOverlayAlpha = 0.30f

// The tinted variant sits on a dark translucent base, so a higher overlay alpha lets the blurred
// app content read through it as tinted glass.
const val PillTintFrostOverlayAlpha = 0.45f

private const val ApplePillContainerAlpha = 0.92f
private const val FrostedTintContainerAlpha = 0.85f
private val PillBorderWidth = 1.dp

/**
 * True while the shell has slid the bottom navigation bar away on scroll. The content insets stay
 * static so lists do not jump, which leaves bottom-anchored chrome hovering over the gap unless it
 * follows the bar down.
 */
val LocalNavigationBarHiddenByScroll = compositionLocalOf { false }

enum class PillRole {
    NAVIGATION_BAR,
    MINI_PLAYER,
    STANDALONE,
}

enum class PillBackdrop {
    NONE,
    FROSTED,
    LIQUID_GLASS,
}

val NavigationBarStyle.pillHorizontalInset: Dp
    get() = if (this == NavigationBarStyle.FLOATING) FloatingNavigationBarHorizontalPadding else NavigationBarHorizontalPadding

val NavigationBarStyle.pillBottomInset: Dp
    get() = if (this == NavigationBarStyle.FLOATING) FloatingNavigationBarBottomPadding else NavigationBarBottomPadding

val NavigationBarStyle.pillMaxWidth: Dp
    get() = if (this == NavigationBarStyle.DEFAULT) NavigationBarMaxWidth else FloatingNavigationBarMaxWidth

/**
 * The geometry and material of the bottom chrome, resolved once from the navigation bar style the
 * user picked in Appearance. The navigation bar, the mini player and the bottom search pill all read
 * it, so they share a corner radius, insets, elevation, hairline and surface tone instead of each
 * hard-coding its own.
 *
 * [containerColor] is the navigation bar's own surface (frosted, tinted, pure-black, translucent);
 * [raisedContainerColor] is the tone of the surfaces stacked above it. The search pill sits inside
 * the content that the frosted and liquid-glass backdrops record, so it cannot sample them without
 * rendering recursively — it takes the raised tone and the shared shape and elevation only.
 */
@Immutable
class PillStyle(
    val navigationStyle: NavigationBarStyle,
    val backdrop: PillBackdrop,
    val tinted: Boolean,
    val cornerRadius: Dp,
    val widthFraction: Float,
    val containerColor: Color,
    val raisedContainerColor: Color,
    val contentColor: Color,
    val tintedContent: Color,
    val compact: Boolean,
    borderColor: Color?,
) {
    val horizontalInset: Dp get() = navigationStyle.pillHorizontalInset
    val bottomInset: Dp get() = navigationStyle.pillBottomInset
    val maxWidth: Dp get() = navigationStyle.pillMaxWidth * (if (compact) 0.9f else 1f)
    val isDocked: Boolean get() = navigationStyle == NavigationBarStyle.DEFAULT
    val tonalElevation: Dp get() = NavigationBarDefaults.Elevation
    val shadowElevation: Dp
        get() = if (navigationStyle == NavigationBarStyle.FLOATING) 8.dp else NavigationBarDefaults.Elevation
    val border: BorderStroke? = borderColor?.let { BorderStroke(PillBorderWidth, it) }

    /**
     * Docked pills pinch toward the junction with their neighbour as [proximity] rises; floating and
     * Apple Music pills never dock, so they keep one radius on every corner.
     */
    fun shape(
        role: PillRole,
        proximity: Float = 0f,
    ): Shape {
        if (backdrop == PillBackdrop.LIQUID_GLASS && role != PillRole.MINI_PLAYER) {
            return RoundedCornerShape(percent = 50)
        }
        if (!isDocked) return RoundedCornerShape(cornerRadius)
        val docking = proximity.coerceIn(0f, 1f)
        val standalone = if (compact) 24.dp else FloatingBarStandaloneCornerRadius
        val outerRadius = if (compact) 20.dp else FloatingBarOuterCornerRadius
        val junctionRadius = if (compact) 10.dp else FloatingBarJunctionCornerRadius
        return when (role) {
            PillRole.NAVIGATION_BAR -> {
                val top = lerp(standalone, junctionRadius, docking)
                val bottom = lerp(standalone, outerRadius, docking)
                RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            }

            PillRole.MINI_PLAYER -> {
                val top = lerp(standalone, outerRadius, docking)
                val bottom = lerp(standalone, junctionRadius, docking)
                RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            }

            PillRole.STANDALONE -> RoundedCornerShape(standalone)
        }
    }
}

@Composable
fun rememberNavigationPillStyle(): NavigationBarStyle {
    val (stored) = rememberEnumPreference(NavigationBarStyleKey, defaultValue = NavigationBarStyle.DEFAULT)
    return if (rememberAppleMusicExperience()) NavigationBarStyle.APPLE_MUSIC else stored
}

/** Resolves the style from the stored preferences, for surfaces that are not handed it by the shell. */
@Composable
fun rememberPillStyle(): PillStyle {
    val (frostedBlur) = rememberPreference(NavigationBarFrostedBlurKey, defaultValue = false)
    val (tintFrostedBlur) = rememberPreference(NavigationBarTintFrostedBlurKey, defaultValue = false)
    val (liquidGlassEnabled) = rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val (liquidGlassNavBar) = rememberPreference(LiquidGlassNavBarEnabledKey, defaultValue = false)
    return rememberPillStyle(
        style = rememberNavigationPillStyle(),
        pureBlack = MaterialTheme.colorScheme.background == Color.Black,
        frostedBlur = frostedBlur,
        tintFrostedBlur = tintFrostedBlur,
        liquidGlass = liquidGlassEnabled && liquidGlassNavBar,
        frostedBackdropAvailable = LocalNavigationBarBackdrop.current != null,
        liquidGlassBackdropAvailable = LocalLiquidGlassBackdrop.current != null,
    )
}

@Composable
fun rememberPillStyle(
    style: NavigationBarStyle,
    pureBlack: Boolean,
    frostedBlur: Boolean,
    tintFrostedBlur: Boolean,
    liquidGlass: Boolean,
    frostedBackdropAvailable: Boolean,
    liquidGlassBackdropAvailable: Boolean,
): PillStyle {
    val colors = MaterialTheme.colorScheme
    val (compactBottomPills) = rememberPreference(CompactBottomPillsKey, defaultValue = false)
    val useCompactPills = compactBottomPills && style != NavigationBarStyle.APPLE_MUSIC
    val (widthPreference) = rememberPreference(NavigationBarWidthKey, defaultValue = NAVIGATION_BAR_WIDTH_DEFAULT)
    val (opacity) = rememberPreference(NavigationBarOpacityKey, defaultValue = NAVIGATION_BAR_OPACITY_DEFAULT)
    val (transparency) =
        rememberPreference(NavigationBarTransparencyKey, defaultValue = NAVIGATION_BAR_TRANSPARENCY_DEFAULT)
    val (cornerRadiusPreference) =
        rememberPreference(NavigationBarCornerRadiusKey, defaultValue = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT)
    val surfaceContainer = colors.surfaceContainer
    val surfaceContainerHigh = colors.surfaceContainerHigh
    val outlineVariant = colors.outlineVariant
    val onSurface = colors.onSurface
    val primary = colors.primary
    val schemeIsDark = pureBlack || colors.background.luminance() < 0.5f

    return remember(
        style,
        pureBlack,
        frostedBlur,
        tintFrostedBlur,
        liquidGlass,
        frostedBackdropAvailable,
        liquidGlassBackdropAvailable,
        widthPreference,
        opacity,
        transparency,
        cornerRadiusPreference,
        useCompactPills,
        surfaceContainer,
        surfaceContainerHigh,
        outlineVariant,
        onSurface,
        primary,
        schemeIsDark,
    ) {
        val isAppleMusic = style == NavigationBarStyle.APPLE_MUSIC
        val supportsBackdrop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val canLiquidGlass = liquidGlass && liquidGlassBackdropAvailable && supportsBackdrop
        val canBlurBackdrop =
            (frostedBlur || tintFrostedBlur) && frostedBackdropAvailable && supportsBackdrop
        val tintedBase = if (schemeIsDark) lerp(Color.Black, primary, 0.30f) else lerp(Color.White, primary, 0.26f)
        val tintedContent = if (schemeIsDark) Color.White else lerp(primary, Color.Black, 0.55f)
        val containerColor =
            when {
                // Transparent so the liquid-glass shader tint shows through.
                canLiquidGlass -> Color.Transparent
                canBlurBackdrop ->
                    when {
                        tintFrostedBlur -> tintedBase.copy(alpha = FrostedTintContainerAlpha)
                        // Pure black still needs some surface under the blur, or the frost is invisible.
                        pureBlack -> Color.Black.copy(alpha = 0.45f)
                        else -> surfaceContainer
                    }
                tintFrostedBlur -> tintedBase
                pureBlack -> Color.Black
                isAppleMusic -> surfaceContainer.copy(alpha = ApplePillContainerAlpha)
                // Transparency applies only without a frost overlay, which already provides the
                // see-through effect; adding it there would double-count.
                else -> surfaceContainer.copy(alpha = (opacity * (1f - transparency)).coerceIn(0.05f, 1f))
            }
        PillStyle(
            navigationStyle = style,
            backdrop =
                when {
                    canLiquidGlass -> PillBackdrop.LIQUID_GLASS
                    canBlurBackdrop -> PillBackdrop.FROSTED
                    else -> PillBackdrop.NONE
                },
            tinted = tintFrostedBlur,
            cornerRadius =
                if (useCompactPills) {
                    20.dp
                } else if (style == NavigationBarStyle.DEFAULT) {
                    FloatingBarStandaloneCornerRadius
                } else {
                    cornerRadiusPreference.dp
                },
            widthFraction =
                when (style) {
                    // Apple Music's bar is inset only slightly: its five labels have to fit, so the
                    // floating default of 0.8 (a fraction for a four-item bar) would squash them. An
                    // untouched slider keeps the wide look; a width the user picked is honoured.
                    NavigationBarStyle.APPLE_MUSIC ->
                        if (widthPreference == NAVIGATION_BAR_WIDTH_DEFAULT) 0.94f else widthPreference.coerceIn(0.6f, 1f)
                    NavigationBarStyle.FLOATING -> widthPreference.coerceIn(0.5f, 1f) * (if (useCompactPills) 0.9f else 1f)
                    NavigationBarStyle.DEFAULT -> if (useCompactPills) 0.88f else 1f
                },
            containerColor = containerColor,
            raisedContainerColor =
                if (isAppleMusic) surfaceContainerHigh.copy(alpha = ApplePillContainerAlpha) else surfaceContainerHigh,
            contentColor =
                when {
                    tintFrostedBlur -> tintedContent
                    pureBlack -> Color.White
                    else -> onSurface
                },
            tintedContent = tintedContent,
            compact = useCompactPills,
            borderColor = if (isAppleMusic) outlineVariant else null,
        )
    }
}
