/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.valentinilk.shimmer.shimmer
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import android.os.Build
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.DraggableScrollbar
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LiquidGlassIconButton
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.MediaDetailAction
import moe.rukamori.archivetune.ui.component.MediaDetailHero
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.shimmer.ButtonPlaceholder
import moe.rukamori.archivetune.ui.component.shimmer.ListItemPlaceHolder
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.menu.SelectionMediaMetadataMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.HeaderDownloadProgressIndicator
import moe.rukamori.archivetune.ui.utils.HeaderDownloadState
import moe.rukamori.archivetune.ui.utils.ItemWrapper
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.formatCompactCount
import moe.rukamori.archivetune.ui.utils.headerDownloadState
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.OnlinePlaylistViewModel
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OnlinePlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: OnlinePlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val viewCounts by viewModel.viewCounts.collectAsStateWithLifecycle()
    val dbPlaylist by viewModel.dbPlaylist.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val canvasArtwork by viewModel.canvasArtwork.collectAsStateWithLifecycle()
    val pageCanvasEnabled by rememberPreference(key = AlbumCanvasEnabledKey, defaultValue = true)
    val downloadUtil = LocalDownloadUtil.current
    var downloads by remember { mutableStateOf<Map<String, Download>>(emptyMap()) }
    var downloadState by remember { mutableStateOf<HeaderDownloadState>(HeaderDownloadState.None) }
    val globalDownloadState = remember(downloads) {
        val activeDownloads = downloads.values.filter {
            it.state == Download.STATE_DOWNLOADING ||
            it.state == Download.STATE_QUEUED ||
            it.state == Download.STATE_RESTARTING ||
            it.state == Download.STATE_STOPPED
        }
        if (activeDownloads.isEmpty()) {
            HeaderDownloadState.None
        } else {
            var progressTotal = 0f
            var hasRunning = false
            var hasPaused = false
            activeDownloads.forEach { download ->
                val progress = download.percentDownloaded.takeIf { it >= 0f }?.div(100f) ?: 0f
                progressTotal += progress.coerceIn(0f, 1f)
                if (download.state == Download.STATE_STOPPED) {
                    hasPaused = hasPaused || download.stopReason == 1
                } else {
                    hasRunning = true
                }
            }
            HeaderDownloadState.Partial(
                progress = progressTotal / activeDownloads.size,
                paused = hasPaused && !hasRunning,
            )
        }
    }

    var selection by remember { mutableStateOf(false) }
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    // Liquid Glass master toggle + lyrics-overlay gate. OnlinePlaylistScreen
    // previously applied layerBackdrop + LiquidGlass header pills UNCONDITIONALLY
    // — even when the user had LiquidGlass turned off in settings, and even when
    // the full-screen lyrics overlay was open on top. The unconditional recording
    // + RuntimeShader sampling starves the 60 Hz karaoke lyrics sweep of GPU
    // budget, causing the 'enhanced word-synced lyrics lag when launched from a
    // playlist page' bug. HomeScreen has no LiquidGlass, which is why the same
    // lyrics path doesn't lag from home.
    val liquidGlassEnabled = rememberLiquidGlassEnabled()
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen

    // Stable top inset: does not collapse to 0 when the status bar is transiently hidden,
    // so the search bar offset and the playlist header always stay anchored below the TopAppBar.
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by
        rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    val filteredSongs =
        remember(songs, query) {
            if (query.text.isEmpty()) {
                songs.mapIndexed { index, song -> index to song }
            } else {
                songs
                    .mapIndexed { index, song -> index to song }
                    .filter { (_, song) ->
                        song.title.contains(query.text, ignoreCase = true) ||
                            song.artists.fastAny { it.name.contains(query.text, ignoreCase = true) }
                    }
            }
        }

    val focusRequester = remember { FocusRequester() }

    // Save scroll position when entering search, restore when leaving.
    // The header item collapses to height 0 when isSearching becomes true, which
    // shifts all items below it. By saving firstVisibleItemIndex + scrollOffset
    // BEFORE the collapse and restoring them AFTER the expand, the visible
    // position is preserved across open/close search.
    var savedScrollIndex by remember { mutableIntStateOf(0) }
    var savedScrollOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            savedScrollIndex = lazyListState.firstVisibleItemIndex
            savedScrollOffset = lazyListState.firstVisibleItemScrollOffset
            focusRequester.requestFocus()
        } else {
            // Wait one frame for the header to re-expand before restoring.
            withFrameNanos {}
            lazyListState.scrollToItem(savedScrollIndex, savedScrollOffset)
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (selection) {
        BackHandler { selection = false }
    }

    val wrappedSongs =
        // The MutableStateList must be created INSIDE remember: calling
        // toMutableStateList() on the remembered result rebuilt a fresh list
        // instance on every recomposition of this screen (playback state,
        // view counts and download maps all recompose it constantly while a
        // song plays), which re-invalidated the LazyColumn's items block and
        // churned allocations every frame — one of the causes of laggy
        // playlist scrolling. Selection stays observable: ItemWrapper.isSelected
        // is itself a mutableStateOf.
        remember(filteredSongs) {
            filteredSongs.map { item -> ItemWrapper(item) }.toMutableStateList()
        }

    LaunchedEffect(songs) {
        val songIds = songs.map { it.id }
        if (songIds.isEmpty()) {
            downloads = emptyMap()
            downloadState = HeaderDownloadState.None
            return@LaunchedEffect
        }
        downloadUtil.downloads.collect { currentDownloads ->
            downloads = currentDownloads
            downloadState = headerDownloadState(songIds, currentDownloads)
        }
    }

    val showTopBarTitle by remember { derivedStateOf { lazyListState.firstVisibleItemIndex > 0 } }

    val surfaceColor = MaterialTheme.colorScheme.surface

    val transparentAppBar by remember {
        derivedStateOf { !selection && !isSearching && !showTopBarTitle }
    }

    val headerItems by remember {
        derivedStateOf {
            val current = playlist
            if (!isLoading && current != null && !isSearching) 1 else 0
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }.collect { lastVisibleIndex ->
            if (
                songs.size >= 5 &&
                lastVisibleIndex != null &&
                lastVisibleIndex >= songs.size - 5
            ) {
                viewModel.loadMoreSongs()
            }
        }
    }

    // Liquid Glass backdrop: created unconditionally (cheap — just a GraphicsLayer
    // handle). The actual content recording happens when
    // `Modifier.layerBackdrop(artworkBackdrop)` is applied to the LazyColumn below.
    // This matches the LocalPlaylistScreen pattern: the backdrop captures the entire
    // scrolling content, and the floating Liquid Glass header buttons are siblings of
    // the LazyColumn (not nested inside its first item) so they sample the backdrop
    // without being recorded into it — and, critically, their click handlers are not
    // competing with any LazyColumn-item pointer-input stack.
    val artworkBackdrop = rememberBackdrop(Color.Black)

    ExpressivePullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor),
    ) {
        LazyColumn(
            state = lazyListState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (layerBackdropActive) {
                            Modifier.layerBackdrop(artworkBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            contentPadding =
                PaddingValues(
                    // When searching, the header item collapses to zero height and the
                    // TopAppBar (which renders the search field) is overlaid on top of
                    // the LazyColumn. Reserve top space so the first songs aren't hidden
                    // behind the TopAppBar + status bar.
                    top = if (isSearching) systemBarsTopPadding + 64.dp else 0.dp,
                    bottom =
                        LocalPlayerAwareWindowInsets.current
                            .union(WindowInsets.ime)
                            .asPaddingValues()
                            .calculateBottomPadding(),
                ),
        ) {
            playlist.let { playlistSnapshot ->
                if (isLoading) {
                    item(key = "shimmer") {
                        ShimmerHost {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 560.dp)
                                        .shimmer()
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .padding(horizontal = 24.dp, vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    TextPlaceholder(
                                        height = 36.dp,
                                        modifier = Modifier.fillMaxWidth(0.55f),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    TextPlaceholder(
                                        height = 18.dp,
                                        modifier = Modifier.fillMaxWidth(0.4f),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    TextPlaceholder(
                                        height = 14.dp,
                                        modifier = Modifier.fillMaxWidth(0.72f),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(
                                                12.dp,
                                                Alignment.CenterHorizontally,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        repeat(2) { index ->
                                            if (index == 1) {
                                                ButtonPlaceholder(
                                                    modifier =
                                                        Modifier
                                                            .width(132.dp)
                                                            .height(48.dp),
                                                )
                                            }
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            MaterialTheme.colorScheme.onSurface,
                                                        ),
                                            )
                                        }
                                    }
                                }
                            }

                            repeat(6) { ListItemPlaceHolder() }
                        }
                    }
                } else if (playlistSnapshot != null) {
                    // Capture into a local non-null val so the smart-cast survives any
                    // recomposition timing inside the LazyColumn item lambda — Compose may
                    // invoke the item{} lambda on a later pass and the outer StateFlow value
                    // could in theory have flipped back to null, which previously risked an
                    // NPE on `playlist.title` / `playlist.thumbnail` access.
                    val playlist = playlistSnapshot
                    item(key = "header") {
                        if (!isSearching) {
                            val author =
                                playlist.author?.let { artist ->
                                    remember(artist) {
                                        buildAnnotatedString {
                                            if (artist.id != null) {
                                                withLink(
                                                    LinkAnnotation.Clickable(artist.id!!) {
                                                        navController.navigate(
                                                            "artist/${artist.id}",
                                                        )
                                                    },
                                                ) {
                                                    append(artist.name)
                                                }
                                            } else {
                                                append(artist.name)
                                            }
                                        }
                                    }
                                }
                            val isBookmarked = dbPlaylist?.playlist?.bookmarkedAt != null
                            val fallbackPlaySong = songs.firstOrNull()

                            // SimpMusic-style liquid glass backdrop source: the LazyColumn itself
                            // carries Modifier.layerBackdrop (see the LazyColumn definition above),
                            // so the entire scrolling content is recorded into the backdrop.
                            MediaDetailHero(
                                title = playlist.title,
                                thumbnailUrl = playlist.thumbnail,
                                fallbackIcon = R.drawable.queue_music,
                                systemBarsTopPadding = systemBarsTopPadding,
                                subtitle = author,
                                metadata = playlist.songCountText,
                                description = playlist.description,
                                isAdded = isBookmarked,
                                addContentDescription = R.string.add_to_library,
                                removeContentDescription = R.string.remove_from_library,
                                canvasPrimaryUrl =
                                    (canvasArtwork?.animated ?: canvasArtwork?.videoUrl)
                                        ?.takeIf { pageCanvasEnabled },
                                canvasFallbackUrl = canvasArtwork?.videoUrl?.takeIf { pageCanvasEnabled },
                                canvasIsPlaying = true,
                                canvasVisible = !lyricsFullScreen,
                                onShuffle =
                                    playlist.shuffleEndpoint?.let { shuffleEndpoint ->
                                        {
                                            playerConnection.playQueue(
                                                YouTubeQueue.playlist(shuffleEndpoint),
                                            )
                                        }
                                    },
                                onPlay =
                                    playlist.playEndpoint?.let { playEndpoint ->
                                        {
                                            playerConnection.playQueue(
                                                YouTubeQueue.playlist(playEndpoint),
                                            )
                                        }
                                    } ?: fallbackPlaySong?.let { firstSong ->
                                        {
                                            playerConnection.playQueue(
                                                YouTubeQueue.playlist(
                                                    endpoint =
                                                        firstSong.toPlaylistPlaybackEndpoint(
                                                            playlistId = playlist.id,
                                                            playlistPlayParams = null,
                                                        ),
                                                    preloadItem = firstSong.toMediaMetadata(),
                                                ),
                                            )
                                        }
                                    },
                                onToggleAdd = null,
                                additionalPrimaryActions = { contentColor ->
                                    if (songs.isNotEmpty()) {
                                        MediaDetailAction(
                                            contentDescription =
                                                if (downloadState == HeaderDownloadState.Completed) {
                                                    R.string.remove_download
                                                } else {
                                                    R.string.download
                                                },
                                            contentColor = contentColor,
                                            onClick = {
                                                when (downloadState) {
                                                    HeaderDownloadState.Completed -> {
                                                        sendRemoveDownloads(
                                                            context = context,
                                                            songIds = songs.map { it.id },
                                                        )
                                                    }

                                                    is HeaderDownloadState.Partial -> {
                                                        sendRemoveDownloads(
                                                            context = context,
                                                            songIds = songs.map { it.id },
                                                        )
                                                    }

                                                    HeaderDownloadState.None -> {
                                                        sendAddMissingDownloads(
                                                            context = context,
                                                            songs =
                                                                songs.map { song ->
                                                                    HeaderDownloadItem(
                                                                        id = song.id,
                                                                        title = song.title,
                                                                    )
                                                                },
                                                            downloads = downloads,
                                                        )
                                                        navController.navigate(
                                                            "auto_playlist/downloaded?tab=progress",
                                                        )
                                                    }
                                                }
                                            },
                                        ) {
                                            when (val state = downloadState) {
                                                HeaderDownloadState.Completed -> {
                                                    Icon(
                                                        painter = painterResource(R.drawable.offline),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(22.dp),
                                                    )
                                                }

                                                is HeaderDownloadState.Partial -> {
                                                    HeaderDownloadProgressIndicator(
                                                        progress = state.progress,
                                                        paused = state.paused,
                                                        icon = R.drawable.download,
                                                    )
                                                }

                                                HeaderDownloadState.None -> {
                                                    Icon(
                                                        painter = painterResource(R.drawable.download),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(22.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                // REMOVED Modifier.animateItem(): the header is a
                                // static first item that never needs placement
                                // animation. animateItem() was causing the header
                                // to briefly shift position ("goes up for a split
                                // second and comes back") when a LiquidGlass header
                                // icon was clicked — the state change triggered a
                                // LazyColumn layout pass, and animateItem()
                                // animated the resulting placement delta.
                                useBlurredPlayButton = true,
                            )
                        }
                    }

                    if (songs.isEmpty() && !isLoading && error == null) {
                        item(key = "empty") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.empty_playlist),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.empty_playlist_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Songs List
                    items(
                        items = wrappedSongs,
                        key = { it.item.second.setVideoId ?: "${it.item.second.id}-${it.item.first}" },
                        contentType = { "online_playlist_song" },
                    ) { song ->
                        YouTubeListItem(
                            item = song.item.second,
                            viewCountText =
                                viewCounts[song.item.second.id]?.let { count ->
                                    formatCompactCount(count.toLong())
                                },
                            isActive = mediaMetadata?.id == song.item.second.id,
                            isPlaying = isPlaying,
                            isSelected = song.isSelected && selection,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song.item.second,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                    onLongClick = {},
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .combinedClickable(
                                        enabled = !hideExplicit || !song.item.second.explicit,
                                        onClick = {
                                            if (!selection) {
                                                if (song.item.second.id == mediaMetadata?.id) {
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        YouTubeQueue.playlist(
                                                            endpoint =
                                                                song.item.second
                                                                    .toPlaylistPlaybackEndpoint(
                                                                        playlistId = playlist.id,
                                                                        playlistPlayParams =
                                                                            playlist.playEndpoint
                                                                                ?.params,
                                                                    ),
                                                            preloadItem = song.item.second.toMediaMetadata(),
                                                        ),
                                                    )
                                                }
                                            } else {
                                                song.isSelected = !song.isSelected
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                            if (!selection) {
                                                selection = true
                                            }
                                            wrappedSongs.forEach { it.isSelected = false }
                                            song.isSelected = true
                                        },
                                    ).animateItem(),
                        )
                    }

                    if (viewModel.continuation != null && songs.isNotEmpty() && isLoadingMore) {
                        item(key = "loading_more") {
                            ShimmerHost { repeat(2) { ListItemPlaceHolder() } }
                        }
                    }
                } else {
                    val isPrivatePlaylist = error?.contains("PLAYLIST_PRIVATE") == true
                    item(key = "error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (isPrivatePlaylist) {
                                Image(
                                    painter = painterResource(R.drawable.anime_blank),
                                    contentDescription = null,
                                    modifier = Modifier.size(120.dp),
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.playlist_private_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.playlist_private_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Text(
                                    text =
                                        if (error != null) {
                                            stringResource(R.string.error_unknown)
                                        } else {
                                            stringResource(R.string.playlist_not_found)
                                        },
                                    style = MaterialTheme.typography.titleLarge,
                                    color =
                                        if (error != null) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        if (error != null) {
                                            error!!
                                        } else {
                                            stringResource(R.string.playlist_not_found_desc)
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (error != null) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { viewModel.retry() }, shapes = ButtonDefaults.shapes()) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        DraggableScrollbar(
            modifier =
                Modifier
                    .padding(
                        LocalPlayerAwareWindowInsets.current
                            .union(WindowInsets.ime)
                            .asPaddingValues(),
                    ).align(Alignment.CenterEnd),
            scrollState = lazyListState,
            headerItems = headerItems,
        )

        // Persistent Liquid Glass header buttons. Siblings of the LazyColumn (children of the
        // ExpressivePullToRefreshBox), positioned at top-start and top-end.
        val currentPlaylistForGlass = playlist
        if (layerBackdropActive && !selection && !isSearching && currentPlaylistForGlass != null) {
            LiquidGlassIconButton(
                backdrop = artworkBackdrop,
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp)
                        .size(48.dp),
                onClick = { navController.navigateUp() },
            )
            LiquidGlassActionPill(
                backdrop = artworkBackdrop,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                // Search
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.IconButton(onClick = { isSearching = true }) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }
                // More
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.IconButton(onClick = {
                        menuState.show {
                            YouTubePlaylistMenu(
                                playlist = currentPlaylistForGlass,
                                songs = songs,
                                coroutineScope = coroutineScope,
                                onDismiss = menuState::dismiss,
                                selectAction = { selection = true },
                                canSelect = true,
                                snackbarHostState = snackbarHostState,
                            )
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        // Top App Bar — hidden when the Liquid Glass header buttons are visible
        // (matches LocalPlaylistScreen pattern). The Liquid Glass back button and
        // search+more pill handle navigation/actions when the hero is visible.
        // With glass active the TopAppBar is only rendered during selection mode and
        // search mode, where it provides the selection count / search TextField; without
        // glass (off, or overridden by Disable blur) it is the plain header. Rendering the
        // TopAppBar on top of the Liquid Glass buttons (even when transparent and
        // with empty actions) causes it to intercept pointer events in the top
        // area, making the Liquid Glass buttons unclickable.
        if (!liquidGlassHeaderActive || selection || isSearching) {
            val topAppBarColors =
                if (transparentAppBar) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        navigationIconContentColor = Color.White,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = Color.Transparent,
                    )
                }

        TopAppBar(
            colors = topAppBarColors,
            windowInsets =
                WindowInsets(top = systemBarsTopPadding)
                    .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
            title = {
                if (selection) {
                    val count = wrappedSongs.count { it.isSelected }
                    Text(
                        text = pluralStringResource(R.plurals.n_song, count, count),
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                } else if (showTopBarTitle) {
                    Text(playlist?.title.orEmpty())
                }
            },
            navigationIcon = {
                // Hide the back arrow when the SimpMusic-style floating liquid
                // glass back button is visible (artwork shown, not searching,
                // not in selection mode, not scrolled). In selection / searching
                // / scrolled state, the floating button has scrolled out of
                // view so we need the TopAppBar's own back/close icon.
                if (isSearching || selection || showTopBarTitle || !liquidGlassHeaderActive) {
                    IconButton(
                        onClick = {
                            if (isSearching) {
                                isSearching = false
                                query = TextFieldValue()
                            } else if (selection) {
                                selection = false
                            } else {
                                navController.navigateUp()
                            }
                        },
                        onLongClick = {
                            if (!isSearching && !selection) {
                                navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (selection) R.drawable.close else R.drawable.arrow_back,
                            ),
                        contentDescription = null,
                    )
                }
                }
            },
            actions = {
                if (selection) {
                    val count = wrappedSongs.count { it.isSelected }
                    IconButton(
                        onClick = {
                            if (count == wrappedSongs.size) {
                                wrappedSongs.forEach { it.isSelected = false }
                            } else {
                                wrappedSongs.forEach { it.isSelected = true }
                            }
                        },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (count == wrappedSongs.size) {
                                        R.drawable.deselect
                                    } else {
                                        R.drawable.select_all
                                    },
                                ),
                            contentDescription = null,
                        )
                    }
                    IconButton(
                        onClick = {
                            menuState.show {
                                SelectionMediaMetadataMenu(
                                    songSelection =
                                        wrappedSongs
                                            .filter { it.isSelected }
                                            .map {
                                                it.item.second
                                                    .toMediaItem()
                                                    .metadata!!
                                            },
                                    onDismiss = menuState::dismiss,
                                    clearAction = { selection = false },
                                    currentItems = emptyList(),
                                )
                            }
                        },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else if (!isSearching) {
                    // Hide search + more when the SimpMusic-style floating
                    // liquid glass pill is visible (artwork shown, not
                    // scrolled). Once scrolled, the floating pill has
                    // scrolled out of view, so the TopAppBar's own actions
                    // take over.
                    if (showTopBarTitle || !liquidGlassHeaderActive) {
                        IconButton(onClick = { isSearching = true }, onLongClick = {}) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = null,
                            )
                        }
                        playlist?.let { currentPlaylist ->
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        YouTubePlaylistMenu(
                                            playlist = currentPlaylist,
                                            songs = songs,
                                            coroutineScope = coroutineScope,
                                            onDismiss = menuState::dismiss,
                                            selectAction = { selection = true },
                                            canSelect = true,
                                            snackbarHostState = snackbarHostState,
                                        )
                                    }
                                },
                                onLongClick = {},
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_horiz),
                                    contentDescription = stringResource(R.string.more_options),
                                )
                            }
                        }
                    }
                }
            },
        )
        } // end if (!liquidGlassHeaderActive || selection || isSearching)

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime),
                    ).align(Alignment.BottomCenter),
        )
    }
}

private fun SongItem.toPlaylistPlaybackEndpoint(
    playlistId: String,
    playlistPlayParams: String?,
): WatchEndpoint {
    val baseEndpoint = endpoint ?: WatchEndpoint(videoId = id)
    return baseEndpoint.copy(
        videoId = baseEndpoint.videoId ?: id,
        playlistId = baseEndpoint.playlistId ?: playlistId,
        playlistSetVideoId = baseEndpoint.playlistSetVideoId ?: setVideoId,
        params = baseEndpoint.params ?: playlistPlayParams,
    )
}
