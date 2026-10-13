/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.NavigationBarAnimationSpec
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.HideSearchChromeWhileScrollingKey
import moe.rukamori.archivetune.constants.NavigationBarHeight
import moe.rukamori.archivetune.constants.SearchBarPosition
import moe.rukamori.archivetune.constants.SearchBarPositionKey
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.db.entities.SearchHistory
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.search.SearchDiscoveryUiModel
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.LocalNavigationBarHiddenByScroll
import moe.rukamori.archivetune.ui.component.PillRole
import moe.rukamori.archivetune.ui.component.PillStyle
import moe.rukamori.archivetune.ui.component.YouTubeGridItem
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.SearchSourcePicker
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.screens.rememberMoodAndGenresArtworkModel
import moe.rukamori.archivetune.ui.screens.rememberMoodAndGenresArtworkUrl
import moe.rukamori.archivetune.viewmodels.SearchDiscoveryScreenState
import moe.rukamori.archivetune.viewmodels.SearchDiscoveryTab
import moe.rukamori.archivetune.viewmodels.SearchDiscoveryViewModel
import moe.rukamori.archivetune.viewmodels.SearchHistoryViewModel
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.ui.component.rememberAppleMusicExperience
import moe.rukamori.archivetune.ui.component.rememberPillStyle

private val SearchHorizontalPadding = 24.dp
private val SearchSectionSpacing = 28.dp
private val SearchCardCornerRadius = 18.dp
private val SearchSegmentedCornerRadius = 28.dp
private val ExpressiveSearchBarHeight = 58.dp
private val ExpressiveSearchBarCornerRadius = 28.dp
private val AppleSearchBarHeight = 48.dp
private val BottomSearchPillMargin = 12.dp
private const val SearchChromeScrollThreshold = 6f

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onSearchQuery: (String) -> Unit,
    onVoiceSearch: () -> Unit = {},
    headerScrollConnection: NestedScrollConnection? = null,
    listState: LazyListState? = null,
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
    historyViewModel: SearchHistoryViewModel = hiltViewModel(),
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchProvider by rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)
    val onSearchSourceSelection: (SearchSource, SearchProvider) -> Unit = { _, provider ->
        searchProvider = provider
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val recentSearches by historyViewModel.recentSearches.collectAsStateWithLifecycle()
    val lazyListState = listState ?: rememberLazyListState()
    val appleMusicStyle = rememberAppleMusicExperience()
    val pill = rememberPillStyle()
    val bottomSearchChromeSpace =
        (if (appleMusicStyle) AppleSearchBarHeight else ExpressiveSearchBarHeight) + BottomSearchPillMargin * 2
    val searchBarPosition by rememberEnumPreference(SearchBarPositionKey, SearchBarPosition.TOP)
    val hideSearchBarWhileScrolling by rememberPreference(HideSearchChromeWhileScrollingKey, defaultValue = false)
    val searchBarAtBottom = searchBarPosition == SearchBarPosition.BOTTOM
    var searchBarScrolledAway by remember { mutableStateOf(false) }
    val searchBarScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y < -SearchChromeScrollThreshold) {
                        searchBarScrolledAway = true
                    } else if (available.y > SearchChromeScrollThreshold) {
                        searchBarScrolledAway = false
                    }
                    return Offset.Zero
                }
            }
        }
    val showBottomSearchBar =
        !hideSearchBarWhileScrolling ||
            !searchBarScrolledAway ||
            WindowInsets.isImeVisible ||
            !lazyListState.canScrollBackward
    val followHiddenNavigationBar = LocalNavigationBarHiddenByScroll.current && !WindowInsets.isImeVisible
    val navigationBarLowering by animateDpAsState(
        targetValue = if (followHiddenNavigationBar) NavigationBarHeight + pill.bottomInset else 0.dp,
        animationSpec = if (LocalAnimationsDisabled.current) snap() else NavigationBarAnimationSpec,
        label = "bottomSearchPillLowering",
    )
    val safeTopPadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
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
                ).then(
                    if (searchBarAtBottom && hideSearchBarWhileScrolling) {
                        Modifier.nestedScroll(searchBarScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding =
                PaddingValues(
                    top = maxOf(LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateTopPadding(), safeTopPadding),
                    bottom =
                        LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() +
                            if (searchBarAtBottom) bottomSearchChromeSpace else 0.dp,
                    start = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
                    end = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
                ),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!searchBarAtBottom) {
                item(
                    key = "search_field",
                    contentType = "search_field",
                ) {
                    SearchEntryField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = onSearchQuery,
                        onVoiceSearch = onVoiceSearch,
                        searchScope = SearchSource.ONLINE,
                        searchProvider = searchProvider,
                        onSourceSelection = onSearchSourceSelection,
                        appleMusicStyle = appleMusicStyle,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SearchHorizontalPadding, vertical = 8.dp)
                                .animateItem(),
                    )
                }
            }

            item(
                key = "search_tabs",
                contentType = "search_tabs",
            ) {
                SearchSegmentedTabs(
                    selectedTab = selectedTab,
                    onTabSelected = viewModel::selectTab,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SearchHorizontalPadding, vertical = 4.dp)
                            .animateItem(),
                )
            }

            when (val currentState = state) {
                SearchDiscoveryScreenState.Loading -> {
                    item(
                        key = "search_loading",
                        contentType = "search_loading",
                    ) {
                        SearchDiscoveryLoading(modifier = Modifier.animateItem())
                    }
                }

                SearchDiscoveryScreenState.Empty -> {
                    item(
                        key = "search_empty",
                        contentType = "search_empty",
                    ) {
                        SearchStateMessage(
                            message = stringResource(R.string.no_results_found),
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                is SearchDiscoveryScreenState.Error -> {
                    item(
                        key = "search_error",
                        contentType = "search_error",
                    ) {
                        SearchStateMessage(
                            message = stringResource(currentState.messageResId),
                            action = {
                                Button(onClick = viewModel::retry) {
                                    Text(stringResource(R.string.retry_button))
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                is SearchDiscoveryScreenState.Success -> {
                    when (selectedTab) {
                        SearchDiscoveryTab.EXPLORE -> {
                            if (recentSearches.isNotEmpty()) {
                                item(
                                    key = "search_recent_searches",
                                    contentType = "recent_searches",
                                ) {
                                    RecentSearchesSection(
                                        recent = recentSearches,
                                        onClear = historyViewModel::clearAll,
                                        onDelete = historyViewModel::delete,
                                        onQueryClick = onSearchQuery,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }

                            if (currentState.data.suggestedArtists.isNotEmpty()) {
                                item(
                                    key = "search_trending_searches_title",
                                    contentType = "section_title",
                                ) {
                                    SearchSectionHeader(
                                        title = stringResource(R.string.search_trending_searches),
                                        leadingIconRes = R.drawable.trending_up,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                                item(
                                    key = "search_trending_searches_chips",
                                    contentType = "trending_chips",
                                ) {
                                    TrendingSearchChips(
                                        artists = currentState.data.suggestedArtists,
                                        onChipClick = { artist ->
                                            navController.navigate("artist/${artist.id}")
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }

                        SearchDiscoveryTab.SUGGESTIONS -> {
                            if (currentState.data.suggestedArtists.isNotEmpty()) {
                                item(
                                    key = "search_suggestions_artists",
                                    contentType = "suggestion_artists",
                                ) {
                                    SearchSuggestionsRowSection(
                                        title = stringResource(R.string.search_recommended_artists),
                                        items = currentState.data.suggestedArtists,
                                        navController = navController,
                                        modifier = Modifier.animateItem(),
                                    ) { artist ->
                                        YouTubeGridItem(item = artist, modifier = Modifier.animateItem())
                                    }
                                }
                            }

                            if (currentState.data.trendingAlbums.isNotEmpty()) {
                                item(
                                    key = "search_suggestions_albums",
                                    contentType = "suggestion_albums",
                                ) {
                                    SearchSuggestionsRowSection(
                                        title = stringResource(R.string.search_recommended_albums),
                                        items = currentState.data.trendingAlbums,
                                        navController = navController,
                                        modifier = Modifier.animateItem(),
                                    ) { album ->
                                        YouTubeGridItem(item = album, modifier = Modifier.animateItem())
                                    }
                                }
                            }

                            if (currentState.data.suggestedSongs.isNotEmpty()) {
                                item(
                                    key = "search_suggestions_songs",
                                    contentType = "suggestion_songs",
                                ) {
                                    RecommendedSongsSection(
                                        songs = currentState.data.suggestedSongs,
                                        navController = navController,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom breathing room so the mini-player never overlaps content.
            item(key = "search_bottom_spacer", contentType = "spacer") {
                Spacer(Modifier.height(SearchSectionSpacing))
            }
        }

        if (searchBarAtBottom) {
            AnimatedVisibility(
                visible = showBottomSearchBar,
                enter =
                    slideInVertically(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        initialOffsetY = { it },
                    ) + fadeIn(),
                exit =
                    slideOutVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        targetOffsetY = { it },
                    ) + fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { translationY = navigationBarLowering.toPx() }
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current
                                .union(WindowInsets.ime)
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = pill.horizontalInset, vertical = BottomSearchPillMargin),
                    contentAlignment = Alignment.Center,
                ) {
                    SearchEntryField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = onSearchQuery,
                        onVoiceSearch = onVoiceSearch,
                        searchScope = SearchSource.ONLINE,
                        searchProvider = searchProvider,
                        onSourceSelection = onSearchSourceSelection,
                        appleMusicStyle = appleMusicStyle,
                        pill = pill,
                        modifier = Modifier.widthIn(max = pill.maxWidth),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEntryField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    searchScope: SearchSource,
    searchProvider: SearchProvider,
    onSourceSelection: (SearchSource, SearchProvider) -> Unit,
    appleMusicStyle: Boolean,
    modifier: Modifier = Modifier,
    pill: PillStyle? = null,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor =
        when {
            pill != null -> pill.raisedContainerColor
            !appleMusicStyle -> colors.surfaceContainerHigh
            else -> colors.onSurface.copy(alpha = 0.08f)
        }
    val shape =
        remember(pill, appleMusicStyle) {
            when {
                pill != null -> pill.shape(PillRole.STANDALONE)
                appleMusicStyle -> CircleShape
                else -> RoundedCornerShape(ExpressiveSearchBarCornerRadius)
            }
        }
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (appleMusicStyle) AppleSearchBarHeight else ExpressiveSearchBarHeight)
                .then(if (pill != null) Modifier.shadow(pill.shadowElevation, shape) else Modifier)
                .clip(shape)
                .background(containerColor)
                .then(pill?.border?.let { Modifier.border(it, shape) } ?: Modifier),
    ) {
        Icon(
            painter = painterResource(if (appleMusicStyle) R.drawable.search else R.drawable.solar_magnifer_linear),
            contentDescription = null,
            tint = onSurfaceVariant,
            modifier =
                Modifier
                    .padding(start = if (appleMusicStyle) 14.dp else 20.dp)
                    .size(if (appleMusicStyle) 20.dp else 24.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.titleMedium
                    .copy(fontSize = 16.sp)
                    .copy(color = onSurface),
            cursorBrush = SolidColor(primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        if (query.isNotEmpty()) {
                            onSearch(query)
                            keyboardController?.hide()
                        }
                    },
                ),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text =
                                stringResource(
                                    when (searchScope) {
                                        SearchSource.LOCAL -> R.string.search_library
                                        SearchSource.ONLINE ->
                                            if (searchProvider == SearchProvider.SPOTIFY) {
                                                R.string.search_source_spotify
                                            } else {
                                                R.string.search_yt_music
                                            }
                                    },
                                ),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                            color = onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        IconButton(
            onClick = onVoiceSearch,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.mic),
                contentDescription = stringResource(R.string.voice_search),
                tint = onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        SearchSourcePicker(
            currentScope = SearchSource.ONLINE,
            currentProvider = searchProvider,
            onSelection = onSourceSelection,
            includeLocal = false,
        )
    }
}

@Composable
private fun SearchSegmentedTabs(
    selectedTab: SearchDiscoveryTab,
    onTabSelected: (SearchDiscoveryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { SearchDiscoveryTab.entries }
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SearchSegmentedCornerRadius))
                .background(surfaceLow)
                .padding(4.dp),
    ) {
        tabs.forEach { tab ->
            val isSelected = selectedTab == tab
            val tabBg by animateColorAsState(
                targetValue = if (isSelected) primary else Color.Transparent,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "tabBg_${tab.name}",
            )
            val tabFg by animateColorAsState(
                targetValue = if (isSelected) onPrimary else onSurfaceVariant,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "tabFg_${tab.name}",
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(SearchSegmentedCornerRadius - 4.dp))
                        .background(tabBg)
                        .clickable { onTabSelected(tab) },
            ) {
                Text(
                    text =
                        stringResource(
                            when (tab) {
                                SearchDiscoveryTab.EXPLORE -> R.string.explore
                                SearchDiscoveryTab.SUGGESTIONS -> R.string.suggestions
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = tabFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    leadingIconRes: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SearchHorizontalPadding, vertical = 8.dp),
    ) {
        // Leading icon in a circular container — matches the Home page's
        // HomeSectionLeadingIcon pattern (clock for Recently Played, bolt
        // for Speed Dial) so every section header across the app has a
        // recognisable affordance before its title text.
        if (leadingIconRes != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(leadingIconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchesSection(
    recent: List<SearchHistory>,
    onClear: () -> Unit,
    onDelete: (SearchHistory) -> Unit,
    onQueryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SearchSectionHeader(
            title = stringResource(R.string.search_recent_searches),
            leadingIconRes = R.drawable.history,
            trailing = {
                Text(
                    text = stringResource(R.string.clear),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClear)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            },
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SearchHorizontalPadding),
        ) {
            recent.forEach { item ->
                RecentSearchRow(
                    history = item,
                    onDelete = onDelete,
                    onClick = { onQueryClick(item.query) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchRow(
    history: SearchHistory,
    onDelete: (SearchHistory) -> Unit,
    onClick: () -> Unit,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onDelete(history)
                    true
                } else {
                    false
                }
            },
            positionalThreshold = { distance -> distance * 0.5f },
        )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val onError = MaterialTheme.colorScheme.error
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(onError.copy(alpha = 0.18f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    tint = onError,
                    modifier =
                        Modifier
                            .padding(end = 20.dp)
                            .size(22.dp),
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .combinedClickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            RecentSearchMonogram(query = history.query)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = history.query,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.search_recent_label),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RecentSearchMonogram(query: String) {
    val initial = remember(query) {
        query.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"
    }
    // Search icon sits behind the initial letter, giving each recent-search
    // row a recognizable "search" affordance (user-requested: "add icons
    // behind recent searches"). The letter remains prominent in the
    // foreground; the icon is dimmed so it doesn't compete.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // Background search icon — dimmed, slightly offset down-right.
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(26.dp),
        )
        // Foreground initial letter.
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BasedOnWhatYouLikeGrid(
    data: SearchDiscoveryUiModel,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val columns = 2
    val rows = (data.moodAndGenres.size + columns - 1) / columns

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SearchHorizontalPadding),
    ) {
        data.moodAndGenres.chunked(columns).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { item ->
                    MoodCard(
                        title = item.title,
                        stripeColor = item.stripeColor,
                        endpoint = item.endpoint,
                        onClick = {
                            navController.navigate(
                                "youtube_browse/${item.endpoint.browseId}?params=${item.endpoint.params}",
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the last row so cards stay equal width.
                if (rowItems.size < columns) {
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MoodCard(
    title: String,
    stripeColor: Long,
    endpoint: BrowseEndpoint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = remember(stripeColor) { Color(stripeColor) }
    val surface = MaterialTheme.colorScheme.surface
    val scrim = MaterialTheme.colorScheme.scrim
    // Reuse the same artwork cache as the MoodAndGenres screen so tiles that
    // were already resolved there appear instantly here too. The artwork is
    // loaded async from YouTube browse (the gradient remains as a graceful
    // placeholder while the thumbnail loads or if it never resolves).
    val artworkUrl = rememberMoodAndGenresArtworkUrl(endpoint)
    val artworkModel = rememberMoodAndGenresArtworkModel(endpoint = endpoint, artworkUrl = artworkUrl)
    val cardBrush =
        remember(base, surface) {
            Brush.linearGradient(
                colors =
                    listOf(
                        base.copy(alpha = 0.55f),
                        surface.copy(alpha = 0.92f),
                    ),
            )
        }
    val textScrimBrush =
        remember(scrim) {
            Brush.verticalGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        scrim.copy(alpha = 0.7f),
                    ),
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(SearchCardCornerRadius))
                .background(cardBrush)
                .clickable(onClick = onClick),
    ) {
        // Artwork thumbnail — fills the card, cropped.
        if (artworkModel != null) {
            AsyncImage(
                model = artworkModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Bottom gradient for title legibility.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(textScrimBrush),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun TrendingSearchChips(
    artists: List<ArtistItem>,
    onChipClick: (ArtistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleArtists = remember(artists) { artists.take(10) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = SearchHorizontalPadding),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items = visibleArtists,
            key = { artist -> artist.id },
            contentType = { "trending_chip" },
        ) { artist ->
            TrendingChip(
                label = artist.title,
                onClick = { onChipClick(artist) },
            )
        }
    }
}

@Composable
private fun TrendingChip(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // Leading trending icon — gives each chip a recognizable "trending"
        // affordance, matching the user's request to add icons behind trending
        // searches.
        Icon(
            painter = painterResource(R.drawable.trending_up),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> SearchSuggestionsRowSection(
    title: String,
    items: List<T>,
    navController: NavController,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SearchSectionHeader(title = title, leadingIconRes = R.drawable.search)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = SearchHorizontalPadding),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = items,
                key = { item ->
                    when (item) {
                        is ArtistItem -> "artist_${item.id}"
                        is AlbumItem -> "album_${item.id}"
                        is SongItem -> "song_${item.id}"
                        else -> item.hashCode()
                    }
                },
                contentType = { "suggestion_row_item" },
            ) { item ->
                itemContent(item)
            }
        }
        Spacer(Modifier.height(SearchSectionSpacing))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendedSongsSection(
    songs: List<SongItem>,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val visibleSongs = remember(songs) { songs.take(6) }

    Column(modifier = modifier.fillMaxWidth()) {
        SearchSectionHeader(title = stringResource(R.string.search_recommended_songs), leadingIconRes = R.drawable.music_note)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SearchHorizontalPadding),
        ) {
            visibleSongs.forEachIndexed { index, song ->
                val isActive = song.id == mediaMetadata?.id
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (isActive) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .combinedClickable(
                                onClick = {
                                    if (isActive) {
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
                ) {
                    YouTubeListItem(
                        item = song,
                        albumIndex = index + 1,
                        viewCountText = song.viewCountText,
                        isActive = isActive,
                        isPlaying = isPlaying,
                        isSwipeable = false,
                        showActiveContainer = false,
                        trailingContent = {
                            YouTubeSongMenuButton(song = song, navController = navController)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeSongMenuButton(
    song: SongItem,
    navController: NavController,
) {
    val menuState = LocalMenuState.current
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
}

@Composable
private fun SearchDiscoveryLoading(modifier: Modifier = Modifier) {
    ShimmerHost(
        modifier = modifier.fillMaxWidth(),
    ) {
        TextPlaceholder(
            height = 56.dp,
            modifier =
                Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .fillMaxWidth(),
        )
        TextPlaceholder(
            height = 28.dp,
            modifier =
                Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .width(180.dp),
        )
        repeat(6) {
            TextPlaceholder(
                height = 84.dp,
                modifier =
                    Modifier
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchStateMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search_off),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(content = action)
        }
    }
}
