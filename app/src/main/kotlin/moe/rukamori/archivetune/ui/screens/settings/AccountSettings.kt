/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import java.util.UUID
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.App.Companion.forgetAccount
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.auth.YouTubeOAuthRepository
import moe.rukamori.archivetune.constants.AccountChannelHandleKey
import moe.rukamori.archivetune.constants.AccountEmailKey
import moe.rukamori.archivetune.constants.AccountNameKey
import moe.rukamori.archivetune.constants.DataSyncIdKey
import moe.rukamori.archivetune.constants.ForceSyncOnAccountSwitchKey
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.constants.SavedAccountsKey
import moe.rukamori.archivetune.constants.SelectedYtmPlaylistsKey
import moe.rukamori.archivetune.constants.UseLoginForBrowse
import moe.rukamori.archivetune.constants.VisitorDataKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.component.SettingsDescriptionText
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.screens.buildLoginRoute
import moe.rukamori.archivetune.ui.theme.LocalYumaColors
import moe.rukamori.archivetune.ui.theme.yumaGlassCard
import moe.rukamori.archivetune.ui.utils.appBarScrollBehavior
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.Updater
import moe.rukamori.archivetune.utils.decodeSavedAccounts
import moe.rukamori.archivetune.utils.encodeSavedAccounts
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.AccountChannelUiModel
import moe.rukamori.archivetune.viewmodels.AccountChannelsState
import moe.rukamori.archivetune.viewmodels.AccountSettingsViewModel

private val AccountContentMaxWidth = 840.dp
private val AvatarSize = 72.dp
private val RowIconSize = 40.dp

@Immutable
private data class SavedAccountCollection(
    val accounts: List<SavedAccount>,
)

@Composable
fun AccountSettings(
    navController: NavController,
    latestVersionName: String,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = appBarScrollBehavior()

    val accountLabel = stringResource(R.string.account)
    val generalLabel = stringResource(R.string.general)
    val miscLabel = stringResource(R.string.misc)
    val loginLabel = stringResource(R.string.login)
    val tokenDescription = stringResource(R.string.token_adv_login_description)

    val (accountNamePref, _) = rememberPreference(AccountNameKey, "")
    val (accountEmail, _) = rememberPreference(AccountEmailKey, "")
    val (accountChannelHandle, _) = rememberPreference(AccountChannelHandleKey, "")
    val (innerTubeCookie, _) = rememberPreference(InnerTubeCookieKey, "")
    val (visitorData, _) = rememberPreference(VisitorDataKey, "")
    val (dataSyncId, _) = rememberPreference(DataSyncIdKey, "")
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)
    val (forceSyncOnAccountSwitch, onForceSyncOnAccountSwitchChange) =
        rememberPreference(ForceSyncOnAccountSwitchKey, false)
    val (selectedYtmPlaylists, _) = rememberPreference(SelectedYtmPlaylistsKey, "")
    val (savedAccountsJson, onSavedAccountsJsonChange) = rememberPreference(SavedAccountsKey, "")
    // YouTubeOAuthRepository.isSignedIn is a one-shot read; observing the refresh token instead
    // keeps the sign-in/sign-out row correct without leaving the screen, and mirrors how
    // isLoggedIn below is derived from the cookie. The token is never rendered.
    val (oauthRefreshToken, _) = rememberPreference(InnerTubeOAuthRefreshTokenKey, "")
    val hasOAuthSession = oauthRefreshToken.isNotBlank()

    val savedAccounts =
        remember(savedAccountsJson) {
            SavedAccountCollection(decodeSavedAccounts(savedAccountsJson))
        }

    val isLoggedIn =
        remember(innerTubeCookie) {
            hasYouTubeLoginCookie(innerTubeCookie)
        }

    LaunchedEffect(useLoginForBrowse) {
        YouTube.useLoginForBrowse = useLoginForBrowse
    }

    val viewModel: AccountSettingsViewModel = hiltViewModel()
    val accountNameFromViewModel by viewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by viewModel.accountImageUrl.collectAsStateWithLifecycle()
    val accountChannelsState by viewModel.accountChannelsState.collectAsStateWithLifecycle()
    val actionFailures by viewModel.actionFailures.collectAsStateWithLifecycle()
    val loginFailureMessage = stringResource(R.string.youtube_login_validation_failed)
    LaunchedEffect(actionFailures) {
        if (actionFailures > 0) Toast.makeText(context, loginFailureMessage, Toast.LENGTH_LONG).show()
    }

    val displayName =
        when {
            accountNameFromViewModel.isNotBlank() -> accountNameFromViewModel
            accountNamePref.isNotBlank() -> accountNamePref
            isLoggedIn -> accountLabel
            else -> loginLabel
        }
    val hasSwitchableChannels =
        (accountChannelsState as? AccountChannelsState.Success)
            ?.channels
            ?.items
            ?.size
            .let { (it ?: 0) > 1 }

    var showToken by remember { mutableStateOf(false) }
    var showTokenEditor by remember { mutableStateOf(false) }
    var showUnsavedAccountDialog by remember { mutableStateOf(false) }
    var showAccountSwitcher by remember { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            showToken = false
        }
    }

    val hasUpdate =
        BuildConfig.UPDATER_AVAILABLE &&
            Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
    val tokenActionTitle =
        when {
            !isLoggedIn -> stringResource(R.string.advanced_login)
            showToken -> stringResource(R.string.token_shown)
            else -> stringResource(R.string.token_hidden)
        }

    val saveCurrentAccount: () -> Unit = {
        val existing = decodeSavedAccounts(savedAccountsJson)
        if (isLoggedIn && existing.none { it.innerTubeCookie == innerTubeCookie }) {
            val newAccount =
                SavedAccount(
                    id = UUID.randomUUID().toString(),
                    name = if (accountNameFromViewModel.isNotBlank()) accountNameFromViewModel else accountNamePref,
                    email = accountEmail,
                    channelHandle = accountChannelHandle,
                    innerTubeCookie = innerTubeCookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    ytmSync = ytmSync,
                    selectedYtmPlaylists = selectedYtmPlaylists,
                )
            onSavedAccountsJsonChange(encodeSavedAccounts(existing + newAccount))
        }
    }

    val switchToAccount: (SavedAccount) -> Unit = { account ->
        viewModel.switchToAccount(
            account = account,
            forceSyncOnSwitch = forceSyncOnAccountSwitch,
        )
    }

    val switchToAccountChannel: (AccountChannelUiModel) -> Unit = { channel ->
        viewModel.switchToAccountChannel(
            channel = channel,
            forceSyncOnSwitch = forceSyncOnAccountSwitch,
        )
    }

    val removeAccount: (SavedAccount) -> Unit = { account ->
        val existing = decodeSavedAccounts(savedAccountsJson)
        onSavedAccountsJsonChange(encodeSavedAccounts(existing.filter { it.id != account.id }))
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(
                            text = accountLabel,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
                colors =
                    TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = Color.Transparent,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal,
                        ),
                    ),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = AccountContentMaxWidth)
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        end = 16.dp,
                        bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    AccountSummaryCard(
                        isLoggedIn = isLoggedIn,
                        accountName = displayName,
                        accountEmail = accountEmail,
                        accountHandle = accountChannelHandle,
                        accountImageUrl = accountImageUrl,
                        accountSwitcherEnabled =
                            isLoggedIn || savedAccounts.accounts.isNotEmpty() || hasSwitchableChannels,
                        onPrimaryAction = {
                            if (isLoggedIn) {
                                navController.navigate("account")
                            } else {
                                navController.navigate(buildLoginRoute())
                            }
                        },
                        onSecondaryAction = {
                            if (isLoggedIn) {
                                showToken = false
                                forgetAccount(context, clearWebAuthSession = true)
                            } else {
                                showTokenEditor = true
                            }
                        },
                        onOpenAccountSwitcher = { showAccountSwitcher = true },
                    )
                }

                if (hasUpdate) {
                    item {
                        UpdateBannerStrip(
                            latestVersion = latestVersionName,
                            onClick = { uriHandler.openUri(Updater.getLatestDownloadUrl()) },
                        )
                    }
                }

                item {
                    // Browser sign-in is the only route offered now. The device-code flow used to
                    // sit beside it, but it yields a VR Bearer that only signs /player — it cannot
                    // reach the library, playlists or browse the way the WEB_REMIX cookie can — so
                    // offering it as a peer of the full sign-in mostly produced half-working
                    // accounts. The SIGN-OUT row stays: anyone who completed that flow before this
                    // change still holds a session and must be able to end it.
                    val browserRowVisible = !isLoggedIn
                    val rowCount = (if (browserRowVisible) 1 else 0) + (if (hasOAuthSession) 1 else 0)
                    if (rowCount > 0) {
                        ExpressiveSectionCard(title = loginLabel) {
                            if (browserRowVisible) {
                                ExpressiveActionRow(
                                    icon = painterResource(R.drawable.login),
                                    title = stringResource(R.string.yt_browser_sign_in),
                                    subtitle = stringResource(R.string.yt_browser_sign_in_desc),
                                    onClick = { navController.navigate(buildLoginRoute()) },
                                    index = 0,
                                    count = rowCount,
                                )
                            }

                            if (hasOAuthSession) {
                                ExpressiveActionRow(
                                    icon = painterResource(R.drawable.logout),
                                    title = stringResource(R.string.yt_oauth_sign_out),
                                    subtitle = stringResource(R.string.yt_oauth_sign_out_desc),
                                    accent = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        coroutineScope.launch {
                                            YouTubeOAuthRepository.signOut(context)
                                            Toast
                                                .makeText(
                                                    context,
                                                    context.getString(R.string.yt_oauth_signed_out),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                    },
                                    index = rowCount - 1,
                                    count = rowCount,
                                )
                            }
                        }
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = isLoggedIn,
                        enter =
                            fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                                expandVertically(
                                    spring(stiffness = Spring.StiffnessLow),
                                ),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        ExpressiveSectionCard(title = generalLabel) {
                            ExpressiveSwitchRow(
                                icon = painterResource(R.drawable.add_circle),
                                title = stringResource(R.string.more_content),
                                subtitle = stringResource(R.string.use_login_for_browse_desc),
                                checked = useLoginForBrowse,
                                onCheckedChange = onUseLoginForBrowseChange,
                                index = 0,
                                count = 3,
                            )

                            ExpressiveSwitchRow(
                                icon = painterResource(R.drawable.cached),
                                title = stringResource(R.string.yt_sync),
                                checked = ytmSync,
                                onCheckedChange = onYtmSyncChange,
                                index = 1,
                                count = 3,
                            )

                            ExpressiveSwitchRow(
                                icon = painterResource(R.drawable.sync),
                                title = stringResource(R.string.force_sync_on_switch_account),
                                subtitle = stringResource(R.string.force_sync_on_switch_account_desc),
                                checked = forceSyncOnAccountSwitch,
                                onCheckedChange = onForceSyncOnAccountSwitchChange,
                                index = 2,
                                count = 3,
                            )
                        }
                    }
                }

                item {
                    ExpressiveSectionCard(title = miscLabel) {
                        ExpressiveActionRow(
                            icon = painterResource(R.drawable.visibility_off),
                            title = stringResource(R.string.hidden_playlists),
                            subtitle = stringResource(R.string.hidden_playlists_description),
                            onClick = { navController.navigate("settings/hidden_playlists") },
                            index = 0,
                            count = 3,
                        )

                        ExpressiveActionRow(
                            icon = painterResource(R.drawable.token),
                            title = tokenActionTitle,
                            subtitle = tokenDescription,
                            accent = if (isLoggedIn && showToken) MaterialTheme.colorScheme.tertiary else null,
                            onClick = {
                                if (!isLoggedIn) {
                                    showTokenEditor = true
                                } else if (!showToken) {
                                    showToken = true
                                } else {
                                    showTokenEditor = true
                                }
                            },
                            index = 1,
                            count = 3,
                        )

                        // PO Token Generation sits with the other account-credential rows; opens the
                        // existing PoTokenScreen route.
                        ExpressiveActionRow(
                            icon = painterResource(R.drawable.token),
                            title = stringResource(R.string.po_token_generation),
                            subtitle = stringResource(R.string.settings_po_token_subtitle),
                            onClick = { navController.navigate(PO_TOKEN_ROUTE) },
                            index = 2,
                            count = 3,
                        )
                    }
                }

                item {
                    VersionStamp()
                }
            }
        }
    }

    if (showAccountSwitcher) {
        AccountSwitcherSheet(
            isLoggedIn = isLoggedIn,
            savedAccounts = savedAccounts,
            activeInnerTubeCookie = innerTubeCookie,
            activeDataSyncId = dataSyncId,
            accountChannelsState = accountChannelsState,
            onSaveAccount = saveCurrentAccount,
            onSwitchAccount = switchToAccount,
            onSwitchAccountChannel = switchToAccountChannel,
            onRemoveAccount = removeAccount,
            onAddAnotherAccount = {
                showAccountSwitcher = false
                val isSaved = savedAccounts.accounts.any { it.innerTubeCookie == innerTubeCookie }
                if (isLoggedIn && !isSaved) {
                    showUnsavedAccountDialog = true
                } else {
                    navController.navigate(buildLoginRoute())
                }
            },
            onDismiss = { showAccountSwitcher = false },
        )
    }

    if (showTokenEditor) {
        TokenEditorDialog(
            innerTubeCookie = innerTubeCookie,
            visitorData = visitorData,
            dataSyncId = dataSyncId,
            accountNamePref = accountNamePref,
            accountEmail = accountEmail,
            accountChannelHandle = accountChannelHandle,
            onImport = viewModel::importManualAuth,
            onDismiss = { showTokenEditor = false },
        )
    }

    if (showUnsavedAccountDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedAccountDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.bookmark),
                    contentDescription = null,
                )
            },
            title = {
                Text(text = stringResource(R.string.unsaved_account_dialog_title))
            },
            text = {
                Text(text = stringResource(R.string.unsaved_account_dialog_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedAccountDialog = false
                        saveCurrentAccount()
                        navController.navigate(buildLoginRoute())
                    },
                ) {
                    Text(text = stringResource(R.string.unsaved_account_dialog_save_yes))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedAccountDialog = false }) {
                        Text(text = stringResource(R.string.unsaved_account_dialog_cancel))
                    }
                    TextButton(
                        onClick = {
                            showUnsavedAccountDialog = false
                            navController.navigate(buildLoginRoute())
                        },
                    ) {
                        Text(text = stringResource(R.string.unsaved_account_dialog_no_thanks))
                    }
                }
            },
        )
    }
}

@Composable
private fun AccountSummaryCard(
    isLoggedIn: Boolean,
    accountName: String,
    accountEmail: String,
    accountHandle: String,
    accountImageUrl: String?,
    accountSwitcherEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
) {
    val yuma = LocalYumaColors.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .yumaGlassCard(
                    shape = RoundedCornerShape(SettingsDimensions.SegmentedCornerLarge),
                    backgroundColor = yuma.glassBackground,
                    borderColor = yuma.glassBorder,
                ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Surface(
                        modifier = Modifier.size(AvatarSize),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        if (isLoggedIn && !accountImageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = accountImageUrl,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter =
                                        painterResource(
                                            if (isLoggedIn) R.drawable.account else R.drawable.login,
                                        ),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    if (isLoggedIn) {
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.check),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = accountName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (accountHandle.isNotBlank()) {
                        SettingsDescriptionText(
                            text = accountHandle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (accountEmail.isNotBlank() || !isLoggedIn) {
                        SettingsDescriptionText(
                            text = accountEmail.ifBlank { stringResource(R.string.not_logged_in) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.ElevatedLeadingButton(
                            onClick = onPrimaryAction,
                            colors =
                                ButtonDefaults.elevatedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (isLoggedIn) R.drawable.account else R.drawable.login,
                                    ),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isLoggedIn) stringResource(R.string.account) else stringResource(R.string.login),
                            )
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.ElevatedTrailingButton(
                            checked = false,
                            onCheckedChange = { onOpenAccountSwitcher() },
                            enabled = accountSwitcherEnabled,
                            colors =
                                ButtonDefaults.elevatedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.expand_more),
                                contentDescription = stringResource(R.string.saved_accounts),
                                modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize),
                            )
                        }
                    },
                )

                TextButton(
                    onClick = onSecondaryAction,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor =
                                if (isLoggedIn) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        text = if (isLoggedIn) stringResource(R.string.action_logout) else stringResource(R.string.advanced_login),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountSwitcherSheet(
    isLoggedIn: Boolean,
    savedAccounts: SavedAccountCollection,
    activeInnerTubeCookie: String,
    activeDataSyncId: String,
    accountChannelsState: AccountChannelsState,
    onSaveAccount: () -> Unit,
    onSwitchAccount: (SavedAccount) -> Unit,
    onSwitchAccountChannel: (AccountChannelUiModel) -> Unit,
    onRemoveAccount: (SavedAccount) -> Unit,
    onAddAnotherAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accountChannels =
        (accountChannelsState as? AccountChannelsState.Success)
            ?.channels
            ?.items
            .orEmpty()
            .takeIf { it.size > 1 }
            .orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.saved_accounts),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (accountChannels.isNotEmpty()) {
                item(key = "channel_header", contentType = "header") {
                    AccountSheetSectionLabel(text = stringResource(R.string.youtube_channels))
                }
                itemsIndexed(
                    items = accountChannels,
                    key = { index, channel -> "${channel.dataSyncId}:${channel.name}:$index" },
                    contentType = { _, _ -> "channel" },
                ) { index, channel ->
                    val isActive = channel.isSelected || channel.dataSyncId == activeDataSyncId
                    SegmentedListItem(
                        selected = isActive,
                        onClick = {
                            if (!isActive) onSwitchAccountChannel(channel)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = accountChannels.size),
                        colors =
                            ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        leadingContent = {
                            AccountSheetAvatar(
                                imageUrl = channel.thumbnailUrl,
                                fallbackIcon = painterResource(R.drawable.account),
                            )
                        },
                        trailingContent = {
                            if (isActive) {
                                Icon(
                                    painter = painterResource(R.drawable.check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        supportingContent = {
                            val subtitle = channel.channelHandle.ifBlank { channel.byline }
                            if (subtitle.isNotBlank()) {
                                SettingsDescriptionText(
                                    text = subtitle,
                                )
                            }
                        },
                    ) {
                        Text(
                            text = channel.name,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            item(key = "saved_account_header", contentType = "header") {
                AccountSheetSectionLabel(text = stringResource(R.string.saved_accounts))
            }

            if (savedAccounts.accounts.isEmpty()) {
                item(key = "empty_accounts", contentType = "empty") {
                    SegmentedListItem(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                        colors =
                            ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        leadingContent = {
                            AccountSheetAvatar(
                                imageUrl = null,
                                fallbackIcon = painterResource(R.drawable.account),
                            )
                        },
                    ) {
                        Text(text = stringResource(R.string.no_saved_accounts))
                    }
                }
            } else {
                itemsIndexed(
                    items = savedAccounts.accounts,
                    key = { _, account -> account.id },
                    contentType = { _, _ -> "saved_account" },
                ) { index, account ->
                    val isActive = account.innerTubeCookie == activeInnerTubeCookie
                    SegmentedListItem(
                        selected = isActive,
                        onClick = {
                            if (!isActive) onSwitchAccount(account)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shapes =
                            ListItemDefaults.segmentedShapes(
                                index = index,
                                count = savedAccounts.accounts.size,
                            ),
                        colors =
                            ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        leadingContent = {
                            AccountSheetAvatar(
                                imageUrl = null,
                                fallbackIcon = painterResource(R.drawable.account),
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isActive) {
                                    Icon(
                                        painter = painterResource(R.drawable.check),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                OutlinedIconButton(
                                    onClick = { onRemoveAccount(account) },
                                    modifier = Modifier.size(48.dp),
                                    border = null,
                                    colors =
                                        IconButtonDefaults.outlinedIconButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = stringResource(R.string.remove_account),
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            if (account.email.isNotBlank()) {
                                SettingsDescriptionText(
                                    text = account.email,
                                )
                            }
                        },
                    ) {
                        Text(
                            text = account.name.ifBlank { account.email },
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (isLoggedIn) {
                item(key = "account_actions", contentType = "actions") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onSaveAccount()
                                onDismiss()
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.bookmark),
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.save_current_account))
                        }
                        OutlinedButton(
                            onClick = onAddAnotherAccount,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.add_circle),
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.add_another_account))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AccountSheetAvatar(
    imageUrl: String?,
    fallbackIcon: Painter,
) {
    Surface(
        modifier = Modifier.size(RowIconSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = fallbackIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun UpdateBannerStrip(
    latestVersion: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = { Badge(containerColor = MaterialTheme.colorScheme.error) },
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.update),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.new_version_available),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = latestVersion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Medium,
                )
            }

            FilledTonalButton(
                onClick = onClick,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = stringResource(R.string.update_text),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ExpressiveSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        SettingsSectionLabel(text = title)
        Column(
            verticalArrangement = Arrangement.spacedBy(SettingsDimensions.SegmentedItemGap),
            content = content,
        )
    }
}

@Composable
private fun ExpressiveActionRow(
    icon: Painter,
    title: String,
    subtitle: String? = null,
    accent: Color? = null,
    onClick: () -> Unit,
    index: Int,
    count: Int,
) {
    ExpressiveRow(
        index = index,
        count = count,
        onClick = onClick,
        leading = {
            ExpressiveRowIcon(icon = icon, accent = accent ?: MaterialTheme.colorScheme.primary)
        },
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        trailing = {
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                modifier = Modifier.size(SettingsDimensions.ChevronSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ExpressiveSwitchRow(
    icon: Painter,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    ExpressiveRow(
        index = index,
        count = count,
        onClick = { onCheckedChange(!checked) },
        leading = {
            ExpressiveRowIcon(
                icon = icon,
                accent = MaterialTheme.colorScheme.primary,
                muted = !checked,
            )
        },
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 3,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
    )
}

/** The row body shared by every account-settings entry; the glass pane comes from the segment. */
@Composable
private fun ExpressiveRow(
    index: Int,
    count: Int,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    subtitleMaxLines: Int,
    trailing: @Composable () -> Unit,
) {
    SettingsGlassSegment(index = index, count = count, onClick = onClick) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsDimensions.SegmentedItemMinHeight)
                    .padding(
                        horizontal = SettingsDimensions.SegmentedItemPaddingHorizontal,
                        vertical = SettingsDimensions.SegmentedItemPaddingVertical,
                    ),
            horizontalArrangement = Arrangement.spacedBy(SettingsDimensions.SegmentedIconSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SettingsDimensions.RowTextSpacing),
            ) {
                SettingsDescriptionText(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                )
                subtitle?.let { supportingText ->
                    if (subtitleMaxLines == 1) {
                        SettingsDescriptionText(
                            text = supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = subtitleMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            trailing()
        }
    }
}

@Composable
private fun ExpressiveRowIcon(
    icon: Painter,
    accent: Color,
    muted: Boolean = false,
) {
    val container = if (muted) MaterialTheme.colorScheme.surfaceContainerHighest else accent
    val tint =
        if (muted) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            contentColorFor(accent).takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.surface
        }
    Box(
        modifier =
            Modifier
                .size(SettingsDimensions.SegmentedIconBoxSize)
                .clip(CircleShape)
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(SettingsDimensions.SegmentedIconSize),
            tint = tint,
        )
    }
}

@Composable
private fun VersionStamp() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
        )
    }
}

@Composable
private fun TokenEditorDialog(
    innerTubeCookie: String,
    visitorData: String,
    dataSyncId: String,
    accountNamePref: String,
    accountEmail: String,
    accountChannelHandle: String,
    onImport: (String, String?, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val text =
        """
        ***INNERTUBE COOKIE*** =$innerTubeCookie
        ***VISITOR DATA*** =$visitorData
        ***DATASYNC ID*** =$dataSyncId
        ***PO TOKEN*** =${YouTube.poToken.orEmpty()}
        ***ACCOUNT NAME*** =$accountNamePref
        ***ACCOUNT EMAIL*** =$accountEmail
        ***ACCOUNT CHANNEL HANDLE*** =$accountChannelHandle
        """.trimIndent()

    TextFieldDialog(
        initialTextFieldValue = TextFieldValue(text),
        onDone = { data ->
            val fields = data.lineSequence().filter { "=" in it }.associate {
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            }
            onImport(fields["***INNERTUBE COOKIE***"].orEmpty(), fields["***VISITOR DATA***"],
                fields["***DATASYNC ID***"], fields["***PO TOKEN***"])
        },
        onDismiss = onDismiss,
        singleLine = false,
        maxLines = 20,
        isInputValid = {
            hasYouTubeLoginCookie(it)
        },
        extraContent = {
            InfoLabel(text = stringResource(R.string.token_adv_login_description))
        },
    )
}

/** The hosting Activity, needed so microG's consent prompt has somewhere to appear. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
