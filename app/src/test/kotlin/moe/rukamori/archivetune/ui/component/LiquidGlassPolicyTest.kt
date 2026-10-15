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

class LiquidGlassPolicyTest {
    @Test
    fun refraction_clampsNegativeZeroSmallAndLargeGeometry() {
        assertEquals(0f, liquidGlassRefractionHeight(-1f, 20f), 0f)
        assertEquals(0f, liquidGlassRefractionHeight(10f, 0f), 0f)
        assertEquals(0f, liquidGlassRefractionHeight(10f, -1f), 0f)
        assertEquals(2f, liquidGlassRefractionHeight(9f, 4f), 0f)
        assertEquals(10f, liquidGlassRefractionHeight(10f, 20f), 0f)
        assertEquals(3f, liquidGlassRefractionHeight(3f, 100f), 0f)
    }

    @Test
    fun eligibility_requiresMasterPlatformSourceAndVisibility() {
        for (master in listOf(false, true)) for (blur in listOf(false, true)) {
            for (source in listOf(false, true)) for (visible in listOf(false, true)) {
                assertEquals(master && blur && source && visible, liquidGlassAllowed(master, blur, source, visible))
            }
        }
    }
}
