/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Renders an [AcousticAuditResult] heat grid to ARGB pixels without Android types, so it is unit-testable.
 * Each output pixel averages the source cells it covers in linear power, then converts back to dB: a
 * dB-domain mean would dilute a short hot cell into the surrounding floor. Output row 0 is the top of the
 * picture (highest frequency); source row 0 is 0 Hz.
 */
internal object AcousticHeatRaster {
    /** ARGB pixels of [outWidth] x [outHeight], or an empty array when the sizes or the grid do not match. */
    fun render(result: AcousticAuditResult, outWidth: Int, outHeight: Int): IntArray {
        val srcWidth = result.width
        val srcHeight = result.height
        val heat = result.magnitudeDb
        if (outWidth <= 0 || outHeight <= 0 || srcWidth <= 0 || srcHeight <= 0 ||
            srcWidth.toLong() * srcHeight != heat.size.toLong()
        ) {
            return IntArray(0)
        }
        val pixels = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val row = outHeight - 1 - y
            val r0 = row * srcHeight / outHeight
            val r1 = maxOf(r0 + 1, (row + 1) * srcHeight / outHeight)
            for (x in 0 until outWidth) {
                val c0 = x * srcWidth / outWidth
                val c1 = maxOf(c0 + 1, (x + 1) * srcWidth / outWidth)
                var power = 0.0
                var count = 0
                for (r in r0 until r1) {
                    for (c in c0 until c1) {
                        val value = heat[r * srcWidth + c]
                        if (value.isFinite()) {
                            power += 10.0.pow(value / 10.0)
                            count++
                        }
                    }
                }
                val meanDb = if (count > 0) (10.0 * log10(power / count)).toFloat() else AcousticHeatPalette.FLOOR_DB
                pixels[y * outWidth + x] = AcousticHeatPalette.argbForDb(meanDb)
            }
        }
        return pixels
    }
}

/** Tick steps and labels for the heat graph axes, using SpotiFlac's nice-step rules. */
internal object AcousticHeatAxis {
    private val kHzSteps = intArrayOf(1, 2, 5, 10, 20, 50)
    private val secondSteps = intArrayOf(5, 10, 15, 30, 60, 120, 300, 600)

    /** Frequency tick step in kHz: the smallest nice step giving at most six ticks, else 100. */
    fun niceKHzStep(maxKHz: Float): Int = kHzSteps.firstOrNull { maxKHz / it <= 6f } ?: 100

    /** Time tick step in seconds: the smallest nice step giving at most six ticks, else 1200. */
    fun niceSecondsStep(durationSeconds: Double): Int =
        secondSteps.firstOrNull { durationSeconds / it <= 6.0 } ?: 1200

    /** m:ss for [seconds], rounded half away from zero to whole seconds; minutes are not wrapped at 60. */
    fun formatClock(seconds: Double): String {
        val total = floor(maxOf(0.0, seconds) + 0.5).toLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}
