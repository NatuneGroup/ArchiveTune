/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.auth.YouTubeLoginRepository
import moe.rukamori.archivetune.constants.AccountImageUrlKey
import moe.rukamori.archivetune.constants.AccountNameKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AccountChannel
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import javax.inject.Inject

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncUtils: SyncUtils,
    private val repository: YouTubeLoginRepository,
) : ViewModel() {
    private val _accountName = MutableStateFlow("")
    val accountName: StateFlow<String> = _accountName.asStateFlow()
    private val _accountImageUrl = MutableStateFlow<String?>(null)
    val accountImageUrl: StateFlow<String?> = _accountImageUrl.asStateFlow()
    private val _accountChannelsState = MutableStateFlow<AccountChannelsState>(AccountChannelsState.Empty)
    val accountChannelsState: StateFlow<AccountChannelsState> = _accountChannelsState.asStateFlow()
    private val _actionFailures = MutableStateFlow(0L)
    val actionFailures: StateFlow<Long> = _actionFailures.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data.map { it.toPlaybackAuthState() }.distinctUntilChanged().collectLatest {
                val auth = repository.reconcilePersisted()
                if (!auth.hasLoginCookie) {
                    _accountName.value = ""
                    _accountImageUrl.value = null
                    _accountChannelsState.value = AccountChannelsState.Empty
                } else refreshAccountIdentity()
            }
        }
    }

    private suspend fun refreshAccountIdentity() {
        val origin = repository.origin()
        val prefs = context.dataStore.data.first()
        if (!repository.isCurrent(origin)) return
        _accountName.value = prefs[AccountNameKey].orEmpty()
        _accountImageUrl.value = prefs[AccountImageUrlKey]
        _accountChannelsState.value = AccountChannelsState.Loading
        repository.refreshIdentity(origin).onSuccess { info ->
            if (repository.isCurrent(origin)) {
                _accountName.value = info.name
                _accountImageUrl.value = info.thumbnailUrl
            }
        }
        if (!repository.isCurrent(origin)) return
        YouTube.accountChannels().onSuccess { channels ->
            if (repository.isCurrent(origin)) {
                _accountChannelsState.value = channels.map { it.toUiModel() }.takeIf { it.size > 1 }
                    ?.let { AccountChannelsState.Success(AccountChannelCollection(it)) } ?: AccountChannelsState.Empty
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (repository.isCurrent(origin)) _accountChannelsState.value = AccountChannelsState.Error(context.getString(R.string.youtube_login_validation_failed))
        }
    }

    private fun AccountChannel.toUiModel() = AccountChannelUiModel(name, byline.orEmpty(), channelHandle.orEmpty(), thumbnailUrl, dataSyncId, isSelected)

    fun switchToAccount(account: SavedAccount, forceSyncOnSwitch: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _accountChannelsState.value = AccountChannelsState.Loading
            repository.switchSavedAccount(account).onSuccess { auth ->
                if (forceSyncOnSwitch && account.ytmSync && auth.hasLoginCookie) syncAfterSwitch()
            }.onFailure { showFailure(it) }
        }
    }

    fun switchToAccountChannel(channel: AccountChannelUiModel, forceSyncOnSwitch: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _accountChannelsState.value = AccountChannelsState.Loading
            repository.switchChannel(channel.dataSyncId).onSuccess { auth ->
                if (forceSyncOnSwitch && context.dataStore.get(YtmSyncKey, true) && auth.hasLoginCookie) syncAfterSwitch()
            }.onFailure { showFailure(it) }
        }
    }

    fun importManualAuth(cookie: String, visitor: String?, dataSyncId: String?, poToken: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.importManualAuth(cookie, visitor, dataSyncId, poToken).onFailure { showFailure(it) }
        }
    }

    private fun showFailure(error: Throwable) {
        if (error is CancellationException) throw error
        _accountChannelsState.value = AccountChannelsState.Error(context.getString(R.string.youtube_login_validation_failed))
        _actionFailures.value += 1
    }

    private suspend fun syncAfterSwitch() {
        try {
            syncUtils.performFullSync(authoritative = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportException(error)
        }
    }
}
