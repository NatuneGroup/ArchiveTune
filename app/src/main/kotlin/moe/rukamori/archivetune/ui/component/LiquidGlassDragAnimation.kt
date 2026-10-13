/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Drag-to-slide animation for the Liquid Glass nav bar's sliding pill.
 * Adapted from SukiSU-Ultra's `DampedDragAnimation` + `inspectDragGestures`
 * (which themselves adapt from compose-miuix-ui), trimmed to ArchiveTune's
 * needs.
 *
 * Behavior (matches SukiSU-Ultra's FloatingBottomBar):
 *   - Tap a tab → animateToValue(index) springs the pill to that position,
 *     AND `press()`/`release()` runs so the pill briefly scales up + deepens
 *     its lens refraction (transient press feedback even on a tap).
 *   - Hold + drag → the pill follows the finger. `press()` runs (pill scales
 *     up + lens deepens + inner shadow appears + the bar gets a subtle
 *     horizontal `panelOffset` rubber-band). `velocity` is tracked so the
 *     pill stretches horizontally when flung.
 *   - Release → snap to the nearest integer index via `animateToValue`,
 *     then `release()` springs scale/press back to 1.0/0.
 *
 * The gesture is observed via `PointerEventPass.Initial` (see
 * [inspectDragGestures]) so it coexists with the tab items' own `clickable`
 * handlers — a tap still fires the tab's onClick, a drag does not (because
 * `clickable` ignores touches that moved significantly).
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * Suspends until the next frame. Used by [LiquidGlassDragAnimation.release] to
 * defer the press-release animation until AFTER the snap-to-target animation
 * has had a chance to start (so the pill doesn't shrink before it slides).
 */
private suspend fun awaitFrame() {
    withFrameNanos { }
}

/**
 * Observe-only drag detector. Mirrors SukiSU-Ultra's `inspectDragGestures`: - Uses
 * `PointerEventPass.Initial` so this fires BEFORE children's Main-pass handlers. This is what lets
 * the drag detector coexist with the tab items' own `clickable` (a tap fires both, a drag only
 * fires here).
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent =
            drag(
                pointerId = initialDown.id,
                onDrag = { onDrag(it, it.positionChange()) }
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}

/**
 * Drives the Liquid Glass nav bar's sliding pill. Holds five `Animatable` channels (position,
 * velocity, press-progress, scale-X, scale-Y) and exposes a single [modifier] that detects drag
 * gestures and feeds them into the animation channels.
 */
class LiquidGlassDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: LiquidGlassDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: LiquidGlassDragAnimation.() -> Unit,
    val onDrag: LiquidGlassDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        var dragActive = false
        inspectDragGestures(
            onDragStart = { down ->
                if (canDrag(down.position)) {
                    dragActive = true
                    onDragStarted(down.position)
                    press()
                } else {
                    dragActive = false
                }
            },
            onDragEnd = {
                if (dragActive) {
                    onDragStopped()
                    release()
                }
                dragActive = false
            },
            onDragCancel = {
                if (dragActive) {
                    onDragStopped()
                    release()
                }
                dragActive = false
            },
        ) { _, dragAmount ->
            // No more isInside/wasInside check — once the drag has started
            // (dragActive == true), the pill follows the finger regardless
            // of position. The user can drag from anywhere on the screen
            // (including the top) as long as their finger stays down.
            if (dragActive) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        // The squash/stretch driver reads the value animation's own velocity. It advances in
        // play time — the same scaled clock the pill's motion runs on — so the stretch stays
        // honest at every animator scale, where a wall-clock tracker would exaggerate it below
        // 1x and flatten it above.
        val targetVelocity = valueAnimation.velocity / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
