/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The Home tab's pages and the controls that pick between them. Which pages exist is
 * [ActiveHomeSourcesKey] (chosen in Appearance); which one is showing is [HomeSourceKey]. A page
 * whose prerequisite disappears (a Spotify session going away) is filtered out of the resolved set
 * rather than erased from the setting, so signing back in restores the user's choice.
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ActiveHomeSourcesKey
import moe.rukamori.archivetune.constants.HomeSource
import moe.rukamori.archivetune.constants.HomeSourceKey
import moe.rukamori.archivetune.constants.QqMusicMusickeyKey
import moe.rukamori.archivetune.constants.QqMusicUinKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.ui.component.PreferenceMultiSelectBottomSheet
import moe.rukamori.archivetune.ui.component.PreferenceSelectionBottomSheet
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore

/**
 * True when the Spotify page is usable at all. Everything Spotify-page-shaped hangs off this: with
 * no session there is no Spotify home to offer, and an entry with one destination is just clutter.
 */
@Composable
fun rememberHomeSourceAvailable(): Boolean {
    val spDc by rememberPreference(SpotifySpDcKey, defaultValue = "")
    return spDc.isNotBlank()
}

/**
 * True when the QQ Music page is usable at all: an account has to be signed in, because every
 * section of it is QQ Music's own catalogue addressed with that account's ticket. Like Spotify's,
 * this is read from the stored session rather than probed, so it costs nothing and cannot leave the
 * switcher offering a page whose session has already gone.
 */
@Composable
fun rememberQqHomeSourceAvailable(): Boolean {
    val uin by rememberPreference(QqMusicUinKey, defaultValue = "")
    val musicKey by rememberPreference(QqMusicMusickeyKey, defaultValue = "")
    return uin.isNotBlank() && musicKey.isNotBlank()
}

/** Whether [this] page can be shown right now. YouTube is the app's own home and always can. */
private fun HomeSource.isAvailable(
    spotifyAvailable: Boolean,
    qqAvailable: Boolean,
): Boolean =
    when (this) {
        HomeSource.YOUTUBE -> true
        HomeSource.SPOTIFY -> spotifyAvailable
        HomeSource.QQ -> qqAvailable
    }

/**
 * Every page the app could offer, in the order the switcher and the picker list them — the enum's
 * own order, so a new source cannot be forgotten here and silently never appear.
 */
val HomeSourceCandidates: List<HomeSource> = HomeSource.entries.toList()

/** Reads [ActiveHomeSourcesKey]; unknown or duplicate names are dropped, order is preserved. */
internal fun parseHomeSources(raw: String): List<HomeSource> =
    raw
        .split(',')
        .mapNotNull { name -> HomeSource.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
        .distinct()

/**
 * The set to store, in [HomeSourceCandidates] order: the switcher lists rows in the stored order,
 * so a set assembled by toggling would otherwise shuffle rows as pages are removed and re-added.
 */
internal fun canonicalHomeSources(selected: List<HomeSource>): List<HomeSource> =
    HomeSourceCandidates.filter { it in selected }

/**
 * The Home pages the user has made available, resolved against availability and never empty.
 *
 * An unset [ActiveHomeSourcesKey] means "not configured" and keeps the historical behaviour:
 * YouTube alone, plus Spotify the moment a session exists and QQ Music the moment an account is
 * signed in. YouTube is always in the result — it is the only page that needs nothing. Unparseable
 * values are treated the same as unset.
 */
@Composable
fun rememberActiveHomeSources(): List<HomeSource> {
    val (raw) = rememberPreference(ActiveHomeSourcesKey, "")
    val spotifyAvailable = rememberHomeSourceAvailable()
    val qqAvailable = rememberQqHomeSourceAvailable()
    val stored = remember(raw) { parseHomeSources(raw) }
    val base =
        if (stored.isEmpty()) {
            buildList {
                add(HomeSource.YOUTUBE)
                if (spotifyAvailable) add(HomeSource.SPOTIFY)
                if (qqAvailable) add(HomeSource.QQ)
            }
        } else {
            stored
        }
    return base
        .filter { it.isAvailable(spotifyAvailable, qqAvailable) }
        .ifEmpty { listOf(HomeSource.YOUTUBE) }
}

/**
 * The Home tab's current page, already resolved against the active set — so callers never have to
 * repeat the availability checks, and a stored choice survives a temporary sign-out instead of
 * being rewritten behind the user's back.
 */
@Composable
fun rememberHomeSource(): HomeSource {
    val stored by rememberEnumPreference(HomeSourceKey, defaultValue = HomeSource.YOUTUBE)
    val actives = rememberActiveHomeSources()
    return if (stored in actives) stored else actives.first()
}

/** Matches the account avatar this sits beside, so the two read as one pair of controls. */
private val ToggleIconSize = 20.dp

internal fun homeSwitchTarget(current: HomeSource, active: List<HomeSource>): HomeSource {
    val resolved = current.takeIf { it in active } ?: active.firstOrNull() ?: HomeSource.YOUTUBE
    return active.firstOrNull { it != resolved }
        ?: if (resolved == HomeSource.SPOTIFY) HomeSource.YOUTUBE else HomeSource.SPOTIFY
}

@Composable
fun HomeSourceToggleButton(
    onOpenSpotifySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actives = rememberActiveHomeSources()
    val spotifyAvailable = rememberHomeSourceAvailable()
    val qqAvailable = rememberQqHomeSourceAvailable()
    val dataStore = LocalContext.current.dataStore

    var source by rememberEnumPreference(HomeSourceKey, defaultValue = HomeSource.YOUTUBE)
    var switcherOpen by remember { mutableStateOf(false) }

    if (switcherOpen) {
        HomeSourceSwitcherSheet(
            active = actives,
            current = if (source in actives) source else actives.first(),
            onSelect = {
                source = it
                switcherOpen = false
            },
            onDismiss = { switcherOpen = false },
        )
    }

    val showsMenu = actives.size > 2
    val target = if (showsMenu) source.takeIf { it in actives } ?: actives.first() else homeSwitchTarget(source, actives)

    IconButton(
        onClick = {
            when {
                showsMenu -> switcherOpen = true
                !target.isAvailable(spotifyAvailable, qqAvailable) -> onOpenSpotifySettings()
                else -> PreferenceStore.launchEdit(dataStore) {
                    if (target !in actives) {
                        this[ActiveHomeSourcesKey] = canonicalHomeSources(
                            parseHomeSources(this[ActiveHomeSourcesKey].orEmpty()) + actives + target + HomeSource.YOUTUBE,
                        ).joinToString(",") { it.name }
                    }
                    this[HomeSourceKey] = target.name
                }
            }
        },
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(target.iconResId()),
            contentDescription =
                if (showsMenu) {
                    stringResource(R.string.home_screens)
                } else if (!target.isAvailable(spotifyAvailable, qqAvailable)) {
                    stringResource(R.string.home_connect_spotify)
                } else {
                    stringResource(R.string.home_source_switch_to, stringResource(target.labelResId()))
                },
            // Sized explicitly: spotify_icon is a 1438x1425 PNG, and an Icon with no size
            // constraint draws its painter at intrinsic size — roughly 520dp.
            modifier = Modifier.size(ToggleIconSize),
        )
    }
}

/**
 * The page switcher, drawn in the app's own selection sheet — the same one the settings rows use for
 * a single choice — so picking a Home page reads like every other picker in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSourceSwitcherSheet(
    active: List<HomeSource>,
    current: HomeSource,
    onSelect: (HomeSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    PreferenceSelectionBottomSheet(
        title = { Text(stringResource(R.string.home_screens)) },
        values = active,
        selectedValue = current,
        valueText = { stringResource(it.labelResId()) },
        sheetState = sheetState,
        onDismiss = onDismiss,
        onValueSelected = onSelect,
    )
}

/**
 * The Appearance screen's picker for [ActiveHomeSourcesKey], as the app's multi-select sheet.
 *
 * Membership is editable regardless of availability — a page whose prerequisite is missing (a
 * signed-out Spotify or QQ Music) is annotated rather than hidden, because otherwise someone who
 * wants that page gone would have to sign in first just to remove it. YouTube, the floor page,
 * cannot be switched off. The sheet commits on dismissal, like every other selection sheet in the
 * app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreensDialog(
    selected: List<HomeSource>,
    spotifyAvailable: Boolean,
    qqAvailable: Boolean,
    onConfirm: (List<HomeSource>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed and copied: the caller's list can change (a session expiring) while this is open, and
    // the committed set must never be one the user did not see.
    var working by remember(selected) { mutableStateOf(selected.toList()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    PreferenceMultiSelectBottomSheet(
        title = { Text(stringResource(R.string.home_screens)) },
        values = HomeSourceCandidates,
        isSelected = { it in working },
        valueText = { stringResource(it.labelResId()) },
        valueDescription = { source ->
            if (source.isAvailable(spotifyAvailable, qqAvailable)) {
                null
            } else {
                stringResource(R.string.home_screens_needs_sign_in)
            }
        },
        sheetState = sheetState,
        onDismiss = { onConfirm(canonicalHomeSources(working).ifEmpty { listOf(HomeSource.YOUTUBE) }) },
        onToggle = { source ->
            if (source != HomeSource.YOUTUBE) {
                working = canonicalHomeSources(if (source in working) working - source else working + source)
            }
        },
    )
}

/**
 * Sends the Home tab back to the YouTube page. A page offers this when its session turns out to be
 * dead: the stored credential is non-blank (or the page would not be showing) but the service
 * rejects it, and without a way out the user is stuck on an error with a button that does nothing.
 */
@Composable
fun rememberSwitchToYouTube(): () -> Unit {
    var source by rememberEnumPreference(HomeSourceKey, defaultValue = HomeSource.YOUTUBE)
    return { source = HomeSource.YOUTUBE }
}

internal fun HomeSource.labelResId(): Int =
    when (this) {
        HomeSource.YOUTUBE -> R.string.home_source_youtube
        HomeSource.SPOTIFY -> R.string.home_source_spotify
        HomeSource.QQ -> R.string.home_source_qq
    }

internal fun HomeSource.iconResId(): Int =
    when (this) {
        HomeSource.YOUTUBE -> R.drawable.ic_music
        HomeSource.SPOTIFY -> R.drawable.spotify_icon
        // No QQ Music mark ships in drawable/, so this is the stand-in the source rows in settings
        // use for the same reason.
        HomeSource.QQ -> R.drawable.ic_music
    }
