/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import android.widget.Toast
import androidx.datastore.preferences.core.MutablePreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SYSTEM_DEFAULT
import moe.rukamori.archivetune.constants.YouTubeMusicRegionKey
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.playback.dsp.UsbExclusiveAudioOutputProvider
import moe.rukamori.archivetune.utils.*
import java.util.UUID

data class RestartPromptState(
    val reasons: Set<RestartReason> = emptySet(),
    val visible: Boolean = false,
    val busy: Boolean = false,
)

/** Owned by MainActivity, not a destination or result row. Survives activity recreation. */
@HiltViewModel
class RestartPromptViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val policy = RestartPromptPolicy()
    private val edits = Mutex()
    private var writes = 0
    private val storageOperations = mutableSetOf<String>()
    private val runningAppearance = restartSettingValues(StartupSettings.values)
    private val runningRegion = PreferenceStore.snapshot()?.get(YouTubeMusicRegionKey) ?: SYSTEM_DEFAULT
    private val _state = MutableStateFlow(RestartPromptState())
    val state = _state.asStateFlow()

    fun edit(
        preferences: PreferenceSnapshot,
        onCommitted: (PreferenceEditResult) -> Unit = {},
        change: MutablePreferences.() -> Unit,
    ) {
        writes++
        publish()
        viewModelScope.launch {
            try {
                edits.withLock {
                    val result = preferences.editAndAwait(change)
                    policy.committed(
                        restartSettingValues(result.before, BitPerfectUsbOutput.isPlatformSupported),
                        restartSettingValues(result.after, BitPerfectUsbOutput.isPlatformSupported),
                        runningAppearance + mapOf(
                            // These describe the actual service's fixed sinks, not app-start preferences.
                            RestartReason.USB_OUTPUT to "${BitPerfectUsbOutput.sinkActive}|${UsbExclusiveAudioOutputProvider.installed}",
                            RestartReason.REGION to runningRegion,
                        ),
                    )
                    onCommitted(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                writes--
                publish()
            }
        }
    }

    fun beginStorageOperation(): String = UUID.randomUUID().toString().also {
        storageOperations.add(it)
        publish()
    }

    /** The migration VM can finish offscreen; duplicate/replayed callbacks cannot request again. */
    fun finishStorageOperation(token: String, succeeded: Boolean) {
        viewModelScope.launch {
            if (!storageOperations.remove(token)) return@launch
            if (succeeded) policy.storageCommitted(token)
            publish()
        }
    }

    fun later() {
        policy.later()
        publish()
    }

    fun restartIfReady(restart: () -> Unit) {
        if (_state.value.visible && !_state.value.busy) {
            policy.later()
            publish()
            restart()
        }
    }

    private fun publish() {
        _state.value = RestartPromptState(policy.reasons, policy.visible, writes > 0 || storageOperations.isNotEmpty())
    }
}
