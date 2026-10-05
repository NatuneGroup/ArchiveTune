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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularWavyProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.media3.exoplayer.offline.Download
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.SpotifyDownloadItem
import moe.rukamori.archivetune.spotify.SPOTIFY_LIKED_SONGS_ID
import moe.rukamori.archivetune.spotify.SpotifyPlaybackResolver
import moe.rukamori.archivetune.spotify.SpotifyPlaylistEvent
import moe.rukamori.archivetune.spotify.SpotifyPlaylistQueue
import moe.rukamori.archivetune.spotify.SpotifyPlaylistViewModel
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.ui.component.DraggableScrollbar
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LiquidGlassIconButton
import moe.rukamori.archivetune.ui.component.MediaDetailAction
import moe.rukamori.archivetune.ui.component.MediaDetailHero
import moe.rukamori.archivetune.ui.component.MediaDetailIconAction
import moe.rukamori.archivetune.ui.component.SpotifyTrackListItem
import moe.rukamori.archivetune.ui.component.layerBackdrop
import android.os.Build
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.HeaderDownloadProgressIndicator
import moe.rukamori.archivetune.ui.utils.HeaderDownloadState
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.headerDownloadState
import moe.rukamori.archivetune.ui.utils.resize
import timber.log.Timber
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import moe.rukamori.archivetune.utils.makeTimeString
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadUtil = LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val isPlaying by playerConnection?.isPlaying?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    val mediaMetadata by playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<MediaMetadata?>(null) }
    val playlist = state.playlist
    val tracks = state.tracks
    val lazyListState = rememberLazyListState()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    val snackbarHostState = remember { SnackbarHostState() }
    val downloadActionFailedMessage = stringResource(R.string.download_action_failed)
    val trackUnavailableMessage = stringResource(R.string.spotify_track_unavailable)
    val latestDownloads by rememberUpdatedState(downloads)

    // Save scroll position when entering search, restore when leaving.
    // The header item collapses to height 0 when isSearching becomes true, which
    // shifts all items below it. By saving firstVisibleItemIndex + scrollOffset
    // BEFORE the collapse and restoring them AFTER the expand, the visible
    // position is preserved across open/close search.
    // (The LaunchedEffect that uses these is declared further below, AFTER
    // isSearching is created — Kotlin requires vals to be declared before use.)
    var savedScrollIndex by remember { mutableIntStateOf(0) }
    var savedScrollOffset by remember { mutableIntStateOf(0) }

    val downloadState =
        remember(state.downloadItems, downloads) {
            headerDownloadState(
                songIds = state.downloadItems.map(SpotifyDownloadItem::id),
                downloads = downloads,
            )
        }

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

    fun handleDownloadAction(
        items: ImmutableList<SpotifyDownloadItem>,
        removeCompleted: Boolean = true,
    ) {
        val songIds = items.map(SpotifyDownloadItem::id)
        when (headerDownloadState(songIds, latestDownloads)) {
            HeaderDownloadState.Completed -> {
                if (removeCompleted) {
                    sendRemoveDownloads(
                        context = navController.context,
                        songIds = songIds,
                    )
                }
            }

            is HeaderDownloadState.Partial -> {
                sendRemoveDownloads(
                    context = navController.context,
                    songIds = songIds,
                )
            }

            HeaderDownloadState.None -> {
                sendAddMissingDownloads(
                    context = navController.context,
                    songs =
                        items.map { item ->
                            HeaderDownloadItem(
                                id = item.id,
                                title = item.title,
                            )
                        },
                    downloads = latestDownloads,
                )
                navController.navigate("auto_playlist/downloaded?tab=progress")
            }
        }
    }

    val showTopBarTitle by remember {
        derivedStateOf { lazyListState.firstVisibleItemIndex > 0 }
    }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var resolvingTrackId by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    val filteredTracks =
        remember(tracks, query.text) {
            if (query.text.isBlank()) {
                tracks
            } else {
                tracks.filter { track ->
                    track.name.contains(query.text, ignoreCase = true) ||
                        track.artists.any { artist -> artist.name.contains(query.text, ignoreCase = true) } ||
                        track.album?.name?.contains(query.text, ignoreCase = true) == true
                }
            }
        }

    val loadedDurationMs =
        remember(tracks) {
            tracks.sumOf { track -> track.durationMs.toLong() }
        }

    val surfaceColor = MaterialTheme.colorScheme.surface

    val thumbnailUrl =
        remember(playlist) {
            playlist?.let { SpotifyMapper.getPlaylistThumbnail(it)?.resize(544, 544) }
        }

    val transparentAppBar by remember {
        derivedStateOf { !showTopBarTitle && !isSearching }
    }

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

    LaunchedEffect(isSearching) {
        if (isSearching) {
            // Save scroll position BEFORE the header collapses, then focus the search field.
            savedScrollIndex = lazyListState.firstVisibleItemIndex
            savedScrollOffset = lazyListState.firstVisibleItemScrollOffset
            focusRequester.requestFocus()
        } else {
            // Restore scroll position AFTER the header has expanded back. A single
            // frame delay lets the LazyColumn re-measure with the header at full height.
            withFrameNanos {}
            lazyListState.scrollToItem(savedScrollIndex, savedScrollOffset)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SpotifyPlaylistEvent.DownloadResolutionFailed -> {
                    snackbarHostState.showSnackbar(downloadActionFailedMessage)
                }

                is SpotifyPlaylistEvent.DownloadsResolved -> {
                    handleDownloadAction(
                        items = event.items,
                        removeCompleted = false,
                    )
                }
            }
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    }

    fun playPlaylist(
        startIndex: Int = 0,
        shuffled: Boolean = false,
    ) {
        val currentPlaylist = playlist ?: return
        val queueTracks = if (shuffled) tracks.shuffled() else tracks
        if (queueTracks.isEmpty()) return
        val boundedStartIndex = startIndex.coerceIn(queueTracks.indices)
        val preloadTrack = queueTracks[boundedStartIndex]
        if (resolvingTrackId != null) return

        coroutineScope.launch {
            resolvingTrackId = preloadTrack.id
            try {
                val preloadItem = SpotifyPlaybackResolver.resolveToMetadata(preloadTrack)
                if (preloadItem == null) {
                    snackbarHostState.showSnackbar(trackUnavailableMessage)
                    return@launch
                }
                playerConnection?.playQueue(
                    SpotifyPlaylistQueue(
                        playlistId = currentPlaylist.id,
                        title = currentPlaylist.name,
                        initialTracks = queueTracks,
                        startIndex = boundedStartIndex,
                        preloadItem = preloadItem,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("SpotifyPlayback").w("Selected track resolution failed: %s", error::class.java.simpleName)
                snackbarHostState.showSnackbar(trackUnavailableMessage)
            } finally {
                resolvingTrackId = null
            }
        }
    }

    // Liquid Glass backdrop: created unconditionally (cheap — just a GraphicsLayer handle). The
    // actual content recording happens when `Modifier.layerBackdrop(artworkBackdrop)` is applied to
    // the LazyColumn below.
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen
    val artworkBackdrop = rememberBackdrop(Color.Black)

    ExpressivePullToRefreshBox(
        // Only a reload has anything to refresh. On the first load the list is empty and the
        // placeholder below already says so, so the indicator would be a second spinner over the
        // same wait.
        isRefreshing = state.isLoading && tracks.isNotEmpty(),
        onRefresh = viewModel::reload,
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor),
    ) {
        LazyColumn(
            state = lazyListState,
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
        ) {
            playlist?.let { currentPlaylist ->
                item(key = "header") {
                    if (!isSearching) {
                        val trackCount = currentPlaylist.tracks?.total ?: tracks.size
                        val metadata =
                            listOfNotNull(
                                pluralStringResource(R.plurals.n_song, trackCount, trackCount),
                                loadedDurationMs
                                    .takeIf { duration -> duration > 0L }
                                    ?.let(::makeTimeString),
                            ).joinToString(MediaDetailMetadataSeparator)

                        // SimpMusic-style liquid glass backdrop source: the LazyColumn itself
                        // carries Modifier.layerBackdrop (see the LazyColumn definition above), so
                        // the entire scrolling content is recorded into the backdrop.
                        MediaDetailHero(
                            title = currentPlaylist.name,
                            thumbnailUrl = thumbnailUrl,
                            fallbackIcon =
                                if (currentPlaylist.id == SPOTIFY_LIKED_SONGS_ID) {
                                    R.drawable.favorite
                                } else {
                                    R.drawable.queue_music
                                },
                            systemBarsTopPadding = systemBarsTopPadding,
                            subtitle =
                                currentPlaylist.owner
                                    ?.displayName
                                    ?.takeIf(String::isNotBlank)
                                    ?.let(::AnnotatedString),
                            metadata = metadata,
                            description = currentPlaylist.description,
                            isAdded = false,
                            addContentDescription = R.string.add_to_library,
                            removeContentDescription = R.string.remove_from_library,
                            onShuffle =
                                if (tracks.isNotEmpty()) {
                                    { playPlaylist(shuffled = true) }
                                } else {
                                    null
                                },
                            onPlay =
                                if (tracks.isNotEmpty()) {
                                    { playPlaylist() }
                                } else {
                                    null
                                },
                            onToggleAdd = null,
                            additionalPrimaryActions = { contentColor ->
                                if (tracks.isNotEmpty()) {
                                    MediaDetailAction(
                                        contentDescription =
                                            if (downloadState == HeaderDownloadState.Completed) {
                                                R.string.remove_download
                                            } else {
                                                R.string.download
                                            },
                                        contentColor = contentColor,
                                        enabled = !state.isLoading && !state.isResolvingDownloads,
                                        onClick = {
                                            if (state.downloadItems.isEmpty()) {
                                                viewModel.resolveDownloads()
                                            } else {
                                                handleDownloadAction(state.downloadItems)
                                            }
                                        },
                                    ) {
                                        if (state.isResolvingDownloads) {
                                            CircularWavyProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                            )
                                        } else {
                                            when (val currentState = downloadState) {
                                                HeaderDownloadState.Completed -> {
                                                    Icon(
                                                        painter = painterResource(R.drawable.offline),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(22.dp),
                                                    )
                                                }

                                                is HeaderDownloadState.Partial -> {
                                                    HeaderDownloadProgressIndicator(
                                                        progress = currentState.progress,
                                                        paused = currentState.paused,
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
                                }
                            },
                            useBlurredPlayButton = true,
                        )
                    }
                }
            }

            if (state.isLoading && tracks.isEmpty()) {
                item(key = "loading") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator()
                    }
                }
            }

            state.errorMessage?.let { error ->
                item(key = "error") {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            if (!state.isLoading && state.errorMessage == null && filteredTracks.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(
                        icon = R.drawable.music_note,
                        text =
                            stringResource(
                                if (query.text.isBlank()) {
                                    R.string.spotify_no_tracks
                                } else {
                                    R.string.ai_model_no_results
                                },
                            ),
                    )
                }
            }

            itemsIndexed(
                items = filteredTracks,
                key = { index, track -> "spotify_track_${track.id}_$index" },
                contentType = { _, _ -> "spotify_track" },
            ) { index, track ->
                val trackIsActive =
                    remember(track, mediaMetadata) {
                        track.isResolvedAs(mediaMetadata)
                    }
                val trackIsResolving = resolvingTrackId == track.id

                SpotifyTrackListItem(
                    track = track,
                    isActive = trackIsActive || trackIsResolving,
                    isPlaying = isPlaying && !trackIsResolving,
                    trailingContent = {
                        if (trackIsResolving) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = resolvingTrackId == null || trackIsActive) {
                                if (trackIsActive) {
                                    playerConnection?.player?.togglePlayPause()
                                } else {
                                    val startIndex =
                                        tracks
                                            .indexOfFirst { item -> item.id == track.id }
                                            .takeIf { itemIndex -> itemIndex >= 0 }
                                            ?: index
                                    playPlaylist(startIndex = startIndex)
                                }
                            },
                )
            }
        }

        DraggableScrollbar(
            modifier =
                Modifier
                    .padding(
                        LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
                    ).align(Alignment.CenterEnd),
            scrollState = lazyListState,
            headerItems = if (!isSearching && playlist != null) 1 else 0,
        )

        // Persistent Liquid Glass header buttons. Siblings of the LazyColumn (children of the
        // ExpressivePullToRefreshBox), positioned at top-start and top-end.
        if (layerBackdropActive && !isSearching && playlist != null) {
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
            }
        }

        // Top App Bar — hidden when the Liquid Glass header buttons are visible
        // (matches LocalPlaylistScreen pattern). The Liquid Glass back button and
        // search pill handle navigation when the hero is visible. The TopAppBar is
        // only rendered during search mode (search TextField) and loading state
        // (back navigation while playlist loads). Rendering the TopAppBar on top of
        // the Liquid Glass buttons (even when transparent and with empty actions)
        // causes it to intercept pointer events in the top area, making the Liquid
        // Glass buttons unclickable.
        if (isSearching || playlist == null) {
        TopAppBar(
            colors = topAppBarColors,
            windowInsets =
                WindowInsets(top = systemBarsTopPadding)
                    .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
            title = {
                if (isSearching) {
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                    )
                } else if (showTopBarTitle) {
                    Text(
                        text = playlist?.name ?: stringResource(R.string.spotify_playlists),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                // Hide the back arrow when the SimpMusic-style floating liquid
                // glass back button is visible (artwork shown, not searching,
                // not scrolled).
                if (isSearching || showTopBarTitle) {
                    IconButton(
                        onClick = {
                            if (isSearching) {
                                isSearching = false
                                query = TextFieldValue()
                            } else {
                                navController.navigateUp()
                            }
                        },
                        onLongClick = {
                            if (!isSearching) navController.backToMain()
                        },
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (isSearching) R.drawable.close else R.drawable.arrow_back,
                                ),
                            contentDescription = null,
                        )
                    }
                }
            },
            actions = {
                if (!isSearching && showTopBarTitle) {
                    IconButton(
                        onClick = { isSearching = true },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }
                }
            },
            scrollBehavior = scrollBehavior,
        )
        } // end if (isSearching || playlist == null)

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current
                            .union(WindowInsets.ime),
                    ).align(Alignment.BottomCenter),
        )
    }
}

private fun SpotifyTrack.isResolvedAs(mediaMetadata: MediaMetadata?): Boolean {
    if (mediaMetadata == null) return false

    mediaMetadata.spotifyTrackId?.let { spotifyTrackId ->
        return id.isNotBlank() && spotifyTrackId == id
    }

    val titleMatches = name.equals(mediaMetadata.title, ignoreCase = true)
    val durationMatches =
        durationMs <= 0 ||
            mediaMetadata.duration <= 0 ||
            abs(durationMs.toLong() - mediaMetadata.duration * 1000L) <= 1_000L
    val albumMatches =
        album?.let { spotifyAlbum ->
            val currentAlbum = mediaMetadata.album ?: return false
            spotifyAlbum.id.isNotBlank() && spotifyAlbum.id == currentAlbum.id ||
                spotifyAlbum.name.equals(currentAlbum.title, ignoreCase = true)
        } ?: true
    val artistMatches =
        artists.isEmpty() ||
            mediaMetadata.artists.isEmpty() ||
            artists.any { spotifyArtist ->
                mediaMetadata.artists.any { artist ->
                    spotifyArtist.name.equals(artist.name, ignoreCase = true)
                }
            }
    val thumbnailMatches =
        SpotifyMapper.getTrackThumbnail(this)?.let { thumbnail ->
            thumbnail == mediaMetadata.thumbnailUrl
        } ?: true

    return titleMatches && durationMatches && albumMatches && artistMatches && thumbnailMatches
}

private const val MediaDetailMetadataSeparator = "  •  "
