/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticHeatRasterTest {
    private fun grid(width: Int, height: Int, heat: FloatArray) = AcousticAuditResult(
        mediaId = "synthetic",
        sourceLabel = "TEST",
        sampleRate = 44_100,
        channelCount = 2,
        startMs = 0L,
        durationMs = 20_000L,
        cutoffHz = null,
        nyquistHz = 22_050f,
        width = width,
        height = height,
        magnitudeDb = heat,
        assessment = AcousticAssessment.INCONCLUSIVE,
        limitations = emptyList(),
    )

    private fun brightness(argb: Int): Int =
        ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    @Test
    fun silenceRendersEveryPixelBlack() {
        val pixels = AcousticHeatRaster.render(grid(4, 4, FloatArray(16) { AcousticHeatPalette.FLOOR_DB }), 8, 4)
        assertEquals(32, pixels.size)
        assertTrue(pixels.all { it == (0xFF shl 24) })
    }

    @Test
    fun averagingIsDoneInLinearPowerNotInDecibels() {
        // Two source cells averaged into one pixel: 0 dB and the -120 dB floor.
        // Linear mean is ~0.5 (-3 dB), near white; a dB mean would be -60 dB, a mid-tone.
        val pixel = AcousticHeatRaster.render(grid(2, 1, floatArrayOf(0f, AcousticHeatPalette.FLOOR_DB)), 1, 1).single()
        assertTrue(brightness(pixel) > brightness(AcousticHeatPalette.argbForDb(-60f)))
    }

    @Test
    fun zeroHertzIsAtTheBottomOfThePicture() {
        // Source row 0 is 0 Hz and loud; source row 1 is the quiet top of the spectrum.
        val pixels = AcousticHeatRaster.render(grid(1, 2, floatArrayOf(0f, AcousticHeatPalette.FLOOR_DB)), 1, 2)
        assertTrue(brightness(pixels[1]) > brightness(pixels[0]))
    }

    @Test
    fun mismatchedGridRendersNothing() {
        assertEquals(0, AcousticHeatRaster.render(grid(2, 2, FloatArray(3)), 2, 2).size)
    }
}
