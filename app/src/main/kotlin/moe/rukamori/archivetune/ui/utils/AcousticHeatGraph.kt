/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.acoustics.AcousticAuditResult
import moe.rukamori.archivetune.acoustics.AcousticHeatAxis
import moe.rukamori.archivetune.acoustics.AcousticHeatPalette
import moe.rukamori.archivetune.acoustics.AcousticHeatRaster

private const val LEGEND_STOPS = 20

/** Raster bounds from the heatmap plan: a big destination is scaled to at most this source size. */
private const val MAX_RASTER_WIDTH = 1024
private const val MAX_RASTER_HEIGHT = 512
private val LeftGutter = 34.dp
private val BottomGutter = 18.dp
private val AxisText = Color(0xFFB5B5B5)
private val GridLine = Color.White.copy(alpha = 0.1f)
private val CutoffLine = Color(0xFF4FC3F7)

/**
 * Heat picture on a black card: a 2:1 plot with a 0 Hz baseline, kHz and m:ss gridlines, a dashed
 * line where the sound stops (when the analyzer found one), and a key drawn from the same palette
 * as the raster. The raster is built on [Dispatchers.Default], not on Main.
 */
@Composable
internal fun AcousticHeatGraph(result: AcousticAuditResult, description: String) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AxisText, fontSize = 10.sp)
    val legendBrush = remember {
        Brush.horizontalGradient(AcousticHeatPalette.legendColors(LEGEND_STOPS).map { Color(it) })
    }
    val cutoffLabel =
        result.cutoffHz
            ?.takeIf { it.isFinite() && it > 0f && it < result.nyquistHz }
            ?.let {
                stringResource(R.string.acoustic_quality_cutoff_marker, it / 1000f)
            }
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val plotWidth = (maxWidth - LeftGutter).coerceAtLeast(0.dp)
        val plotHeight = plotWidth / 2f
        val density = LocalDensity.current
        val plotPxWidth = with(density) { plotWidth.roundToPx() }
        val plotPxHeight = with(density) { plotHeight.roundToPx() }
        // Allocate the raster at the bounded source size, not the full plot size, and let the draw
        // scale it to the destination with medium filtering; a wide plot keeps its bounded cost.
        val rasterWidth = plotPxWidth.coerceAtMost(MAX_RASTER_WIDTH)
        val rasterHeight = plotPxHeight.coerceAtMost(MAX_RASTER_HEIGHT)
        val image: ImageBitmap? by
            produceState<ImageBitmap?>(null, result, rasterWidth, rasterHeight) {
                value =
                    withContext(Dispatchers.Default) {
                        val pixels = AcousticHeatRaster.render(result, rasterWidth, rasterHeight)
                        if (pixels.isEmpty()) {
                            null
                        } else {
                            Bitmap.createBitmap(
                                    pixels,
                                    rasterWidth,
                                    rasterHeight,
                                    Bitmap.Config.ARGB_8888,
                                )
                                .asImageBitmap()
                        }
                    }
            }
        Column {
            Canvas(
                Modifier.fillMaxWidth().height(plotHeight + BottomGutter).semantics {
                    contentDescription = description
                }
            ) {
                val originX = LeftGutter.toPx()
                val plotW = plotPxWidth.toFloat()
                val plotH = plotPxHeight.toFloat()
                image?.let {
                    drawImage(
                        image = it,
                        dstOffset = IntOffset(originX.roundToInt(), 0),
                        dstSize = IntSize(plotPxWidth, plotPxHeight),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                val plot = Rect(originX, 0f, originX + plotW, plotH)
                drawFrequencyGrid(result, textMeasurer, labelStyle, plot)
                drawTimeGrid(result, textMeasurer, labelStyle, plot)
                val cutoffHz = result.cutoffHz
                if (cutoffLabel != null && cutoffHz != null) {
                    drawCutoff(cutoffHz / result.nyquistHz, cutoffLabel, textMeasurer, plot)
                }
            }
            HeatKey(legendBrush)
        }
    }
}

/** kHz gridlines from 0 Hz up to the top of the chart, which is half the sample rate. */
private fun DrawScope.drawFrequencyGrid(
    result: AcousticAuditResult,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    plot: Rect,
) {
    val maxKHz = result.nyquistHz / 1000f
    if (maxKHz <= 0f) return
    val step = AcousticHeatAxis.niceKHzStep(maxKHz)
    var khz = 0
    while (khz <= maxKHz) {
        val y = plot.bottom * (1f - khz / maxKHz)
        drawLine(GridLine, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        val label = textMeasurer.measure(if (khz == 0) "0" else "${khz}k", labelStyle)
        drawText(
            label,
            topLeft =
                Offset(plot.left - 5.dp.toPx() - label.size.width, y - label.size.height / 2f),
        )
        khz += step
    }
}

/** m:ss gridlines across the analysed part of the song. */
private fun DrawScope.drawTimeGrid(
    result: AcousticAuditResult,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    plot: Rect,
) {
    val durationSeconds = result.durationMs / 1000.0
    if (durationSeconds <= 0.0) return
    val step = AcousticHeatAxis.niceSecondsStep(durationSeconds)
    var second = 0
    while (second <= durationSeconds) {
        val x = plot.left + plot.width * (second / durationSeconds).toFloat()
        drawLine(GridLine, Offset(x, 0f), Offset(x, plot.bottom), strokeWidth = 1f)
        val label =
            textMeasurer.measure(
                AcousticHeatAxis.formatClock(result.startMs / 1000.0 + second),
                labelStyle,
            )
        val left =
            (x - label.size.width / 2f)
                .coerceAtLeast(0f)
                .coerceAtMost(maxOf(0f, size.width - label.size.width))
        drawText(label, topLeft = Offset(left, plot.bottom + 3.dp.toPx()))
        second += step
    }
}

/** Dashed line where the sound stops, at [fraction] of the chart height, labelled at the right. */
private fun DrawScope.drawCutoff(
    fraction: Float,
    text: String,
    textMeasurer: TextMeasurer,
    plot: Rect,
) {
    val y = plot.bottom * (1f - fraction)
    drawLine(
        CutoffLine,
        Offset(plot.left, y),
        Offset(plot.right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
    val label = textMeasurer.measure(text, TextStyle(color = CutoffLine, fontSize = 10.sp))
    val gap = 2.dp.toPx()
    val top = if (y - label.size.height - gap >= 0f) y - label.size.height - gap else y + gap
    drawText(label, topLeft = Offset(plot.right - label.size.width - 4.dp.toPx(), top))
}

/** Colour key: the raster's palette from silent to loud. */
@Composable
private fun HeatKey(legendBrush: Brush) {
    Row(
        Modifier.fillMaxWidth().padding(start = LeftGutter, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.acoustic_quality_heat_legend_low),
            style = MaterialTheme.typography.labelSmall,
            color = AxisText,
        )
        Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(8.dp).background(legendBrush))
        Text(
            stringResource(R.string.acoustic_quality_heat_legend_high),
            style = MaterialTheme.typography.labelSmall,
            color = AxisText,
        )
    }
}
