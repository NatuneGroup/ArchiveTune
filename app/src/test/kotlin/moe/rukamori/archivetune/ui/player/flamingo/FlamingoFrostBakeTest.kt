/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player.flamingo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frost bake geometry: a down-scaled canvas still, CPU-blurred at a radius the helper can run,
 * must read — stretched over the frost band — as the same blur the display intent asked for.
 */
class FlamingoFrostBakeTest {
    @Test
    fun clampsRadiusToHelperMaximum() {
        // 288dp at 2.75x density is 792px — far above the 48px ceiling, so the bake must clamp
        // and shrink the bitmap to compensate.
        val plan = flamingoFrostBakePlan(256, 144, displayRadiusPx = 792f, frostWidthPx = 1080)
        assertEquals(48f, plan!!.blurRadiusPx, 0.01f)
    }

    @Test
    fun mapsClampedRadiusBackToDisplayIntent() {
        // Bake width 65px over a 1080px frost: 48 * 1080 / 65 ≈ 797px ≈ the 792px intent.
        val plan = flamingoFrostBakePlan(256, 144, displayRadiusPx = 792f, frostWidthPx = 1080)
        assertEquals(FlamingoFrostBakePlan(48f, 256, 144, 65, 37), plan)
        assertTrue(plan!!.needsDownscale)
    }

    @Test
    fun keepsSourceWhenFrostWidthUnknown() {
        val plan = flamingoFrostBakePlan(256, 144, displayRadiusPx = 792f, frostWidthPx = 0)
        assertEquals(FlamingoFrostBakePlan(48f, 256, 144, 256, 144), plan)
        assertFalse(plan!!.needsDownscale)
    }

    @Test
    fun keepsSourceWhenBakeWouldUpscale() {
        // A display radius within the helper's ceiling maps to a bake wider than the source —
        // keep the source instead of upscaling into the blur.
        val plan = flamingoFrostBakePlan(256, 144, displayRadiusPx = 40f, frostWidthPx = 1080)
        assertEquals(FlamingoFrostBakePlan(40f, 256, 144, 256, 144), plan)
        assertFalse(plan!!.needsDownscale)
    }

    @Test
    fun clampsBakeDimensionsToOnePixel() {
        val plan = flamingoFrostBakePlan(256, 144, displayRadiusPx = 792f, frostWidthPx = 8)
        assertEquals(FlamingoFrostBakePlan(48f, 256, 144, 1, 1), plan)
        assertTrue(plan!!.needsDownscale)
    }

    @Test
    fun rejectsInvalidSources() {
        assertNull(flamingoFrostBakePlan(0, 144, displayRadiusPx = 792f, frostWidthPx = 1080))
        assertNull(flamingoFrostBakePlan(256, 0, displayRadiusPx = 792f, frostWidthPx = 1080))
        assertNull(flamingoFrostBakePlan(256, 144, displayRadiusPx = 0f, frostWidthPx = 1080))
    }
}
