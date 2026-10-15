/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Our implementation of the SukiSU liquid glass navigation bar, after SukiSU-Ultra's
 * FloatingBottomBar (https://github.com/sukisu-ultra/sukisu-ultra), which adapts
 * compose-miuix-ui's IosLiquidGlassNavigationBar (Apache 2.0) on top of the kyant backdrop
 * library already shipped with ArchiveTune. Renders inside MainActivity's existing nav-bar
 * container: navigation goes through the shell's click handler (which coalesces taps and
 * decides re-select vs navigate), and compact mode, hide-on-scroll and insets are the
 * container's concerns, not this bar's.
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component.sukisu

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.ui.component.LiquidGlassRole
import moe.rukamori.archivetune.ui.component.liquidGlassMaterial
import moe.rukamori.archivetune.ui.component.liquidGlassEligible
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.scaleAwareSpring
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.FloatingNavigationBarMaxWidth
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.ui.component.InnerShadow
import moe.rukamori.archivetune.ui.component.LiquidGlassDragAnimation
import moe.rukamori.archivetune.ui.component.innerShadow
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** The bar's fixed height (SukiSU's 64 dp). MainActivity reserves exactly this much chrome. */
val SukisuLiquidNavBarHeight = 64.dp

private val SukisuPillHeight = 56.dp
private val SukisuItemPadding = 4.dp

// Bar and pill are lit from different directions, so each specular gets its own bias.
private const val SukisuBarHighlightExtraDegrees = -45f
private const val SukisuPillHighlightExtraDegrees = 90f

@Composable
fun SukisuLiquidNavBar(
    items: List<Screens>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop?,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens) -> Unit,
) {
    if (items.isEmpty()) return
    val glassActive = liquidGlassEligible(backdrop)

    val (hideLabels) = rememberPreference(HideNavigationBarLabelsKey, defaultValue = false)
    val (disableAnimations) = rememberPreference(DisableAnimationsKey, defaultValue = false)
    // The pill slide and the icon pop follow the system animator scale, like every other nav
    // motion: the spring damps below 1x so the bounce survives the shortened duration, snaps at 0.
    val animationScale = LocalAnimationScale.current

    // The app's own theme, not the system's: a forced light theme on a dark system would
    // otherwise put white glyphs on a light backdrop (same rule as the M3 toolbar's glass mode).
    val glassIsNight = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pillShape = CircleShape
    val accentColor = MaterialTheme.colorScheme.primary
    val tabContentColor = MaterialTheme.colorScheme.onSurface
    val containerColor =
        if (glassActive) {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    // Rubber band: while a drag pulls the pill past the first or last tab, the whole row leans a
    // few pixels with the overdrag and eases back on release.
    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    val tabsCount = items.size
    var currentIndex by remember { mutableIntStateOf(0) }
    val itemsUpdated = rememberUpdatedState(items)
    val isSelectedUpdated = rememberUpdatedState(isSelected)
    val onItemClickUpdated = rememberUpdatedState(onItemClick)

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { SukisuItemPadding.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }

    val dampedDragAnimation =
        remember(animationScope, tabsCount, density, isLtr) {
            LiquidGlassDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                canDrag = { offset -> offset.x in 0f..totalWidthPx },
                onDragStarted = { position ->
                    updateValue(indexAt(position.x).toFloat())
                },
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    // A drag that releases on a new tab navigates. Releasing on the tab the bar
                    // already shows is not a re-select, so it does not scroll to top.
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        val screen = itemsUpdated.value.getOrNull(targetIndex)
                        if (screen != null) {
                            onItemClickUpdated.value(screen)
                        }
                    }
                    updateValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (tabWidthPx > 0f && dragAmount.x != 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                .coerceIn(0f, (tabsCount - 1).toFloat()),
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                },
            )
        }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        // Every tap is reported; the shell's click handler decides navigate vs re-select
        // (scroll to top) from the live back stack — the bar never guesses from its own pill.
        currentIndex = index
        onItemClickUpdated.value(itemsUpdated.value[index])
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    // The pill follows the shell's selection: the optimistic route a tap sets (this bar's own
    // included — activateTab animates it first, so these are retargets, not restarts) and any
    // external navigation (rail, compact circles, deep links). The first emission places the
    // pill without animating.
    LaunchedEffect(dampedDragAnimation) {
        var initial = true
        snapshotFlow {
            val list = itemsUpdated.value
            list.indexOfFirst { isSelectedUpdated.value(it) }
        }.collect { idx ->
            if (idx >= 0 && idx < tabsCount && idx != currentIndex) {
                currentIndex = idx
                if (initial) {
                    dampedDragAnimation.updateValue(idx.toFloat())
                } else {
                    dampedDragAnimation.animateToValue(idx.toFloat())
                }
            }
            initial = false
        }
    }

    // Remembered once: the position lambda reads tabWidthPx/panelOffset through their state
    // delegates, so it stays live without rebuilding the Animatables (and the AGSL shader)
    // every time the width changes.
    val highlightMotion = glassActive && !disableAnimations && !LocalAnimationsDisabled.current && animationScale > 0f
    val interactiveHighlight =
        if (highlightMotion) remember(animationScope, dampedDragAnimation) {
            SukisuInteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        } else null

    DisposableEffect(interactiveHighlight) {
        onDispose { interactiveHighlight?.cancel() }
    }

    val barHighlight by rememberSukisuSpecularHighlight(SukisuBarHighlightExtraDegrees)
    val pillHighlight by rememberSukisuSpecularHighlight(SukisuPillHighlightExtraDegrees)

    val combinedBackdrop =
        if (glassActive) {
            rememberCombinedBackdrop(backdrop!!, tabsBackdrop)
        } else {
            null
        }
    val tabScaleProvider =
        remember(dampedDragAnimation, highlightMotion) {
            { lerp(1f, 1.2f, (if (highlightMotion) dampedDragAnimation.pressProgress else 0f)) }
        }

    val glassSelectedColor =
        if (glassIsNight) Color.White else MaterialTheme.colorScheme.onSurface
    val glassUnselectedColor =
        if (glassIsNight) {
            Color.White
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val itemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = glassSelectedColor,
            selectedTextColor = glassSelectedColor,
            unselectedIconColor = glassUnselectedColor,
            unselectedTextColor = glassUnselectedColor,
        )
    // The item under the pill paints nothing: the pill surface itself is the indicator there.
    val transparentItemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = Color.Transparent,
            selectedTextColor = Color.Transparent,
            unselectedIconColor = Color.Transparent,
            unselectedTextColor = Color.Transparent,
        )
    // The hidden ghost row is recorded into [tabsBackdrop] so the sliding pill refracts
    // accent-tinted tab content. It is invisible on screen (alpha 0, no semantics) and its
    // items are disabled so the alpha-0 row never eats a tap (they are painted accent for
    // the refraction, which is the whole point of the row).
    val ghostItemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = accentColor,
            selectedTextColor = accentColor,
            unselectedIconColor = accentColor,
            unselectedTextColor = accentColor,
            disabledIconColor = accentColor,
            disabledTextColor = accentColor,
        )
    val displayIndex by remember(dampedDragAnimation) {
        derivedStateOf {
            dampedDragAnimation.value.roundToInt().coerceIn(0, items.lastIndex)
        }
    }

    @Composable
    fun sukisuItemLabel(screen: Screens) {
        val nightGlassLabelStyle =
            if (glassIsNight) {
                // White labels over a bright patch of artwork need a soft dark halo.
                LocalTextStyle.current.copy(
                    shadow =
                        Shadow(
                            color = Color.Black.copy(alpha = 0.8f),
                            blurRadius = 10f,
                        ),
                )
            } else {
                LocalTextStyle.current
            }
        Text(
            text = stringResource(screen.titleId),
            maxLines = 1,
            modifier = Modifier.offset(y = (-4).dp),
            fontWeight = FontWeight.Normal,
            style = nightGlassLabelStyle,
        )
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = FloatingNavigationBarMaxWidth)
                    .fillMaxWidth()
                    .height(SukisuLiquidNavBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (glassActive) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                            .graphicsLayer { translationX = panelOffset }
                            .liquidGlassMaterial(
                                backdrop = backdrop,
                                shape = pillShape,
                                role = LiquidGlassRole.NavigationBar,
                                onDrawSurface = { drawRect(containerColor) },
                            )
                            .height(SukisuPillHeight)
                            .padding(horizontal = SukisuItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, screen ->
                        ShortNavigationBarItem(
                            selected = index == currentIndex,
                            onClick = {},
                            enabled = false,
                            colors = ghostItemColors,
                            icon = {
                                Icon(
                                    painter = painterResource(screen.iconIdActive),
                                    contentDescription = null,
                                )
                            },
                            label = if (hideLabels) null else {
                                { sukisuItemLabel(screen) }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        totalWidthPx = coords.size.width.toFloat()
                        val contentWidthPx =
                            totalWidthPx - with(density) { (SukisuItemPadding * 2).toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .then(
                        if (glassActive) {
                            Modifier.liquidGlassMaterial(
                                backdrop = backdrop,
                                shape = pillShape,
                                role = LiquidGlassRole.NavigationBar,
                                highlight = { barHighlight.copy(alpha = 0.75f) },
                                layerBlock = {
                                    val width = size.width.coerceAtLeast(1f)
                                    val s =
                                        lerp(
                                            1f,
                                            1f + 16.dp.toPx() / width,
                                            (if (highlightMotion) dampedDragAnimation.pressProgress else 0f),
                                        )
                                    scaleX = s
                                    scaleY = s
                                },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        },
                    )
                    .then(
                        if (interactiveHighlight != null) {
                            interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                        } else {
                            Modifier
                        },
                    )
                    .then(dampedDragAnimation.modifier)
                    .height(SukisuLiquidNavBarHeight)
                    .padding(SukisuItemPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides tabContentColor,
                ) {
                    items.forEachIndexed { index, screen ->
                        val selected = isSelected(screen)
                        // Velocity kick instead of a snap to 80%: a rapid re-tap retargets the
                        // pop mid-flight instead of jumping the icon back (same as the M3 bar).
                        val iconScale = remember(screen) { Animatable(1f) }
                        LaunchedEffect(selected, disableAnimations, animationScale) {
                            if (disableAnimations || animationScale <= 0f) {
                                iconScale.snapTo(1f)
                            } else if (selected) {
                                iconScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec =
                                        scaleAwareSpring(
                                            scale = animationScale,
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    initialVelocity = -4f,
                                )
                            } else {
                                iconScale.snapTo(1f)
                            }
                        }

                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { activateTab(index) },
                            // Only the glass path draws a replacement glyph/label (the accent ghost
                            // row refracted by the pill); the solid fallback pill draws none, so
                            // hiding the item there would leave an empty slot.
                            colors =
                                if (combinedBackdrop != null && index == displayIndex) {
                                    transparentItemColors
                                } else {
                                    itemColors
                                },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        val scale = tabScaleProvider()
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            icon = {
                                Crossfade(
                                    targetState = selected,
                                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                                    label = "sukisuNavItemIcon",
                                ) { isSelected ->
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                                            ),
                                        contentDescription = null,
                                        modifier =
                                            Modifier.graphicsLayer {
                                                scaleX = iconScale.value
                                                scaleY = iconScale.value
                                            },
                                    )
                                }
                            },
                            label = if (hideLabels) null else {
                                { sukisuItemLabel(screen) }
                            },
                        )
                    }
                }
            }

            if (tabWidthPx > 0f) {
                val tabWidthDp = with(density) { tabWidthPx.toDp() }
                if (combinedBackdrop != null) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = SukisuItemPadding)
                            .graphicsLayer {
                                val progressOffset = dampedDragAnimation.value * tabWidthPx
                                translationX =
                                    if (isLtr) {
                                        progressOffset + panelOffset
                                    } else {
                                        -progressOffset + panelOffset
                                    }
                            }
                            .liquidGlassMaterial(
                                backdrop = combinedBackdrop,
                                shape = pillShape,
                                role = LiquidGlassRole.SelectionLens,
                                progress = { (if (highlightMotion) dampedDragAnimation.pressProgress else 0f) },
                                highlight = { pillHighlight.copy(alpha = (if (highlightMotion) dampedDragAnimation.pressProgress else 0f)) },
                                layerBlock = {
                                    scaleX = if (highlightMotion) dampedDragAnimation.scaleX else 1f
                                    scaleY = if (highlightMotion) dampedDragAnimation.scaleY else 1f
                                    // Velocity squash/stretch: the pill elongates along its travel
                                    // and contracts across it, from the drag animation's velocity.
                                    val velocity = if (highlightMotion) dampedDragAnimation.velocity / 10f else 0f
                                    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                                },
                                onDrawSurface = {
                                    val progress = (if (highlightMotion) dampedDragAnimation.pressProgress else 0f)
                                    drawRect(
                                        color =
                                            if (!glassIsNight) {
                                                Color.Black.copy(alpha = 0.1f)
                                            } else {
                                                Color.White.copy(alpha = 0.1f)
                                            },
                                        alpha = 1f - progress,
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                },
                            )
                            .innerShadow(shape = pillShape) {
                                if ((if (highlightMotion) dampedDragAnimation.pressProgress else 0f) > 0f) {
                                    InnerShadow(
                                        radius = 8.dp * (if (highlightMotion) dampedDragAnimation.pressProgress else 0f),
                                        color = Color.Black.copy(alpha = 0.15f),
                                        alpha = (if (highlightMotion) dampedDragAnimation.pressProgress else 0f),
                                    )
                                } else {
                                    null
                                }
                            }
                            .height(SukisuPillHeight)
                            .width(tabWidthDp),
                    )
                } else {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = SukisuItemPadding)
                            .graphicsLayer {
                                val progressOffset = dampedDragAnimation.value * tabWidthPx
                                translationX =
                                    if (isLtr) {
                                        progressOffset + panelOffset
                                    } else {
                                        -progressOffset + panelOffset
                                    }
                            }
                            .clip(pillShape)
                            .background(accentColor.copy(alpha = 0.15f), pillShape)
                            .height(SukisuPillHeight)
                            .width(tabWidthDp),
                    )
                }
            }
        }
    }
}
