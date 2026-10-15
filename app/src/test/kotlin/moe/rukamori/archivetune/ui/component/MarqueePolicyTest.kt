/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import org.junit.Assert.*
import org.junit.Test

class MarqueePolicyTest {
    @Test
    fun onlyMeasuredOverflowWithActiveMotionAndPositivePasses_animates() {
        assertTrue(marqueeShouldAnimate(101, 100, true, true, 2))
        for (content in listOf(0, 50, 100)) assertFalse(marqueeShouldAnimate(content, 100, true, true, 2))
        assertFalse(marqueeShouldAnimate(101, 0, true, true, 2))
        assertFalse(marqueeShouldAnimate(101, 100, false, true, 2))
        assertFalse(marqueeShouldAnimate(101, 100, true, false, 2))
        for (iterations in listOf(0, -1)) assertFalse(marqueeShouldAnimate(101, 100, true, true, iterations))
    }

    @Test
    fun finiteSettingsPassesAndExistingInfinitePlayerPasses_shareEligibility() {
        assertTrue(marqueeShouldAnimate(200, 100, true, true, 2))
        assertTrue(marqueeShouldAnimate(200, 100, true, true, Int.MAX_VALUE))
        assertFalse(marqueeShouldAnimate(200, 100, true, false, Int.MAX_VALUE))
    }
}
