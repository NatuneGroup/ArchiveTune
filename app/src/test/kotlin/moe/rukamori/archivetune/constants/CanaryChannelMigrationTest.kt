/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pure decision behind the drop-Canary DataStore migration. A stored "CANARY" — the
 * channel the app no longer has — must land on NIGHTLY with the keys only that channel wrote
 * dropped, while STABLE, NIGHTLY and an absent value stay untouched so the migration never
 * re-runs.
 */
class CanaryChannelMigrationTest {
    @Test
    fun canaryStoredRewritesToNightlyAndDropsLegacyKeys() {
        val present = CanaryLegacyKeyNames
        val decision = canaryChannelMigration("CANARY", present)
        assertTrue(decision.channelToNightly)
        assertEquals(CanaryLegacyKeyNames, decision.dropKeyNames)
        assertTrue(decision.needed)
    }

    @Test
    fun stableStoredNeedsNoMigration() {
        val decision = canaryChannelMigration("STABLE", emptySet())
        assertFalse(decision.channelToNightly)
        assertTrue(decision.dropKeyNames.isEmpty())
        assertFalse(decision.needed)
    }

    @Test
    fun nightlyStoredNeedsNoMigration() {
        val decision = canaryChannelMigration("NIGHTLY", emptySet())
        assertFalse(decision.channelToNightly)
        assertTrue(decision.dropKeyNames.isEmpty())
        assertFalse(decision.needed)
    }

    @Test
    fun absentStoredNeedsNoMigration() {
        val decision = canaryChannelMigration(null, emptySet())
        assertFalse(decision.channelToNightly)
        assertTrue(decision.dropKeyNames.isEmpty())
        assertFalse(decision.needed)
    }

    @Test
    fun legacyKeysAloneStillTriggerMigration() {
        // Idempotency's other half: an install that never stored CANARY but still carries the
        // unlock flag or cache keys must get one migration pass to drop them.
        val decision = canaryChannelMigration("STABLE", setOf("canaryChannelUnlocked", "unrelated_key"))
        assertFalse(decision.channelToNightly)
        assertEquals(setOf("canaryChannelUnlocked"), decision.dropKeyNames)
        assertTrue(decision.needed)
    }

    @Test
    fun unknownKeysAreNeverDropped() {
        val decision = canaryChannelMigration("CANARY", setOf("unrelated_key", "another_unrelated_key"))
        assertTrue(decision.channelToNightly)
        assertTrue(decision.dropKeyNames.isEmpty())
    }

    @Test
    fun canonicalLegacyKeyNamesArePinned() {
        // The migration in DataStore.kt rebuilds these keys from literal names; if this list and
        // that wiring drift apart, stale Canary state survives the migration.
        assertEquals(
            setOf(
                "canaryChannelUnlocked",
                "canary_releases_etag",
                "canary_releases_json",
                "canary_releases_last_checked_at",
                "canary_releases_fingerprint",
            ),
            CanaryLegacyKeyNames,
        )
    }

    @Test
    fun fromStoredNameMapsCanaryToNightly() {
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("CANARY", UpdateChannel.STABLE))
    }

    @Test
    fun fromStoredNameMapsDailyNightlyToNightly() {
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("DAILY_NIGHTLY", UpdateChannel.STABLE))
    }

    @Test
    fun fromStoredNameKeepsKnownValues() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStoredName("STABLE", UpdateChannel.NIGHTLY))
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("NIGHTLY", UpdateChannel.STABLE))
    }

    @Test
    fun fromStoredNameFallsBackToDefault() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStoredName(null, UpdateChannel.STABLE))
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("garbage", UpdateChannel.NIGHTLY))
    }
}
