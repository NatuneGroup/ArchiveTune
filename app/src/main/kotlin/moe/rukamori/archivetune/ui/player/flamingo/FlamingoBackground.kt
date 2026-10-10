/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Ported from Flamingo (yos.music.player) ui/widgets/effects/YosFloatingLightView.kt — GPLv3,
 * https://github.com/shouryadixitisverycool/Flamingo
 *
 * The color behind the artwork: the cover is center-cropped, downscaled,
 * saturated x3, darkened with two overlay fills, then stack-blurred (radius 25).
 * With the background effect (moving blur) enabled the backdrop drifts with the
 * pre-redesign BlurWanderDrift floating-blur animation (44dp/s legs, rotation),
 * rendered from an oversized footprint so the screen edges are always covered;
 * when leaving the lyrics page an identical copy is laid over it with a
 * 0x33000000 Overlay tint fading to alpha 0.618.
 */

package moe.rukamori.archivetune.ui.player.flamingo

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.ui.player.blurBackdropFootprint
import moe.rukamori.archivetune.ui.player.movingBlurWanderMaxDriftDp
import moe.rukamori.archivetune.ui.player.rememberBlurWanderDrift

object FlamingoPage {
    const val Album = "Album"
    const val PlayingList = "PlayingList"
    const val Lyric = "Lyric"
}

private const val FlamingoMovingBlurSpeedDpPerSecond = 44f
private const val FlamingoMovingBlurMinLegMs = 3_500f
private const val FlamingoMovingBlurMaxLegMs = 11_000f
private const val FlamingoMovingBlurScale = 1.35f

@Composable
fun FlamingoFloatingLight(
    modifier: Modifier,
    albumUrl: () -> String?,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    backgroundEffect: Boolean,
) {
    val context = LocalContext.current

    val processedBitmap by produceState<android.graphics.Bitmap?>(null, albumUrl(), backgroundEffect) {
        val url = albumUrl()
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val source: Bitmap = result.image.toBitmap()
                    val compressed = FlamingoBitmapResolver.bitmapCompress(
                        source,
                        px = if (backgroundEffect) 96 else 32,
                    )
                    flamingoImageResolve(compressed)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    FlamingoWrapper {
        val lossEffect = remember("FlamingoFloatingLight_lossEffect") {
            androidx.compose.runtime.derivedStateOf {
                nowPage() != FlamingoPage.Lyric
            }
        }

        val useBackground = remember("FlamingoFloatingLight_useBackground") {
            androidx.compose.runtime.derivedStateOf {
                albumUrl() == null
            }
        }

        val bitmap = processedBitmap

        val baseBlackBackground = Modifier.drawWithCache {
            onDrawBehind {
                if (useBackground.value) {
                    drawRect(Color.Black)
                }
            }
        }

        if (bitmap != null) {
            if (backgroundEffect) {
                FlamingoMovingBlurImage(
                    bitmap = bitmap,
                    isPlaying = isPlaying,
                    modifier = modifier.then(baseBlackBackground),
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.then(baseBlackBackground),
                )
            }
        } else {
            Box(
                modifier = modifier.drawWithCache {
                    onDrawBehind {
                        drawRect(Color.Black)
                    }
                },
            )
        }

        FlamingoWrapper {
            val alpha = animateFloatAsState(
                targetValue = if (lossEffect.value) 0.618f else 0f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.tint(Color(0x33000000), BlendMode.Overlay),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.alpha = alpha.value
                        },
                )
            }
        }
    }
}

@Composable
private fun FlamingoMovingBlurImage(
    bitmap: android.graphics.Bitmap,
    isPlaying: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val wanderMaxDrift = movingBlurWanderMaxDriftDp(maxWidth, maxHeight)
        val blurWander = rememberBlurWanderDrift(
            active = isPlaying(),
            maxDriftDp = wanderMaxDrift,
            speedDpPerSecond = FlamingoMovingBlurSpeedDpPerSecond,
            minLegDurationMs = FlamingoMovingBlurMinLegMs,
            maxLegDurationMs = FlamingoMovingBlurMaxLegMs,
        )

        val driftFootprint =
            remember(maxWidth, maxHeight) {
                blurBackdropFootprint(
                    width = maxWidth,
                    height = maxHeight,
                    restScale = FlamingoMovingBlurScale,
                    driftScale = FlamingoMovingBlurScale,
                    maxDriftDp = wanderMaxDrift,
                )
            }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .requiredSize(driftFootprint)
                    .graphicsLayer {
                        scaleX = FlamingoMovingBlurScale
                        scaleY = FlamingoMovingBlurScale
                        translationX = blurWander.xDp.floatValue.dp.toPx()
                        translationY = blurWander.yDp.floatValue.dp.toPx()
                        rotationZ = blurWander.rotationDeg.floatValue
                    },
            )
        }
    }
}
