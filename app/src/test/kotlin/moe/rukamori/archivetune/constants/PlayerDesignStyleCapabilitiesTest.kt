/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the shared player-settings capabilities each native style declares. */
class PlayerDesignStyleCapabilitiesTest {
    private val nativeStyles =
        listOf(
            PlayerDesignStyle.BITCHORD,
            PlayerDesignStyle.TIKTOK,
            PlayerDesignStyle.SIMPMUSIC,
            PlayerDesignStyle.SPATIALFLOW,
            PlayerDesignStyle.LOOPER,
            PlayerDesignStyle.FLAMINGO,
            PlayerDesignStyle.APPLE_MUSIC,
        )

    @Test
    fun storedNamesAndOrder_areUnchanged() {
        assertEquals(
            listOf(
                "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "APPLE_MUSIC", "V10",
                "BITCHORD", "TIKTOK", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO",
            ),
            PlayerDesignStyle.entries.map { it.name },
        )
    }

    @Test
    fun nativeStyles_offerBackgroundChoice() {
        nativeStyles.forEach { style ->
            assertTrue(style.name, style.supportsBackgroundChoice)
        }
    }

    @Test
    fun defaultBackground_keepsNativeRecipe() {
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerDesignStyle.APPLE_MUSIC.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertEquals(PlayerBackgroundStyle.GLOW_ANIMATED, PlayerDesignStyle.BITCHORD.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertEquals(PlayerBackgroundStyle.GRADIENT, PlayerDesignStyle.SIMPMUSIC.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertEquals(PlayerBackgroundStyle.DEFAULT, PlayerDesignStyle.LOOPER.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertEquals(PlayerBackgroundStyle.GLOW_ANIMATED, PlayerDesignStyle.TIKTOK.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerDesignStyle.SPATIALFLOW.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerDesignStyle.FLAMINGO.resolveBackground(PlayerBackgroundStyle.DEFAULT))
        assertFalse(PlayerDesignStyle.FLAMINGO.usesSharedBackground(PlayerBackgroundStyle.DEFAULT))
    }

    @Test
    fun explicitBackground_isHonouredByNativeStyles() {
        nativeStyles.forEach { style ->
            assertEquals(style.name, PlayerBackgroundStyle.CUSTOM, style.resolveBackground(PlayerBackgroundStyle.CUSTOM))
            assertTrue(style.name, style.usesSharedBackground(PlayerBackgroundStyle.GRADIENT))
            assertTrue(style.name, style.rendersSharedBackground(PlayerBackgroundStyle.GRADIENT))
        }
    }

    @Test
    fun stylesWithoutChoice_keepTheirOwnBackdrop() {
        assertEquals(PlayerBackgroundStyle.DEFAULT, PlayerDesignStyle.V8.resolveBackground(PlayerBackgroundStyle.BLUR))
        assertFalse(PlayerDesignStyle.V8.usesSharedBackground(PlayerBackgroundStyle.BLUR))
    }

    @Test
    fun nativeStyles_keepNativeTransportAndSeek() {
        nativeStyles.forEach { style ->
            assertFalse(style.name, style.supportsSharedButtons)
            assertFalse(style.name, style.supportsSharedSeekStyle)
        }
        assertTrue(PlayerDesignStyle.V4.supportsSharedButtons)
    }

    @Test
    fun volumeRow_onlyOnLayoutsThatHaveOne() {
        val withRow = PlayerDesignStyle.entries.filter { it.hasVolumeRow }.toSet()

        assertEquals(
            setOf(
                PlayerDesignStyle.V7,
                PlayerDesignStyle.V8,
                PlayerDesignStyle.BITCHORD,
                PlayerDesignStyle.APPLE_MUSIC,
                PlayerDesignStyle.FLAMINGO,
            ),
            withRow,
        )
    }

    @Test
    fun canvasSurface_includesSpatialFlowAndLooper() {
        assertTrue(PlayerDesignStyle.SPATIALFLOW.hasArtworkCanvasSurface)
        assertTrue(PlayerDesignStyle.LOOPER.hasArtworkCanvasSurface)
        assertTrue(PlayerDesignStyle.APPLE_MUSIC.hasArtworkCanvasSurface)
        assertTrue(PlayerDesignStyle.FLAMINGO.hasArtworkCanvasSurface)
        assertFalse(PlayerDesignStyle.TIKTOK.hasArtworkCanvasSurface)
    }

    @Test
    fun ordinarySleeveAndHorizontalSkip() {
        assertFalse(PlayerDesignStyle.TIKTOK.hasOrdinarySleeve)
        assertTrue(PlayerDesignStyle.BITCHORD.hasOrdinarySleeve)
        assertTrue(PlayerDesignStyle.LOOPER.hasHorizontalArtworkSkip)
        assertFalse(PlayerDesignStyle.FLAMINGO.hasHorizontalArtworkSkip)
    }

    @Test
    fun canvasCapability_isExactlyExistingFourAndDefaultPlayerIsUnchanged() {
        assertEquals(
            setOf(PlayerDesignStyle.APPLE_MUSIC, PlayerDesignStyle.FLAMINGO, PlayerDesignStyle.SPATIALFLOW, PlayerDesignStyle.LOOPER),
            PlayerDesignStyle.entries.filter { it.hasArtworkCanvasSurface }.toSet(),
        )
        assertEquals(PlayerDesignStyle.V4, PlayerDesignStyle.Default)
        assertFalse(PlayerDesignStyle.LOOPER.supportsSharedButtons)
        assertFalse(PlayerDesignStyle.LOOPER.supportsSharedSeekStyle)
    }

    @Test
    fun everyExplicitBackdrop_survivesNativeStyleSwitchesWithoutRewritingChoice() {
        for (stored in PlayerBackgroundStyle.entries.filter { it != PlayerBackgroundStyle.DEFAULT }) {
            nativeStyles.forEach { style -> assertEquals(stored, style.resolveBackground(stored)) }
        }
    }
}
