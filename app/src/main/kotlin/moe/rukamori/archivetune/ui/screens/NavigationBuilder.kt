/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.HomeScreenStyle
import moe.rukamori.archivetune.constants.HomeScreenStyleKey
import moe.rukamori.archivetune.constants.HomeSource
import moe.rukamori.archivetune.constants.LibraryStyle
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.defaultUpdateChannel
import moe.rukamori.archivetune.musicrecognition.MusicRecognitionRoute
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.musicrecognition.MusicRecognitionDetailsRoute
import moe.rukamori.archivetune.ui.component.rememberLibraryStyle
import moe.rukamori.archivetune.ui.screens.BrowseScreen
import moe.rukamori.archivetune.ui.screens.artist.ArtistAlbumsScreen
import moe.rukamori.archivetune.ui.screens.artist.ArtistItemsScreen
import moe.rukamori.archivetune.ui.screens.artist.ArtistScreen
import moe.rukamori.archivetune.ui.screens.artist.ArtistSongsScreen
import moe.rukamori.archivetune.ui.screens.library.AppleMusicLibraryScreen
import moe.rukamori.archivetune.ui.screens.library.LibraryScreen
import moe.rukamori.archivetune.ui.screens.library.LocalSongScreen
import moe.rukamori.archivetune.ui.screens.musicrecognition.MusicRecognitionScreen
import moe.rukamori.archivetune.ui.screens.musicrecognition.MusicRecognitionDetailsScreen
import moe.rukamori.archivetune.ui.screens.playlist.AutoPlaylistScreen
import moe.rukamori.archivetune.ui.screens.playlist.CachePlaylistScreen
import moe.rukamori.archivetune.ui.screens.playlist.LocalPlaylistScreen
import moe.rukamori.archivetune.ui.screens.playlist.OnlinePlaylistScreen
import moe.rukamori.archivetune.ui.screens.playlist.SpotifyPlaylistScreen
import moe.rukamori.archivetune.ui.screens.playlist.TopPlaylistScreen
import moe.rukamori.archivetune.ui.screens.podcast.PodcastRoute
import moe.rukamori.archivetune.ui.screens.podcast.PodcastScreen
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResult
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultArgument
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchProviderArgument
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultRoute
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultRoutePrefix
import moe.rukamori.archivetune.ui.screens.search.SearchScreen
import moe.rukamori.archivetune.ui.screens.settings.AboutScreen
import moe.rukamori.archivetune.ui.screens.settings.AddonSettings
import moe.rukamori.archivetune.ui.screens.settings.AccountSettings
import moe.rukamori.archivetune.ui.screens.settings.AiIntegrationSettings
import moe.rukamori.archivetune.ui.screens.settings.AndroidAutoSettings
import moe.rukamori.archivetune.ui.screens.settings.AodCustomizedScreen
import moe.rukamori.archivetune.ui.screens.settings.AppearanceExtrasSettings
import moe.rukamori.archivetune.ui.screens.settings.NavigationBarSettings
import moe.rukamori.archivetune.ui.screens.settings.AppearanceSection
import moe.rukamori.archivetune.ui.screens.settings.AppearanceSectionSettings
import moe.rukamori.archivetune.ui.screens.settings.AppearanceSettings
import moe.rukamori.archivetune.ui.screens.settings.BackupAndRestore
import moe.rukamori.archivetune.ui.screens.settings.CanvasSettings
import moe.rukamori.archivetune.ui.screens.settings.ChangelogScreen
import moe.rukamori.archivetune.ui.screens.settings.ContentSettings
import moe.rukamori.archivetune.ui.screens.settings.CustomizeBackground
import moe.rukamori.archivetune.ui.screens.settings.DebugSettings
import moe.rukamori.archivetune.ui.screens.settings.DiscordSettings
import moe.rukamori.archivetune.ui.screens.settings.ExportDownloadedSongsScreen
import moe.rukamori.archivetune.ui.screens.settings.HiddenPlaylistsScreen
import moe.rukamori.archivetune.ui.screens.settings.IconScreen
import moe.rukamori.archivetune.ui.screens.settings.IntegrationScreen
import moe.rukamori.archivetune.ui.screens.settings.ListenTogetherSettings
import moe.rukamori.archivetune.ui.screens.settings.InternetSettings
import moe.rukamori.archivetune.ui.screens.settings.TidalSettings
import moe.rukamori.archivetune.ui.screens.settings.QobuzSettings
import moe.rukamori.archivetune.ui.screens.settings.AmazonSettings
import moe.rukamori.archivetune.ui.screens.settings.QqMusicSettings
import moe.rukamori.archivetune.ui.screens.settings.DeezerSettings
import moe.rukamori.archivetune.ui.screens.settings.JioSettings
import moe.rukamori.archivetune.ui.screens.settings.TidalLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.TIDAL_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.QobuzLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.QOBUZ_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.AppleMusicLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.AppleMusicSettings
import moe.rukamori.archivetune.ui.screens.settings.APPLE_MUSIC_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.AppleMusicLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.AppleMusicSettings
import moe.rukamori.archivetune.ui.screens.settings.DeezerLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.DEEZER_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.LASTFM_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.LastFmLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.LASTFM_LIBREFM_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.LibreFmLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.TELEGRAM_LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.TelegramLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.YOUTUBE_OAUTH_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.YouTubeOAuthLoginScreen
import moe.rukamori.archivetune.ui.screens.settings.TelegramSettings
import moe.rukamori.archivetune.ui.screens.settings.LastFMSettings
import moe.rukamori.archivetune.ui.screens.settings.LastFmDashboardScreen
import moe.rukamori.archivetune.ui.screens.settings.LanguagePackSettings
import moe.rukamori.archivetune.ui.screens.settings.LogcatScreen
import moe.rukamori.archivetune.ui.screens.settings.LyricsAnimationSettings
import moe.rukamori.archivetune.ui.screens.settings.LyricsSettings
import moe.rukamori.archivetune.ui.screens.settings.LyricsProvidersSettings
import moe.rukamori.archivetune.ui.screens.settings.LyricsRomanisationSettings
import moe.rukamori.archivetune.ui.screens.settings.PO_TOKEN_ROUTE
import moe.rukamori.archivetune.ui.screens.settings.PalettePickerScreen
import moe.rukamori.archivetune.ui.screens.settings.PlayerSettings
import moe.rukamori.archivetune.ui.screens.settings.PoTokenScreen
import moe.rukamori.archivetune.ui.screens.settings.PrivacySettings
import moe.rukamori.archivetune.ui.screens.settings.SettingsScreen
import moe.rukamori.archivetune.ui.screens.settings.SourceSettings
import moe.rukamori.archivetune.ui.screens.settings.StorageSettings
import moe.rukamori.archivetune.ui.screens.settings.DownloadsSettings
import moe.rukamori.archivetune.ui.screens.settings.ThemeCreatorScreen
import moe.rukamori.archivetune.ui.screens.settings.UpdateScreen
import moe.rukamori.archivetune.viewmodels.OnlineSearchSort

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: () -> String,
    disableAnimations: Boolean = false,
    onClearUpdateBadge: () -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onVoiceSearch: () -> Unit = {},
    homeListState: LazyListState? = null,
    searchListState: LazyListState? = null,
    homeScrollConnection: NestedScrollConnection? = null,
    searchScrollConnection: NestedScrollConnection? = null,
    onlineSearchSort: OnlineSearchSort = OnlineSearchSort.DEFAULT,
) {
    composable(Screens.Home.route) {
        // Three separate home pages behind one tab, picked by HomeSourceKey and switched from the
        // HomeSourceToggleButton in the top app bar. They are not layered: whichever is showing
        // owns the tab, keeps its own layout style, and leaves the others exactly as they were.
        // rememberHomeSource already resolves a page whose session has gone back to YOUTUBE.
        when (rememberHomeSource()) {
            HomeSource.SPOTIFY -> {
                SpotifyHomeScreen(navController, headerScrollConnection = homeScrollConnection)
                return@composable
            }

            HomeSource.QQ -> {
                // No navController: the QQ page plays its sections rather than opening anything.
                QqHomeScreen(headerScrollConnection = homeScrollConnection)
                return@composable
            }

            // The app's own home, in whichever of its two styles is configured.
            HomeSource.YOUTUBE -> Unit
        }

        val homeScreenStyle by rememberEnumPreference(HomeScreenStyleKey, HomeScreenStyle.Default)
        when (homeScreenStyle) {
            HomeScreenStyle.RUKAMORI -> {
                RukamoriHomeScreen(
                    navController,
                    headerScrollConnection = homeScrollConnection,
                    listState = homeListState,
                )
            }

            HomeScreenStyle.DEFAULT -> {
                HomeScreen(
                    navController,
                    headerScrollConnection = homeScrollConnection,
                    listState = homeListState,
                )
            }
        }
    }
    composable(
        Screens.Library.route,
    ) {
        // The Apple Music Experience's library half: a large-title list of sections instead of the
        // fork's chip row. Dispatched here rather than inside LibraryScreen so the two layouts stay
        // separate screens, each owning its own scroll state, insets and section state.
        if (rememberLibraryStyle().first == LibraryStyle.APPLE_MUSIC) {
            AppleMusicLibraryScreen(navController)
        } else {
            LibraryScreen(navController)
        }
    }
    composable("listen_together") {
        ListenTogetherScreen(navController, showTopBar = false)
    }
    composable(
        route = "listen_together_from_topbar",
    ) {
        ListenTogetherScreen(navController, showTopBar = true)
    }
    composable("listen_together/chat") {
        CommentTogetherScreen(navController)
    }
    composable(Screens.Search.route) {
        SearchScreen(
            navController = navController,
            onSearchQuery = onSearchQuery,
            onVoiceSearch = onVoiceSearch,
            headerScrollConnection = searchScrollConnection,
            listState = searchListState,
        )
    }
    composable("local_songs") {
        LocalSongScreen(navController)
    }
    composable("history") {
        HistoryScreen(navController)
    }
    composable("stats") {
        StatsScreen(navController)
    }
    composable("news") {
        NewsScreen(navController)
    }
    composable(
        route = "view_news/{newsId}",
        arguments =
            listOf(
                navArgument("newsId") { type = NavType.StringType },
            ),
    ) {
        ViewNewsScreen(navController)
    }
    composable(
        route = "year_in_music?year={year}",
        arguments =
            listOf(
                navArgument("year") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
    ) { backStackEntry ->
        val selectedYear = backStackEntry.arguments?.getInt("year")?.takeIf { it > 0 }
        YearInMusicScreen(
            navController = navController,
            initialYear = selectedYear,
        )
    }
    composable(MusicRecognitionRoute) {
        MusicRecognitionScreen(navController)
    }
    composable(MusicRecognitionDetailsRoute) { backStackEntry ->
        val encodedTrack = backStackEntry.arguments?.getString("encodedTrack").orEmpty()
        MusicRecognitionDetailsScreen(navController, encodedTrack)
    }
    composable(Screens.MoodAndGenres.route) {
        MoodAndGenresScreen(navController)
    }
    composable("account") {
        AccountScreen(navController, scrollBehavior)
    }
    composable("new_release") {
        NewReleaseScreen(navController, scrollBehavior)
    }
    composable("charts_screen") {
        ChartsScreen(navController)
    }
    composable(
        route = "browse/{browseId}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                },
            ),
    ) {
        BrowseScreen(
            navController,
            scrollBehavior,
        )
    }
    composable(
        route = OnlineSearchResultRoute,
        arguments =
            listOf(
                navArgument(OnlineSearchResultArgument) {
                    type = NavType.StringType
                },
                navArgument(OnlineSearchProviderArgument) {
                    type = NavType.StringType
                    defaultValue = moe.rukamori.archivetune.constants.SearchProvider.YOUTUBE.name
                },
            ),
        enterTransition = {
            if (disableAnimations) {
                fadeIn(tween(0))
            } else {
                fadeIn(tween(250))
            }
        },
        exitTransition = {
            if (disableAnimations) {
                fadeOut(tween(0))
            } else if (targetState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                fadeOut(tween(200))
            } else {
                fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
            }
        },
        popEnterTransition = {
            if (disableAnimations) {
                fadeIn(tween(0))
            } else if (initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                fadeIn(tween(250))
            } else {
                fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
            }
        },
        popExitTransition = {
            if (disableAnimations) {
                fadeOut(tween(0))
            } else {
                fadeOut(tween(200))
            }
        },
    ) {
        OnlineSearchResult(
            navController = navController,
            searchSort = onlineSearchSort,
        )
    }
    composable(
        route = "album/{albumId}",
        arguments =
            listOf(
                navArgument("albumId") {
                    type = NavType.StringType
                },
            ),
    ) {
        AlbumScreen(navController, scrollBehavior)
    }
    composable(
        route = PodcastRoute,
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                },
            ),
    ) {
        PodcastScreen(navController)
    }
    composable(
        route = "artist/{artistId}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/songs",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistSongsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/albums",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistAlbumsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/items?browseId={browseId}&params={params}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        ArtistItemsScreen(navController, scrollBehavior)
    }
    composable(
        route = "online_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "local_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        LocalPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "spotify_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        SpotifyPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "auto_playlist/{playlist}?tab={tab}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
                navArgument("tab") {
                    type = NavType.StringType
                    defaultValue = "downloaded"
                },
            ),
    ) {
        AutoPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        CachePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "top_playlist/{top}",
        arguments =
            listOf(
                navArgument("top") {
                    type = NavType.StringType
                },
            ),
    ) {
        TopPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        YouTubeBrowseScreen(navController)
    }
    composable("settings") {
        SettingsScreen(navController, latestVersionName())
    }
    composable("settings/account") {
        AccountSettings(navController, latestVersionName())
    }
    composable("settings/hidden_playlists") {
        HiddenPlaylistsScreen(navController)
    }
    composable(
        route = "settings/appearance?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/theme?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSectionSettings(navController, AppearanceSection.THEME, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/player?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSectionSettings(navController, AppearanceSection.PLAYER, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/interface?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSectionSettings(navController, AppearanceSection.INTERFACE, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/extras?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceExtrasSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/navigation_bar?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        NavigationBarSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/appearance/icon") {
        IconScreen(navController)
    }
    composable(
        route = "settings/appearance/aod_customized?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AodCustomizedScreen(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/appearance/palette_picker") {
        PalettePickerScreen(navController)
    }
    composable(
        route = "settings/appearance/lyrics_animations?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsAnimationSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/appearance/theme_creator") {
        ThemeCreatorScreen(navController)
    }
    composable(
        route = "settings/content?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        ContentSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lyrics?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lyrics/providers?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsProvidersSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lyrics/romanisation?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsRomanisationSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/language_packs") {
        LanguagePackSettings(navController)
    }
    composable(
        route = "settings/internet?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        InternetSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/player?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        PlayerSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable("settings/canvas") {
        CanvasSettings(navController)
    }
    composable(
        route = "settings/sources?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        SourceSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable("settings/android_auto") {
        AndroidAutoSettings(navController)
    }
    composable(
        route = "settings/storage?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        StorageSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/storage/export_songs") {
        ExportDownloadedSongsScreen(navController)
    }
    composable(
        route = "settings/downloads?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DownloadsSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/privacy?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        PrivacySettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/backup_restore?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        BackupAndRestore(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/discord?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DiscordSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/integration?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        IntegrationScreen(navController, it.savedStateHandle["scrollTo"])
    }
    composable("settings/addons") {
        AddonSettings(navController)
    }
    composable(route = "settings/integrations/listen_together") {
        ListenTogetherSettings(navController, scrollBehavior)
    }
    composable(
        route = "settings/tidal?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        TidalSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/qobuz?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        QobuzSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/deezer?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DeezerSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/amazon?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AmazonSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/jiosaavn?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        JioSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/qqmusic?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        QqMusicSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(TIDAL_LOGIN_ROUTE) {
        TidalLoginScreen(navController)
    }
    composable(QOBUZ_LOGIN_ROUTE) {
        QobuzLoginScreen(navController)
    }
    composable(DEEZER_LOGIN_ROUTE) {
        DeezerLoginScreen(navController)
    }
    composable(APPLE_MUSIC_LOGIN_ROUTE) {
        AppleMusicLoginScreen(navController)
    }
    composable(
        route = "settings/applemusic",
    ) {
        AppleMusicSettings(navController)
    }
    composable(LASTFM_LOGIN_ROUTE) {
        LastFmLoginScreen(navController)
    }
    composable(LASTFM_LIBREFM_LOGIN_ROUTE) {
        LibreFmLoginScreen(navController)
    }
    composable(
        route = "settings/telegram?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        TelegramSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(TELEGRAM_LOGIN_ROUTE) {
        TelegramLoginScreen(navController)
    }
    composable(TELEGRAM_BROWSE_ROUTE) {
        TelegramBrowseScreen(navController)
    }
    composable(TELEGRAM_BOTS_ROUTE) {
        TelegramBotsScreen(navController)
    }
    composable(
        route = "$TELEGRAM_BOT_CHAT_ROUTE_BASE/{botId}",
        arguments = listOf(navArgument("botId") { type = NavType.StringType }),
    ) { entry ->
        TelegramBotChatScreen(
            botId = entry.arguments?.getString("botId").orEmpty(),
            navController = navController,
        )
    }
    composable(
        route = "settings/ai_integration?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AiIntegrationSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lastfm?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LastFMSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("lastfm_dashboard") {
        LastFmDashboardScreen(navController)
    }
    composable(
        route = "settings/discord/experimental?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        moe.rukamori.archivetune.ui.screens.settings
            .DiscordExperimental(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/misc") {
        DebugSettings(navController)
    }
    composable("settings/logcat") {
        LogcatScreen(navController)
    }
    if (BuildConfig.UPDATER_AVAILABLE) {
        composable("settings/update") {
            UpdateScreen(navController, onUpToDate = onClearUpdateBadge)
        }
    }
    composable(
        route = "settings/changelog?channel={channel}",
        arguments =
            listOf(
                navArgument("channel") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val channelName = backStackEntry.arguments?.getString("channel")
        val channel = UpdateChannel.fromStoredName(channelName, defaultUpdateChannel)
        ChangelogScreen(navController, channel = channel)
    }
    composable("settings/about") {
        AboutScreen(navController)
    }
    composable(PO_TOKEN_ROUTE) {
        PoTokenScreen(navController)
    }
    composable("customize_background") {
        CustomizeBackground(navController)
    }
    composable(
        route = "$LOGIN_ROUTE?$LOGIN_URL_ARGUMENT={$LOGIN_URL_ARGUMENT}",
        arguments =
            listOf(
                navArgument(LOGIN_URL_ARGUMENT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        LoginScreen(
            navController,
            startUrl = backStackEntry.arguments?.getString(LOGIN_URL_ARGUMENT)?.let(Uri::decode),
        )
    }
    // The other half of YouTube sign-in: no WebView, no cookie — a code typed at google.com/device.
    composable(YOUTUBE_OAUTH_ROUTE) {
        YouTubeOAuthLoginScreen(navController)
    }
}
