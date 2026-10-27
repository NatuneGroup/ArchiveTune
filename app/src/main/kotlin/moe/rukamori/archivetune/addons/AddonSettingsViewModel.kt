/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import javax.inject.Inject

sealed interface AddonSettingsState {
    data object Loading : AddonSettingsState
    data object Empty : AddonSettingsState
    data class Success(val installations: List<AddonInstallation>) : AddonSettingsState
    data class Error(val messageRes: Int) : AddonSettingsState
}

@HiltViewModel
class AddonSettingsViewModel
    @Inject
    constructor(
        private val repository: AddonRepository,
    ) : ViewModel() {
        private val _screenState = MutableStateFlow<AddonSettingsState>(AddonSettingsState.Loading)
        val screenState: StateFlow<AddonSettingsState> = _screenState.asStateFlow()

        private val _manifestUrl = MutableStateFlow("")
        val manifestUrl: StateFlow<String> = _manifestUrl.asStateFlow()

        private val _isWorking = MutableStateFlow(false)
        val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

        private val _showActionError = MutableStateFlow(false)
        val showActionError: StateFlow<Boolean> = _showActionError.asStateFlow()

        init {
            viewModelScope.launch {
                repository.observeInstallations()
                    .catch { _screenState.value = AddonSettingsState.Error(R.string.addon_operation_failed) }
                    .collect { installations ->
                        _screenState.value =
                            if (installations.isEmpty()) AddonSettingsState.Empty else AddonSettingsState.Success(installations)
                    }
            }
        }

        fun updateManifestUrl(value: String) {
            _manifestUrl.value = value
        }

        fun addAddon() {
            runAction {
                repository.addAddon(_manifestUrl.value)
                _manifestUrl.value = ""
            }
        }

        fun setEnabled(
            addonId: String,
            enabled: Boolean,
        ) = runAction { repository.setEnabled(addonId, enabled) }

        fun removeAddon(addonId: String) = runAction { repository.removeAddon(addonId) }

        private fun runAction(action: suspend () -> Unit) {
            viewModelScope.launch {
                _isWorking.value = true
                _showActionError.value = false
                try {
                    action()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    _showActionError.value = true
                } finally {
                    _isWorking.value = false
                }
            }
        }
    }