/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import moe.rukamori.archivetune.utils.PendingRestart
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class SettingsGroup(
    val title: String,
    val items: List<SettingsItem>,
    val showWhenFiltered: Boolean = true,
)

@Immutable
data class SettingsItem(
    val key: String,
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val showUpdateIndicator: Boolean = false,
    val accentColor: Color = Color.Unspecified,
    val keywords: List<String> = emptyList(),
    val children: List<SettingsChild> = emptyList(),
    val onClick: () -> Unit,
    val switchControl: (@Composable () -> Unit)? = null,
    /**
     * When true, the item is excluded from the visible groups on the main settings page
     * (so no row is rendered for it) but its [children] still participate in settings
     * search. Use this for a pill that lives on a sub-page (e.g. "Source" in Playback)
     * when the original search index entries should keep working — tapping a child search
     * result still navigates to the sub-page via [onClick].
     */
    val hidden: Boolean = false,
)

/**
 * Represents a single searchable setting inside a settings category.
 * When the user searches settings, each [SettingsChild] that matches is
 * shown as a separate result row and may provide an inline control such as
 * a switch for boolean preferences.
 */
@Immutable
data class SettingsChild(
    val title: String,
    val scrollKey: String,
    val keywords: List<String> = emptyList(),
    /** Set on restart-scoped settings: which [PendingRestart] flag this row's switch can raise. */
    val restartPending: ((PendingRestart) -> Boolean)? = null,
    /** Set on blur-dependent rows that Disable blur effects overrides. */
    val blurOverridden: Boolean = false,
    val switchControl: (@Composable () -> Unit)? = null,
)

/**
 * A flattened search result derived from a [SettingsChild].
 * Shown as an individual row in the search results list.
 */
@Immutable
data class SearchResultItem(
    val title: String,
    val parentTitle: String,
    @DrawableRes val parentIcon: Int,
    val parentKey: String,
    val parentAccentColor: Color = Color.Unspecified,
    val parentRoute: String?,
    val scrollKey: String?,
    val onClick: () -> Unit,
    val restartPending: ((PendingRestart) -> Boolean)? = null,
    /** Set on blur-dependent rows that Disable blur effects overrides. */
    val blurOverridden: Boolean = false,
    val switchControl: (@Composable () -> Unit)? = null,
)
