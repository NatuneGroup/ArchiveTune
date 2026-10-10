/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b (home screen exact port)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import moe.rukamori.archivetune.playback.queues.Queue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.HomeCatalogueSwitchKey
import moe.rukamori.archivetune.constants.QuickPicks
import moe.rukamori.archivetune.home.HomeAction
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.HomeUiState
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.HomeViewModel
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.menu.AlbumMenu
import moe.rukamori.archivetune.ui.menu.ArtistMenu
import moe.rukamori.archivetune.ui.menu.PlaylistMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val HomeFeedMaxWidth = 1_200.dp

private val HomeSectionSpacing = 26.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    headerScrollConnection: NestedScrollConnection? = null,
    listState: LazyListState? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current

    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val isPlaying = playerConnection?.isPlaying?.collectAsStateWithLifecycle()?.value ?: false
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value
    var pendingQueue by remember { mutableStateOf<Queue?>(null) }
    val onPlayQueue: (Queue) -> Unit = { queue ->
        if (playerConnection == null) pendingQueue = queue else playerConnection.playQueue(queue)
    }
    LaunchedEffect(playerConnection, pendingQueue) {
        val connection = playerConnection ?: return@LaunchedEffect
        val queue = pendingQueue ?: return@LaunchedEffect
        pendingQueue = null
        connection.playQueue(queue)
    }
    androidx.activity.compose.ReportDrawnWhen { screenState !is HomeScreenState.Loading }

    val lazyListState = listState ?: rememberLazyListState()
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry
            ?.savedStateHandle
            ?.getStateFlow("scrollToTop", false)
            ?.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val successState = screenState as? HomeScreenState.Success
    val uiState = successState?.uiState
    val selectedChip = uiState?.selectedChip

    LaunchedEffect(uiState?.homePage?.continuation) {
        val continuation = uiState?.homePage?.continuation ?: return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) {
                viewModel.onAction(HomeAction.LoadMore(continuation))
            }
        }
    }

    if (selectedChip != null) {
        BackHandler {
            viewModel.onAction(HomeAction.SelectChip(selectedChip))
        }
    }

    LaunchedEffect(uiState?.showCategoryChips, selectedChip) {
        if (uiState?.showCategoryChips == false && selectedChip != null) {
            viewModel.onAction(HomeAction.SelectChip(selectedChip))
        }
    }

    val homeHazeState = LocalHomeHazeState.current
    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .let { m -> if (homeHazeState != null) m.hazeSource(homeHazeState) else m }
                .then(
                    if (headerScrollConnection != null) {
                        Modifier.nestedScroll(headerScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        // The home screen keeps its ORIGINAL full-intensity atmosphere wash
        // (light mode) while blur effects are on — the light-mode look was
        // never meant to change. In dark mode this draws nothing and the
        // root layer's subtle gradient shows through instead.
        if (!disableBlur) {
            HomeAtmosphereBackground()
        }
        when (val state = screenState) {
            HomeScreenState.Loading -> {
                HomeSkeletonFeed()
            }

            HomeScreenState.Empty -> {
                HomeStatePane(
                    iconResId = R.drawable.music_note,
                    messageResId = R.string.no_results_found,
                    actionResId = R.string.retry,
                    onAction = { viewModel.onAction(HomeAction.Refresh) },
                )
            }

            is HomeScreenState.Error -> {
                HomeStatePane(
                    iconResId = R.drawable.info,
                    messageResId = state.messageResId,
                    actionResId = R.string.retry,
                    onAction = { viewModel.onAction(HomeAction.Refresh) },
                )
            }

            is HomeScreenState.Success -> {
                HomeContent(
                    uiState = state.uiState,
                    mediaMetadata = mediaMetadata,
                    isPlaying = isPlaying,
                    navController = navController,
                    playerConnection = playerConnection,
                    onPlayQueue = onPlayQueue,
                    menuState = menuState,
                    haptic = haptic,
                    scope = scope,
                    lazyListState = lazyListState,
                    onAction = viewModel::onAction,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeStatePane(
    @DrawableRes iconResId: Int?,
    @StringRes messageResId: Int?,
    modifier: Modifier = Modifier,
    @StringRes actionResId: Int? = null,
    showLoadingIndicator: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (showLoadingIndicator) {
                LoadingIndicator()
            } else {
                iconResId?.let {
                    Icon(
                        painter = painterResource(it),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
                messageResId?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (actionResId != null && onAction != null) {
                    Spacer(Modifier.height(20.dp))
                    FilledTonalButton(onClick = onAction) {
                        Text(stringResource(actionResId))
                    }
                }
            }
        }
    }
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
private fun HomeContent(
    uiState: HomeUiState,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection?,
    onPlayQueue: (Queue) -> Unit,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteQuickPicks =
        uiState
            .takeIf { it.quickPicksMode == QuickPicks.QUICK_PICKS }
            ?.remoteQuickPicks
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { onAction(HomeAction.Refresh) },
            state = pullState,
            indicator = {},
            modifier = Modifier.fillMaxSize(),
        ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val allRemoteSections = uiState.homePage?.sections.orEmpty()
                val (livePerformanceSections, otherRemoteSections) =
                    remember(allRemoteSections) {
                        val live =
                            allRemoteSections
                                .filter { section ->
                                    section.title.contains("Live performance", ignoreCase = true)
                                }.filter { it.items.isNotEmpty() }
                        val other =
                            allRemoteSections
                                .filter { section ->
                                    !section.title.contains("Live performance", ignoreCase = true)
                                }.filter { it.items.isNotEmpty() }
                        live to other
                    }

                val (homeCatalogueSwitchEnabled, _) =
                    rememberPreference(HomeCatalogueSwitchKey, defaultValue = false)

                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                    modifier =
                        Modifier
                            .widthIn(max = HomeFeedMaxWidth)
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                ) {
                    item(
                        key = "home_greeting_title",
                        contentType = "greeting_title",
                    ) {
                        HomeWelcomeHeader(
                            accountName = uiState.accountName,
                            modifier = Modifier.animateItem(),
                        )
                    }

                    val availableWidth = maxWidth
                    val minimalMode = uiState.minimalHomeMode

                    if (uiState.recentlyPlayed.size > 1) {
                        item(
                            key = "home_recently_played",
                            contentType = "recently_played",
                        ) {
                            val recentSongs =
                                remember(uiState.recentlyPlayed) {
                                    uiState.recentlyPlayed.distinctBy { it.id }
                                }
                            Column(Modifier.animateItem()) {
                                BitChordRecentsShelf(
                                    songs = recentSongs,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    availableWidth = availableWidth,
                                    onPlaySong = { song, index ->
                                        onPlayQueue(
                                            moe.rukamori.archivetune.playback.queues.ListQueue(
                                                title = context.getString(R.string.home_recently_played),
                                                items = recentSongs.map { it.toMediaItem() },
                                                startIndex = index,
                                            ),
                                        )
                                    },
                                    onSongLongClick = { song ->
                                        menuState.show {
                                            moe.rukamori.archivetune.ui.menu.SongMenu(
                                                originalSong = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (uiState.heroPicks.isNotEmpty()) {
                        item(
                            key = "home_jump_back_in",
                            contentType = "jump_back_in",
                        ) {
                            val heroSongs =
                                remember(uiState.heroPicks) {
                                    uiState.heroPicks.distinctBy { it.id }
                                }
                            Column(Modifier.animateItem()) {
                                BitChordSectionHeader(title = stringResource(R.string.home_jump_back_in_badge))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                                    horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
                                ) {
                                    items(heroSongs, key = { "hero_${it.id}" }) { song ->
                                        BitChordHeroCard(
                                            artworkUrl = song.thumbnailUrl,
                                            title = song.song.title,
                                            subtitle = song.artists.joinToString { it.name },
                                            isCurrent = mediaMetadata?.id == song.id,
                                            isPlaying = isPlaying,
                                            modifier = Modifier.width(bitChordHeroCardWidth(availableWidth)),
                                            onClick = {
                                                onPlayQueue(
                                                    moe.rukamori.archivetune.playback.queues.ListQueue(
                                                        title = context.getString(R.string.home_jump_back_in_badge),
                                                        items = heroSongs.map { it.toMediaItem() },
                                                        startIndex = heroSongs.indexOf(song),
                                                    ),
                                                )
                                            },
                                            onLongClick = {
                                                menuState.show {
                                                    moe.rukamori.archivetune.ui.menu.SongMenu(
                                                        originalSong = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(BitChordShelfBottomSpacing))
                            }
                        }
                    }

                    if (remoteQuickPicks?.items?.isNotEmpty() == true) {
                        item(
                            key = "home_remote_quick_picks",
                            contentType = "media_shelf",
                        ) {
                            BitChordYtItemShelf(
                                title = remoteQuickPicks.title,
                                items = remoteQuickPicks.items,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (uiState.keepListening.isNotEmpty()) {
                        item(
                            key = "home_keep_listening",
                            contentType = "media_shelf",
                        ) {
                            BitChordLocalItemShelf(
                                title = stringResource(R.string.keep_listening),
                                items = uiState.keepListening,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                scope = scope,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (uiState.speedDialItems.isNotEmpty()) {
                        item(
                            key = "home_speed_dial",
                            contentType = "speed_dial",
                        ) {
                            BitChordLocalItemShelf(
                                title = stringResource(R.string.speed_dial),
                                items = uiState.speedDialItems,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                scope = scope,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    livePerformanceSections.forEachIndexed { index, section ->
                        val sectionKey = "${section.endpoint?.browseId ?: section.title}_$index"
                        item(
                            key = "home_live_performances_$sectionKey",
                            contentType = "media_shelf",
                        ) {
                            BitChordYtItemShelf(
                                title = section.title,
                                items = section.items,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (!minimalMode && uiState.accountPlaylists.isNotEmpty()) {
                        item(
                            key = "home_account_playlists",
                            contentType = "media_shelf",
                        ) {
                            Column(Modifier.animateItem()) {
                                BitChordSectionHeader(
                                    title = stringResource(R.string.your_youtube_playlists),
                                    subtitle = uiState.accountName.takeIf { it.isNotBlank() },
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                                    horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
                                ) {
                                    items(
                                        uiState.accountPlaylists,
                                        key = { "account_playlist_${it.id}" },
                                    ) { playlist ->
                                        BitChordShelfCard(
                                            artworkUrl = playlist.thumbnail,
                                            title = playlist.title,
                                            subtitle = playlist.songCountText ?: "",
                                            isCurrent = false,
                                            isPlaying = false,
                                            onClick = { navController.navigate("online_playlist/${playlist.id}") },
                                            onLongClick = {},
                                        )
                                    }
                                }
                                Spacer(Modifier.height(BitChordShelfBottomSpacing))
                            }
                        }
                    }

                    if (!minimalMode && uiState.forgottenFavorites.isNotEmpty()) {
                        item(
                            key = "home_forgotten_favorites",
                            contentType = "song_shelf",
                        ) {
                            BitChordSongShelf(
                                title = stringResource(R.string.forgotten_favorites),
                                songs = uiState.forgottenFavorites,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                menuState = menuState,
                                onPlayQueue = onPlayQueue,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (!minimalMode) {
                        uiState.similarRecommendations.forEach { recommendation ->
                            item(
                                key = "home_similar_${recommendation.title.id}",
                                contentType = "media_shelf",
                            ) {
                                BitChordYtItemShelf(
                                    title = recommendation.title.title,
                                    items = recommendation.items,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    if (!minimalMode) {
                        otherRemoteSections.forEachIndexed { index, section ->
                            val sectionKey = "${section.endpoint?.browseId ?: section.title}_$index"
                            item(
                                key = "home_remote_$sectionKey",
                                contentType = "media_shelf",
                            ) {
                                BitChordYtItemShelf(
                                    title = section.title,
                                    items = section.items,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    if (uiState.isLoadingMore) {
                        homeFeedMoreSkeleton()
                    }
                }
        }
        }

        HomePullRefreshLine(
            refreshing = uiState.isRefreshing,
            distanceFraction = { pullState.distanceFraction },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateTopPadding()),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionSpacer(key: String) {
    item(
        key = "home_section_spacer_$key",
        contentType = "section_spacer",
    ) {
        Spacer(Modifier.height(HomeSectionSpacing))
    }
}

@Composable
internal fun HomeSkeletonFeed(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier =
            modifier
                .widthIn(max = HomeFeedMaxWidth)
                .fillMaxWidth(),
    ) {
        item(key = "home_skeleton_greeting") {
            HomeShimmerBox(
                modifier =
                    Modifier
                        .padding(horizontal = BitChordPageGutter)
                        .padding(vertical = 14.dp)
                        .fillMaxWidth(0.55f)
                        .height(34.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            )
        }
        homeFeedSkeleton()
    }
}

@Composable
private fun BitChordSongShelf(
    title: String,
    songs: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    menuState: MenuState,
    onPlayQueue: (moe.rukamori.archivetune.playback.queues.Queue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val distinct = remember(songs) { songs.distinctBy { it.id } }
    Column(modifier) {
        BitChordSectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = BitChordPageGutter),
            horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
        ) {
            items(distinct, key = { "song_${it.id}" }) { song ->
                BitChordShelfCard(
                    artworkUrl = song.thumbnailUrl,
                    title = song.song.title,
                    subtitle = song.artists.joinToString { it.name },
                    isCurrent = mediaMetadata?.id == song.id,
                    isPlaying = isPlaying,
                    onClick = {
                        onPlayQueue(
                            ListQueue(
                                title = title,
                                items = distinct.map { it.toMediaItem() },
                                startIndex = distinct.indexOf(song),
                            ),
                        )
                    },
                    onLongClick = {
                        menuState.show {
                            SongMenu(
                                originalSong = song,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(BitChordShelfBottomSpacing))
    }
}

@Composable
private fun BitChordLocalItemShelf(
    title: String,
    items: List<LocalItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: MenuState,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        BitChordSectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = BitChordPageGutter),
            horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
        ) {
            items(items, key = { "local_${it.id}" }) { localItem ->
                val isActive = mediaMetadata?.id == localItem.id
                BitChordShelfCard(
                    artworkUrl = localItem.thumbnailUrl,
                    title = localItem.title,
                    subtitle =
                        when (localItem) {
                            is Song -> localItem.artists.joinToString { it.name }
                            is Album -> localItem.artists.joinToString { it.name }
                            is Playlist -> androidx.compose.ui.res.pluralStringResource(
                                R.plurals.n_song,
                                localItem.songCount,
                                localItem.songCount,
                            )
                            is Artist -> ""
                            else -> ""
                        },
                    isCurrent = isActive,
                    isPlaying = isPlaying,
                    onClick = {
                        when (localItem) {
                            is Song -> {
                                if (isActive) {
                                    playerConnection?.player?.togglePlayPause()
                                } else {
                                    playerConnection?.playQueue(
                                        ListQueue(
                                            title = title,
                                            items = items.filterIsInstance<Song>().map { it.toMediaItem() },
                                        ),
                                    )
                                }
                            }

                            is Album -> navController.navigate("album/${localItem.id}")

                            is Artist -> navController.navigate("artist/${localItem.id}")

                            is Playlist -> navController.navigate("local_playlist/${localItem.id}")

                            else -> {}
                        }
                    },
                    onLongClick = {
                        menuState.show {
                            when (localItem) {
                                is Song ->
                                    SongMenu(
                                        originalSong = localItem,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )

                                is Album ->
                                    AlbumMenu(
                                        originalAlbum = localItem,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )

                                is Artist ->
                                    ArtistMenu(
                                        originalArtist = localItem,
                                        coroutineScope = scope,
                                        onDismiss = menuState::dismiss,
                                    )

                                is Playlist ->
                                    PlaylistMenu(
                                        playlist = localItem,
                                        coroutineScope = scope,
                                        onDismiss = menuState::dismiss,
                                    )

                                else -> {}
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(BitChordShelfBottomSpacing))
    }
}

@Composable
private fun BitChordYtItemShelf(
    title: String,
    items: List<YTItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: MenuState,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        BitChordSectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = BitChordPageGutter),
            horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
        ) {
            items(items, key = { "yt_${it.id}" }) { item ->
                BitChordShelfCard(
                    artworkUrl = item.thumbnail,
                    title = item.title,
                    subtitle =
                        when (item) {
                            is SongItem -> item.artists?.joinToString { it.name } ?: ""
                            is AlbumItem -> item.artists?.joinToString { it.name } ?: ""
                            is ArtistItem -> ""
                            is PlaylistItem -> item.songCountText ?: ""
                            else -> ""
                        },
                    isCurrent =
                        when (item) {
                            is SongItem -> mediaMetadata?.id == item.id
                            is AlbumItem -> mediaMetadata?.album?.id == item.id
                            else -> false
                        },
                    isPlaying = isPlaying,
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                if (item.id == mediaMetadata?.id) {
                                    playerConnection?.player?.togglePlayPause()
                                } else {
                                    playerConnection?.playQueue(
                                        YouTubeQueue(
                                            item.endpoint ?: WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata(),
                                        ),
                                    )
                                }
                            }

                            is AlbumItem -> navController.navigate("album/${item.id}")

                            is ArtistItem -> navController.navigate("artist/${item.id}")

                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")

                            else -> {}
                        }
                    },
                    onLongClick = {
                        val songItem = item as? SongItem
                        if (songItem != null) {
                            menuState.show {
                                YouTubeSongMenu(
                                    song = songItem,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(BitChordShelfBottomSpacing))
    }
}
