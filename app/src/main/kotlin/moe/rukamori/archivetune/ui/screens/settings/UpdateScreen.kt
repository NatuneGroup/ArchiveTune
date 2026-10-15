/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableUpdateNotificationKey
import moe.rukamori.archivetune.constants.PersistentUpdatePopupKey
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.UpdateChannelKey
import moe.rukamori.archivetune.defaultUpdateChannel
import moe.rukamori.archivetune.effectiveUpdateChannel
import moe.rukamori.archivetune.ui.component.BottomSheetPage
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.MarkdownText
import moe.rukamori.archivetune.ui.theme.LocalYumaColors
import moe.rukamori.archivetune.ui.theme.yumaGlassCard
import moe.rukamori.archivetune.ui.utils.appBarScrollBehavior
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.AppUpdateInstaller
import moe.rukamori.archivetune.utils.GitCommit
import moe.rukamori.archivetune.utils.UpdateNotificationManager
import moe.rukamori.archivetune.utils.UpdateOffer
import moe.rukamori.archivetune.utils.Updater
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    navController: NavController,
    onUpToDate: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = appBarScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val useWideLayout =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    val maximumContentWidth = if (useWideLayout) 1_040.dp else 680.dp
    val (enableUpdateNotification, onEnableUpdateNotificationChange) =
        rememberPreference(
            EnableUpdateNotificationKey,
            defaultValue = false,
        )
    val (persistentUpdatePopup, onPersistentUpdatePopupChange) =
        rememberPreference(PersistentUpdatePopupKey, defaultValue = false)
    val (storedUpdateChannel) =
        rememberEnumPreference(
            UpdateChannelKey,
            defaultValue = defaultUpdateChannel,
        )

    val updateChannel = effectiveUpdateChannel(storedUpdateChannel)
    val channelRevision by Updater.selectionChanges.collectAsStateWithLifecycle()

    var commits by remember(updateChannel, channelRevision) { mutableStateOf<List<GitCommit>>(emptyList()) }
    var isLoadingCommits by remember(updateChannel, channelRevision) { mutableStateOf(true) }
    var latestVersion by remember(updateChannel, channelRevision) { mutableStateOf<String?>(null) }
    var latestOffer by remember(updateChannel, channelRevision) { mutableStateOf<UpdateOffer?>(null) }
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    var showNightlyChannelConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showEnableUpdateNotificationConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }
    val isUpdateAvailable = BuildConfig.UPDATER_AVAILABLE && latestOffer?.channel == updateChannel
    val updateSheetState = remember { BottomSheetPageState() }
    var updateSheetLoading by remember(updateChannel, channelRevision) { mutableStateOf(false) }
    var updateSheetVersion by remember(updateChannel, channelRevision) { mutableStateOf<String?>(null) }
    var updateSheetNotes by remember(updateChannel, channelRevision) { mutableStateOf<String?>(null) }
    var updateSheetError by remember(updateChannel, channelRevision) { mutableStateOf<String?>(null) }
    var updateSheetIsSameVersion by remember(updateChannel, channelRevision) { mutableStateOf(false) }
    var updateCheckJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showUpdateUpToDateDialog by remember(updateChannel, channelRevision) { mutableStateOf(false) }
    var showUpdateErrorDialog by remember(updateChannel, channelRevision) { mutableStateOf(false) }
    var updateSheetOffer by remember(updateChannel, channelRevision) { mutableStateOf<UpdateOffer?>(null) }
    var updateSheetRevision by remember(updateChannel, channelRevision) { mutableStateOf(Updater.selectionRevision) }
    val useInAppUpdateInstaller = BuildConfig.DISTRIBUTION == "gms"
    // The download itself lives in AppUpdateInstaller; this screen only renders whichever state the
    // process-scoped job is in, so a navigation away and back does not lose it.
    val downloadState by AppUpdateInstaller.downloadState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val openUpdateUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
        }
    }

    val onUpdateChannelChange: (UpdateChannel) -> Unit = { channel ->
        if (channel != updateChannel) {
            coroutineScope.launch {
                try {
                    Updater.selectChannel(context, channel)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    snackbarHostState.showSnackbar(error.message ?: context.getString(R.string.error_unknown))
                }
            }
        }
    }
    val installUpdate: (UpdateOffer, Long) -> Unit = { offer, revision ->
        coroutineScope.launch {
            if (Updater.isCurrentSelection(offer.channel, revision)) {
                updateSheetState.dismiss()
                if (useInAppUpdateInstaller) {
                    updateSheetError = null
                    showUpdateErrorDialog = false
                    AppUpdateInstaller.startDownload(context, offer)
                } else {
                    openUpdateUrl(offer.downloadUrl)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { updateCheckJob?.cancel() }
    }

    // Terminal states are consumed exactly once. A download that finished while the reader was on
    // another screen waits for them to return instead of raising the installer from the background,
    // where Android is free to discard the activity launch.
    val downloadOutcome =
        downloadState.takeIf {
            it is AppUpdateInstaller.DownloadState.ReadyToInstall ||
                it is AppUpdateInstaller.DownloadState.Failed
        }

    LaunchedEffect(downloadOutcome) {
        when (val outcome = downloadOutcome) {
            is AppUpdateInstaller.DownloadState.ReadyToInstall -> {
                AppUpdateInstaller.acknowledgeResult()
                if (AppUpdateInstaller.installStagedUpdate(context, outcome.offer)) {
                    // A reused APK was already announced as downloaded when it first arrived.
                    if (!outcome.reused) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.update_download_complete_offer, outcome.offer.channel.name, outcome.offer.versionName),
                            )
                        }
                    }
                } else {
                    // The staged APK is gone (cache evicted while the reader was away) or unreadable.
                    updateSheetError = context.getString(R.string.error_unknown)
                    showUpdateErrorDialog = true
                }
            }

            is AppUpdateInstaller.DownloadState.Failed -> {
                updateSheetError = context.getString(
                    R.string.update_offer_failure, outcome.offer.channel.name, outcome.offer.versionName,
                    outcome.message ?: context.getString(R.string.error_unknown),
                )
                showUpdateErrorDialog = true
                AppUpdateInstaller.acknowledgeResult()
            }

            else -> Unit
        }
    }

    val updateSheetContent: @Composable ColumnScope.() -> Unit = {
        Text(
            text = stringResource(R.string.new_update_available),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = updateSheetVersion ?: "",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
        ) {
            val notes = updateSheetNotes
            if (notes != null && notes.isNotBlank()) {
                MarkdownText(
                    markdown = notes,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = stringResource(R.string.release_notes_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        val offer = updateSheetOffer
        val revision = updateSheetRevision
        Button(
            onClick = { if (offer != null) installUpdate(offer, revision) },
            enabled = offer != null && offer.channel == updateChannel,
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(text = stringResource(R.string.update_text))
        }

        Spacer(Modifier.height(12.dp))
    }

    val onCheckForUpdate: () -> Unit = {
        if (updateCheckJob?.isActive != true) {
            updateSheetLoading = true
            updateSheetVersion = null
            updateSheetNotes = null
            updateSheetError = null
            updateSheetIsSameVersion = false
            showUpdateUpToDateDialog = false
            showUpdateErrorDialog = false

            updateCheckJob =
                coroutineScope.launch {
                    val requestChannel = updateChannel
                    val revision = Updater.selectionRevision
                    val releaseResult = Updater.getLatestRelease(requestChannel, forceRefresh = true)
                    if (!Updater.isCurrentSelection(requestChannel, revision)) return@launch

                    updateSheetLoading = false

                    releaseResult
                        .onSuccess { release ->
                            val offer = Updater.eligibleOffer(release, requestChannel)
                            val version = Updater.releaseVersionName(release, requestChannel)
                            latestVersion = version
                            latestOffer = offer
                            updateSheetOffer = offer
                            updateSheetRevision = revision
                            updateSheetNotes = release.body
                            updateSheetIsSameVersion = offer == null
                            updateSheetVersion = version

                            if (updateSheetIsSameVersion) {
                                showUpdateUpToDateDialog = true
                                onUpToDate()
                            } else {
                                updateSheetState.show(updateSheetContent)
                            }
                        }.onFailure { error ->
                            latestVersion = ""
                            latestOffer = null
                            updateSheetOffer = null
                            updateSheetError = error.message ?: context.getString(R.string.error_unknown)
                            showUpdateErrorDialog = true
                        }
                }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasNotificationPermission = isGranted
            if (isGranted) {
                onEnableUpdateNotificationChange(true)
                UpdateNotificationManager.schedulePeriodicUpdateCheck(context)
            }
        }

    if (showEnableUpdateNotificationConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEnableUpdateNotificationConfirmDialog = false },
            title = { Text(stringResource(R.string.enable_update_notification)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.updates_channel_warning_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_source),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.updates_channel_warning_nightly_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.updates_nightly_hosting_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_nightly_risk),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        text = stringResource(R.string.updates_channel_warning_nightly_unstable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.updates_channel_warning_nightly_acknowledgement),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableUpdateNotificationConfirmDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onEnableUpdateNotificationChange(true)
                            UpdateNotificationManager.schedulePeriodicUpdateCheck(context)
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableUpdateNotificationConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showNightlyChannelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showNightlyChannelConfirmDialog = false },
            title = { Text(stringResource(R.string.channel_nightly)) },
            text = {
                Text(
                    text = stringResource(R.string.updates_nightly_channel_confirmation),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNightlyChannelConfirmDialog = false
                        onUpdateChannelChange(UpdateChannel.NIGHTLY)
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNightlyChannelConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    LaunchedEffect(updateChannel, channelRevision) {
        updateCheckJob?.cancel()
        updateCheckJob = null
        updateSheetState.dismiss()
        if (!BuildConfig.UPDATER_AVAILABLE) {
            isLoadingCommits = false
            return@LaunchedEffect
        }
        val revision = Updater.selectionRevision
        val releaseResult = Updater.getLatestRelease(updateChannel)
        if (!Updater.isCurrentSelection(updateChannel, revision)) return@LaunchedEffect
        releaseResult.onSuccess { release ->
            latestVersion = Updater.releaseVersionName(release, updateChannel)
            latestOffer = Updater.eligibleOffer(release, updateChannel)
            if (latestOffer == null) onUpToDate()
        }.onFailure {
            latestVersion = ""
            latestOffer = null
        }
        val commitResult = Updater.getCommitHistory(50, branch = Updater.branchForChannel(updateChannel))
        if (!Updater.isCurrentSelection(updateChannel, revision)) return@LaunchedEffect
        commits = commitResult.getOrDefault(emptyList())
        isLoadingCommits = false
    }

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "rotation",
    )
    val topBarSubtitle =
        when (updateChannel) {
            UpdateChannel.NIGHTLY -> stringResource(R.string.updates_subtitle_nightly)
            UpdateChannel.STABLE -> stringResource(R.string.updates_subtitle_stable)
        }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.updates),
                        fontWeight = FontWeight.Bold,
                    )
                },
                subtitle = {
                    Text(
                        text = topBarSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
                actions = {},
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = Color.Transparent,
                    ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal,
                        ),
                    ),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = SettingsDimensions.ScreenBottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "update_dashboard", contentType = "dashboard") {
                UpdateDashboard(
                    currentVersion = BuildConfig.VERSION_NAME,
                    latestVersion = latestVersion,
                    updateChannel = updateChannel,
                    isUpdateAvailable = isUpdateAvailable,
                    enableUpdateNotification = enableUpdateNotification,
                    persistentUpdatePopup = persistentUpdatePopup,
                    useWideLayout = useWideLayout,
                    onCheckForUpdate = onCheckForUpdate,
                    onOpenChangelog = {
                        navController.navigate("settings/changelog?channel=$updateChannel")
                    },
                    onPersistentUpdatePopupChange = onPersistentUpdatePopupChange,
                    onUpdateNotificationChange = { enabled ->
                        if (enabled) {
                            showEnableUpdateNotificationConfirmDialog = true
                        } else {
                            onEnableUpdateNotificationChange(false)
                            UpdateNotificationManager.cancelPeriodicUpdateCheck(context)
                        }
                    },
                    onStableSelected = { onUpdateChannelChange(UpdateChannel.STABLE) },
                    onNightlySelected = {
                        if (updateChannel != UpdateChannel.NIGHTLY) {
                            showNightlyChannelConfirmDialog = true
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = maximumContentWidth),
                )
            }

            // Task 5: unofficial fork warning pill. Sits right under the dashboard so the user
            // sees it before checking for updates or reading commits. Reminds them this is a
            // personal fork — issues are unlikely to be fixed unless they're serious.
            item(key = "fork_warning", contentType = "warning") {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = maximumContentWidth),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(R.string.fork_warning_unofficial),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            item(key = "commit_history", contentType = "commit_history") {
                CommitHistorySection(
                    commits = commits,
                    isLoading = isLoadingCommits,
                    isExpanded = isExpanded,
                    rotationAngle = rotationAngle,
                    onToggleExpanded = { isExpanded = !isExpanded },
                    onCommitClick = { commit -> uriHandler.openUri(commit.url) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = maximumContentWidth),
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomSheetPage(
            state = updateSheetState,
            modifier = Modifier.align(Alignment.BottomCenter),
            contentWindowInsets = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom),
        )
    }

    if (updateSheetLoading) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.updates_status_checking),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            confirmButton = {},
        )
    }

    // No local "is the dialog open" flag: an in-flight download is exactly what the dialog shows, so
    // returning to this screen mid-download puts the progress back on screen.
    val inFlightDownload = downloadState as? AppUpdateInstaller.DownloadState.Downloading
    if (inFlightDownload != null) {
        val progress = inFlightDownload.fraction
        val animatedProgress by animateFloatAsState(
            targetValue = progress ?: 0f,
            animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "updateDownloadProgress",
        )
        val centeredDialogContentModifier = remember { Modifier.fillMaxWidth() }
        val determinateProgressModifier = remember { Modifier.size(96.dp) }
        val determinateIndicatorModifier = remember { Modifier.fillMaxSize() }
        val indeterminateIndicatorModifier = remember { Modifier.size(72.dp) }

        val downloadTitle =
            buildString {
                when (inFlightDownload.offer.channel) {
                    UpdateChannel.NIGHTLY -> {
                        append(context.getString(R.string.app_name))
                        append(' ')
                        append(context.getString(R.string.channel_nightly))
                    }

                    UpdateChannel.STABLE -> {
                        append(context.getString(R.string.app_name))
                        append(' ')
                        append(context.getString(R.string.channel_stable))
                    }
                }
                append(' ')
                append(inFlightDownload.offer.versionName)
            }

        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = downloadTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = centeredDialogContentModifier,
                )
            },
            text = {
                Column(
                    modifier = centeredDialogContentModifier,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (progress != null) {
                        Box(
                            modifier = determinateProgressModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator(
                                progress = { animatedProgress },
                                modifier = determinateIndicatorModifier,
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.download_progress_percent,
                                        (animatedProgress * 100f).roundToInt().coerceIn(0, 100),
                                    ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        CircularWavyProgressIndicator(
                            modifier = indeterminateIndicatorModifier,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { AppUpdateInstaller.cancelDownload() },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showUpdateUpToDateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateUpToDateDialog = false },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.update_no_installable_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.update_no_installable_build),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                TextButton(onClick = { showUpdateUpToDateDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showUpdateErrorDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateErrorDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.error_loading_changelog),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    text = updateSheetError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { showUpdateErrorDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun UpdateDashboard(
    currentVersion: String,
    latestVersion: String?,
    updateChannel: UpdateChannel,
    isUpdateAvailable: Boolean,
    enableUpdateNotification: Boolean,
    persistentUpdatePopup: Boolean,
    useWideLayout: Boolean,
    modifier: Modifier = Modifier,
    onCheckForUpdate: () -> Unit,
    onOpenChangelog: () -> Unit,
    onUpdateNotificationChange: (Boolean) -> Unit,
    onPersistentUpdatePopupChange: (Boolean) -> Unit,
    onStableSelected: () -> Unit,
    onNightlySelected: () -> Unit,
) {
    if (useWideLayout) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            UpdateStatusPanel(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                updateChannel = updateChannel,
                isUpdateAvailable = isUpdateAvailable,
                onCheckForUpdate = onCheckForUpdate,
                onOpenChangelog = onOpenChangelog,
                modifier = Modifier.weight(1f),
            )
            UpdatePreferencesPanel(
                enableUpdateNotification = enableUpdateNotification,
                persistentUpdatePopup = persistentUpdatePopup,
                updateChannel = updateChannel,
                onUpdateNotificationChange = onUpdateNotificationChange,
                onPersistentUpdatePopupChange = onPersistentUpdatePopupChange,
                onStableSelected = onStableSelected,
                onNightlySelected = onNightlySelected,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpdateStatusPanel(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                updateChannel = updateChannel,
                isUpdateAvailable = isUpdateAvailable,
                onCheckForUpdate = onCheckForUpdate,
                onOpenChangelog = onOpenChangelog,
            )
            UpdatePreferencesPanel(
                enableUpdateNotification = enableUpdateNotification,
                persistentUpdatePopup = persistentUpdatePopup,
                updateChannel = updateChannel,
                onUpdateNotificationChange = onUpdateNotificationChange,
                onPersistentUpdatePopupChange = onPersistentUpdatePopupChange,
                onStableSelected = onStableSelected,
                onNightlySelected = onNightlySelected,
            )
        }
    }
}

@Composable
private fun UpdateStatusPanel(
    currentVersion: String,
    latestVersion: String?,
    updateChannel: UpdateChannel,
    isUpdateAvailable: Boolean,
    modifier: Modifier = Modifier,
    onCheckForUpdate: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    val channelLabel =
        when (updateChannel) {
            UpdateChannel.STABLE -> stringResource(R.string.channel_stable)
            UpdateChannel.NIGHTLY -> stringResource(R.string.channel_nightly)
        }
    val supportingText =
        when {
            latestVersion == null -> stringResource(R.string.updates_status_checking)
            latestVersion.isEmpty() -> stringResource(R.string.update_feed_unavailable)
            isUpdateAvailable -> stringResource(R.string.latest_version_format, latestVersion)
            else -> stringResource(R.string.update_no_installable_title)
        }
    val statusContainerColor =
        if (isUpdateAvailable) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val statusContentColor =
        if (isUpdateAvailable) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    val channelContainerColor =
        if (updateChannel != UpdateChannel.STABLE) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val channelContentColor =
        if (updateChannel != UpdateChannel.STABLE) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    val statusShape = MaterialShapes.SoftBurst.toShape()

    val panelYuma = LocalYumaColors.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .yumaGlassCard(
                    shape = RoundedCornerShape(SettingsDimensions.SegmentedCornerLarge),
                    backgroundColor = panelYuma.glassBackground,
                    borderColor = panelYuma.glassBorder,
                ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = statusShape,
                    color = statusContainerColor,
                    contentColor = statusContentColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.update),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.current_version),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = channelContainerColor,
                            contentColor = channelContentColor,
                        ) {
                            Text(
                                text = channelLabel,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Text(
                        text = currentVersion,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onCheckForUpdate,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.sync),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.check_for_update))
                }

                TextButton(
                    onClick = onOpenChangelog,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.update),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.view_changelog))
                }
            }
        }
    }
}

@Composable
private fun UpdatePreferencesPanel(
    enableUpdateNotification: Boolean,
    persistentUpdatePopup: Boolean,
    updateChannel: UpdateChannel,
    modifier: Modifier = Modifier,
    onUpdateNotificationChange: (Boolean) -> Unit,
    onPersistentUpdatePopupChange: (Boolean) -> Unit,
    onStableSelected: () -> Unit,
    onNightlySelected: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGlassSegment(index = 0, count = 1) {
            SegmentedListItem(
                onClick = { onUpdateNotificationChange(!enableUpdateNotification) },
                shapes =
                    ListItemDefaults.shapes(
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ListItemDefaults.segmentedColors(
                        containerColor = Color.Transparent,
                    ),
                leadingContent = {
                    FeatureIcon(
                        iconRes = R.drawable.new_release,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = enableUpdateNotification,
                        onCheckedChange = null,
                    )
                },
                supportingContent = {
                    Text(text = stringResource(R.string.enable_update_notification_channel_desc))
                },
                content = {
                    Text(text = stringResource(R.string.enable_update_notification))
                },
            )
        }

        SettingsGlassSegment(index = 0, count = 1) {
            SegmentedListItem(
                onClick = { onPersistentUpdatePopupChange(!persistentUpdatePopup) },
                shapes =
                    ListItemDefaults.shapes(
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ListItemDefaults.segmentedColors(
                        containerColor = Color.Transparent,
                    ),
                leadingContent = {
                    FeatureIcon(
                        iconRes = R.drawable.ic_repeat,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = persistentUpdatePopup,
                        onCheckedChange = null,
                    )
                },
                supportingContent = {
                    Text(text = stringResource(R.string.persistent_update_popup_desc))
                },
                content = {
                    Text(text = stringResource(R.string.persistent_update_popup))
                },
            )
        }

        val cardYuma = LocalYumaColors.current
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .yumaGlassCard(
                        shape = RoundedCornerShape(SettingsDimensions.SegmentedCornerLarge),
                        backgroundColor = cardYuma.glassBackground,
                        borderColor = cardYuma.glassBorder,
                    ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeatureIcon(
                        iconRes = R.drawable.tune,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.update_channel),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.update_channel_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = updateChannel == UpdateChannel.STABLE,
                        onClick = onStableSelected,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {},
                    ) {
                        Text(text = stringResource(R.string.channel_stable))
                    }
                    SegmentedButton(
                        selected = updateChannel == UpdateChannel.NIGHTLY,
                        onClick = onNightlySelected,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {},
                    ) {
                        Text(text = stringResource(R.string.channel_nightly))
                    }
                }
                Text(
                    text = stringResource(R.string.update_choice_newer_build_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommitHistorySection(
    commits: List<GitCommit>,
    isLoading: Boolean,
    isExpanded: Boolean,
    rotationAngle: Float,
    modifier: Modifier = Modifier,
    onToggleExpanded: () -> Unit,
    onCommitClick: (GitCommit) -> Unit,
) {
    Column(
        modifier = modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsGlassSegment(index = 0, count = 1) {
            SegmentedListItem(
                onClick = onToggleExpanded,
                shapes =
                    ListItemDefaults.shapes(
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ListItemDefaults.segmentedColors(
                        containerColor = Color.Transparent,
                    ),
                leadingContent = {
                    FeatureIcon(
                        iconRes = R.drawable.history,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = null,
                        modifier = Modifier.rotate(rotationAngle),
                    )
                },
                supportingContent = {
                    Text(
                        text =
                            when {
                                isLoading -> {
                                    stringResource(R.string.updates_loading_commits)
                                }

                                commits.isEmpty() -> {
                                    stringResource(R.string.updates_no_commits)
                                }

                                else -> {
                                    stringResource(
                                        R.string.updates_recent_commits_count,
                                        commits.size,
                                    )
                                }
                            },
                    )
                },
                content = {
                    Text(
                        text = stringResource(R.string.recent_commits),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            when {
                isLoading -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LoadingIndicator(modifier = Modifier.size(32.dp))
                                Text(
                                    text = stringResource(R.string.updates_loading_commits),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                commits.isEmpty() -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            text = stringResource(R.string.updates_no_commits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 32.dp),
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        commits.forEachIndexed { index, commit ->
                            key(commit.sha) {
                                CommitItem(
                                    commit = commit,
                                    index = index,
                                    count = commits.size,
                                    onClick = { onCommitClick(commit) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureIcon(
    @DrawableRes iconRes: Int,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier =
                Modifier
                    .padding(12.dp)
                    .size(22.dp),
        )
    }
}

@Composable
private fun CommitItem(
    commit: GitCommit,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    SettingsGlassSegment(index = index, count = count) {
        SegmentedListItem(
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
            colors =
                ListItemDefaults.segmentedColors(
                    containerColor = Color.Transparent,
                ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            leadingContent = {
                CommitAvatar(avatarUrl = commit.authorAvatarUrl)
            },
            trailingContent = {
                Icon(
                    painter = painterResource(R.drawable.arrow_forward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = commit.sha,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text =
                            if (commit.date.isNotEmpty()) {
                                commit.author + " - " + formatCommitDate(commit.date)
                            } else {
                                commit.author
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            content = {
                Text(
                    text = commit.message,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

@Composable
private fun CommitAvatar(avatarUrl: String?) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.github),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun formatCommitDate(isoDate: String): String =
    try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        inputFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = inputFormat.parse(isoDate)
        val outputFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        outputFormat.format(date!!)
    } catch (e: Exception) {
        isoDate.take(10)
    }
