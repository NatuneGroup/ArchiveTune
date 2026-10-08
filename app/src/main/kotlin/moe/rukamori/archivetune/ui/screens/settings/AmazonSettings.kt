/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Amazon Music integration settings, for the instance-based source: the user adds their own
 * "Amazon Music Stream API" instance URL(s) and authorizes once via the Turnstile WebView (or
 * pastes an operator bypass token). Reached from the Integration screen alongside
 * Tidal/Qobuz/Deezer/Apple Music. Shaped like TidalSettings — instances + auth, no account.
 */



package moe.rukamori.archivetune.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.constants.AmazonAudioQuality
import moe.rukamori.archivetune.constants.AmazonAudioQualityKey
import moe.rukamori.archivetune.constants.AmazonBypassTokenKey
import moe.rukamori.archivetune.constants.AmazonEnabledKey
import moe.rukamori.archivetune.constants.AmazonInstancesKey
import moe.rukamori.archivetune.constants.AmazonTurnstileJwtExpiryMsKey
import moe.rukamori.archivetune.constants.AmazonTurnstileJwtKey
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmazonSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val context = LocalContext.current

    val (enabled, onEnabledChangeRaw) = rememberPreference(AmazonEnabledKey, false)
    val (orderRaw, onOrderChange) = rememberPreference(AudioSourceOrderKey, "")
    // Turning the source on has to write it into the order as well. AMAZON is deliberately not a
    // member of AudioSourceConfig.DEFAULT_ORDER, and the order picker can only reorder what it is
    // given, so without this the toggle would flip a preference the resolver never reads and
    // playback would stay on YouTube with no indication why. Mirrors QqMusicSettings.
    val onEnabledChange: (Boolean) -> Unit = { next ->
        onEnabledChangeRaw(next)
        if (next) {
            onOrderChange(AudioSourceConfig.withSourceAdded(orderRaw, AudioSourceType.AMAZON))
        }
    }
    val (audioQuality, onAudioQualityChange) =
        rememberEnumPreference(AmazonAudioQualityKey, AmazonAudioQuality.Default)
    val (storedInstances, onStoredInstancesChange) = rememberPreference(AmazonInstancesKey, "")
    val (jwt, _) = rememberPreference(AmazonTurnstileJwtKey, "")
    val (jwtExpiry, _) = rememberPreference(AmazonTurnstileJwtExpiryMsKey, 0L)
    val (bypassToken, onBypassTokenChange) = rememberPreference(AmazonBypassTokenKey, "")

    val instanceCount =
        remember(storedInstances) {
            storedInstances.split('\n').map { it.trim() }.count { it.isNotEmpty() }
        }

    var showInstancesDialog by remember { mutableStateOf(false) }
    var showBypassDialog by remember { mutableStateOf(false) }

    if (showInstancesDialog) {
        TextFieldDialog(
            icon = { Icon(painterResource(R.drawable.link), null) },
            title = { Text(stringResource(R.string.amazon_instances)) },
            placeholder = { Text(stringResource(R.string.amazon_instances_hint)) },
            initialTextFieldValue =
                TextFieldValue(storedInstances, selection = TextRange(storedInstances.length)),
            singleLine = false,
            isInputValid = { true },
            onDone = { raw ->
                onStoredInstancesChange(
                    raw
                        .split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString("\n"),
                )
            },
            onDismiss = { showInstancesDialog = false },
        )
    }

    if (showBypassDialog) {
        TextFieldDialog(
            icon = { Icon(painterResource(R.drawable.token), null) },
            title = { Text(stringResource(R.string.amazon_bypass_token)) },
            placeholder = { Text(stringResource(R.string.amazon_bypass_token_hint)) },
            initialTextFieldValue =
                TextFieldValue(bypassToken, selection = TextRange(bypassToken.length)),
            singleLine = true,
            isInputValid = { true },
            onDone = { raw -> onBypassTokenChange(raw.trim()) },
            onDismiss = { showBypassDialog = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.source_amazon)) },
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
                .padding(bottom = playerAwareBottomPadding + 16.dp),
        ) {
            PreferenceGroup(
                title = stringResource(R.string.source_amazon),
            ) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("amazon_source_enable"),
                        title = { Text(stringResource(R.string.source_amazon)) },
                        description = stringResource(R.string.audio_source_enabled),
                        icon = { Icon(painterResource(R.drawable.star), null) },
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }

                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("amazon_audio_quality"),
                        title = { Text(stringResource(R.string.amazon_audio_quality)) },
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        selectedValue = audioQuality,
                        onValueSelected = onAudioQualityChange,
                        isEnabled = enabled,
                        valueText = { quality ->
                            when (quality) {
                                AmazonAudioQuality.ULTRA_HD -> stringResource(R.string.amazon_quality_ultra_hd)
                                AmazonAudioQuality.HD -> stringResource(R.string.amazon_quality_hd)
                                AmazonAudioQuality.STANDARD -> stringResource(R.string.amazon_quality_standard)
                            }
                        },
                    )
                }
            }

            PreferenceGroup(
                title = stringResource(R.string.amazon_instances),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("amazon_instances"),
                        title = { Text(stringResource(R.string.amazon_instances)) },
                        description =
                            if (instanceCount > 0) {
                                stringResource(R.string.amazon_instances_count, instanceCount)
                            } else {
                                stringResource(R.string.amazon_instances_empty)
                            },
                        icon = { Icon(painterResource(R.drawable.link), null) },
                        onClick = { showInstancesDialog = true },
                    )
                }

                item {
                    // Informational: the pool's instances are tried after the user's own and are
                    // not editable here (they belong to whoever runs them). Read without remember —
                    // the pool cache fills in on its own schedule, and the row should show it.
                    val pooledInstances = PoolAccountManager.amazonInstances()
                    PreferenceEntry(
                        modifier = positions.modifierFor("amazon_pool_instances"),
                        title = { Text(stringResource(R.string.amazon_pool_instances)) },
                        description =
                            if (pooledInstances.isEmpty()) {
                                stringResource(R.string.amazon_pool_instances_empty)
                            } else {
                                stringResource(R.string.amazon_pool_instances_count, pooledInstances.size)
                            },
                        icon = { Icon(painterResource(R.drawable.cloud), null) },
                    )
                }

                item {
                    val authorizeLauncher =
                        rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.StartActivityForResult(),
                        ) { /* JWT + expiry are persisted by the activity; the rows below re-read
                            them from DataStore, so no result handling is needed here. */ }
                    PreferenceEntry(
                        modifier = positions.modifierFor("amazon_authorize"),
                        title = { Text(stringResource(R.string.amazon_authorize)) },
                        description = stringResource(R.string.amazon_authorize_description),
                        icon = { Icon(painterResource(R.drawable.login), null) },
                        onClick = {
                            if (instanceCount == 0) {
                                Toast
                                    .makeText(context, R.string.amazon_instances_empty, Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                authorizeLauncher.launch(
                                    Intent(context, AmazonTurnstileActivity::class.java),
                                )
                            }
                        },
                    )
                }

                item {
                    // The JWT + expiry are collected from DataStore via rememberPreference, so the
                    // status text updates on its own the moment the Turnstile activity persists
                    // them — no resume hook needed.
                    PreferenceEntry(
                        modifier = positions.modifierFor("amazon_jwt_status"),
                        title = { Text(stringResource(R.string.amazon_jwt_status)) },
                        description =
                            when {
                                jwt.isBlank() -> stringResource(R.string.amazon_jwt_status_never)
                                jwtExpiry <= System.currentTimeMillis() -> stringResource(R.string.amazon_jwt_status_expired)
                                else ->
                                    stringResource(
                                        R.string.amazon_jwt_status_valid_until,
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(jwtExpiry)),
                                    )
                            },
                        icon = { Icon(painterResource(R.drawable.lock), null) },
                        onClick = null,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("amazon_bypass_token"),
                        title = { Text(stringResource(R.string.amazon_bypass_token)) },
                        description =
                            if (bypassToken.isBlank()) {
                                stringResource(R.string.amazon_bypass_token_description)
                            } else {
                                stringResource(
                                    R.string.amazon_bypass_token_set,
                                    bypassToken.takeLast(4),
                                )
                            },
                        icon = { Icon(painterResource(R.drawable.token), null) },
                        onClick = { showBypassDialog = true },
                    )
                }
            }
        }
    }
}

