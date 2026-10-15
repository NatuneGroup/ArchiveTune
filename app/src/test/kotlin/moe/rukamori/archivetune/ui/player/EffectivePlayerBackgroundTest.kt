/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player

import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** With blur off, the Blur player background must fall back to a non-blur style. */
class EffectivePlayerBackgroundTest {
    @Test
    fun blurOff_blurFallsBackToGradient() {
        assertEquals(PlayerBackgroundStyle.GRADIENT, effectivePlayerBackground(PlayerBackgroundStyle.BLUR, blurOff = true))
    }

    @Test
    fun blurOff_blurGradientKeepsStyle() {
        assertEquals(
            PlayerBackgroundStyle.BLUR_GRADIENT,
            effectivePlayerBackground(PlayerBackgroundStyle.BLUR_GRADIENT, blurOff = true),
        )
    }

    @Test
    fun blurOn_blurIsKept() {
        assertEquals(PlayerBackgroundStyle.BLUR, effectivePlayerBackground(PlayerBackgroundStyle.BLUR, blurOff = false))
    }

    @Test
    fun nonBlurStylesUnchangedEitherWay() {
        for (style in PlayerBackgroundStyle.entries.filter { it != PlayerBackgroundStyle.BLUR }) {
            assertEquals(style, effectivePlayerBackground(style, blurOff = true))
            assertEquals(style, effectivePlayerBackground(style, blurOff = false))
        }
    }
}
