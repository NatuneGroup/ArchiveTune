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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.addons.AddonPack
import moe.rukamori.archivetune.addons.AddonState
import moe.rukamori.archivetune.addons.AdditionalDownloads
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackManager
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackState
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.formatFileSize
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePackSettings(navController: NavController) {
    val packState by JapaneseLanguagePackManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (_, onLyricsRomanizeJapaneseChange) =
        rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = false)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(bottom = SettingsDimensions.ScreenBottomPadding),
    ) {
        PreferenceGroup(title = stringResource(R.string.language_pack_base_language)) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.language_pack_english)) },
                    description = stringResource(R.string.language_pack_built_in),
                    icon = { Icon(painterResource(R.drawable.language), null) },
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.language_pack_optional_data)) {
            item {
                val isDownloading = packState is JapaneseLanguagePackState.Downloading
                val description =
                    when (val state = packState) {
                        JapaneseLanguagePackState.NotInstalled ->
                            stringResource(R.string.language_pack_japanese_download_description)
                        is JapaneseLanguagePackState.Downloading ->
                            stringResource(R.string.language_pack_downloading, state.progressPercent)
                        is JapaneseLanguagePackState.Installed ->
                            stringResource(R.string.language_pack_installed_size, formatFileSize(state.sizeBytes))
                        is JapaneseLanguagePackState.Failed -> state.message
                    }
                PreferenceEntry(
                    title = { Text(stringResource(R.string.language_pack_japanese)) },
                    description = description,
                    icon = { Icon(painterResource(R.drawable.translate), null) },
                    trailingContent = {
                        when (packState) {
                            is JapaneseLanguagePackState.Downloading ->
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            is JapaneseLanguagePackState.Installed ->
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            if (JapaneseLanguagePackManager.remove()) {
                                                onLyricsRomanizeJapaneseChange(false)
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.language_pack_remove)) }
                            JapaneseLanguagePackState.NotInstalled,
                            is JapaneseLanguagePackState.Failed,
                            ->
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            JapaneseLanguagePackManager.install().onSuccess {
                                                onLyricsRomanizeJapaneseChange(true)
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.download)) }
                        }
                    },
                    isEnabled = !isDownloading,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.language_pack_other_romanization)) },
                    description = stringResource(R.string.language_pack_other_romanization_description),
                    icon = { Icon(painterResource(R.drawable.translate), null) },
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.additional_downloads_player_fonts)) {
            item {
                AddonPackEntry(
                    pack = AdditionalDownloads.spatialFlowFont,
                    title = stringResource(R.string.additional_downloads_spatialflow_font),
                    description = stringResource(R.string.additional_downloads_spatialflow_font_description),
                    iconRes = R.drawable.text_fields,
                )
            }
        }
    }

    SettingsTopAppBar(
        title = { Text(stringResource(R.string.additional_downloads)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun AddonPackEntry(
    pack: AddonPack,
    title: String,
    description: String,
    iconRes: Int,
) {
    val state by pack.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    PreferenceEntry(
        title = { Text(title) },
        description =
            when (val current = state) {
                AddonState.Unavailable -> stringResource(R.string.additional_downloads_unavailable)
                AddonState.NotInstalled -> description
                is AddonState.Downloading -> stringResource(R.string.language_pack_downloading, current.progressPercent)
                is AddonState.Installed ->
                    stringResource(R.string.language_pack_installed_size, formatFileSize(current.file.length()))
                is AddonState.Failed -> current.message
            },
        icon = { Icon(painterResource(iconRes), null) },
        trailingContent = {
            when (state) {
                AddonState.Unavailable -> Unit
                is AddonState.Downloading ->
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                is AddonState.Installed ->
                    TextButton(onClick = { scope.launch { pack.remove() } }) {
                        Text(stringResource(R.string.language_pack_remove))
                    }
                AddonState.NotInstalled,
                is AddonState.Failed,
                ->
                    TextButton(onClick = { scope.launch { pack.install() } }) {
                        Text(stringResource(R.string.download))
                    }
            }
        },
        isEnabled = state !is AddonState.Downloading && state != AddonState.Unavailable,
    )
}
