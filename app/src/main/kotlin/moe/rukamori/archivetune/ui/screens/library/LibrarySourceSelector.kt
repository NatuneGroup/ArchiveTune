/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LibrarySource
import moe.rukamori.archivetune.constants.LibrarySourceKey
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * True when the Library has a second service to offer: the user is signed in to Spotify AND has
 * turned Spotify content on in Integration settings.
 *
 * Both halves matter. A signed-in user who has not opted in keeps the Library exactly as it was,
 * and the same preference already gates what Android Auto browses, so the two agree.
 */
@Composable
fun rememberLibrarySourceAvailable(): Boolean {
    val spDc by rememberPreference(SpotifySpDcKey, defaultValue = "")
    val showSpotify by rememberPreference(ShowSpotifyPlaylistsKey, defaultValue = false)
    return spDc.isNotBlank() && showSpotify
}

/**
 * The stored source choice, which is the selector's to write and nobody else's to read.
 *
 * Readers want [rememberLibrarySource], which resolves that choice against whether Spotify can be
 * read at all. This is the raw preference, so exactly one screen — the Library, which owns the
 * selector — can put the user's choice back.
 */
@Composable
fun rememberLibrarySourcePreference(): MutableState<LibrarySource> =
    rememberEnumPreference(LibrarySourceKey, defaultValue = LibrarySource.YTM)

/**
 * The Library's active source, already resolved against whether Spotify is usable.
 *
 * One value for the whole screen: the Library reads it once and hands it to every section, so a
 * switch re-reads all of them in the same frame rather than leaving a section showing the source it
 * happened to be built with.
 */
@Composable
fun rememberLibrarySource(): LibrarySource {
    val stored by rememberLibrarySourcePreference()
    return stored.resolved(spotifyAvailable = rememberLibrarySourceAvailable())
}

/**
 * The YTM / Spotify selector, sat at the top of the Library above the section chips.
 *
 * Uses [ExpressiveTabChip], the same chip the Library's section tabs are built from, so the two
 * rows read as one control surface instead of two different ideas about what a pill is — and the
 * control that decides what the page is showing is no longer the first row of the page's list.
 *
 * Stateless: the screen owns the source, so every section reads the one value that was switched.
 * Renders nothing when there is no second service — see [rememberLibrarySourceAvailable].
 */
@Composable
fun LibrarySourceSelector(
    source: LibrarySource,
    onSourceSelected: (LibrarySource) -> Unit,
    modifier: Modifier = Modifier,
    // 24dp matches every Library section's gutter. The Apple Music library lays its own rows out at
    // 16dp and passes that, so the selector lines up with the list it sits in.
    horizontalPadding: Dp = 24.dp,
) {
    if (!rememberLibrarySourceAvailable()) return

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = horizontalPadding, vertical = 8.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveTabChip(
            label = stringResource(R.string.library_source_ytm),
            iconRes = R.drawable.ic_music,
            selected = source == LibrarySource.YTM,
            onClick = { onSourceSelected(LibrarySource.YTM) },
        )
        ExpressiveTabChip(
            label = stringResource(R.string.home_source_spotify),
            iconRes = R.drawable.spotify_icon,
            selected = source == LibrarySource.SPOTIFY,
            onClick = { onSourceSelected(LibrarySource.SPOTIFY) },
        )
    }
}
