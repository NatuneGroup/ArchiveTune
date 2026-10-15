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
import moe.rukamori.archivetune.spotify.SpotifyLibraryRepository
import moe.rukamori.archivetune.spotify.SpotifySearchItem
import moe.rukamori.archivetune.spotify.toSearchItems
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultArgument
import moe.rukamori.archivetune.ui.screens.search.decodeOnlineSearchQuery
import javax.inject.Inject

@Immutable
data class SpotifySearchUiState(
    val items: List<SpotifySearchItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class SpotifySearchViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: SpotifyLibraryRepository,
    ) : ViewModel() {
        val query: String =
            decodeOnlineSearchQuery(
                savedStateHandle.get<String>(OnlineSearchResultArgument).orEmpty(),
            )

        private val _uiState = MutableStateFlow(SpotifySearchUiState())
        val uiState: StateFlow<SpotifySearchUiState> = _uiState.asStateFlow()

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
                    _uiState.value = SpotifySearchUiState(isLoading = true)
                } else {
                    _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                }

                try {
                    val page =
                        repository.search(
                            query = query,
                            limit = PAGE_SIZE,
                            offset = nextOffset,
                        )
                    val pageItems = page.toSearchItems()
                    val mergedItems =
                        if (reset) {
                            pageItems
                        } else {
                            (_uiState.value.items + pageItems).distinctBy(SpotifySearchItem::key)
                        }
                    val returnedCount =
                        listOfNotNull(
                            page.tracks,
                            page.albums,
                            page.artists,
                            page.playlists,
                        ).maxOfOrNull { it.items.size } ?: 0
                    val total =
                        listOfNotNull(
                            page.tracks,
                            page.albums,
                            page.artists,
                            page.playlists,
                        ).maxOfOrNull { it.total } ?: 0
                    // Spotify's offset is advanced by the number actually returned. Do not force
                    // a full PAGE_SIZE advance: a proxy or a reduced server page can legitimately
                    // return fewer items, and jumping by PAGE_SIZE would skip catalog results.
                    nextOffset += returnedCount
                    _uiState.value =
                        SpotifySearchUiState(
                            items = mergedItems,
                            isLoading = false,
                            hasMore = returnedCount > 0 && nextOffset < total,
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
