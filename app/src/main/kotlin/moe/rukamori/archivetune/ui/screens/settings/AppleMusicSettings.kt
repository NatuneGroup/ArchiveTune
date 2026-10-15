/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.constants.AppleMusicDevTokenKey
import moe.rukamori.archivetune.constants.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.screens.settings.SettingsDimensions
import moe.rukamori.archivetune.ui.utils.appBarScrollBehavior
import moe.rukamori.archivetune.ui.utils.backToMain
import androidx.datastore.preferences.core.edit
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

/** JWT-ish shape check: three base64url segments. Good enough to catch paste errors. */
private fun looksLikeJwt(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"))

/**
 * Media-user-token shape: Apple's iTunes-store token is NOT a JWT — it is a
 * short version prefix (`0.`) followed by standard base64 (may contain `+`, `/`,
 * `=`), e.g. `0.Ap7VmmO+s4RlV4F…==`. A JWT is accepted too, so panel-pasted
 * tokens pass.
 */
private fun looksLikeMediaUserToken(value: String): Boolean =
    looksLikeJwt(value) || value.matches(Regex("^0\\.[A-Za-z0-9+/=]{40,}$"))

/**
 * Apple Music sign-in — the community source pool supplies a Media User Token when the user
 * has none of their own (see App's mediaUserTokenProvider fallback), so playback can engage
 * with zero setup. Signing in here simply guarantees access with the user's own account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMusicSettings(navController: NavController) {
    val scrollBehavior = appBarScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (mediaToken, onMediaTokenChange) = rememberPreference(AppleMusicMediaUserTokenKey, "")
    val (devToken, onDevTokenChange) = rememberPreference(AppleMusicDevTokenKey, "")
    val signedIn = mediaToken.isNotBlank() && devToken.isNotBlank()

    var showTokenSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.applemusic_settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val scrollState = rememberScrollState()

        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )
                .verticalScroll(scrollState)
                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.applemusic_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // One continuous pane like every other settings screen — the hand-rolled
            // per-row Cards this screen used were the odd one out (Yuma treatment =
            // grouped panes, not per-row cards).
            PreferenceGroup(title = stringResource(R.string.applemusic_settings)) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.applemusic_sign_in_web)) },
                        description = stringResource(R.string.applemusic_sign_in_web_desc),
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        onClick = { navController.navigate(APPLE_MUSIC_LOGIN_ROUTE) },
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.applemusic_tokens_title)) },
                        description =
                            if (signedIn) stringResource(R.string.applemusic_tokens_ready)
                            else stringResource(R.string.applemusic_tokens_missing),
                        icon = { Icon(painterResource(R.drawable.token), null) },
                        onClick = { showTokenSheet = true },
                    )
                }

                if (signedIn) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.applemusic_disconnect)) },
                            icon = { Icon(painterResource(R.drawable.close), null) },
                            onClick = {
                                scope.launch {
                                    context.dataStore.edit {
                                        it.remove(AppleMusicMediaUserTokenKey)
                                        it.remove(AppleMusicDevTokenKey)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showTokenSheet) {
        TokenSheet(
            currentMediaToken = mediaToken,
            currentDevToken = devToken,
            onSave = { media, dev ->
                scope.launch {
                    context.dataStore.edit {
                        val m = media.trim()
                        val d = dev.trim()
                        if (m.isNotBlank()) it[AppleMusicMediaUserTokenKey] = m else it.remove(AppleMusicMediaUserTokenKey)
                        if (d.isNotBlank()) it[AppleMusicDevTokenKey] = d else it.remove(AppleMusicDevTokenKey)
                    }
                    showTokenSheet = false
                }
            },
            onDismiss = { showTokenSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenSheet(
    currentMediaToken: String,
    currentDevToken: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mediaToken by rememberSaveable { mutableStateOf(currentMediaToken) }
    var devToken by rememberSaveable { mutableStateOf(currentDevToken) }
    var showMedia by rememberSaveable { mutableStateOf(false) }
    var showDev by rememberSaveable { mutableStateOf(false) }

    val mediaValid = looksLikeMediaUserToken(mediaToken.trim())
    // Tolerate a "Bearer " prefix and stray whitespace on the pasted dev JWT.
    val devClean = devToken.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
    val devValid = devClean.isEmpty() || looksLikeJwt(devClean)
    // Developer token is optional — the app ships a fallback web-player JWT and can scrape a fresh one.
    // The user's Media User Token (0.Ap...) alone is enough to resolve ES/STM etc catalog via their account.
    // If they pasted both (Media User Token + Bearer JWT) we store both.
    val canSave = mediaValid && devValid && mediaToken.trim().isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            // Scrollable: two long credential fields push the save row below the sheet fold
            // otherwise, which reads as "the submit button disappeared".
            Modifier
                .padding(16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.applemusic_tokens_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.applemusic_tokens_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = mediaToken,
                onValueChange = { mediaToken = it },
                label = { Text(stringResource(R.string.applemusic_media_token)) },
                visualTransformation = if (showMedia) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showMedia = !showMedia }) {
                        Icon(if (showMedia) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = mediaToken.isNotBlank() && !mediaValid,
                supportingText = { if (mediaToken.isNotBlank() && !mediaValid) Text(stringResource(R.string.applemusic_token_invalid)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = devToken,
                onValueChange = { devToken = it },
                label = { Text(stringResource(R.string.applemusic_dev_token)) },
                visualTransformation = if (showDev) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showDev = !showDev }) {
                        Icon(if (showDev) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = devToken.isNotBlank() && !devValid,
                supportingText = { if (devToken.isNotBlank() && !devValid) Text(stringResource(R.string.applemusic_token_invalid)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(android.R.string.cancel)) }
                FilledTonalButton(onClick = { onSave(mediaToken, devClean) }, enabled = canSave, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.youtube_session_save))
                }
            }
        }
    }
}
