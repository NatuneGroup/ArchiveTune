/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class AcousticHeatRasterTest {
    private fun grid(
        width: Int,
        height: Int,
        heat: FloatArray,
    ) = AcousticAuditResult(
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
    fun silenceRendersEveryPixelBlack() = runTest {
        val pixels = AcousticHeatRaster.render(grid(4, 4, FloatArray(16) { AcousticHeatPalette.FLOOR_DB }), 8, 4)
        assertEquals(32, pixels.size)
        assertTrue(pixels.all { it == (0xFF shl 24) })
    }

    @Test
    fun averagingIsDoneInLinearPowerNotInDecibels() = runTest {
        // Two source cells averaged into one pixel: 0 dB and the -120 dB floor.
        // The linear-power mean is 10*log10((1 + 1e-12)/2) ~= -3.0103 dB, mapped to its palette stop;
        // a dB-domain mean would be -60 dB (much darker) and a max reducer would be 0 dB (white).
        val expectedDb = (10.0 * log10((1 + 1e-12) / 2)).toFloat()
        val pixel = AcousticHeatRaster.render(grid(2, 1, floatArrayOf(0f, AcousticHeatPalette.FLOOR_DB)), 1, 1).single()
        assertEquals(AcousticHeatPalette.argbForDb(expectedDb), pixel)
        assertNotEquals(AcousticHeatPalette.argbForDb(-60f), pixel)
        assertNotEquals(AcousticHeatPalette.argbForDb(0f), pixel)
    }

    @Test
    fun zeroHertzIsAtTheBottomOfThePicture() = runTest {
        // Source row 0 is 0 Hz and loud; source row 1 is the quiet top of the spectrum.
        val pixels = AcousticHeatRaster.render(grid(1, 2, floatArrayOf(0f, AcousticHeatPalette.FLOOR_DB)), 1, 2)
        assertTrue(brightness(pixels[1]) > brightness(pixels[0]))
    }

    @Test
    fun nonSquareUpscaleKeepsAUniformSource() = runTest {
        // 2 x 2 at 0 dB, up-sampled to non-square 3 x 4: every output pixel averages the same 0 dB.
        val pixels = AcousticHeatRaster.render(grid(2, 2, FloatArray(4) { 0f }), 3, 4)
        assertEquals(12, pixels.size)
        assertTrue(pixels.all { it == AcousticHeatPalette.argbForDb(0f) })
    }

    @Test
    fun mismatchedGridRendersNothing() = runTest {
        assertEquals(0, AcousticHeatRaster.render(grid(2, 2, FloatArray(3)), 2, 2).size)
    }

    @Test
    fun cancelledScopeStopsRendering() = runTest {
        // A dismissed or re-laid-out graph cancels the produceState coroutine; the raster checks per row.
        val parent = Job().apply { cancel() }
        var finished = false
        launch(Dispatchers.Unconfined + parent, start = CoroutineStart.UNDISPATCHED) {
            try {
                AcousticHeatRaster.render(grid(1, 4, FloatArray(4)), 1, 4)
                finished = true
            } catch (_: CancellationException) {
            }
        }
        assertFalse(finished)
    }
}
