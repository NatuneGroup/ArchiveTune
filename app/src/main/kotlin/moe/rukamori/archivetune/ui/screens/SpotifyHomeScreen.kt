/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * YumaPlayer (2026) | Modified work by MuwMx
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.GridThumbnailCornerRadius
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.spotify.SPOTIFY_DJ_PLAYLIST_ID
import moe.rukamori.archivetune.spotify.SpotifyHomeAction
import moe.rukamori.archivetune.spotify.SpotifyHomeNavigationEvent
import moe.rukamori.archivetune.spotify.SpotifyHomeScreenState
import moe.rukamori.archivetune.spotify.SpotifyHomeSection
import moe.rukamori.archivetune.spotify.SpotifyHomeViewModel
import moe.rukamori.archivetune.spotify.SpotifyRecentItem
import moe.rukamori.archivetune.spotify.isSpotifyDj
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.GridItem
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.MarqueeText
import moe.rukamori.archivetune.ui.component.SpotifyTrackListItem
import moe.rukamori.archivetune.ui.component.pressScaleClickable
import moe.rukamori.archivetune.utils.joinByBullet

/**
 * The Spotify home's geometry: YumaPlayer's own numbers — two rows of track tiles per carousel,
 * 16dp gutters, 150dp cards.
 *
 * Holding them as one value rather than repeating the dp inside each row keeps the four section rows
 * to a single implementation apiece; copies of each would drift the first time one is touched.
 */
@androidx.compose.runtime.Immutable
private data class SpotifyHomeMetrics(
    val trackItemWidth: Dp,
    /** Height of one track row; the grid is two rows deep. */
    val trackRowHeight: Dp,
    /** Width of an album/playlist card. */
    val cardWidth: Dp,
    val artistSize: Dp,
    val contentPadding: Dp,
    val itemSpacing: Dp,
)

private val spotifyHomeMetrics =
    SpotifyHomeMetrics(
        trackItemWidth = 240.dp,
        trackRowHeight = 128.dp,
        cardWidth = 150.dp,
        artistSize = 140.dp,
        contentPadding = 16.dp,
        itemSpacing = 12.dp,
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
/**
 * Opens a Spotify playlist tile, or hands the DJ off to Spotify.
 *
 * The DJ arrives shaped like a playlist and is not one — see [isSpotifyDj]. Navigating to it gave
 * an empty playlist page, which read as the tile being broken; Spotify's own app is the only place
 * it can actually play.
 */
@Composable
private fun rememberOpenSpotifyPlaylist(navController: NavController): (String) -> Unit {
    val context = LocalContext.current
    return remember(context, navController) {
        { playlistId: String ->
            if (isSpotifyDj(playlistId)) {
                Toast
                    .makeText(context, context.getString(R.string.spotify_dj_unsupported), Toast.LENGTH_LONG)
                    .show()
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://open.spotify.com/playlist/$SPOTIFY_DJ_PLAYLIST_ID"),
                        ),
                    )
                }
            } else {
                navController.navigate("spotify_playlist/$playlistId")
            }
            Unit
        }
    }
}

@Composable
fun SpotifyHomeScreen(
    navController: NavController,
    headerScrollConnection: NestedScrollConnection? = null,
    viewModel: SpotifyHomeViewModel = hiltViewModel(),
) {
    val openSpotifyPlaylist = rememberOpenSpotifyPlaylist(navController)
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val resolvingItemKey by viewModel.resolvingItemKey.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val onSwitchToYoutube = rememberSwitchToYouTube()

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelSelection() }
    }

    LaunchedEffect(viewModel, navController, playerConnection, context) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SpotifyHomeNavigationEvent.OpenAlbum -> navController.navigate("album/${event.browseId}")
                is SpotifyHomeNavigationEvent.OpenArtist -> navController.navigate("artist/${event.id}")
                is SpotifyHomeNavigationEvent.PlayTracks -> playerConnection.playQueue(event.queue)
                is SpotifyHomeNavigationEvent.PlaySong ->
                    playerConnection.playQueue(
                        moe.rukamori.archivetune.playback.queues.YouTubeQueue.radio(
                            event.song.toMediaMetadata(),
                        ),
                    )
                is SpotifyHomeNavigationEvent.ShowMessage ->
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SpotifyHomeLayout(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (headerScrollConnection != null) {
                    Modifier.nestedScroll(headerScrollConnection)
                } else {
                    Modifier
                }
            )
    ) {
        when (val state = screenState) {
            SpotifyHomeScreenState.Loading -> {
                HomeStatePane(
                    iconResId = null,
                    messageResId = null,
                    showLoadingIndicator = true,
                )
            }
            SpotifyHomeScreenState.Empty -> {
                HomeStatePane(
                    iconResId = R.drawable.music_note,
                    messageResId = R.string.no_results_found,
                    actionResId = R.string.retry,
                    onAction = { viewModel.onAction(SpotifyHomeAction.Refresh) },
                )
            }
            is SpotifyHomeScreenState.Error -> {
                if (state.notAuthenticated == true) {
                    HomeStatePane(
                        iconResId = R.drawable.ic_about,
                        messageResId = R.string.spotify_not_connected,
                        actionResId = R.string.home_switch_to_yt,
                        onAction = onSwitchToYoutube,
                    )
                } else {
                    HomeStatePane(
                        iconResId = R.drawable.ic_about,
                        messageResId = state.messageResId,
                        actionResId = R.string.retry,
                        onAction = { viewModel.onAction(SpotifyHomeAction.Refresh) },
                    )
                }
            }
            is SpotifyHomeScreenState.Success -> {
                ExpressivePullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onAction(SpotifyHomeAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "spotify_recent_panel", contentType = "recent_panel") {
                            SpotifyRecentPanel(
                                recentItems = state.recentItems,
                                frequentArtists = state.frequentArtists,
                                onPlaylistClick = { playlist ->
                                    viewModel.cancelSelection()
                                    openSpotifyPlaylist(playlist.id)
                                },
                                onAlbumClick = { album ->
                                    viewModel.onAction(
                                        SpotifyHomeAction.AlbumClick(
                                            id = album.id,
                                            name = album.name,
                                            artist = album.artists.firstOrNull()?.name,
                                        ),
                                    )
                                },
                                onArtistClick = { artist ->
                                    viewModel.onAction(
                                        SpotifyHomeAction.ArtistClick(id = artist.id, name = artist.name),
                                    )
                                },
                                resolvingItemKey = resolvingItemKey,
                                modifier = Modifier.animateItem()
                            )
                        }

                        // Sections render in the order Spotify's feed returns them. An earlier pass
                        // hoisted the first track shelf to the top under a "Quick Picks" header with
                        // a hero carousel; YumaPlayer has neither, and the extra header plus the
                        // reorder were the most visible reason this screen did not match it.
                        state.sections.forEachIndexed { index, section ->
                            item(
                                key = "spotify_section_title_${section.title}_$index",
                                contentType = "section_header"
                            ) {
                                HomeSectionHeader(
                                    title = resolveSpotifySectionTitle(section),
                                    modifier = Modifier.animateItem()
                                )
                            }

                            item(
                                key = "spotify_section_content_${section.title}_$index",
                                contentType = "section_content"
                            ) {
                                when (section) {
                                    is SpotifyHomeSection.Tracks -> {
                                        val sectionTitle = resolveSpotifySectionTitle(section)
                                        SpotifyTrackSectionRow(
                                            tracks = section.tracks,
                                            onTrackClick = { track ->
                                                if (mediaMetadata?.spotifyTrackId == track.id) {
                                                    viewModel.cancelSelection()
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    viewModel.onAction(
                                                        SpotifyHomeAction.TrackClick(track, section.tracks, sectionTitle),
                                                    )
                                                }
                                            },
                                            activeTrackId = mediaMetadata?.spotifyTrackId,
                                            isPlaying = isPlaying,
                                            resolvingItemKey = resolvingItemKey,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                    is SpotifyHomeSection.Cards -> {
                                        SpotifyCardSectionRow(
                                            items = section.items,
                                            resolvingItemKey = resolvingItemKey,
                                            onAlbumClick = { album ->
                                                viewModel.onAction(
                                                    SpotifyHomeAction.AlbumClick(
                                                        id = album.id,
                                                        name = album.name,
                                                        artist = album.artists.firstOrNull()?.name,
                                                    ),
                                                )
                                            },
                                            onArtistClick = { artist ->
                                                viewModel.onAction(
                                                    SpotifyHomeAction.ArtistClick(id = artist.id, name = artist.name),
                                                )
                                            },
                                            onPlaylistClick = { playlist ->
                                                viewModel.cancelSelection()
                                                openSpotifyPlaylist(playlist.id)
                                            },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun resolveSpotifySectionTitle(section: SpotifyHomeSection): String {
    val title = section.title
    return when {
        title.startsWith("spotify_because_you_like:") -> {
            val artistName = title.removePrefix("spotify_because_you_like:")
            stringResource(R.string.spotify_because_you_like, artistName)
        }
        title == "spotify_top_tracks" -> stringResource(R.string.spotify_top_tracks)
        title == "spotify_top_artists" -> stringResource(R.string.spotify_top_artists)
        title == "spotify_made_for_you" -> stringResource(R.string.spotify_made_for_you)
        title == "spotify_discover" -> stringResource(R.string.spotify_discover)
        title == "spotify_your_playlists" -> stringResource(R.string.spotify_your_playlists)
        title == "spotify_new_releases" -> stringResource(R.string.spotify_new_releases)
        else -> title
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotifyTrackSectionRow(
    tracks: List<SpotifyTrack>,
    onTrackClick: (SpotifyTrack) -> Unit,
    modifier: Modifier = Modifier,
    activeTrackId: String? = null,
    isPlaying: Boolean = false,
    resolvingItemKey: String? = null,
) {
    if (tracks.isEmpty()) return
    LazyHorizontalGrid(
        state = rememberLazyGridState(),
        rows = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = spotifyHomeMetrics.contentPadding),
        modifier = modifier
            .fillMaxWidth()
            .height(spotifyHomeMetrics.trackRowHeight * 2),
    ) {
        itemsIndexed(
            items = tracks,
            key = { index, track -> "spotify_track_${track.id}_$index" },
            contentType = { _, _ -> "spotify_track" },
        ) { _, track ->
            SpotifyTrackListItem(
                track = track,
                isActive = activeTrackId == track.id,
                isPlaying = isPlaying,
                trailingContent = {
                    if (resolvingItemKey == "track:${track.id}") SpotifySelectionIndicator()
                },
                modifier = Modifier
                    .width(spotifyHomeMetrics.trackItemWidth)
                    .fillMaxHeight()
                    .pressScaleClickable(onClick = { onTrackClick(track) }),
            )
        }
    }
}

/**
 * One shelf of feed tiles. Playlists, albums and artists share a row because Spotify sends them
 * that way; each tile is drawn and dispatched by its own kind, so nothing is dropped for being in
 * the minority and no tile opens somebody else's thing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotifyCardSectionRow(
    items: List<SpotifyHomeFeedItem>,
    onAlbumClick: (SpotifyHomeFeedItem.Album) -> Unit,
    onArtistClick: (SpotifyHomeFeedItem.Artist) -> Unit,
    onPlaylistClick: (SpotifyHomeFeedItem.Playlist) -> Unit,
    modifier: Modifier = Modifier,
    resolvingItemKey: String? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = spotifyHomeMetrics.contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spotifyHomeMetrics.itemSpacing),
        modifier = modifier,
    ) {
        items(
            items = items,
            key = { it.uri },
            contentType = { it::class.simpleName },
        ) { item ->
            when (item) {
                is SpotifyHomeFeedItem.Album ->
                    SpotifyHomeCard(
                        title = item.name,
                        subtitle = item.artists.joinToString { it.name },
                        thumbnailUrl = item.imageUrl,
                        isResolving = resolvingItemKey == "album:${item.id}",
                        onClick = { onAlbumClick(item) },
                        modifier = Modifier.width(spotifyHomeMetrics.cardWidth),
                    )

                is SpotifyHomeFeedItem.Playlist ->
                    SpotifyHomeCard(
                        title = item.name,
                        subtitle = joinByBullet(item.ownerName, item.totalCount.takeIf { it > 0 }?.toString()),
                        thumbnailUrl = item.imageUrl,
                        onClick = { onPlaylistClick(item) },
                        modifier = Modifier.width(spotifyHomeMetrics.cardWidth),
                    )

                is SpotifyHomeFeedItem.Artist ->
                    SpotifyArtistCard(
                        artist = item,
                        size = spotifyHomeMetrics.artistSize,
                        isResolving = resolvingItemKey == "artist:${item.id}",
                        onClick = { onArtistClick(item) },
                    )
            }
        }
    }
}

@Composable
private fun SpotifyArtistCard(
    artist: SpotifyHomeFeedItem.Artist,
    size: Dp,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size)
            .pressScaleClickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = artist.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
            if (isResolving) SpotifySelectionIndicator()
        }
        MarqueeText(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SpotifyHomeCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isResolving: Boolean = false,
) {
    GridItem(
        title = title,
        subtitle = subtitle,
        fillMaxWidth = true,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = thumbnailUrl,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(GridThumbnailCornerRadius),
                placeholderIconRes = R.drawable.music_note,
            )
            if (isResolving) SpotifySelectionIndicator()
        },
        modifier = modifier
            .spotifyHomeSurface(RoundedCornerShape(24.dp))
            .pressScaleClickable(onClick = onClick)
            .padding(8.dp),
    )
}

@Composable
private fun SpotifySelectionIndicator() {
    val loadingLabel = stringResource(R.string.loading)
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(6.dp)
            .size(20.dp)
            .semantics { contentDescription = loadingLabel },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeStatePane(
    iconResId: Int?,
    messageResId: Int?,
    modifier: Modifier = Modifier,
    actionResId: Int? = null,
    showLoadingIndicator: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (showLoadingIndicator) {
                androidx.compose.material3.LoadingIndicator()
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
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (actionResId != null && onAction != null) {
                    Spacer(Modifier.height(20.dp))
                    androidx.compose.material3.FilledTonalButton(onClick = onAction) {
                        Text(stringResource(actionResId))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyRecentPanel(
    recentItems: List<SpotifyRecentItem>,
    frequentArtists: List<SpotifyArtist>,
    onPlaylistClick: (SpotifyRecentItem.Playlist) -> Unit,
    onAlbumClick: (SpotifyRecentItem.Album) -> Unit,
    onArtistClick: (SpotifyArtist) -> Unit,
    modifier: Modifier = Modifier,
    resolvingItemKey: String? = null,
) {
    Column(modifier = modifier) {
        if (recentItems.isNotEmpty()) {
            HomeSectionHeader(
                title = stringResource(R.string.spotify_recently_played),
            )
            SpotifyQuickGrid(
                items = recentItems,
                maxItems = 8,
                columns = 2
            ) { item ->
                when (item) {
                    is SpotifyRecentItem.Playlist -> {
                        SpotifyQuickGridCell(
                            title = item.name,
                            imageUrl = item.imageUrl,
                            onClick = { onPlaylistClick(item) },
                            isArtist = false
                        )
                    }
                    is SpotifyRecentItem.Album -> {
                        SpotifyQuickGridCell(
                            title = item.name,
                            imageUrl = item.imageUrl,
                            onClick = { onAlbumClick(item) },
                            isArtist = false,
                            isResolving = resolvingItemKey == "album:${item.id}",
                        )
                    }
                }
            }
        }

        if (frequentArtists.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            HomeSectionHeader(
                title = stringResource(R.string.spotify_frequently_listened),
            )
            SpotifyQuickGrid(
                items = frequentArtists,
                maxItems = 8,
                columns = 2
            ) { artist ->
                val thumbnail = remember(artist.id) {
                    artist.images.maxByOrNull { it.width ?: 0 }?.url
                        ?: artist.images.firstOrNull()?.url
                }
                SpotifyQuickGridCell(
                    title = artist.name,
                    imageUrl = thumbnail,
                    onClick = { onArtistClick(artist) },
                    isArtist = true,
                    isResolving = resolvingItemKey == "artist:${artist.id}",
                )
            }
        }
    }
}

@Composable
private fun <T> SpotifyQuickGrid(
    items: List<T>,
    maxItems: Int = 8,
    columns: Int = 2,
    itemContent: @Composable (T) -> Unit
) {
    val displayItems = items.take(maxItems)
    if (displayItems.isEmpty()) return
    
    val rows = displayItems.chunked(columns)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { item ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        itemContent(item)
                    }
                }
                val emptyCells = columns - rowItems.size
                repeat(emptyCells) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowIndex < rows.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SpotifyQuickGridCell(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    isArtist: Boolean,
    isResolving: Boolean = false,
) {
    val cellShape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .spotifyHomeSurface(cellShape)
            .pressScaleClickable(onClick = onClick)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                // Если артист - круг, если альбом - скругляем только левые углы под форму плашки
                .clip(if (isArtist) CircleShape else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        if (isResolving) SpotifySelectionIndicator()
    }
}
