/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.menu

import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.customartwork.CustomArtworkResult
import moe.rukamori.archivetune.customartwork.CustomArtworkStore
import moe.rukamori.archivetune.ui.component.MenuSurfaceSection
import moe.rukamori.archivetune.ui.player.resolveCanvasArtworkForPlayback

@Composable
internal fun CustomArtworkMenuSection(
    songId: String,
    thumbnailUrl: String?,
    title: String,
    artistName: String,
    albumTitle: String?,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val overrides by CustomArtworkStore.overrides.collectAsStateWithLifecycle()
    var busy by remember(songId) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CustomArtworkStore.init(context)
        withContext(Dispatchers.IO) { CustomArtworkStore.preload() }
    }

    val coverUrl = thumbnailUrl?.takeIf { it.isNotBlank() }
    val hasCover = remember(overrides, coverUrl) { overrides.hasCover(coverUrl) }
    val hasCanvas = remember(overrides, songId) { overrides.hasCanvas(songId) }

    suspend fun publishCurrentCanvas() {
        val connection = playerConnection ?: return
        val artwork =
            CustomArtworkStore.customCanvasArtwork(songId)
                ?: resolveCanvasArtworkForPlayback(
                    mediaId = songId,
                    songTitleRaw = title,
                    artistNameRaw = artistName,
                    storefront = "",
                    requireVertical = false,
                    allowNetwork = false,
                    albumTitle = albumTitle,
                )
                ?: return
        connection.publishCanvasArtwork(songId, artwork)
    }

    val coverPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val url = coverUrl
            if (uri == null || url == null || busy) return@rememberLauncherForActivityResult
            busy = true
            coroutineScope.launch {
                try {
                    val result = CustomArtworkStore.setCover(context, url, uri)
                    context.showCustomArtworkResult(result, R.string.custom_artwork_cover_saved, CustomArtworkStore.MaxCoverBytes)
                } finally {
                    busy = false
                }
            }
        }

    val canvasPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null || busy) return@rememberLauncherForActivityResult
            busy = true
            coroutineScope.launch {
                try {
                    val result = CustomArtworkStore.setCanvas(context, songId, uri)
                    context.showCustomArtworkResult(result, R.string.custom_artwork_canvas_saved, CustomArtworkStore.MaxCanvasBytes)
                    if (result == CustomArtworkResult.Saved) withContext(Dispatchers.IO) { publishCurrentCanvas() }
                } finally {
                    busy = false
                }
            }
        }

    val rows =
        buildList {
            if (coverUrl != null) {
                add(CustomArtworkRow(R.string.custom_artwork_set_cover, R.drawable.image) {
                    if (!busy) coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
            add(CustomArtworkRow(R.string.custom_artwork_set_canvas, R.drawable.motion_photos_on) {
                if (!busy) canvasPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            })
            if (hasCover) {
                add(CustomArtworkRow(R.string.custom_artwork_remove_cover, R.drawable.hide_image) {
                    coroutineScope.launch {
                        if (CustomArtworkStore.removeCover(coverUrl)) {
                            Toast.makeText(context, R.string.custom_artwork_cover_removed, Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            if (hasCanvas) {
                add(CustomArtworkRow(R.string.custom_artwork_remove_canvas, R.drawable.delete) {
                    coroutineScope.launch {
                        if (CustomArtworkStore.removeCanvas(songId)) {
                            Toast.makeText(context, R.string.custom_artwork_canvas_removed, Toast.LENGTH_SHORT).show()
                            withContext(Dispatchers.IO) { publishCurrentCanvas() }
                        }
                    }
                })
            }
        }

    MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                ListItem(
                    headlineContent = { Text(text = stringResource(row.label)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(row.icon),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable(enabled = !busy, onClick = row.onClick),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

private class CustomArtworkRow(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

private fun Context.showCustomArtworkResult(
    result: CustomArtworkResult,
    savedMessage: Int,
    maxBytes: Long,
) {
    val message =
        when (result) {
            CustomArtworkResult.Saved -> getString(savedMessage)
            CustomArtworkResult.TooLarge -> getString(R.string.custom_artwork_too_large, (maxBytes / (1024 * 1024)).toInt())
            CustomArtworkResult.Unsupported -> getString(R.string.custom_artwork_unsupported)
            CustomArtworkResult.Failed -> getString(R.string.custom_artwork_failed)
        }
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
