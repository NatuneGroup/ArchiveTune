/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The Apple Music Experience's Library tab.
 * The experience switch already replaced the album/playlist headers, the tab bar and the player, but
 * the Library tab still rendered the fork's chip-and-inline-content layout, so the switch's "Apple
 * Music library" half was a style with nothing to style. This is the missing screen: a large title
 * over a plain list of sections, each row a thumbnail-column entry with a chevron, exactly the list
 * language AppleMusicPlaylistRow established for the playlists inside those sections — same side
 * padding, same artwork column width, same hairline inset to the text column, taken from the
 * constants that file exports for callers that need to line up with it.
 * The sections themselves are the app's existing screens, opened in place rather than duplicated;
 * they render no header of their own, so the compact back row above them is the only chrome.
 * The root list carries the YTM/Spotify selector the chip layout has: the sections below read one
 * source, and this layout is the only Library screen its user ever sees.
 */



package moe.rukamori.archivetune.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.constants.LibrarySource

/** Row order is Apple Music's own: playlists first, then the credit lists, then the mix. */
private enum class AppleMusicLibrarySection(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    PLAYLISTS(R.string.playlists, R.drawable.queue_music),
    ARTISTS(R.string.artists, R.drawable.artist),
    ALBUMS(R.string.albums, R.drawable.album),
    MIX(R.string.library_mix, R.drawable.music_note),
}

@Composable
fun AppleMusicLibraryScreen(navController: NavController) {
    var section by rememberSaveable { mutableStateOf<AppleMusicLibrarySection?>(null) }

    // The sections below are the app's own, so they follow the same source the Library tab does,
    // and the root list carries the same selector to change it. Nothing here reads the Apple Music
    // experience's own style keys: a library layout is not a source.
    val librarySource = rememberLibrarySource()
    val librarySourcePreference = rememberLibrarySourcePreference()

    // With a section open the system back returns to the section list rather than leaving the tab,
    // which is what the chevron-and-back-row shape promises.
    BackHandler(enabled = section != null) { section = null }

    Box(Modifier.fillMaxSize()) {
        when (val open = section) {
            null ->
                AppleMusicLibrarySections(
                    source = librarySource,
                    onSourceSelected = { librarySourcePreference.value = it },
                    onSectionSelected = { section = it },
                )

            else -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                LocalPlayerAwareWindowInsets.current.only(
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                                ),
                            ),
                ) {
                    AppleMusicSectionHeader(titleRes = open.labelRes, onBack = { section = null })
                    when (open) {
                        AppleMusicLibrarySection.PLAYLISTS ->
                            LibraryPlaylistsScreen(
                                navController = navController,
                                librarySource = librarySource,
                                filterContent = null,
                                selectedTagIds = emptySet(),
                            )

                        AppleMusicLibrarySection.ARTISTS ->
                            LibraryArtistsScreen(
                                navController = navController,
                                librarySource = librarySource,
                                onDeselect = { section = null },
                            )

                        AppleMusicLibrarySection.ALBUMS ->
                            LibraryAlbumsScreen(
                                navController = navController,
                                librarySource = librarySource,
                                onDeselect = { section = null },
                            )

                        AppleMusicLibrarySection.MIX ->
                            LibraryMixScreen(
                                navController = navController,
                                librarySource = librarySource,
                                filterContent = null,
                                selectedTagIds = emptySet(),
                                onTabSelected = { _: LibraryFilter -> },
                            )
                    }
                }
            }
        }
    }
}

/** The root: the large title, the source selector, then one entry per section. */
@Composable
private fun AppleMusicLibrarySections(
    source: LibrarySource,
    onSourceSelected: (LibrarySource) -> Unit,
    onSectionSelected: (AppleMusicLibrarySection) -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                ),
        contentPadding = PaddingValues(bottom = LibraryHeaderContentPadding),
    ) {
        item(key = "library_title") {
            Text(
                text = stringResource(R.string.filter_library),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.padding(
                        start = AppleMusicListSidePadding,
                        end = AppleMusicListSidePadding,
                        top = 0.dp,
                        bottom = 8.dp,
                    ),
            )
        }

        // The sections below read one source, so this layout needs the control that picks it: the
        // section rows are the only other thing here, and it is the same selector the chip layout
        // uses, at this list's own gutter.
        item(key = "library_source_selector") {
            LibrarySourceSelector(
                source = source,
                onSourceSelected = onSourceSelected,
                horizontalPadding = AppleMusicListSidePadding,
            )
        }

        items(items = AppleMusicLibrarySection.entries, key = { it.name }) { entry ->
            AppleMusicLibrarySectionRow(entry = entry, onClick = { onSectionSelected(entry) })
        }
    }
}

@Composable
private fun AppleMusicLibrarySectionRow(
    entry: AppleMusicLibrarySection,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = AppleMusicListSidePadding, vertical = 4.dp),
        ) {
            // The sections have no artwork of their own, so the reference's thumbnail column holds
            // a tinted glyph tile: same size and corner as the playlists list, so the two read as
            // one column when the user moves between them.
            Surface(
                shape = RoundedCornerShape(AppleMusicListArtworkCorner),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(AppleMusicListArtworkSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(entry.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Text(
                text = stringResource(entry.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )

            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp).padding(start = 4.dp),
            )
        }

        // Same hairline and same inset as the playlist rows: the artwork column reads as one
        // continuous edge, which is what separates an Apple Music list from a stack of full-bleed
        // rules.
        Box(
            modifier =
                Modifier
                    .padding(start = AppleMusicListSidePadding + AppleMusicRowTextInset)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}

/** Compact header for an open section — the sections themselves render no chrome of their own. */
@Composable
private fun AppleMusicSectionHeader(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(end = AppleMusicListSidePadding, top = 4.dp, bottom = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
        }
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
