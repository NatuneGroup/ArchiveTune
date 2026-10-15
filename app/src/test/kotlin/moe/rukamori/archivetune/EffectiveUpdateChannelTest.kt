/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune

import moe.rukamori.archivetune.constants.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stored choices win on either build; only an unset preference uses build identity. */
class EffectiveUpdateChannelTest {
    @Test
    fun nightlyBuildHonoursStoredStable() {
        assertEquals(
            UpdateChannel.STABLE,
            effectiveUpdateChannel(UpdateChannel.STABLE, isNightlyBuild = true),
        )
    }

    @Test
    fun nightlyBuildHonoursStoredNightly() {
        assertEquals(
            UpdateChannel.NIGHTLY,
            effectiveUpdateChannel(UpdateChannel.NIGHTLY, isNightlyBuild = true),
        )
    }

    @Test
    fun nightlyBuildDefaultsToNightly() {
        assertEquals(UpdateChannel.NIGHTLY, effectiveUpdateChannel(null, isNightlyBuild = true))
    }

    @Test
    fun stableBuildHonoursStoredNightly() {
        assertEquals(
            UpdateChannel.NIGHTLY,
            effectiveUpdateChannel(UpdateChannel.NIGHTLY, isNightlyBuild = false),
        )
    }

    @Test
    fun stableBuildHonoursStoredStable() {
        assertEquals(
            UpdateChannel.STABLE,
            effectiveUpdateChannel(UpdateChannel.STABLE, isNightlyBuild = false),
        )
    }

    @Test
    fun stableBuildDefaultsToStable() {
        assertEquals(UpdateChannel.STABLE, effectiveUpdateChannel(null, isNightlyBuild = false))
    }
}
