/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.applemusic.AppleMusicCatalog
import moe.rukamori.archivetune.applemusic.AppleMusicSearchItem
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultArgument
import moe.rukamori.archivetune.ui.screens.search.decodeOnlineSearchQuery
import javax.inject.Inject

@Immutable
data class AppleMusicSearchUiState(
    val items: List<AppleMusicSearchItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AppleMusicSearchViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val query: String =
            decodeOnlineSearchQuery(
                savedStateHandle.get<String>(OnlineSearchResultArgument).orEmpty(),
            )

        private val _uiState = MutableStateFlow(AppleMusicSearchUiState())
        val uiState: StateFlow<AppleMusicSearchUiState> = _uiState.asStateFlow()

        private var nextOffset = 0
        private var loadJobActive = false

        init {
            loadPage(reset = true)
        }

        fun reload() {
            loadPage(reset = true)
        }

        fun loadMore() {
            if (_uiState.value.hasMore) loadPage(reset = false)
        }

        private fun loadPage(reset: Boolean) {
            if (loadJobActive || query.isBlank()) return
            loadJobActive = true
            viewModelScope.launch {
                if (reset) {
                    nextOffset = 0
                    _uiState.value = AppleMusicSearchUiState(isLoading = true)
                } else {
                    _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                }

                try {
                    val page =
                        AppleMusicCatalog.searchPage(
                            query = query,
                            limit = PAGE_SIZE,
                            offset = nextOffset,
                        )
                    nextOffset += PAGE_SIZE
                    val mergedItems =
                        if (reset) {
                            page.items
                        } else {
                            (_uiState.value.items + page.items).distinctBy { it.key }
                        }
                    _uiState.value =
                        AppleMusicSearchUiState(
                            items = mergedItems,
                            isLoading = false,
                            hasMore = page.anyEndpointFull && page.items.isNotEmpty(),
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            hasMore = false,
                            errorMessage = error.message,
                        )
                    }
                } finally {
                    loadJobActive = false
                }
            }
        }

        private companion object {
            const val PAGE_SIZE = 20
        }
    }
