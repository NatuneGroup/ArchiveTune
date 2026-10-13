/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * "Rukamori" home screen style — layout ported from upstream rukamori/ArchiveTune
 * (dev branch HomeScreen.kt) onto this fork's HomeViewModel/HomeUiState wiring.
 * Differences vs the fork's default home:
 *   - No "Jump back in" hero, no minimal-mode partitioning, no Live-performance
 *     special casing: the feed is the classic upstream order
 *     chips → quick picks → speed dial → keep listening → account playlists
 *     → forgotten favourites → similar recommendations → ALL remote sections.
 *   - Tighter section spacing (18dp) and a stronger tonal backdrop gradient,
 *     matching upstream's visual signature.
 *
 * Only public components from HomeScreenComponents.kt are used, so the two
 * home styles always render shelves identically.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import moe.rukamori.archivetune.ui.utils.SnapLayoutInfoProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListItemHeight
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.QuickPicks
import moe.rukamori.archivetune.constants.QuickPicksDisplayMode
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.lazyKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.home.HomeAction
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.HomeUiState
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.SimilarRecommendation
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.viewmodels.HomeViewModel

private val HomeFeedMaxWidth = 1_200.dp
private val HomeSectionSpacing = 18.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RukamoriHomeScreen(
    navController: NavController,
    headerScrollConnection: NestedScrollConnection? = null,
    listState: LazyListState? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current

    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val lazyListState = listState ?: rememberLazyListState()
    val scope = rememberCoroutineScope()
    // This screen's own back-stack entry, not the controller's current one (see HomeScreen).
    val backStackEntry = LocalLifecycleOwner.current as? NavBackStackEntry
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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (headerScrollConnection != null) {
                        Modifier.nestedScroll(headerScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        when (val state = screenState) {
            HomeScreenState.Loading -> {
                RukamoriHomeStatePane(
                    iconResId = null,
                    messageResId = null,
                    showLoadingIndicator = true,
                )
            }

            HomeScreenState.Empty -> {
                RukamoriHomeStatePane(
                    iconResId = R.drawable.music_note,
                    messageResId = R.string.no_results_found,
                    actionResId = R.string.retry,
                    onAction = { viewModel.onAction(HomeAction.Refresh) },
                )
            }

            is HomeScreenState.Error -> {
                RukamoriHomeStatePane(
                    iconResId = R.drawable.info,
                    messageResId = state.messageResId,
                    actionResId = R.string.retry,
                    onAction = { viewModel.onAction(HomeAction.Refresh) },
                )
            }

            is HomeScreenState.Success -> {
                RukamoriHomeContent(
                    uiState = state.uiState,
                    mediaMetadata = mediaMetadata,
                    isPlaying = isPlaying,
                    navController = navController,
                    playerConnection = playerConnection,
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

@Composable
private fun RukamoriHomeStatePane(
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
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun RukamoriHomeContent(
    uiState: HomeUiState,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    lazyListState: LazyListState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteQuickPicks =
        uiState
            .takeIf { it.quickPicksMode == QuickPicks.QUICK_PICKS }
            ?.remoteQuickPicks
    val tonalStart = MaterialTheme.colorScheme.primaryContainer
    val tonalMiddle = MaterialTheme.colorScheme.secondaryContainer
    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.showTonalBackdrop) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                        .align(Alignment.TopCenter)
                        .drawWithCache {
                            val brush =
                                Brush.verticalGradient(
                                    // Upstream rukamori gradient strengths.
                                    0f to tonalStart.copy(alpha = 0.30f),
                                    0.42f to tonalMiddle.copy(alpha = 0.14f),
                                    1f to Color.Transparent,
                                )
                            onDrawBehind { drawRect(brush) }
                        },
            )
        }

        ExpressivePullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { onAction(HomeAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                    modifier =
                        Modifier
                            .widthIn(max = HomeFeedMaxWidth)
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                ) {
                    if (uiState.showCategoryChips) {
                        item(
                            key = "home_category_chips",
                            contentType = "category_chips",
                        ) {
                            HomeCategoryChips(
                                chips = uiState.homePage?.chips.orEmpty(),
                                selectedChip = uiState.selectedChip,
                                onChipSelected = { onAction(HomeAction.SelectChip(it)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (remoteQuickPicks?.items?.isNotEmpty() == true) {
                        rukamoriSectionSpacer("remote_quick_picks")
                        item(
                            key = "home_remote_quick_picks_header",
                            contentType = "section_header",
                        ) {
                            HomeSectionHeader(
                                title = stringResource(R.string.quick_picks),
                                leadingIcon = {
                                    HomeSectionLeadingIcon(iconRes = R.drawable.discover_tune)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(
                            key = "home_remote_quick_picks",
                            contentType = "quick_picks",
                        ) {
                            RemoteQuickPicksSection(
                                section = remoteQuickPicks,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                displayMode = uiState.quickPicksDisplayMode,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    } else if (
                        // Not gated on LAST_LISTEN. YouTube does not always put a quick-picks
                        // section in the home feed, and when it does not, the mode is still
                        // QUICK_PICKS — which left this branch unreachable and the carousel gone
                        // from the page entirely rather than falling back to what is on device.
                        uiState.quickPicksMode != QuickPicks.DONT_SHOW &&
                        uiState.quickPicks.isNotEmpty()
                    ) {
                        rukamoriSectionSpacer("quick_picks")
                        item(
                            key = "home_quick_picks_header",
                            contentType = "section_header",
                        ) {
                            HomeSectionHeader(
                                title = stringResource(R.string.quick_picks),
                                leadingIcon = {
                                    HomeSectionLeadingIcon(
                                        iconRes =
                                            if (uiState.quickPicksMode == QuickPicks.LAST_LISTEN) {
                                                R.drawable.history
                                            } else {
                                                R.drawable.discover_tune
                                            },
                                    )
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(
                            key = "home_quick_picks",
                            contentType = "quick_picks",
                        ) {
                            QuickPicksSection(
                                quickPicks = uiState.quickPicks,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                displayMode = uiState.quickPicksDisplayMode,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (uiState.speedDialItems.isNotEmpty()) {
                        rukamoriSectionSpacer("speed_dial")
                        item(
                            key = "home_speed_dial_header",
                            contentType = "section_header",
                        ) {
                            HomeSectionHeader(
                                title = stringResource(R.string.speed_dial),
                                leadingIcon = {
                                    HomeSectionLeadingIcon(iconRes = R.drawable.bolt)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(
                            key = "home_speed_dial",
                            contentType = "speed_dial",
                        ) {
                            SpeedDialSection(
                                speedDialItems = uiState.speedDialItems,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                scope = scope,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (uiState.keepListening.isNotEmpty()) {
                        rukamoriSectionSpacer("keep_listening")
                        item(
                            key = "home_keep_listening_header",
                            contentType = "section_header",
                        ) {
                            HomeSectionHeader(
                                title = stringResource(R.string.keep_listening),
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(
                            key = "home_keep_listening",
                            contentType = "media_shelf",
                        ) {
                            KeepListeningSection(
                                keepListening = uiState.keepListening,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                scope = scope,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (uiState.accountPlaylists.isNotEmpty()) {
                        rukamoriSectionSpacer("account_playlists")
                        item(
                            key = "home_account_playlists",
                            contentType = "media_shelf",
                        ) {
                            Column(modifier = Modifier.animateItem()) {
                                AccountPlaylistsTitle(
                                    accountName = uiState.accountName,
                                    accountImageUrl = uiState.accountImageUrl,
                                    onClick = { navController.navigate("account") },
                                )
                                AccountPlaylistsSection(
                                    accountPlaylists = uiState.accountPlaylists,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    scope = scope,
                                )
                            }
                        }
                    }

                    if (uiState.forgottenFavorites.isNotEmpty()) {
                        rukamoriSectionSpacer("forgotten_favorites")
                        item(
                            key = "home_forgotten_favorites_header",
                            contentType = "section_header",
                        ) {
                            HomeSectionHeader(
                                title = stringResource(R.string.forgotten_favorites),
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(
                            key = "home_forgotten_favorites",
                            contentType = "song_shelf",
                        ) {
                            ForgottenFavoritesSection(
                                forgottenFavorites = uiState.forgottenFavorites,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    // Upstream renders every similar recommendation as one deck in a single
                    // "discovery decks" rail rather than a header + shelf per recommendation;
                    // the deck carries its own "Similar to <artist>" header.
                    if (uiState.similarRecommendations.isNotEmpty()) {
                        rukamoriSectionSpacer("similar_recommendations")
                        item(
                            key = "home_similar_recommendations",
                            contentType = "discovery_decks",
                        ) {
                            SimilarRecommendationsDecks(
                                recommendations = uiState.similarRecommendations,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                scope = scope,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    uiState.homePage?.sections.orEmpty().forEachIndexed { index, section ->
                        val sectionKey = "${section.endpoint?.browseId ?: section.title}_$index"
                        rukamoriSectionSpacer("remote_$sectionKey")
                        item(
                            key = "home_remote_header_$sectionKey",
                            contentType = "section_header",
                        ) {
                            HomePageSectionTitle(
                                section = section,
                                navController = navController,
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(
                            key = "home_remote_$sectionKey",
                            contentType = "media_shelf",
                        ) {
                            HomePageSectionContent(
                                section = section,
                                mediaMetadata = mediaMetadata,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                scope = scope,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (uiState.isLoadingMore) {
                        item(
                            key = "home_loading_more",
                            contentType = "loading",
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp)
                                        .animateItem(),
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rukamoriSectionSpacer(key: String) {
    item(
        key = "home_section_spacer_$key",
        contentType = "section_spacer",
    ) {
        Spacer(Modifier.height(HomeSectionSpacing))
    }
}

// ──────────────────────────────────────────────────────────────────────
// Quick picks — verbatim port of upstream rukamori HomeScreenComponents
// (the hero-carousel cards that were missing from this fork).
// ──────────────────────────────────────────────────────────────────────

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun QuickPicksSection(
    quickPicks: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    displayMode: QuickPicksDisplayMode,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val distinctQuickPicks = remember(quickPicks) { quickPicks.distinctBy { it.id } }

    when (displayMode) {
        QuickPicksDisplayMode.CARD -> {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val heroHeight =
                    when {
                        maxWidth >= 840.dp -> 380.dp
                        maxWidth >= 600.dp -> 356.dp
                        else -> 332.dp
                    }
                val heroMaxWidth =
                    (maxWidth - 48.dp)
                        .coerceAtLeast(232.dp)
                        .coerceAtMost(440.dp)
                val density = LocalDensity.current
                val requestWidthPx = with(density) { heroMaxWidth.roundToPx().coerceAtLeast(1) }
                val requestHeightPx = with(density) { heroHeight.roundToPx().coerceAtLeast(1) }

                HorizontalCenteredHeroCarousel(
                    state = rememberCarouselState { distinctQuickPicks.size },
                    maxItemWidth = heroMaxWidth,
                    itemSpacing = 10.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(heroHeight),
                ) { index ->
                    val song = distinctQuickPicks[index]
                    val isActive = song.id == mediaMetadata?.id
                    val context = LocalContext.current
                    val imageRequest =
                        remember(song.song.thumbnailUrl, requestWidthPx, requestHeightPx) {
                            ImageRequest
                                .Builder(context)
                                .data(song.song.thumbnailUrl?.highRes())
                                .size(Size(requestWidthPx, requestHeightPx))
                                .crossfade(true)
                                .build()
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .maskClip(MaterialTheme.shapes.extraLarge)
                                .maskBorder(
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                                    ),
                                    MaterialTheme.shapes.extraLarge,
                                ).focusable()
                                .combinedClickable(
                                    onClick = {
                                        if (isActive) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                if (song.song.isLocal) {
                                                    ListQueue(items = listOf(song.toMediaItem()))
                                                } else {
                                                    YouTubeQueue.radio(song.toMediaMetadata())
                                                },
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ),
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.48f to Color.Black.copy(alpha = 0.08f),
                                            1f to Color.Black.copy(alpha = 0.84f),
                                        ),
                                    ),
                        )

                        if (isActive && isPlaying) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                tonalElevation = 2.dp,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(14.dp)
                                        .size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.volume_up),
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp),
                        ) {
                            Text(
                                text = song.song.title,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        QuickPicksDisplayMode.LIST -> {
            BoxWithConstraints(
                modifier = modifier.fillMaxWidth(),
            ) {
                val widthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                val itemWidth = maxWidth * widthFactor
                val lazyGridState = rememberLazyGridState()
                val snapLayoutInfoProvider =
                    remember(lazyGridState, widthFactor) {
                        SnapLayoutInfoProvider(
                            lazyGridState = lazyGridState,
                            positionInLayout = { layoutSize, itemSize ->
                                layoutSize * widthFactor / 2f - itemSize / 2f
                            },
                        )
                    }
                LazyHorizontalGrid(
                    state = lazyGridState,
                    rows = GridCells.Fixed(4),
                    flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                    contentPadding =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * 4),
                ) {
                    items(
                        items = distinctQuickPicks,
                        key = { it.id },
                        contentType = { "quick_pick_song" },
                    ) { song ->
                        SongListItem(
                            song = song,
                            showInLibraryIcon = true,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            isSwipeable = false,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .width(itemWidth)
                                    .combinedClickable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    if (song.song.isLocal) {
                                                        ListQueue(items = listOf(song.toMediaItem()))
                                                    } else {
                                                        YouTubeQueue.radio(song.toMediaMetadata())
                                                    },
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun RemoteQuickPicksSection(
    section: HomePage.Section,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    displayMode: QuickPicksDisplayMode,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val songs =
        remember(section.items) {
            section.items.filterIsInstance<SongItem>().distinctBy(SongItem::id)
        }

    when (displayMode) {
        QuickPicksDisplayMode.CARD -> {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val heroHeight =
                    when {
                        maxWidth >= 840.dp -> 380.dp
                        maxWidth >= 600.dp -> 356.dp
                        else -> 332.dp
                    }
                val heroMaxWidth =
                    (maxWidth - 48.dp)
                        .coerceAtLeast(232.dp)
                        .coerceAtMost(440.dp)
                val density = LocalDensity.current
                val requestWidthPx = with(density) { heroMaxWidth.roundToPx().coerceAtLeast(1) }
                val requestHeightPx = with(density) { heroHeight.roundToPx().coerceAtLeast(1) }

                HorizontalCenteredHeroCarousel(
                    state = rememberCarouselState { songs.size },
                    maxItemWidth = heroMaxWidth,
                    itemSpacing = 10.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(heroHeight),
                ) { index ->
                    val song = songs[index]
                    val isActive = song.id == mediaMetadata?.id
                    val context = LocalContext.current
                    val imageRequest =
                        remember(song.thumbnail, requestWidthPx, requestHeightPx) {
                            ImageRequest
                                .Builder(context)
                                .data(song.thumbnail.highRes())
                                .size(Size(requestWidthPx, requestHeightPx))
                                .crossfade(true)
                                .build()
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .maskClip(MaterialTheme.shapes.extraLarge)
                                .maskBorder(
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                                    ),
                                    MaterialTheme.shapes.extraLarge,
                                ).focusable()
                                .combinedClickable(
                                    onClick = {
                                        if (isActive) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                YouTubeQueue.radio(song.toMediaMetadata()),
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ),
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.48f to Color.Black.copy(alpha = 0.08f),
                                            1f to Color.Black.copy(alpha = 0.84f),
                                        ),
                                    ),
                        )

                        if (isActive && isPlaying) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                tonalElevation = 2.dp,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(14.dp)
                                        .size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.volume_up),
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp),
                        ) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToString { artist -> artist.name },
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        QuickPicksDisplayMode.LIST -> {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val widthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                val itemWidth = maxWidth * widthFactor
                val lazyGridState = rememberLazyGridState()
                val snapLayoutInfoProvider =
                    remember(lazyGridState, widthFactor) {
                        SnapLayoutInfoProvider(
                            lazyGridState = lazyGridState,
                            positionInLayout = { layoutSize, itemSize ->
                                layoutSize * widthFactor / 2f - itemSize / 2f
                            },
                        )
                    }
                LazyHorizontalGrid(
                    state = lazyGridState,
                    rows = GridCells.Fixed(4),
                    flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                    contentPadding =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * 4),
                ) {
                    items(
                        items = songs,
                        key = SongItem::id,
                        contentType = { "remote_quick_pick_song" },
                    ) { song ->
                        YouTubeListItem(
                            item = song,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            isSwipeable = false,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .width(itemWidth)
                                    .combinedClickable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    YouTubeQueue(
                                                        endpoint = song.endpoint ?: WatchEndpoint(videoId = song.id),
                                                        preloadItem = song.toMediaMetadata(),
                                                    ),
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ),
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Discovery decks — the similar-recommendations rail from upstream rukamori
// HomeScreenComponents.kt (SimilarRecommendationsSection).
// ──────────────────────────────────────────────────────────────────────

private const val DiscoveryDeckMaxItems = 4
private const val DiscoveryDeckColumns = 2

/** A playlist's cover lives in its song thumbnails; the other item kinds carry their own. */
private fun LocalItem.deckThumbnailUrl(): String? =
    when (this) {
        is Song -> thumbnailUrl
        is Album -> thumbnailUrl
        is Artist -> thumbnailUrl
        is Playlist -> thumbnails.firstOrNull()
    }

@Composable
private fun SimilarRecommendationsDecks(
    recommendations: List<SimilarRecommendation>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val distinctRecommendations =
        remember(recommendations) { recommendations.distinctBy { recommendation -> recommendation.title.lazyKey() } }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // A deck is two shelf cards wide plus its own gutter, so the cards land exactly on the
        // shelf rhythm; it only shrinks when the screen is narrower than that.
        val deckWidth =
            (HomeShelfCardWidth * DiscoveryDeckColumns + HomeShelfCardSpacing + HomeFeedGutter * 2)
                .coerceAtMost(maxWidth - HomeFeedGutter * 2)

        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeFeedGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
        ) {
            items(
                items = distinctRecommendations,
                key = { recommendation -> recommendation.title.lazyKey() },
                contentType = { "similar_discovery_deck" },
            ) { recommendation ->
                SimilarDiscoveryDeck(
                    recommendation = recommendation,
                    mediaMetadata = mediaMetadata,
                    isPlaying = isPlaying,
                    navController = navController,
                    playerConnection = playerConnection,
                    menuState = menuState,
                    haptic = haptic,
                    scope = scope,
                    modifier = Modifier.width(deckWidth),
                )
            }
        }
    }
}

@Composable
private fun SimilarDiscoveryDeck(
    recommendation: SimilarRecommendation,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val source = recommendation.title
    val deckItems =
        remember(recommendation.items) {
            recommendation.items.distinctBy { item -> item.lazyKey() }.take(DiscoveryDeckMaxItems)
        }
    val deckRows = remember(deckItems) { deckItems.chunked(DiscoveryDeckColumns) }
    val deckSongs = remember(deckItems) { deckItems.filterIsInstance<SongItem>().map(SongItem::toMediaItem) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            HomeSectionHeader(
                title = source.title,
                label = stringResource(R.string.similar_to),
                thumbnail = {
                    ItemThumbnail(
                        thumbnailUrl = source.deckThumbnailUrl(),
                        isActive = false,
                        isPlaying = false,
                        shape =
                            if (source is Artist) {
                                CircleShape
                            } else {
                                RoundedCornerShape(ThumbnailCornerRadius)
                            },
                        modifier = Modifier.size(ListThumbnailSize),
                    )
                },
                onClick = {
                    when (source) {
                        is Song -> source.album?.id?.let { albumId -> navController.navigate("album/$albumId") }
                        is Album -> navController.navigate("album/${source.id}")
                        is Artist -> navController.navigate("artist/${source.id}")
                        is Playlist -> Unit
                    }
                },
            )
            deckRows.forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HomeFeedGutter),
                ) {
                    rowItems.forEach { item ->
                        HomeFeedYTItemCard(
                            item = item,
                            mediaMetadata = mediaMetadata,
                            navController = navController,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope,
                            onPlaySongFromSection = { playerConnection.playShelfFrom(deckSongs, it, source.title) },
                            onPlayEpisode = { episode ->
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = episode.podcast?.name ?: episode.title,
                                        items = listOf(episode.toMediaItem()),
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            isPlaying = isPlaying,
                        )
                    }
                    repeat(DiscoveryDeckColumns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
