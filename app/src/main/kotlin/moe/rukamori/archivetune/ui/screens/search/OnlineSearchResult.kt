/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.addons.AddonTrack
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.SearchSummary
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.models.MediaMetadata as AppMediaMetadata
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.ChipsRow
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.shimmer.ListItemPlaceHolder
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubeArtistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.viewmodels.OnlineSearchSort
import moe.rukamori.archivetune.viewmodels.OnlineSearchViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    searchSort: OnlineSearchSort,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val addonTracks by viewModel.addonTracks.collectAsStateWithLifecycle()
    if (viewModel.searchProvider == SearchProvider.SPOTIFY) {
        SpotifyOnlineSearchResult(navController = navController)
        return
    }
    if (viewModel.searchProvider == SearchProvider.APPLE_MUSIC) {
        AppleMusicOnlineSearchResult(navController = navController)
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val searchFilter by viewModel.filter.collectAsStateWithLifecycle()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }
    val allModeSections =
        buildList<SearchSummary> {
            searchSummary
                ?.summaries
                ?.firstOrNull()
                ?.takeIf { it.items.isNotEmpty() }
                ?.let(::add)

            listOf(
                FILTER_SONG to stringResource(R.string.filter_songs),
                FILTER_VIDEO to stringResource(R.string.filter_videos),
                FILTER_ALBUM to stringResource(R.string.filter_albums),
                FILTER_ARTIST to stringResource(R.string.filter_artists),
                FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
                FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
            ).forEach { (sectionFilter, sectionTitle) ->
                viewModel.viewStateMap[sectionFilter.value]
                    ?.items
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        add(SearchSummary(title = sectionTitle, items = viewModel.sortedItems(items, searchSort)))
                    }
            }
        }
    val isAllModeLoaded =
        searchSummary != null ||
            listOf(
                FILTER_SONG,
                FILTER_VIDEO,
                FILTER_ALBUM,
                FILTER_ARTIST,
                FILTER_COMMUNITY_PLAYLIST,
                FILTER_FEATURED_PLAYLIST,
            ).all { viewModel.viewStateMap.containsKey(it.value) }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem) -> Unit = { item: YTItem ->
        val longClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem -> {
                        YouTubeSongMenu(
                            song = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }

                    is AlbumItem -> {
                        YouTubeAlbumMenu(
                            albumItem = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }

                    is ArtistItem -> {
                        YouTubeArtistMenu(
                            artist = item,
                            onDismiss = menuState::dismiss,
                        )
                    }

                    is PlaylistItem -> {
                        YouTubePlaylistMenu(
                            playlist = item,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                    }

                    is PodcastItem, is EpisodeItem -> Unit
                }
            }
        }
        YouTubeListItem(
            item = item,
            viewCountText = (item as? SongItem)?.viewCountText,
            isActive =
                when (item) {
                    is SongItem -> mediaMetadata?.id == item.id
                    is AlbumItem -> mediaMetadata?.album?.id == item.id
                    else -> false
                },
            isPlaying = isPlaying,
            trailingContent = {
                IconButton(
                    onClick = longClick,
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
                        onClick = {
                            when (item) {
                                is SongItem -> {
                                    if (item.id == mediaMetadata?.id) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            YouTubeQueue(
                                                WatchEndpoint(videoId = item.id),
                                                item.toMediaMetadata(),
                                            ),
                                        )
                                    }
                                }

                                is AlbumItem -> {
                                    navController.navigate("album/${item.id}")
                                }

                                is ArtistItem -> {
                                    navController.navigate("artist/${item.id}")
                                }

                                is PlaylistItem -> {
                                    navController.navigate("online_playlist/${item.id}")
                                }

                                is PodcastItem, is EpisodeItem -> Unit
                            }
                        },
                        onLongClick = longClick,
                    ).animateItem(),
        )
    }

    val addonTrackContent: @Composable (AddonTrack) -> Unit = { track ->
        val addonMediaId = "addon:${track.addonId}:${track.id}"
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (mediaMetadata?.id == addonMediaId) {
                            playerConnection.player.togglePlayPause()
                        } else {
                            coroutineScope.launch {
                                try {
                                    val stream = viewModel.resolveAddonTrack(track)
                                    val metadata =
                                        AppMediaMetadata(
                                            id = addonMediaId,
                                            title = track.title,
                                            artists = listOf(AppMediaMetadata.Artist(id = null, name = track.artist)),
                                            duration = ((track.displayDurationMs ?: 0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                            thumbnailUrl = track.displayArtworkUrl,
                                            album = track.album?.let { AppMediaMetadata.Album(id = it, title = it) },
                                        )
                                    val mediaItem =
                                        metadata.toMediaItem()
                                            .buildUpon()
                                            .setUri(stream.url.toUri())
                                            .apply { stream.mimeType?.let(::setMimeType) }
                                            .build()
                                    playerConnection.playQueue(
                                        ListQueue(title = track.addonName, items = listOf(mediaItem)),
                                    )
                                } catch (error: Throwable) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    Toast.makeText(context, R.string.addon_stream_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.music_note), contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${track.artist} · ${track.addonName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
            modifier =
                Modifier
                    // Use safeDrawing (not systemBars) so the chips row stays
                    // below the notch/cutout even when the status bar is hidden
                    // (e.g., an item overflow menu is open on the search results
                    // page, which flips shouldHideStatusBars to true). When the
                    // status bar is hidden, systemBars reports 0 top inset and
                    // the chips row would float into the cutout. safeDrawing
                    // always reports the cutout-safe area.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top).add(WindowInsets(top = AppBarHeight)))
                    .fillMaxWidth(),
        ) {
            ChipsRow(
                chips =
                    listOf(
                        null to stringResource(R.string.filter_all),
                        FILTER_SONG to stringResource(R.string.filter_songs),
                        FILTER_VIDEO to stringResource(R.string.filter_videos),
                        FILTER_ALBUM to stringResource(R.string.filter_albums),
                        FILTER_ARTIST to stringResource(R.string.filter_artists),
                        FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
                        FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
                    ),
                currentValue = searchFilter,
                onValueUpdate = {
                    if (viewModel.filter.value != it) {
                        viewModel.filter.value = it
                    }
                    coroutineScope.launch {
                        lazyListState.animateScrollToItem(0)
                    }
                },
                icons =
                    mapOf(
                        null to R.drawable.search,
                        FILTER_SONG to R.drawable.music_note,
                        FILTER_VIDEO to R.drawable.slow_motion_video,
                        FILTER_ALBUM to R.drawable.album,
                        FILTER_ARTIST to R.drawable.person,
                        FILTER_COMMUNITY_PLAYLIST to R.drawable.queue_music,
                        FILTER_FEATURED_PLAYLIST to R.drawable.playlist_play,
                    ),
            )
        }

        LazyColumn(
            state = lazyListState,
            contentPadding =
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    .add(WindowInsets(top = 8.dp))
                    .asPaddingValues(),
            modifier = Modifier.weight(1f),
        ) {
            if (searchFilter == null) {
                allModeSections.forEachIndexed { index, summary ->
                    if (index > 0) {
                        item(key = "divider_$index", contentType = "divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }

                    item(
                        key = "section_header_${summary.title}_$index",
                        contentType = "section_header",
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = summary.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    itemsIndexed(
                        items = viewModel.sortedItems(summary.items, searchSort),
                        key = { itemIndex, item -> "${summary.title}/${item.id}/$itemIndex" },
                        contentType = { _, _ -> "search_result" },
                    ) { _, item ->
                        ytItemContent(item)
                    }

                    item(
                        key = "section_spacer_${summary.title}_$index",
                        contentType = "section_spacer",
                    ) {
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (addonTracks.isNotEmpty()) {
                    item(key = "addon_results_header", contentType = "section_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Box(
                                modifier = Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.addon_search_results),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    items(
                        items = addonTracks,
                        key = { "addon:${it.addonId}:${it.id}" },
                        contentType = { "addon_result" },
                        itemContent = { track -> addonTrackContent(track) },
                    )
                }

                if (allModeSections.isEmpty() && addonTracks.isEmpty() && isAllModeLoaded) {
                    item(key = "empty_all", contentType = "empty") {
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                        )
                    }
                }
            } else {
                if (searchFilter?.value == FILTER_SONG.value && addonTracks.isNotEmpty()) {
                    item(key = "addon_songs_header", contentType = "section_header") {
                        Text(
                            text = stringResource(R.string.addon_search_results),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                    items(
                        items = addonTracks,
                        key = { "filtered_addon:${it.addonId}:${it.id}" },
                        contentType = { "addon_result" },
                        itemContent = { track -> addonTrackContent(track) },
                    )
                }
                items(
                    items = viewModel.sortedItems(itemsPage?.items.orEmpty().distinctBy { it.id }, searchSort),
                    key = { "filtered_${it.id}" },
                    contentType = { "search_result" },
                    itemContent = ytItemContent,
                )

                if (itemsPage?.continuation != null) {
                    item(key = "loading", contentType = "loading") {
                        ShimmerHost {
                            repeat(3) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                }

                if (itemsPage?.items?.isEmpty() == true &&
                    (searchFilter?.value != FILTER_SONG.value || addonTracks.isEmpty())
                ) {
                    item(key = "empty_filtered", contentType = "empty") {
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                        )
                    }
                }
            }

            if (searchFilter == null && allModeSections.isEmpty() && !isAllModeLoaded || searchFilter != null && itemsPage == null) {
                item(key = "initial_loading", contentType = "loading") {
                    ShimmerHost {
                        repeat(8) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }
        }
    }
}
