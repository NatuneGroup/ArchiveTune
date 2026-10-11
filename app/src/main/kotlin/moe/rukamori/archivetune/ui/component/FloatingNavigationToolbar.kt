/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import moe.rukamori.archivetune.utils.isLowEndDevice
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.utils.ImageBlurUtils
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_HEIGHT_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_LABEL_SPACING_DEFAULT
import moe.rukamori.archivetune.constants.NavigationBarHeight
import moe.rukamori.archivetune.constants.NavigationBarHeightKey
import moe.rukamori.archivetune.constants.NavigationBarLabelSpacingKey
import moe.rukamori.archivetune.constants.NavigationBarStyle
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.utils.rememberPreference
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Shared handle for the frosted navigation bar: the app records its content into [layer] each
 * frame, and the bar draws that layer (offset by the recorded content's root position) behind a
 * blur so the pixels underneath show through frosted.
 */
class NavigationBarBackdrop(
    val layer: GraphicsLayer,
) {
    var contentOffsetInRoot: Offset = Offset.Zero
}

/**
 * The app-content capture used for frosted-glass surfaces (navigation bar, mini player). Null when
 * no frosted surface is enabled or the device cannot blur (below Android 12).
 */
val LocalNavigationBarBackdrop = compositionLocalOf<NavigationBarBackdrop?> { null }

private val NavigationItemsMaxWidth = 360.dp
private val NavigationItemVerticalPadding = 8.dp

private val SukiSUBarHeight = 64.dp
private val SukiSUItemPadding = 4.dp // applied to the items Row on ALL sides (matches SukiSU's Row.padding(4.dp))

// The sliding pill wraps just the icon (like the stock indicator), so the label sits below it,
// outside the bubble. These are the standard Material3 active-indicator dimensions.
private val NavigationIndicatorWidth = 56.dp
private val NavigationIndicatorHeight = 32.dp

// The floating pill uses a larger, softer blob around the selected icon (label stays outside),
// tinted with the accent color like the reference bar.
private val FloatingNavigationIndicatorWidth = 64.dp
private val FloatingNavigationIndicatorHeight = 42.dp

/**
 * Forces the signature navigation-bar motion (the sliding pill + icon pop) to always run at its
 * full, intended duration, ignoring the system "Animator duration scale" developer setting. Users
 * who run the OS at 0.5x still get expressive tab-switch motion, while the in-app "disable
 * animations" toggle continues to fully bypass these animations.
 */
private object FullMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

@Composable
fun FloatingNavigationToolbar(
    items: List<Screens>,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
    miniPlayerProximityProvider: () -> Float = { 0f },
    style: NavigationBarStyle = NavigationBarStyle.DEFAULT,
    frostedBlur: Boolean = false,
    tintFrostedBlur: Boolean = false,
    frostedBackdrop: NavigationBarBackdrop? = null,
    liquidGlass: Boolean = false,
    liquidGlassBackdrop: LayerBackdrop? = null,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
    onSearchItemDoubleClick: (() -> Unit)? = null,
) {
    val isFloating = style == NavigationBarStyle.FLOATING
    val isYumaPlayer = style == NavigationBarStyle.YUMA_PLAYER
    val isAppleMusic = style == NavigationBarStyle.APPLE_MUSIC
    // Navigation bar customization. Read directly here so the toolbar picks up the user's
    // tuning without the call site needing to thread 6 extra params.
    val (navBarHeightMultiplier) =
        rememberPreference(NavigationBarHeightKey, defaultValue = NAVIGATION_BAR_HEIGHT_DEFAULT)
    val (navBarLabelSpacing) =
        rememberPreference(NavigationBarLabelSpacingKey, defaultValue = NAVIGATION_BAR_LABEL_SPACING_DEFAULT)
    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    // Either frosted variant enables backdrop blur. The tint variant additionally tints the
    // bar surface with the accent (primary) color so the frost reads as a colored glass.
    val anyFrosted = frostedBlur || tintFrostedBlur
    val canBlurBackdrop = anyFrosted && frostedBackdrop != null && !isPreS
    // available (Android 12+). Pure-black mode is not a hard blocker — the
    // liquid glass surface tints itself with `surfaceContainerHigh`, so it stays
    // visible even when the rest of the UI is pitch black, and the user explicitly
    // opted in via the Liquid Glass toggle.
    val canLiquidGlass = liquidGlass && liquidGlassBackdrop != null && !isPreS
    val resolvedBarHeight =
        if (canLiquidGlass) SukiSUBarHeight else NavigationBarHeight * navBarHeightMultiplier
    // SukiSU-Ultra: when the Liquid Glass nav bar is active, the items Row uses
    // 4.dp padding on ALL sides (matching SukiSU's Row.padding(4.dp)). The non-
    // Liquid-Glass variants keep the original 8.dp vertical-only padding.
    val itemVerticalPadding =
        if (canLiquidGlass) SukiSUItemPadding else NavigationItemVerticalPadding
    val itemHorizontalPadding = if (canLiquidGlass) SukiSUItemPadding else 0.dp
    // Same per-frame discipline as the mini player: the provider fires every sheet-drag
    // frame, so read it through derivedStateOf and remember the shape. At proximity 0 the
    // lerp settles on exactly 32.dp, which is MaterialTheme.shapes.extraLarge (see
    // Theme.kt) — the previous unpaired fallback — so rest geometry is bit-identical.
    val miniPlayerProximity by remember(miniPlayerProximityProvider) {
        derivedStateOf { miniPlayerProximityProvider().coerceIn(0f, 1f) }
    }
    val pill =
        rememberPillStyle(
            style = style,
            pureBlack = pureBlack,
            frostedBlur = frostedBlur,
            tintFrostedBlur = tintFrostedBlur,
            liquidGlass = liquidGlass,
            frostedBackdropAvailable = frostedBackdrop != null,
            liquidGlassBackdropAvailable = liquidGlassBackdrop != null,
        )
    val navigationShape =
        remember(pill, miniPlayerProximity) { pill.shape(PillRole.NAVIGATION_BAR, miniPlayerProximity) }
    val tintedContent = pill.tintedContent
    val navigationContainerColor = pill.containerColor
    val motionScheme = MaterialTheme.motionScheme
    val (disableAnimations) = rememberPreference(DisableAnimationsKey, defaultValue = false)
    val (hideNavigationBarLabelsPreference) =
        rememberPreference(HideNavigationBarLabelsKey, defaultValue = false)
    // Apple Music's tab bar is icon + label by definition, so the style wins over the global
    // "hide labels" preference rather than silently losing its own look to it.
    val hideNavigationLabels = hideNavigationBarLabelsPreference && !isYumaPlayer && !isAppleMusic
    val density = LocalDensity.current

    val indicatorColor =
        when {
            // Liquid Glass: transparent indicator — the glass pill itself is the
            // indicator. Setting this to transparent avoids a theme-tinted blob
            // behind the active item.
            canLiquidGlass -> Color.Transparent
            // Apple Music's active tab sits in a tinted pill: the reference shows a solid
            // primary-coloured capsule with the glyph knocked out of it and the label in the accent.
            isYumaPlayer -> MaterialTheme.colorScheme.primary
            // Apple Music: the active tab gets a faint neutral capsule and the accent goes to the
            // glyph and label only, so the bar itself stays monochrome.
            isAppleMusic && pureBlack -> Color.White.copy(alpha = 0.16f)
            isAppleMusic -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            tintFrostedBlur && !isFloating -> tintedContent.copy(alpha = 0.18f)
            isFloating -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            pureBlack -> Color.White.copy(alpha = 0.16f)
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    val largeIndicator = isFloating || isYumaPlayer || isAppleMusic
    val indicatorWidth = if (largeIndicator) FloatingNavigationIndicatorWidth else NavigationIndicatorWidth
    val indicatorHeight = if (largeIndicator) FloatingNavigationIndicatorHeight else NavigationIndicatorHeight

    // The built-in per-item indicator just fades in place; hide it so our single pill can slide
    // between items instead. On pure-black we also pin the icon/label colors for contrast.
    // For the tint-frosted variant, the bar surface is the primary color, so icons/labels use
    // onPrimary for contrast (selected = full opacity, unselected = 0.85 opacity for legibility).
    val itemColors =
        when {
            canLiquidGlass ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = Color.White,
                    unselectedTextColor = Color.White,
                )
            isFloating || isAppleMusic ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor =
                        if (pureBlack) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor =
                        if (pureBlack) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            isYumaPlayer ->
                ShortNavigationBarItemDefaults.colors(
                    // The pill is drawn behind the item (see indicatorColor), so the item's own
                    // indicator stays transparent and the glyph is knocked out of the accent.
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            // Checked before pureBlack so an AMOLED + tinted bar keeps its tint.
            tintFrostedBlur ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = tintedContent,
                    selectedTextColor = tintedContent,
                    unselectedIconColor = tintedContent.copy(alpha = 0.7f),
                    unselectedTextColor = tintedContent.copy(alpha = 0.7f),
                )
            pureBlack ->
                ShortNavigationBarItemDefaults.colors(
                    selectedIndicatorColor = Color.Transparent,
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                    unselectedTextColor = Color.White.copy(alpha = 0.6f),
                )
            else -> ShortNavigationBarItemDefaults.colors(selectedIndicatorColor = Color.Transparent)
        }

    val selectedIndex = items.indexOfFirst { isSelected(it) }

    val liquidGlassTransparentColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = Color.Transparent,
            selectedTextColor = Color.Transparent,
            unselectedIconColor = Color.Transparent,
            unselectedTextColor = Color.Transparent,
        )

    // Measured center of each item's icon (root-space) and the row container's top-left, so the pill
    // can slide to the exact icon position regardless of layout/insets. Only the icon is tracked so
    // the bubble hugs the icon and leaves the text label outside of it.
    val iconCenters = remember { mutableStateMapOf<Int, Offset>() }
    val itemBounds = remember { mutableStateMapOf<Int, Rect>() }
    var containerPos by remember { mutableStateOf(Offset.Zero) }

    val indicatorX = remember { Animatable(0f) }
    var indicatorY by remember { mutableFloatStateOf(0f) }
    var indicatorPlaced by remember { mutableStateOf(false) }
    // Liquid Glass pill size, computed from the selected item's full bounds
    // (icon + label). Zero until the first item is measured.
    var liquidGlassPillWidth by remember { mutableStateOf(0.dp) }
    var liquidGlassPillHeight by remember { mutableStateOf(0.dp) }

    val animationScope = rememberCoroutineScope()
    val isLtr = true // ArchiveTune doesn't currently support RTL layout flipping
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    // The items Row is centered inside the container Box (via the Box's
    // `contentAlignment = Alignment.Center`). To position the sliding pill at
    // `dampedDragAnimation.value × tabWidthPx`, we need to know the items
    // Row's left edge within the container Box — tracked here.
    var itemsRowLeftInContainer by remember { mutableFloatStateOf(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    // Accumulated raw drag delta (unclamped). Used as the source for the
    // clamped rubber-band `panelOffset` — at most ±4dp, with an EaseOut curve
    // so it ramps up quickly then slows down (the iOS liquid-glass feel).
    val rubberBandOffset = remember { Animatable(0f) }
    val panelOffset by remember(rubberBandPx, totalWidthPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (rubberBandOffset.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    val tabsCount = items.size
    val selectedIndexUpdated = rememberUpdatedState(selectedIndex)
    val onItemClickUpdated = rememberUpdatedState(onItemClick)
    val dampedDragAnimation =
        remember(tabsCount, canLiquidGlass) {
            if (canLiquidGlass && tabsCount > 0) {
                LiquidGlassDragAnimation(
                    animationScope = animationScope,
                    initialValue = selectedIndex.coerceIn(0, tabsCount - 1).toFloat(),
                    valueRange = 0f..(tabsCount - 1).toFloat(),
                    visibilityThreshold = 0.001f,
                    initialScale = 1f,
                    pressedScale = 78f / 56f, // SukiSU: 56dp tab → 78dp pressed pill
                    canDrag = { offset ->
                        // Only accept drags that begin inside the bar's content area
                        // (totalWidthPx × bar height). This prevents the drag from
                        // triggering when the user touches outside the items Row.
                        if (totalWidthPx == 0f) return@LiquidGlassDragAnimation false
                        offset.x in 0f..totalWidthPx && offset.y >= 0f
                    },
                    onDragStarted = { /* no-op — press() is called by the modifier */ },
                    onDragStopped = {
                        val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                        animationScope.launch {
                            rubberBandOffset.animateTo(0f, spring(1f, 300f, 0.5f))
                        }
                        // Read the LATEST selectedIndex via the State wrapper —
                        // otherwise we'd compare against the stale value captured
                        // at animation-creation time.
                        if (targetIndex != selectedIndexUpdated.value) {
                            onItemClickUpdated.value(items[targetIndex], false)
                        }
                        animateToValue(targetIndex.toFloat())
                    },
                    onDrag = { _, dragAmount ->
                        if (tabWidthPx > 0f) {
                            updateValue(
                                (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                    .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                            )
                            animationScope.launch {
                                rubberBandOffset.snapTo(rubberBandOffset.value + dragAmount.x)
                            }
                        }
                    },
                )
            } else {
                null
            }
        }

    val displayIndex by remember(dampedDragAnimation) {
        derivedStateOf {
            dampedDragAnimation?.value?.roundToInt()?.coerceIn(0, items.lastIndex) ?: selectedIndex
        }
    }

    val isSelectedTracker = rememberUpdatedState(isSelected)
    val itemsTracker = rememberUpdatedState(items)
    LaunchedEffect(dampedDragAnimation) {
        val anim = dampedDragAnimation ?: return@LaunchedEffect
        snapshotFlow {
            val itemsList = itemsTracker.value
            val sel = isSelectedTracker.value
            itemsList.indexOfFirst { sel(it) }
        }.drop(1).collect { idx ->
            // `items.size` is captured at composition time. It's stable for the
            // lifetime of `dampedDragAnimation` (which is `remember(tabsCount, ...)`
            // keyed on `items.size`), so this check is safe.
            if (idx in 0..(items.size - 1)) {
                anim.animateToValue(idx.toFloat())
            }
        }
    }

    val selectedCenter = if (selectedIndex >= 0) iconCenters[selectedIndex] else null
    val selectedItemBounds = if (selectedIndex >= 0) itemBounds[selectedIndex] else null
    var itemsRowTopInContainer by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(selectedIndex, selectedCenter, selectedItemBounds, containerPos, disableAnimations, indicatorWidth, indicatorHeight, canLiquidGlass, tabWidthPx, itemsRowTopInContainer, itemVerticalPadding) {
        if (canLiquidGlass) {
            if (tabWidthPx <= 0f) return@LaunchedEffect
            liquidGlassPillWidth = with(density) { tabWidthPx.toDp() }
            liquidGlassPillHeight = SukiSUBarHeight - SukiSUItemPadding * 2 // 64 - 8 = 56.dp
            // Position the pill at the items Row's content area top (which is
            // `itemVerticalPadding` below the bar's top edge). The items Row
            // fills the Surface height (64.dp) with 4.dp vertical padding, so
            // its content area starts at y = 4.dp from the Surface's top.
            indicatorY = itemsRowTopInContainer + with(density) { itemVerticalPadding.toPx() }
            indicatorPlaced = true
        } else {
            // Default / frosted / floating: pill wraps only the icon (label
            // stays outside). This is the original behavior.
            val center = selectedCenter ?: return@LaunchedEffect
            val widthPx = with(density) { indicatorWidth.toPx() }
            val heightPx = with(density) { indicatorHeight.toPx() }
            val targetX = (center.x - containerPos.x) - widthPx / 2f
            indicatorY = (center.y - containerPos.y) - heightPx / 2f
            val firstPlacement = !indicatorPlaced
            if (disableAnimations || firstPlacement) {
                indicatorX.snapTo(targetX)
                indicatorPlaced = true
            } else {
                withContext(FullMotionDurationScale) {
                    indicatorX.animateTo(
                        targetValue = targetX,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                    )
                }
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.Center,
    ) {
        var barPositionInRoot by remember { mutableStateOf(Offset.Zero) }
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier =
                Modifier
                    .widthIn(max = pill.maxWidth)
                    .fillMaxWidth(pill.widthFraction)
                    .height(resolvedBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        barPositionInRoot = it.positionInRoot()
                        barSize = it.size
                    }
                    .graphicsLayer {
                        if (canLiquidGlass) {
                            translationX = panelOffset
                        }
                    }
                    .then(
                        if (canLiquidGlass && liquidGlassBackdrop != null) {
                            Modifier.liquidGlass(
                                backdrop = liquidGlassBackdrop,
                                shape = navigationShape,
                                interactive = false,
                                baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = navigationShape,
            color = navigationContainerColor,
            border = pill.border,
            tonalElevation = if (canLiquidGlass) 0.dp else pill.tonalElevation,
            shadowElevation = if (canLiquidGlass) 0.dp else pill.shadowElevation,
        ) {
            if (canBlurBackdrop && frostedBackdrop != null) {
                if (isPreS) {
                    val blurredBitmap = rememberPreSFrostedBitmap(
                        backdrop = frostedBackdrop,
                        barPositionInRoot = barPositionInRoot,
                        barSize = barSize,
                        blurRadiusPx = PillFrostBlurRadiusPx,
                        updateIntervalMs = if (LocalContext.current.isLowEndDevice()) 160L else 80L,
                    )
                    if (blurredBitmap != null) {
                        // Match the S+ path's conditional overlay alpha (see the comment below).
                        val preSOverlayAlpha = if (tintFrostedBlur) PillTintFrostOverlayAlpha else PillFrostOverlayAlpha
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = preSOverlayAlpha
                                        clip = true
                                    }.drawBehind {
                                        drawImage(blurredBitmap)
                                    },
                        )
                    }
                } else {
                    val frostedOverlayAlpha = if (tintFrostedBlur) PillTintFrostOverlayAlpha else PillFrostOverlayAlpha
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    renderEffect =
                                        BlurEffect(
                                            radiusX = PillFrostBlurRadiusPx,
                                            radiusY = PillFrostBlurRadiusPx,
                                            edgeTreatment = TileMode.Clamp,
                                        )
                                    alpha = frostedOverlayAlpha
                                    clip = true
                                }.drawBehind {
                                    val offset = frostedBackdrop.contentOffsetInRoot - barPositionInRoot
                                    translate(offset.x, offset.y) {
                                        drawLayer(frostedBackdrop.layer)
                                    }
                                },
                    )
                }
            }
            val transparentRipple = remember { ripple(color = Color.Transparent) }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalIndication provides transparentRipple,
            ) {
                ShortNavigationBar(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor =
                        when {
                            tintFrostedBlur -> tintedContent
                            pureBlack -> Color.White
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    arrangement = ShortNavigationBarArrangement.EqualWeight,
                ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { containerPos = it.positionInRoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selectedIndex >= 0 && indicatorPlaced && !canLiquidGlass) {
                        val pillWidth = indicatorWidth
                        val pillHeight = indicatorHeight
                        if (pillWidth > 0.dp && pillHeight > 0.dp) {
                            val pillShape = RoundedCornerShape(percent = 50)
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset {
                                            IntOffset(
                                                indicatorX.value.roundToInt(),
                                                indicatorY.roundToInt(),
                                            )
                                        }
                                        .width(pillWidth)
                                        .height(pillHeight)
                                        .clip(pillShape)
                                        .background(indicatorColor),
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier
                                .widthIn(max = NavigationItemsMaxWidth)
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .padding(
                                    vertical = itemVerticalPadding,
                                    horizontal = itemHorizontalPadding,
                                )
                                .onGloballyPositioned { coordinates ->
                                    val rowPosInRoot = coordinates.positionInRoot()
                                    itemsRowLeftInContainer = rowPosInRoot.x - containerPos.x
                                    itemsRowTopInContainer = rowPosInRoot.y - containerPos.y
                                    totalWidthPx = coordinates.size.width.toFloat()
                                    val horizontalPaddingPx = with(density) { itemHorizontalPadding.toPx() }
                                    val contentWidthPx = (totalWidthPx - 2f * horizontalPaddingPx).coerceAtLeast(0f)
                                    tabWidthPx = if (tabsCount > 0) (contentWidthPx / tabsCount).coerceAtLeast(0f) else 0f
                                }
                                .then(
                                    if (canLiquidGlass && dampedDragAnimation != null) {
                                        dampedDragAnimation.modifier
                                    } else {
                                        Modifier
                                    },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items.forEachIndexed { index, screen ->
                            val selected = isSelected(screen)
                            // Tactile "pop": when an item becomes selected, its icon springs
                            // from 80% up past 100% and settles back, giving the bottom pill a
                            // lively bounce on every tab switch (skipped when animations are off).
                            val iconScale = remember(screen) { Animatable(1f) }
                            LaunchedEffect(selected, disableAnimations) {
                                if (disableAnimations) {
                                    iconScale.snapTo(1f)
                                } else if (selected) {
                                    iconScale.snapTo(0.8f)
                                    // Fixed motion scale keeps the pop expressive at 0.5x too.
                                    withContext(FullMotionDurationScale) {
                                        iconScale.animateTo(
                                            targetValue = 1f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                        )
                                    }
                                } else {
                                    iconScale.snapTo(1f)
                                }
                            }
                            val onDoubleClick =
                                remember(screen, onSearchItemDoubleClick) {
                                    if (screen == Screens.Search) onSearchItemDoubleClick else null
                                }
                            val lastClickTime = remember(screen) { mutableLongStateOf(0L) }
                            val onClick =
                                remember(screen, selected, onItemClick, onDoubleClick) {
                                    {
                                        val currentTime = SystemClock.uptimeMillis()
                                        val isDoubleClick =
                                            onDoubleClick != null &&
                                                currentTime - lastClickTime.longValue <= ViewConfiguration.getDoubleTapTimeout()
                                        lastClickTime.longValue = if (isDoubleClick) 0L else currentTime
                                        if (isDoubleClick) {
                                            onDoubleClick?.invoke()
                                            Unit
                                        } else {
                                            onItemClick(screen, selected)
                                        }
                                    }
                                }

                            ShortNavigationBarItem(
                                selected = selected,
                                onClick = onClick,
                                colors = if (canLiquidGlass && index == displayIndex) liquidGlassTransparentColors else itemColors,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { coordinates ->
                                            // Track the full item bounds (icon + spacing +
                                            // label) so the Liquid Glass pill can size to
                                            // cover both. The default variant ignores this
                                            // and uses the icon-only [iconCenters] map.
                                            val pos = coordinates.positionInRoot()
                                            itemBounds[index] =
                                                Rect(
                                                    pos.x,
                                                    pos.y,
                                                    pos.x + coordinates.size.width,
                                                    pos.y + coordinates.size.height,
                                                )
                                        },
                                icon = {
                                    // Measure the icon's own bounds so the pill hugs only the icon.
                                    Box(
                                        modifier =
                                            Modifier.onGloballyPositioned { coordinates ->
                                                val pos = coordinates.positionInRoot()
                                                iconCenters[index] =
                                                    Offset(
                                                        pos.x + coordinates.size.width / 2f,
                                                        pos.y + coordinates.size.height / 2f,
                                                    )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Crossfade(
                                            targetState = selected,
                                            animationSpec = motionScheme.fastEffectsSpec(),
                                            label = "navigationItemIcon",
                                        ) { isSelected ->
                                            Icon(
                                                painter =
                                                    painterResource(
                                                        // For Liquid Glass: the underlying bar always
                                                        // uses the INACTIVE icon variant. The pill
                                                        // overlay renders the ACTIVE variant in
                                                        // primary color on top of the glass.
                                                        if (canLiquidGlass) screen.iconIdInactive else if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                                                    ),
                                                contentDescription = null,
                                                modifier =
                                                    Modifier.graphicsLayer {
                                                        scaleX = iconScale.value
                                                        scaleY = iconScale.value
                                                    },
                                            )
                                        }
                                    }
                                },
                                label = if (hideNavigationLabels) {
                                    null
                                } else {
                                    {
                                        if (canLiquidGlass) {
                                            Text(
                                                text = stringResource(screen.titleId),
                                                maxLines = 1,
                                                modifier = Modifier.offset(y = (-4).dp),
                                                // Liquid Glass: underlying label is always Normal.
                                                // The pill overlay renders the active label in
                                                // SemiBold (primary color) on top of the glass.
                                                fontWeight = FontWeight.Normal,
                                            )
                                        } else {
                                            Spacer(Modifier.height(navBarLabelSpacing.dp))
                                            Text(
                                                text = stringResource(screen.titleId),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            } // end CompositionLocalProvider(LocalIndication provides transparentRipple)

        }

        if (canLiquidGlass && selectedIndex >= 0 && indicatorPlaced && liquidGlassBackdrop != null) {
            val pillWidth = liquidGlassPillWidth
            val pillHeight = liquidGlassPillHeight
            val dragAnim = dampedDragAnimation
            if (pillWidth > 0.dp && pillHeight > 0.dp && dragAnim != null && tabWidthPx > 0f) {
                val pillShape = RoundedCornerShape(percent = 50)
                val isDark = isSystemInDarkTheme()
                // Liquid Glass pill: always use white for the active icon/label so
                // they don't change with the app's color theme. The pill's glass
                // effect provides enough contrast on its own.
                val primaryColor = Color.White
                // Fallback surface drawn UNDER the backdrop sample. Hoisted to the
                // composable scope because MaterialTheme.colorScheme is only
                // accessible from a @Composable context (onDrawBehind is a plain
                // DrawScope lambda).
                val pillFallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                Box(
                    modifier =
                        Modifier
                            .offset {
                                val pillWidthPx = pillWidth.toPx()
                                val hPaddingPx = itemHorizontalPadding.toPx()
                                // X: align with the items Row's content area, then slide.
                                // iconOffsetX corrects for ShortNavigationBarItem not
                                // centering the icon within the tab slot.
                                val tabSlotCenterForSelected =
                                    containerPos.x +
                                        itemsRowLeftInContainer +
                                        hPaddingPx +
                                        selectedIndex * tabWidthPx +
                                        tabWidthPx / 2f
                                val iconOffsetX =
                                    selectedCenter?.let { it.x - tabSlotCenterForSelected } ?: 0f
                                val xInRoot =
                                    containerPos.x +
                                        itemsRowLeftInContainer +
                                        hPaddingPx +
                                        dragAnim.value * tabWidthPx +
                                        (tabWidthPx - pillWidthPx) / 2f +
                                        panelOffset +
                                        iconOffsetX
                                // Y = 0: the wrapper Box's CenterStart alignment
                                // centers the pill vertically. No Y computation needed.
                                val xRelativeToWrapper = xInRoot - barPositionInRoot.x
                                IntOffset(xRelativeToWrapper.roundToInt(), 0)
                            }
                            .width(pillWidth)
                            .height(pillHeight)
                            .graphicsLayer {
                                // SukiSU pressedScale = 78f / 56f ≈ 1.393.
                                // Velocity-stretch: the pill squishes horizontally when
                                // flung (a physical "rubber" feel). The asymmetry (0.75 ×
                                // horizontal, 0.25 × vertical) preserves area roughly.
                                val pressScale = lerp(1f, 78f / 56f, dragAnim.pressProgress)
                                val velocityStretch = (dragAnim.velocity / 10f).fastCoerceIn(-0.2f, 0.2f)
                                scaleX = pressScale / (1f - velocityStretch * 0.75f)
                                scaleY = pressScale * (1f - velocityStretch * 0.25f)
                            }
                            .drawBackdrop(
                                backdrop = liquidGlassBackdrop,
                                effects = {
                                    vibrancy()
                                    blur(4f.dp.toPx())
                                    lens(
                                        refractionHeight = 24f.dp.toPx(),
                                        refractionAmount = size.minDimension / 4f,
                                        chromaticAberration = false,
                                    )
                                },
                                onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                                shape = { pillShape },
                                // Fallback surface drawn UNDER the backdrop sample.
                                // When the backdrop has content (album art, page content
                                // behind the nav bar), the backdrop sample covers this
                                // and you see the liquid glass refraction. When the
                                // backdrop is EMPTY (e.g. bottom of a short page with
                                // nothing behind the nav bar), the backdrop sample is
                                // transparent and this opaque surface shows through —
                                // matching the existing pattern used by the bar Surface
                                // (baseColor = surfaceContainerHigh) and the Frosted nav
                                // bar variant (opaque surface + 30% alpha overlay).
                                onDrawBehind = {
                                    drawRect(pillFallbackColor)
                                },
                                onDrawSurface = {
                                    val progress = dragAnim.pressProgress
                                    drawRect(
                                        color = (if (isDark) Color.Black else Color.White).copy(alpha = 0.1f),
                                        alpha = 1f - progress,
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                },
                            )
                            .innerShadow(shape = pillShape) {
                                // Inner shadow only appears during press — radius + alpha
                                // both scale with pressProgress. This is the "glass lifted
                                // off the bar" feel.
                                if (dragAnim.pressProgress > 0f) {
                                    InnerShadow(
                                        radius = 8.dp * dragAnim.pressProgress,
                                        color = Color.Black.copy(alpha = 0.15f),
                                        alpha = dragAnim.pressProgress,
                                    )
                                } else {
                                    null
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    val displayScreen = items[displayIndex]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(
                            painter = painterResource(displayScreen.iconIdActive),
                            contentDescription = null,
                            tint = Color.White,
                            modifier =
                                Modifier.graphicsLayer {
                                    // SukiSU LocalFloatingBottomBarTabScale: the
                                    // icon scales up to 1.2× during press.
                                    val scale = lerp(1f, 1.2f, dragAnim.pressProgress)
                                    scaleX = scale
                                    scaleY = scale
                                },
                        )
                        if (!hideNavigationLabels) {
                            Text(
                                text = stringResource(displayScreen.titleId),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        } // end wrapper Box (CenterStart — vertically centers the pill)
    }
}

/** Pre-Android 12 (pre-S) fallback for the frosted-glass surfaces (navigation bar, mini player). */
@Composable
internal fun rememberPreSFrostedBitmap(
    backdrop: NavigationBarBackdrop?,
    barPositionInRoot: Offset,
    barSize: IntSize,
    blurRadiusPx: Float,
    updateIntervalMs: Long = 80L,
): ImageBitmap? {
    if (backdrop == null) return null
    var blurred by remember(backdrop, blurRadiusPx, updateIntervalMs) {
        mutableStateOf<ImageBitmap?>(null)
    }
    // rememberUpdatedState lets the LaunchedEffect's coroutine read the LATEST bar position/size
    // without re-launching on every layout pass (the LaunchedEffect's keys are intentionally
    // coarse — backdrop/blurRadius/interval — so the capture loop isn't torn down and rebuilt
    // every time the bar moves).
    val barPositionState = rememberUpdatedState(barPositionInRoot)
    val barSizeState = rememberUpdatedState(barSize)

    LaunchedEffect(backdrop, blurRadiusPx, updateIntervalMs) {
        while (isActive) {
            val layer = backdrop.layer
            val layerW = layer.size.width
            val layerH = layer.size.height
            if (layerW > 0 && layerH > 0) {
                try {
                    val next = withContext(Dispatchers.Default) {
                        // Read the latest bar geometry. These States are updated by the caller's
                        // onGloballyPositioned, which fires on the main thread during layout.
                        val pos = barPositionState.value
                        val size = barSizeState.value
                        if (size.width <= 0 || size.height <= 0) return@withContext null

                        // The slice is the bar's bounds expressed in the LAYER's coordinate
                        // system (layer's top-left is contentOffsetInRoot in root coords).
                        val contentOffset = backdrop.contentOffsetInRoot
                        val rawX = (pos.x - contentOffset.x).toInt()
                        val rawY = (pos.y - contentOffset.y).toInt()

                        // Padding around the slice so the stack blur has neighboring pixels to
                        // sample at the bar's edges. Without this, the blur clamps the bar's own
                        // edge pixels and the frost looks wrong at the boundary.
                        val pad = blurRadiusPx.toInt().coerceIn(8, 64)

                        // Clamp the padded slice to the layer's bounds. If the bar is partially
                        // off-layer (e.g. floating bar that overshoots), we still capture what we
                        // can — the missing pixels are filled with the layer's edge color via
                        // stack blur's edge clamping, which is acceptable.
                        val paddedX = rawX - pad
                        val paddedY = rawY - pad
                        val paddedW = size.width + 2 * pad
                        val paddedH = size.height + 2 * pad
                        val clampedX = paddedX.coerceIn(0, layerW - 1)
                        val clampedY = paddedY.coerceIn(0, layerH - 1)
                        val clampedRight = (paddedX + paddedW).coerceIn(1, layerW)
                        val clampedBottom = (paddedY + paddedH).coerceIn(1, layerH)
                        val clampedW = clampedRight - clampedX
                        val clampedH = clampedBottom - clampedY
                        if (clampedW <= 0 || clampedH <= 0) return@withContext null

                        // Capture the full layer to a bitmap. This is the expensive part (GPU→CPU
                        // readback) but is unavoidable — GraphicsLayer has no region capture API.
                        val imageBitmap = layer.toImageBitmap()
                        val fullBitmap = imageBitmap.asAndroidBitmap()

                        // Extract just the small slice under the bar (with padding). This is a
                        // cheap pixel copy; the slice is ~10x smaller than the full bitmap.
                        val sliceBitmap = Bitmap.createBitmap(
                            fullBitmap,
                            clampedX,
                            clampedY,
                            clampedW,
                            clampedH,
                        )

                        // Blur the small slice directly. Because the slice is small, the 720 px
                        // downscale threshold in ImageBlurUtils either doesn't trigger or triggers
                        // at a mild factor, so the blur keeps real resolution (no muddy upscale).
                        val blurredSlice = ImageBlurUtils.blur(sliceBitmap, blurRadiusPx)

                        val barXInSlice = (rawX - clampedX).coerceIn(0, blurredSlice.width - 1)
                        val barYInSlice = (rawY - clampedY).coerceIn(0, blurredSlice.height - 1)
                        val barW = size.width.coerceAtMost(blurredSlice.width - barXInSlice)
                        val barH = size.height.coerceAtMost(blurredSlice.height - barYInSlice)
                        if (barW <= 0 || barH <= 0) {
                            // Degenerate slice — return as-is and let the caller clip.
                            blurredSlice.asImageBitmap()
                        } else if (barXInSlice == 0 && barYInSlice == 0 &&
                            blurredSlice.width == size.width && blurredSlice.height == size.height
                        ) {
                            // No padding was added (pad == 0, impossible here since pad >= 8) or
                            // slice is already exactly the bar size — skip the extra allocation.
                            blurredSlice.asImageBitmap()
                        } else {
                            Bitmap.createBitmap(blurredSlice, barXInSlice, barYInSlice, barW, barH)
                                .asImageBitmap()
                        }
                    }
                    if (next != null) blurred = next
                } catch (_: Throwable) {
                    // Capture or blur failed (e.g. OOM, native crash on some devices) — keep the
                    // previous frame; the opaque base surface is still visible underneath.
                }
            }
            delay(updateIntervalMs)
        }
    }
    return blurred
}
