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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.acoustics.AcousticAuditResult
import moe.rukamori.archivetune.acoustics.AcousticHeatAxis
import moe.rukamori.archivetune.acoustics.AcousticHeatPalette
import moe.rukamori.archivetune.acoustics.AcousticHeatRaster
import kotlin.math.roundToInt

private const val LEGEND_STOPS = 20

/** Raster bounds from the heatmap plan: a big destination is scaled to at most this source size. */
private const val MAX_RASTER_WIDTH = 1024
private const val MAX_RASTER_HEIGHT = 512
private val LeftGutter = 34.dp
private val BottomGutter = 18.dp
private val AxisText = Color(0xFFB5B5B5)
private val GridLine = Color.White.copy(alpha = 0.1f)

/**
 * Heat picture on a black card: a 2:1 plot with a 0 Hz baseline, kHz and m:ss gridlines, and a legend
 * drawn from the same palette as the raster. The raster is built on [Dispatchers.Default], not on Main.
 */
@Composable
internal fun AcousticHeatGraph(result: AcousticAuditResult, description: String) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AxisText, fontSize = 10.sp)
    val legendBrush = remember {
        Brush.horizontalGradient(AcousticHeatPalette.legendColors(LEGEND_STOPS).map { Color(it) })
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 8.dp, vertical = 6.dp)) {
        val plotWidth = (maxWidth - LeftGutter).coerceAtLeast(0.dp)
        val plotHeight = plotWidth / 2f
        val density = LocalDensity.current
        val plotPxWidth = with(density) { plotWidth.roundToPx() }
        val plotPxHeight = with(density) { plotHeight.roundToPx() }
        // Allocate the raster at the bounded source size, not the full plot size, and let the draw
        // scale it to the destination with medium filtering; a wide plot keeps its bounded cost.
        val rasterWidth = plotPxWidth.coerceAtMost(MAX_RASTER_WIDTH)
        val rasterHeight = plotPxHeight.coerceAtMost(MAX_RASTER_HEIGHT)
        val image: ImageBitmap? by produceState<ImageBitmap?>(null, result, rasterWidth, rasterHeight) {
            value = withContext(Dispatchers.Default) {
                val pixels = AcousticHeatRaster.render(result, rasterWidth, rasterHeight)
                if (pixels.isEmpty()) {
                    null
                } else {
                    Bitmap.createBitmap(pixels, rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }
        }
        Column {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(plotHeight + BottomGutter)
                    .semantics { contentDescription = description },
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
                val maxKHz = result.nyquistHz / 1000f
                if (maxKHz > 0f) {
                    val step = AcousticHeatAxis.niceKHzStep(maxKHz)
                    var khz = 0
                    while (khz <= maxKHz) {
                        val y = plotH * (1f - khz / maxKHz)
                        drawLine(GridLine, Offset(originX, y), Offset(originX + plotW, y), strokeWidth = 1f)
                        val label = textMeasurer.measure(if (khz == 0) "0" else "${khz}k", labelStyle)
                        drawText(
                            label,
                            topLeft = Offset(originX - 5.dp.toPx() - label.size.width, y - label.size.height / 2f),
                        )
                        khz += step
                    }
                }
                val durationSeconds = result.durationMs / 1000.0
                if (durationSeconds > 0.0) {
                    val step = AcousticHeatAxis.niceSecondsStep(durationSeconds)
                    var second = 0
                    while (second <= durationSeconds) {
                        val x = originX + plotW * (second / durationSeconds).toFloat()
                        drawLine(GridLine, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1f)
                        val label = textMeasurer.measure(
                            AcousticHeatAxis.formatClock(result.startMs / 1000.0 + second),
                            labelStyle,
                        )
                        val left = (x - label.size.width / 2f)
                            .coerceAtLeast(0f)
                            .coerceAtMost(maxOf(0f, size.width - label.size.width))
                        drawText(label, topLeft = Offset(left, plotH + 3.dp.toPx()))
                        second += step
                    }
                }
            }
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
    }
}
