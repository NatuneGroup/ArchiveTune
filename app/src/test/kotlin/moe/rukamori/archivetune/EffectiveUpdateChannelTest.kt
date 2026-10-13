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

/**
 * Guards the single channel rule every update path (launch popup, Updates screen, changelog,
 * background worker, notifications) goes through. A nightly build is locked to NIGHTLY no matter
 * what is stored — its versionCode only ever moves along the nightly line, so honouring a stored
 * STABLE would offer a downgrade the system installer rejects — and every other build follows the
 * stored choice, defaulting to STABLE.
 */
class EffectiveUpdateChannelTest {
    @Test
    fun nightlyBuildForcesNightlyWithStoredStable() {
        assertEquals(
            UpdateChannel.NIGHTLY,
            effectiveUpdateChannel(UpdateChannel.STABLE, isNightlyBuild = true),
        )
    }

    @Test
    fun nightlyBuildForcesNightlyWithStoredNightly() {
        assertEquals(
            UpdateChannel.NIGHTLY,
            effectiveUpdateChannel(UpdateChannel.NIGHTLY, isNightlyBuild = true),
        )
    }

    @Test
    fun nightlyBuildForcesNightlyWhenNothingStored() {
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
