

package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class MiscSettings(
    val isStudioMasterClarityEnabled: Boolean = true,
    val clarityPreset: Int = 0,
    val clarityAtmosBypass: Boolean = false,
    val bitPerfectEnabled: Boolean = false,
)

@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val MUSIC_ENHANCER = booleanPreferencesKey("lw_music_enhancer")
        val CLARITY_PRESET = intPreferencesKey("lw_clarity_preset")
        val CLARITY_ATMOS_BYPASS = booleanPreferencesKey("lw_clarity_atmos_bypass")
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("lw_bit_perfect_enabled")
    }

    val settings: Flow<MiscSettings> = dataStore.data
        .map { p ->
            MiscSettings(
                isStudioMasterClarityEnabled = p.readSafely(Keys.MUSIC_ENHANCER) ?: true,
                clarityPreset = p.readSafely(Keys.CLARITY_PRESET)?.takeIf { it in 0..3 } ?: 0,
                clarityAtmosBypass = p.readSafely(Keys.CLARITY_ATMOS_BYPASS) ?: false,
                bitPerfectEnabled = p.readSafely(Keys.BIT_PERFECT_ENABLED) ?: false,
            )
        }

    val isStudioMasterClarityEnabled: Flow<Boolean> =
        settings.map { it.isStudioMasterClarityEnabled }.distinctUntilChanged()

    suspend fun setStudioMasterClarity(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_ENHANCER] = enabled }
    }

    suspend fun setClarityPreset(index: Int) {
        dataStore.edit { it[Keys.CLARITY_PRESET] = index.coerceIn(0, 3) }
    }

    suspend fun setClarityAtmosBypass(enabled: Boolean) {
        dataStore.edit { it[Keys.CLARITY_ATMOS_BYPASS] = enabled }
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIT_PERFECT_ENABLED] = enabled }
    }
}
