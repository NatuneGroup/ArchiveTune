/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.spotify.SPOTIFY_LIKED_SONGS_ID
import moe.rukamori.archivetune.constants.LibrarySource
import moe.rukamori.archivetune.constants.HideAiMixKey
import moe.rukamori.archivetune.constants.HideCachedCardKey
import moe.rukamori.archivetune.constants.HideLikedSongsCardKey
import moe.rukamori.archivetune.constants.HideLocalFilesCardKey
import moe.rukamori.archivetune.constants.HideOfflineCardKey
import moe.rukamori.archivetune.constants.HideTop50CardKey
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.rememberSpotifyCatalogOpener
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LibraryMixViewModel
import moe.rukamori.archivetune.viewmodels.LibraryTopMixEmptyReason
import moe.rukamori.archivetune.viewmodels.LibraryTopMixUiModel
import moe.rukamori.archivetune.viewmodels.LibraryTopMixesUiState
import moe.rukamori.archivetune.viewmodels.MostPlayedAlbumUiModel
import moe.rukamori.archivetune.viewmodels.MostPlayedAlbumUiState

/**
 * Builds a sized, cache-enabled [ImageRequest] for a thumbnail that will be displayed at [widthDp]
 * × [heightDp].
 */
@Composable
private fun rememberSizedImageRequest(
    url: String?,
    widthDp: Dp,
    heightDp: Dp,
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { widthDp.roundToPx().coerceAtLeast(1) }
    val heightPx = with(density) { heightDp.roundToPx().coerceAtLeast(1) }
    return remember(url, widthPx, heightPx) {
        ImageRequest
            .Builder(context)
            .data(url)
            .size(Size(widthPx, heightPx))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryMixScreen(
    navController: NavController,
    librarySource: LibrarySource,
    filterContent: (@Composable () -> Unit)?,
    selectedTagIds: Set<String>,
    onTabSelected: (LibraryFilter) -> Unit,
    viewModel: LibraryMixViewModel = hiltViewModel(),
    spotifyLibraryViewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit =
        remember(coroutineScope, snackbarHostState) {
            { message ->
                coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                Unit
            }
        }
    val database = LocalDatabase.current

    val likedSongsCount by database.likedSongsCount().collectAsStateWithLifecycle(initialValue = 0)
    val recentSongs by database.recentSongs(15).collectAsStateWithLifecycle(initialValue = emptyList())
    val topSize by viewModel.topValue.collectAsStateWithLifecycle(initialValue = "50")
    val myTopTitle = stringResource(R.string.my_top)
    val topPlaylistTitle = remember(myTopTitle, topSize) { "$myTopTitle $topSize" }

    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val mostPlayedAlbumUiState by viewModel.mostPlayedAlbumUiState.collectAsStateWithLifecycle()
    val topMixesUiState by viewModel.topMixesUiState.collectAsStateWithLifecycle()
    val spotifyPlaylistsState by spotifyLibraryViewModel.playlists.collectAsStateWithLifecycle()
    val spotifyArtistsState by spotifyLibraryViewModel.artists.collectAsStateWithLifecycle()
    val spotifyRefreshing by spotifyLibraryViewModel.isRefreshing.collectAsStateWithLifecycle()
    val spotifyAccountRevision by spotifyLibraryViewModel.accountRevision.collectAsStateWithLifecycle()
    val (hideAiMix) = rememberPreference(HideAiMixKey, false)
    val (hideLikedSongsCard) = rememberPreference(HideLikedSongsCardKey, false)
    val (hideOfflineCard) = rememberPreference(HideOfflineCardKey, false)
    val (hideCachedCard) = rememberPreference(HideCachedCardKey, false)
    val (hideLocalFilesCard) = rememberPreference(HideLocalFilesCardKey, false)
    val (hideTop50Card) = rememberPreference(HideTop50CardKey, false)

    val filteredPlaylistIds by database
        .playlistIdsByTags(
            if (selectedTagIds.isEmpty()) emptyList() else selectedTagIds.toList(),
        ).collectAsStateWithLifecycle(initialValue = emptyList())

    val visiblePlaylists =
        remember(playlists, selectedTagIds, filteredPlaylistIds) {
            playlists.filter { playlist ->
                val name = playlist.playlist.name
                val matchesName = !name.contains("episode", ignoreCase = true)
                val matchesTags = selectedTagIds.isEmpty() || playlist.id in filteredPlaylistIds
                matchesName && matchesTags
            }
        }
    val spotifyArtists = spotifyArtistsState.items.orEmpty()
    val spotifyPlaylists = spotifyPlaylistsState.orEmpty()

    // What the active source has for the rails below, and the reads that fill them. Spotify's
    // playlists and artists come from the same Spotify library path the Playlists, Artists and
    // Albums sections use; nothing else on the Library tab asks for them, so switching to Spotify
    // is what starts that read.
    //
    // Empty means the source answered and had nothing, the rule SpotifySectionList follows: a
    // remote list that has not come back yet is not an empty library, and claiming it is would put
    // "nothing here yet" on screen for the length of the fetch. A null list is that not-read state,
    // because isRefreshing only turns true once the read reaches the IO dispatcher, a frame after
    // the one that would otherwise have drawn the empty state.
    val sourcePlaylistsEmpty =
        when (librarySource) {
            LibrarySource.YTM -> visiblePlaylists.isEmpty()
            LibrarySource.SPOTIFY ->
                spotifyPlaylistsState != null && spotifyPlaylists.isEmpty() && !spotifyRefreshing
        }
    val sourceArtistsEmpty =
        when (librarySource) {
            LibrarySource.YTM -> artists.isEmpty()
            LibrarySource.SPOTIFY ->
                spotifyArtistsState.items != null && spotifyArtists.isEmpty() && !spotifyArtistsState.isLoading
        }
    // A failed read leaves the state's items null, so the rail would draw a bare header. Say what
    // went wrong instead, the shape SpotifySectionList gives the sections this rail previews.
    val sourceArtistsError =
        when (librarySource) {
            LibrarySource.YTM -> null
            LibrarySource.SPOTIFY -> spotifyArtistsState.errorMessage
        }

    // Re-asked on an account change too: signing in to another Spotify account clears the sections
    // this rail previews, so a rail that only watched the source would keep the old account's
    // answer — the sections themselves key on the revision for the same reason.
    LaunchedEffect(librarySource, spotifyAccountRevision, spotifyLibraryViewModel) {
        if (librarySource == LibrarySource.SPOTIFY) {
            spotifyLibraryViewModel.ensurePlaylists()
            spotifyLibraryViewModel.loadArtists()
        }
    }

    val spotifyCatalogOpener = rememberSpotifyCatalogOpener(navController)

    LaunchedEffect(viewModel) {
        viewModel.topMixEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
        ExpressivePullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.syncAllLibrary() },
            modifier = Modifier.fillMaxSize(),
            indicatorOffset = LibraryPullToRefreshIndicatorOffset,
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding =
                    PaddingValues(
                        top = LibraryHeaderContentPadding,
                        bottom = playerAwareBottomPadding,
                    ),
                modifier = Modifier.fillMaxSize(),
            ) {
                // The source selector is not here any more: it sits at the top of the Library,
                // above these section chips, where a switch is one tap instead of a scroll.
                item(key = "most_played_album_spotlight", contentType = "spotlight") {
                    val state = mostPlayedAlbumUiState
                    if (state is MostPlayedAlbumUiState.Success) {
                        val album = state.album
                        val playAlbum =
                            remember(album.tracks, playerConnection) {
                                {
                                    playerConnection.playQueue(
                                        ListQueue(items = album.tracks.map { it.toMediaItem() }),
                                    )
                                }
                            }
                        val shuffleAlbum =
                            remember(album.tracks, playerConnection) {
                                {
                                    playerConnection.playQueue(
                                        ListQueue(items = album.tracks.shuffled().map { it.toMediaItem() }),
                                    )
                                }
                            }

                        MostPlayedAlbumSpotlightCard(
                            album = album,
                            onOpenAlbum = { navController.navigate("album/${album.id}") },
                            onPlayAll = playAlbum,
                            onShuffle = shuffleAlbum,
                        )
                    }
                }

                // 2. Shortcuts Grid
                item(key = "shortcuts_grid", contentType = "shortcuts_grid") {
                    val showRow1 = !hideLikedSongsCard || !hideOfflineCard
                    val showRow2 = !hideCachedCard || !hideLocalFilesCard
                    val showRow3 = !hideTop50Card
                    if (showRow1 || showRow2 || showRow3) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (showRow1) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (!hideLikedSongsCard) {
                                        // Spotify's liked songs sit on the same card as
                                        // everything else called "Liked songs", and follow the
                                        // source: the count is only meaningful for the local
                                        // library, and Spotify's is named instead.
                                        val likedOnSpotify = librarySource == LibrarySource.SPOTIFY
                                        ShortcutCard(
                                            title = stringResource(R.string.liked_songs),
                                            countText =
                                                if (likedOnSpotify) {
                                                    stringResource(R.string.home_source_spotify)
                                                } else {
                                                    "$likedSongsCount ${stringResource(R.string.tracks_label)}"
                                                },
                                            iconRes = R.drawable.favorite,
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                            iconColor = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                if (likedOnSpotify) {
                                                    navController.navigate(
                                                        "spotify_playlist/$SPOTIFY_LIKED_SONGS_ID",
                                                    )
                                                } else {
                                                    navController.navigate("auto_playlist/liked")
                                                }
                                            },
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }

                                    if (!hideOfflineCard) {
                                        ShortcutCard(
                                            title = stringResource(R.string.offline_shortcut),
                                            countText = stringResource(R.string.downloaded_desc),
                                            iconRes = R.drawable.offline,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                            iconColor = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                            onClick = { navController.navigate("auto_playlist/downloaded") },
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }

                            if (showRow2) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (!hideCachedCard) {
                                        ShortcutCard(
                                            title = stringResource(R.string.cached),
                                            countText = stringResource(R.string.instant_playback),
                                            iconRes = R.drawable.cached,
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                            iconColor = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.weight(1f),
                                            onClick = { navController.navigate("cache_playlist/cached") },
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }

                                    if (!hideLocalFilesCard) {
                                        ShortcutCard(
                                            title = stringResource(R.string.local_files),
                                            countText = stringResource(R.string.on_device),
                                            iconRes = R.drawable.snippet_folder,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                            iconColor = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.weight(1f),
                                            onClick = { navController.navigate("local_songs") },
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }

                            if (showRow3) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    ShortcutCard(
                                        title = topPlaylistTitle,
                                        countText = stringResource(R.string.all_time),
                                        iconRes = R.drawable.trending_up,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                        iconColor = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.weight(1f),
                                        onClick = { navController.navigate("top_playlist/$topSize") },
                                    )

                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // 3. Recently Played Horizontal Row
                if (recentSongs.isNotEmpty()) {
                    item(key = "recently_played") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.recently_played),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(recentSongs, key = { it.id }) { song ->
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(110.dp)
                                                .clickable {
                                                    playerConnection.playQueue(ListQueue(items = listOf(song.toMediaItem())))
                                                },
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(110.dp)
                                                    .clip(RoundedCornerShape(28.dp)),
                                        ) {
                                            AsyncImage(
                                                model = rememberSizedImageRequest(song.song.thumbnailUrl, 110.dp, 110.dp),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            // Play Overlay button
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(8.dp)
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.play),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = song.song.title,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                        Text(
                                            text = song.artists.joinToString(", ") { it.name },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Mixes are built from YouTube listening history, so Spotify cannot fill this one.
                // The section keeps its place and says so, rather than disappearing and reshuffling
                // everything under it whenever the source changes.
                if (!hideAiMix) {
                    item(key = "top_mixes") {
                        if (librarySource == LibrarySource.SPOTIFY) {
                            TopMixesMessageSection(
                                message = stringResource(R.string.library_source_mixes_spotify),
                                isRefreshing = false,
                                onRefresh = viewModel::refreshTopMixes,
                                showRefresh = false,
                            )
                        } else {
                            TopMixesForYouSection(
                                state = topMixesUiState,
                                onRefresh = viewModel::refreshTopMixes,
                                onConfigureAi = { navController.navigate("settings/ai_integration") },
                                onPlayMix = { mix ->
                                    playerConnection.playQueue(
                                        ListQueue(
                                            items = mix.tracks.map { it.toMediaItem() },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                val playlistTagFilterContent = filterContent
                if (playlistTagFilterContent != null) {
                    item(key = "playlist_tag_filters") {
                        playlistTagFilterContent()
                    }
                }

                // Playlists Row: one section for both sources, so the screen keeps its shape
                // when the switch moves the data. The label and the cards follow the source, and
                // a source with none leaves the section in place showing its empty state.
                item(key = "your_playlists") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LibraryRailHeader(
                            titleRes =
                                if (librarySource == LibrarySource.SPOTIFY) {
                                    R.string.your_spotify_playlists
                                } else {
                                    R.string.your_youtube_playlists
                                },
                            onSeeAll = { onTabSelected(LibraryFilter.PLAYLISTS) },
                        )

                        if (sourcePlaylistsEmpty) {
                            LibraryRailEmptyText(stringResource(R.string.library_source_playlists_empty))
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (librarySource == LibrarySource.YTM) {
                                    items(
                                        items = visiblePlaylists.take(8),
                                        key = { playlist -> "playlist_${playlist.id}" },
                                        contentType = { "library_playlist" },
                                    ) { playlist ->
                                        val cardBgColor =
                                            rememberArtworkCardColor(
                                                thumbnailUrl = playlist.thumbnails.getOrNull(0),
                                                fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            )

                                        val interactionSource = remember { MutableInteractionSource() }
                                        val isPressed by interactionSource.collectIsPressedAsState()
                                        val scale by animateFloatAsState(
                                            targetValue = if (isPressed) 0.97f else 1.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessLow,
                                                ),
                                            label = "MixPlaylistCardScale",
                                        )

                                        Column(
                                            modifier =
                                                Modifier
                                                    .width(130.dp)
                                                    .graphicsLayer {
                                                        scaleX = scale
                                                        scaleY = scale
                                                    }.clip(RoundedCornerShape(32.dp))
                                                    .background(cardBgColor)
                                                    .clickable(
                                                        interactionSource = interactionSource,
                                                        indication = null,
                                                        onClick = {
                                                            if (!playlist.playlist.isEditable && playlist.songCount == 0 &&
                                                                playlist.playlist.remoteSongCount != 0
                                                            ) {
                                                                navController.navigate("online_playlist/${playlist.playlist.browseId}")
                                                            } else {
                                                                navController.navigate("local_playlist/${playlist.id}")
                                                            }
                                                        },
                                                    ).padding(12.dp),
                                        ) {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(106.dp)
                                                        .clip(RoundedCornerShape(24.dp)),
                                            ) {
                                                AsyncImage(
                                                    model = rememberSizedImageRequest(playlist.thumbnails.getOrNull(0), 106.dp, 106.dp),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                                // Play Overlay button
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(6.dp)
                                                            .size(28.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.primary)
                                                            .clickable {
                                                                playerConnection.let { conn ->
                                                                    coroutineScope.launch {
                                                                        database.playlistSongs(playlist.id).firstOrNull()?.let { songs ->
                                                                            if (songs.isNotEmpty()) {
                                                                                conn.playQueue(
                                                                                    ListQueue(items = songs.map { it.song.toMediaItem() }),
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        painter = painterResource(id = R.drawable.play),
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = playlist.playlist.name,
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onBackground,
                                            )
                                            Text(
                                                text = "${playlist.songCount} ${stringResource(R.string.tracks_label)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                            )
                                        }
                                    }
                                } else {
                                    items(
                                        items = spotifyPlaylists.take(8),
                                        key = { playlist -> "spotify_playlist_${playlist.id}" },
                                        contentType = { "library_spotify_playlist" },
                                    ) { playlist ->
                                        SpotifyPlaylistCompactCard(
                                            playlist = playlist,
                                            onClick = {
                                                navController.navigate("spotify_playlist/${playlist.id}")
                                            },
                                        )
                                    }
                                }

                                // Ending "More" card
                                item {
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(130.dp)
                                                .height(168.dp)
                                                .clip(RoundedCornerShape(32.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                                .clickable {
                                                    onTabSelected(LibraryFilter.PLAYLISTS)
                                                },
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.expand_more),
                                                contentDescription = stringResource(R.string.more_playlists_desc),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = stringResource(R.string.more_label),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Your Artists Row: the same section for both sources — the label and the
                // tiles follow the source, and an empty source keeps the row and says so.
                item(key = "your_artists") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LibraryRailHeader(
                            titleRes =
                                if (librarySource == LibrarySource.SPOTIFY) {
                                    R.string.your_spotify_artists
                                } else {
                                    R.string.your_youtube_artists
                                },
                            onSeeAll = { onTabSelected(LibraryFilter.ARTISTS) },
                        )
                        if (sourceArtistsError != null) {
                            LibraryRailErrorText(
                                message = sourceArtistsError,
                                onRetry = { spotifyLibraryViewModel.loadArtists(force = true) },
                            )
                        } else if (sourceArtistsEmpty) {
                            LibraryRailEmptyText(stringResource(R.string.library_source_artists_empty))
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (librarySource == LibrarySource.YTM) {
                                    items(artists.take(10), key = { it.artist.id }) { item ->
                                        val artist = item.artist
                                        ArtistTile(
                                            thumbnailUrl = artist.thumbnailUrl,
                                            name = artist.name,
                                            onClick = { navController.navigate("artist/${artist.id}") },
                                        )
                                    }
                                } else {
                                    items(spotifyArtists.take(10), key = { it.id }) { artist ->
                                        ArtistTile(
                                            thumbnailUrl = SpotifyMapper.getArtistThumbnail(artist),
                                            name = artist.name,
                                            onClick = {
                                                spotifyCatalogOpener.openArtist(key = artist.id, artistName = artist.name)
                                            },
                                            isResolving = spotifyCatalogOpener.resolvingKey == artist.id,
                                        )
                                    }
                                }

                                // Ending "+" button
                                item {
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(80.dp)
                                                .clickable {
                                                    onTabSelected(LibraryFilter.ARTISTS)
                                                },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.add),
                                                contentDescription = stringResource(R.string.more_label),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.more_label),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = playerAwareBottomPadding),
        )
    }
}

@Composable
private fun SpotifyPlaylistCompactCard(
    playlist: SpotifyPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnailUrl = remember(playlist) { SpotifyMapper.getPlaylistThumbnail(playlist) }
    val cardBgColor =
        rememberArtworkCardColor(
            thumbnailUrl = thumbnailUrl,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "SpotifyPlaylistCompactCardScale",
    )

    Column(
        modifier =
            modifier
                .width(130.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(32.dp))
                .background(cardBgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(106.dp)
                    .clip(RoundedCornerShape(24.dp)),
        ) {
            AsyncImage(
                model = rememberSizedImageRequest(thumbnailUrl, 106.dp, 106.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.spotify_icon),
                    contentDescription = stringResource(R.string.spotify_account),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "${playlist.tracks?.total ?: 0} ${stringResource(R.string.tracks_label)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

/**
 * One artist in the Library tab's artists row, for either source.
 *
 * The row is the local library's shape, so the source changes the data and where a tap goes, not
 * the tile — which is why YouTube's and Spotify's artists share this one.
 */
@Composable
private fun ArtistTile(
    thumbnailUrl: String?,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isResolving: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .width(80.dp)
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = rememberSizedImageRequest(thumbnailUrl, 72.dp, 72.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape),
            )
            if (isResolving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * A rail's title and its "see all" link — the header both of the Library tab's rails wear.
 *
 * The title carries the source ("Your YouTube playlists" / "Your Spotify playlists"), so the rails
 * differ in their words and where the link goes, not in their shape.
 */
@Composable
private fun LibraryRailHeader(
    @StringRes titleRes: Int,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.see_all),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                ),
            modifier =
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * A Library rail's empty state, drawn in the section's own place.
 *
 * A rail the active source has nothing for keeps its header and shows this, so a source switch
 * swaps the contents and never the layout. Same gutter and tone as the mixes section's messages.
 */
@Composable
private fun LibraryRailEmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A rail's failure, drawn in the section's own place: what the read said went wrong and the call
 * that asks again, laid out as SpotifySectionList lays out a section's error.
 */
@Composable
private fun LibraryRailErrorText(
    message: String,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun TopMixesForYouSection(
    state: LibraryTopMixesUiState,
    onRefresh: () -> Unit,
    onConfigureAi: () -> Unit,
    onPlayMix: (LibraryTopMixUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LibraryTopMixesUiState.Loading -> {
            TopMixesMessageSection(
                message = stringResource(R.string.library_top_mixes_loading),
                isRefreshing = true,
                onRefresh = onRefresh,
                showRefresh = false,
                modifier = modifier,
            )
        }

        is LibraryTopMixesUiState.Empty -> {
            TopMixesEmptySection(
                reason = state.reason,
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                onConfigureAi = onConfigureAi,
                modifier = modifier,
            )
        }

        is LibraryTopMixesUiState.Error -> {
            TopMixesMessageSection(
                message = state.message,
                isRefreshing = false,
                onRefresh = onRefresh,
                showRefresh = true,
                modifier = modifier,
            )
        }

        is LibraryTopMixesUiState.Success -> {
            if (state.mixes.isEmpty()) {
                TopMixesMessageSection(
                    message = stringResource(R.string.library_top_mixes_no_recent_history),
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    showRefresh = true,
                    modifier = modifier,
                )
            } else {
                Column(modifier = modifier.fillMaxWidth()) {
                    TopMixesHeader(
                        isRefreshing = state.isRefreshing,
                        onRefresh = onRefresh,
                        showRefresh = true,
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(
                            items = state.mixes,
                            key = { mix -> mix.id },
                            contentType = { "library_top_mix" },
                        ) { mix ->
                            LibraryTopMixCard(
                                mix = mix,
                                onPlay = { onPlayMix(mix) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopMixesMessageSection(
    message: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    showRefresh: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopMixesHeader(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            showRefresh = showRefresh,
        )
        LibraryRailEmptyText(message)
    }
}

@Composable
private fun TopMixesEmptySection(
    reason: LibraryTopMixEmptyReason,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onConfigureAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopMixesHeader(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            showRefresh = reason != LibraryTopMixEmptyReason.AI_NOT_CONFIGURED,
        )
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(32.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            when (reason) {
                                LibraryTopMixEmptyReason.AI_NOT_CONFIGURED -> R.string.library_top_mixes_ai_not_configured_title
                                LibraryTopMixEmptyReason.NO_RECENT_HISTORY -> R.string.library_top_mixes_no_recent_history_title
                            },
                        ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        stringResource(
                            when (reason) {
                                LibraryTopMixEmptyReason.AI_NOT_CONFIGURED -> R.string.library_top_mixes_ai_not_configured_desc
                                LibraryTopMixEmptyReason.NO_RECENT_HISTORY -> R.string.library_top_mixes_no_recent_history
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reason == LibraryTopMixEmptyReason.AI_NOT_CONFIGURED) {
                    Button(onClick = onConfigureAi) {
                        Text(text = stringResource(R.string.library_top_mixes_configure_ai))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                    ) {
                        Text(text = stringResource(R.string.library_top_mixes_refresh))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopMixesHeader(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    showRefresh: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.top_mixes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (showRefresh) {
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.sync),
                        contentDescription = stringResource(R.string.library_top_mixes_refresh),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTopMixCard(
    mix: LibraryTopMixUiModel,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "LibraryTopMixCardScale",
    )

    Card(
        modifier =
            modifier
                .width(180.dp)
                .height(130.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onPlay,
                ),
        shape = RoundedCornerShape(32.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = mix.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = mix.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    mix.tracks.take(3).forEach { track ->
                        val artworkUrl = track.thumbnailUrl
                        if (artworkUrl == null) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                            )
                        } else {
                            AsyncImage(
                                model = rememberSizedImageRequest(artworkUrl, 28.dp, 28.dp),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onPlay,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MostPlayedAlbumSpotlightCard(
    album: MostPlayedAlbumUiModel,
    onOpenAlbum: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val isDark =
        MaterialTheme.colorScheme.surface.let {
            ColorUtils.calculateLuminance(it.toArgb()) < 0.5
        }
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val spotlightBg =
        remember(surfaceContainer, primaryColor, isDark) {
            if (isDark) {
                Color(ColorUtils.blendARGB(surfaceContainer.toArgb(), primaryColor.toArgb(), 0.12f))
            } else {
                Color(ColorUtils.blendARGB(surfaceContainer.toArgb(), primaryColor.toArgb(), 0.08f))
            }
        }
    val trackCountText = pluralStringResource(R.plurals.n_song, album.trackCount, album.trackCount)
    val backgroundBrush =
        remember(spotlightBg) {
            Brush.verticalGradient(
                colors =
                    listOf(
                        spotlightBg,
                        spotlightBg.copy(alpha = 0.9f),
                    ),
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(backgroundBrush)
                .clickable(onClick = onOpenAlbum)
                .padding(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(primaryColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumbnailUrl = album.thumbnailUrl
                    if (thumbnailUrl != null) {
                        AsyncImage(
                            model = rememberSizedImageRequest(thumbnailUrl, 64.dp, 64.dp),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.album),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(primaryColor.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.star),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.most_played_badge),
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp,
                                ),
                            color = primaryColor,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = album.title,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = trackCountText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlayAll,
                    shape = CircleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.play_all),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }

                IconButton(
                    onClick = onShuffle,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.shuffle),
                        contentDescription = stringResource(R.string.shuffle),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ShortcutCard(
    title: String,
    countText: String,
    iconRes: Int,
    containerColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ShortcutCardScale",
    )

    val isDark =
        MaterialTheme.colorScheme.surface.let {
            ColorUtils.calculateLuminance(it.toArgb()) < 0.5
        }

    val surfaceContainerColor = MaterialTheme.colorScheme.surfaceContainer
    val finalBgColor =
        remember(surfaceContainerColor, iconColor, isDark) {
            if (isDark) {
                Color(ColorUtils.blendARGB(surfaceContainerColor.toArgb(), iconColor.toArgb(), 0.08f))
            } else {
                Color(ColorUtils.blendARGB(surfaceContainerColor.toArgb(), iconColor.toArgb(), 0.06f))
            }
        }

    val iconBgColor =
        remember(iconColor, isDark) {
            if (isDark) {
                iconColor.copy(alpha = 0.16f)
            } else {
                iconColor.copy(alpha = 0.10f)
            }
        }

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(26.dp))
                .background(finalBgColor)
                .clickable(
                    interactionSource = interactionSource,
                    onClick = onClick,
                ).padding(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(iconBgColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
