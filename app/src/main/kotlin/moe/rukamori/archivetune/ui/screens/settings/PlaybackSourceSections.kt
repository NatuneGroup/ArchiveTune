/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Streaming-source sections rendered inline inside Player & Audio settings:
 *  - a single lyrics-style drag-to-reorder "preferred sources" picker (top = preferred),
 *  - a common section (YouTube history sync),
 *  - and per-source sections (YouTube note, Tidal, Qobuz).
 *
 * Account login / instance / API management lives in the Integration section, not here.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.AppleMusicQuality
import moe.rukamori.archivetune.constants.AppleMusicQualityKey
import moe.rukamori.archivetune.constants.DeezerAudioQuality
import moe.rukamori.archivetune.constants.DeezerAudioQualityKey
import moe.rukamori.archivetune.constants.DeezerEnabledKey
import moe.rukamori.archivetune.constants.AmazonEnabledKey
import moe.rukamori.archivetune.constants.QqMusicEnabledKey
import moe.rukamori.archivetune.constants.AppleMusicSourceEnabledKey
import moe.rukamori.archivetune.constants.JioSaavnEnabledKey
import moe.rukamori.archivetune.constants.SaavnAudioQuality
import moe.rukamori.archivetune.constants.SaavnAudioQualityKey
import moe.rukamori.archivetune.constants.QobuzAudioQuality
import moe.rukamori.archivetune.constants.QobuzAudioQualityKey
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.QobuzBackupEnabledKey
import moe.rukamori.archivetune.constants.TidalAccountFirstKey
import moe.rukamori.archivetune.constants.TidalAnimatedCoversEnabledKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.TidalAudioQualityKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AutoChoosePlaybackClientKey
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.DefaultMetadataSourceKey
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.MetadataSource
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.YouTubeSourceEnabledKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.PoolAccountManager
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun AudioSourceType.displayName(context: android.content.Context): String =
    when (this) {
        AudioSourceType.TIDAL -> context.getString(R.string.source_tidal)
        AudioSourceType.QOBUZ -> context.getString(R.string.source_qobuz)
        AudioSourceType.QOBUZ_BACKUP -> context.getString(R.string.source_qobuz_backup)
        AudioSourceType.DEEZER -> context.getString(R.string.source_deezer)
        AudioSourceType.APPLE -> context.getString(R.string.source_apple_music)
        AudioSourceType.AMAZON -> context.getString(R.string.source_amazon)
        AudioSourceType.QQ -> context.getString(R.string.source_qq_music)
        AudioSourceType.JIOSAAVN -> context.getString(R.string.source_jiosaavn)
        AudioSourceType.YOUTUBE -> context.getString(R.string.source_youtube)
    }

private fun AudioSourceType.iconRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.drawable.provider_tidal
        AudioSourceType.QOBUZ -> R.drawable.provider_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.drawable.provider_qobuz
        AudioSourceType.DEEZER -> R.drawable.provider_deezer
        AudioSourceType.APPLE -> R.drawable.ic_music
        // No dedicated Amazon Music mark ships in drawable/ yet; ic_music is the same
        // stand-in APPLE uses above for the same reason.
        AudioSourceType.AMAZON -> R.drawable.ic_music
        // Same stand-in for QQ Music.
        AudioSourceType.QQ -> R.drawable.ic_music
        AudioSourceType.JIOSAAVN -> R.drawable.provider_jiosaavn
        AudioSourceType.YOUTUBE -> R.drawable.play
    }

/** Renders all streaming-source preference groups inline in the caller's scrolling Column. */
@Composable
internal fun PlaybackSourceSections(
    navController: NavController,
    positions: PreferencePositions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (sourceOrderRaw, onSourceOrderChange) = rememberPreference(AudioSourceOrderKey, "")
    val (tidalEnabled, onTidalEnabledChange) = rememberPreference(TidalEnabledKey, true)
    val (qobuzEnabled, onQobuzEnabledChangeRaw) = rememberPreference(QobuzEnabledKey, false)
    val (deezerEnabled, onDeezerEnabledChangeRaw) = rememberPreference(DeezerEnabledKey, false)
    val (appleMusicEnabled, onAppleMusicEnabledChangeRaw) = rememberPreference(AppleMusicSourceEnabledKey, false)
    val (amazonEnabled, onAmazonEnabledChangeRaw) = rememberPreference(AmazonEnabledKey, false)
    val (qqMusicEnabled, onQqMusicEnabledChangeRaw) = rememberPreference(QqMusicEnabledKey, false)
    val (deezerQuality, onDeezerQualityChange) =
        rememberEnumPreference(DeezerAudioQualityKey, DeezerAudioQuality.FLAC)
    val (jioSaavnEnabled, onJioSaavnEnabledChange) = rememberPreference(JioSaavnEnabledKey, false)

    // YouTube is a normal toggleable source now; default ON to preserve historical behaviour.
    val (youTubeEnabled, onYouTubeEnabledChange) = rememberPreference(YouTubeSourceEnabledKey, true)
    val (saavnQuality, onSaavnQualityChange) =
        rememberEnumPreference(SaavnAudioQualityKey, SaavnAudioQuality.QUALITY_320)
    val (defaultMetadataSource, onDefaultMetadataSourceChange) =
        rememberEnumPreference(DefaultMetadataSourceKey, MetadataSource.YOUTUBE)
    val (defaultSearchSource, onDefaultSearchSourceChange) =
        rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)


    // When the user enables a pool-backed source (Qobuz or Deezer), automatically
    // trigger a pool refresh in the background. This ensures the latest pool
    // accounts are loaded before the user tries to play a song through that
    // source — fixing the "Qobuz server through the pool doesn't work even when
    // accounts are already added" bug where the pool cache was stale or empty
    // when the user enabled the source.
    val onQobuzEnabledChange: (Boolean) -> Unit = { enabled ->
        onQobuzEnabledChangeRaw(enabled)
        if (enabled && PoolAccountManager.isEnabled) {
            scope.launch(Dispatchers.IO) {
                runCatching { PoolAccountManager.refresh(context, force = false) }
            }
        }
    }
    val onDeezerEnabledChange: (Boolean) -> Unit = { enabled ->
        onDeezerEnabledChangeRaw(enabled)
        if (enabled && PoolAccountManager.isEnabled) {
            scope.launch(Dispatchers.IO) {
                runCatching { PoolAccountManager.refresh(context, force = false) }
            }
        }
    }
    val onAppleMusicEnabledChange: (Boolean) -> Unit = { enabled ->
        onAppleMusicEnabledChangeRaw(enabled)
        if (enabled && PoolAccountManager.isEnabled) {
            scope.launch(Dispatchers.IO) {
                runCatching { PoolAccountManager.refresh(context, force = false) }
            }
        }
    }
    val onAmazonEnabledChange: (Boolean) -> Unit = { enabled ->
        onAmazonEnabledChangeRaw(enabled)
        if (enabled) {
            onSourceOrderChange(AudioSourceConfig.withSourceAdded(sourceOrderRaw, AudioSourceType.AMAZON))
        }
        if (enabled && PoolAccountManager.isEnabled) {
            scope.launch(Dispatchers.IO) {
                runCatching { PoolAccountManager.refresh(context, force = false) }
            }
        }
    }
    val onQqMusicEnabledChange: (Boolean) -> Unit = { enabled ->
        onQqMusicEnabledChangeRaw(enabled)
        if (enabled) {
            onSourceOrderChange(AudioSourceConfig.withSourceAdded(sourceOrderRaw, AudioSourceType.QQ))
        }
    }

    val (tidalAccountFirst, onTidalAccountFirstChange) = rememberPreference(TidalAccountFirstKey, true)
    val (audioQuality, onAudioQualityChange) =
        rememberEnumPreference(TidalAudioQualityKey, TidalAudioQuality.FLAC)

    // YouTube-specific playback state
    val (ytAudioQuality, onYtAudioQualityChange) =
        rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)
    val (playerStreamClient, onPlayerStreamClientChange) =
        rememberEnumPreference(PlayerStreamClientKey, defaultValue = PlayerStreamClient.WEB_REMIX)
    val (autoChoosePlaybackClient, onAutoChoosePlaybackClientChange) =
        rememberPreference(AutoChoosePlaybackClientKey, true)
    val playerStreamClients =
        remember { PlayerStreamClient.entries }
    val selectedPlayerStreamClient =
        if (playerStreamClient in playerStreamClients) playerStreamClient
        else PlayerStreamClient.WEB_REMIX

    val (qobuzQuality, onQobuzQualityChange) =
        rememberEnumPreference(QobuzAudioQualityKey, QobuzAudioQuality.FLAC)
    val (qobuzBackupEnabled, onQobuzBackupEnabledChange) = rememberPreference(QobuzBackupEnabledKey, false)
    val (appleMusicQuality, onAppleMusicQualityChange) =
        rememberEnumPreference(AppleMusicQualityKey, AppleMusicQuality.LOSSLESS)
    // The Tidal artwork-fetching toggle lives in Player Settings → Artwork (same key).
    val (animatedCovers, onAnimatedCoversChange) =
        rememberPreference(TidalAnimatedCoversEnabledKey, false)

    val sourceOrder =
        remember(sourceOrderRaw) {
            AudioSourceConfig.parseOrder(sourceOrderRaw.ifBlank { null })
        }

    fun isEnabled(source: AudioSourceType): Boolean =
        when (source) {
            AudioSourceType.TIDAL -> tidalEnabled
            AudioSourceType.QOBUZ -> qobuzEnabled
            AudioSourceType.QOBUZ_BACKUP -> qobuzBackupEnabled
            AudioSourceType.DEEZER -> deezerEnabled
            AudioSourceType.APPLE -> appleMusicEnabled
            AudioSourceType.AMAZON -> amazonEnabled
            AudioSourceType.QQ -> qqMusicEnabled
            AudioSourceType.JIOSAAVN -> jioSaavnEnabled
            AudioSourceType.YOUTUBE -> youTubeEnabled
        }

    var showOrderDialog by rememberSaveable { mutableStateOf(false) }

    // Dragging a source to the top is the strongest possible signal the user wants it — a
    // disabled top source would be silently skipped by the resolver, which is exactly how
    // "I put Qobuz first but still got YouTube" happens. Auto-enable the new top source on
    // confirm so the order dialog and the enable toggles can never disagree again.
    fun onOrderConfirm(newOrder: List<AudioSourceType>) {
        newOrder.firstOrNull()?.let { top ->
            when (top) {
                AudioSourceType.TIDAL -> if (!tidalEnabled) onTidalEnabledChange(true)
                AudioSourceType.QOBUZ -> if (!qobuzEnabled) onQobuzEnabledChange(true)
                AudioSourceType.QOBUZ_BACKUP -> if (!qobuzBackupEnabled) onQobuzBackupEnabledChange(true)
                AudioSourceType.DEEZER -> if (!deezerEnabled) onDeezerEnabledChange(true)
                AudioSourceType.APPLE -> if (!appleMusicEnabled) onAppleMusicEnabledChange(true)
                AudioSourceType.AMAZON -> if (!amazonEnabled) onAmazonEnabledChange(true)
                AudioSourceType.QQ -> if (!qqMusicEnabled) onQqMusicEnabledChange(true)
                AudioSourceType.JIOSAAVN -> if (!jioSaavnEnabled) onJioSaavnEnabledChange(true)
                AudioSourceType.YOUTUBE -> if (!youTubeEnabled) onYouTubeEnabledChange(true)
            }
        }
        onSourceOrderChange(newOrder.joinToString(",") { it.name })
        showOrderDialog = false
    }

    if (showOrderDialog) {
        SourceOrderDialog(
            initialOrder = sourceOrder,
            isEnabled = ::isEnabled,
            onDismiss = { showOrderDialog = false },
            onConfirm = ::onOrderConfirm,
        )
    }

    val preferred = sourceOrder.firstOrNull { isEnabled(it) } ?: AudioSourceType.YOUTUBE

    PreferenceGroup(title = stringResource(R.string.playback_sources)) {
        item {
            PreferenceEntry(
                modifier = positions.modifierFor("preferred_sources"),
                title = { Text(stringResource(R.string.preferred_sources)) },
                description = preferred.displayName(context),
                icon = { Icon(painterResource(preferred.iconRes()), null) },
                onClick = { showOrderDialog = true },
            )
        }
    }
    PreferenceGroup(title = stringResource(R.string.catalog_sources)) {
        item {
            EnumListPreference(
                modifier = positions.modifierFor("default_metadata_source"),
                title = { Text(stringResource(R.string.default_metadata_source)) },
                description = stringResource(R.string.default_metadata_source_desc),
                icon = { Icon(painterResource(R.drawable.album), null) },
                selectedValue = defaultMetadataSource,
                valueText = {
                    when (it) {
                        MetadataSource.YOUTUBE -> stringResource(R.string.metadata_source_youtube)
                        MetadataSource.SPOTIFY -> stringResource(R.string.metadata_source_spotify)
                    }
                },
                onValueSelected = onDefaultMetadataSourceChange,
            )
        }

        item {
            EnumListPreference(
                modifier = positions.modifierFor("default_search_source"),
                title = { Text(stringResource(R.string.default_search_source)) },
                description = stringResource(R.string.default_search_source_desc),
                icon = { Icon(painterResource(R.drawable.language), null) },
                selectedValue = defaultSearchSource,
                valueText = {
                    when (it) {
                        SearchProvider.YOUTUBE -> stringResource(R.string.search_source_youtube)
                        SearchProvider.SPOTIFY -> stringResource(R.string.search_source_spotify)
                        SearchProvider.APPLE_MUSIC -> stringResource(R.string.search_source_apple_music)
                    }
                },
                onValueSelected = onDefaultSearchSourceChange,
            )
        }

        item {
            PreferenceEntry(
                modifier = positions.modifierFor("spotify_catalog_source"),
                title = { Text(stringResource(R.string.spotify_catalog_source)) },
                description = stringResource(R.string.spotify_catalog_source_desc),
                icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                onClick = { navController.navigate("settings/integration") },
            )
        }
    }


    PreferenceGroup(title = stringResource(R.string.source_youtube)) {
        // Enable/disable YouTube as a resolution source. Default ON — matches the historical
        // "YouTube is the implicit fallback" behaviour, so existing users see no change until
        // they deliberately switch it off.
        item {
            SwitchPreference(
                modifier = positions.modifierFor("youtube_source_enable"),
                title = { Text(stringResource(R.string.source_youtube)) },
                description = stringResource(R.string.source_youtube_enable_description),
                icon = { Icon(painterResource(R.drawable.play), null) },
                checked = youTubeEnabled,
                onCheckedChange = onYouTubeEnabledChange,
            )
        }
        item {
            EnumListPreference(
                title = { Text(stringResource(R.string.audio_quality)) },
                description = stringResource(R.string.audio_quality_description),
                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                selectedValue = ytAudioQuality,
                onValueSelected = onYtAudioQualityChange,
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
        item {
            SwitchPreference(
                modifier = positions.modifierFor("auto_choose_playback_client"),
                title = { Text(stringResource(R.string.auto_choose_playback_client)) },
                description =
                    stringResource(
                        if (autoChoosePlaybackClient) {
                            R.string.auto_choose_playback_client_enabled_note
                        } else {
                            R.string.auto_choose_playback_client_disabled_note
                        },
                    ),
                icon = { Icon(painterResource(R.drawable.tune), null) },
                checked = autoChoosePlaybackClient,
                onCheckedChange = onAutoChoosePlaybackClientChange,
            )
        }


        item {
            ListPreference(
                modifier = positions.modifierFor("player_stream_client"),
                title = { Text(stringResource(R.string.player_stream_client)) },
                description = stringResource(R.string.player_stream_client_desc),
                icon = { Icon(painterResource(R.drawable.integration), null) },
                selectedValue = selectedPlayerStreamClient,
                values = playerStreamClients,
                onValueSelected = onPlayerStreamClientChange,
                isEnabled = !autoChoosePlaybackClient,
                // Exhaustive on purpose: no `else` branch, so adding a client to
                // PlayerStreamClient fails the build here instead of silently rendering
                // every row with the Web Remix label.
                valueText = {
                    when (it) {
                        PlayerStreamClient.ANDROID_VR ->
                            stringResource(R.string.player_stream_client_android_vr)
                        PlayerStreamClient.WEB_REMIX ->
                            stringResource(R.string.player_stream_client_web_remix)
                        PlayerStreamClient.HI_RES_LOSSLESS ->
                            stringResource(R.string.player_stream_client_hi_res_lossless)
                        PlayerStreamClient.IOS ->
                            stringResource(R.string.player_stream_client_ios)
                        PlayerStreamClient.TVHTML5 ->
                            stringResource(R.string.player_stream_client_tvhtml5)
                        PlayerStreamClient.ANDROID_MUSIC ->
                            stringResource(R.string.player_stream_client_android_music)
                    }
                },
                valueDescription = {
                    when (it) {
                        PlayerStreamClient.ANDROID_VR ->
                            stringResource(R.string.player_stream_client_android_vr_desc)
                        PlayerStreamClient.WEB_REMIX ->
                            stringResource(R.string.player_stream_client_web_remix_desc)
                        PlayerStreamClient.HI_RES_LOSSLESS ->
                            stringResource(R.string.player_stream_client_hi_res_lossless_desc)
                        PlayerStreamClient.IOS ->
                            stringResource(R.string.player_stream_client_ios_desc)
                        PlayerStreamClient.TVHTML5 ->
                            stringResource(R.string.player_stream_client_tvhtml5_desc)
                        PlayerStreamClient.ANDROID_MUSIC ->
                            stringResource(R.string.player_stream_client_android_music_desc)
                    }
                },
            )
        }
    }

    PreferenceGroup(title = stringResource(R.string.tidal_specific)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("tidal_enable"),
                title = { Text(stringResource(R.string.tidal_enable)) },
                description = stringResource(R.string.tidal_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_tidal), null) },
                checked = tidalEnabled,
                onCheckedChange = onTidalEnabledChange,
            )
        }

        item {
            SwitchPreference(
                modifier = positions.modifierFor("tidal_account_first"),
                title = { Text(stringResource(R.string.tidal_account_first)) },
                description = stringResource(R.string.tidal_account_first_description),
                icon = { Icon(painterResource(R.drawable.token), null) },
                checked = tidalAccountFirst,
                onCheckedChange = onTidalAccountFirstChange,
                isEnabled = tidalEnabled,
            )
        }

        item {
            EnumListPreference(
                modifier = positions.modifierFor("tidal_audio_quality"),
                title = { Text(stringResource(R.string.tidal_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = audioQuality,
                onValueSelected = onAudioQualityChange,
                isEnabled = tidalEnabled,
                valueText = { quality ->
                    when (quality) {
                        TidalAudioQuality.AAC_320 -> stringResource(R.string.tidal_quality_aac_320)
                        TidalAudioQuality.FLAC -> stringResource(R.string.tidal_quality_flac)
                        TidalAudioQuality.HI_RES_LOSSLESS -> stringResource(R.string.tidal_quality_hires)
                    }
                },
            )
        }

        item {
            SwitchPreference(
                modifier = positions.modifierFor("tidal_animated_covers"),
                title = { Text(stringResource(R.string.tidal_animated_covers)) },
                description = stringResource(R.string.tidal_animated_covers_description),
                checked = animatedCovers,
                onCheckedChange = onAnimatedCoversChange,
                isEnabled = tidalEnabled,
            )
        }

        item {
            PreferenceEntry(
                modifier = positions.modifierFor("tidal_manage_instances"),
                title = { Text(stringResource(R.string.tidal_manage_instances)) },
                description = stringResource(R.string.manage_in_integration),
                icon = { Icon(painterResource(R.drawable.integration), null) },
                onClick = { navController.navigate("settings/integration") },
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.TIDAL)
        }
    }

    PreferenceGroup(title = stringResource(R.string.qobuz_specific)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("qobuz_enable"),
                title = { Text(stringResource(R.string.qobuz_enable)) },
                description = stringResource(R.string.qobuz_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_qobuz), null) },
                checked = qobuzEnabled,
                onCheckedChange = onQobuzEnabledChange,
            )
        }

        item {
            EnumListPreference(
                modifier = positions.modifierFor("qobuz_audio_quality"),
                title = { Text(stringResource(R.string.qobuz_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = qobuzQuality,
                onValueSelected = onQobuzQualityChange,
                isEnabled = qobuzEnabled,
                valueText = { quality ->
                    when (quality) {
                        QobuzAudioQuality.FLAC -> stringResource(R.string.qobuz_quality_flac)
                        QobuzAudioQuality.HI_RES -> stringResource(R.string.qobuz_quality_hires)
                        QobuzAudioQuality.MAX -> stringResource(R.string.qobuz_quality_max)
                    }
                },
            )
        }

        item {
            PreferenceEntry(
                modifier = positions.modifierFor("qobuz_manage_instances"),
                title = { Text(stringResource(R.string.qobuz_manage_instances)) },
                description = stringResource(R.string.manage_in_integration),
                icon = { Icon(painterResource(R.drawable.integration), null) },
                onClick = { navController.navigate("settings/integration") },
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.QOBUZ)
        }
    }

    PreferenceGroup(title = stringResource(R.string.qobuz_backup_specific)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("qobuz_backup_enable"),
                title = { Text(stringResource(R.string.qobuz_backup_enable)) },
                description = stringResource(R.string.qobuz_backup_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_qobuz), null) },
                checked = qobuzBackupEnabled,
                onCheckedChange = onQobuzBackupEnabledChange,
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.QOBUZ_BACKUP)
        }
    }

    PreferenceGroup(title = stringResource(R.string.applemusic_settings)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("applemusic_enable"),
                title = { Text(stringResource(R.string.applemusic_enable)) },
                description = stringResource(R.string.applemusic_enable_description),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                checked = appleMusicEnabled,
                onCheckedChange = onAppleMusicEnabledChange,
            )
        }
        item {
            EnumListPreference(
                title = { Text(stringResource(R.string.applemusic_quality)) },
                description = stringResource(R.string.applemusic_quality_desc),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                selectedValue = appleMusicQuality,
                onValueSelected = onAppleMusicQualityChange,
                valueText = {
                    when (it) {
                        AppleMusicQuality.AAC -> stringResource(R.string.applemusic_quality_aac)
                        AppleMusicQuality.LOSSLESS -> stringResource(R.string.applemusic_quality_lossless)
                        AppleMusicQuality.HI_RES_LOSSLESS -> stringResource(R.string.applemusic_quality_hires)
                    }
                },
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.applemusic_settings)) },
                description = stringResource(R.string.applemusic_helper_short),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                onClick = { navController.navigate("settings/applemusic") },
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.APPLE)
        }
    }

    PreferenceGroup(title = stringResource(R.string.deezer_specific)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("deezer_enable"),
                title = { Text(stringResource(R.string.deezer_enable)) },
                description = stringResource(R.string.deezer_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_deezer), null) },
                checked = deezerEnabled,
                onCheckedChange = onDeezerEnabledChange,
            )
        }

        item {
            EnumListPreference(
                modifier = positions.modifierFor("deezer_audio_quality"),
                title = { Text(stringResource(R.string.deezer_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = deezerQuality,
                onValueSelected = onDeezerQualityChange,
                isEnabled = deezerEnabled,
                valueText = { quality ->
                    when (quality) {
                        DeezerAudioQuality.FLAC -> stringResource(R.string.deezer_quality_flac)
                        DeezerAudioQuality.MP3_320 -> stringResource(R.string.deezer_quality_mp3_320)
                        DeezerAudioQuality.MP3_128 -> stringResource(R.string.deezer_quality_mp3_128)
                    }
                },
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.DEEZER)
        }
    }

    PreferenceGroup(title = stringResource(R.string.source_amazon)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("amazon_enable"),
                title = { Text(stringResource(R.string.source_amazon)) },
                description = stringResource(R.string.amazon_instances_empty),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                checked = amazonEnabled,
                onCheckedChange = onAmazonEnabledChange,
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.source_amazon)) },
                description = stringResource(R.string.amazon_instances_hint),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                onClick = { navController.navigate("settings/amazon") },
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.AMAZON)
        }
    }

    PreferenceGroup(title = stringResource(R.string.source_qq_music)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("qq_music_enable"),
                title = { Text(stringResource(R.string.source_qq_music)) },
                description = stringResource(R.string.qq_music_enabled_desc),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                checked = qqMusicEnabled,
                onCheckedChange = onQqMusicEnabledChange,
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.source_qq_music)) },
                description = stringResource(R.string.qq_music_enabled_desc),
                icon = { Icon(painterResource(R.drawable.ic_music), null) },
                onClick = { navController.navigate("settings/qqmusic") },
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.QQ)
        }
    }

    PreferenceGroup(title = stringResource(R.string.jiosaavn_specific)) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("jiosaavn_enable"),
                title = { Text(stringResource(R.string.jiosaavn_enable)) },
                description = stringResource(R.string.jiosaavn_enable_description),
                icon = { Icon(painterResource(R.drawable.play), null) },
                checked = jioSaavnEnabled,
                onCheckedChange = onJioSaavnEnabledChange,
            )
        }

        item {
            EnumListPreference(
                modifier = positions.modifierFor("jiosaavn_audio_quality"),
                title = { Text(stringResource(R.string.jiosaavn_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = saavnQuality,
                onValueSelected = onSaavnQualityChange,
                isEnabled = jioSaavnEnabled,
                valueText = { quality -> quality.toLabel() },
            )
        }

        // Credit row — required by vivi-music's GPL-3.0 porting guidelines, so it sits directly
        // beneath the audio-quality selection instead of behind its own sub-page.
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.jiosaavn_credit)) },
                description = stringResource(R.string.jiosaavn_credit_description),
                icon = { Icon(painterResource(R.drawable.info), null) },
                onClick = {},
            )
        }

        item {
            SourceCheckRow(source = AudioSourceType.JIOSAAVN)
        }
    }
}

/**
 * "Check source" row — runs a per-source health probe via [SourceCheckService] and shows the result
 * in a dialog. Lets the user diagnose why a source isn't working without having to look at logcat.
 */
@Composable
private fun SourceCheckRow(source: AudioSourceType) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SourceCheckResult?>(null) }

    PreferenceEntry(
        title = { Text(stringResource(R.string.check_source)) },
        description = stringResource(R.string.check_source_description),
        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
        trailingContent = if (checking) {
            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
        isEnabled = !checking,
        onClick = {
            if (checking) return@PreferenceEntry
            checking = true
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    SourceCheckService.check(source, context)
                }
                result = res
                checking = false
            }
        },
    )

    result?.let { res ->
        DefaultDialog(
            onDismiss = { result = null },
            buttons = {
                TextButton(onClick = { result = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.check_source_result_title, source.displayName(context)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = res.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (res.healthy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceOrderDialog(
    initialOrder: List<AudioSourceType>,
    isEnabled: (AudioSourceType) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<AudioSourceType>) -> Unit,
) {
    val context = LocalContext.current
    val sources = remember { mutableStateListOf(*initialOrder.toTypedArray()) }
    val lazyListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val item = sources.removeAt(from.index)
            sources.add(to.index, item)
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
                onClick = { onConfirm(sources.toList()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.set_source_priority),
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
                itemsIndexed(sources, key = { _, item -> item.name }) { index, source ->
                    ReorderableItem(reorderableState, key = source.name) {
                        val isFirst = index == 0
                        val enabled = isEnabled(source)
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
                                    .padding(bottom = if (index < sources.size - 1) 4.dp else 0.dp)
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
                            Icon(
                                painter = painterResource(source.iconRes()),
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.displayName(context),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                                if (!enabled) {
                                    Text(
                                        text = stringResource(R.string.audio_source_disabled),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentColor.copy(alpha = 0.7f),
                                    )
                                }
                            }
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
