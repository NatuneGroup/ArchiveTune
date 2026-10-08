/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © 4nx3b — github.com/4nx3b (BitChord bar re-implementation)
 * Portions © vossgraves — github.com/vossgraves
 *
 * The BitChord navigation bar: a frosted 50%-radius pill with a travelling
 * selection pill that stretches while moving, icon bounce, color crossfade
 * and a horizontal drag-to-switch gesture. Adapted from BitChord's
 * FloatingBottomBar (https://github.com/kushagrasinghx/BitChord, GPL-3.0)
 * via 4nx3b/ArchiveTune's re-implementation, over this fork's Screens +
 * frosted backdrop infrastructure. This fork has no throttled layer
 * backdrop, so only the frosted path is ported.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private val BitChordBarGutter = 16.dp
private val BitChordPillInset = 6.dp
private val BitChordTabSpacing = 6.dp
private val BitChordTabIconSize = 25.dp
private val BitChordTabIconLabelGap = 2.dp
private val BitChordGlassEdgeWidth = 0.5.dp
private const val BitChordStretch = 0.16f
private const val BitChordSquash = 0.5f
private const val BitChordDragStrideThreshold = 0.35f
private val BitChordGlassSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)
private val BitChordIconSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

@Composable
fun BitChordNavBar(
    barHeight: Dp,
    selectedRoute: String?,
    onRouteSelected: (String) -> Unit,
    itemCount: Int,
    itemRoute: (Int) -> String,
    itemLabel: @Composable (Int) -> String,
    itemIcon: @Composable (Int) -> ImageVector,
    modifier: Modifier = Modifier,
    frostedBackdrop: NavigationBarBackdrop? = null,
    frostedBlurRadiusPx: Float = 60f,
    frostedOverlayAlpha: Float = 0.30f,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pillShape = RoundedCornerShape(percent = 50)

    // Synced in an effect (not a remember key) so an in-flight drag offset is
    // not snapped back by a route change arriving mid-gesture.
    var selectedIndex by remember {
        mutableIntStateOf(
            (0 until itemCount).firstOrNull { itemRoute(it) == selectedRoute } ?: 0,
        )
    }
    LaunchedEffect(selectedRoute, itemCount) {
        val index = (0 until itemCount).firstOrNull { itemRoute(it) == selectedRoute } ?: 0
        if (index != selectedIndex) selectedIndex = index
    }
    val dragOffset = remember { mutableFloatStateOf(0f) }

    val tabSpacingPx = with(density) { BitChordTabSpacing.toPx() }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    val tabStepPx =
        if (itemCount > 0 && rowWidthPx > 0f) {
            (rowWidthPx + tabSpacingPx) / itemCount
        } else {
            0f
        }
    val tabWidthDp =
        if (tabStepPx > 0f) {
            with(density) { ((rowWidthPx - tabSpacingPx * (itemCount - 1)) / itemCount).toDp() }
        } else {
            0.dp
        }
    val animatedPillOffset by
        animateFloatAsState(
            targetValue = selectedIndex * tabStepPx,
            animationSpec = BitChordGlassSpring,
            label = "BitChordNavPillOffset",
        )
    val pillTargetPx = selectedIndex * tabStepPx + dragOffset.floatValue
    val pillLag =
        if (tabStepPx > 0f) {
            (abs(pillTargetPx - animatedPillOffset) / tabStepPx).coerceIn(0f, 1f)
        } else {
            0f
        }

    fun selectIndex(index: Int) {
        val clamped = index.coerceIn(0, itemCount - 1)
        if (clamped != selectedIndex) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedIndex = clamped
            onRouteSelected(itemRoute(clamped))
        }
    }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(barHeight)
                .padding(horizontal = BitChordBarGutter)
                .padding(bottom = 2.dp)
                .clip(pillShape)
                .borderGuard(),
        shape = pillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = BitChordPillInset, vertical = BitChordPillInset)
                    .onGloballyPositioned { rowWidthPx = it.size.width.toFloat() },
        ) {
            if (frostedBackdrop != null) {
                var barPositionInRoot by remember { mutableStateOf(Offset.Zero) }
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                renderEffect =
                                    BlurEffect(
                                        radiusX = frostedBlurRadiusPx,
                                        radiusY = frostedBlurRadiusPx,
                                        edgeTreatment = TileMode.Clamp,
                                    )
                                alpha = frostedOverlayAlpha
                                clip = true
                            }.drawBehind {
                                val offset = frostedBackdrop.contentOffsetInRoot - barPositionInRoot
                                translate(offset.x, offset.y) {
                                    runCatching { drawLayer(frostedBackdrop.layer) }
                                }
                            }.onGloballyPositioned { coordinates ->
                                barPositionInRoot = coordinates.positionInRoot()
                            },
                )
            }

            if (tabWidthDp > 0.dp) {
                Box(
                    modifier =
                        Modifier
                            .offset { IntOffset(pillTargetPx.roundToInt().coerceAtLeast(0), 0) }
                            .width(tabWidthDp)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = 1f + pillLag * BitChordStretch
                                scaleY = 1f - pillLag * BitChordStretch * BitChordSquash
                            }
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(itemCount) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDragEnd = {
                                    if (tabStepPx > 0f) {
                                        val ratio = totalDrag / tabStepPx
                                        if (abs(ratio) > BitChordDragStrideThreshold) {
                                            val shift = maxOf(1, abs(ratio).roundToInt())

                                            selectIndex(selectedIndex - shift * kotlin.math.sign(ratio).toInt())
                                        }
                                    }
                                    totalDrag = 0f
                                    dragOffset.floatValue = 0f
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                                if (tabStepPx > 0f) {
                                    dragOffset.floatValue =
                                        (dragOffset.floatValue + dragAmount).let { raw ->
                                            val resisted =
                                                when {
                                                    selectedIndex == 0 && raw > 0f -> raw * 0.25f
                                                    selectedIndex == itemCount - 1 && raw < 0f -> raw * 0.25f
                                                    else -> raw
                                                }
                                            resisted.coerceIn(-tabStepPx * 1.5f, tabStepPx * 1.5f)
                                        }
                                }
                            }
                        },
                horizontalArrangement = Arrangement.spacedBy(BitChordTabSpacing),
            ) {
                for (index in 0 until itemCount) {
                    BitChordNavBarItem(
                        label = itemLabel(index),
                        icon = itemIcon(index),
                        selected = index == selectedIndex,
                        onSelect = { selectIndex(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun Modifier.borderGuard(): Modifier =
    this.then(
        Modifier.drawBehind {
            val stroke = BitChordGlassEdgeWidth.toPx()
            val outline =
                RoundedCornerShape(percent = 50).createOutline(size, layoutDirection, this)
            drawOutline(outline, Color.White.copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        },
    )

@Composable
private fun BitChordNavBarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconScale by
        animateFloatAsState(
            targetValue = if (selected) 1.08f else 1f,
            animationSpec = BitChordIconSpring,
            label = "BitChordNavIconScale",
        )
    val tint by
        animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(durationMillis = 200),
            label = "BitChordNavTint",
        )
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSelect,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier =
                Modifier
                    .size(BitChordTabIconSize)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
        )
        Spacer(Modifier.height(BitChordTabIconLabelGap))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

val BitChordHomeIcon: ImageVector by lazy {
    ImageVector.Builder(name = "BitChordHome", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(3.5f, 11.2f)
            lineTo(12f, 4.0f)
            lineTo(20.5f, 11.2f)
            moveTo(5.8f, 9.8f)
            lineTo(5.8f, 19.6f)
            lineTo(18.2f, 19.6f)
            lineTo(18.2f, 9.8f)
            moveTo(9.8f, 19.6f)
            lineTo(9.8f, 14.0f)
            lineTo(14.2f, 14.0f)
            lineTo(14.2f, 19.6f)
        }
    }.build()
}

val BitChordLibraryIcon: ImageVector by lazy {
    ImageVector.Builder(name = "BitChordLibrary", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
            moveTo(9.0f, 3.4f)
            lineTo(18.6f, 3.4f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 5.8f)
            lineTo(21.0f, 15.4f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18.6f, 17.8f)
            lineTo(9.0f, 17.8f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.6f, 15.4f)
            lineTo(6.6f, 5.8f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 3.4f)
            close()
        }
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(6.6f, 20.6f)
            lineTo(16.2f, 20.6f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18.6f, 18.2f)
        }
    }.build()
}

val BitChordSearchIcon: ImageVector by lazy {
    ImageVector.Builder(name = "BitChordSearch", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(15.9f, 15.9f)
            lineTo(20.4f, 20.4f)
        }
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(10.6f, 4.4f)
            arcTo(6.6f, 6.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.2f, 11.0f)
            arcTo(6.6f, 6.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10.6f, 17.6f)
            arcTo(6.6f, 6.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.0f, 11.0f)
            arcTo(6.6f, 6.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10.6f, 4.4f)
            close()
        }
    }.build()
}
