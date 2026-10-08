/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Drift for the heavily blurred artwork behind lyrics, shared by the Apple-Music-style player's
 * inline backdrop, the standalone lyrics screen and every other "moving blur" style, so all of them
 * move identically.
 *
 * The anchor drifts between random targets spread over the WHOLE reachable disc — never a narrow
 * ring around the centre — so the colour mass explores every part of the screen: it can sink into
 * the lower half, travel past the top edge and sweep back in. Each leg is cosine-eased, so velocity
 * is zero at every waypoint and a direction change never flickers or snaps. [blurBackdropFootprint]
 * sizes the backdrop so it keeps covering the display at any offset, which is what makes the motion
 * gapless.
 */
internal class BlurWanderDrift(
    private val random: Random = Random.Default,
    private val maxDriftDp: Float = DefaultWanderRadiusDp,
) {
    private val xState = mutableFloatStateOf(0f)
    private val yState = mutableFloatStateOf(0f)
    private val rotationState = mutableFloatStateOf(0f)

    /** Horizontal offset in dp, in `-maxDriftDp..maxDriftDp`. */
    val xDp: FloatState get() = xState

    /** Vertical offset in dp, in `-maxDriftDp..maxDriftDp`. */
    val yDp: FloatState get() = yState

    /**
     * Rotation of the backdrop about its own centre, in degrees.
     *
     * Unbounded on purpose. It is an angle, so it wraps for free, and letting it
     * accumulate is what lets a colour keep travelling the same way past a
     * half-turn instead of being tugged back toward a nominal zero.
     */
    val rotationDeg: FloatState get() = rotationState

    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f
    private var fromRotation = 0f
    private var toRotation = 0f
    private var legDurationMs = 0f
    private var legElapsedMs = 0f

    init {
        startNextLeg()
    }

    /**
     * Advances the walk by [deltaMs] of wall time. Legs roll over inside the
     * same call, so a dropped frame is caught up rather than skipped.
     */
    fun advance(deltaMs: Float) {
        if (deltaMs <= 0f) return
        legElapsedMs += deltaMs
        while (legElapsedMs >= legDurationMs) {
            legElapsedMs -= legDurationMs
            startNextLeg()
        }
        // Raised cosine: zero velocity at both ends of the leg.
        val t = legElapsedMs / legDurationMs
        val eased = 0.5f - 0.5f * cos(PI.toFloat() * t)
        xState.floatValue = fromX + (toX - fromX) * eased
        yState.floatValue = fromY + (toY - fromY) * eased
        rotationState.floatValue = fromRotation + (toRotation - fromRotation) * eased
    }

    private fun startNextLeg() {
        fromX = toX
        fromY = toY
        fromRotation = toRotation

        // Uniform-area sampling over the full reachable disc: sqrt(u) spreads targets evenly by area
        // (a plain radius would pile them up near the centre) and lets an offset reach the disc edge,
        // so the colour mass regularly crosses the display bounds on its way to the far side.
        val radius = maxDriftDp * sqrt(random.nextFloat())
        val angle = random.nextFloat() * TwoPi
        toX = cos(angle) * radius
        toY = sin(angle) * radius
        val rotationSign = if (random.nextBoolean()) 1f else -1f
        val rotationSpan =
            MinLegRotationDegrees + random.nextFloat() * (MaxLegRotationDegrees - MinLegRotationDegrees)
        toRotation = fromRotation + rotationSign * rotationSpan

        // Long traversals stay slow: the leg duration is derived from the
        // distance (not clamped down to a sprint), so a full-screen sweep
        // takes its time exactly like the Apple Music lyrics backdrop.
        val distance = hypot(toX - fromX, toY - fromY)
        legDurationMs =
            (distance / WanderSpeedDpPerSecond * 1000f)
                .coerceIn(MinLegDurationMs, MaxLegDurationMs)
    }

    internal companion object {
        /** Legacy fixed amplitude, used only when no screen size is known. */
        const val DefaultWanderRadiusDp = 120f

        /** Average travel speed. Deliberately slow — this sits behind lyrics. */
        private const val WanderSpeedDpPerSecond = 26f

        private const val MinLegDurationMs = 6_000f

        private const val MaxLegDurationMs = 26_000f

        private const val MinLegRotationDegrees = 18f

        private const val MaxLegRotationDegrees = 55f

        private const val TwoPi = (2.0 * PI).toFloat()
    }
}

/**
 * Footprint the heavily blurred backdrop layer has to occupy so that [BlurWanderDrift]'s rotation
 * can never swing one of the layer's own corners into view.
 */

/**
 * Screen-proportional wander amplitude shared by every player style, so the moving blur behaves
 * identically everywhere: the anchor may reach ~85% of the half-diagonal away from the centre in
 * any direction, letting colour features traverse the whole display and briefly cross its bounds
 * before the (always-covering) backdrop sweeps them back in.
 *
 * The amplitude is what the backdrop layer has to grow to cover (see [blurBackdropFootprint]), so it
 * is a real GPU-memory cost. The standalone lyrics screen is the one that pays it: there the rest
 * scale and the drift scale are both 2.4, so the covering footprint of its 64dp-blurred offscreen
 * layer goes from ~527dp to ~787dp on a 411x914dp screen — an extra ~260dp square of ARGB_8888
 * raster, on the order of 10MB. The Apple Music player and SpatialFlow keep their previous footprint
 * because their resting scale term dominates that maths instead. Capping the amplitude (for example
 * min(half-diagonal * 0.85, 240dp)) would buy the memory back, but it would also shorten the wander
 * visibly on every normal phone, so the cost stands unless a low-RAM device shows jank here.
 */
internal fun movingBlurWanderMaxDriftDp(
    width: Dp,
    height: Dp,
): Float {
    val w = width.value
    val h = height.value
    if (w <= 0f || h <= 0f) return BlurWanderDrift.DefaultWanderRadiusDp
    return (hypot(w, h) / 2f) * 0.85f
}
internal fun blurBackdropFootprint(
    width: Dp,
    height: Dp,
    restScale: Float,
    driftScale: Float,
    maxDriftDp: Float = BlurWanderDrift.DefaultWanderRadiusDp,
): DpSize {
    val w = width.value
    val h = height.value
    if (w <= 0f || h <= 0f || restScale <= 0f || driftScale <= 0f) return DpSize(width, height)

    val corner = hypot(w, h) / 2f
    // Callers ramp scale, translation and rotation off one progress value, so the requirement along
    // the ramp is `2 * (corner + drift * p) / (restScale + (driftScale - restScale) * p)`. That is
    // a Mobius function of p, so it has no interior extremum and the worst case is an endpoint:
    // either resting (no drift, but the smallest scale) or fully drifting (largest scale, but the
    // corner has moved out by the whole wander radius).
    val requiredAtRest = 2f * corner / restScale
    val requiredAtFullDrift = 2f * (corner + maxDriftDp) / driftScale
    val required = max(requiredAtRest, requiredAtFullDrift) * BlurBackdropCoverSafety

    return DpSize(max(w, required).dp, max(h, required).dp)
}

/**
 * A little headroom on [blurBackdropFootprint]'s result. The derivation is exact, so this only
 * absorbs rounding between the dp maths here and the pixel maths the layer is actually rasterised
 * with — 2% is a couple of dp on a phone.
 */
private const val BlurBackdropCoverSafety = 1.02f

/** Remembers a [BlurWanderDrift] and advances it from the frame clock while [active]. */
@Composable
internal fun rememberBlurWanderDrift(
    active: Boolean,
    maxDriftDp: Float = BlurWanderDrift.DefaultWanderRadiusDp,
): BlurWanderDrift {
    val drift = remember(maxDriftDp) { BlurWanderDrift(maxDriftDp = maxDriftDp) }
    // Keyed on maxDriftDp as well: a size change (rotation, fold/unfold,
    // split-screen) recreates the drift instance above, and the animation
    // loop must follow the new instance or the wander freezes at (0, 0).
    LaunchedEffect(active, maxDriftDp) {
        if (!active) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    drift.advance((frameTimeNanos - lastFrameNanos) / 1_000_000f)
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }
    return drift
}
