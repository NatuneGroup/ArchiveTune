/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import androidx.compose.runtime.State
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.constants.FlamingoShowVolumeBarKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.ShowPlayerVolumeBarKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One canonical volume-bar key; Flamingo's old per-style choice still wins until the user edits. */
class PlayerVolumePreferencesTest {
    private fun snapshotOf(preferences: Preferences): PreferenceSnapshot {
        val state =
            object : State<Preferences> {
                override val value: Preferences = preferences
            }
        val dataStore =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flowOf(preferences)

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    throw UnsupportedOperationException("read-only test store")
            }
        return PreferenceSnapshot(state, dataStore)
    }

    @Test
    fun absentKeys_defaultToVisible() {
        assertTrue(effectivePlayerVolumeVisibility(PlayerDesignStyle.V8, canonical = null, legacyFlamingo = null))
        assertTrue(effectivePlayerVolumeVisibility(PlayerDesignStyle.FLAMINGO, canonical = null, legacyFlamingo = null))
    }

    @Test
    fun canonicalKey_appliesToEveryStyle() {
        assertFalse(effectivePlayerVolumeVisibility(PlayerDesignStyle.V8, canonical = false, legacyFlamingo = null))
        assertFalse(effectivePlayerVolumeVisibility(PlayerDesignStyle.BITCHORD, canonical = false, legacyFlamingo = null))
        assertFalse(effectivePlayerVolumeVisibility(PlayerDesignStyle.FLAMINGO, canonical = false, legacyFlamingo = null))
    }

    @Test
    fun legacyFlamingoChoice_winsOnlyForFlamingo() {
        assertFalse(effectivePlayerVolumeVisibility(PlayerDesignStyle.FLAMINGO, canonical = true, legacyFlamingo = false))
        assertTrue(effectivePlayerVolumeVisibility(PlayerDesignStyle.FLAMINGO, canonical = false, legacyFlamingo = true))
        assertTrue(effectivePlayerVolumeVisibility(PlayerDesignStyle.APPLE_MUSIC, canonical = true, legacyFlamingo = false))
        assertFalse(effectivePlayerVolumeVisibility(PlayerDesignStyle.V7, canonical = false, legacyFlamingo = true))
    }

    @Test
    fun snapshotRead_resolvesWithoutWriting() {
        val snapshot =
            snapshotOf(mutablePreferencesOf(ShowPlayerVolumeBarKey to true, FlamingoShowVolumeBarKey to false))

        assertFalse(snapshot.playerVolumeVisibility(PlayerDesignStyle.FLAMINGO).value)
        assertTrue(snapshot.playerVolumeVisibility(PlayerDesignStyle.APPLE_MUSIC).value)
        assertTrue(snapshotOf(emptyPreferences()).playerVolumeVisibility(PlayerDesignStyle.FLAMINGO).value)
    }

    @Test
    fun explicitWriter_updatesCanonicalAndRemovesLegacyWithoutTouchingStyle() {
        for (visible in listOf(false, true)) {
            val preferences = mutablePreferencesOf(
                ShowPlayerVolumeBarKey to !visible, FlamingoShowVolumeBarKey to !visible,
                PlayerDesignStyleKey to PlayerDesignStyle.FLAMINGO.name,
            )
            preferences.setPlayerVolumeVisibility(visible)
            assertEquals(visible, preferences[ShowPlayerVolumeBarKey])
            assertNull(preferences[FlamingoShowVolumeBarKey])
            assertEquals(PlayerDesignStyle.FLAMINGO.name, preferences[PlayerDesignStyleKey])
        }
    }
}
