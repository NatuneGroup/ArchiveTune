/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.canvas.CanvasHealth
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.viewmodels.CanvasCacheOption
import moe.rukamori.archivetune.viewmodels.CanvasSettingsAction
import moe.rukamori.archivetune.viewmodels.CanvasSettingsDialog
import moe.rukamori.archivetune.viewmodels.CanvasSettingsState
import moe.rukamori.archivetune.viewmodels.CanvasSettingsUiModel
import moe.rukamori.archivetune.viewmodels.CanvasSettingsViewModel

@Composable
fun CanvasSettings(
    navController: NavController,
    viewModel: CanvasSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onAction = remember(viewModel) { viewModel::onAction }
    val onBack: () -> Unit = remember(navController) {
        {
            navController.navigateUp()
            Unit
        }
    }
    CanvasSettingsContent(state = state, onAction = onAction, onBack = onBack)
}

@Composable
private fun CanvasSettingsContent(
    state: CanvasSettingsState,
    onAction: (CanvasSettingsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archivetune_canvas)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.back_button_desc))
                    }
                },
            )
        },
    ) { padding ->
        val topPadding = padding.calculateTopPadding()
        val insets = LocalPlayerAwareWindowInsets.current
        val contentModifier = remember(topPadding, insets) {
            Modifier.padding(top = topPadding)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        }
        when (state) {
            CanvasSettingsState.Loading -> Box(contentModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is CanvasSettingsState.Success -> CanvasSettingsBody(state.model, onAction, contentModifier)
            CanvasSettingsState.Empty -> CanvasSettingsFailure(R.string.canvas_settings_load_failed, onAction, contentModifier)
            is CanvasSettingsState.Error -> CanvasSettingsFailure(state.messageRes, onAction, contentModifier)
        }
    }
}

@Composable
private fun CanvasSettingsBody(
    model: CanvasSettingsUiModel,
    onAction: (CanvasSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onEnabled: (Boolean) -> Unit = remember(onAction) { { onAction(CanvasSettingsAction.SetEnabled(it)) } }
    val onWifiOnly: (Boolean) -> Unit = remember(onAction) { { onAction(CanvasSettingsAction.SetWifiOnly(it)) } }
    val onRefresh = remember(onAction) { { onAction(CanvasSettingsAction.RefreshHealth) } }
    val onCacheLimit = remember(onAction) { { onAction(CanvasSettingsAction.ShowCacheLimit) } }
    val onClear = remember(onAction) { { onAction(CanvasSettingsAction.ShowClearCache) } }
    val sources = remember { CanvasSource.entries.toList() }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
            .padding(top = SettingsDimensions.SectionSpacing, bottom = SettingsDimensions.ScreenBottomPadding),
        verticalArrangement = remember { Arrangement.spacedBy(SettingsDimensions.SectionSpacing) },
    ) {
        CanvasHero(active = model.configuration.enabled)
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (model.configuration.enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ) {
            CanvasSwitchRow(
                title = stringResource(R.string.canvas_enable),
                description = stringResource(R.string.archivetune_canvas_desc),
                iconRes = R.drawable.auto_awesome,
                checked = model.configuration.enabled,
                enabled = !model.busy,
                onCheckedChange = onEnabled,
            )
        }
        CanvasSectionHeader(title = stringResource(R.string.canvas_source))
        Column(
            modifier = remember { Modifier.selectableGroup() },
            verticalArrangement = remember { Arrangement.spacedBy(SettingsDimensions.SegmentedItemGap) },
        ) {
            sources.forEachIndexed { index, source ->
                val onSelect = remember(source, onAction) { { onAction(CanvasSettingsAction.SelectSource(source)) } }
                CanvasChoiceRow(
                    title = stringResource(source.labelResource()),
                    description = when (source) {
                        CanvasSource.ALL -> stringResource(R.string.canvas_source_all_desc)
                        CanvasSource.SPOTIFY -> stringResource(R.string.canvas_spotify_desc)
                        else -> null
                    },
                    iconRes = source.iconResource(),
                    shape = CanvasSectionShapes.segment(index, sources.size),
                    selected = model.configuration.source == source,
                    enabled = !model.busy,
                    onClick = onSelect,
                )
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            CanvasSwitchRow(
                title = stringResource(R.string.canvas_wifi_only),
                description = stringResource(R.string.canvas_wifi_only_desc),
                iconRes = R.drawable.wifi_proxy,
                checked = model.configuration.wifiOnly,
                enabled = !model.busy,
                onCheckedChange = onWifiOnly,
            )
        }
        CanvasHealthSection(model = model, onRefresh = onRefresh)
        CanvasCacheSection(model = model, onCacheLimit = onCacheLimit, onClear = onClear)
    }
    CanvasSettingsDialogs(model, onAction)
}

/**
 * Static, decorative artwork for the top of the screen. It reflects only whether Canvas is on and
 * never shows example videos, thumbnails or metrics, so it is hidden from accessibility services.
 */
@Composable
private fun CanvasHero(active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val backBrush = remember(colors.tertiaryContainer, colors.secondaryContainer) {
        Brush.linearGradient(listOf(colors.tertiaryContainer, colors.secondaryContainer))
    }
    val frontBrush = remember(colors.primary, colors.tertiary) {
        Brush.linearGradient(listOf(colors.primary, colors.tertiary))
    }
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .alpha(if (active) 1f else 0.6f)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = 34.dp)
                    .size(width = 88.dp, height = 108.dp)
                    .rotate(6f)
                    .background(backBrush, RoundedCornerShape(20.dp)),
            )
            Box(
                modifier = Modifier
                    .offset(x = (-18).dp)
                    .size(width = 96.dp, height = 116.dp)
                    .rotate(-4f)
                    .background(frontBrush, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.motion_photos_on),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = colors.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun CanvasSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier
            .padding(horizontal = SettingsDimensions.RowHorizontalPadding)
            .padding(top = SettingsDimensions.SectionHeaderBottomPadding)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CanvasSwitchRow(
    title: String,
    description: String,
    @DrawableRes iconRes: Int,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val rowModifier = remember(checked, enabled, onCheckedChange) {
        Modifier.fillMaxWidth()
            .heightIn(min = SettingsDimensions.SegmentedItemMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = SettingsDimensions.RowHorizontalPadding, vertical = SettingsDimensions.RowVerticalPadding)
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = remember { Arrangement.spacedBy(16.dp) }) {
        CanvasSectionIcon(iconRes = iconRes, tint = MaterialTheme.colorScheme.primary)
        Column(remember { Modifier.weight(1f) }, verticalArrangement = remember { Arrangement.spacedBy(2.dp) }) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.78f),
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun CanvasChoiceRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String? = null,
    @DrawableRes iconRes: Int? = null,
    shape: Shape? = null,
) {
    val rowModifier = remember(selected, enabled, onClick) {
        Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = SettingsDimensions.RowHorizontalPadding, vertical = SettingsDimensions.RowVerticalPadding)
    }
    val row: @Composable () -> Unit = {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = remember { Arrangement.spacedBy(16.dp) }) {
            if (iconRes != null) {
                CanvasSectionIcon(iconRes = iconRes, tint = MaterialTheme.colorScheme.primary)
            } else {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
            }
            Column(remember { Modifier.weight(1f) }) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (iconRes != null) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
            }
        }
    }
    if (shape != null) {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, content = row)
    } else {
        row()
    }
}

@Composable
private fun CanvasHealthSection(model: CanvasSettingsUiModel, onRefresh: () -> Unit) {
    Column(verticalArrangement = remember { Arrangement.spacedBy(SettingsDimensions.SectionHeaderBottomPadding) }) {
        Row(
            modifier = remember { Modifier.fillMaxWidth() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CanvasSectionHeader(
                title = stringResource(R.string.canvas_provider_health),
                modifier = remember { Modifier.weight(1f) },
            )
            OutlinedButton(
                onClick = onRefresh,
                enabled = model.canRefreshHealth && !model.busy,
                contentPadding = remember { PaddingValues(horizontal = 14.dp, vertical = 6.dp) },
            ) {
                Icon(painterResource(R.drawable.sync), null, modifier = remember { Modifier.size(18.dp) })
                Text(stringResource(R.string.refresh), modifier = remember { Modifier.padding(start = 8.dp) })
            }
        }
        Column(verticalArrangement = remember { Arrangement.spacedBy(SettingsDimensions.SegmentedItemGap) }) {
            CanvasHealthRow(
                title = stringResource(R.string.canvas_better_lyrics),
                health = model.health.betterLyrics,
                iconRes = CanvasSource.BETTER_LYRICS.iconResource(),
                shape = CanvasSectionShapes.top,
            )
            CanvasHealthRow(
                title = stringResource(R.string.canvas_apple_music),
                health = model.health.appleMusic,
                iconRes = CanvasSource.APPLE_MUSIC.iconResource(),
                shape = CanvasSectionShapes.middle,
            )
            CanvasHealthRow(
                title = stringResource(R.string.canvas_tidal),
                health = model.health.tidal,
                iconRes = CanvasSource.TIDAL.iconResource(),
                shape = CanvasSectionShapes.middle,
            )
            CanvasHealthRow(
                title = stringResource(R.string.canvas_spotify),
                health = model.health.spotify,
                iconRes = CanvasSource.SPOTIFY.iconResource(),
                shape = CanvasSectionShapes.bottom,
            )
        }
    }
}

@Composable
private fun CanvasHealthRow(title: String, health: CanvasHealth, @DrawableRes iconRes: Int, shape: Shape) {
    val statusColor = when (health) {
        CanvasHealth.AVAILABLE -> MaterialTheme.colorScheme.primary
        CanvasHealth.UNAVAILABLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = remember {
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
                    .padding(horizontal = SettingsDimensions.RowHorizontalPadding, vertical = SettingsDimensions.RowVerticalPadding)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = remember { Arrangement.spacedBy(16.dp) },
        ) {
            CanvasSectionIcon(iconRes = iconRes, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = title,
                modifier = remember { Modifier.weight(1f) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = remember { Arrangement.spacedBy(8.dp) },
            ) {
                if (health == CanvasHealth.CHECKING) {
                    CircularProgressIndicator(modifier = remember { Modifier.size(16.dp) }, strokeWidth = 2.dp)
                } else {
                    Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
                }
                Text(
                    text = stringResource(health.labelResource()),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }
        }
    }
}

@Composable
private fun CanvasCacheSection(model: CanvasSettingsUiModel, onCacheLimit: () -> Unit, onClear: () -> Unit) {
    val limitLabel = when (model.configuration.cacheLimitMb) {
        0 -> stringResource(R.string.disable)
        -1 -> stringResource(R.string.unlimited)
        else -> model.cacheLimit
    }
    Column(verticalArrangement = remember { Arrangement.spacedBy(SettingsDimensions.SectionHeaderBottomPadding) }) {
        CanvasSectionHeader(title = stringResource(R.string.canvas_cache))
        Column(verticalArrangement = remember { Arrangement.spacedBy(SettingsDimensions.SegmentedItemGap) }) {
            Surface(shape = CanvasSectionShapes.top, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    modifier = remember {
                        Modifier.fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .padding(SettingsDimensions.RowHorizontalPadding)
                    },
                    verticalArrangement = remember { Arrangement.spacedBy(10.dp) },
                ) {
                    Column(verticalArrangement = remember { Arrangement.spacedBy(2.dp) }) {
                        Text(stringResource(R.string.canvas_cache), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (model.configuration.cacheLimitMb > 0) {
                                stringResource(R.string.canvas_cache_usage, model.cacheSize, model.cacheLimit)
                            } else {
                                stringResource(R.string.size_used, model.cacheSize)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.busy) {
                        LinearProgressIndicator(modifier = remember { Modifier.fillMaxWidth() })
                    } else if (model.configuration.cacheLimitMb > 0) {
                        val progress = remember(model.cacheProgress) { { model.cacheProgress } }
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = remember { Modifier.fillMaxWidth() },
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                    Text(
                        text = stringResource(R.string.canvas_cache_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CanvasActionRow(
                title = stringResource(R.string.max_cache_size),
                subtitle = limitLabel,
                iconRes = R.drawable.storage,
                shape = CanvasSectionShapes.middle,
                enabled = !model.busy,
                onClick = onCacheLimit,
            )
            CanvasActionRow(
                title = stringResource(R.string.clear_canvas_cache),
                subtitle = null,
                iconRes = R.drawable.delete,
                shape = CanvasSectionShapes.bottom,
                enabled = !model.busy,
                onClick = onClear,
                destructive = true,
            )
        }
    }
}

@Composable
private fun CanvasActionRow(
    title: String,
    subtitle: String?,
    @DrawableRes iconRes: Int,
    shape: Shape,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = remember {
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .padding(horizontal = SettingsDimensions.RowHorizontalPadding, vertical = 12.dp)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = remember { Arrangement.spacedBy(16.dp) },
        ) {
            CanvasSectionIcon(iconRes = iconRes, tint = accent)
            Column(modifier = remember { Modifier.weight(1f) }) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painterResource(R.drawable.navigate_next), null,
                modifier = remember { Modifier.size(SettingsDimensions.ChevronSize) },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CanvasSectionIcon(@DrawableRes iconRes: Int, tint: Color) {
    Surface(
        modifier = remember { Modifier.size(SettingsDimensions.RowIconSize) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), null, modifier = remember { Modifier.size(SettingsDimensions.RowIconInnerSize) })
        }
    }
}

private object CanvasSectionShapes {
    val top = RoundedCornerShape(
        topStart = SettingsDimensions.BannerCardCornerRadius,
        topEnd = SettingsDimensions.BannerCardCornerRadius,
        bottomStart = 4.dp,
        bottomEnd = 4.dp,
    )
    val middle = RoundedCornerShape(4.dp)
    val bottom = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 4.dp,
        bottomStart = SettingsDimensions.BannerCardCornerRadius,
        bottomEnd = SettingsDimensions.BannerCardCornerRadius,
    )
    val single = RoundedCornerShape(SettingsDimensions.BannerCardCornerRadius)

    fun segment(index: Int, count: Int): Shape = when {
        count <= 1 -> single
        index == 0 -> top
        index == count - 1 -> bottom
        else -> middle
    }
}

@Composable
private fun CanvasSettingsDialogs(model: CanvasSettingsUiModel, onAction: (CanvasSettingsAction) -> Unit) {
    val onDismiss = remember(onAction) { { onAction(CanvasSettingsAction.DismissDialog) } }
    val onConfirmClear = remember(onAction) { { onAction(CanvasSettingsAction.ClearCache) } }
    when (model.dialog) {
        CanvasSettingsDialog.CACHE_LIMIT -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.max_cache_size)) },
            text = {
                LazyColumn(modifier = remember { Modifier.heightIn(max = 420.dp).selectableGroup() }) {
                    items(model.cacheOptions.values, key = CanvasCacheOption::limitMb, contentType = { "cache_limit" }) { option ->
                        val onSelect = remember(onAction, option.limitMb) {
                            { onAction(CanvasSettingsAction.SetCacheLimit(option.limitMb)) }
                        }
                        CanvasChoiceRow(
                            title = when (option.limitMb) {
                                0 -> stringResource(R.string.disable)
                                -1 -> stringResource(R.string.unlimited)
                                else -> option.formattedSize
                            },
                            selected = model.configuration.cacheLimitMb == option.limitMb,
                            enabled = !model.busy,
                            onClick = onSelect,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        CanvasSettingsDialog.CLEAR_CACHE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.clear_canvas_cache)) },
            text = { Text(stringResource(R.string.clear_canvas_cache_dialog)) },
            confirmButton = { TextButton(onClick = onConfirmClear, enabled = !model.busy) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun CanvasSettingsFailure(@StringRes messageRes: Int, onAction: (CanvasSettingsAction) -> Unit, modifier: Modifier) {
    val onRetry = remember(onAction) { { onAction(CanvasSettingsAction.Retry) } }
    Column(
        modifier = modifier.fillMaxSize().padding(SettingsDimensions.ScreenHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(messageRes))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@StringRes
private fun CanvasSource.labelResource(): Int = when (this) {
    CanvasSource.BETTER_LYRICS -> R.string.canvas_better_lyrics
    CanvasSource.APPLE_MUSIC -> R.string.canvas_apple_music
    CanvasSource.TIDAL -> R.string.canvas_tidal
    CanvasSource.SPOTIFY -> R.string.canvas_spotify
    CanvasSource.ALL -> R.string.canvas_source_all
}

@DrawableRes
private fun CanvasSource.iconResource(): Int = when (this) {
    CanvasSource.BETTER_LYRICS -> R.drawable.lyrics
    CanvasSource.APPLE_MUSIC -> R.drawable.apple_music_icon
    CanvasSource.TIDAL -> R.drawable.provider_tidal
    CanvasSource.SPOTIFY -> R.drawable.spotify_icon
    CanvasSource.ALL -> R.drawable.all_inclusive
}

@StringRes
private fun CanvasHealth.labelResource(): Int = when (this) {
    CanvasHealth.NOT_CHECKED -> R.string.canvas_health_not_checked
    CanvasHealth.CHECKING -> R.string.canvas_health_checking
    CanvasHealth.AVAILABLE -> R.string.canvas_health_available
    CanvasHealth.UNAVAILABLE -> R.string.canvas_health_unavailable
    CanvasHealth.NOT_CONNECTED -> R.string.spotify_not_connected
    CanvasHealth.NOT_SELECTED -> R.string.canvas_health_not_selected
    CanvasHealth.DISABLED -> R.string.canvas_health_disabled
    CanvasHealth.OFFLINE -> R.string.canvas_health_offline
    CanvasHealth.WIFI_REQUIRED -> R.string.canvas_health_wifi_required
    CanvasHealth.LOW_DATA_MODE -> R.string.canvas_health_low_data
}
