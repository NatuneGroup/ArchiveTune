/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenBrainzTokenKey
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.constants.SpotifyHistorySyncEnabledKey
import moe.rukamori.archivetune.constants.UsePoolAccountsKey
import moe.rukamori.archivetune.spotify.SpotifyAccountViewModel
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.menu.CrossServiceImportPlaylistDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.foundation.layout.asPaddingValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController,
    scrollTo: String? = null,
    spotifyAccountViewModel: SpotifyAccountViewModel = hiltViewModel(),
) {
    val (listenBrainzEnabled, onListenBrainzEnabledChange) = rememberPreference(ListenBrainzEnabledKey, false)
    val (listenBrainzToken, onListenBrainzTokenChange) = rememberPreference(ListenBrainzTokenKey, "")
    val (usePoolAccounts, onUsePoolAccountsChange) = rememberPreference(UsePoolAccountsKey, true)

    val spotifyState by spotifyAccountViewModel.uiState.collectAsStateWithLifecycle()
    val (showSpotifyPlaylists, onShowSpotifyPlaylistsChange) = rememberPreference(ShowSpotifyPlaylistsKey, false)
    val (syncSpotifyHistory, onSyncSpotifyHistoryChange) = rememberPreference(SpotifyHistorySyncEnabledKey, false)
    var showSpotifyLogin by rememberSaveable { mutableStateOf(false) }

    var showListenBrainzTokenEditor = remember { mutableStateOf(false) }
    var showCrossServiceImport by remember { mutableStateOf(false) }

    LaunchedEffect(spotifyState.isAuthenticated) {
        if (spotifyState.isAuthenticated) {
            showSpotifyLogin = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.integration)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .padding(top = topPadding)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            // AI integration sits here with the other integrations (Discord, Last.fm, Tidal,
            // Qobuz, Telegram, …).
            PreferenceGroup(
                modifier = positions.modifierFor("ai_integration"),
                title = stringResource(R.string.ai_integration),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.ai_integration)) },
                        description = stringResource(R.string.ai_integration_desc),
                        icon = { Icon(painterResource(R.drawable.ai), null) },
                        onClick = { navController.navigate("settings/ai_integration") },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("discord_presence"),
                title = stringResource(R.string.general),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("discord_account"),
                        title = { Text(stringResource(R.string.discord_integration)) },
                        icon = { Icon(painterResource(R.drawable.discord), null) },
                        onClick = {
                            navController.navigate("settings/discord")
                        },
                    )
                }
            }

            // "Music Sources" groups every external streaming source together: Tidal, Qobuz,
            // Deezer, Amazon, Apple Music, QQ Music and Telegram. Every sign-in row is always
            // shown (there is no manual-login gate), and the first switch decides whether the
            // community source pool's shared accounts are used alongside the user's own.
            PreferenceGroup(
                modifier = positions.modifierFor("music_sources"),
                title = stringResource(R.string.music_sources),
            ) {
                val poolConfigured = PoolAccountManager.isEnabled
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("use_pool_accounts"),
                        title = { Text(stringResource(R.string.use_pool_accounts)) },
                        description =
                            if (poolConfigured) {
                                stringResource(R.string.source_pool_enabled_description)
                            } else {
                                stringResource(R.string.source_pool_unavailable_description)
                            },
                        icon = { Icon(painterResource(R.drawable.group), null) },
                        checked = poolConfigured && usePoolAccounts,
                        onCheckedChange = {
                            if (poolConfigured) {
                                onUsePoolAccountsChange(it)
                                PoolAccountManager.setPoolAccountsEnabled(it)
                            }
                        },
                        isEnabled = poolConfigured,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("applemusic"),
                        title = { Text(stringResource(R.string.applemusic_settings)) },
                        description = stringResource(R.string.applemusic_helper),
                        icon = { Icon(painterResource(R.drawable.album), null) },
                        onClick = { navController.navigate("settings/applemusic") },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("tidal"),
                        title = { Text(stringResource(R.string.tidal_integration)) },
                        description = stringResource(R.string.tidal_integration_description),
                        icon = { Icon(painterResource(R.drawable.provider_tidal), null) },
                        onClick = {
                            navController.navigate("settings/tidal")
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("qobuz"),
                        title = { Text(stringResource(R.string.qobuz_integration)) },
                        description = stringResource(R.string.qobuz_integration_description),
                        icon = { Icon(painterResource(R.drawable.provider_qobuz), null) },
                        onClick = {
                            navController.navigate("settings/qobuz")
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("deezer"),
                        title = { Text(stringResource(R.string.deezer_integration)) },
                        description = stringResource(R.string.deezer_integration_description),
                        icon = { Icon(painterResource(R.drawable.provider_deezer), null) },
                        onClick = {
                            navController.navigate("settings/deezer")
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("amazon"),
                        title = { Text(stringResource(R.string.source_amazon)) },
                        description = stringResource(R.string.amazon_integration_description),
                        icon = { Icon(painterResource(R.drawable.login), null) },
                        onClick = {
                            navController.navigate("settings/amazon")
                        },
                    )
                }

                // QQ Music's settings screen is its only entry point: the source is not part of the
                // shipped order, so there is no row for it in the playback-sources list to reach it
                // from. Always visible, because the screen itself is where the partnership
                // requirement is explained.
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("qqmusic"),
                        title = { Text(stringResource(R.string.source_qq_music)) },
                        description = stringResource(R.string.qq_music_enabled_desc),
                        icon = { Icon(painterResource(R.drawable.ic_music), null) },
                        onClick = {
                            navController.navigate("settings/qqmusic")
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("telegram"),
                        title = { Text(stringResource(R.string.telegram_integration)) },
                        description = stringResource(R.string.telegram_integration_description),
                        icon = { Icon(painterResource(R.drawable.provider_telegram), null) },
                        onClick = {
                            navController.navigate("settings/telegram")
                        },
                    )
                }
            }

            // "External Sources" hosts Spotify — a read-only playlist import source, not a
            // playback source like Tidal/Qobuz/Deezer/Telegram above. Separating it from
            // "Music Sources" makes the distinction clear: Music Sources feed the player,
            // External Sources feed the Library (playlist sync, scrobbling, etc.).
            PreferenceGroup(
                // Also carries "spotify": Spotify is the only account in this group, so a search
                // hit on it scrolls here. Chaining is safe — modifierFor only records a position.
                modifier =
                    positions
                        .modifierFor("external_sources")
                        .then(positions.modifierFor("spotify")),
                title = stringResource(R.string.external_sources),
            ) {
                spotifyAccountPreferences(
                    state = spotifyState,
                    showPlaylists = showSpotifyPlaylists,
                    syncHistory = syncSpotifyHistory,
                    onConnectClick = { showSpotifyLogin = true },
                    onShowPlaylistsChange = onShowSpotifyPlaylistsChange,
                    onSyncHistoryChange = onSyncSpotifyHistoryChange,
                    onReloadClick = spotifyAccountViewModel::reloadPlaylists,
                    onLogoutClick = { spotifyAccountViewModel.logout() },
                )
            }

            PreferenceGroup(
                // Also carries the "lastfm_scrobbling" anchor: settings search offers a
                // "Last.fm scrobbling" result that scrolls here, so without this the result
                // would open this screen and then sit at the top.
                // Chaining is safe: modifierFor only registers a y position per key.
                modifier =
                    positions
                        .modifierFor("listenbrainz")
                        .then(positions.modifierFor("lastfm_scrobbling")),
                title = stringResource(R.string.scrobbling),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lastfm_account"),
                        title = { Text(stringResource(R.string.lastfm_integration)) },
                        icon = { Icon(painterResource(R.drawable.token), null) },
                        onClick = {
                            navController.navigate("settings/lastfm")
                        },
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.listenbrainz_scrobbling)) },
                        description = stringResource(R.string.listenbrainz_scrobbling_description),
                        icon = { Icon(painterResource(R.drawable.token), null) },
                        checked = listenBrainzEnabled,
                        onCheckedChange = onListenBrainzEnabledChange,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("listenbrainz_token"),
                        title = {
                            Text(
                                if (listenBrainzToken.isBlank()) {
                                    stringResource(
                                        R.string.set_listenbrainz_token,
                                    )
                                } else {
                                    stringResource(R.string.edit_listenbrainz_token)
                                },
                            )
                        },
                        icon = { Icon(painterResource(R.drawable.token), null) },
                        onClick = { showListenBrainzTokenEditor.value = true },
                    )
                }
            }

            // ─── Playlist import ──────────────────────────────────────────
            // Cross-service playlist import: paste a URL from YouTube Music,
            // Apple Music, Amazon Music, Tidal or Deezer and we'll resolve
            // the tracks against YouTube Music and build a local playlist.
            // Lives here (in Integration) per product decision so all
            // cross-service features are co-located.
            PreferenceGroup(
                modifier = positions.modifierFor("cross_service_import"),
                title = stringResource(R.string.cross_service_import_playlist_title),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.cross_service_import_entry_title)) },
                        description = stringResource(R.string.cross_service_import_entry_desc),
                        icon = { Icon(painterResource(R.drawable.playlist_import), null) },
                        onClick = { showCrossServiceImport = true },
                    )
                }
            }
        }
    }

    if (showListenBrainzTokenEditor.value) {
        TextFieldDialog(
            initialTextFieldValue =
                androidx.compose.ui.text.input
                    .TextFieldValue(listenBrainzToken),
            onDone = { data ->
                onListenBrainzTokenChange(data)
                showListenBrainzTokenEditor.value = false
            },
            onDismiss = { showListenBrainzTokenEditor.value = false },
            singleLine = true,
            maxLines = 1,
            // The dialog opens pre-filled with the stored token, so editing it put a working
            // ListenBrainz credential on screen in cleartext every time.
            masked = true,
            isInputValid = {
                it.isNotEmpty()
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.listenbrainz_scrobbling_description))
            },
        )
    }

    CrossServiceImportPlaylistDialog(
        isVisible = showCrossServiceImport,
        onDismiss = { showCrossServiceImport = false },
    )

    if (showSpotifyLogin) {
        SpotifyLoginSheet(
            onDismiss = { showSpotifyLogin = false },
            onCookiesCaptured = { spDc, spKey ->
                showSpotifyLogin = false
                spotifyAccountViewModel.connectWithCookies(spDc = spDc, spKey = spKey)
            },
        )
    }

    spotifyState.errorMessage?.let { error ->
        SpotifyErrorDialog(
            message = error,
            onDismiss = spotifyAccountViewModel::dismissError,
        )
    }
}
