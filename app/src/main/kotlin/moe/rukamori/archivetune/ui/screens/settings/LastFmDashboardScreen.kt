/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LibraryCoverCandidate
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DarkModeKey
import moe.rukamori.archivetune.constants.LastFmPreferYtThumbnailsKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.SearchResult
import moe.rukamori.archivetune.lastfm.CatalogueCoverProvider
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lastfm.LastFmArtworkNormalizer
import moe.rukamori.archivetune.lastfm.models.RecentTrack
import moe.rukamori.archivetune.lastfm.models.TopAlbumsResponse
import moe.rukamori.archivetune.lastfm.models.TopArtistsResponse
import moe.rukamori.archivetune.lastfm.models.TopTrack
import moe.rukamori.archivetune.lastfm.models.TopTracksResponse
import moe.rukamori.archivetune.lastfm.models.UserImage
import moe.rukamori.archivetune.lastfm.models.UserInfo
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.scrobbling.LastFmSettingsRepository
import moe.rukamori.archivetune.telegram.TelegramCoverProvider
import moe.rukamori.archivetune.ui.component.IconButton as AppIconButton
import moe.rukamori.archivetune.ui.menu.AddToPlaylistDialog
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.ui.utils.YTThumbQuality
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.buildYTThumbnailUrl
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// ── Theme tokens ────────────────────────────────────────────────────────────

/** Centralised color tokens for the Last.fm dashboard. */
private data class DashboardTheme(
    val pageBackground: Color,
    val cardBackground: Color,
    val pillBackground: Color,
    val accent: Color,
    val nowPlayingRowBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val statsHeroInner: Color,
    val statsHeroNumberText: Color,
    val statsHeroLabelText: Color,
    val statsPillBackground: Color,
    val statsPillValueText: Color,
    val statsPillLabelText: Color,
    val heroArrowCircleBackground: Color,
    val heroArrowIconTint: Color,
    val rankingBadgeBackground: Color,
    val rankingBadgeText: Color,
    val playCountPillBackground: Color,
    val playCountPillText: Color,
    val nowPlayingPillBackground: Color,
    val nowPlayingPillText: Color,
    val nowPlayingDotColor: Color,
    val nowPlayingTrackTitle: Color,
    val nowPlayingTrackArtist: Color,
    val artworkPlaceholderBackground: Color,
    val artworkPlaceholderTint: Color,
    val filterPillBackground: Color,
    val filterPillText: Color,
    val filterPillIconTint: Color,
    val dropdownBackground: Color,
    val dropdownActiveItemBackground: Color,
    val dropdownActiveItemText: Color,
    val dropdownActiveItemIconTint: Color,
    val dropdownInactiveItemText: Color,
    val dropdownInactiveItemIconTint: Color,
    val dropdownCheckTint: Color,
    val overflowIconTint: Color,
    val topAppBarContainer: Color,
    val topAppBarIconTint: Color,
    val topAppBarTitleText: Color,
    val fallbackCardBackground: Color,
    val fallbackAvatarBackground: Color,
    val fallbackAvatarTint: Color,
    val signInAvatarBackground: Color,
    val signInAvatarTint: Color,
    val signInButtonText: Color,
    val signInButtonContainer: Color,
    val emptyHintText: Color,
    val dividerColor: Color,
)

@Composable
private fun isDashboardDarkTheme(): Boolean {
    val darkMode by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    return if (darkMode == DarkMode.AUTO) isSystemInDarkTheme() else darkMode == DarkMode.ON
}

/** Builds a [DashboardTheme] from the current [MaterialTheme.colorScheme]. */
@Composable
private fun dashboardTheme(): DashboardTheme {
    val cs = MaterialTheme.colorScheme
    val isDark = isDashboardDarkTheme()
    // Surface tokens — the single source of truth for "what shade is this
    // surface". Using surfaceContainer* gives us the standard Material 3
    // elevation stack (background → surface → surfaceContainer →
    // surfaceContainerHigh → surfaceContainerHighest), so cards/pills
    // naturally appear elevated over the page background.
    val pageBackground = cs.background
    val cardBackground = cs.surfaceContainer
    val elevatedSurface = cs.surfaceContainerHigh
    val accent = cs.primary
    val onAccent = cs.onPrimary
    val accentContainer = cs.primaryContainer
    val onAccentContainer = cs.onPrimaryContainer
    val secondaryContainer = cs.secondaryContainer
    val onSecondaryContainer = cs.onSecondaryContainer

    // The now-playing pulse row uses a translucent accent so the user can
    // tell it's a "now playing" highlight without losing text contrast.
    val nowPlayingRowBg = accent.copy(alpha = if (isDark) 0.16f else 0.12f)

    return DashboardTheme(
        pageBackground = pageBackground,
        cardBackground = cardBackground,
        pillBackground = elevatedSurface,
        accent = accent,
        nowPlayingRowBackground = nowPlayingRowBg,
        textPrimary = cs.onBackground,
        textSecondary = cs.onSurfaceVariant,
        statsHeroInner = accentContainer,
        statsHeroNumberText = onAccentContainer,
        statsHeroLabelText = onAccentContainer.copy(alpha = 0.75f),
        statsPillBackground = elevatedSurface,
        statsPillValueText = cs.onSurface,
        statsPillLabelText = cs.onSurfaceVariant,
        heroArrowCircleBackground = accent,
        heroArrowIconTint = onAccent,
        rankingBadgeBackground = elevatedSurface,
        rankingBadgeText = accent,
        playCountPillBackground = elevatedSurface,
        playCountPillText = cs.onSurfaceVariant,
        nowPlayingPillBackground = accent,
        nowPlayingPillText = onAccent,
        nowPlayingDotColor = onAccent,
        nowPlayingTrackTitle = cs.onSurface,
        nowPlayingTrackArtist = cs.onSurface.copy(alpha = 0.8f),
        artworkPlaceholderBackground = elevatedSurface,
        artworkPlaceholderTint = cs.onSurfaceVariant,
        filterPillBackground = elevatedSurface,
        filterPillText = cs.onSurface,
        filterPillIconTint = cs.onSurfaceVariant,
        dropdownBackground = cardBackground,
        dropdownActiveItemBackground = accent.copy(alpha = if (isDark) 0.22f else 0.14f),
        dropdownActiveItemText = accent,
        dropdownActiveItemIconTint = accent,
        dropdownInactiveItemText = cs.onSurface,
        dropdownInactiveItemIconTint = cs.onSurfaceVariant,
        dropdownCheckTint = accent,
        overflowIconTint = cs.onSurface,
        topAppBarContainer = pageBackground,
        topAppBarIconTint = cs.onSurface,
        topAppBarTitleText = cs.onSurface,
        fallbackCardBackground = cardBackground,
        fallbackAvatarBackground = secondaryContainer,
        fallbackAvatarTint = onSecondaryContainer,
        signInAvatarBackground = secondaryContainer,
        signInAvatarTint = onSecondaryContainer,
        signInButtonText = onAccent,
        signInButtonContainer = accent,
        emptyHintText = cs.onSurfaceVariant,
        dividerColor = cs.onSurface.copy(alpha = 0.08f),
    )
}

private enum class LastFmFilter { RECENT, TOP_TRACKS, TOP_ARTISTS, TOP_ALBUMS }

// Last successful load, shown immediately when the dashboard reopens while a refresh runs, and
// kept per section when a refresh fails offline.
private data class LastFmDashboardSnapshot(
    val username: String,
    val userInfo: Result<UserInfo>,
    val recentTracks: Result<List<RecentTrack>>,
    val topTracks: Result<TopTracksResponse>,
    val topArtists: Result<TopArtistsResponse>,
    val topAlbums: Result<TopAlbumsResponse>,
)

@Volatile
private var dashboardSnapshot: LastFmDashboardSnapshot? = null

private fun <T> Result<T>.orPreviousSuccess(previous: Result<T>?): Result<T> =
    if (isFailure && previous?.isSuccess == true) previous else this

/**
 * Process-wide cache of YouTube search results keyed by "<title>::<artist>". Last.fm tracks don't
 * carry YouTube video IDs, so every playback action on a Last.fm track needs to round-trip through
 * a YouTube song search first.
 */
private val ytSearchCache = ConcurrentHashMap<String, SongItem?>()

/**
 * Searches YouTube Music for a Last.fm track by "<title> <artist>" query and returns the first
 * SongItem result, or null if no match. Results are cached in [ytSearchCache] to avoid re-searching
 * for the same track.
 */
private suspend fun searchYtForLastFmTrack(title: String, artist: String?): SongItem? {
    if (title.isBlank()) return null
    val cacheKey = "${title.trim().lowercase()}::${artist?.trim()?.lowercase().orEmpty()}"
    ytSearchCache[cacheKey]?.let {
        Timber.d("searchYt cache hit: %s → %s", cacheKey, it?.id ?: "null-cached")
        return it
    }
    val term = listOfNotNull(artist?.takeIf(String::isNotBlank), title).joinToString(" ")
    Timber.d("searchYt query: \"%s\" (key=%s)", term, cacheKey)
    val searchResult = YouTube.search(term, YouTube.SearchFilter.FILTER_SONG).getOrNull()
    if (searchResult == null) {
        Timber.w("searchYt no result for: \"%s\"", term)
        ytSearchCache[cacheKey] = null
        return null
    }
    val first = findFirstSongItem(searchResult)
    if (first == null) {
        Timber.w("searchYt no SongItem in results for: \"%s\"", term)
    } else {
        Timber.d("searchYt resolved: \"%s\" → videoId=%s thumb=%s", term, first.id, first.thumbnail)
    }
    ytSearchCache[cacheKey] = first
    return first
}

/** Extracts the first SongItem from a SearchResult's items list. */
private fun findFirstSongItem(result: SearchResult): SongItem? {
    for (item in result.items) {
        if (item is SongItem) return item
    }
    return null
}

/** Lightweight track reference carried across the bottom-sheet boundary. */
private data class LastFmTrackRef(
    val title: String,
    val artist: String?,
    val url: String?,
    val image: List<UserImage>?,
    val playCount: Int?,
    val isNowPlaying: Boolean,
) {
    fun artworkKey(): String = "${title.trim().lowercase()}::${artist.orEmpty().trim().lowercase()}"
}

private fun RecentTrack.toRef(playCount: Int? = null): LastFmTrackRef = LastFmTrackRef(
    title = name.orEmpty(),
    artist = artist?.text,
    url = url,
    image = image,
    playCount = playCount,
    isNowPlaying = isNowPlaying,
)

private fun TopTrack.toRef(): LastFmTrackRef = LastFmTrackRef(
    title = name.orEmpty(),
    artist = artist?.text,
    url = url,
    image = image,
    playCount = playcount,
    isNowPlaying = false,
)

// ── Recent-track dedup ──────────────────────────────────────────────────────

/** Paired (track, count) for the merged recent-tracks list. */
private data class RecentTrackWithCount(
    val track: RecentTrack,
    val playCount: Int,
)

/**
 * Group consecutive recent-tracks by `(name, artist.text)` and emit a single [RecentTrackWithCount]
 * per group, with [RecentTrackWithCount.playCount] equal to the number of consecutive scrobbles.
 */
private fun List<RecentTrack>.mergeDuplicatesWithCount(): List<RecentTrackWithCount> {
    if (isEmpty()) return emptyList()
    val nowPlayingKey = firstOrNull { it.isNowPlaying }?.trackArtworkKey()
    val result = mutableListOf<RecentTrackWithCount>()
    for (track in this) {
        val key = track.trackArtworkKey()
        if (nowPlayingKey != null && key == nowPlayingKey && !track.isNowPlaying) continue
        val last = result.lastOrNull()
        if (last != null && last.track.trackArtworkKey() == key) {
            // Promote to now-playing if any copy in the run is now-playing —
            // the now-playing flag should be preserved across the merge so
            // the row renders with the pulsing badge.
            val mergedIsNowPlaying = last.track.isNowPlaying || track.isNowPlaying
            val representative = if (mergedIsNowPlaying && track.isNowPlaying) track else last.track
            result[result.lastIndex] = last.copy(
                track = representative,
                playCount = last.playCount + 1,
            )
        } else {
            result.add(RecentTrackWithCount(track, 1))
        }
    }
    return result
}

// ── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun LastFmDashboardScreen(
    navController: NavController,
    repository: LastFmSettingsRepository = hiltViewModel<LastFmDashboardViewModel>().repository,
) {
    val theme = dashboardTheme()
    val settings by repository.observeSettings().collectAsStateWithLifecycle(initialValue = null)
    val current = settings
    val isLoggedIn = current?.isLoggedIn == true

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current

    var userInfo by remember { mutableStateOf<Result<UserInfo>?>(null) }
    var recentTracks by remember { mutableStateOf<Result<List<RecentTrack>>?>(null) }
    // Full TopX*Response wrappers are kept (rather than unwrapping to bare
    // lists at fetch time) so the dashboard can read both the page list
    // (.toptracks.track / .topartists.artist / .topalbums.album) AND the
    // canonical count of unique items ever scrobbled (.toptracks.attr.total /
    // .topartists.attr.total / .topalbums.attr.total) from a single piece of
    // state. The previous implementation unwrapped immediately and lost the
    // .attr.total, so the hero stat pills had to fall back to the page size
    // (always 20). Storing the wrapper keeps the canonical counts available
    // for the stat pills while still letting the track list call sites unwrap
    // once at the read site.
    var topTracks by remember { mutableStateOf<Result<TopTracksResponse>?>(null) }
    var topArtists by remember { mutableStateOf<Result<TopArtistsResponse>?>(null) }
    var topAlbums by remember { mutableStateOf<Result<TopAlbumsResponse>?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(LastFmFilter.RECENT) }
    var overflowTrack by remember { mutableStateOf<LastFmTrackRef?>(null) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    // Captured ref of the track being added to a playlist. Decoupled from
    // [overflowTrack] because the bottom sheet is dismissed BEFORE the
    // AddToPlaylistDialog opens (otherwise the dialog renders behind the
    // sheet's high-elevation scrim). The ref lives here so it survives the
    // sheet dismissal and is consumed by the dialog's onGetSong.
    var showAddToPlaylistTrack by remember { mutableStateOf<LastFmTrackRef?>(null) }
    // Inline scrobble-search overlay: tapping the header search icon toggles a
    // TextField that filters the currently-loaded recent + top tracks by
    // title / artist, with an "×N" badge for each match's play count (from the
    // merged-dedup data). Empty when not visible.
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun applySnapshot(snapshot: LastFmDashboardSnapshot) {
        userInfo = snapshot.userInfo
        recentTracks = snapshot.recentTracks
        topTracks = snapshot.topTracks
        topArtists = snapshot.topArtists
        topAlbums = snapshot.topAlbums
    }

    fun refresh() {
        val username = current?.username?.takeIf { it.isNotBlank() } ?: return
        if (!isLoggedIn) return
        scope.launch {
            isRefreshing = true
            try {
                current.serviceConfig.apply(sessionKey = current.sessionKey)
                // Five parallel calls. The hero stat pills read `@attr.total` (the lifetime
                // unique-item count) from the same limit=20 top-list responses that feed the
                // filter views, so no separate limit=1 stats requests are needed.
                withContext(Dispatchers.IO) {
                    val infoDeferred = async { LastFM.getUserInfo(username) }
                    // Last.fm permits up to 200 recent tracks per request. Keep every
                    // returned scrobble rather than collapsing repeated plays, so every
                    // recent listening event remains visible in the list.
                    val recentDeferred = async { LastFM.getRecentTracks(username, limit = 200) }
                    val topTracksDeferred = async { LastFM.getTopTracks(username, period = "overall", limit = 20) }
                    val topArtistsDeferred = async { LastFM.getTopArtists(username, period = "overall", limit = 20) }
                    val topAlbumsDeferred = async { LastFM.getTopAlbums(username, period = "overall", limit = 20) }

                    val infoResult = infoDeferred.await()
                    val recentResult = recentDeferred.await()
                    val topTracksResult = topTracksDeferred.await()
                    val topArtistsResult = topArtistsDeferred.await()
                    val topAlbumsResult = topAlbumsDeferred.await()

                    // Surface parse / auth failures to logcat so the dashboard
                    // doesn't silently fall back to "0" stats without any signal
                    // that the request failed (the previous implementation would
                    // show 0 across the board with no clue as to why).
                    infoResult.onFailure { Timber.e(it, "Last.fm user.getInfo failed") }
                    recentResult.onFailure { Timber.e(it, "Last.fm user.getRecentTracks failed") }
                    topTracksResult.onFailure { Timber.e(it, "Last.fm user.getTopTracks(limit=20) failed") }
                    topArtistsResult.onFailure { Timber.e(it, "Last.fm user.getTopArtists(limit=20) failed") }
                    topAlbumsResult.onFailure { Timber.e(it, "Last.fm user.getTopAlbums(limit=20) failed") }

                    // A failed refresh keeps the last successful section instead of replacing
                    // loaded data with an error while offline.
                    val previous = dashboardSnapshot?.takeIf { it.username == username }
                    val snapshot =
                        LastFmDashboardSnapshot(
                            username = username,
                            userInfo = infoResult.orPreviousSuccess(previous?.userInfo),
                            recentTracks = recentResult.map { it.recenttracks.track }.orPreviousSuccess(previous?.recentTracks),
                            topTracks = topTracksResult.orPreviousSuccess(previous?.topTracks),
                            topArtists = topArtistsResult.orPreviousSuccess(previous?.topArtists),
                            topAlbums = topAlbumsResult.orPreviousSuccess(previous?.topAlbums),
                        )
                    dashboardSnapshot = snapshot
                    applySnapshot(snapshot)
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(isLoggedIn, current?.username) {
        if (!isLoggedIn) return@LaunchedEffect
        dashboardSnapshot?.takeIf { it.username == current?.username }?.let(::applySnapshot)
        refresh()
    }

    // ── Notch / status-bar handling ───────────────────────────────────── The Scaffold's
    // `contentWindowInsets` is set to `WindowInsets.safeDrawing` so that the body content's
    // `innerPadding` always accounts for the display cutout / status bar — even when the user has
    // toggled "hide status bar" on (in which case `WindowInsets.statusBars` reports 0 but
    // `safeDrawing.displayCutout` still tracks the physical notch).
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = theme.pageBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LastFmDashboardHeader(
                searchVisible = searchVisible,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = {
                    searchVisible = !searchVisible
                    if (!searchVisible) searchQuery = ""
                },
                theme = theme,
                profileImageUrl = bestArtwork(userInfo?.getOrNull()?.image),
                onBack = navController::navigateUp,
                onBackLong = navController::backToMain,
            )
        },
    ) { innerPadding ->
        if (current == null) {
            Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = theme.accent)
            }
            return@Scaffold
        }

        if (!isLoggedIn) {
            NotSignedIn(
                onSignIn = { navController.navigate("settings/lastfm") },
                theme = theme,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
            return@Scaffold
        }

        val recent = remember(recentTracks) {
            // Preserve the complete recent-history response while grouping only
            // consecutive identical scrobbles into the ×N row the dashboard
            // uses for repeat playback.
            recentTracks?.getOrNull().orEmpty().mergeDuplicatesWithCount()
        }
        // Unwrap the page list off the stored wrapper (state still holds the
        // wrapper so the hero stat pills can read .attr.total off the same
        // source — see the var declaration comment).
        val top = topTracks?.getOrNull()?.toptracks?.track.orEmpty()
        val artists = topArtists?.getOrNull()?.topartists?.artist.orEmpty()
        val albums = topAlbums?.getOrNull()?.topalbums?.album.orEmpty()
        val recentArtworkByTrack = remember(recent) {
            recent.associateArtworkByTrack()
        }

        // Track artwork is resolved lazily per-row inside [DashboardTrackRow] via its own
        // `LaunchedEffect(track.artworkKey())`. A per-row effect only resolves what is actually
        // composed, whereas an upfront batch LaunchedEffect over all tracks also runs for
        // off-screen rows — that is 50+ parallel YouTube searches the moment the dashboard opens
        // with a long recent-tracks list, before the user has scrolled past row 3. The
        // [CachedArtworkStore] process cache (seed lookups below) is still consulted so a
        // previously-resolved row doesn't re-resolve.

        // Scrobble-search filter: case-insensitive substring match on title or
        // artist text. Empty query = no filtering (return the original list).
        val q = searchQuery.trim()
        val recentFiltered = remember(recent, q) {
            if (q.isBlank() || !searchVisible) recent
            else recent.filter { e ->
                e.track.name?.contains(q, ignoreCase = true) == true ||
                    e.track.artist?.text?.contains(q, ignoreCase = true) == true
            }
        }
        val topFiltered = remember(top, q) {
            if (q.isBlank() || !searchVisible) top
            else top.filter { t ->
                t.name?.contains(q, ignoreCase = true) == true ||
                    t.artist?.text?.contains(q, ignoreCase = true) == true
            }
        }
        val artistsFiltered = remember(artists, q) {
            if (q.isBlank() || !searchVisible) artists
            else artists.filter { it.name?.contains(q, ignoreCase = true) == true }
        }
        val albumsFiltered = remember(albums, q) {
            if (q.isBlank() || !searchVisible) albums
            else albums.filter { a ->
                a.name?.contains(q, ignoreCase = true) == true ||
                    a.artist?.text?.contains(q, ignoreCase = true) == true
            }
        }

        // ── Artist image resolution ────────────────────────────────────
        //
        // Last.fm artist images are sparse (the placeholder hash gets rejected
        // by LastFmArtworkNormalizer for less-known artists), so for any artist
        // whose image array is empty we fall back to a YouTube artist-channel
        // search and use the returned thumbnail. Mirrors the track artwork
        // pipeline (seed cache → resolve missing → publish snapshot).
        val database = LocalDatabase.current
        val artistSeedMap = remember(artists) {
            val snapshot = HashMap<String, String>()
            for (artist in artists) {
                val key = artist.name.orEmpty().trim().lowercase()
                if (key.isBlank()) continue
                CachedArtworkStore.get("artist::$key")?.let { snapshot[key] = it }
            }
            snapshot
        }
        var artistArtworkByName by remember { mutableStateOf<Map<String, String>>(artistSeedMap) }

        LaunchedEffect(artists) {
            if (artists.isEmpty()) return@LaunchedEffect
            val snapshot = HashMap<String, String>(artistArtworkByName)
            // Seed with any Last.fm-provided images first (synchronous, no IO).
            for (artist in artists) {
                val name = artist.name.orEmpty()
                val key = name.trim().lowercase()
                if (key.isBlank() || snapshot.containsKey(key)) continue
                val lastFmImage = bestArtwork(artist.image)
                if (!lastFmImage.isNullOrBlank()) {
                    snapshot[key] = lastFmImage
                    CachedArtworkStore.put("artist::$key", lastFmImage)
                }
            }
            artistArtworkByName = snapshot.toMap()
            // Resolve missing entries via YouTube artist search in parallel.
            val toResolve = artists
                .filter { artist ->
                    val key = artist.name.orEmpty().trim().lowercase()
                    key.isNotBlank() && !snapshot.containsKey(key)
                }
            if (toResolve.isEmpty()) return@LaunchedEffect
            val resolved = withContext(Dispatchers.IO) {
                toResolve
                    .map { artist ->
                        async(Dispatchers.IO) {
                            val name = artist.name.orEmpty()
                            val key = name.trim().lowercase()
                            // Last.fm has no portraits at all now, so the artists the library already
                            // knows by name come first; the YouTube search is for the rest.
                            val url =
                                runCatching { database.libraryArtistImage(name.trim()) }.getOrNull()?.takeIf(String::isNotBlank)
                                    ?: resolveArtistImage(name)
                            if (url != null) {
                                CachedArtworkStore.put("artist::$key", url)
                                key to url
                            } else {
                                null
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
            if (resolved.isNotEmpty()) {
                resolved.forEach { (k, u) -> snapshot[k] = u }
                artistArtworkByName = snapshot.toMap()
            }
        }

        val playerAwareInsets = LocalPlayerAwareWindowInsets.current
        val density = LocalDensity.current
        val bottomInsetDp = with(density) { playerAwareInsets.getBottom(density).toDp() }

        // (v4 redesign) Layout now mirrors LastWave-native's HomeScreen
        // structure: header pills + hero stats card sit ABOVE a rounded
        // list container (ListContainerShape — 28dp top corners, filled
        // with theme.cardBackground) that holds the FilterHeader + the
        // scrollable track list. The previous single LazyColumn scrolled
        // the header pills and hero card along with the tracks; the
        // reference design keeps them fixed above the list, which also
        // gives the list its own card-like visual grouping.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    playerAwareInsets.only(WindowInsetsSides.Horizontal),
                ),
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding()))

            // Hide hero card while the scrobble-search overlay is open —
            // the user is searching, not browsing stats, so we collapse
            // down to just the search field + filtered list.
            //
            // No separate username/scrobbles pill row above this: the hero
            // card's own avatar and scrobble count already show both, so a
            // pill row here would just duplicate it.
            if (!searchVisible) {
                HeroStatsCard(
                    userInfo = userInfo,
                    isRefreshing = isRefreshing,
                    trackCount = topTracks
                        ?.getOrNull()?.toptracks?.attr?.total?.toIntOrNull() ?: 0,
                    artistCount = topArtists
                        ?.getOrNull()?.topartists?.attr?.total?.toIntOrNull() ?: 0,
                    albumCount = topAlbums
                        ?.getOrNull()?.topalbums?.attr?.total?.toIntOrNull() ?: 0,
                    onRetry = ::refresh,
                    onOpenProfile = {
                        userInfo?.getOrNull()?.url
                            ?.takeIf { it.isNotBlank() }
                            ?.let { profileUrl ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(profileUrl)))
                            }
                    },
                    theme = theme,
                )

                Spacer(Modifier.height(8.dp))
            }

            // List container — no background card. The track rows sit directly
            // on the page background (matching LastWave-native's design where
            // the list is NOT inside a coloured container): a dark card behind
            // the rows reads as a block of black behind the songs.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                FilterHeader(
                    selectedFilter = selectedFilter,
                    onSelect = { selectedFilter = it },
                    theme = theme,
                )

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 0.dp,
                        bottom = bottomInsetDp + 16.dp,
                    ),
                ) {
                    when (selectedFilter) {
                        LastFmFilter.RECENT -> {
                            if (recentFiltered.isEmpty() && recentTracks != null && !isRefreshing) {
                                item(key = "recent_empty") {
                                    EmptyHint(
                                        text = if (searchVisible && q.isNotBlank())
                                            stringResource(R.string.lastfm_no_search_results)
                                        else stringResource(R.string.lastfm_no_recent_tracks),
                                        theme = theme,
                                    )
                                }
                            } else {
                                items(
                                    recentFiltered,
                                    key = { "recent_${it.track.name}_${it.track.date?.uts ?: it.track.attr?.nowplaying ?: ""}" },
                                ) { entry ->
                                    DashboardTrackRow(
                                        track = entry.track.toRef(playCount = entry.playCount),
                                        fallbackArtworkUrl = recentArtworkByTrack[entry.track.trackArtworkKey()],
                                        onOverflow = { overflowTrack = entry.track.toRef(playCount = entry.playCount) },
                                        theme = theme,
                                    )
                                }
                            }
                        }
                        LastFmFilter.TOP_TRACKS -> {
                            if (topFiltered.isEmpty() && topTracks != null && !isRefreshing) {
                                item(key = "top_empty") {
                                    EmptyHint(
                                        text = if (searchVisible && q.isNotBlank())
                                            stringResource(R.string.lastfm_no_search_results)
                                        else stringResource(R.string.lastfm_no_top_tracks),
                                        theme = theme,
                                    )
                                }
                            } else {
                                items(
                                    topFiltered.withIndex().toList(),
                                    key = { "top_${it.index}_${it.value.name}" },
                                ) { (index, track) ->
                                    DashboardTrackRow(
                                        track = track.toRef(),
                                        rank = index + 1,
                                        fallbackArtworkUrl = recentArtworkByTrack[track.trackArtworkKey()],
                                        onOverflow = { overflowTrack = track.toRef() },
                                        theme = theme,
                                    )
                                }
                            }
                        }
                        LastFmFilter.TOP_ARTISTS -> {
                            if (artistsFiltered.isEmpty() && topArtists != null && !isRefreshing) {
                                item(key = "artists_empty") {
                                    EmptyHint(
                                        text = if (searchVisible && q.isNotBlank())
                                            stringResource(R.string.lastfm_no_search_results)
                                        else stringResource(R.string.lastfm_no_top_tracks),
                                        theme = theme,
                                    )
                                }
                            } else {
                                items(
                                    artistsFiltered.withIndex().toList(),
                                    key = { "artist_${it.index}_${it.value.name}" },
                                ) { (index, artist) ->
                                    DashboardArtistRow(
                                        name = artist.name.orEmpty(),
                                        playCount = artist.playcount,
                                        rank = index + 1,
                                        artworkUrl = bestArtwork(artist.image)
                                            ?: artistArtworkByName[artist.name.orEmpty().trim().lowercase()],
                                        theme = theme,
                                    )
                                }
                            }
                        }
                        LastFmFilter.TOP_ALBUMS -> {
                            if (albumsFiltered.isEmpty() && topAlbums != null && !isRefreshing) {
                                item(key = "albums_empty") {
                                    EmptyHint(
                                        text = if (searchVisible && q.isNotBlank())
                                            stringResource(R.string.lastfm_no_search_results)
                                        else stringResource(R.string.lastfm_no_top_tracks),
                                        theme = theme,
                                    )
                                }
                            } else {
                                items(
                                    albumsFiltered.withIndex().toList(),
                                    key = { "album_${it.index}_${it.value.name}_${it.value.artist?.text ?: ""}" },
                                ) { (index, album) ->
                                    val albumTitle = album.name.orEmpty()
                                    val albumArtist = album.artist?.text
                                    DashboardAlbumRow(
                                        title = albumTitle,
                                        artist = albumArtist,
                                        playCount = album.playcount,
                                        rank = index + 1,
                                        // 29 of a typical 50 top albums come back with no image at all,
                                        // so the row resolves its own the way a track row does.
                                        artworkUrl =
                                            rememberResolvedArtwork(
                                                lastFmUrl = bestArtwork(album.image),
                                                key = "album::${albumTitle.trim().lowercase()}::${albumArtist.orEmpty().trim().lowercase()}",
                                                title = albumTitle,
                                                artist = albumArtist,
                                            ),
                                        theme = theme,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dismiss the bottom sheet FIRST, then surface the AddToPlaylistDialog on top:
        // a low-elevation AlertDialog opened under a mounted high-elevation
        // ModalBottomSheet is visually hidden, so the user sees nothing happen. The
        // sheet's onDismiss clears `overflowTrack`, but we capture the ref locally
        // first so the dialog still has a track to resolve.
        overflowTrack?.let { track ->
            TrackOverflowSheet(
                track = track,
                onDismiss = { overflowTrack = null },
                onOpenGenres = { navController.navigate(Screens.MoodAndGenres.route) },
                onAddToPlaylist = {
                    // Capture the ref before dismissing the sheet — the
                    // AddToPlaylistDialog's onGetSong callback needs it.
                    val captured = track
                    overflowTrack = null
                    showAddToPlaylistTrack = captured
                    showAddToPlaylist = true
                },
                theme = theme,
            )
        }

        if (showAddToPlaylist && showAddToPlaylistTrack != null) {
            val track = showAddToPlaylistTrack!!
            AddToPlaylistDialog(
                isVisible = true,
                onGetSong = {
                    // AddToPlaylistDialog runs onGetSong on a background
                    // coroutine; searchYtForLastFmTrack already dispatches
                    // to IO internally. We return the YouTube videoId of the
                    // first SongItem match so the dialog can write it into
                    // the picked playlist's playlist_map / playlist_song
                    // join tables via the standard LocalDatabase path.
                    Timber.d("AddToPlaylist onGetSong for title=%s artist=%s", track.title, track.artist.orEmpty())
                    val song = searchYtForLastFmTrack(track.title, track.artist)
                    if (song == null) {
                        Timber.w("No YouTube match for Last.fm track (add-to-playlist): %s - %s", track.artist.orEmpty(), track.title)
                    }
                    listOfNotNull(song?.id)
                },
                onDismiss = { showAddToPlaylist = false; showAddToPlaylistTrack = null },
                onAddComplete = { _, _ ->
                    showAddToPlaylist = false
                    showAddToPlaylistTrack = null
                },
            )
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

/** Top app bar for the dashboard. */
@Composable
private fun LastFmDashboardHeader(
    searchVisible: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    theme: DashboardTheme,
    profileImageUrl: String?,
    onBack: () -> Unit,
    onBackLong: () -> Unit,
) {
    Surface(
        color = theme.topAppBarContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .consumeWindowInsets(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(
                onClick = onBack,
                onLongClick = onBackLong,
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_arrow_left_linear),
                    contentDescription = stringResource(R.string.back_button_desc),
                    tint = theme.topAppBarIconTint,
                )
            }
            // bar search animation. An instant `if (searchVisible) OutlinedTextField else Text`
            // swap reads as abrupt, and a bare OutlinedTextField looks unfinished — Material3's
            // SearchBar pill + AnimatedContent gives the same polished feel as the New Releases
            // page.
            //
            // Keep dashboard actions at the far trailing edge rather than
            // visually attaching them to the Last.fm wordmark.
            AnimatedContent(
                targetState = searchVisible,
                transitionSpec = {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                        slideInVertically(initialOffsetY = { fullHeight -> fullHeight / 8 }) togetherWith
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) +
                        slideOutVertically(targetOffsetY = { fullHeight -> fullHeight / 8 }))
                },
                label = "lastfm_header_search_swap",
            ) { searching ->
                if (searching) {
                    // Material3 SearchBar with the default pill shape.
                    // Leading icon = back arrow (dismiss search + clear
                    // query); trailing icon = close X (clear query only).
                    SearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                onSearch = { /* no-op — search is live-filtering */ },
                                expanded = false,
                                onExpandedChange = {},
                                placeholder = {
                                    Text(stringResource(R.string.lastfm_search_placeholder))
                                },
                                leadingIcon = {
                                    IconButton(onClick = onToggleSearch) {
                                        Icon(
                                            painter = painterResource(R.drawable.solar_arrow_left_linear),
                                            contentDescription = stringResource(R.string.back_button_desc),
                                            tint = theme.topAppBarIconTint,
                                        )
                                    }
                                },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { onSearchQueryChange("") }) {
                                            Icon(
                                                painter = painterResource(R.drawable.solar_close_circle_linear),
                                                contentDescription = stringResource(R.string.clear_search),
                                                tint = theme.topAppBarIconTint,
                                            )
                                        }
                                    }
                                } else null,
                            )
                        },
                        expanded = false,
                        onExpandedChange = {},
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    ) {}
                } else {
                    // Large "Last.fm" wordmark, matching the LastWave-native
                    // HomeScreen header. headlineMedium gives the prominent
                    // display size the reference screenshot uses (the previous
                    // titleLarge read as a regular app-bar title rather than
                    // the brand wordmark).
                    Text(
                        text = "Last.fm",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.topAppBarTitleText,
                        modifier = Modifier
                            .padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!searchVisible) Spacer(Modifier.weight(1f))
            // Search icon — toggles the inline scrobble-search field.
            IconButton(
                onClick = onToggleSearch,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = theme.topAppBarIconTint,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_magnifer_linear),
                    contentDescription = stringResource(R.string.search),
                    tint = theme.topAppBarIconTint,
                )
            }
            IconButton(
                // This is an account avatar, not a navigation control. The
                // profile remains available through the hero-card arrow.
                onClick = {},
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = theme.topAppBarIconTint,
                ),
            ) {
                if (!profileImageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = profileImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.solar_user_circle_linear),
                        contentDescription = null,
                        tint = theme.topAppBarIconTint,
                    )
                }
            }
        }
    }
}

// ── Hero stats card ──────────────────────────────────────────────────────────

/** Large rounded stats card replacing the previous flat UserCard. */
@Composable
private fun HeroStatsCard(
    userInfo: Result<UserInfo>?,
    isRefreshing: Boolean,
    trackCount: Int,
    artistCount: Int,
    albumCount: Int,
    onRetry: () -> Unit,
    onOpenProfile: () -> Unit,
    theme: DashboardTheme,
) {
    when {
        userInfo == null && isRefreshing -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = theme.accent) }
        }
        userInfo?.isSuccess == true -> {
            val info = userInfo.getOrNull()!!
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = theme.cardBackground,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = theme.statsHeroInner,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.align(Alignment.Center),
                                ) {
                                    Text(
                                        text = formatCount((info.playcount ?: 0).toLong()),
                                        style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.statsHeroNumberText,
                                    )
                                    Text(
                                        text = stringResource(R.string.lastfm_scrobbles),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = theme.statsHeroLabelText,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = theme.heroArrowCircleBackground,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .align(Alignment.CenterEnd),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        IconButton(onClick = onOpenProfile) {
                                            Icon(
                                                painter = painterResource(R.drawable.solar_forward_linear),
                                                contentDescription = stringResource(R.string.lastfm_open_in_lastfm),
                                                tint = theme.heroArrowIconTint,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatPill(
                                label = stringResource(R.string.lastfm_filter_top_tracks),
                                value = formatCount(trackCount.toLong()),
                                modifier = Modifier.weight(1f),
                                theme = theme,
                            )
                            StatPill(
                                label = stringResource(R.string.lastfm_filter_top_artists),
                                value = formatCount(artistCount.toLong()),
                                modifier = Modifier.weight(1f),
                                theme = theme,
                            )
                            StatPill(
                                label = stringResource(R.string.lastfm_filter_top_albums),
                                value = formatCount(albumCount.toLong()),
                                modifier = Modifier.weight(1f),
                                theme = theme,
                            )
                        }
                    }
                }
            }
        }
        else -> {
            // When user.getInfo fails, show nothing — the user will see
            // just the list below. The refresh button in the header can
            // be tapped to retry.
        }
    }
}

/**
 * Formats a count with thousands separators (e.g., 6328 → "6,328").
 * Ported from LastWave-native's formatCount helper.
 */
private fun formatCount(count: Long): String = "%,d".format(count)

/**
 * Small stat pill used in the hero stats card. Ported from LastWave-native's StatPill.
 */
@Composable
private fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    theme: DashboardTheme,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = theme.statsPillBackground,
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = theme.statsPillValueText,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = theme.statsPillLabelText,
            )
        }
    }
}

// ── Filter header ─────────────────────────────────────────────────────────────

/**
 * "List" title + a sort pill on the right that opens a [DropdownMenu] for
 * picking the filter mode (Recent / Top Tracks / Top Artists / Top Albums).
 * Ported from LastWave-native's MixHeader.
 */
@Composable
private fun FilterHeader(
    selectedFilter: LastFmFilter,
    onSelect: (LastFmFilter) -> Unit,
    theme: DashboardTheme,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.lastfm_list),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        Box {
            Surface(
                onClick = { menuOpen = true },
                shape = RoundedCornerShape(50),
                color = theme.filterPillBackground,
                tonalElevation = 1.dp,
                modifier = Modifier.heightIn(min = 34.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(iconForFilter(selectedFilter)),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = theme.filterPillIconTint,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = labelForFilter(selectedFilter),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.filterPillText,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = theme.filterPillIconTint,
                    )
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(24.dp),
                containerColor = theme.dropdownBackground,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                FilterOption(
                    iconRes = R.drawable.cached,
                    label = stringResource(R.string.lastfm_filter_recent),
                    active = selectedFilter == LastFmFilter.RECENT,
                    onClick = { onSelect(LastFmFilter.RECENT); menuOpen = false },
                    theme = theme,
                )
                FilterOption(
                    iconRes = R.drawable.solar_music_note_2_linear,
                    label = stringResource(R.string.lastfm_filter_top_tracks),
                    active = selectedFilter == LastFmFilter.TOP_TRACKS,
                    onClick = { onSelect(LastFmFilter.TOP_TRACKS); menuOpen = false },
                    theme = theme,
                )
                FilterOption(
                    iconRes = R.drawable.solar_users_group_rounded_linear,
                    label = stringResource(R.string.lastfm_filter_top_artists),
                    active = selectedFilter == LastFmFilter.TOP_ARTISTS,
                    onClick = { onSelect(LastFmFilter.TOP_ARTISTS); menuOpen = false },
                    theme = theme,
                )
                FilterOption(
                    iconRes = R.drawable.solar_playlist_linear,
                    label = stringResource(R.string.lastfm_filter_top_albums),
                    active = selectedFilter == LastFmFilter.TOP_ALBUMS,
                    onClick = { onSelect(LastFmFilter.TOP_ALBUMS); menuOpen = false },
                    theme = theme,
                )
            }
        }
    }
}

private fun iconForFilter(filter: LastFmFilter): Int = when (filter) {
    LastFmFilter.RECENT -> R.drawable.cached
    LastFmFilter.TOP_TRACKS -> R.drawable.solar_music_note_2_linear
    LastFmFilter.TOP_ARTISTS -> R.drawable.solar_users_group_rounded_linear
    LastFmFilter.TOP_ALBUMS -> R.drawable.solar_playlist_linear
}

@Composable
private fun labelForFilter(filter: LastFmFilter) = when (filter) {
    LastFmFilter.RECENT -> stringResource(R.string.lastfm_filter_recent)
    LastFmFilter.TOP_TRACKS -> stringResource(R.string.lastfm_filter_top_tracks)
    LastFmFilter.TOP_ARTISTS -> stringResource(R.string.lastfm_filter_top_artists)
    LastFmFilter.TOP_ALBUMS -> stringResource(R.string.lastfm_filter_top_albums)
}

@Composable
private fun FilterOption(
    @androidx.annotation.DrawableRes iconRes: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    theme: DashboardTheme,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) theme.dropdownActiveItemText else theme.dropdownInactiveItemText,
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (active) theme.dropdownActiveItemIconTint else theme.dropdownInactiveItemIconTint,
            )
        },
        trailingIcon = {
            if (active) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = theme.dropdownCheckTint,
                )
            }
        },
        onClick = onClick,
        modifier = if (active) {
            Modifier
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(theme.dropdownActiveItemBackground)
        } else {
            Modifier.padding(horizontal = 6.dp)
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    )
}

// ── Track / artist / album rows ───────────────────────────────────────────────

/**
 * Track row ported from LastWave-native's TrackRow (HomeScreen.kt lines 820-934). Differences: -
 * Album artwork comes from [bestArtwork] / [resolveCatalogueCover] instead of LastWave-native's
 * ArtworkImage (the catalog/cover resolution pipeline is what the existing ArchiveTune dashboard
 * already had).
 */
@Composable
private fun DashboardTrackRow(
    track: LastFmTrackRef,
    rank: Int? = null,
    fallbackArtworkUrl: String? = null,
    onOverflow: () -> Unit,
    theme: DashboardTheme,
) {
    // Per-row artwork resolution. Seed from the Last.fm image array via [bestArtwork], the
    // parent's pre-resolved map, then the process-wide [CachedArtworkStore]. Only if all three miss
    // do we kick off a YouTube search in a `LaunchedEffect(track.artworkKey())`, which runs only
    // when this row is actually composed (i.e. visible / about to be visible on the LazyColumn) —
    // an upfront batch would fire 50+ parallel YT searches the moment the dashboard opens.
    // Resolved URLs are written back to the [CachedArtworkStore] so re-composition (filter switch,
    // scroll back) doesn't re-resolve.
    val artworkKey = track.artworkKey()
    val database = LocalDatabase.current
    // When the user has enabled "Prefer YouTube thumbnails" in settings,
    // skip the Last.fm image array entirely (it can return non-square /
    // brown-matted images from Last.fm's catalogue) and go straight to
    // the catalogue resolver, which starts with YouTube hq720 (clean
    // 16:9, no baked-in bars). When disabled (default), use the original
    // chain: Last.fm image array → parent fallback → cache → resolve.
    val preferYtThumbnails by rememberPreference(LastFmPreferYtThumbnailsKey, defaultValue = false)
    var resolvedArtworkUrl by remember(artworkKey, preferYtThumbnails) {
        mutableStateOf(
            if (preferYtThumbnails) {
                CachedArtworkStore.get(artworkKey)
            } else {
                bestArtwork(track.image)
                    ?: fallbackArtworkUrl
                    ?: CachedArtworkStore.get(artworkKey)
            },
        )
    }
    LaunchedEffect(artworkKey, resolvedArtworkUrl) {
        if (!resolvedArtworkUrl.isNullOrBlank()) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) {
            resolveCatalogueCover(ArtworkLookup(artworkKey, track.title, track.artist), database)
        }
        if (!url.isNullOrBlank()) {
            CachedArtworkStore.put(artworkKey, url)
            resolvedArtworkUrl = url
        }
    }
    val artworkUrl = resolvedArtworkUrl
    val isNowPlaying = track.isNowPlaying
    val trackTitleColor = if (isNowPlaying) theme.nowPlayingTrackTitle else theme.textPrimary
    val trackArtistColor = if (isNowPlaying) theme.nowPlayingTrackArtist else theme.textSecondary
    Surface(
        shape = if (isNowPlaying) RoundedCornerShape(22.dp) else RoundedCornerShape(18.dp),
        color = if (isNowPlaying) theme.nowPlayingRowBackground else Color.Transparent,
        tonalElevation = if (isNowPlaying) 1.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.artworkPlaceholderBackground),
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.solar_music_note_2_linear),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = theme.artworkPlaceholderTint,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (rank != null) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = theme.rankingBadgeBackground,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = rank.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = theme.rankingBadgeText,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = trackTitleColor,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = trackArtistColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isNowPlaying) {
                val infiniteTransition = rememberInfiniteTransition(label = "lastfm_now_playing_pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "lastfm_pulse_scale",
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = theme.nowPlayingPillBackground,
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(theme.nowPlayingDotColor, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.lastfm_now_playing),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = theme.nowPlayingPillText,
                        )
                    }
                }
            } else if (track.playCount != null && track.playCount > 1) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = theme.playCountPillBackground,
                ) {
                    Text(
                        text = "×${track.playCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.playCountPillText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            IconButton(onClick = onOverflow) {
                Icon(
                    painter = painterResource(R.drawable.solar_more_circle_linear),
                    contentDescription = "More",
                    tint = theme.overflowIconTint,
                )
            }
        }
    }
}

/**
 * Simplified row for the Top Artists filter — name + playcount badge, no track-specific actions (no
 * overflow menu, since the bottom sheet's actions are all track-focused: Start Mix / Play / Add to
 * queue all need a YouTube song id that an artist row can't supply).
 */
@Composable
private fun DashboardArtistRow(
    name: String,
    playCount: Int?,
    rank: Int,
    artworkUrl: String?,
    theme: DashboardTheme,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(theme.artworkPlaceholderBackground),
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.solar_user_circle_linear),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = theme.artworkPlaceholderTint,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = theme.rankingBadgeBackground,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = rank.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = theme.rankingBadgeText,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = playCount?.let { stringResource(R.string.lastfm_playcount, it) }
                        ?: stringResource(R.string.lastfm_filter_top_artists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (playCount != null && playCount > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = theme.playCountPillBackground,
                ) {
                    Text(
                        text = "×$playCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.playCountPillText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Simplified row for the Top Albums filter — album name + artist + playcount
 * badge. Like the artist row, no overflow menu (the sheet is track-focused).
 */
@Composable
private fun DashboardAlbumRow(
    title: String,
    artist: String?,
    playCount: Int?,
    rank: Int,
    artworkUrl: String?,
    theme: DashboardTheme,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.artworkPlaceholderBackground),
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.solar_music_note_2_linear),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = theme.artworkPlaceholderTint,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = theme.rankingBadgeBackground,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = rank.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = theme.rankingBadgeText,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (playCount != null && playCount > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = theme.playCountPillBackground,
                ) {
                    Text(
                        text = "×$playCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.playCountPillText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

// ── Track overflow bottom sheet ───────────────────────────────────────────────

/**
 * ModalBottomSheet shown when the user taps the three-dot overflow button on a [DashboardTrackRow].
 * Renders, top to bottom: 1. "Start Mix with this Song" banner (solar_forward_linear icon,
 * primaryContainer bg) → searches YouTube for a matching song and seeds a radio queue 2.
 */
@Composable
private fun TrackOverflowSheet(
    track: LastFmTrackRef,
    onDismiss: () -> Unit,
    onOpenGenres: () -> Unit,
    onAddToPlaylist: () -> Unit,
    theme: DashboardTheme,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    var loadingAction by remember { mutableStateOf<String?>(null) }
    var genre by remember(track.artworkKey()) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.title, track.artist) {
        genre = track.artist
            ?.takeIf { it.isNotBlank() }
            ?.let { artist ->
                withContext(Dispatchers.IO) {
                    LastFM.getTrackInfo(artist = artist, track = track.title)
                        .getOrNull()
                        ?.toptags
                        ?.tag
                        ?.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
                        ?.take(3)
                        ?.joinToString(", ")
                }
            }
    }

    // Pre-resolve the YT search once on sheet open so the banner can show the YouTube
    // thumbnail when Last.fm has no artwork, AND so the user-facing play / queue / mix
    // actions are instant (cache hit). The resolved SongItem (or null, if no match) is
    // also cached in [ytSearchCache] so subsequent taps reuse it without re-searching.
    // Falls back to Last.fm's own image array via [bestArtwork] first — for most
    // scrobbles Last.fm has artwork, so the YT search never even has to run for the
    // banner.
    var bannerArtworkUrl by remember(track.artworkKey()) {
        mutableStateOf(bestArtwork(track.image))
    }
    LaunchedEffect(track.artworkKey()) {
        if (!bannerArtworkUrl.isNullOrBlank()) return@LaunchedEffect
        // Kick off a background YT search to (1) populate the banner image
        // and (2) prime the cache so the user's first action is instant.
        val song = withContext(Dispatchers.IO) {
            searchYtForLastFmTrack(track.title, track.artist)
        }
        if (!song?.thumbnail.isNullOrBlank()) {
            bannerArtworkUrl = song!!.thumbnail
        }
    }

    // Wraps each YT-resolving action with: leading CircularProgressIndicator while the search
    // is in flight (via [loadingAction] state), toast on null match, toast on null
    // [playerConnection], and Timber logging so failures don't disappear silently. Calling
    // `playerConnection?.playNext(...)` directly no-ops when the player service isn't bound,
    // so the user taps and sees nothing happen; this surfaces it as a toast.
    fun runWithYtSearch(action: String, onFound: (SongItem) -> Unit) {
        if (loadingAction != null) return
        if (playerConnection == null) {
            Timber.w("playerConnection is null in TrackOverflowSheet action=%s", action)
            Toast.makeText(
                context,
                context.getString(R.string.lastfm_player_unavailable),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        scope.launch {
            loadingAction = action
            try {
                val song = withContext(Dispatchers.IO) {
                    searchYtForLastFmTrack(track.title, track.artist)
                }
                if (song == null) {
                    Timber.w("No YT match for Last.fm track action=%s title=%s artist=%s", action, track.title, track.artist.orEmpty())
                    Toast.makeText(
                        context,
                        context.getString(R.string.lastfm_no_yt_match),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Timber.d("YT action %s resolved to videoId=%s", action, song.id)
                    onFound(song)
                }
            } catch (t: Throwable) {
                Timber.e(t, "YT action %s threw", action)
                Toast.makeText(
                    context,
                    context.getString(R.string.lastfm_no_yt_match),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                loadingAction = null
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.cardBackground,
        contentColor = theme.textPrimary,
    ) {
        // ── Start Mix banner ─────────────────────────────────────────────
        // The banner is intentionally a clickable Surface (not a ListItem) so it reads as a
        // primary CTA — same visual hierarchy as LastWave-native's hero "Start radio" banner.
        //
        // The 48dp circular slot shows the track's album artwork: Last.fm image array via
        // [bestArtwork] → YouTube thumbnail from the background-resolved SongItem → accent-colour
        // fallback with solar_forward_linear icon when no artwork is available at all. The
        // CircularProgressIndicator still renders on top while the search is in flight.
        Surface(
            onClick = {
                runWithYtSearch("mix") { song ->
                    playerConnection?.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                }
            },
            shape = RoundedCornerShape(20.dp),
            color = theme.statsHeroInner,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = theme.accent,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!bannerArtworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = bannerArtworkUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        if (loadingAction == "mix") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = theme.nowPlayingPillText,
                                strokeWidth = 2.dp,
                            )
                        } else if (bannerArtworkUrl.isNullOrBlank()) {
                            Icon(
                                painter = painterResource(R.drawable.solar_forward_linear),
                                contentDescription = null,
                                tint = theme.nowPlayingPillText,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lastfm_start_mix),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.statsHeroNumberText,
                    )
                    Text(
                        text = listOfNotNull(track.artist, track.title).joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.statsHeroLabelText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 2. Genre ────────────────────────────────────────────────────
        ListItem(
            headlineContent = {
                Text(
                    stringResource(R.string.lastfm_genre),
                    color = theme.textPrimary,
                )
            },
            supportingContent = {
                Text(
                    genre ?: stringResource(R.string.lastfm_unknown_genre),
                    color = theme.textSecondary,
                )
            },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.solar_server_linear),
                    contentDescription = null,
                    tint = theme.textSecondary,
                )
            },
            modifier = Modifier.clickable {
                onDismiss()
                onOpenGenres()
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        HorizontalDivider(color = theme.dividerColor)

        // ── 3. Play in ArchiveTune ─────────────────────────────────────
        OverflowActionItem(
            label = stringResource(R.string.lastfm_play_in_archivetune),
            iconRes = R.drawable.solar_play_linear,
            loading = loadingAction == "play",
            enabled = loadingAction == null,
            onClick = {
                runWithYtSearch("play") { song ->
                    playerConnection?.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                }
            },
            theme = theme,
        )

        // ── 4. Play next ───────────────────────────────────────────────
        OverflowActionItem(
            label = stringResource(R.string.lastfm_play_next),
            iconRes = R.drawable.solar_skip_next_linear,
            loading = loadingAction == "playNext",
            enabled = loadingAction == null,
            onClick = {
                runWithYtSearch("playNext") { song ->
                    playerConnection?.playNext(song.toMediaItem())
                }
            },
            theme = theme,
        )

        // ── 5. Add to queue ─────────────────────────────────────────────
        OverflowActionItem(
            label = stringResource(R.string.lastfm_add_to_queue),
            iconRes = R.drawable.solar_playlist_linear,
            loading = loadingAction == "addToQueue",
            enabled = loadingAction == null,
            onClick = {
                runWithYtSearch("addToQueue") { song ->
                    playerConnection?.addToQueue(song.toMediaItem())
                }
            },
            theme = theme,
        )

        // ── 6. Add to playlist ─────────────────────────────────────────
        OverflowActionItem(
            label = stringResource(R.string.lastfm_add_to_playlist),
            iconRes = R.drawable.solar_add_circle_linear,
            loading = false,
            enabled = loadingAction == null,
            onClick = {
                onDismiss()
                onAddToPlaylist()
            },
            theme = theme,
        )

        HorizontalDivider(color = theme.dividerColor)

        // ── 7. Open in Last.fm ─────────────────────────────────────────
        val lastFmUrl = track.url?.takeIf(String::isNotBlank)
        OverflowActionItem(
            label = stringResource(R.string.lastfm_open_in_lastfm),
            iconRes = R.drawable.solar_send_square_linear,
            loading = false,
            enabled = loadingAction == null && !lastFmUrl.isNullOrBlank(),
            onClick = {
                onDismiss()
                lastFmUrl?.let {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
            theme = theme,
        )

        // ── 8. Copy Song ───────────────────────────────────────────────
        OverflowActionItem(
            label = stringResource(R.string.lastfm_copy_song),
            iconRes = R.drawable.copy,
            loading = false,
            enabled = loadingAction == null,
            onClick = {
                val label = listOfNotNull(track.artist, track.title)
                    .joinToString(" - ")
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Last.fm Track", label))
                Toast.makeText(
                    context,
                    context.getString(R.string.lastfm_song_copied),
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            },
            theme = theme,
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OverflowActionItem(
    label: String,
    @androidx.annotation.DrawableRes iconRes: Int,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    theme: DashboardTheme,
) {
    ListItem(
        headlineContent = {
            Text(
                label,
                color = if (enabled) theme.textPrimary else theme.textSecondary.copy(alpha = 0.4f),
            )
        },
        leadingContent = {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = theme.accent,
                )
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (enabled) theme.textPrimary else theme.textSecondary.copy(alpha = 0.4f),
                )
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            disabledContentColor = theme.textSecondary.copy(alpha = 0.4f),
        ),
    )
}

// ── Sign-in / empty states ────────────────────────────────────────────────────

@Composable
private fun NotSignedIn(
    onSignIn: () -> Unit,
    theme: DashboardTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = theme.signInAvatarBackground,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.solar_music_note_2_linear),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = theme.signInAvatarTint,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.lastfm_sign_in_required),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = theme.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.lastfm_sign_in_required_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = onSignIn,
            shape = RoundedCornerShape(50),
            color = theme.signInButtonContainer,
        ) {
            Box(
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lastfm_sign_in_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = theme.signInButtonText,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, theme: DashboardTheme) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = theme.emptyHintText,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

// ── Artwork resolution (kept from previous implementation) ────────────────────

private fun bestArtwork(images: List<UserImage>?): String? =
    LastFmArtworkNormalizer.bestImageUrl(images)

private fun RecentTrack.trackArtworkKey(): String = "${name.orEmpty().trim().lowercase()}::${artist?.text.orEmpty().trim().lowercase()}"

private fun TopTrack.trackArtworkKey(): String = "${name.orEmpty().trim().lowercase()}::${artist?.text.orEmpty().trim().lowercase()}"

private fun List<RecentTrackWithCount>.associateArtworkByTrack(): Map<String, String> =
    mapNotNull { entry -> bestArtwork(entry.track.image)?.let { entry.track.trackArtworkKey() to it } }.toMap()

private data class ArtworkLookup(
    val key: String,
    val title: String,
    val artist: String?,
)

private object CachedArtworkStore {
    private const val MAX_ENTRIES = 256
    private val map = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(key: String): String? = map[key]

    @Synchronized
    fun put(key: String, url: String) {
        map[key] = url
    }
}

/**
 * Library covers for dashboard rows. A title lookup scans the song table (see
 * [MusicDatabase.libraryCoverCandidates]), so the titles of rows that resolve together are folded
 * into one query, and every answer, misses included, is kept for the process: scrolling back,
 * switching tabs or reopening the dashboard never scans for the same title again.
 */
private object LibraryCoverCache {
    private const val MAX_TITLES = 512
    private const val MAX_BATCH = 500

    private val lock = Mutex()
    private val pending = LinkedHashSet<String>()
    private val byTitle =
        object : LinkedHashMap<String, List<LibraryCoverCandidate>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LibraryCoverCandidate>>?): Boolean =
                size > MAX_TITLES
        }

    suspend fun coverFor(
        database: MusicDatabase,
        title: String,
        artist: String?,
    ): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val key = trimmed.lowercase()
        // Registered before waiting on the lock, so whichever row holds it next queries this
        // title along with its own.
        synchronized(pending) { pending.add(trimmed) }
        val candidates =
            lock.withLock {
                byTitle[key] ?: run {
                    val drained = synchronized(pending) { pending.toList().also { pending.clear() } }
                    val batch =
                        (drained + trimmed)
                            .distinctBy { it.lowercase() }
                            .filterNot { byTitle.containsKey(it.lowercase()) }
                    val found = HashMap<String, MutableList<LibraryCoverCandidate>>()
                    try {
                        for (chunk in batch.chunked(MAX_BATCH)) {
                            for (row in database.libraryCoverCandidates(chunk)) {
                                val rowTitle = row.title ?: continue
                                found.getOrPut(rowTitle.lowercase()) { ArrayList() }.add(row)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Library cover lookup failed")
                        return null
                    }
                    for (requested in batch) {
                        val requestedKey = requested.lowercase()
                        byTitle[requestedKey] = found[requestedKey].orEmpty()
                    }
                    found[key].orEmpty()
                }
            }
        // A scrobble's artist string joins every credited artist, so a stored artist is matched by
        // containment. The title alone can be shared by unrelated songs; the artist only decides
        // between them, it is not required to match.
        val scrobbleArtist = artist.orEmpty().lowercase()
        return candidates
            .filter { !it.thumbnailUrl.isNullOrBlank() }
            .minWithOrNull(
                compareBy<LibraryCoverCandidate> { candidate ->
                    val name = candidate.artistName
                    if (name != null && scrobbleArtist.contains(name.lowercase())) 0 else 1
                }.thenByDescending { it.inLibrary ?: Long.MIN_VALUE },
            )?.thumbnailUrl
    }
}

/**
 * Last.fm answers most of what ArchiveTune scrobbles with its grey placeholder, since it has no
 * catalogue entry for a YouTube upload. But the app scrobbled those plays itself, so the song —
 * and the cover it already shows everywhere else — is in the library: asking the database first
 * is instant, exact, and costs no network. The rest of the chain only runs for what it lacks.
 */
private suspend fun resolveCatalogueCover(
    lookup: ArtworkLookup,
    database: MusicDatabase? = null,
): String? {
    if (lookup.title.isBlank()) return null
    val title = lookup.title
    val artist = lookup.artist
    database
        ?.let { db -> LibraryCoverCache.coverFor(db, title, artist) }
        ?.let { return it }
    return resolveYtThumbnail(title, artist)
        ?: TelegramCoverProvider.coverUrl(title, artist)
        ?: CatalogueCoverProvider.resolveCoverUrl(title, artist)
}

/**
 * The artwork for a row whose own Last.fm image may be missing: [lastFmUrl] when it is real, else
 * what the process already resolved, else a lookup that runs only while the row is composed.
 */
@Composable
private fun rememberResolvedArtwork(
    lastFmUrl: String?,
    key: String,
    title: String,
    artist: String?,
): String? {
    val database = LocalDatabase.current
    var resolved by remember(key, lastFmUrl) { mutableStateOf(lastFmUrl ?: CachedArtworkStore.get(key)) }
    LaunchedEffect(key, lastFmUrl) {
        if (!resolved.isNullOrBlank()) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) { resolveCatalogueCover(ArtworkLookup(key, title, artist), database) }
        if (!url.isNullOrBlank()) {
            CachedArtworkStore.put(key, url)
            resolved = url
        }
    }
    return resolved
}

private suspend fun resolveYtThumbnail(title: String, artist: String?): String? {
    if (title.isBlank()) return null
    val term = listOfNotNull(artist?.takeIf(String::isNotBlank), title).joinToString(" ")

    val searchResult =
        YouTube.search(term, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ?: return null
    val first = findFirstSongItem(searchResult) ?: return null
    val videoId = first.id
    return if (videoId.length == 11) {
        // Use HQ720 (1280x720 16:9) instead of HQ (480x360 4:3).
        // YouTube's hqdefault.jpg has 45px black bars baked in top/bottom
        // (a 4:3 frame surrounding a 16:9 video), which causes the
        // "cropped thumbnail with black bars on top/bottom" symptom in
        // the dashboard. hq720 is a clean 16:9 image — ContentScale.Crop
        // then cleanly crops the sides without letterboxing.
        buildYTThumbnailUrl(videoId, YTThumbQuality.HQ720)
    } else {
        first.thumbnail.takeIf(String::isNotBlank)
    }
}

/** Extracts the first ArtistItem from a SearchResult's items list. */
private fun findFirstArtistItem(result: SearchResult): ArtistItem? {
    for (item in result.items) {
        if (item is ArtistItem) return item
    }
    return null
}

/**
 * Resolves an artist's profile image via a YouTube artist-channel search.
 * Used as a fallback when [LastFmArtworkNormalizer.bestImageUrl] returns
 * null (Last.fm's artist image array is sparse for less-popular artists —
 * the placeholder hash gets rejected by the normalizer, so we round-trip
 * through YouTube's FILTER_ARTIST search and pick the first ArtistItem's
 * thumbnail). Mirrors the [resolveYtThumbnail] pattern for tracks.
 */
private suspend fun resolveArtistImage(artistName: String): String? {
    if (artistName.isBlank()) return null
    val searchResult = YouTube.search(artistName, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
        ?: return null
    val firstArtist = findFirstArtistItem(searchResult)
    return firstArtist?.thumbnail?.takeIf(String::isNotBlank)
}

// ── View model ────────────────────────────────────────────────────────────────

@HiltViewModel
class LastFmDashboardViewModel
    @Inject
    constructor(
        val repository: LastFmSettingsRepository,
    ) : ViewModel()
