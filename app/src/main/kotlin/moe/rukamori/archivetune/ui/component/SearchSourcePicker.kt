/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource

/** Compact source menu shared by the active search bar and search result top bar. */
@Composable
fun SearchSourcePicker(
    currentScope: SearchSource,
    currentProvider: SearchProvider,
    onSelection: (SearchSource, SearchProvider) -> Unit,
    includeLocal: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter =
                    painterResource(
                        if (currentScope == SearchSource.LOCAL) {
                            R.drawable.library_music
                        } else if (currentProvider == SearchProvider.SPOTIFY) {
                            R.drawable.spotify_icon
                        } else {
                            R.drawable.language
                        },
                    ),
                contentDescription = stringResource(R.string.search_source_picker),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (includeLocal) {
                SearchSourceMenuItem(
                    label = stringResource(R.string.search_library),
                    iconRes = R.drawable.library_music,
                    selected = currentScope == SearchSource.LOCAL,
                ) {
                    expanded = false
                    onSelection(SearchSource.LOCAL, currentProvider)
                }
            }
            SearchSourceMenuItem(
                label = stringResource(R.string.search_source_youtube),
                iconRes = R.drawable.language,
                selected = currentScope == SearchSource.ONLINE && currentProvider == SearchProvider.YOUTUBE,
            ) {
                expanded = false
                onSelection(SearchSource.ONLINE, SearchProvider.YOUTUBE)
            }
            SearchSourceMenuItem(
                label = stringResource(R.string.search_source_spotify),
                iconRes = R.drawable.spotify_icon,
                selected = currentScope == SearchSource.ONLINE && currentProvider == SearchProvider.SPOTIFY,
            ) {
                expanded = false
                onSelection(SearchSource.ONLINE, SearchProvider.SPOTIFY)
            }
            SearchSourceMenuItem(
                label = stringResource(R.string.search_source_apple_music),
                iconRes = R.drawable.apple_music_icon,
                selected = currentScope == SearchSource.ONLINE && currentProvider == SearchProvider.APPLE_MUSIC,
            ) {
                expanded = false
                onSelection(SearchSource.ONLINE, SearchProvider.APPLE_MUSIC)
            }
        }
    }
}

@Composable
private fun SearchSourceMenuItem(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
    )
}
