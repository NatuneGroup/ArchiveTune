/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Glass values adapted from YumaPlayer (2026) by MuwMix, GPL-3.0.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.scaleAwareSpring

object SettingsDimensions {
    val GroupCardCornerRadius = 18.dp
    val BannerCardCornerRadius = 18.dp

    val ScreenHorizontalPadding = 16.dp
    val ScreenBottomPadding = 24.dp
    val SectionSpacing = 12.dp
    val RowVerticalPadding = 10.dp
    val RowHorizontalPadding = 14.dp

    val RowIconSize = 32.dp
    val RowIconInnerSize = 20.dp
    val BannerIconSize = 46.dp
    val BannerIconInnerSize = 24.dp
    val ChevronSize = 18.dp

    val ProfileCardAvatarSize = 52.dp
    val ProfileCardAvatarIconSize = 24.dp

    val DividerThickness = 0.5.dp
    val DividerStartIndent = 60.dp
    val DividerAlpha = 0.3f

    val SectionHeaderBottomPadding = 6.dp
    val SectionHeaderHorizontalPadding = 20.dp

    val SegmentedGroupHorizontalPadding = 16.dp
    val SegmentedItemGap = 1.5.dp
    val SegmentedCornerLarge = 22.dp
    val SegmentedCornerSmall = 5.dp
    val SegmentedItemMinHeight = 72.dp
    val SegmentedItemPaddingHorizontal = 18.dp
    val SegmentedItemPaddingVertical = 12.dp
    val SegmentedIconBoxSize = 46.dp
    val SegmentedIconSize = 25.dp
    val SegmentedIconSpacing = 16.dp
    val SegmentedRowSpacing = 2.dp
    val SegmentedBadgeSpacing = 10.dp
    val SegmentedBadgePaddingH = 8.dp
    val SegmentedBadgePaddingV = 4.dp

    val RowTextSpacing = 1.dp
    val RowChevronSpacing = 4.dp
    val RowChevronAlpha = 0.3f
    val BadgePaddingH = 8.dp

    val GlassCornerRadius = 18.dp
    val GlassBorderThickness = 0.5.dp

    val BottomSheetHorizontalPadding = 16.dp
    val BottomSheetBottomPadding = 20.dp
    val BottomSheetCornerRadius = 28.dp
    val BottomSheetContentPaddingH = 20.dp
    val BottomSheetContentPaddingTop = 16.dp
    val BottomSheetContentPaddingBottom = 24.dp
    val BottomSheetDragHandleWidth = 40.dp
    val BottomSheetDragHandleHeight = 4.dp
    val BottomSheetDragHandleBottomPadding = 12.dp
    val BottomSheetTitleBottomPadding = 16.dp
    val BottomSheetListMaxHeight = 480.dp
    val BottomSheetOptionIconSize = 20.dp
    val BottomSheetOptionIconSpacing = 12.dp
}

object SettingsAnimations {
    val PressScale = 0.96f

    /**
     * The shared press/expand spring, adapted to the system's animator duration scale through the
     * same [scaleAwareSpring] the rest of the app animates with: below 1x the damping rises
     * towards critical so there is no overshoot left to draw and the stiffness comes down so the
     * remaining travel spreads over the frames that are actually available; at 0 it snaps.
     */
    @Composable
    fun <T> pressSpring(): FiniteAnimationSpec<T> {
        if (LocalAnimationsDisabled.current) return snap()
        return scaleAwareSpring(
            scale = LocalAnimationScale.current,
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMedium,
        )
    }
}
