/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.ArtistSeparatorsKey
import moe.rukamori.archivetune.constants.ArtworkProviderOrderKey
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.RejectSuspectedUpscaledAudioKey
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.constants.AutoStartOnBluetoothKey
import moe.rukamori.archivetune.constants.CanvasResolverEndpointsKey
import moe.rukamori.archivetune.constants.CrossfadeDurationKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.AutomixEnabledKey
import moe.rukamori.archivetune.constants.AutomixPerformanceMode
import moe.rukamori.archivetune.constants.AutomixPerformanceModeKey
import moe.rukamori.archivetune.constants.CrossfadeGaplessKey
import moe.rukamori.archivetune.constants.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.constants.EnableVideoPlaybackKey
import moe.rukamori.archivetune.constants.EnablePipModeKey
import moe.rukamori.archivetune.constants.DefaultArtworkProviderOrder
import moe.rukamori.archivetune.constants.HISTORY_DURATION_DEFAULT
import moe.rukamori.archivetune.constants.HistoryDuration
import moe.rukamori.archivetune.constants.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.constants.PermanentShuffleKey
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.constants.SeekExtraSeconds
import moe.rukamori.archivetune.constants.SkipSilenceKey
import moe.rukamori.archivetune.constants.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.constants.SponsorBlockEnabledKey
import moe.rukamori.archivetune.sponsorblock.SponsorBlockCategory
import moe.rukamori.archivetune.constants.PreferredArtworkProvider
import moe.rukamori.archivetune.constants.SpotifyCanvasKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.SwipeToSongKey
import moe.rukamori.archivetune.constants.SwipeSensitivityKey
import moe.rukamori.archivetune.constants.SwipeThumbnailKey
import moe.rukamori.archivetune.constants.deserializeArtworkProviderOrder
import moe.rukamori.archivetune.constants.TidalArtworkFallbackEnabledKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.constants.WakelockKey
import moe.rukamori.archivetune.ui.component.ArtistSeparatorsDialog
import moe.rukamori.archivetune.ui.component.CrossfadeSliderPreference
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.constants.BitPerfectUsbOutputKey
import moe.rukamori.archivetune.constants.QueueAddAfterManualKey
import moe.rukamori.archivetune.constants.SwipeUpToOpenQueueKey
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.NumberPickerPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SliderPreference
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TagsManagementDialog
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.CanvasResolverEndpoints
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.rememberEnumPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.asPaddingValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(navController: NavController, scrollTo: String? = null) {
    // snapshot paints the stored value on the first frame instead of a default flash.
    val (persistentQueue, onPersistentQueueChange) =
        rememberPreference(
            PersistentQueueKey,
            defaultValue = true,
        )
    val (permanentShuffle, onPermanentShuffleChange) =
        rememberPreference(
            PermanentShuffleKey,
            defaultValue = false,
        )
    val (sponsorBlockEnabled, onSponsorBlockEnabledChange) =
        rememberPreference(SponsorBlockEnabledKey, defaultValue = false)
    val (sponsorBlockCategories, onSponsorBlockCategoriesRawChange) =
        rememberPreference(
            SponsorBlockCategoriesKey,
            defaultValue = SponsorBlockCategory.Defaults.map { it.apiName }.toSet(),
        )
    // Same scope-death fix as above, with the apiName round-trip kept: parse
    // synchronously, write the re-encoded set through the preference helper.
    val onSponsorBlockCategoriesChange: (Set<String>) -> Unit =
        remember(onSponsorBlockCategoriesRawChange) { { categories ->
            val parsed = categories.mapNotNull(SponsorBlockCategory::fromApiName).toSet()
            onSponsorBlockCategoriesRawChange(parsed.map { it.apiName }.toSet())
        } }
    val (skipSilence, onSkipSilenceChange) =
        rememberPreference(
            SkipSilenceKey,
            defaultValue = false,
        )
    val (audioNormalization, onAudioNormalizationChange) =
        rememberPreference(
            AudioNormalizationKey,
            defaultValue = true,
        )
    val (audioQuality, onAudioQualityChange) =
        rememberEnumPreference(AudioQualityKey, AudioQuality.AUTO)
    val (rejectSuspectedUpscaledAudio, onRejectSuspectedUpscaledAudioChange) =
        rememberPreference(RejectSuspectedUpscaledAudioKey, defaultValue = false)
    val (audioOffload, onAudioOffloadChange) =
        rememberPreference(
            AudioOffload,
            defaultValue = false,
        )
    val (swipeUpToOpenQueue, onSwipeUpToOpenQueueChange) =
        rememberPreference(SwipeUpToOpenQueueKey, defaultValue = true)
    val (queueAddAfterManual, onQueueAddAfterManualChange) =
        rememberPreference(QueueAddAfterManualKey, defaultValue = true)
    val (bitPerfectUsb, onBitPerfectUsbChange) =
        rememberPreference(
            BitPerfectUsbOutputKey,
            defaultValue = false,
        )
    val bitPerfectStatus by BitPerfectUsbOutput.status.collectAsState()
    val bitPerfectContext = LocalContext.current
    val releaseBitPerfectUsb = {
        onBitPerfectUsbChange(false)
        if (BitPerfectUsbOutput.sinkActive) BitPerfectUsbOutput.setEnabled(bitPerfectContext, false)
    }

    val (seekExtraSeconds, onSeekExtraSeconds) =
        rememberPreference(
            SeekExtraSeconds,
            defaultValue = false,
        )

    val (enableVideoPlayback, onEnableVideoPlaybackChange) =
        rememberPreference(
            EnableVideoPlaybackKey,
            defaultValue = false,
        )
    val (enablePipMode, onEnablePipModeChange) =
        rememberPreference(
            EnablePipModeKey,
            defaultValue = false,
        )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) =
        rememberPreference(
            AutoSkipNextOnErrorKey,
            defaultValue = false,
        )
    val (pauseOnDeviceMute, onPauseOnDeviceMuteChange) =
        rememberPreference(
            PauseOnDeviceMuteKey,
            defaultValue = false,
        )
    val (
        deviceMutePlaybackRecoveryVolume,
        onDeviceMutePlaybackRecoveryVolumeChange,
    ) =
        rememberPreference(
            DeviceMutePlaybackRecoveryVolumeKey,
            defaultValue = 0,
        )
    val (autoStartOnBluetooth, onAutoStartOnBluetoothChange) =
        rememberPreference(
            AutoStartOnBluetoothKey,
            defaultValue = false,
        )
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) =
        rememberPreference(
            StopMusicOnTaskClearKey,
            defaultValue = false,
        )
    val (historyDuration, onHistoryDurationChange) =
        rememberPreference(
            HistoryDuration,
            defaultValue = HISTORY_DURATION_DEFAULT,
        )

    val (crossfadeEnabled, onCrossfadeEnabledChange) =
        rememberPreference(
            CrossfadeEnabledKey,
            defaultValue = false,
        )
    val (automixEnabled, onAutomixEnabledChange) =
        rememberPreference(
            AutomixEnabledKey,
            defaultValue = false,
        )
    val (automixPerformanceMode, onAutomixPerformanceModeChange) =
        rememberEnumPreference(
            AutomixPerformanceModeKey,
            defaultValue = AutomixPerformanceMode.BALANCED,
        )
    val (crossfadeDurationSeconds, onCrossfadeDurationSecondsChange) =
        rememberPreference(
            CrossfadeDurationKey,
            defaultValue = 5f,
        )
    val (crossfadeGapless, onCrossfadeGaplessChange) =
        rememberPreference(
            CrossfadeGaplessKey,
            defaultValue = true,
        )

    // Artwork sources. The Tidal toggle defaults to false so users do not get unexpected Tidal
    // network traffic; DataStore only stores values the user explicitly changed, so an explicit
    // choice is preserved either way.
    val (archiveTuneCanvasEnabled, onArchiveTuneCanvasEnabledChange) =
        rememberPreference(
            ArchiveTuneCanvasKey,
            defaultValue = false,
        )
    // Spotify Canvas: fetch the official Spotify Canvas looping video for the current song
    // using its YouTube Music video ID via https://mlc.kouzu.in/api/canvas?id=<videoId>.
    // Defaults to false so existing users don't see surprise network traffic / video playback
    // until they explicitly opt in.
    val (spotifyCanvasEnabled, onSpotifyCanvasEnabledChange) =
        rememberPreference(
            SpotifyCanvasKey,
            defaultValue = false,
        )
    // Extra Spotify Canvas resolver endpoints, one per line. Every community canvas API on
    // GitHub is a self-hosted wrapper around Spotify's own canvaz-cache endpoint and needs the
    // operator's own sp_dc cookie, so there is no stable public instance worth hardcoding —
    // the user supplies whichever instances they have access to and they are tried in order.
    val (canvasResolverEndpointsRaw, onCanvasResolverEndpointsChange) =
        rememberPreference(
            CanvasResolverEndpointsKey,
            defaultValue = "",
        )
    val (tidalEnabled, _) =
        rememberPreference(
            TidalEnabledKey,
            defaultValue = true,
        )
    val (tidalArtworkEnabled, onTidalArtworkEnabledChange) =
        rememberPreference(
            TidalArtworkFallbackEnabledKey,
            defaultValue = false,
        )

    // Artwork provider priority order. The user can rearrange the order in which artwork
    // providers are tried — whichever is on top gets the most priority. If the top provider
    // has no artwork for the current song, the resolver falls back to the next one, and so on.
    val (artworkProviderOrderStr, onArtworkProviderOrderStrChange) =
        rememberPreference(
            ArtworkProviderOrderKey,
            defaultValue = "",
        )
    val artworkProviderOrder =
        remember(artworkProviderOrderStr) {
            deserializeArtworkProviderOrder(artworkProviderOrderStr)
        }
    var showArtworkProviderOrderDialog by remember { mutableStateOf(false) }

    val (artistSeparators, onArtistSeparatorsChange) =
        rememberPreference(
            ArtistSeparatorsKey,
            defaultValue = ",;/&",
        )
    val (wakelockEnabled, onWakelockChange) =
        rememberPreference(
            WakelockKey,
            defaultValue = false,
        )
    val (swipeToSong, onSwipeToSongChange) =
        rememberPreference(
            SwipeToSongKey,
            defaultValue = false,
        )
    // Swipe-to-change-song: lets the user swipe the player thumbnail left/right to skip tracks.
    val (swipeThumbnail, onSwipeThumbnailChange) =
        rememberPreference(
            SwipeThumbnailKey,
            defaultValue = true,
        )
    val (swipeSensitivity, onSwipeSensitivityChange) =
        rememberPreference(
            SwipeSensitivityKey,
            defaultValue = 0.73f,
        )
    var showArtistSeparatorsDialog by remember { mutableStateOf(false) }
    var showTagsManagementDialog by remember { mutableStateOf(false) }

    if (showArtistSeparatorsDialog) {
        ArtistSeparatorsDialog(
            currentSeparators = artistSeparators,
            onDismiss = { showArtistSeparatorsDialog = false },
            onSave = { newSeparators ->
                onArtistSeparatorsChange(newSeparators)
                showArtistSeparatorsDialog = false
            },
        )
    }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.player_and_audio)) },
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
            // "Sources" group: music source + lyrics settings now live on the Playback page
            // (moved from the main settings list per Tasks 4 & 5). Each row navigates to its
            // own dedicated sub-page so the existing screens stay reachable.
            PreferenceGroup(
                title = stringResource(R.string.settings_section_player_content),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.source_settings)) },
                        description = stringResource(R.string.source_settings_subtitle),
                        icon = { Icon(painterResource(R.drawable.provider_tidal), null) },
                        onClick = { navController.navigate("settings/sources") },
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.lyrics)) },
                        description = stringResource(R.string.settings_lyrics_subtitle),
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        onClick = { navController.navigate("settings/lyrics") },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("enable_video_playback"),
                title = stringResource(R.string.video_playback),
            ) {
                item {
                    Column(modifier = positions.modifierFor("enable_video_playback")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.enable_video_playback)) },
                            description = stringResource(R.string.enable_video_playback_desc),
                            icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
                            checked = enableVideoPlayback,
                            onCheckedChange = onEnableVideoPlaybackChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("enable_pip_mode")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.enable_pip_mode)) },
                            description = stringResource(R.string.enable_pip_mode_desc),
                            icon = { Icon(painterResource(R.drawable.player_pip), null) },
                            checked = enablePipMode,
                            onCheckedChange = onEnablePipModeChange,
                            isEnabled = enableVideoPlayback,
                        )
                    }
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("low_data_mode"),
                title = stringResource(R.string.player),
            ) {
                item {
                    Column(modifier = positions.modifierFor("history_duration")) {
                        SliderPreference(
                            title = { Text(stringResource(R.string.history_duration)) },
                            icon = { Icon(painterResource(R.drawable.history), null) },
                            value = historyDuration,
                            onValueChange = onHistoryDurationChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("crossfade")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.audio_crossfade_title)) },
                            description = stringResource(R.string.audio_crossfade_description),
                            icon = { Icon(painterResource(R.drawable.animation), null) },
                            checked = crossfadeEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    onAudioOffloadChange(false)
                                    // Crossfade and automix are mutually exclusive: at most one
                                    // engine shapes a given track boundary.
                                    onAutomixEnabledChange(false)
                                    // A fade needs a second player and gain changes, neither of
                                    // which a bit-perfect path allows.
                                    releaseBitPerfectUsb()
                                }
                                onCrossfadeEnabledChange(enabled)
                            },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("automix")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.automix_title)) },
                            description =
                                stringResource(
                                    if (automixEnabled) R.string.automix_enabled_subtitle else R.string.automix_disabled_subtitle,
                                ),
                            icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                            checked = automixEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    onAudioOffloadChange(false)
                                    // Automix hands every transition to the analysis engine; the
                                    // manual crossfade slider stops applying.
                                    onCrossfadeEnabledChange(false)
                                    releaseBitPerfectUsb()
                                }
                                onAutomixEnabledChange(enabled)
                            },
                        )
                    }
                }

                if (automixEnabled) {
                    item {
                        Column(modifier = positions.modifierFor("automix_performance")) {
                            EnumListPreference(
                                title = { Text(stringResource(R.string.automix_performance_title)) },
                                description = stringResource(R.string.automix_performance_subtitle),
                                icon = { Icon(painterResource(R.drawable.tune), null) },
                                selectedValue = automixPerformanceMode,
                                onValueSelected = onAutomixPerformanceModeChange,
                                valueText = {
                                    when (it) {
                                        AutomixPerformanceMode.EFFICIENT -> stringResource(R.string.automix_performance_efficient)
                                        AutomixPerformanceMode.BALANCED -> stringResource(R.string.automix_performance_balanced)
                                        AutomixPerformanceMode.PERFORMANCE -> stringResource(R.string.automix_performance_max)
                                    }
                                },
                            )
                        }
                    }
                }

                item {
                    CrossfadeSliderPreference(
                        valueSeconds = crossfadeDurationSeconds,
                        onValueChange = onCrossfadeDurationSecondsChange,
                        isEnabled = crossfadeEnabled,
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("crossfade_gapless")) {
                        SwitchPreference(
                            modifier = positions.modifierFor("crossfade_gapless_title"),
                            title = { Text(stringResource(R.string.crossfade_gapless_title)) },
                            description = stringResource(R.string.crossfade_gapless_description),
                            icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                            checked = crossfadeGapless,
                            onCheckedChange = onCrossfadeGaplessChange,
                            isEnabled = crossfadeEnabled,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("skip_silence")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.skip_silence)) },
                            icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                            checked = skipSilence,
                            onCheckedChange = onSkipSilenceChange,
                            isEnabled = !audioOffload,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("audio_normalization")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.audio_normalization)) },
                            icon = { Icon(painterResource(R.drawable.volume_up), null) },
                            checked = audioNormalization,
                            onCheckedChange = onAudioNormalizationChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("sponsor_block")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.sponsor_block)) },
                            description = stringResource(R.string.sponsor_block_description),
                            icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                            checked = sponsorBlockEnabled,
                            onCheckedChange = onSponsorBlockEnabledChange,
                        )
                    }
                }

                if (sponsorBlockEnabled) {
                    item {
                        Column(modifier = positions.modifierFor("sponsor_block_categories")) {
                            SponsorBlockCategory.entries.forEach { category ->
                                SwitchPreference(
                                    title = { Text(stringResource(category.titleRes())) },
                                    icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                                    checked = category.apiName in sponsorBlockCategories,
                                    onCheckedChange = { checked ->
                                        onSponsorBlockCategoriesChange(
                                            if (checked) {
                                                sponsorBlockCategories + category.apiName
                                            } else {
                                                sponsorBlockCategories - category.apiName
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    Column(modifier = positions.modifierFor("audio_quality")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.audio_quality)) },
                            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                            description = stringResource(R.string.audio_quality_description),
                            selectedValue = audioQuality,
                            onValueSelected = onAudioQualityChange,
                            valueText = {
                                when (it) {
                                    AudioQuality.HIGHEST -> stringResource(R.string.audio_quality_max)
                                    AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                                    AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                                    AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                                }
                            },
                        )
                    }
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.audio_authenticity_skip_upscaled_title)) },
                        description = stringResource(R.string.audio_authenticity_skip_upscaled_description),
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        checked = rejectSuspectedUpscaledAudio,
                        onCheckedChange = onRejectSuspectedUpscaledAudioChange,
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.audio_offload)) },
                        description = stringResource(R.string.audio_offload_desc),
                        icon = { Icon(painterResource(R.drawable.speed), null) },
                        checked = audioOffload,
                        onCheckedChange = { enabled ->
                            onAudioOffloadChange(enabled)
                            if (enabled) {
                                onSkipSilenceChange(false)
                                onCrossfadeEnabledChange(false)
                                onAutomixEnabledChange(false)
                                releaseBitPerfectUsb()
                            }
                        },
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("bit_perfect_usb")) {
                        val bitPerfectDescription =
                            when {
                                !BitPerfectUsbOutput.isPlatformSupported ->
                                    stringResource(R.string.bit_perfect_usb_unsupported)
                                !bitPerfectUsb -> stringResource(R.string.bit_perfect_usb_desc)
                                !BitPerfectUsbOutput.sinkActive ->
                                    stringResource(R.string.bit_perfect_usb_restart)
                                else ->
                                    when (val status = bitPerfectStatus) {
                                        is BitPerfectUsbOutput.Status.Active ->
                                            stringResource(
                                                R.string.bit_perfect_usb_active,
                                                status.deviceName.ifBlank { stringResource(R.string.bit_perfect_usb_default_device) },
                                                status.format,
                                            )
                                        is BitPerfectUsbOutput.Status.Fallback ->
                                            stringResource(R.string.bit_perfect_usb_fallback, status.reason)
                                        is BitPerfectUsbOutput.Status.Ready ->
                                            stringResource(
                                                R.string.bit_perfect_usb_ready,
                                                status.deviceName.ifBlank { stringResource(R.string.bit_perfect_usb_default_device) },
                                            )
                                        BitPerfectUsbOutput.Status.NoDevice ->
                                            stringResource(R.string.bit_perfect_usb_no_device)
                                        else -> stringResource(R.string.bit_perfect_usb_desc)
                                    }
                            }
                        SwitchPreference(
                            title = { Text(stringResource(R.string.bit_perfect_usb_title)) },
                            description = bitPerfectDescription,
                            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                            checked = bitPerfectUsb && BitPerfectUsbOutput.isPlatformSupported,
                            isEnabled = BitPerfectUsbOutput.isPlatformSupported,
                            onCheckedChange = { enabled ->
                                onBitPerfectUsbChange(enabled)
                                if (enabled) {
                                    onAudioOffloadChange(false)
                                    onCrossfadeEnabledChange(false)
                                    onAutomixEnabledChange(false)
                                } else if (BitPerfectUsbOutput.sinkActive) {
                                    // Release the DAC now; the players keep their float sink until
                                    // the service restarts, which plays normally without a preference.
                                    BitPerfectUsbOutput.setEnabled(bitPerfectContext, false)
                                }
                            },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("seek_seconds")) {
                        SwitchPreference(
                            modifier = positions.modifierFor("seek_seconds_addup"),
                            title = { Text(stringResource(R.string.seek_seconds_addup)) },
                            description = stringResource(R.string.seek_seconds_addup_description),
                            icon = { Icon(painterResource(R.drawable.arrow_forward), null) },
                            checked = seekExtraSeconds,
                            onCheckedChange = onSeekExtraSeconds,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("pause_mute")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.pause_on_device_mute)) },
                            description = stringResource(R.string.pause_on_device_mute_desc),
                            icon = { Icon(painterResource(R.drawable.volume_off), null) },
                            checked = pauseOnDeviceMute,
                            onCheckedChange = onPauseOnDeviceMuteChange,
                        )
                    }
                }

                item(visible = pauseOnDeviceMute) {
                    val context = LocalContext.current
                    val disabledLabel = stringResource(R.string.device_mute_recovery_volume_disabled)
                    val recoveryVolumeText =
                        remember(context, disabledLabel) {
                            { value: Int ->
                                if (value == 0) {
                                    disabledLabel
                                } else {
                                    context.getString(R.string.percentage_format, value)
                                }
                            }
                        }
                    Column(modifier = positions.modifierFor("device_mute_recovery_volume")) {
                        NumberPickerPreference(
                            title = { Text(stringResource(R.string.device_mute_recovery_volume)) },
                            icon = { Icon(painterResource(R.drawable.volume_up), null) },
                            value = deviceMutePlaybackRecoveryVolume,
                            onValueChange = onDeviceMutePlaybackRecoveryVolumeChange,
                            minValue = 0,
                            maxValue = 100,
                            valueText = recoveryVolumeText,
                            isEnabled = pauseOnDeviceMute,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("bluetooth_auto_start")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.auto_start_on_bluetooth)) },
                            description = stringResource(R.string.auto_start_on_bluetooth_desc),
                            icon = { Icon(painterResource(R.drawable.bluetooth), null) },
                            checked = autoStartOnBluetooth,
                            onCheckedChange = onAutoStartOnBluetoothChange,
                        )
                    }
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("archive_tune_canvas"),
                title = stringResource(R.string.tidal_artwork),
            ) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.archivetune_canvas)) },
                        description = stringResource(R.string.archivetune_canvas_desc),
                        icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                        checked = archiveTuneCanvasEnabled,
                        onCheckedChange = onArchiveTuneCanvasEnabledChange,
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("spotify_canvas")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.spotify_canvas)) },
                            description = stringResource(R.string.spotify_canvas_desc),
                            icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
                            checked = spotifyCanvasEnabled,
                            onCheckedChange = onSpotifyCanvasEnabledChange,
                        )
                    }
                }

                item(visible = spotifyCanvasEnabled) {
                    var showCanvasResolversDialog by remember { mutableStateOf(false) }
                    val configuredResolvers =
                        remember(canvasResolverEndpointsRaw) {
                            CanvasResolverEndpoints.parse(canvasResolverEndpointsRaw)
                        }
                    Column(modifier = positions.modifierFor("canvas_resolvers")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.canvas_resolvers)) },
                            description =
                                if (configuredResolvers.isEmpty()) {
                                    stringResource(R.string.canvas_resolvers_none)
                                } else {
                                    stringResource(
                                        R.string.canvas_resolvers_count,
                                        configuredResolvers.size,
                                    )
                                },
                            icon = { Icon(painterResource(R.drawable.solar_server_linear), null) },
                            onClick = { showCanvasResolversDialog = true },
                        )
                    }
                    if (showCanvasResolversDialog) {
                        TextFieldDialog(
                            onDismiss = { showCanvasResolversDialog = false },
                            title = { Text(stringResource(R.string.canvas_resolvers)) },
                            placeholder = {
                                Text(stringResource(R.string.canvas_resolvers_description))
                            },
                            textFieldValue = canvasResolverEndpointsRaw,
                            onTextFieldValueChange = onCanvasResolverEndpointsChange,
                            singleLine = false,
                            maxLines = 8,
                            // Blank is valid: it means "built-in resolver only".
                            isInputValid = { true },
                            onDone = { raw ->
                                onCanvasResolverEndpointsChange(
                                    CanvasResolverEndpoints.serialize(
                                        CanvasResolverEndpoints.parse(raw),
                                    ),
                                )
                            },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("tidal_artwork_fallback")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.tidal_artwork_fallback)) },
                            description =
                                stringResource(
                                    if (tidalEnabled) {
                                        R.string.tidal_artwork_fallback_description
                                    } else {
                                        R.string.tidal_artwork_fallback_unavailable
                                    },
                                ),
                            icon = { Icon(painterResource(R.drawable.image), null) },
                            checked = tidalArtworkEnabled,
                            onCheckedChange = onTidalArtworkEnabledChange,
                            isEnabled = tidalEnabled,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("artwork_priority")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.artwork_priority)) },
                            description = artworkProviderOrder.firstOrNull()?.displayName(),
                            icon = { Icon(painterResource(R.drawable.filter_alt), null) },
                            onClick = { showArtworkProviderOrderDialog = true },
                        )
                    }
                }
            }

            if (showArtworkProviderOrderDialog) {
                ArtworkProviderOrderDialog(
                    initialOrder = artworkProviderOrder,
                    onDismiss = { showArtworkProviderOrderDialog = false },
                    onConfirm = { newOrder ->
                        onArtworkProviderOrderStrChange(newOrder.joinToString(",") { it.name })
                        showArtworkProviderOrderDialog = false
                    },
                )
            }

            PreferenceGroup(
                modifier = positions.modifierFor("persistent_queue"),
                title = stringResource(R.string.queue),
            ) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.persistent_queue)) },
                        description = stringResource(R.string.persistent_queue_desc),
                        icon = { Icon(painterResource(R.drawable.queue_music), null) },
                        checked = persistentQueue,
                        onCheckedChange = onPersistentQueueChange,
                    )
                }

                item {
                    Column(modifier = positions.modifierFor("permanent_shuffle")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.permanent_shuffle)) },
                            description = stringResource(R.string.permanent_shuffle_desc),
                            icon = { Icon(painterResource(R.drawable.shuffle), null) },
                            checked = permanentShuffle,
                            onCheckedChange = onPermanentShuffleChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("swipe_up_queue")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.swipe_up_to_open_queue)) },
                            description = stringResource(R.string.swipe_up_to_open_queue_desc),
                            icon = { Icon(painterResource(R.drawable.queue_music), null) },
                            checked = swipeUpToOpenQueue,
                            onCheckedChange = onSwipeUpToOpenQueueChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("queue_after_manual")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.queue_add_after_manual)) },
                            description = stringResource(R.string.queue_add_after_manual_desc),
                            icon = { Icon(painterResource(R.drawable.queue_music), null) },
                            checked = queueAddAfterManual,
                            onCheckedChange = onQueueAddAfterManualChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("swipe_to_song")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.swipe_song_to_add)) },
                            icon = { Icon(painterResource(R.drawable.swipe), null) },
                            checked = swipeToSong,
                            onCheckedChange = onSwipeToSongChange,
                        )
                    }
                }

                // Swipe-to-change-song: belongs with the other queue/skip behaviours, including its
                // sensitivity dialog.
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_swipe_thumbnail"),
                        title = { Text(stringResource(R.string.enable_swipe_thumbnail)) },
                        icon = { Icon(painterResource(R.drawable.swipe), null) },
                        checked = swipeThumbnail,
                        onCheckedChange = onSwipeThumbnailChange,
                    )
                }

                item(visible = swipeThumbnail) {
                    var showSensitivityDialog by rememberSaveable { mutableStateOf(false) }

                    if (showSensitivityDialog) {
                        var tempSensitivity by remember { mutableFloatStateOf(swipeSensitivity) }

                        DefaultDialog(
                            onDismiss = {
                                tempSensitivity = swipeSensitivity
                                showSensitivityDialog = false
                            },
                            buttons = {
                                TextButton(
                                    onClick = { tempSensitivity = 0.73f },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(R.string.reset))
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                TextButton(
                                    onClick = {
                                        tempSensitivity = swipeSensitivity
                                        showSensitivityDialog = false
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                                TextButton(
                                    onClick = {
                                        onSwipeSensitivityChange(tempSensitivity)
                                        showSensitivityDialog = false
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.swipe_sensitivity),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(bottom = playerAwareBottomPadding + 16.dp),
                                )

                                Text(
                                    text = stringResource(
                                        R.string.sensitivity_percentage,
                                        (tempSensitivity * 100).roundToInt(),
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(bottom = playerAwareBottomPadding + 16.dp),
                                )

                                Slider(
                                    value = tempSensitivity,
                                    onValueChange = { tempSensitivity = it },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    Column(modifier = positions.modifierFor("swipe_sensitivity")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.swipe_sensitivity)) },
                            description = stringResource(
                                R.string.sensitivity_percentage,
                                (swipeSensitivity * 100).roundToInt(),
                            ),
                            icon = { Icon(painterResource(R.drawable.tune), null) },
                            onClick = { showSensitivityDialog = true },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("auto_skip_error")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
                            description = stringResource(R.string.auto_skip_next_on_error_desc),
                            icon = { Icon(painterResource(R.drawable.skip_next), null) },
                            checked = autoSkipNextOnError,
                            onCheckedChange = onAutoSkipNextOnErrorChange,
                        )
                    }
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("audio_offload"),
                title = stringResource(R.string.misc),
            ) {
                item {
                    Column(modifier = positions.modifierFor("stop_task_clear")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
                            icon = { Icon(painterResource(R.drawable.clear_all), null) },
                            checked = stopMusicOnTaskClear,
                            onCheckedChange = onStopMusicOnTaskClearChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("wakelock")) {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.wakelock)) },
                            description = stringResource(R.string.wakelock_desc),
                            icon = { Icon(painterResource(R.drawable.bolt), null) },
                            checked = wakelockEnabled,
                            onCheckedChange = onWakelockChange,
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("artist_separators")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.artist_separators)) },
                            description = artistSeparators.map { "\"$it\"" }.joinToString("  "),
                            icon = { Icon(painterResource(R.drawable.artist), null) },
                            onClick = { showArtistSeparatorsDialog = true },
                        )
                    }
                }

                item {
                    Column(modifier = positions.modifierFor("manage_playlist_tags")) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.manage_playlist_tags)) },
                            description = stringResource(R.string.manage_playlist_tags_desc),
                            icon = { Icon(painterResource(R.drawable.style), null) },
                            onClick = { showTagsManagementDialog = true },
                        )
                    }
                }
            }
        }
    }
}

internal fun PreferredArtworkProvider.displayName(): String =
    when (this) {
        PreferredArtworkProvider.LOCAL_EMBEDDED -> "Local embedded"
        PreferredArtworkProvider.ORIGINAL_METADATA -> "Original metadata"
        PreferredArtworkProvider.TIDAL -> "Tidal"
        PreferredArtworkProvider.SPOTIFY_CANVAS -> "Spotify Canvas"
        PreferredArtworkProvider.ARCHIVETUNE_CANVAS -> "ArchiveTune Canvas"
    }

@Composable
internal fun ArtworkProviderOrderDialog(
    initialOrder: List<PreferredArtworkProvider>,
    onDismiss: () -> Unit,
    onConfirm: (List<PreferredArtworkProvider>) -> Unit,
) {
    val providers = remember { mutableStateListOf(*initialOrder.toTypedArray()) }
    val lazyListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val item = providers.removeAt(from.index)
            providers.add(to.index, item)
        }

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onConfirm(providers.toList()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.artwork_priority),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(
                state = lazyListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
            ) {
                itemsIndexed(providers, key = { _, item -> item.name }) { index, provider ->
                    ReorderableItem(reorderableState, key = provider.name) {
                        val isFirst = index == 0
                        val containerColor =
                            if (isFirst) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        val contentColor =
                            if (isFirst) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (index < providers.size - 1) 4.dp else 0.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(containerColor)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = provider.displayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                painter = painterResource(R.drawable.drag_handle),
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.6f),
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .draggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.StringRes
private fun SponsorBlockCategory.titleRes(): Int =
    when (this) {
        SponsorBlockCategory.SPONSOR -> R.string.sponsor_block_category_sponsor
        SponsorBlockCategory.SELFPROMO -> R.string.sponsor_block_category_selfpromo
        SponsorBlockCategory.INTERACTION -> R.string.sponsor_block_category_interaction
        SponsorBlockCategory.INTRO -> R.string.sponsor_block_category_intro
        SponsorBlockCategory.OUTRO -> R.string.sponsor_block_category_outro
        SponsorBlockCategory.PREVIEW -> R.string.sponsor_block_category_preview
        SponsorBlockCategory.MUSIC_OFFTOPIC -> R.string.sponsor_block_category_music_offtopic
        SponsorBlockCategory.FILLER -> R.string.sponsor_block_category_filler
    }
