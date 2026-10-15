/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.spotify.SpotifySearchItem
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.SpotifyPlayableRow

/** The Library's Songs, Artists and Albums sections on the Spotify source. */
@Composable
fun LibrarySpotifySongsScreen(
    navController: NavController,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.likedSongs.collectAsStateWithLifecycle()
    val accountRevision by viewModel.accountRevision.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, accountRevision) { viewModel.loadLikedSongs() }

    SpotifySectionList(
        items = remember(state.items) { state.items.orEmpty().map(SpotifySearchItem::Track) },
        navController = navController,
        isRefreshing = state.isLoading || (state.items == null && state.errorMessage == null),
        errorMessage = state.errorMessage,
        onRefresh = { viewModel.loadLikedSongs(force = true) },
    )
}

@Composable
fun LibrarySpotifyArtistsScreen(
    navController: NavController,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.artists.collectAsStateWithLifecycle()
    val accountRevision by viewModel.accountRevision.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, accountRevision) { viewModel.loadArtists() }

    SpotifySectionList(
        items = remember(state.items) { state.items.orEmpty().map(SpotifySearchItem::Artist) },
        navController = navController,
        isRefreshing = state.isLoading || (state.items == null && state.errorMessage == null),
        errorMessage = state.errorMessage,
        onRefresh = { viewModel.loadArtists(force = true) },
    )
}

@Composable
fun LibrarySpotifyAlbumsScreen(
    navController: NavController,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.albums.collectAsStateWithLifecycle()
    val accountRevision by viewModel.accountRevision.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, accountRevision) { viewModel.loadAlbums() }

    SpotifySectionList(
        items = remember(state.items) { state.items.orEmpty().map(SpotifySearchItem::Album) },
        navController = navController,
        isRefreshing = state.isLoading || (state.items == null && state.errorMessage == null),
        errorMessage = state.errorMessage,
        onRefresh = { viewModel.loadAlbums(force = true) },
    )
}

@Composable
private fun SpotifySectionList(
    items: List<SpotifySearchItem>,
    navController: NavController,
    isRefreshing: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
) {
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    ExpressivePullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        indicatorOffset = LibraryPullToRefreshIndicatorOffset,
    ) {
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding =
                PaddingValues(
                    top = LibraryHeaderContentPadding,
                    bottom = playerAwareBottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (errorMessage != null) {
                item(key = "spotify_section_error", contentType = "spotify_section_error") {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            } else if (items.isEmpty() && !isRefreshing) {
                item(key = "spotify_section_empty", contentType = "spotify_section_empty") {
                    Text(
                        text = stringResource(R.string.no_results_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }

            items(
                items = items,
                key = SpotifySearchItem::key,
                contentType = { "spotify_section_row" },
            ) { item ->
                SpotifyPlayableRow(item, navController)
            }
        }
    }
}
