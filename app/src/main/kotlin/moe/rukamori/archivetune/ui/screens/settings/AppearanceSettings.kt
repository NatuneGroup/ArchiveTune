/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.constants.LibraryStyle
import moe.rukamori.archivetune.ui.component.rememberLibraryStyle
import moe.rukamori.archivetune.ui.component.rememberInterfaceStyle
import moe.rukamori.archivetune.constants.HideSearchChromeWhileScrollingKey
import moe.rukamori.archivetune.constants.InterfaceStyle
import moe.rukamori.archivetune.constants.SearchBarPosition
import moe.rukamori.archivetune.constants.SearchBarPositionKey
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppFontPreference
import moe.rukamori.archivetune.constants.AppleMusicAnimatedArtworkKey
import moe.rukamori.archivetune.constants.BackdropBlurAmountKey
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.BackdropEnabledKey
import moe.rukamori.archivetune.constants.BlurRadiusKey
import moe.rukamori.archivetune.constants.ChipSortTypeKey
import moe.rukamori.archivetune.constants.CropThumbnailToSquareKey
import moe.rukamori.archivetune.constants.CustomFontNameKey
import moe.rukamori.archivetune.constants.CustomFontUriKey
import moe.rukamori.archivetune.constants.DarkModeKey
import moe.rukamori.archivetune.constants.DefaultOpenTabKey
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.DynamicThemeKey
import moe.rukamori.archivetune.constants.FontPreferenceKey
import moe.rukamori.archivetune.constants.ForceHighRefreshRateKey
import moe.rukamori.archivetune.constants.HideStatusBarKey
import moe.rukamori.archivetune.constants.GridItemSize
import moe.rukamori.archivetune.constants.GridItemsSizeKey
import moe.rukamori.archivetune.constants.HidePlayerThumbnailKey
import moe.rukamori.archivetune.constants.HideScrollbarKey
import moe.rukamori.archivetune.constants.SplashOverlayEnabledKey
import moe.rukamori.archivetune.constants.DefaultLibraryFilterOrderPreference
import moe.rukamori.archivetune.constants.LibraryChipOrderKey
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.constants.PlaylistTagOrderKey
import moe.rukamori.archivetune.constants.toLibraryFilterOrder
import moe.rukamori.archivetune.constants.toPlaylistTagPreference
import moe.rukamori.archivetune.constants.toPlaylistTagOrder
import moe.rukamori.archivetune.constants.toLibraryFilterPreference
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.HomeScreenStyle
import moe.rukamori.archivetune.constants.HomeScreenStyleKey
import moe.rukamori.archivetune.constants.ActiveHomeSourcesKey
import moe.rukamori.archivetune.constants.HomeSource
import moe.rukamori.archivetune.ui.screens.HomeScreensDialog
import moe.rukamori.archivetune.ui.screens.labelResId
import moe.rukamori.archivetune.ui.screens.parseHomeSources
import moe.rukamori.archivetune.ui.screens.rememberActiveHomeSources
import moe.rukamori.archivetune.ui.screens.rememberHomeSourceAvailable
import moe.rukamori.archivetune.ui.screens.rememberQqHomeSourceAvailable
import moe.rukamori.archivetune.constants.MinimalHomeModeKey
import moe.rukamori.archivetune.constants.LyricsBackgroundStyle
import moe.rukamori.archivetune.constants.LyricsBackgroundStyleKey
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyle
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.CompactMiniPlayerKey
import moe.rukamori.archivetune.constants.CompactLayoutChoice
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.PlayerButtonsStyle
import moe.rukamori.archivetune.constants.PlayerButtonsStyleKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.PureBlackKey
import moe.rukamori.archivetune.constants.RandomThemeOnStartupKey
import moe.rukamori.archivetune.constants.ShowPlayerVolumeBarKey
import moe.rukamori.archivetune.constants.SliderStyle
import moe.rukamori.archivetune.constants.SliderStyleKey
import moe.rukamori.archivetune.constants.TabletModeEnabledKey
import moe.rukamori.archivetune.constants.ThumbnailCornerRadiusKey
import moe.rukamori.archivetune.constants.TikTokMainLyricsEnabledKey
import moe.rukamori.archivetune.constants.WallpaperExtractionFailedKey
import moe.rukamori.archivetune.constants.UiScaleFactorKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LibraryChipOrderDialog
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PlaylistTagOrderDialog
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.preferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.ThumbnailCornerRadiusSelectorButton
import moe.rukamori.archivetune.ui.player.StyledPlaybackSlider
import moe.rukamori.archivetune.ui.theme.CustomFontLoader
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.PlaylistTagUiModel
import moe.rukamori.archivetune.viewmodels.PlaylistTagsScreenState
import moe.rukamori.archivetune.viewmodels.PlaylistTagsViewModel
import moe.rukamori.archivetune.utils.isLowRamDevice
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.asPaddingValues

/** The three pages Appearance is split across. */
enum class AppearanceSection { THEME, PLAYER, INTERFACE }

/**
 * Appearance itself: three links, nothing else. Every row that used to be here lives on one of the
 * sub-pages, and the settings search index points at whichever one owns it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(navController: NavController, scrollTo: String? = null) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.appearance)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
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
        val positions = rememberPreferencePositions()
        val listState = rememberLazyListState()

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, listState) }

        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                ),
            modifier =
                Modifier
                    .padding(top = innerPadding.calculateTopPadding())
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                    .then(positions.containerModifier()),
        ) {
            preferenceGroup {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("appearance_theme"),
                        title = { Text(stringResource(R.string.appearance_theme)) },
                        description = stringResource(R.string.appearance_theme_desc),
                        icon = { Icon(painterResource(R.drawable.palette), null) },
                        onClick = { navController.navigate("settings/appearance/theme") },
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("appearance_player"),
                        title = { Text(stringResource(R.string.appearance_player)) },
                        description = stringResource(R.string.appearance_player_desc),
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        onClick = { navController.navigate("settings/appearance/player") },
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("appearance_interface"),
                        title = { Text(stringResource(R.string.appearance_interface)) },
                        description = stringResource(R.string.appearance_interface_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        onClick = { navController.navigate("settings/appearance/interface") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSectionSettings(
    navController: NavController,
    section: AppearanceSection,
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val defaultDisableAnimations = remember(context) { context.isLowRamDevice() }
    val (wallpaperExtractionFailed) =
        rememberPreference(WallpaperExtractionFailedKey, defaultValue = false)
    val (dynamicTheme, onDynamicThemeChange) =
        rememberPreference(
            DynamicThemeKey,
            defaultValue = true,
        )
    val (randomThemeOnStartup, onRandomThemeOnStartupChange) =
        rememberPreference(
            RandomThemeOnStartupKey,
            defaultValue = false,
        )
    val (darkMode, onDarkModeChange) =
        rememberEnumPreference(
            DarkModeKey,
            defaultValue = DarkMode.AUTO,
        )
    val (playerDesignStyle, onPlayerDesignStyleChange) =
        rememberEnumPreference(
            PlayerDesignStyleKey,
            defaultValue = PlayerDesignStyle.Default,
        )
    val (appleMusicAnimatedArtwork, onAppleMusicAnimatedArtworkChange) =
        rememberPreference(
            AppleMusicAnimatedArtworkKey,
            defaultValue = true,
        )
    val (tikTokMainLyrics, onTikTokMainLyricsChange) =
        rememberPreference(
            TikTokMainLyricsEnabledKey,
            defaultValue = false,
        )
    val (showPlayerVolumeBar, onShowPlayerVolumeBarChange) =
        rememberPreference(
            ShowPlayerVolumeBarKey,
            defaultValue = true,
        )
    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) =
        rememberPreference(
            HidePlayerThumbnailKey,
            defaultValue = false,
        )
    // The ArchiveTune Canvas artwork toggle lives in Player Settings → Artwork.
    val (thumbnailCornerRadius, onThumbnailCornerRadiusChange) =
        rememberPreference(
            key = ThumbnailCornerRadiusKey,
            defaultValue = 16f, // default dp
        )
    val (cropThumbnailToSquare, onCropThumbnailToSquareChange) =
        rememberPreference(
            CropThumbnailToSquareKey,
            defaultValue = false,
        )
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(
            PlayerBackgroundStyleKey,
            defaultValue = PlayerBackgroundStyle.DEFAULT,
        )
    val (configuredLyricsBackground, onLyricsBackgroundChange) =
        rememberEnumPreference(
            LyricsBackgroundStyleKey,
            defaultValue = LyricsBackgroundStyle.DEFAULT,
        )
    val (miniPlayerBackground, onMiniPlayerBackgroundChange) =
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            defaultValue = MiniPlayerBackgroundStyle.THEME,
        )
    val (compactMiniPlayer, onCompactMiniPlayerChange) =
        rememberPreference(CompactMiniPlayerKey, defaultValue = false)
    val (liquidGlassEnabled, onLiquidGlassEnabledChange) =
        rememberPreference(
            LiquidGlassEnabledKey,
            defaultValue = false,
        )
    val (libraryStyle, setLibraryStyle) = rememberLibraryStyle()
    val (interfaceStyle, onInterfaceStyleChange) = rememberInterfaceStyle()
    val (searchBarPosition, onSearchBarPositionChange) =
        rememberEnumPreference(SearchBarPositionKey, defaultValue = SearchBarPosition.TOP)
    val (hideSearchBarWhileScrolling, onHideSearchBarWhileScrollingChange) =
        rememberPreference(HideSearchChromeWhileScrollingKey, defaultValue = false)
    // The experience owns the player style while it is on, and the coupling lives with the setting
    // rather than here — the settings-search switch sets the same thing and has to move the style
    // with it too.
    val (pureBlack, onPureBlackChange) = rememberPreference(PureBlackKey, defaultValue = false)
    val (disableBlur, onDisableBlurChange) = rememberPreference(DisableBlurKey, defaultValue = false)
    val (disableAnimations, onDisableAnimationsChange) =
        rememberPreference(
            DisableAnimationsKey,
            defaultValue = defaultDisableAnimations,
        )
    val (splashOverlayEnabled, onSplashOverlayEnabledChange) =
        rememberPreference(
            SplashOverlayEnabledKey,
            defaultValue = true,
        )
    val (forceHighRefreshRate, onForceHighRefreshRateChange) =
        rememberPreference(
            ForceHighRefreshRateKey,
            defaultValue = false,
        )
    val (hideStatusBar, onHideStatusBarChange) =
        rememberPreference(
            HideStatusBarKey,
            defaultValue = false,
        )
    val (uiScale, onUiScaleChange) =
        rememberPreference(
            UiScaleFactorKey,
            defaultValue = 1.0f,
        )
    val (tabletModeEnabled, onTabletModeEnabledChange) =
        rememberPreference(
            TabletModeEnabledKey,
            defaultValue = false,
        )
    val (blurRadius, onBlurRadiusChange) = rememberPreference(BlurRadiusKey, defaultValue = 48f)
    val (backdropEnabled, onBackdropEnabledChange) = rememberPreference(BackdropEnabledKey, defaultValue = true)
    val (albumCanvasEnabled, onAlbumCanvasEnabledChange) =
        rememberPreference(AlbumCanvasEnabledKey, defaultValue = true)
    val (backdropBlurAmount, onBackdropBlurAmountChange) = rememberPreference(BackdropBlurAmountKey, defaultValue = 60)
    val (fontPreference, onFontPreferenceChange) =
        rememberEnumPreference(
            FontPreferenceKey,
            defaultValue = AppFontPreference.DEFAULT,
        )
    val (customFontUri, onCustomFontUriChange) = rememberPreference(CustomFontUriKey, defaultValue = "")
    val (customFontName, onCustomFontNameChange) = rememberPreference(CustomFontNameKey, defaultValue = "")
    val (defaultOpenTab, onDefaultOpenTabChange) =
        rememberEnumPreference(
            DefaultOpenTabKey,
            defaultValue = NavigationTab.HOME,
        )
    val (playerButtonsStyle, onPlayerButtonsStyleChange) =
        rememberEnumPreference(
            PlayerButtonsStyleKey,
            defaultValue = PlayerButtonsStyle.DEFAULT,
        )
    val (sliderStyle, onSliderStyleChange) =
        rememberEnumPreference(
            SliderStyleKey,
            defaultValue = SliderStyle.Standard,
        )
    val (gridItemSize, onGridItemSizeChange) =
        rememberEnumPreference(
            GridItemsSizeKey,
            defaultValue = GridItemSize.SMALL,
        )
    val (hideScrollbar, onHideScrollbarChange) =
        rememberPreference(HideScrollbarKey, defaultValue = false)
    val (minimalHomeMode, onMinimalHomeModeChange) =
        rememberPreference(MinimalHomeModeKey, defaultValue = false)
    val (homeScreenStyle, onHomeScreenStyleChange) =
        rememberEnumPreference(HomeScreenStyleKey, defaultValue = HomeScreenStyle.Default)
    val spotifySignedIn = rememberHomeSourceAvailable()
    val qqSignedIn = rememberQqHomeSourceAvailable()
    val (activeHomeSourcesRaw, setActiveHomeSources) = rememberPreference(ActiveHomeSourcesKey, "")
    val activeHomeSources = rememberActiveHomeSources()
    // What the picker shows and edits: the stored set when there is one, otherwise the resolved
    // default. Reading the stored set (not the resolved one) keeps the checkboxes and the summary
    // agreeing with the CSV — a page parked while signed out still reads as selected.
    val storedHomeSources = remember(activeHomeSourcesRaw) { parseHomeSources(activeHomeSourcesRaw) }
    val homeScreensSelection = storedHomeSources.ifEmpty { activeHomeSources }
    var homeScreensOpen by remember { mutableStateOf(false) }

    val customFontPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (!CustomFontLoader.isSupportedTtf(context, uri)) {
                Toast.makeText(context, context.getString(R.string.custom_font_invalid), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            if (customFontUri.isNotBlank() && customFontUri != uri.toString()) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(customFontUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }

            onCustomFontUriChange(uri.toString())
            onCustomFontNameChange(CustomFontLoader.displayName(context, uri))
            onFontPreferenceChange(AppFontPreference.CUSTOM)
        }
    val pickCustomFont =
        remember(customFontPickerLauncher) {
            {
                customFontPickerLauncher.launch(CustomFontLoader.supportedMimeTypes)
            }
        }
    val onFontPreferenceSelected =
        remember(customFontUri, onFontPreferenceChange, pickCustomFont) {
            { value: AppFontPreference ->
                onFontPreferenceChange(value)
                if (value == AppFontPreference.CUSTOM && customFontUri.isBlank()) {
                    pickCustomFont()
                }
            }
        }

    val availableBackgroundStyles =
        PlayerBackgroundStyle.entries.filter {
            it != PlayerBackgroundStyle.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }
    val availableLyricsBackgroundStyles =
        remember {
            // MOVING_BLUR uses Modifier.blur on Android 12+ (hardware-accelerated)
            // and falls back to a CPU pre-blurred bitmap (ImageBlurUtils.blur via
            // inline produceState, PR #924 approach) on pre-S devices. The drift
            // animation is applied via Modifier.offset on the pre-blurred bitmap,
            // so it doesn't require per-frame blurs and works on all SDK levels.
            buildList {
                add(LyricsBackgroundStyle.DEFAULT)
                add(LyricsBackgroundStyle.FOLLOW_THEME)
                add(LyricsBackgroundStyle.COLORING)
                add(LyricsBackgroundStyle.MOVING_BLUR)
            }
        }
    val lyricsBackground = configuredLyricsBackground.resolveFor(playerBackground)
    // Same source of truth the player renders from, so the picker can never offer a backdrop the
    // player will discard — or hide one it would have honoured.
    val isPlayerStyleCustomizationEnabled = playerDesignStyle.supportsBackgroundChoice
    val isVolumeBarSupported =
        playerDesignStyle == PlayerDesignStyle.V7 ||
            playerDesignStyle == PlayerDesignStyle.V8
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme =
        remember(darkMode, isSystemInDarkTheme) {
            if (darkMode == DarkMode.AUTO) isSystemInDarkTheme else darkMode == DarkMode.ON
        }

    val (defaultChip, onDefaultChipChange) =
        rememberEnumPreference(
            key = ChipSortTypeKey,
            defaultValue = LibraryFilter.LIBRARY,
        )
    val (libraryChipOrderPreference, onLibraryChipOrderChange) =
        rememberPreference(
            LibraryChipOrderKey,
            defaultValue = DefaultLibraryFilterOrderPreference,
        )
    val libraryChipOrder =
        remember(libraryChipOrderPreference) {
            libraryChipOrderPreference.toLibraryFilterOrder()
        }
    val (playlistTagOrderPreference, onPlaylistTagOrderChange) =
        rememberPreference(
            PlaylistTagOrderKey,
            defaultValue = "",
        )
    val playlistTagsViewModel: PlaylistTagsViewModel = hiltViewModel()
    var showLibraryChipOrderDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showPlaylistTagOrderDialog by rememberSaveable {
        mutableStateOf(false)
    }
    // The tags query + full editable-playlist mapping run only while the dialog
    // is open: collecting unconditionally costs a full playlist query on every
    // Appearance visit for a dialog opened rarely (low-end: large libraries).
    val playlistTagsState by
        if (showPlaylistTagOrderDialog) {
            playlistTagsViewModel.screenState.collectAsStateWithLifecycle()
        } else {
            remember { mutableStateOf(PlaylistTagsScreenState.Loading) }
        }
    val availablePlaylistTags =
        (playlistTagsState as? PlaylistTagsScreenState.Success)?.tags.orEmpty()
    val playlistTagOrder =
        remember(availablePlaylistTags, playlistTagOrderPreference) {
            val tagsById = availablePlaylistTags.associateBy(PlaylistTagUiModel::id)
            playlistTagOrderPreference
                .toPlaylistTagOrder(availablePlaylistTags.map(PlaylistTagUiModel::id))
                .mapNotNull { tagId -> tagsById[tagId] }
        }

    if (showLibraryChipOrderDialog) {
        LibraryChipOrderDialog(
            initialOrder = libraryChipOrder,
            onDismiss = { showLibraryChipOrderDialog = false },
            onConfirm = { newOrder ->
                onLibraryChipOrderChange(newOrder.toLibraryFilterPreference())
                showLibraryChipOrderDialog = false
            },
        )
    }

    if (showPlaylistTagOrderDialog) {
        PlaylistTagOrderDialog(
            state = playlistTagsState,
            initialOrder = playlistTagOrder,
            onDismiss = { showPlaylistTagOrderDialog = false },
            onConfirm = { newOrder ->
                onPlaylistTagOrderChange(
                    newOrder.map(PlaylistTagUiModel::id).toPlaylistTagPreference(),
                )
                showPlaylistTagOrderDialog = false
            },
        )
    }
    val supportedHighestFps = rememberSupportedHighestFps()
    val isHighRefreshRateSupported = supportedHighestFps > HIGH_REFRESH_RATE_THRESHOLD_FPS

    ApplyRefreshRate(
        isEnabled = forceHighRefreshRate && isHighRefreshRateSupported,
        targetFps = supportedHighestFps,
    )

    var showSliderOptionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(section, isPlayerStyleCustomizationEnabled, playerBackground) {
        if (section == AppearanceSection.PLAYER &&
            !isPlayerStyleCustomizationEnabled && playerBackground != PlayerBackgroundStyle.DEFAULT
        ) {
            onPlayerBackgroundChange(PlayerBackgroundStyle.DEFAULT)
        }
    }

    LaunchedEffect(isPlayerStyleCustomizationEnabled) {
        if (!isPlayerStyleCustomizationEnabled) {
            showSliderOptionDialog = false
        }
    }

    if (homeScreensOpen) {
        HomeScreensDialog(
            selected = homeScreensSelection,
            spotifyAvailable = spotifySignedIn,
            qqAvailable = qqSignedIn,
            onConfirm = { chosen ->
                // Only an explicit change rewrites the key. Confirming an untouched picker has to
                // leave an unset key unset — otherwise a fresh install (or a legacy one) whose
                // owner taps OK while signed out would pin the set to YouTube alone and never see
                // the Spotify page again, even after signing in.
                if (chosen.toSet() != homeScreensSelection.toSet()) {
                    setActiveHomeSources(chosen.joinToString(",") { it.name })
                }
                homeScreensOpen = false
            },
            onDismiss = { homeScreensOpen = false },
        )
    }

    if (showSliderOptionDialog && isPlayerStyleCustomizationEnabled) {
        val sliderStyles =
            remember {
                listOf(
                    SliderStyle.Standard,
                    SliderStyle.Wavy,
                    SliderStyle.Thick,
                    SliderStyle.Circular,
                    SliderStyle.Simple,
                )
            }
        DefaultDialog(
            buttons = {
                TextButton(
                    onClick = { showSliderOptionDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showSliderOptionDialog = false
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sliderStyles.chunked(3).forEach { styleRow ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        styleRow.forEach { style ->
                            SliderStyleOptionCard(
                                sliderStyle = style,
                                selected = sliderStyle == style,
                                onClick = {
                                    onSliderStyleChange(style)
                                    showSliderOptionDialog = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - styleRow.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (section) {
                                AppearanceSection.THEME -> R.string.appearance_theme
                                AppearanceSection.PLAYER -> R.string.appearance_player
                                AppearanceSection.INTERFACE -> R.string.appearance_interface
                            },
                        ),
                    )
                },
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
        // Lazy, not a verticalScroll Column: this page carries about sixty preference rows and the
        // Column composed every one of them before it could draw a frame, which is what made
        // opening Appearance lag and swallowed its enter animation.
        val listState = rememberLazyListState()
        val positions = rememberPreferencePositions()

        // Read here rather than in the group calls: the LazyColumn builder is a plain lambda, so
        // stringResource cannot be called from inside it.
        val themeTitle = stringResource(R.string.theme)
        val liquidGlassTitle = stringResource(R.string.liquid_glass)
        val playerTitle = stringResource(R.string.player)
        val albumPageTitle = stringResource(R.string.album_page)
        val homeTitle = stringResource(R.string.home)
        val miscTitle = stringResource(R.string.misc)
        val extrasTitle = stringResource(R.string.extras)

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, listState) }

        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                ),
            modifier =
                Modifier
                    .padding(top = topPadding)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                    // Measures the viewport rather than the scrolling content, so scrollToKey can
                    // work in pure deltas.
                    .then(positions.containerModifier()),
        ) {
            // Liquid Glass gets a header of its own. The master switch used to be the first row
            // under "Theme", the navigation bar's glass toggle is a page away under Interface, and
            // the mini player's is a value buried in a background-style picker — three places, with
            // nothing to say they were one feature. The switch and a link to the navigation bar's
            // own glass options sit together here; the mini player's stays where it is, because it
            // is one choice among several backgrounds rather than a glass setting that wandered.
            if (section == AppearanceSection.THEME) {
                preferenceGroup(
                    modifier = positions.modifierFor("liquid_glass"),
                    title = liquidGlassTitle,
                ) {
                    item {
                        Column(modifier = positions.modifierFor("liquid_glass_effects")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.liquid_glass_effects)) },
                                description = stringResource(R.string.liquid_glass_effects_desc),
                                icon = { Icon(painterResource(R.drawable.blur_on), null) },
                                checked = liquidGlassEnabled,
                                onCheckedChange = onLiquidGlassEnabledChange,
                            )
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && liquidGlassEnabled) {
                                Text(
                                    text = stringResource(R.string.liquid_glass_effects_unsupported),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                                )
                            }
                        }
                    }

                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("liquid_glass_nav_bar_link"),
                            title = { Text(stringResource(R.string.liquid_glass_nav_bar)) },
                            description = stringResource(R.string.liquid_glass_nav_bar_desc),
                            icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                            onClick = {
                                navController.navigate(
                                    "settings/appearance/navigation_bar?scrollTo=liquid_glass_nav_bar",
                                )
                            },
                        )
                    }
                }
            }

            if (section == AppearanceSection.THEME) {
                preferenceGroup(
                    modifier = positions.modifierFor("dynamic_theme"),
                    title = themeTitle,
                ) {
                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("interface_style", "apple_music_experience"),
                        title = { Text(stringResource(R.string.interface_style)) },
                        description = stringResource(R.string.interface_style_desc),
                        icon = { Icon(painterResource(R.drawable.palette), null) },
                        selectedValue = interfaceStyle,
                        onValueSelected = onInterfaceStyleChange,
                        valueText = {
                            when (it) {
                                InterfaceStyle.MATERIAL_EXPRESSIVE -> stringResource(R.string.interface_style_material_expressive)
                                InterfaceStyle.APPLE_MUSIC -> stringResource(R.string.apple_music_experience)
                            }
                        },
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.enable_dynamic_theme)) },
                        icon = { Icon(painterResource(R.drawable.palette), null) },
                        checked = dynamicTheme,
                        onCheckedChange = onDynamicThemeChange,
                    )
                }

                item(visible = dynamicTheme && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && wallpaperExtractionFailed) {
                    PreferenceEntry(
                        modifier = positions.modifierFor("wallpaper_permission"),
                        title = { Text(stringResource(R.string.wallpaper_permission)) },
                        description = stringResource(R.string.wallpaper_permission_desc),
                        icon = { Icon(painterResource(R.drawable.storage), null) },
                        onClick = {
                            val intent =
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                )
                            context.startActivity(intent)
                        },
                    )
                }

                item(visible = !dynamicTheme) {
                    SwitchPreference(
                        modifier = positions.modifierFor("random_theme_on_startup"),
                        title = { Text(stringResource(R.string.random_theme_on_startup)) },
                        description = stringResource(R.string.random_theme_on_startup_desc),
                        icon = { Icon(painterResource(R.drawable.shuffle), null) },
                        checked = randomThemeOnStartup,
                        onCheckedChange = onRandomThemeOnStartupChange,
                    )
                }

                item(visible = !dynamicTheme || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Column(modifier = positions.modifierFor("color_palette")) {
                        PreferenceEntry(
                            modifier = positions.modifierFor("palette_picker"),
                            title = { Text(stringResource(R.string.color_palette)) },
                            description = stringResource(R.string.customize_theme_colors),
                            icon = { Icon(painterResource(R.drawable.format_paint), null) },
                            onClick = { navController.navigate("settings/appearance/palette_picker") },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("app_icon")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.app_icon)) },
                            description = stringResource(R.string.app_icon_description),
                            icon = { Icon(painterResource(R.drawable.app_icon_small), null) },
                            onClick = { navController.navigate("settings/appearance/icon") },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("dark_theme")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.dark_theme)) },
                            icon = { Icon(painterResource(R.drawable.dark_mode), null) },
                            selectedValue = darkMode,
                            onValueSelected = onDarkModeChange,
                            valueText = {
                                when (it) {
                                    DarkMode.ON -> stringResource(R.string.dark_theme_on)
                                    DarkMode.OFF -> stringResource(R.string.dark_theme_off)
                                    DarkMode.AUTO -> stringResource(R.string.dark_theme_follow_system)
                                }
                            },
                        )
                    }
                }

                item(visible = useDarkTheme) {
                    Column(modifier = positions.modifierFor("pure_black")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.pure_black)) },
                            icon = { Icon(painterResource(R.drawable.contrast), null) },
                            checked = pureBlack,
                            onCheckedChange = onPureBlackChange,
                        )
                    }
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.disable_blur)) },
                        description = stringResource(R.string.disable_blur_desc),
                        icon = { Icon(painterResource(R.drawable.blur_off), null) },
                        checked = disableBlur,
                        onCheckedChange = onDisableBlurChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("disable_animations"),
                        title = { Text(stringResource(R.string.disable_animations)) },
                        description = stringResource(R.string.disable_animations_desc),
                        icon = { Icon(painterResource(R.drawable.animation), null) },
                        checked = disableAnimations,
                        onCheckedChange = onDisableAnimationsChange,
                    )
                }

                // The opening animation is the one animation that plays before the app is
                // visible, so it gets its own switch rather than riding on "Disable animations":
                // a reader can want the launch flourish without the rest of the app's motion.
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("splash_overlay_enabled"),
                        title = { Text(stringResource(R.string.splash_overlay_enabled)) },
                        description = stringResource(R.string.splash_overlay_enabled_desc),
                        icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                        checked = splashOverlayEnabled,
                        onCheckedChange = onSplashOverlayEnabledChange,
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("hide_status_bar")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.hide_status_bar)) },
                            description = stringResource(R.string.hide_status_bar_desc),
                            icon = { Icon(painterResource(R.drawable.visibility_off), null) },
                            checked = hideStatusBar,
                            onCheckedChange = onHideStatusBarChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("ui_scale")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.ui_scale)) },
                            description = stringResource(R.string.ui_scale_desc),
                            icon = { Icon(painterResource(R.drawable.text_fields), null) },
                            content = {
                                Spacer(modifier = Modifier.height(10.dp))
                                Slider(
                                    value = uiScale,
                                    onValueChange = { v ->
                                        // Round to the nearest 1% so the displayed value and the
                                        // stored value stay in sync (otherwise dragging produces
                                        // long-tail floats like 0.92371 that look messy in backups).
                                        onUiScaleChange((v * 100f).roundToInt() / 100f)
                                    },
                                    valueRange = 0.85f..1.30f,
                                    steps = 44, // 45 discrete positions = 1% increments
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(R.string.ui_scale_value, (uiScale * 100f).roundToInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 56.dp, top = 4.dp),
                                )
                            },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("blur_intensity")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.blur_intensity)) },
                            description = stringResource(R.string.blur_intensity_value, blurRadius.roundToInt()),
                            icon = { Icon(painterResource(R.drawable.blur_on), null) },
                            isEnabled = !disableBlur,
                            content = {
                                Spacer(modifier = Modifier.height(10.dp))
                                Slider(
                                    value = blurRadius,
                                    onValueChange = onBlurRadiusChange,
                                    valueRange = 0f..64f,
                                    steps = 63,
                                    enabled = !disableBlur,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                        )
                    }
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.album_backdrop)) },
                        description = stringResource(R.string.album_backdrop_desc),
                        icon = { Icon(painterResource(R.drawable.blur_on), null) },
                        checked = backdropEnabled,
                        onCheckedChange = onBackdropEnabledChange,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("backdrop_blur_amount"),
                        title = { Text(stringResource(R.string.backdrop_blur_amount)) },
                        description = stringResource(R.string.backdrop_blur_amount_value, backdropBlurAmount),
                        icon = { Icon(painterResource(R.drawable.blur_on), null) },
                        isEnabled = backdropEnabled,
                        content = {
                            Spacer(modifier = Modifier.height(10.dp))
                            Slider(
                                value = backdropBlurAmount.toFloat(),
                                onValueChange = { onBackdropBlurAmountChange(it.roundToInt()) },
                                valueRange = 0f..100f,
                                steps = 19,
                                enabled = backdropEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("font_preference")) {
                        EnumListPreference(
                            modifier = positions.modifierFor("use_system_font"),
                            title = { Text(stringResource(R.string.font_preference)) },
                            description = stringResource(R.string.font_preference_desc),
                            icon = { Icon(painterResource(R.drawable.text_fields), null) },
                            selectedValue = fontPreference,
                            onValueSelected = onFontPreferenceSelected,
                            valueText = {
                                when (it) {
                                    AppFontPreference.DEFAULT -> stringResource(R.string.font_preference_default)
                                    AppFontPreference.SYSTEM -> stringResource(R.string.font_preference_system)
                                    AppFontPreference.CUSTOM -> stringResource(R.string.font_preference_custom)
                                }
                            },
                        )
                    }
                }

                item(visible = fontPreference == AppFontPreference.CUSTOM) {
                    val customFontDescription =
                        if (customFontName.isNotBlank()) {
                            customFontName
                        } else if (customFontUri.isBlank()) {
                            stringResource(R.string.custom_font_desc)
                        } else {
                            customFontUri
                        }
                    PreferenceEntry(
                        modifier = positions.modifierFor("custom_font"),
                        title = { Text(stringResource(R.string.custom_font)) },
                        description = customFontDescription,
                        icon = { Icon(painterResource(R.drawable.text_fields), null) },
                        onClick = pickCustomFont,
                    )
                }
                }
            }

            if (section == AppearanceSection.PLAYER) {
                preferenceGroup(
                    modifier = positions.modifierFor("disable_blur"),
                    title = playerTitle,
                ) {
                    item {
                        Column(modifier = positions.modifierFor("home_screens")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.home_screens)) },
                                description = stringResource(R.string.home_screens_desc),
                                icon = { Icon(painterResource(R.drawable.ic_home_outline), null) },
                                isEnabled = true,
                                onClick = { homeScreensOpen = true },
                                trailingContent = {
                                    Text(
                                        text = homeSourcesLabel(homeScreensSelection),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("library_style")) {
                            EnumListPreference(
                                title = { Text(stringResource(R.string.library_style)) },
                                description = stringResource(R.string.library_style_desc),
                                icon = { Icon(painterResource(R.drawable.music_note), null) },
                                selectedValue = libraryStyle,
                                onValueSelected = setLibraryStyle,
                                valueText = {
                                    when (it) {
                                        LibraryStyle.DEFAULT -> stringResource(R.string.library_style_default)
                                        LibraryStyle.APPLE_MUSIC ->
                                            stringResource(R.string.library_style_apple_music)
                                    }
                                },
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("player_design_style")) {
                            EnumListPreference(
                                title = { Text(stringResource(R.string.player_design_style)) },
                                icon = { Icon(painterResource(R.drawable.palette), null) },
                                selectedValue = playerDesignStyle,
                                onValueSelected = onPlayerDesignStyleChange,
                                valueText = {
                                    when (it) {
                                        PlayerDesignStyle.V1 -> stringResource(R.string.player_design_v1)
                                        PlayerDesignStyle.V2 -> stringResource(R.string.player_design_v2)
                                        PlayerDesignStyle.V3 -> stringResource(R.string.player_design_v3)
                                        PlayerDesignStyle.V4 -> stringResource(R.string.player_design_v4)
                                        PlayerDesignStyle.V5 -> stringResource(R.string.player_design_v5)
                                        PlayerDesignStyle.V6 -> stringResource(R.string.player_design_v6)
                                        PlayerDesignStyle.V7 -> stringResource(R.string.player_design_v7)
                                        PlayerDesignStyle.V8 -> stringResource(R.string.player_design_v8)
                                        PlayerDesignStyle.V9 -> stringResource(R.string.player_design_v9)
                                        PlayerDesignStyle.APPLE_MUSIC ->
                                            stringResource(R.string.player_design_apple_music)
                                        PlayerDesignStyle.V10 ->
                                            stringResource(R.string.player_design_v10)
                                        PlayerDesignStyle.BITCHORD ->
                                            stringResource(R.string.player_design_bitchord)
                                        PlayerDesignStyle.TIKTOK ->
                                            stringResource(R.string.player_design_tiktok)
                                        PlayerDesignStyle.SIMPMUSIC ->
                                            stringResource(R.string.player_design_simpmusic)
                                        PlayerDesignStyle.SPATIALFLOW ->
                                            stringResource(R.string.player_design_spatialflow)
                                    }
                                },
                            )
                        }
                }

                // Only for the TikTok style: the strip reserves a fixed slot under the artwork on
                // that page and nowhere else.
                if (playerDesignStyle == PlayerDesignStyle.TIKTOK) {
                    item {
                        Column(modifier = positions.modifierFor("tiktok_main_lyrics")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.tiktok_main_lyrics)) },
                                description = stringResource(R.string.tiktok_main_lyrics_desc),
                                icon = { Icon(painterResource(R.drawable.lyrics), null) },
                                checked = tikTokMainLyrics,
                                onCheckedChange = onTikTokMainLyricsChange,
                            )
                        }
                    }
                }

                // Only for the Apple Music style: it is the one style that plays a Canvas loop or
                // a music video in the cover's place, so the switch would control nothing anywhere
                // else. Hidden rather than disabled — a permanently greyed row is a worse
                // explanation than no row.
                if (playerDesignStyle == PlayerDesignStyle.APPLE_MUSIC) {
                    item {
                        SwitchPreference(
                            modifier = positions.modifierFor("apple_music_animated_artwork"),
                            title = { Text(stringResource(R.string.apple_music_animated_artwork)) },
                            description = stringResource(R.string.apple_music_animated_artwork_desc),
                            icon = { Icon(painterResource(R.drawable.animation), null) },
                            checked = appleMusicAnimatedArtwork,
                            onCheckedChange = onAppleMusicAnimatedArtworkChange,
                        )
                    }
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("show_player_volume_bar"),
                        title = { Text(stringResource(R.string.show_player_volume_bar)) },
                        description =
                            if (isVolumeBarSupported) {
                                null
                            } else {
                                stringResource(R.string.player_volume_bar_v7_v8_only)
                            },
                        icon = { Icon(painterResource(R.drawable.volume_up), null) },
                        checked = showPlayerVolumeBar,
                        onCheckedChange = onShowPlayerVolumeBarChange,
                        isEnabled = isVolumeBarSupported,
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("player_background_style")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.player_background_style)) },
                            description =
                                if (isPlayerStyleCustomizationEnabled) {
                                    null
                                } else {
                                    stringResource(R.string.player_background_style_v8_v9_desc)
                                },
                            icon = { Icon(painterResource(R.drawable.gradient), null) },
                            selectedValue = playerBackground,
                            onValueSelected = { selectedBackground ->
                                onPlayerBackgroundChange(selectedBackground)
                                when {
                                    selectedBackground == PlayerBackgroundStyle.CUSTOM -> {
                                        onLyricsBackgroundChange(LyricsBackgroundStyle.CUSTOM)
                                    }

                                    configuredLyricsBackground == LyricsBackgroundStyle.CUSTOM -> {
                                        onLyricsBackgroundChange(LyricsBackgroundStyle.DEFAULT)
                                    }
                                }
                            },
                            isEnabled = isPlayerStyleCustomizationEnabled,
                            valueText = {
                                when (it) {
                                    PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                                    PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                                    PlayerBackgroundStyle.CUSTOM -> stringResource(R.string.custom)
                                    PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                                    PlayerBackgroundStyle.COLORING -> stringResource(R.string.coloring)
                                    PlayerBackgroundStyle.BLUR_GRADIENT -> stringResource(R.string.blur_gradient)
                                    PlayerBackgroundStyle.GLOW -> stringResource(R.string.glow)
                                    PlayerBackgroundStyle.GLOW_ANIMATED -> "Glow Animated"
                                }
                            },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("lyrics_background_style")) {
                        ListPreference(
                            modifier = positions.modifierFor("lyrics_background_style"),
                            title = { Text(stringResource(R.string.lyrics_background_style)) },
                            icon = { Icon(painterResource(R.drawable.lyrics), null) },
                            selectedValue = lyricsBackground,
                            values = availableLyricsBackgroundStyles,
                            onValueSelected = onLyricsBackgroundChange,
                            isEnabled = playerBackground != PlayerBackgroundStyle.CUSTOM,
                            valueText = {
                                when (it) {
                                    LyricsBackgroundStyle.DEFAULT -> stringResource(R.string.lyrics_background_default)
                                    LyricsBackgroundStyle.FOLLOW_THEME -> stringResource(R.string.follow_theme)
                                    LyricsBackgroundStyle.COLORING -> stringResource(R.string.coloring)
                                    LyricsBackgroundStyle.MOVING_BLUR -> stringResource(R.string.lyrics_background_moving_blur)
                                    LyricsBackgroundStyle.CUSTOM -> stringResource(R.string.custom)
                                }
                            },
                        )
                        // Pre-Android 12 disclaimer: the moving-blur background relies on per-frame
                        // Modifier.blur (RenderEffect, API 31+). On pre-S the fallback renders a
                        // single pre-blurred bitmap with no drift animation, so the blur is static.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                            lyricsBackground == LyricsBackgroundStyle.MOVING_BLUR
                        ) {
                            Text(
                                text = stringResource(R.string.moving_blur_static_disclaimer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                    }
                }

                item(visible = playerBackground == PlayerBackgroundStyle.CUSTOM) {
                    PreferenceEntry(
                        modifier = positions.modifierFor("customized_background"),
                        title = { Text(stringResource(R.string.customized_background)) },
                        icon = { Icon(painterResource(R.drawable.image), null) },
                        onClick = { navController.navigate("customize_background") },
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("mini_player_background_style")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.mini_player_background_style)) },
                            icon = { Icon(painterResource(R.drawable.gradient), null) },
                            selectedValue = miniPlayerBackground,
                            onValueSelected = { newStyle ->
                                // Guard the LIQUID_GLASS style: only commit it when the master
                                // Liquid Glass toggle is on AND we're on Android 12+. Otherwise
                                // silently downgrade to THEME so the picker still closes but no
                                // unsupported state is persisted. The user sees the warning below
                                // telling them why their selection didn't apply.
                                val canUseLiquidGlass =
                                    liquidGlassEnabled &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                val effective =
                                    if (newStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS &&
                                        !canUseLiquidGlass
                                    ) {
                                        MiniPlayerBackgroundStyle.THEME
                                    } else {
                                        newStyle
                                    }
                                onMiniPlayerBackgroundChange(effective)
                            },
                            valueText = {
                                when (it) {
                                    MiniPlayerBackgroundStyle.THEME -> stringResource(R.string.follow_theme)
                                    MiniPlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                                    MiniPlayerBackgroundStyle.GLOW -> stringResource(R.string.glow)
                                    MiniPlayerBackgroundStyle.FROSTED -> stringResource(R.string.frosted_blur)
                                    MiniPlayerBackgroundStyle.LIQUID_GLASS -> stringResource(R.string.liquid_glass)
                                }
                            },
                        )
                        // Pre-Android 12 warning: frosted mini player uses RenderEffect (API 31+).
                        // On pre-S the FROSTED style is silently downgraded to THEME — surface a
                        // warning so users on older devices know why their selection isn't applying.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                            miniPlayerBackground == MiniPlayerBackgroundStyle.FROSTED
                        ) {
                            Text(
                                text = stringResource(R.string.frosted_mini_player_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                        // Liquid Glass mini player requires the master Liquid Glass toggle
                        // (Appearance → Liquid Glass effects) AND Android 12+. If the user
                        // somehow has LIQUID_GLASS selected but the master toggle is off (e.g.
                        // they turned the master off after selecting LIQUID_GLASS), surface a
                        // hint that the master toggle needs to be on for the style to apply.
                        if (miniPlayerBackground == MiniPlayerBackgroundStyle.LIQUID_GLASS &&
                            (!liquidGlassEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                        ) {
                            Text(
                                text = stringResource(R.string.liquid_glass_mini_player_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                    }
                }

                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("compact_mini_player"),
                        title = { Text(stringResource(R.string.mini_player_layout)) },
                        description = stringResource(R.string.mini_player_layout_desc),
                        icon = { Icon(painterResource(R.drawable.gradient), null) },
                        selectedValue = if (compactMiniPlayer) CompactLayoutChoice.COMPACT else CompactLayoutChoice.DEFAULT,
                        onValueSelected = { onCompactMiniPlayerChange(it == CompactLayoutChoice.COMPACT) },
                        valueText = {
                            when (it) {
                                CompactLayoutChoice.DEFAULT -> stringResource(R.string.default_style)
                                CompactLayoutChoice.COMPACT -> stringResource(R.string.compact_layout)
                            }
                        },
                        isEnabled = interfaceStyle != InterfaceStyle.APPLE_MUSIC,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_player_thumbnail"),
                        title = { Text(stringResource(R.string.hide_player_thumbnail)) },
                        description = stringResource(R.string.hide_player_thumbnail_desc),
                        icon = { Icon(painterResource(R.drawable.hide_image), null) },
                        checked = hidePlayerThumbnail,
                        onCheckedChange = onHidePlayerThumbnailChange,
                    )
                }

                item {
                    ThumbnailCornerRadiusSelectorButton(
                        onRadiusSelected = {},
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("crop_thumbnail_to_square"),
                        title = { Text(stringResource(R.string.crop_thumbnail_to_square)) },
                        description = stringResource(R.string.crop_thumbnail_to_square_desc),
                        icon = { Icon(painterResource(R.drawable.image), null) },
                        checked = cropThumbnailToSquare,
                        onCheckedChange = onCropThumbnailToSquareChange,
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.aod_customize_title)) },
                        description = stringResource(R.string.aod_customize_entry_desc),
                        icon = { Icon(painterResource(R.drawable.bedtime), null) },
                        onClick = { navController.navigate("settings/appearance/aod_customized") },
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("player_buttons_style")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.player_buttons_style)) },
                            description =
                                if (isPlayerStyleCustomizationEnabled) {
                                    null
                                } else {
                                    stringResource(R.string.player_background_style_v8_v9_desc)
                                },
                            icon = { Icon(painterResource(R.drawable.palette), null) },
                            selectedValue = playerButtonsStyle,
                            onValueSelected = onPlayerButtonsStyleChange,
                            isEnabled = isPlayerStyleCustomizationEnabled,
                            valueText = {
                                when (it) {
                                    PlayerButtonsStyle.DEFAULT -> stringResource(R.string.default_style)
                                    PlayerButtonsStyle.SECONDARY -> stringResource(R.string.secondary_color_style)
                                }
                            },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("player_slider_style")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.player_slider_style)) },
                            description = sliderStyleLabel(sliderStyle),
                            icon = { Icon(painterResource(R.drawable.sliders), null) },
                            onClick = {
                                showSliderOptionDialog = true
                            },
                            isEnabled = isPlayerStyleCustomizationEnabled,
                        )
                    }
                }
                }
            }

            if (section == AppearanceSection.PLAYER) {
                preferenceGroup(
                    modifier = positions.modifierFor("album_page"),
                    title = albumPageTitle,
                ) {
                    item {
                        SwitchPreference(
                            modifier = positions.modifierFor("album_canvas_enabled"),
                            title = { Text(stringResource(R.string.album_canvas_enabled)) },
                            description = stringResource(R.string.album_canvas_enabled_desc),
                            icon = { Icon(painterResource(R.drawable.album), null) },
                            checked = albumCanvasEnabled,
                            onCheckedChange = onAlbumCanvasEnabledChange,
                        )
                }
                }
            }

            // The three settings that decide what the Home tab shows were scattered through
            // "Misc" between tablet mode, the scrollbar toggle and the library chips. They are
            // one decision — which home you get — so they read as one group.
            if (section == AppearanceSection.INTERFACE) {
                preferenceGroup(
                    modifier = positions.modifierFor("home_screen"),
                    title = homeTitle,
                ) {
                    item {
                        EnumListPreference(
                            modifier = positions.modifierFor("home_screen_style"),
                            title = { Text(stringResource(R.string.home_screen_style)) },
                            description = stringResource(R.string.home_screen_style_desc),
                            icon = { Icon(painterResource(R.drawable.home_outlined), null) },
                            selectedValue = homeScreenStyle,
                            onValueSelected = onHomeScreenStyleChange,
                            valueText = {
                                when (it) {
                                    HomeScreenStyle.DEFAULT -> stringResource(R.string.home_screen_style_default)
                                    HomeScreenStyle.RUKAMORI -> stringResource(R.string.home_screen_style_rukamori)
                                }
                            },
                        )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("minimal_home_mode"),
                        title = { Text(stringResource(R.string.minimal_home_mode)) },
                        description = stringResource(R.string.minimal_home_mode_desc),
                        icon = { Icon(painterResource(R.drawable.home_outlined), null) },
                        checked = minimalHomeMode,
                        onCheckedChange = onMinimalHomeModeChange,
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("default_open_tab")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.default_open_tab)) },
                            icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                            selectedValue = defaultOpenTab,
                            onValueSelected = onDefaultOpenTabChange,
                            valueText = {
                                when (it) {
                                    NavigationTab.HOME -> stringResource(R.string.home)
                                    NavigationTab.SEARCH -> stringResource(R.string.search)
                                    NavigationTab.LIBRARY -> stringResource(R.string.filter_library)
                                }
                            },
                        )
                    }
                }

                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("search_bar_position"),
                        title = { Text(stringResource(R.string.search_bar_position)) },
                        description =
                            stringResource(
                                if (searchBarPosition == SearchBarPosition.BOTTOM) {
                                    R.string.search_bar_position_bottom_desc
                                } else {
                                    R.string.search_bar_position_desc
                                },
                            ),
                        icon = { Icon(painterResource(R.drawable.search), null) },
                        selectedValue = searchBarPosition,
                        onValueSelected = onSearchBarPositionChange,
                        valueText = {
                            when (it) {
                                SearchBarPosition.TOP -> stringResource(R.string.search_bar_position_top)
                                SearchBarPosition.BOTTOM -> stringResource(R.string.search_bar_position_bottom)
                            }
                        },
                    )
                }

                item(visible = searchBarPosition == SearchBarPosition.BOTTOM) {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_search_bar_while_scrolling"),
                        title = { Text(stringResource(R.string.hide_search_bar_while_scrolling)) },
                        description = stringResource(R.string.hide_search_bar_while_scrolling_desc),
                        icon = { Icon(painterResource(R.drawable.search), null) },
                        checked = hideSearchBarWhileScrolling,
                        onCheckedChange = onHideSearchBarWhileScrollingChange,
                    )
                }
                }
            }

            if (section == AppearanceSection.INTERFACE) {
                preferenceGroup(
                    modifier = positions.modifierFor("app_language"),
                    title = miscTitle,
                ) {
                    item {
                        SwitchPreference(
                            modifier = positions.modifierFor("tablet_mode"),
                            title = { Text(stringResource(R.string.tablet_mode)) },
                            description = stringResource(R.string.tablet_mode_desc),
                            icon = { Icon(painterResource(R.drawable.desktop_windows), null) },
                            checked = tabletModeEnabled,
                            onCheckedChange = onTabletModeEnabledChange,
                        )
                }

                item {
                    PreferenceEntry(
                        // Both keys on the one row: this page used to carry two identical entries
                        // for the same sub-page, one per key. One row, both aliases.
                        modifier = positions.modifierFor("navigation_bar_settings", "navigation_bar_style"),
                        title = { Text(stringResource(R.string.navigation_bar_settings_title)) },
                        description = stringResource(R.string.navigation_bar_settings_subtitle),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        onClick = { navController.navigate("settings/appearance/navigation_bar") },
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_scrollbar"),
                        title = { Text(stringResource(R.string.hide_scrollbar)) },
                        description = stringResource(R.string.hide_scrollbar_desc),
                        icon = { Icon(painterResource(R.drawable.filter_alt), null) },
                        checked = hideScrollbar,
                        onCheckedChange = onHideScrollbarChange,
                    )
                }

                item {
                    ListPreference(
                        modifier = positions.modifierFor("default_lib_chips"),
                        title = { Text(stringResource(R.string.default_lib_chips)) },
                        icon = { Icon(painterResource(R.drawable.tab), null) },
                        selectedValue = defaultChip,
                        values =
                            listOf(
                                LibraryFilter.LIBRARY,
                                LibraryFilter.PLAYLISTS,
                                LibraryFilter.SONGS,
                                LibraryFilter.ALBUMS,
                                LibraryFilter.ARTISTS,
                            ),
                        valueText = {
                            when (it) {
                                LibraryFilter.SONGS -> stringResource(R.string.songs)
                                LibraryFilter.ARTISTS -> stringResource(R.string.artists)
                                LibraryFilter.ALBUMS -> stringResource(R.string.albums)
                                LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
                                LibraryFilter.LIBRARY -> stringResource(R.string.filter_library)
                            }
                        },
                        onValueSelected = onDefaultChipChange,
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.arrange_library_chips)) },
                        description = stringResource(R.string.arrange_library_chips_desc),
                        icon = { Icon(painterResource(R.drawable.tab), null) },
                        onClick = { showLibraryChipOrderDialog = true },
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.arrange_playlist_tags)) },
                        description = stringResource(R.string.arrange_playlist_tags_desc),
                        icon = { Icon(painterResource(R.drawable.style), null) },
                        onClick = { showPlaylistTagOrderDialog = true },
                    )
                }
                }
            }

            if (section == AppearanceSection.INTERFACE) {
                preferenceGroup(
                    modifier = positions.modifierFor("extras"),
                    title = extrasTitle,
                ) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.extras)) },
                            description = stringResource(R.string.settings_extras_subtitle),
                            icon = { Icon(painterResource(R.drawable.discover_tune), null) },
                            onClick = { navController.navigate("settings/appearance/extras") },
                        )
                }
                }
            }
        }
    }
}

@Composable
fun ApplyRefreshRate(
    isEnabled: Boolean,
    targetFps: Float,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val requestedFps = if (isEnabled) targetFps else DEFAULT_REFRESH_RATE_REQUEST

    DisposableEffect(view, activity, requestedFps) {
        applyRefreshRate(
            view = view,
            activity = activity,
            requestedFps = requestedFps,
        )

        onDispose {
            applyRefreshRate(
                view = view,
                activity = activity,
                requestedFps = DEFAULT_REFRESH_RATE_REQUEST,
            )
        }
    }
}

@Composable
private fun homeSourcesLabel(sources: List<HomeSource>): String {
    // map is inline, so the stringResource calls stay in composable scope; joinToString is not.
    val labels = sources.map { stringResource(it.labelResId()) }
    return labels.joinToString(", ")
}

@Composable
private fun rememberSupportedHighestFps(): Float {
    val view = LocalView.current

    return remember(view) {
        val display = view.display
        display
            ?.supportedModes
            ?.maxOfOrNull { mode -> mode.refreshRate }
            ?: display?.refreshRate
            ?: DEFAULT_STANDARD_REFRESH_RATE_FPS
    }
}

private fun applyRefreshRate(
    view: View,
    activity: Activity?,
    requestedFps: Float,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        view.setRequestedFrameRate(requestedFps)
        return
    }

    activity?.window?.let { window ->
        val attributes = window.attributes
        if (attributes.preferredRefreshRate != requestedFps) {
            attributes.preferredRefreshRate = requestedFps
            window.attributes = attributes
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val HIGH_REFRESH_RATE_THRESHOLD_FPS = 60.5f
private const val DEFAULT_STANDARD_REFRESH_RATE_FPS = 60f
private const val DEFAULT_REFRESH_RATE_REQUEST = 0f

@Composable
private fun SliderStyleOptionCard(
    sliderStyle: SliderStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember {
        mutableFloatStateOf(0.5f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(16.dp),
                ).clickable(onClick = onClick)
                .padding(16.dp),
    ) {
        StyledPlaybackSlider(
            sliderStyle = sliderStyle,
            value = sliderValue,
            valueRange = 0f..1f,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {},
            activeColor = MaterialTheme.colorScheme.primary,
            isPlaying = true,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        )

        Text(
            text = sliderStyleLabel(sliderStyle),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun sliderStyleLabel(sliderStyle: SliderStyle): String =
    when (sliderStyle) {
        SliderStyle.Standard -> stringResource(R.string.slider_style_standard)
        SliderStyle.Wavy -> stringResource(R.string.slider_style_wavy)
        SliderStyle.Thick -> stringResource(R.string.slider_style_thick)
        SliderStyle.Circular -> stringResource(R.string.slider_style_circular)
        SliderStyle.Simple -> stringResource(R.string.slider_style_simple)
    }

enum class DarkMode {
    ON,
    OFF,
    AUTO,
}

enum class NavigationTab {
    HOME,
    SEARCH,
    LIBRARY,
}

enum class PlayerTextAlignment {
    SIDED,
    CENTER,
}

enum class LyricsPosition {
    LEFT,
    CENTER,
    RIGHT,
}
