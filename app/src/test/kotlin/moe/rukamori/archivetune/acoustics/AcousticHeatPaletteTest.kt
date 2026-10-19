/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticHeatPaletteTest {
    @Test
    fun intensityIsLinearInDbBetweenFloorAndCeilingAndClamps() {
        assertEquals(0f, AcousticHeatPalette.intensity(-120f), 0f)
        assertEquals(0.5f, AcousticHeatPalette.intensity(-60f), 1e-6f)
        assertEquals(1f, AcousticHeatPalette.intensity(0f), 0f)
        assertEquals(0f, AcousticHeatPalette.intensity(-200f), 0f)
        assertEquals(1f, AcousticHeatPalette.intensity(10f), 0f)
        assertEquals(0f, AcousticHeatPalette.intensity(Float.NaN), 0f)
    }

    @Test
    fun ffmpegIntensityAnchorsGiveTheReferenceColours() {
        val anchors = listOf(
            0.00 to 0x000000,
            0.13 to 0x000050,
            0.30 to 0x6E0180,
            0.60 to 0xF00000,
            0.73 to 0xFF9E00,
            0.78 to 0xFFCA00,
            0.91 to 0xFFFF96,
            1.00 to 0xFFFFFF,
        )
        for ((intensity, rgb) in anchors) {
            assertEquals("intensity $intensity", (0xFF shl 24) or rgb, AcousticHeatPalette.argb(intensity))
        }
    }

    @Test
    fun interiorDbValuesMatchTheReferenceRamp() {
        assertEquals((0xFF shl 24) or 0x000132, AcousticHeatPalette.argb(10.0 / 120.0)) // -110 dB
        assertEquals((0xFF shl 24) or 0xC50129, AcousticHeatPalette.argb(60.0 / 120.0)) // -60 dB
        assertEquals((0xFF shl 24) or 0xFEE13D, AcousticHeatPalette.argb(100.0 / 120.0)) // -20 dB
    }

    @Test
    fun legendRunsFromBlackToWhite() {
        val stops = AcousticHeatPalette.legendColors(20)
        assertEquals(20, stops.size)
        assertEquals(0xFF shl 24, stops.first())
        assertEquals(-1, stops.last())
    }
}
