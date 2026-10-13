/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * Bitchord player style — scrubber.
 *
 * Ported verbatim from BitChord (https://github.com/kushagrasinghx/BitChord),
 * app/src/main/java/com/music/bitchord/ui/player/ThinSlider.kt (package renamed).
 * The `mixing` / `transitionWindow` parameters (BitChord's Automix planner
 * markers) drive the crossfade/automix morph: players wire `mixing` to
 * PlayerConnection.isMixing and automix passes its planned transition window.
 * The sheen half of the morph is shared with Flamingo's track
 * (ui/player/MixTrackSheen.kt); the rest belongs exclusively to the Bitchord
 * player style, per the self-containment rule for player styles.
 */

package moe.rukamori.archivetune.ui.player.bitchord

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.ui.player.MixTrackSheen
import moe.rukamori.archivetune.ui.player.rememberMixFillFactor

/**
 * Apple Music's scrubber: a hairline capsule with no thumb knob, which
 * thickens under your finger and settles back when you let go. Material's
 * Slider can't be shaped like this — it always draws a thumb and a tall
 * track — so this is drawn directly.
 */
@Composable
fun ThinSlider(
    /**
     * Called from the draw scope, never read during composition. The played width is the only thing
     * the position affects here, so a tick repaints this slider instead of recomposing it.
     */
    valueProvider: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * Sends a sheen travelling along the played portion, and collapses the played
     * fill, for as long as it is true — a transition that genuinely mixed. Players
     * pass PlayerConnection.isMixing: the plain crossfade flag, or the automix
     * SmartFade's.
     */
    mixing: Boolean = false,
    /**
     * Span of the track, as fractions of its duration, that the next Automix
     * transition is planned to occupy. Drawn as a brighter stretch of the
     * unplayed bar so the mix is visible before it arrives.
     */
    transitionWindow: ClosedFloatingPointRange<Float>? = null,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
    /** Halfway between the two track colours: visible against unplayed, invisible under played. */
    markerColor: Color = Color.White.copy(alpha = 0.5f),
) {
    // The gesture block runs under a Unit-keyed pointerInput, so it keeps the
    // FIRST composition's lambdas. Re-read the latest here or a drag-seek
    // lands on a stale player after the service swaps the session player
    // (automix/crossfade promotion): the bar moves, nothing seeks.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        targetValue = if (dragging) activeHeight else idleHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "sliderHeight",
    )
    // While a mix blends, the played fill collapses to nothing (and grows back when the
    // mix hands over) — the sheen below takes over as the position signal, since the
    // outgoing and incoming playheads can't both be the played width.
    val fillFactor = rememberMixFillFactor(mixing, "sliderFillFactor")

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Generous invisible touch target — the visible bar is only ~7dp.
            .height(activeHeight + 22.dp)
            // One gesture loop for both taps and drags. Two separate detectors
            // — a drag one plus a tap one — meant taps never landed: the drag
            // detector took the pointer and a tap has no drag to report.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    currentOnValueChange((down.position.x / size.width).coerceIn(0f, 1f))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        if (pointer.positionChanged()) {
                            currentOnValueChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                            pointer.consume()
                        }
                    }

                    dragging = false
                    currentOnValueChangeFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(color = inactiveColor, cornerRadius = radius)
            // Between the two track colours, and drawn *under* the played fill:
            // once the playhead reaches the window the transition is no longer
            // upcoming, and the ordinary progress colour taking it over is what
            // says so.
            transitionWindow?.let { window ->
                val from = size.width * window.start.coerceIn(0f, 1f)
                val to = size.width * window.endInclusive.coerceIn(0f, 1f)
                if (to > from) {
                    drawRoundRect(
                        color = markerColor,
                        topLeft = Offset(from, 0f),
                        size = Size(to - from, size.height),
                        cornerRadius = radius,
                    )
                }
            }
            val filled = size.width * valueProvider().coerceIn(0f, 1f) * fillFactor
            if (filled > 0f) {
                drawRoundRect(
                    color = activeColor,
                    // The minimum fill shrinks with fillFactor so the collapse can reach
                    // zero instead of parking a nub at the left edge.
                    size = Size(
                        filled.coerceAtLeast(size.height * fillFactor).coerceAtMost(size.width),
                        size.height,
                    ),
                    cornerRadius = radius,
                )
            }
        }
        MixTrackSheen(visible = mixing, color = activeColor, height = height)
    }
}
