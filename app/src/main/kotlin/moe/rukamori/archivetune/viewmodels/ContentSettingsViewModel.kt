/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.AiContentFilterRefreshResult
import moe.rukamori.archivetune.aicontentfilter.ObserveAiContentFilterUseCase
import moe.rukamori.archivetune.aicontentfilter.RefreshAiContentFilterUseCase
import moe.rukamori.archivetune.aicontentfilter.UpdateAiContentFilterSettingsUseCase
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics
import moe.rukamori.archivetune.paxsenix.models.PaxsenixStats
import javax.inject.Inject

sealed interface PaxsenixStatsState {
    data object Loading : PaxsenixStatsState

    data class Success(
        val stats: PaxsenixStats,
    ) : PaxsenixStatsState

    data object Error : PaxsenixStatsState
}

/**
 * Result of asking the configured Paxsenix endpoint which per-provider lyrics paths it
 * still serves. Surfaced by the "Check Paxsenix endpoints" action so a user can tell a
 * retired upstream route apart from a wrong endpoint or a bad API key — from inside the
 * app both look identical (the provider simply returns no lyrics).
 */
sealed interface PaxsenixEndpointCheckState {
    data object Idle : PaxsenixEndpointCheckState

    data object Running : PaxsenixEndpointCheckState

    data class Success(
        val results: List<PaxsenixLyrics.PathCheck>,
    ) : PaxsenixEndpointCheckState
}

sealed interface AiContentFilterSettingsState {
    data object Loading : AiContentFilterSettingsState

    data class Success(
        val model: AiContentFilterSettingsUiModel,
    ) : AiContentFilterSettingsState

    data object Empty : AiContentFilterSettingsState

    data class Error(
        val messageResId: Int,
    ) : AiContentFilterSettingsState
}

@Immutable
data class AiContentFilterSettingsUiModel(
    val enabled: Boolean,
    val includeModerateConfidence: Boolean,
    val blocklistCount: Int,
    val warnlistCount: Int,
    val refreshing: Boolean,
)

@Immutable
sealed interface AiContentFilterSettingsEffect {
    data class ShowMessage(
        val messageResId: Int,
    ) : AiContentFilterSettingsEffect

    data class OpenUrl(
        val url: String,
    ) : AiContentFilterSettingsEffect
}

@HiltViewModel
class ContentSettingsViewModel
    @Inject
    constructor(
        private val lyricsHelper: LyricsHelper,
        private val database: MusicDatabase,
        observeAiContentFilter: ObserveAiContentFilterUseCase,
        private val updateAiContentFilterSettings: UpdateAiContentFilterSettingsUseCase,
        private val refreshAiContentFilterLists: RefreshAiContentFilterUseCase,
    ) : ViewModel() {
        private val _paxsenixStatsState = MutableStateFlow<PaxsenixStatsState>(PaxsenixStatsState.Loading)
        val paxsenixStatsState = _paxsenixStatsState.asStateFlow()
        private val _paxsenixEndpointCheckState =
            MutableStateFlow<PaxsenixEndpointCheckState>(PaxsenixEndpointCheckState.Idle)
        val paxsenixEndpointCheckState = _paxsenixEndpointCheckState.asStateFlow()
        private var paxsenixEndpointCheckJob: Job? = null
        private val refreshingAiContentFilter = MutableStateFlow(false)
        private val _aiContentFilterEffects = MutableSharedFlow<AiContentFilterSettingsEffect>(extraBufferCapacity = 1)
        val aiContentFilterEffects = _aiContentFilterEffects.asSharedFlow()
        private var aiContentFilterRefreshJob: Job? = null
        private var aiContentFilterEnabledJob: Job? = null
        private var aiContentFilterModerateJob: Job? = null

        val aiContentFilterState: StateFlow<AiContentFilterSettingsState> =
            combine(
                observeAiContentFilter(),
                refreshingAiContentFilter,
            ) { (settings, status), refreshing ->
                AiContentFilterSettingsUiModel(
                    enabled = settings.enabled,
                    includeModerateConfidence = settings.includeModerateConfidence,
                    blocklistCount = status.blocklistCount,
                    warnlistCount = status.warnlistCount,
                    refreshing = refreshing,
                )
            }.map<AiContentFilterSettingsUiModel, AiContentFilterSettingsState> { model ->
                AiContentFilterSettingsState.Success(model)
            }.catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(AiContentFilterSettingsState.Error(R.string.error_unknown))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AiContentFilterSettingsState.Loading,
            )

        init {
            // Lyrics, Lyrics providers and Content all create this ViewModel; refreshing on each
            // visit put a network round-trip on every one of those page opens.
            val now = System.currentTimeMillis()
            if (shouldAutoRefreshAiContentFilter(now, lastAutoRefreshAtMs)) {
                lastAutoRefreshAtMs = now
                startAiContentFilterRefresh(force = false, showSuccess = false)
            }
        }

        fun fetchPaxsenixStats() {
            _paxsenixStatsState.value = PaxsenixStatsState.Loading
            viewModelScope.launch(Dispatchers.IO) {
                PaxsenixLyrics
                    .getStats()
                    .onSuccess { _paxsenixStatsState.value = PaxsenixStatsState.Success(it) }
                    .onFailure { _paxsenixStatsState.value = PaxsenixStatsState.Error }
            }
        }

        /**
         * Probes every per-provider Paxsenix path against the endpoint currently configured
         * in settings. Re-tapping while a check is running restarts it rather than queueing a
         * second sweep.
         */
        fun checkPaxsenixEndpoints() {
            paxsenixEndpointCheckJob?.cancel()
            _paxsenixEndpointCheckState.value = PaxsenixEndpointCheckState.Running
            paxsenixEndpointCheckJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val results = PaxsenixLyrics.checkProviderPaths()
                    _paxsenixEndpointCheckState.value = PaxsenixEndpointCheckState.Success(results)
                }
        }

        fun clearLyricsCache() {
            viewModelScope.launch(Dispatchers.IO) {
                lyricsHelper.clearCache()
                database.query {
                    clearAllLyrics()
                }
            }
        }

        fun setAiContentFilterEnabled(enabled: Boolean) {
            if (!enabled) aiContentFilterRefreshJob?.cancel()
            aiContentFilterEnabledJob?.cancel()
            aiContentFilterEnabledJob =
                viewModelScope.launch(Dispatchers.IO) {
                    updateAiContentFilterSettings.setEnabled(enabled)
                    if (enabled) {
                        startAiContentFilterRefresh(force = false, showSuccess = false)
                    }
                }
        }

        fun setAiContentFilterIncludeModerate(enabled: Boolean) {
            aiContentFilterModerateJob?.cancel()
            aiContentFilterModerateJob =
                viewModelScope.launch(Dispatchers.IO) {
                    updateAiContentFilterSettings.setIncludeModerateConfidence(enabled)
                }
        }

        fun refreshAiContentFilter() {
            startAiContentFilterRefresh(force = true, showSuccess = true)
        }

        fun openAiContentFilterSource() {
            _aiContentFilterEffects.tryEmit(AiContentFilterSettingsEffect.OpenUrl(AISLIST_URL))
        }

        private fun startAiContentFilterRefresh(
            force: Boolean,
            showSuccess: Boolean,
        ) {
            aiContentFilterRefreshJob?.cancel()
            aiContentFilterRefreshJob =
                viewModelScope.launch(Dispatchers.IO) {
                    refreshingAiContentFilter.value = true
                    try {
                        when (refreshAiContentFilterLists(force)) {
                            is AiContentFilterRefreshResult.Success -> {
                                if (showSuccess) {
                                    _aiContentFilterEffects.emit(
                                        AiContentFilterSettingsEffect.ShowMessage(R.string.ai_content_filter_updated),
                                    )
                                }
                            }

                            AiContentFilterRefreshResult.Unavailable -> {
                                _aiContentFilterEffects.emit(
                                    AiContentFilterSettingsEffect.ShowMessage(R.string.ai_content_filter_update_failed),
                                )
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        _aiContentFilterEffects.emit(
                            AiContentFilterSettingsEffect.ShowMessage(R.string.ai_content_filter_update_failed),
                        )
                    } finally {
                        refreshingAiContentFilter.value = false
                    }
                }
        }

        private companion object {
            const val AISLIST_URL = "https://aisloplist.com"

            @Volatile
            var lastAutoRefreshAtMs: Long = 0L
        }
    }

internal const val AI_CONTENT_FILTER_AUTO_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** True when the background AI filter refresh on opening a settings page is due again. */
internal fun shouldAutoRefreshAiContentFilter(
    nowMs: Long,
    lastRefreshAtMs: Long,
    intervalMs: Long = AI_CONTENT_FILTER_AUTO_REFRESH_INTERVAL_MS,
): Boolean = lastRefreshAtMs <= 0L || nowMs - lastRefreshAtMs >= intervalMs || nowMs < lastRefreshAtMs
