/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.addons.AddonSettingsState
import moe.rukamori.archivetune.addons.AddonSettingsViewModel
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonSettings(
    navController: NavController,
    viewModel: AddonSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.screenState.collectAsStateWithLifecycle()
    val manifestUrl by viewModel.manifestUrl.collectAsStateWithLifecycle()
    val isWorking by viewModel.isWorking.collectAsStateWithLifecycle()
    val showActionError by viewModel.showActionError.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val installations =
        when (val current = state) {
            AddonSettingsState.Loading, AddonSettingsState.Empty -> emptyList()
            is AddonSettingsState.Success -> current.installations
            is AddonSettingsState.Error -> emptyList()
        }

    if (showAddDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.addon_add_title)) },
            textFieldValue = manifestUrl,
            onTextFieldValueChange = viewModel::updateManifestUrl,
            placeholder = { Text(stringResource(R.string.addon_manifest_url_hint)) },
            enabled = !isWorking,
            isInputValid = { it.trim().startsWith("https://", ignoreCase = true) },
            onDone = {
                viewModel.addAddon()
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.addons_title)) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        val bottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.addons_title)) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.addon_add_title)) },
                        description = stringResource(R.string.addon_add_description),
                        icon = { Icon(painterResource(R.drawable.add_circle), contentDescription = null) },
                        onClick = { showAddDialog = true },
                        isEnabled = !isWorking,
                    )
                }
                if (installations.isEmpty()) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.addon_empty_title)) },
                            description = stringResource(R.string.addon_empty_description),
                            icon = { Icon(painterResource(R.drawable.integration), contentDescription = null) },
                        )
                    }
                }
                installations.forEach { installation ->
                    item {
                        PreferenceEntry(
                            title = { Text(installation.name) },
                            description = installation.manifestUrl,
                            icon = { Icon(painterResource(R.drawable.integration), contentDescription = null) },
                            trailingContent = {
                                Row {
                                    Switch(
                                        checked = installation.enabled,
                                        enabled = !isWorking,
                                        onCheckedChange = { viewModel.setEnabled(installation.id, it) },
                                    )
                                    IconButton(
                                        onClick = { viewModel.removeAddon(installation.id) },
                                        onLongClick = {},
                                        enabled = !isWorking,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.delete),
                                            contentDescription = stringResource(R.string.addon_remove),
                                        )
                                    }
                                }
                            },
                            onClick = { viewModel.setEnabled(installation.id, !installation.enabled) },
                        )
                    }
                }
                if (state is AddonSettingsState.Loading) {
                    item { Text(stringResource(R.string.loading), modifier = Modifier.padding(16.dp)) }
                }
                if (showActionError || state is AddonSettingsState.Error) {
                    item {
                        Text(
                            stringResource(R.string.addon_operation_failed),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}