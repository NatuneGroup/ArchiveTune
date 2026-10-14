/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Support primitives for the SukiSU liquid glass navigation bar, after SukiSU-Ultra
 * (https://github.com/sukisu-ultra/sukisu-ultra), which adapts them from
 * compose-miuix-ui / miuix-blur (Apache 2.0) on top of the kyant backdrop library.
 */

package moe.rukamori.archivetune.ui.component.sukisu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRuntimeShaderSupported
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Static shared lighting avoids per-surface sensors and background/0x sensor work. */
@Composable
fun rememberSukisuSpecularHighlight(extraDegrees: Float): State<Highlight> =
    remember(extraDegrees) { mutableStateOf(sukisuSpecularHighlight(90f - extraDegrees)) }

private fun sukisuSpecularHighlight(angleDegrees: Float): Highlight =
    Highlight(
        width = 1.dp,
        alpha = 1f,
        style =
            HighlightStyle.Default(
                color = Color.White.copy(alpha = 0.12f),
                blendMode = BlendMode.Plus,
                angle = angleDegrees,
                falloff = 1f,
            ),
    )

/**
 * A press spotlight for a glass surface: the spot follows the finger while it is down and springs
 * back to rest on release. The AGSL radial shader is used from API 33; below it (or where runtime
 * shaders are unsupported) the same spotlight is drawn with a radial-gradient brush. Attach
 * [modifier] for the drawing and [gestureModifier] to feed it; both are safe to skip on
 * non-glass fallbacks, where the spotlight has nothing to shine on.
 */
class SukisuInteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimationSpec = spring<Float>(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private var disposed = false
    private var pressJob: Job? = null
    private var positionJob: Job? = null

    fun cancel() {
        disposed = true
        pressJob?.cancel()
        positionJob?.cancel()
    }

    private fun press(target: Float) {
        if (disposed) return
        pressJob?.cancel()
        pressJob = animationScope.launch {
            pressProgressAnimation.animateTo(target, pressProgressAnimationSpec)
        }
    }

    private fun move(target: Offset, release: Boolean = false) {
        if (disposed) return
        positionJob?.cancel()
        positionJob = animationScope.launch {
            if (release) positionAnimation.animateTo(target, positionAnimationSpec)
            else positionAnimation.snapTo(target)
        }
    }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                drawRect(
                    Color.White.copy(0.06f * progress),
                    blendMode = BlendMode.Plus,
                )
                val position = position(size, positionAnimation.value)
                val spotX = position.x.fastCoerceIn(0f, size.width)
                val spotY = position.y.fastCoerceIn(0f, size.height)
                val spotColor = Color.White.copy(0.12f * progress)
                val radius = (size.minDimension * 1.2f).coerceAtLeast(1f)
                if (shader != null) {
                    shader.apply {
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", spotColor)
                        setFloatUniform("radius", radius)
                        setFloatUniform("position", spotX, spotY)
                    }
                    drawRect(
                        ShaderBrush(shader.asComposeShader()),
                        blendMode = BlendMode.Plus,
                    )
                } else {
                    drawRect(
                        Brush.radialGradient(
                            0f to spotColor,
                            0.5f to spotColor,
                            1f to Color.Transparent,
                            center = Offset(spotX, spotY),
                            radius = radius,
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            drawContent()
        }

    // Observe Initial pass only; never consume changes or install clickable/focus semantics.
    val gestureModifier: Modifier =
        Modifier.pointerInput(this) {
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(false, PointerEventPass.Initial)
                    startPosition = down.position
                    move(startPosition)
                    press(1f)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer != null && pointer.pressed) move(pointer.position)
                        val held = pointer?.pressed == true
                    } while (held)
                    press(0f)
                    move(startPosition, release = true)
                }
            } finally {
                press(0f)
            }
        }

    private val shader: RuntimeShader? =
        if (!isRuntimeShaderSupported()) {
            null
        } else {
            RuntimeShader(
                """
                uniform float2 size;
                layout(color) uniform half4 color;
                uniform float radius;
                uniform float2 position;

                half4 main(float2 coord) {
                    float dist = distance(coord, position);
                    float intensity = smoothstep(radius, radius * 0.5, dist);
                    return color * intensity;
                }
                """,
            )
        }
}
