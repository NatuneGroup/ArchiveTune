/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LastFmPreferYtThumbnailsKey
import moe.rukamori.archivetune.constants.LastFmProvider
import moe.rukamori.archivetune.scrobbling.LastFmScrobbleQueue
import moe.rukamori.archivetune.scrobbling.LastFmSendLovesKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.LastFmLoginDialogUiModel
import moe.rukamori.archivetune.viewmodels.LastFmServiceEditorUiModel
import moe.rukamori.archivetune.viewmodels.LastFmSettingsScreenState
import moe.rukamori.archivetune.viewmodels.LastFmSettingsUiModel
import moe.rukamori.archivetune.viewmodels.LastFmSettingsViewModel
import moe.rukamori.archivetune.viewmodels.LastFmTimingEditorUiModel
import moe.rukamori.archivetune.viewmodels.LastFmTimingSetting
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.asPaddingValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastFMSettings(
    navController: NavController,
    scrollTo: String? = null,
    viewModel: LastFmSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.lastfm_integration)) },
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
        val topPadding = innerPadding.calculateTopPadding()

        LastFmSettingsContent(
            navController = navController,
            state = state,
            topPadding = topPadding,
            scrollTo = scrollTo,
            onOpenLoginDialog = viewModel::openLoginDialog,
            onDismissLoginDialog = viewModel::dismissLoginDialog,
            onLoginUsernameChange = viewModel::updateLoginUsername,
            onLoginPasswordChange = viewModel::updateLoginPassword,
            onLogin = viewModel::login,
            onLogout = viewModel::logout,
            onScrobblingChange = viewModel::setScrobblingEnabled,
            onNowPlayingChange = viewModel::setNowPlayingEnabled,
            onOpenTimingEditor = viewModel::openTimingEditor,
            onDismissTimingEditor = viewModel::dismissTimingEditor,
            onTimingMinTrackDurationChange = viewModel::updateTimingMinTrackDuration,
            onTimingDelayPercentChange = viewModel::updateTimingDelayPercent,
            onTimingDelaySecondsChange = viewModel::updateTimingDelaySeconds,
            onSaveTimingEditor = viewModel::saveTimingEditor,
            // The custom-endpoint dialog is wired straight to the repository
            // via a coroutine — it persists the entered endpoint / api key /
            // secret directly into DataStore (under the CUSTOM provider keys)
            // rather than through the view model's service-editor state
            // machine.
            onSaveCustomEndpoint = { endpoint, apiKey, secret ->
                viewModel.saveCustomEndpoint(endpoint, apiKey, secret)
            },
        )
    }
}

@Composable
private fun LastFmSettingsContent(
    navController: NavController,
    state: LastFmSettingsScreenState,
    topPadding: Dp,
    scrollTo: String? = null,
    onOpenLoginDialog: () -> Unit,
    onDismissLoginDialog: () -> Unit,
    onLoginUsernameChange: (String) -> Unit,
    onLoginPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onScrobblingChange: (Boolean) -> Unit,
    onNowPlayingChange: (Boolean) -> Unit,
    onOpenTimingEditor: (LastFmTimingSetting) -> Unit,
    onDismissTimingEditor: () -> Unit,
    onTimingMinTrackDurationChange: (Int) -> Unit,
    onTimingDelayPercentChange: (Float) -> Unit,
    onTimingDelaySecondsChange: (Int) -> Unit,
    onSaveTimingEditor: () -> Unit,
    onSaveCustomEndpoint: (endpoint: String, apiKey: String, secret: String) -> Unit,
) {
    val scrollState = rememberScrollState()
    val positions = rememberPreferencePositions()
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()

    // Custom-endpoint dialog state — hoisted here so it survives
    // recompositions of the inner Column (e.g. when the model updates).
    var showCustomEndpointDialog by remember { mutableStateOf(false) }

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
        when (state) {
            LastFmSettingsScreenState.Loading -> {
                LastFmSettingsLoading()
            }

            LastFmSettingsScreenState.Empty -> {
                Unit
            }

            is LastFmSettingsScreenState.Error -> {
                LastFmSettingsError(state.messageResId)
            }

            is LastFmSettingsScreenState.Success -> {
                LastFmSettingsSuccess(
                    navController = navController,
                    model = state.model,
                    positions = positions,
                    onOpenLoginDialog = onOpenLoginDialog,
                    onLogout = onLogout,
                    onScrobblingChange = onScrobblingChange,
                    onNowPlayingChange = onNowPlayingChange,
                    onOpenTimingEditor = onOpenTimingEditor,
                    showCustomEndpointDialog = showCustomEndpointDialog,
                    onShowCustomEndpointDialog = { showCustomEndpointDialog = it },
                )
            }
        }
    }

    if (state is LastFmSettingsScreenState.Success) {
        val model = state.model
        LastFmLoginDialog(
            model = model,
            dialog = model.loginDialog,
            onDismiss = onDismissLoginDialog,
            onUsernameChange = onLoginUsernameChange,
            onPasswordChange = onLoginPasswordChange,
            onLogin = onLogin,
        )
        // LastFmServiceEditorDialog has no live entry point; the composable is
        // kept as dead code pending a separate cleanup.
        LastFmCustomEndpointDialog(
            visible = showCustomEndpointDialog,
            onDismiss = { showCustomEndpointDialog = false },
            onSave = { endpoint, apiKey, secret ->
                onSaveCustomEndpoint(endpoint, apiKey, secret)
                showCustomEndpointDialog = false
            },
        )
        LastFmTimingEditorDialog(
            editor = model.timingEditor,
            onDismiss = onDismissTimingEditor,
            onMinTrackDurationChange = onTimingMinTrackDurationChange,
            onDelayPercentChange = onTimingDelayPercentChange,
            onDelaySecondsChange = onTimingDelaySecondsChange,
            onSave = onSaveTimingEditor,
        )
    }
}

@Composable
private fun LastFmSettingsLoading() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularWavyProgressIndicator(modifier = Modifier.size(28.dp))
        Text(
            text = stringResource(R.string.loading),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun LastFmSettingsError(
    @StringRes messageResId: Int,
) {
    Text(
        text = stringResource(messageResId),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun LastFmSettingsSuccess(
    navController: NavController,
    model: LastFmSettingsUiModel,
    positions: PreferencePositions,
    onOpenLoginDialog: () -> Unit,
    onLogout: () -> Unit,
    onScrobblingChange: (Boolean) -> Unit,
    onNowPlayingChange: (Boolean) -> Unit,
    onOpenTimingEditor: (LastFmTimingSetting) -> Unit,
    showCustomEndpointDialog: Boolean,
    onShowCustomEndpointDialog: (Boolean) -> Unit,
) {

    // Local state for the "Prefer YouTube thumbnails" toggle. When enabled,
    // the Last.fm dashboard skips the Last.fm image array (which can return
    // non-square / brown-matted images) and resolves artwork via YouTube hq720
    // thumbnails (clean 16:9, no baked-in bars). Read here so the toggle is
    // always in sync with the dashboard's read of the same key.
    var preferYtThumbnails by rememberPreference(LastFmPreferYtThumbnailsKey, defaultValue = false)
    var sendLoves by rememberPreference(LastFmSendLovesKey, defaultValue = false)
    val context = LocalContext.current
    LaunchedEffect(Unit) { LastFmScrobbleQueue.attach(context) }
    val pendingScrobbles by LastFmScrobbleQueue.pendingCount.collectAsStateWithLifecycle()

    PreferenceGroup(
        modifier = positions.modifierFor("lastfm_account"),
        title = stringResource(R.string.account),
    ) {
        // PRIMARY: One-tap WebView sign-in. Opens the LastFmLoginScreen which loads
        // Last.fm's official auth page in a WebView. The user approves the app,
        // Last.fm redirects to our custom scheme, we capture the token and exchange
        // it for a session key via auth.getSession. No API key / secret / username /
        // password fields — the baked-in LastFmAppCredentials identifies the app.
        item {
            PreferenceEntry(
                modifier = positions.modifierFor("lastfm_connect_button"),
                title = { Text(stringResource(R.string.lastfm_connect_button)) },
                description = stringResource(R.string.lastfm_connect_button_description),
                icon = { Icon(painterResource(R.drawable.login), null) },
                onClick = { navController.navigate(LASTFM_LOGIN_ROUTE) },
            )
        }

        // Libre.fm — opens a parallel WebView login flow that
        // uses libre.fm's auth URL and the same baked-in credentials
        // (Libre.fm is API-compatible with Last.fm and accepts any API
        // key for read access). After login, the runtime endpoint is
        // switched to https://libre.fm/2.0/ and the provider is pinned
        // to LIBREFM so subsequent scrobbles / now-playing updates go to
        // Libre.fm instead of Last.fm.
        item {
            PreferenceEntry(
                modifier = positions.modifierFor("lastfm_connect_librefm_button"),
                title = { Text(stringResource(R.string.lastfm_connect_librefm_button)) },
                description = stringResource(R.string.lastfm_connect_librefm_button_description),
                icon = { Icon(painterResource(R.drawable.login), null) },
                onClick = { navController.navigate(LASTFM_LIBREFM_LOGIN_ROUTE) },
            )
        }

        // Custom endpoint — opens a dialog where the user
        // enters their own API endpoint URL + API key + shared secret
        // (for self-hosted GNU FM / ListenBrainz-compatible scrobblers
        // that aren't Libre.fm). After saving, the provider is pinned
        // to CUSTOM and the runtime config is initialized with the
        // entered values.
        item {
            PreferenceEntry(
                modifier = positions.modifierFor("lastfm_connect_custom_button"),
                title = { Text(stringResource(R.string.lastfm_connect_custom_button)) },
                description = stringResource(R.string.lastfm_connect_custom_button_description),
                icon = { Icon(painterResource(R.drawable.token), null) },
                onClick = { onShowCustomEndpointDialog(true) },
            )
        }

        // Status row showing the current username + sign out button. When not
        // logged in, this row is hidden — the connect button above is the only
        // sign-in affordance.
        if (model.isLoggedIn) {
            item {
                PreferenceEntry(
                    title = {
                        Text(
                            text = model.username,
                            modifier = Modifier.alpha(1f),
                        )
                    },
                    description = null,
                    icon = { Icon(painterResource(R.drawable.account), null) },
                    trailingContent = {
                        OutlinedButton(onClick = onLogout, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.action_logout))
                        }
                    },
                )
            }
        }
    }

    PreferenceGroup(
        modifier = positions.modifierFor("lastfm_options"),
        title = stringResource(R.string.options),
    ) {
        item {
            SwitchPreference(
                modifier = positions.modifierFor("enable_scrobbling"),
                title = { Text(stringResource(R.string.enable_scrobbling)) },
                checked = model.scrobblingEnabled,
                onCheckedChange = onScrobblingChange,
                isEnabled = model.canEnableScrobbling,
            )
        }

        item {
            SwitchPreference(
                modifier = positions.modifierFor("lastfm_now_playing"),
                title = { Text(stringResource(R.string.lastfm_now_playing)) },
                checked = model.nowPlayingEnabled,
                onCheckedChange = onNowPlayingChange,
                isEnabled = model.canEnableScrobbling && model.scrobblingEnabled,
            )
        }

        item {
            SwitchPreference(
                modifier = positions.modifierFor("lastfm_send_loves"),
                title = { Text(stringResource(R.string.lastfm_send_loves)) },
                description = stringResource(R.string.lastfm_send_loves_desc),
                checked = sendLoves,
                onCheckedChange = { sendLoves = it },
                isEnabled = model.isLoggedIn,
            )
        }

        if (pendingScrobbles > 0) {
            item {
                PreferenceEntry(
                    modifier = positions.modifierFor("lastfm_pending_scrobbles"),
                    title = { Text(stringResource(R.string.lastfm_pending_scrobbles_title)) },
                    description =
                        pluralStringResource(
                            R.plurals.lastfm_pending_scrobbles_desc,
                            pendingScrobbles,
                            pendingScrobbles,
                        ),
                    trailingContent = {
                        OutlinedButton(
                            onClick = LastFmScrobbleQueue::flushSoon,
                            enabled = model.isLoggedIn,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.lastfm_pending_scrobbles_send_now))
                        }
                    },
                )
            }
        }

        item {
            SwitchPreference(
                modifier = positions.modifierFor("lastfm_prefer_yt_thumbnails"),
                title = { Text(stringResource(R.string.lastfm_prefer_yt_thumbnails)) },
                description = stringResource(R.string.lastfm_prefer_yt_thumbnails_desc),
                checked = preferYtThumbnails,
                onCheckedChange = { preferYtThumbnails = it },
            )
        }
    }

    PreferenceGroup(
        modifier = positions.modifierFor("lastfm_scrobbling_config"),
        title = stringResource(R.string.scrobbling_configuration),
    ) {
        item {
            PreferenceEntry(
                modifier = positions.modifierFor("scrobble_min_track_duration"),
                title = { Text(stringResource(R.string.scrobble_min_track_duration)) },
                description = stringResource(R.string.duration_seconds_short, model.minTrackDurationSeconds),
                onClick = { onOpenTimingEditor(LastFmTimingSetting.MIN_TRACK_DURATION) },
            )
        }

        item {
            PreferenceEntry(
                modifier = positions.modifierFor("scrobble_delay_percent"),
                title = { Text(stringResource(R.string.scrobble_delay_percent)) },
                description =
                    stringResource(
                        R.string.percent_format,
                        (model.scrobbleDelayPercent * 100).roundToInt(),
                    ),
                onClick = { onOpenTimingEditor(LastFmTimingSetting.DELAY_PERCENT) },
            )
        }

        item {
            PreferenceEntry(
                modifier = positions.modifierFor("scrobble_delay_minutes"),
                title = { Text(stringResource(R.string.scrobble_delay_minutes)) },
                description = stringResource(R.string.duration_seconds_short, model.scrobbleDelaySeconds),
                onClick = { onOpenTimingEditor(LastFmTimingSetting.DELAY_SECONDS) },
            )
        }
    }
}

@Composable
private fun LastFmLoginDialog(
    model: LastFmSettingsUiModel,
    dialog: LastFmLoginDialogUiModel,
    onDismiss: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
) {
    if (!dialog.visible) return

    AlertDialog(
        onDismissRequest = {
            if (!dialog.isLoggingIn) onDismiss()
        },
        title = { Text(stringResource(R.string.login)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialog.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    enabled = !dialog.isLoggingIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dialog.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !dialog.isLoggingIn,
                    modifier = Modifier.fillMaxWidth(),
                )

                dialog.errorMessageResId?.let { messageResId ->
                    Text(
                        text = stringResource(messageResId),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (dialog.isLoggingIn) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.logging_in),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onLogin,
                enabled =
                    !dialog.isLoggingIn &&
                        model.canLogin &&
                        dialog.username.isNotBlank() &&
                        dialog.password.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.login))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !dialog.isLoggingIn,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LastFmServiceEditorDialog(
    editor: LastFmServiceEditorUiModel,
    onDismiss: () -> Unit,
    onProviderChange: (LastFmProvider) -> Unit,
    onCustomEndpointChange: (String) -> Unit,
    onApiKeyOverrideChange: (String) -> Unit,
    onSecretOverrideChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    if (!editor.visible) return

    AlertDialog(
        onDismissRequest = {
            if (!editor.isSaving) onDismiss()
        },
        title = { Text(stringResource(R.string.lastfm_service)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.lastfm_service_provider),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val providers = remember { LastFmProvider.entries.toList() }
                    providers.forEach { provider ->
                        FilterChip(
                            selected = editor.provider == provider,
                            onClick = { onProviderChange(provider) },
                            enabled = !editor.isSaving,
                            label = { Text(stringResource(provider.titleResId())) },
                        )
                    }
                }

                if (editor.showCustomEndpoint) {
                    OutlinedTextField(
                        value = editor.customEndpoint,
                        onValueChange = onCustomEndpointChange,
                        label = { Text(stringResource(R.string.lastfm_custom_endpoint)) },
                        singleLine = true,
                        isError = editor.errorMessageResId == R.string.lastfm_endpoint_invalid,
                        enabled = !editor.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (editor.showApiCredentials) {
                    OutlinedTextField(
                        value = editor.apiKeyOverride,
                        onValueChange = onApiKeyOverrideChange,
                        label = { Text(stringResource(R.string.lastfm_api_key_override)) },
                        singleLine = true,
                        enabled = !editor.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.secretOverride,
                        onValueChange = onSecretOverrideChange,
                        label = { Text(stringResource(R.string.lastfm_secret_override)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !editor.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InfoLabel(text = stringResource(R.string.lastfm_api_credentials_hint))
                }

                editor.errorMessageResId?.let { messageResId ->
                    Text(
                        text = stringResource(messageResId),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !editor.isSaving,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !editor.isSaving,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun LastFmTimingEditorDialog(
    editor: LastFmTimingEditorUiModel,
    onDismiss: () -> Unit,
    onMinTrackDurationChange: (Int) -> Unit,
    onDelayPercentChange: (Float) -> Unit,
    onDelaySecondsChange: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val setting = editor.setting ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.titleResId())) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp),
            ) {
                when (setting) {
                    LastFmTimingSetting.MIN_TRACK_DURATION -> {
                        Text(
                            text = stringResource(R.string.duration_seconds_short, editor.minTrackDurationSeconds),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Slider(
                            value = editor.minTrackDurationSeconds.toFloat(),
                            onValueChange = { onMinTrackDurationChange(it.toInt()) },
                            valueRange = 10f..60f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    LastFmTimingSetting.DELAY_PERCENT -> {
                        Text(
                            text =
                                stringResource(
                                    R.string.percent_format,
                                    (editor.scrobbleDelayPercent * 100).roundToInt(),
                                ),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Slider(
                            value = editor.scrobbleDelayPercent,
                            onValueChange = onDelayPercentChange,
                            valueRange = 0.3f..0.95f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    LastFmTimingSetting.DELAY_SECONDS -> {
                        Text(
                            text = stringResource(R.string.duration_seconds_short, editor.scrobbleDelaySeconds),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Slider(
                            value = editor.scrobbleDelaySeconds.toFloat(),
                            onValueChange = { onDelaySecondsChange(it.toInt()) },
                            valueRange = 30f..360f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@StringRes
private fun LastFmProvider.titleResId(): Int =
    when (this) {
        LastFmProvider.LASTFM -> R.string.lastfm_provider_lastfm
        LastFmProvider.LIBREFM -> R.string.lastfm_provider_librefm
        LastFmProvider.CUSTOM -> R.string.lastfm_provider_custom
    }

@StringRes
private fun LastFmTimingSetting.titleResId(): Int =
    when (this) {
        LastFmTimingSetting.MIN_TRACK_DURATION -> R.string.scrobble_min_track_duration
        LastFmTimingSetting.DELAY_PERCENT -> R.string.scrobble_delay_percent
        LastFmTimingSetting.DELAY_SECONDS -> R.string.scrobble_delay_minutes
    }

/**
 * Custom-endpoint sign-in dialog. Lets the user enter their own API endpoint URL (e.g.
 * `https://my-scrobbler.example.com/2.0/`), API key, and shared secret for self-hosted GNU FM /
 * ListenBrainz-compatible scrobblers that aren't Libre.fm.
 */
@Composable
private fun LastFmCustomEndpointDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (endpoint: String, apiKey: String, secret: String) -> Unit,
) {
    if (!visible) return
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var endpointError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lastfm_connect_custom_button)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoLabel(text = stringResource(R.string.lastfm_custom_endpoint_hint))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        endpointError = false
                    },
                    label = { Text(stringResource(R.string.lastfm_custom_endpoint)) },
                    placeholder = { Text("https://my-scrobbler.example.com/2.0/") },
                    singleLine = true,
                    isError = endpointError,
                    supportingText = if (endpointError) {
                        { Text(stringResource(R.string.lastfm_endpoint_invalid)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.lastfm_api_key_override)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.lastfm_secret_override)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Validate the endpoint is a well-formed HTTP(S) URL —
                    // LastFM.normalizeEndpoint throws on malformed input, which
                    // we catch and surface as a field error. Empty API key /
                    // secret are allowed (the runtime falls back to the
                    // FALLBACK_COMPAT_* constants for endpoints that don't
                    // validate credentials).
                    val normalized = runCatching {
                        moe.rukamori.archivetune.lastfm.LastFM.normalizeEndpoint(endpoint.trim())
                    }.getOrNull()
                    if (normalized.isNullOrBlank()) {
                        endpointError = true
                        return@TextButton
                    }
                    onSave(normalized, apiKey.trim(), secret.trim())
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
