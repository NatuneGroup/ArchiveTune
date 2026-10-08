/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerColorExtractorTest {
    private fun swatch(
        argb: Int,
        population: Int,
    ): PlayerColorExtractor.ColorSwatch = PlayerColorExtractor.ColorSwatch(argb, population)

    private fun hslOf(color: Color): FloatArray {
        val argb =
            (255 shl 24) or
                ((color.red * 255f).toInt().coerceIn(0, 255) shl 16) or
                ((color.green * 255f).toInt().coerceIn(0, 255) shl 8) or
                (color.blue * 255f).toInt().coerceIn(0, 255)
        return PlayerColorExtractor.rgbToHsl(argb)
    }

    private fun hueOf(color: Color): Float = hslOf(color)[0]

    private fun saturationOf(color: Color): Float = hslOf(color)[1]

    private fun lightnessOf(color: Color): Float = hslOf(color)[2]

    private fun hueDistance(
        a: Float,
        b: Float,
    ): Float {
        val raw = kotlin.math.abs(a - b) % 360f
        return if (raw > 180f) 360f - raw else raw
    }

    @Test
    fun trueGreyscaleArtworkIsClassifiedGreyscale() {
        val swatches =
            listOf(
                swatch(0xFF101010.toInt(), 70),
                swatch(0xFF808080.toInt(), 20),
                swatch(0xFFF0F0F0.toInt(), 10),
            )
        val stats = PlayerColorExtractor.computeGreyscaleStats(swatches)
        assertTrue(PlayerColorExtractor.isGreyscale(stats))

        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        assertEquals(6, colors.size)
        colors.forEach { color ->
            assertTrue(
                "greyscale gradient must stay neutral, got saturation ${saturationOf(color)}",
                saturationOf(color) < 0.05f,
            )
        }
    }

    @Test
    fun darkSaturatedBlueIsNotGreyscale() {
        // Mostly dark blue with some black: dark does NOT mean greyscale.
        val swatches =
            listOf(
                swatch(0xFF0A2050.toInt(), 78),
                swatch(0xFF050505.toInt(), 22),
            )
        val stats = PlayerColorExtractor.computeGreyscaleStats(swatches)
        assertFalse(PlayerColorExtractor.isGreyscale(stats))

        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        val blueHue = hueOf(Color(0xFF0A2050.toInt()))
        assertTrue(
            "dark blue cover must keep its blue hue",
            colors.any { hueDistance(hueOf(it), blueHue) < 40f && saturationOf(it) > 0.4f },
        )
    }

    @Test
    fun colorfulSubjectOnBlackBackgroundIsNotGreyscale() {
        val swatches =
            listOf(
                swatch(0xFF000000.toInt(), 70),
                swatch(0xFFCC1111.toInt(), 20),
                swatch(0xFF1133CC.toInt(), 10),
            )
        val stats = PlayerColorExtractor.computeGreyscaleStats(swatches)
        assertFalse(
            "20% colourful population must not be suppressed by a black background",
            PlayerColorExtractor.isGreyscale(stats),
        )

        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        val hasRed = colors.any { hueDistance(hueOf(it), 0f) < 30f && saturationOf(it) > 0.5f }
        val hasBlue = colors.any { hueDistance(hueOf(it), 225f) < 35f && saturationOf(it) > 0.5f }
        assertTrue("red focal region must survive", hasRed)
        assertTrue("blue focal region must survive", hasBlue)
    }

    @Test
    fun pastelArtworkRetainsItsColours() {
        val swatches =
            listOf(
                swatch(0xFFF4CFE0.toInt(), 45), // pastel pink
                swatch(0xFFCFE8DC.toInt(), 35), // pastel mint
                swatch(0xFFE8E2F4.toInt(), 20), // pastel lavender
            )
        val stats = PlayerColorExtractor.computeGreyscaleStats(swatches)
        assertFalse(PlayerColorExtractor.isGreyscale(stats))

        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        val pinkHue = hueOf(Color(0xFFF4CFE0.toInt()))
        assertTrue(
            "pastel colours must be retained instead of forced to grey",
            colors.any { hueDistance(hueOf(it), pinkHue) < 45f },
        )
    }

    @Test
    fun whiteCoverWithColorfulTextKeepsColourfulSwatches() {
        val swatches =
            listOf(
                swatch(0xFFFFFFFF.toInt(), 85),
                swatch(0xFFE65100.toInt(), 15), // saturated orange "text"
            )
        val stats = PlayerColorExtractor.computeGreyscaleStats(swatches)
        assertFalse(PlayerColorExtractor.isGreyscale(stats))

        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        val orangeHue = hueOf(Color(0xFFE65100.toInt()))
        assertTrue(
            "white dominance must not discard the colourful swatch",
            colors.any { hueDistance(hueOf(it), orangeHue) < 35f && saturationOf(it) > 0.5f },
        )
    }

    @Test
    fun genuineNeutralColoursAreNotForceSaturated() {
        val swatches =
            listOf(
                swatch(0xFF0A2050.toInt(), 50),
                swatch(0xFF7A7A7A.toInt(), 50),
            )
        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        assertTrue(
            "a genuine neutral swatch must stay neutral (no forced minimum saturation)",
            colors.any { saturationOf(it) < 0.15f },
        )
    }

    @Test
    fun additionalStaysWithinArtworkHues() {
        val swatches =
            listOf(
                swatch(0xFFCC1111.toInt(), 55),
                swatch(0xFF1133CC.toInt(), 45),
            )
        val colors = PlayerColorExtractor.extractGradientColors(swatches, FALLBACK)
        // A pure two-hue cover yields at least the source colours plus related derived stops;
        // the similarity guard prevents padding with near-identical swatches.
        assertTrue("expected at least 4 gradient stops, got ${colors.size}", colors.size >= 4)
        // No invented hues: every stop must be near a source hue or a blend between the two
        // source hues (the red→blue band), never an unrelated green/orange/purple-opposite.
        colors.forEach { color ->
            val hue = hueOf(color)
            val nearRed = hueDistance(hue, 0f) < 40f
            val nearBlue = hueDistance(hue, 225f) < 40f
            val onBlendBand = hue in 200f..360f || hue < 40f
            assertTrue(
                "unexpected invented hue $hue (saturation ${saturationOf(color)})",
                nearRed || nearBlue || onBlendBand,
            )
        }
    }

    @Test
    fun greyscaleStatsUsePopulationWeighting() {
        // A tiny colourful speck (5%) on a grey cover: still greyscale overall.
        val speckStats =
            PlayerColorExtractor.computeGreyscaleStats(
                listOf(
                    swatch(0xFF909090.toInt(), 95),
                    swatch(0xFFCC1111.toInt(), 5),
                ),
            )
        assertTrue(PlayerColorExtractor.isGreyscale(speckStats))

        // The same colours with inverted populations: colourful.
        val colourfulStats =
            PlayerColorExtractor.computeGreyscaleStats(
                listOf(
                    swatch(0xFF909090.toInt(), 5),
                    swatch(0xFFCC1111.toInt(), 95),
                ),
            )
        assertFalse(PlayerColorExtractor.isGreyscale(colourfulStats))
    }

    private companion object {
        const val FALLBACK = 0xFF202020.toInt()
    }
}
