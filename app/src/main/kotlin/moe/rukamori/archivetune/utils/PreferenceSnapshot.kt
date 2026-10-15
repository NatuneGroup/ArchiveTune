/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.extensions.toEnum

/**
 * One DataStore collector for a whole settings page. Every [rememberPreference] call starts its own
 * flow chain and every write re-runs the `map` of every live chain, so a page with forty rows paid
 * for forty collectors during its opening transition. Reads here go through the single snapshot;
 * writes still edit one key.
 */
@Stable
class PreferenceSnapshot(
    @PublishedApi internal val preferences: State<Preferences>,
    @PublishedApi internal val dataStore: DataStore<Preferences>,
) {
    val current: Preferences get() = preferences.value

    /** Opt-in transaction for explicit actions that must finish saving before requesting restart. */
    suspend fun editAndAwait(change: MutablePreferences.() -> Unit): PreferenceEditResult {
        lateinit var before: Preferences
        val after = dataStore.edit {
            before = it.toPreferences()
            it.change()
        }
        return PreferenceEditResult(before, after)
    }

    operator fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): T = preferences.value[key] ?: defaultValue

    fun <T> state(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): MutableState<T> =
        SnapshotPreferenceState(
            read = { preferences.value[key] ?: defaultValue },
            write = { value -> PreferenceStore.launchEdit(dataStore) { this[key] = value } },
        )

    inline fun <reified E : Enum<E>> enumState(
        key: Preferences.Key<String>,
        defaultValue: E,
    ): MutableState<E> =
        SnapshotPreferenceState(
            read = { preferences.value[key].toEnum(defaultValue = defaultValue) },
            write = { value -> PreferenceStore.launchEdit(dataStore) { this[key] = value.name } },
        )
}

data class PreferenceEditResult(val before: Preferences, val after: Preferences)

@PublishedApi
internal class SnapshotPreferenceState<T>(
    private val read: () -> T,
    private val write: (T) -> Unit,
) : MutableState<T> {
    override var value: T
        get() = read()
        set(value) = write(value)

    override fun component1(): T = value

    override fun component2(): (T) -> Unit = write
}

@Composable
fun rememberPreferenceSnapshot(): PreferenceSnapshot {
    val context = LocalContext.current
    val dataStore = context.dataStore
    // Seeded from the hot in-memory copy so the first frame shows stored values, not defaults.
    val initial = remember { PreferenceStore.snapshot() ?: emptyPreferences() }
    val preferences = remember(dataStore) { dataStore.data }.collectAsStateWithLifecycle(initialValue = initial)
    return remember(dataStore) { PreferenceSnapshot(preferences, dataStore) }
}
