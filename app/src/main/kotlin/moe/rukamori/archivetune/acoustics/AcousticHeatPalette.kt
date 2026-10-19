/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Colour map for the acoustic heat graph: FFmpeg's `showspectrumpic` INTENSITY table
 * (libavfilter/avf_showspectrum.c, master 7045cc9b), the palette SpotiFlac-Mobile renders.
 * Intensity 0 is black and 1 is white; heat maps linearly in dB across [FLOOR_DB, CEILING_DB].
 */
internal object AcousticHeatPalette {
    /** Heat at or below this is black. */
    const val FLOOR_DB = -120f

    /** Heat at full intensity, white. */
    const val CEILING_DB = 0f

    /**
     * Added to power-derived dB so the heat domain matches SpotiFlac's window normalisation:
     * 20 log10(sum w / sum w^2) = 20 log10(4/3) = 2.4988 dB for a Hann window.
     */
    const val WINDOW_OFFSET_DB = 2.4988f

    private const val LUT_STEPS = 1023

    // (intensity, Y, U, V) anchors of the FFmpeg INTENSITY table, float YUV before 8-bit quantisation.
    private val anchors: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.00, 0.0, 0.0, 0.0),
        doubleArrayOf(0.13, 0.03587126228984074, 0.1573300977624594, -0.02548747583751842),
        doubleArrayOf(0.30, 0.18572281794568020, 0.1772436246393981, 0.17475554840414750),
        doubleArrayOf(0.60, 0.28184980583656130, -0.1593064119945782, 0.47132074554608920),
        doubleArrayOf(0.73, 0.65830621175547810, -0.3716070802232764, 0.24352759331252930),
        doubleArrayOf(0.78, 0.76318535758242900, -0.4307467689263783, 0.16866496622310430),
        doubleArrayOf(0.91, 0.95336363636363640, -0.2045454545454546, 0.03313636363636363),
        doubleArrayOf(1.00, 1.0, 0.0, 0.0),
    )

    // ARGB for intensity i / LUT_STEPS; the raster and the legend index this table.
    private val lut: IntArray = IntArray(LUT_STEPS + 1) { argb(it.toDouble() / LUT_STEPS) }

    /** Intensity in [0, 1] for a heat value in dB. NaN is black. */
    fun intensity(db: Float): Float =
        if (db.isNaN()) 0f else ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0f, 1f)

    /** ARGB colour for [intensity] in [0, 1], computed from the anchors. */
    fun argb(intensity: Double): Int {
        val a = intensity.coerceIn(0.0, 1.0)
        var from = anchors[0]
        var to = anchors[anchors.size - 1]
        for (i in 0 until anchors.size - 1) {
            if (a <= anchors[i + 1][0]) {
                from = anchors[i]
                to = anchors[i + 1]
                break
            }
        }
        val t = if (to[0] == from[0]) 0.0 else (a - from[0]) / (to[0] - from[0])
        val y = from[1] + (to[1] - from[1]) * t
        val u = from[2] + (to[2] - from[2]) * t
        val v = from[3] + (to[3] - from[3]) * t
        // FFmpeg 8-bit quantisation: round(clip(256 Y)), round(clip(127.5 + 256 U)), round(clip(127.5 + 256 V)).
        val y8 = round((256.0 * y).coerceIn(0.0, 255.0)).toInt()
        val u8 = round((127.5 + 256.0 * u).coerceIn(0.0, 255.0)).toInt()
        val v8 = round((127.5 + 256.0 * v).coerceIn(0.0, 255.0)).toInt()
        // BT.601 full-range YUV to RGB, rounded and clipped per channel.
        val cb = u8 - 128
        val cr = v8 - 128
        val r = round((y8 + 1.402 * cr).coerceIn(0.0, 255.0)).toInt()
        val g = round((y8 - 0.344136 * cb - 0.714136 * cr).coerceIn(0.0, 255.0)).toInt()
        val b = round((y8 + 1.772 * cb).coerceIn(0.0, 255.0)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** ARGB colour for a heat value in dB, through the precomputed table. */
    fun argbForDb(db: Float): Int = lut[(intensity(db) * LUT_STEPS).roundToInt()]

    /** [count] ARGB stops from black to white for the legend gradient; requires count >= 2. */
    fun legendColors(count: Int): IntArray = IntArray(count) { lut[it * LUT_STEPS / (count - 1)] }
}
