/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Disable blur effects" must switch off every blur family, and with blur allowed the render-effect
 * and CPU stack-blur paths must split cleanly at Android 12 (API 31).
 */
class BlurPolicyTest {
    @Test
    fun disabledByUser_noFamilyAllowedOnAnySdk() {
        for (sdk in listOf(23, 30, 31, 35)) {
            assertFalse(blurRenderEffectAllowed(userEnabled = false, sdkInt = sdk))
            assertFalse(blurCpuFallbackAllowed(userEnabled = false, sdkInt = sdk))
        }
        assertFalse(BlurPolicy(userEnabled = false).any)
    }

    @Test
    fun enabled_api31AndAbove_usesRenderEffectOnly() {
        for (sdk in listOf(31, 34, 37)) {
            assertTrue(blurRenderEffectAllowed(userEnabled = true, sdkInt = sdk))
            assertFalse(blurCpuFallbackAllowed(userEnabled = true, sdkInt = sdk))
        }
    }

    @Test
    fun enabled_belowApi31_usesCpuFallbackOnly() {
        for (sdk in listOf(23, 29, 30)) {
            assertFalse(blurRenderEffectAllowed(userEnabled = true, sdkInt = sdk))
            assertTrue(blurCpuFallbackAllowed(userEnabled = true, sdkInt = sdk))
        }
    }

    @Test
    fun enabled_anyIsTrue() {
        assertTrue(BlurPolicy(userEnabled = true).any)
    }
}
