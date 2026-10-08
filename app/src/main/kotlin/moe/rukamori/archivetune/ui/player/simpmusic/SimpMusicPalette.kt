/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.player.simpmusic

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The one colour SimpMusic's now-playing wash ramps FROM. */
private val washCache =
    object : LinkedHashMap<String, Color>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>): Boolean = size > 64
    }

@Composable
fun rememberSimpMusicWashColor(imageUrl: String?): Color {
    val context = LocalContext.current
    val cached = imageUrl?.let { synchronized(washCache) { washCache[it] } }
    val state = remember(imageUrl) { mutableStateOf(cached ?: Color.Black) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || cached != null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .size(128) // a wash needs a colour, never detail
                .allowHardware(false) // Palette needs pixel access
                .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@LaunchedEffect
        // Palette.generate() is synchronous pixel work — small at 128px, still not the main thread's.
        val color = withContext(Dispatchers.Default) { washColorOf(bitmap) }
        synchronized(washCache) { imageUrl.let { washCache[it] = color } }
        state.value = color
    }
    return state.value
}

/** SimpMusic's swatch preference order, verbatim. */
private fun washColorOf(bitmap: Bitmap): Color {
    val palette = Palette.from(bitmap).maximumColorCount(24).generate()
    val rgb =
        palette.darkVibrantSwatch?.rgb
            ?: palette.darkMutedSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.lightMutedSwatch?.rgb
            ?: return Color.Black
    return Color(rgb)
}
