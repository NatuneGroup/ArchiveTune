/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure animation-scale maths (see [scaleDuration]): a span that mirrors an on-screen animation
 * must stretch and shrink with the system animator duration scale, and at scale 0 — animations off
 * — it must collapse to zero so waits resolve immediately.
 */
class AnimationScaleDurationTest {
    @Test
    fun scaleDuration_halfScaleHalves() {
        assertEquals(150L, scaleDuration(300L, 0.5f))
    }

    @Test
    fun scaleDuration_doubleScaleDoubles() {
        assertEquals(600L, scaleDuration(300L, 2f))
    }

    @Test
    fun scaleDuration_zeroScaleCollapsesToZero() {
        assertEquals(0L, scaleDuration(300L, 0f))
    }

    @Test
    fun scaleDuration_unitScaleIsIdentity() {
        assertEquals(300L, scaleDuration(300L, 1f))
    }

    @Test
    fun scaleDuration_largeScaleStretches() {
        assertEquals(3000L, scaleDuration(300L, 10f))
    }

    @Test
    fun scaleDuration_zeroMillisStaysZero() {
        assertEquals(0L, scaleDuration(0L, 2f))
    }

    @Test
    fun scaleDuration_roundsHalfWayUp() {
        // An odd span at an odd scale lands between whole milliseconds and must round up, not
        // truncate to zero — truncating would make a one-frame stagger instant at every scale.
        assertEquals(1L, scaleDuration(1L, 0.5f))
    }

    @Test
    fun scaleAwareSpring_snapsAtZeroScale() {
        val spec = scaleAwareSpring<Float>(scale = 0f, dampingRatio = 0.6f, stiffness = 400f)
        assertTrue("at scale 0 the spec must snap, not animate", spec is SnapSpec)
    }

    @Test
    fun scaleAwareSpring_plainSpringAtUnitScale() {
        val spec = scaleAwareSpring<Float>(scale = 1f, dampingRatio = 0.6f, stiffness = 400f)
        assertTrue("at scale 1 the spec must be the plain spring", spec is SpringSpec)
        spec as SpringSpec
        assertEquals(0.6f, spec.dampingRatio, 0f)
        assertEquals(400f, spec.stiffness, 0f)
    }

    @Test
    fun scaleAwareSpring_belowUnitScaleDampsTowardCritical() {
        val spec = scaleAwareSpring<Float>(scale = 0.5f, dampingRatio = 0.6f, stiffness = 400f)
        spec as SpringSpec
        // Halfway down, damping sits halfway between critical (1f) and the requested 0.6f.
        assertEquals(0.8f, spec.dampingRatio, 1e-4f)
        // Stiffness comes down so the remaining travel spreads over the available frames.
        assertEquals(300f, spec.stiffness, 1e-4f)
    }
}
