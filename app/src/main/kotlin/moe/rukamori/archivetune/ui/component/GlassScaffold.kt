/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * ArchiveTune (2026) | Modified work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * YumaPlayer (2026) | Original work by MuwMix
 */


package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A Scaffold that paints nothing of its own.
 *
 * The glass look is not something the cards do — it is what happens when they sit on the app's
 * themed background instead of on a slab of `surface`. Material3's Scaffold paints that slab by
 * default, so a glass card drawn inside one reads as a plain card on flat paint, which is why the
 * settings pages looked less like YumaPlayer than the card styling alone suggested.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

object GlassDefaults {
    /** Transparent in both states, so the bar never paints over the background mid-scroll. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun topAppBarColors(): TopAppBarColors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        )
}

/**
 * The settings top bar. Identical to [TopAppBar] except that it cannot forget the glass colours.
 *
 * Every settings screen built its own bare `TopAppBar`, none passed `colors`, and Material3's
 * default is an opaque container that also darkens on scroll — so all forty-odd pages sat behind a
 * solid strip. Routing them through one composable means a new screen gets this by construction
 * rather than by remembering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = GlassDefaults.topAppBarColors(),
        scrollBehavior = scrollBehavior,
    )
}
