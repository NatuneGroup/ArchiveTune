/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Shared flat slider + volume row for the "Apple Music" player design.
 * Both the player and its lyrics screen draw this one component, so the two surfaces stay in step
 * instead of drifting apart.
 */



package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import moe.rukamori.archivetune.LocalAnimationsDisabled
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppleMusicSeekGrowKey
import moe.rukamori.archivetune.utils.rememberPreference

private val AppleMusicSliderTouchHeight = 26.dp
private val AppleMusicSliderIdleTrackHeight = 6.dp
private val AppleMusicSliderPressedTrackHeight = 10.dp
private val AppleMusicVolumeIconSize = 18.dp

/**
 * Flat Apple-Music-style slider: rounded track, no thumb, tap or drag anywhere to set the value.
 *
 * The track grows while held and settles back when released, which is what gives the control its
 * "soft" feel. The fill is animated so a value arriving from outside (hardware volume keys, or
 * playback progress) glides instead of jumping, but it snaps to the finger while dragging — an
 * animated fill during a drag would lag behind the touch and feel broken.
 */
@Composable
internal fun AppleMusicFlatSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFractionChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
    idleTrackHeight: Dp = AppleMusicSliderIdleTrackHeight,
    pressedTrackHeight: Dp = AppleMusicSliderPressedTrackHeight,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    fillColor: Color = Color.White,
    onFractionChangeStarted: () -> Unit = {},
    onFractionChangeCancelled: () -> Unit = onFractionChangeFinished,
    directPresentation: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    // Optional seek swell: the track fattens and the fill brightens while held. Off keeps the
    // idle height and alpha — a straight, unanimated bar under the finger.
    val (seekGrowEnabled) = rememberPreference(AppleMusicSeekGrowKey, defaultValue = true)

    // The gesture blocks below are keyed on `enabled` alone, so anything they capture would
    // otherwise be frozen at the composition that installed them. The seek bar's callback closes
    // over the track duration, which changes on every song change -- a captured copy would keep
    // converting taps against the previous song's length and seek to the wrong spot. Keying the
    // pointerInput on the callbacks instead would cancel an in-flight drag whenever the position
    // updated, so read them through rememberUpdatedState.
    val animationsDisabled = LocalAnimationsDisabled.current
    val currentOnStart by rememberUpdatedState(onFractionChangeStarted)
    val currentOnCancel by rememberUpdatedState(onFractionChangeCancelled)
    val currentOnFractionChange by rememberUpdatedState(onFractionChange)
    val currentOnFractionChangeFinished by rememberUpdatedState(onFractionChangeFinished)
    DisposableEffect(enabled) {
        onDispose {
            if (dragging) {
                dragging = false
                currentOnCancel()
            }
        }
    }

    val target = (if (dragging && !directPresentation) dragFraction else fraction).coerceIn(0f, 1f)
    val targetHeight = if (dragging && seekGrowEnabled) pressedTrackHeight else idleTrackHeight
    val targetFillAlpha = if (dragging && seekGrowEnabled) 1f else 0.85f
    val animatedFraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging || directPresentation || animationsDisabled) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "appleMusicSliderFraction",
    )
    val trackHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = if (animationsDisabled) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "appleMusicSliderTrackHeight",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = targetFillAlpha,
        animationSpec = if (animationsDisabled) snap() else spring(),
        label = "appleMusicSliderFillAlpha",
    )

    Box(
        modifier =
            modifier
                .height(AppleMusicSliderTouchHeight)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
                    if (enabled) setProgress { value ->
                        currentOnStart()
                        currentOnFractionChange(value.coerceIn(0f, 1f))
                        currentOnFractionChangeFinished()
                        true
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        currentOnStart()
                        val tapped = (offset.x / size.width).coerceIn(0f, 1f)
                        currentOnFractionChange(tapped)
                        currentOnFractionChangeFinished()
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            currentOnStart()
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            currentOnFractionChange(dragFraction)
                        },
                        onDragEnd = {
                            dragging = false
                            currentOnFractionChangeFinished()
                        },
                        onDragCancel = {
                            dragging = false
                            currentOnCancel()
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            currentOnFractionChange(dragFraction)
                        },
                    )
                }.drawBehind {
                    val height = (if (animationsDisabled) targetHeight else trackHeight).toPx()
                    val top = (size.height - height) / 2f
                    val radius = CornerRadius(height / 2f)
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, height),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = fillColor.copy(alpha = fillColor.alpha * if (animationsDisabled) targetFillAlpha else fillAlpha),
                        topLeft = Offset(0f, top),
                        size = Size(size.width * (if (dragging || directPresentation || animationsDisabled) target else animatedFraction), height),
                        cornerRadius = radius,
                    )
                },
    )
}

/**
 * Volume row used by both the Apple Music player and its lyrics screen.
 *
 * The left glyph switches to the muted speaker at zero so silence is visible at a glance.
 */
@Composable
internal fun AppleMusicVolumeRow(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White.copy(alpha = 0.55f),
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    fillColor: Color = Color.White,
) {
    val controller = LocalDeviceMusicVolumeController.current
    val displayedVolume = if (controller != null) volumeDisplayFraction(controller) else volume
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        val muted = displayedVolume <= 0.001f
        Icon(
            painter =
                painterResource(
                    if (muted) R.drawable.player_volume_off else R.drawable.player_volume_min,
                ),
            contentDescription =
                stringResource(
                    if (muted) R.string.muted else R.string.minimum_volume,
                ),
            tint = iconTint,
            modifier = Modifier.size(AppleMusicVolumeIconSize),
        )
        Spacer(Modifier.width(12.dp))
        AppleMusicFlatSlider(
            fraction = displayedVolume,
            onFractionChange = onVolumeChange,
            onFractionChangeStarted = { controller?.startInteraction() },
            onFractionChangeFinished = { controller?.finishInteraction() },
            onFractionChangeCancelled = { controller?.cancelInteraction() },
            directPresentation = controller != null,
            trackColor = trackColor,
            fillColor = fillColor,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.player_volume_up),
            contentDescription = stringResource(R.string.maximum_volume),
            tint = iconTint,
            modifier = Modifier.size(AppleMusicVolumeIconSize),
        )
    }
}
