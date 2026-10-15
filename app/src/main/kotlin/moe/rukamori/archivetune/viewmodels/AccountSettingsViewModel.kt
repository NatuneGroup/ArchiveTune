/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.auth.SwitchSavedYouTubeAccountUseCase
import moe.rukamori.archivetune.constants.AccountChannelHandleKey
import moe.rukamori.archivetune.constants.AccountEmailKey
import moe.rukamori.archivetune.constants.AccountImageUrlKey
import moe.rukamori.archivetune.constants.AccountNameKey
import moe.rukamori.archivetune.constants.DataSyncIdKey
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AccountChannel
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import timber.log.Timber
import javax.inject.Inject

/**
 * The account identity and channel list for the Account page. The page used to borrow
 * [HomeViewModel] for these, which started a full home-feed load on every visit.
 */
@HiltViewModel
class AccountSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncUtils: SyncUtils,
        private val switchSavedYouTubeAccount: SwitchSavedYouTubeAccountUseCase,
    ) : ViewModel() {
        private val _accountName = MutableStateFlow("")
        val accountName: StateFlow<String> = _accountName.asStateFlow()
        private val _accountImageUrl = MutableStateFlow<String?>(null)
        val accountImageUrl: StateFlow<String?> = _accountImageUrl.asStateFlow()
        private val _accountChannelsState = MutableStateFlow<AccountChannelsState>(AccountChannelsState.Empty)
        val accountChannelsState: StateFlow<AccountChannelsState> = _accountChannelsState.asStateFlow()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                context.dataStore.data
                    .map { it[InnerTubeCookieKey] }
                    .distinctUntilChanged()
                    .collect { cookie ->
                        if (cookie.isNullOrEmpty() || !hasYouTubeLoginCookie(cookie)) {
                            clearAccountData()
                            return@collect
                        }
                        try {
                            YouTube.cookie = cookie
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to set YouTube cookie")
                            clearAccountData()
                            return@collect
                        }
                        refreshAccountIdentity()
                    }
            }
        }

        private fun clearAccountData() {
            _accountName.value = ""
            _accountImageUrl.value = null
            _accountChannelsState.value = AccountChannelsState.Empty
        }

        private suspend fun refreshAccountIdentity() {
            // Seeded from the identity saved at login: accountInfo() is a live call that may fail,
            // and a failure must not read as "no account connected". The avatar is assigned, not
            // merged, so a switched account never shows the previous account's picture.
            context.dataStore.data.first().let { prefs ->
                prefs[AccountNameKey]?.takeIf { it.isNotBlank() }?.let { _accountName.value = it }
                _accountImageUrl.value = prefs[AccountImageUrlKey]?.takeIf { it.isNotBlank() }
            }
            _accountChannelsState.value = AccountChannelsState.Loading

            try {
                YouTube
                    .accountInfo()
                    .onSuccess { info ->
                        _accountName.value = info.name
                        _accountImageUrl.value = info.thumbnailUrl
                        context.dataStore.edit { preferences ->
                            preferences[AccountNameKey] = info.name
                            info.thumbnailUrl?.let { preferences[AccountImageUrlKey] = it }
                        }
                    }.onFailure { error ->
                        Timber.w(error, "Failed to fetch account info")
                    }

                YouTube
                    .accountChannels()
                    .onSuccess { channels ->
                        _accountChannelsState.value = channels
                            .map { it.toUiModel() }
                            .takeIf { it.size > 1 }
                            ?.let { AccountChannelsState.Success(AccountChannelCollection(it)) }
                            ?: AccountChannelsState.Empty
                    }.onFailure { error ->
                        Timber.w(error, "Failed to fetch account channels")
                        reportException(error)
                        _accountChannelsState.value = AccountChannelsState.Error(error.message.orEmpty())
                    }
            } catch (e: CancellationException) {
                _accountChannelsState.value = AccountChannelsState.Empty
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Exception fetching account info")
                reportException(e)
                _accountChannelsState.value = AccountChannelsState.Error(e.message.orEmpty())
            }
        }

        private fun AccountChannel.toUiModel(): AccountChannelUiModel =
            AccountChannelUiModel(
                name = name,
                byline = byline.orEmpty(),
                channelHandle = channelHandle.orEmpty(),
                thumbnailUrl = thumbnailUrl,
                dataSyncId = dataSyncId,
                isSelected = isSelected,
            )

        fun switchToAccount(
            account: SavedAccount,
            forceSyncOnSwitch: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val authState = switchSavedYouTubeAccount(account).getOrThrow()

                    if (forceSyncOnSwitch && account.ytmSync && authState.hasLoginCookie) {
                        syncUtils.performFullSync(authoritative = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error switching account")
                    reportException(e)
                }
            }
        }

        fun switchToAccountChannel(
            channel: AccountChannelUiModel,
            forceSyncOnSwitch: Boolean,
        ) {
            if (channel.dataSyncId.isBlank()) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    _accountChannelsState.value = AccountChannelsState.Loading

                    context.dataStore.edit { preferences ->
                        preferences[DataSyncIdKey] = channel.dataSyncId
                        preferences[AccountNameKey] = channel.name
                        preferences[AccountChannelHandleKey] = channel.channelHandle
                        if (channel.byline.contains("@")) {
                            preferences[AccountEmailKey] = channel.byline
                        }
                    }

                    val authState =
                        context.dataStore.data
                            .first()
                            .toPlaybackAuthState()
                    YouTube.authState = authState

                    refreshAccountIdentity()

                    if (forceSyncOnSwitch && context.dataStore.get(YtmSyncKey, true) && authState.hasLoginCookie) {
                        syncUtils.performFullSync(authoritative = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error switching account channel")
                    reportException(e)
                    _accountChannelsState.value = AccountChannelsState.Error(e.message.orEmpty())
                }
            }
        }
    }
