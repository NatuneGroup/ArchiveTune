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

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.ui.component.inspectDragGestures
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

// Below this squared gravity magnitude the reading is noise, not a direction: fall back to the
// device lying flat (gravity pointing up through the screen), where the specular has no bias.
private const val SukisuGravityDirThresholdSq = 0.01f

// Quantize the specular angle to 3-degree steps so the highlight does not shimmer with every
// sensor jitter — it snaps between 120 discrete directions instead of rotating continuously.
private const val SukisuGravityAngleStepRad = (3.0 * PI / 180.0).toFloat()

private class SukisuDeviceTilt internal constructor(
    val gravityX: Float,
    val gravityY: Float,
)

@Composable
private fun rememberSukisuDeviceTilt(): State<SukisuDeviceTilt> {
    val context = LocalContext.current
    val tilt =
        remember {
            mutableStateOf(SukisuDeviceTilt(gravityX = 0f, gravityY = -1f))
        }
    DisposableEffect(context) {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor =
            sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || sensor == null) {
            onDispose { }
        } else {
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.size < 2) return
                        tilt.value =
                            SukisuDeviceTilt(
                                gravityX = event.values[0],
                                gravityY = event.values[1],
                            )
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {}
                }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }
    return tilt
}

@Composable
private fun rememberSukisuGravityAngleRad(): State<Float> {
    val tilt = rememberSukisuDeviceTilt()
    return remember(tilt) {
        derivedStateOf {
            val magnitudeSquared =
                tilt.value.gravityX * tilt.value.gravityX + tilt.value.gravityY * tilt.value.gravityY
            if (magnitudeSquared > SukisuGravityDirThresholdSq) {
                val raw = atan2(tilt.value.gravityY, tilt.value.gravityX)
                (raw / SukisuGravityAngleStepRad).roundToInt() * SukisuGravityAngleStepRad
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

/**
 * The tilt-driven specular highlight: the device's gravity direction, quantized to 3-degree steps
 * and biased by [extraDegrees] (bar and pill are lit from different directions), as a [Highlight]
 * that the glass draw reads per frame.
 */
@Composable
fun rememberSukisuSpecularHighlight(extraDegrees: Float): State<Highlight> {
    val gravityAngleRad = rememberSukisuGravityAngleRad()
    return remember(gravityAngleRad, extraDegrees) {
        derivedStateOf {
            val angleDegrees =
                -(Math.toDegrees(gravityAngleRad.value.toDouble())).toFloat() - extraDegrees
            sukisuSpecularHighlight(angleDegrees)
        }
    }
}

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

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
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
