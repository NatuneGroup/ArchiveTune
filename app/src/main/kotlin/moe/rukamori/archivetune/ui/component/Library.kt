/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.ui.menu.AlbumMenu

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryAlbumGridItem(
    modifier: Modifier = Modifier,
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
) = AlbumGridItem(
    album = album,
    isActive = isActive,
    isPlaying = isPlaying,
    coroutineScope = coroutineScope,
    fillMaxWidth = true,
    modifier =
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    navController.navigate("album/${album.id}")
                },
                onLongClick = {
                    menuState.show {
                        AlbumMenu(
                            originalAlbum = album,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
            ),
)
