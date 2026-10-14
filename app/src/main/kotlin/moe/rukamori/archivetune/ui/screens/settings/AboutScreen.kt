/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.UpdateChannelKey
import moe.rukamori.archivetune.defaultUpdateChannel
import moe.rukamori.archivetune.effectiveUpdateChannel
import moe.rukamori.archivetune.formatVersionName
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.preferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.viewmodels.AboutContributorsUiState
import moe.rukamori.archivetune.viewmodels.AboutDependencyLicenseUiCollection
import moe.rukamori.archivetune.viewmodels.AboutDependencyLicensesUiState
import moe.rukamori.archivetune.viewmodels.AboutDialog
import moe.rukamori.archivetune.viewmodels.AboutLinkCollection
import moe.rukamori.archivetune.viewmodels.AboutScreenEffect
import moe.rukamori.archivetune.viewmodels.AboutScreenState
import moe.rukamori.archivetune.viewmodels.AboutTranslationContributorUiCollection
import moe.rukamori.archivetune.viewmodels.AboutTranslationContributorsUiState
import moe.rukamori.archivetune.viewmodels.AboutUiModel
import moe.rukamori.archivetune.viewmodels.AboutViewModel
import moe.rukamori.archivetune.viewmodels.TeamMember

@Composable
fun AboutScreen(
    navController: NavController,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel, uriHandler) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AboutScreenEffect.OpenUri -> uriHandler.openUri(effect.uri)
            }
        }
    }

    AboutScreenContent(
        state = state,
        onNavigateUp = navController::navigateUp,
        onNavigateHome = navController::backToMain,
        onNavigateToChangelog = { channel ->
            navController.navigate("settings/changelog?channel=${channel.name}")
        },
        onNavigateToUpdates = { navController.navigate("settings/update") },
        onOpenUri = viewModel::openUri,
        onRetryContributors = viewModel::retryContributors,
        onOpenTranslationContributors = viewModel::openTranslationContributors,
        onOpenDependencyLicenses = viewModel::openDependencyLicenses,
        onDismissDialog = viewModel::dismissDialog,
        onRetryTranslationContributors = viewModel::retryTranslationContributors,
        onRetryDependencyLicenses = viewModel::retryDependencyLicenses,
    )
}

@Composable
private fun AboutScreenContent(
    state: AboutScreenState,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToChangelog: (UpdateChannel) -> Unit,
    onNavigateToUpdates: () -> Unit,
    onOpenUri: (String) -> Unit,
    onRetryContributors: () -> Unit,
    onOpenTranslationContributors: () -> Unit,
    onOpenDependencyLicenses: () -> Unit,
    onDismissDialog: () -> Unit,
    onRetryTranslationContributors: () -> Unit,
    onRetryDependencyLicenses: () -> Unit,
) {
    // Same rule as the Updates screen and the launch popup: the effective channel decides which
    // changelog feed opens and which pill the header shows.
    val storedChannel by rememberEnumPreference(
        UpdateChannelKey,
        defaultValue = defaultUpdateChannel,
    )
    val effectiveChannel = effectiveUpdateChannel(storedChannel)

    val listState = rememberLazyListState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        onLongClick = onNavigateHome,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
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
        val playerAwareInsets =
            LocalPlayerAwareWindowInsets.current.only(
                WindowInsetsSides.Horizontal,
            )

        when (state) {
            AboutScreenState.Loading -> {
                AboutLoadingContent(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .windowInsetsPadding(playerAwareInsets),
                )
            }

            AboutScreenState.Empty -> {
                AboutMessageContent(
                    message = stringResource(R.string.no_results_found),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .windowInsetsPadding(playerAwareInsets),
                )
            }

            is AboutScreenState.Error -> {
                AboutMessageContent(
                    message = stringResource(state.messageResId),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .windowInsetsPadding(playerAwareInsets),
                )
            }

            is AboutScreenState.Success -> {
                AboutSuccessContent(
                    model = state.model,
                    effectiveChannel = effectiveChannel,
                    onNavigateToChangelog = onNavigateToChangelog,
                    onNavigateToUpdates = onNavigateToUpdates,
                    onOpenUri = onOpenUri,
                    onRetryContributors = onRetryContributors,
                    onOpenTranslationContributors = onOpenTranslationContributors,
                    onOpenDependencyLicenses = onOpenDependencyLicenses,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(playerAwareInsets),
                    contentPadding =
                        PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                        ),
                    listState = listState,
                )
            }
        }
    }

    if (state is AboutScreenState.Success) {
        AboutFullScreenDialogs(
            model = state.model,
            onDismiss = onDismissDialog,
            onRetryTranslationContributors = onRetryTranslationContributors,
            onRetryDependencyLicenses = onRetryDependencyLicenses,
        )
    }
}

@Composable
private fun AboutLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator(modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun AboutMessageContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(AboutSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutFullScreenDialogs(
    model: AboutUiModel,
    onDismiss: () -> Unit,
    onRetryTranslationContributors: () -> Unit,
    onRetryDependencyLicenses: () -> Unit,
) {
    when (model.activeDialog) {
        AboutDialog.NONE -> {
            Unit
        }

        AboutDialog.TRANSLATION_CONTRIBUTORS -> {
            AboutFullScreenDialog(
                title = stringResource(R.string.about_contributor_translation),
                onDismiss = onDismiss,
            ) { modifier ->
                TranslationContributorsDialogContent(
                    state = model.translationContributorsState,
                    onRetry = onRetryTranslationContributors,
                    modifier = modifier,
                )
            }
        }

        AboutDialog.DEPENDENCY_LICENSES -> {
            AboutFullScreenDialog(
                title = stringResource(R.string.about_open_source_licenses),
                onDismiss = onDismiss,
            ) { modifier ->
                DependencyLicensesDialogContent(
                    state = model.dependencyLicensesState,
                    onRetry = onRetryDependencyLicenses,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun AboutFullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                SettingsTopAppBar(
                    title = {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, onLongClick = {}) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.close_dialog),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            content(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun TranslationContributorsDialogContent(
    state: AboutTranslationContributorsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AboutTranslationContributorsUiState.Loading -> {
            DialogStatusContent(
                message = stringResource(R.string.loading),
                showRetry = false,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        AboutTranslationContributorsUiState.Empty -> {
            DialogStatusContent(
                message = stringResource(R.string.no_results_found),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutTranslationContributorsUiState.Error -> {
            DialogStatusContent(
                message = stringResource(state.messageResId),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutTranslationContributorsUiState.Success -> {
            TranslationContributorList(
                contributors = state.contributors,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DependencyLicensesDialogContent(
    state: AboutDependencyLicensesUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AboutDependencyLicensesUiState.Loading -> {
            DialogStatusContent(
                message = stringResource(R.string.loading),
                showRetry = false,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        AboutDependencyLicensesUiState.Empty -> {
            DialogStatusContent(
                message = stringResource(R.string.no_results_found),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutDependencyLicensesUiState.Error -> {
            DialogStatusContent(
                message = stringResource(state.messageResId),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutDependencyLicensesUiState.Success -> {
            DependencyLicenseList(
                licenses = state.licenses,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DialogStatusContent(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(AboutSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!showRetry) {
            LoadingIndicator(modifier = Modifier.size(40.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AboutSpacing.sm),
        )
        if (showRetry) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = AboutSpacing.sm),
            ) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun TranslationContributorList(
    contributors: AboutTranslationContributorUiCollection,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = AboutDimensions.DialogContentMaxWidth)
                    .fillMaxWidth(),
            contentPadding = AboutDimensions.DialogListPadding,
        ) {
            preferenceGroup {
                repeat(contributors.size) { index ->
                    item {
                        val contributor = contributors[index]
                        PreferenceEntry(
                            title = { Text(contributor.language) },
                            description = contributor.contributors,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DependencyLicenseList(
    licenses: AboutDependencyLicenseUiCollection,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = AboutDimensions.DialogContentMaxWidth)
                    .fillMaxWidth(),
            contentPadding = AboutDimensions.DialogListPadding,
        ) {
            preferenceGroup {
                repeat(licenses.size) { index ->
                    item {
                        val dependency = licenses[index]
                        val unknownLicense = stringResource(R.string.about_license_unknown)
                        PreferenceEntry(
                            title = { Text(dependency.name) },
                            description =
                                listOfNotNull(
                                    dependency.version,
                                    dependency.licenses ?: unknownLicense,
                                ).joinToString(separator = " · "),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSuccessContent(
    model: AboutUiModel,
    effectiveChannel: UpdateChannel,
    onNavigateToChangelog: (UpdateChannel) -> Unit,
    onNavigateToUpdates: () -> Unit,
    onOpenUri: (String) -> Unit,
    onRetryContributors: () -> Unit,
    onOpenTranslationContributors: () -> Unit,
    onOpenDependencyLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    listState: LazyListState,
) {
    // Titles are read here rather than inside the group builders: the lazy builder is a plain
    // lambda, so stringResource cannot be called from within it (AppearanceSettings pattern).
    val appSectionTitle = stringResource(R.string.about_section_app)
    val communitySectionTitle = stringResource(R.string.about_section_community)
    val leadDeveloperTitle = stringResource(R.string.about_lead_developer)
    val teamTitle = stringResource(R.string.about_archive_tune_team)
    val acknowledgementsTitle = stringResource(R.string.about_acknowledgements)
    val translatorsTitle = stringResource(R.string.about_translators)
    val contributorsTitle = stringResource(R.string.about_contributors)
    val versionTitle = stringResource(R.string.about_version)
    val changelogTitle = stringResource(R.string.changelog)
    val updatesTitle = stringResource(R.string.updates)
    val licensesTitle = stringResource(R.string.about_open_source_licenses)
    val translationTitle = stringResource(R.string.about_contributor_translation)
    val moreTitle = stringResource(R.string.more)
    val versionLine = formatVersionName()

    // The whole list is capped and centred, not just its header and labels: preferenceGroup rows
    // only fill the width they are given, so they would otherwise span wide tablet/TV screens.
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .widthIn(max = AboutDimensions.ScreenContentMaxWidth)
                    .fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "header", contentType = "about_header") {
                AboutContentContainer {
                    AboutHeaderCard(
                        model = model,
                        channel = effectiveChannel,
                        modifier =
                            Modifier
                                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                                .fillMaxWidth(),
                    )
                }
            }

            AboutLabelItem(appSectionTitle)

            preferenceGroup {
                item {
                    PreferenceEntry(
                        title = { Text(versionTitle) },
                        description = versionLine,
                        icon = { Icon(painterResource(R.drawable.info), null) },
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(changelogTitle) },
                        icon = { Icon(painterResource(R.drawable.history), null) },
                        onClick = { onNavigateToChangelog(effectiveChannel) },
                    )
                }
                item(visible = BuildConfig.UPDATER_AVAILABLE) {
                    PreferenceEntry(
                        title = { Text(updatesTitle) },
                        icon = { Icon(painterResource(R.drawable.sync), null) },
                        onClick = onNavigateToUpdates,
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(licensesTitle) },
                        icon = { Icon(painterResource(R.drawable.solar_info), null) },
                        onClick = onOpenDependencyLicenses,
                    )
                }
            }

            AboutLabelItem(communitySectionTitle)

            preferenceGroup {
                repeat(model.primaryLinks.size) { index ->
                    val link = model.primaryLinks[index]
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(link.labelResId)) },
                            description = link.url.removePrefix("https://"),
                            icon = { Icon(painterResource(link.iconResId), null) },
                            onClick = { onOpenUri(link.url) },
                        )
                    }
                }
            }

            AboutLabelItem(leadDeveloperTitle)

            preferenceGroup {
                repeat(model.leadDevelopers.size) { index ->
                    item {
                        AboutMemberRow(
                            member = model.leadDevelopers[index],
                            index = index,
                            count = model.leadDevelopers.size,
                            onOpenUri = onOpenUri,
                        )
                    }
                }
            }

            AboutLabelItem(teamTitle)

            preferenceGroup {
                repeat(model.collaborators.size) { index ->
                    item {
                        AboutMemberRow(
                            member = model.collaborators[index],
                            index = index,
                            count = model.collaborators.size,
                            onOpenUri = onOpenUri,
                        )
                    }
                }
            }

            AboutLabelItem(acknowledgementsTitle)

            preferenceGroup {
                repeat(model.respecters.size) { index ->
                    item {
                        AboutMemberRow(
                            member = model.respecters[index],
                            index = index,
                            count = model.respecters.size,
                            onOpenUri = onOpenUri,
                        )
                    }
                }
            }

            AboutLabelItem(translatorsTitle)

            preferenceGroup {
                item {
                    PreferenceEntry(
                        title = { Text(translationTitle) },
                        icon = { Icon(painterResource(R.drawable.translate), null) },
                        onClick = onOpenTranslationContributors,
                    )
                }
            }

            AboutLabelItem(contributorsTitle)

            preferenceGroup {
                when (val contributorsState = model.contributorsState) {
                    AboutContributorsUiState.Loading -> {
                        item {
                            AboutContributorsStatusSegment(
                                message = stringResource(R.string.loading),
                                showRetry = false,
                                onRetry = onRetryContributors,
                            )
                        }
                    }

                    AboutContributorsUiState.Empty -> {
                        item {
                            AboutContributorsStatusSegment(
                                message = stringResource(R.string.no_results_found),
                                showRetry = true,
                                onRetry = onRetryContributors,
                            )
                        }
                    }

                    is AboutContributorsUiState.Error -> {
                        item {
                            AboutContributorsStatusSegment(
                                message = stringResource(contributorsState.messageResId),
                                showRetry = true,
                                onRetry = onRetryContributors,
                            )
                        }
                    }

                    is AboutContributorsUiState.Success -> {
                        val contributors = contributorsState.contributors
                        repeat(contributors.size) { index ->
                            item {
                                AboutContributorRow(
                                    login = contributors[index].login,
                                    avatarUrl = contributors[index].avatarUrl,
                                    profileUrl = contributors[index].profileUrl,
                                    index = index,
                                    count = contributors.size + 1,
                                    onOpenProfile = onOpenUri,
                                )
                            }
                        }
                        item {
                            PreferenceEntry(
                                title = { Text(moreTitle) },
                                icon = { Icon(painterResource(R.drawable.add_circle), null) },
                                onClick = { onOpenUri(model.contributorsReadMoreUrl) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A section label as its own lazy item, capped at the shared content width like every row. */
private fun LazyListScope.AboutLabelItem(text: String) {
    item(contentType = "about_label") {
        AboutContentContainer {
            SettingsSectionLabel(text = text)
        }
    }
}

@Composable
private fun AboutContentContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = AboutDimensions.ScreenContentMaxWidth)
                    .fillMaxWidth(),
        ) {
            content()
        }
    }
}

/**
 * The hero card: app icon (deliberately not clickable), app name, formatted version and pills for
 * the effective update channel, the distribution and the ABI (or Debug). Modelled on the
 * current-version panel of the Updates screen.
 */
@Composable
private fun AboutHeaderCard(
    model: AboutUiModel,
    channel: UpdateChannel,
    modifier: Modifier = Modifier,
) {
    val channelLabel =
        when (channel) {
            UpdateChannel.STABLE -> stringResource(R.string.channel_stable)
            UpdateChannel.NIGHTLY -> stringResource(R.string.channel_nightly)
        }
    val channelContainerColor =
        if (channel != UpdateChannel.STABLE) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val channelContentColor =
        if (channel != UpdateChannel.STABLE) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    val distributionLabel = BuildConfig.DISTRIBUTION.uppercase()
    val abiLabel =
        if (BuildConfig.DEBUG) {
            stringResource(R.string.about_badge_debug)
        } else {
            BuildConfig.ARCHITECTURE.uppercase()
        }

    SettingsGlassSegment(index = 0, count = 1, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AboutSpacing.xs),
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                val iconTint = MaterialTheme.colorScheme.onPrimaryContainer
                val iconColorFilter = remember(iconTint) { ColorFilter.tint(iconTint) }
                Image(
                    painter = painterResource(R.drawable.about_splash),
                    contentDescription = null,
                    colorFilter = iconColorFilter,
                    modifier =
                        Modifier
                            .padding(AboutSpacing.xs)
                            .size(64.dp),
                )
            }
            Text(
                text = stringResource(model.appNameResId),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatVersionName(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AboutSpacing.xs, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(AboutSpacing.xs),
            ) {
                AboutMetadataPill(
                    text = channelLabel,
                    containerColor = channelContainerColor,
                    contentColor = channelContentColor,
                )
                AboutMetadataPill(text = distributionLabel)
                AboutMetadataPill(text = abiLabel)
            }
        }
    }
}

@Composable
private fun AboutMetadataPill(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier =
                Modifier.padding(
                    horizontal = SettingsDimensions.SegmentedBadgePaddingH,
                    vertical = SettingsDimensions.SegmentedBadgePaddingV,
                ),
        )
    }
}

/**
 * A credit row: the avatar sits directly on the glass segment — [PreferenceEntry]'s icon slot
 * paints a filled expressive blob behind its content, which would put a colour disc behind the
 * picture. Row anatomy (heights, paddings, typography) matches [PreferenceEntry].
 */
@Composable
private fun AboutMemberRow(
    member: TeamMember,
    index: Int,
    count: Int,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowOnClick: (() -> Unit)? =
        remember(member.profileUrl, onOpenUri) {
            member.profileUrl?.let { url -> { onOpenUri(url) } }
        }

    SettingsGlassSegment(
        index = index,
        count = count,
        modifier = modifier,
        onClick = rowOnClick,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsDimensions.SegmentedItemMinHeight)
                    .padding(
                        horizontal = SettingsDimensions.SegmentedItemPaddingHorizontal,
                        vertical = SettingsDimensions.SegmentedItemPaddingVertical,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = member.avatarUrl,
                contentDescription = member.name,
                modifier =
                    Modifier
                        .size(SettingsDimensions.SegmentedIconBoxSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(modifier = Modifier.width(SettingsDimensions.SegmentedIconSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(SettingsDimensions.SegmentedRowSpacing))
                Text(
                    text = stringResource(member.positionResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (member.links.size > 0) {
                Spacer(modifier = Modifier.width(SettingsDimensions.SegmentedIconSpacing))
                MemberLinkActions(
                    links = member.links,
                    onOpenUri = onOpenUri,
                )
            }
        }
    }
}

@Composable
private fun MemberLinkActions(
    links: AboutLinkCollection,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.widthIn(max = 156.dp),
        horizontalArrangement = Arrangement.spacedBy(AboutSpacing.xxs, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(AboutSpacing.xxs),
    ) {
        repeat(links.size) { index ->
            val link = links[index]
            val onClick = remember(link.url, onOpenUri) { { onOpenUri(link.url) } }

            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(link.iconResId),
                    contentDescription = stringResource(link.labelResId),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutContributorRow(
    login: String,
    avatarUrl: String,
    profileUrl: String,
    index: Int,
    count: Int,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onClick =
        remember(profileUrl, onOpenProfile) {
            { onOpenProfile(profileUrl) }
        }

    SettingsGlassSegment(
        index = index,
        count = count,
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsDimensions.SegmentedItemMinHeight)
                    .padding(
                        horizontal = SettingsDimensions.SegmentedItemPaddingHorizontal,
                        vertical = SettingsDimensions.SegmentedItemPaddingVertical,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = login,
                modifier =
                    Modifier
                        .size(SettingsDimensions.SegmentedIconBoxSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(modifier = Modifier.width(SettingsDimensions.SegmentedIconSpacing))
            Text(
                text = login,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AboutContributorsStatusSegment(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGlassSegment(index = 0, count = 1, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AboutSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AboutSpacing.sm),
        ) {
            if (!showRetry) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.retry))
                }
            }
        }
    }
}

private object AboutDimensions {
    val ScreenContentMaxWidth = 840.dp
    val DialogContentMaxWidth = 920.dp
    val DialogListPadding = PaddingValues(vertical = 8.dp)
}

private object AboutSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 16.dp
    val md = 24.dp
}
