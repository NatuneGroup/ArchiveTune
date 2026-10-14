/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.compose.runtime.State
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads through the one-collector page snapshot must match what per-key preference reads gave. */
class PreferenceSnapshotTest {
    private val flagKey = booleanPreferencesKey("snapshotTestFlag")
    private val styleKey = stringPreferencesKey("snapshotTestStyle")

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
    fun missingKeys_returnDefaults() {
        val snapshot = snapshotOf(emptyPreferences())
        assertTrue(snapshot[flagKey, true])
        assertTrue(snapshot.state(flagKey, true).value)
        assertEquals(PlayerBackgroundStyle.GRADIENT, snapshot.enumState(styleKey, PlayerBackgroundStyle.GRADIENT).value)
    }

    @Test
    fun storedValues_areRead() {
        val snapshot = snapshotOf(mutablePreferencesOf(flagKey to false, styleKey to PlayerBackgroundStyle.BLUR.name))
        assertFalse(snapshot[flagKey, true])
        assertFalse(snapshot.state(flagKey, true).value)
        assertEquals(PlayerBackgroundStyle.BLUR, snapshot.enumState(styleKey, PlayerBackgroundStyle.DEFAULT).value)
    }

    @Test
    fun unknownEnumName_fallsBackToDefault() {
        val snapshot = snapshotOf(mutablePreferencesOf(styleKey to "NO_SUCH_STYLE"))
        assertEquals(PlayerBackgroundStyle.DEFAULT, snapshot.enumState(styleKey, PlayerBackgroundStyle.DEFAULT).value)
    }

    @Test
    fun destructuredRead_matchesValue() {
        val snapshot = snapshotOf(mutablePreferencesOf(flagKey to true))
        val (flag, _) = snapshot.state(flagKey, false)
        assertTrue(flag)
    }
}
