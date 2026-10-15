/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalBlurPolicy
import moe.rukamori.archivetune.utils.ImageBlurUtils

/**
 * Compose helper that resolves an image URL into a pre-blurred [Bitmap] for use on Android versions
 * below S (API 31) where Compose's `Modifier.blur` is silently a no-op. On Android 12+ it returns
 * `null` because the platform `Modifier.blur` renders a better blur than a CPU stack blur can.
 *
 * The blur reads and writes pixels on the CPU, so the decode asks Coil for a software bitmap
 * ([allowHardware] off); a hardware bitmap has no pixels to read and cannot be turned into one by
 * drawing it into a software `Canvas` either — that throws
 * `IllegalArgumentException("Software rendering doesn't support hardware bitmaps")` on every device
 * from API 26 up. [softwarePixels] converts with the platform copy, which does the GPU readback.
 *
 * @param imageUrl The source URL (or null — returns null immediately).
 * @param radiusDp The intended blur radius in dp, converted to pixels at the device's density and
 *   capped inside [ImageBlurUtils] because stack-blur cost grows quadratically.
 * @param maxDimensionPx Maximum dimension of the source bitmap to load — artwork is routinely far
 *   larger than a blurred backdrop needs to be.
 * @param cacheKey Cache key for the decoded source, so a bitmap that is only ever shown blurred does
 *   not evict the sharp artwork the rest of the UI is reusing from Coil's caches.
 */
@Composable
fun rememberPreBlurredBitmap(
    imageUrl: String?,
    radiusDp: Dp = 48.dp,
    maxDimensionPx: Int = 720,
    cacheKey: String = "$imageUrl#preblur",
): Bitmap? {
    if (imageUrl.isNullOrBlank()) return null
    // The blur policy: with "Disable blur effects" on there is no CPU fallback either, and on S+
    // there never was one (the platform blur is better) — the caller shows its plain fallback.
    if (!LocalBlurPolicy.current.cpuFallback) return null

    val context = LocalContext.current
    val imageLoader = context.imageLoader
    var bitmap by remember(imageUrl, radiusDp, maxDimensionPx, cacheKey) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imageUrl, radiusDp, maxDimensionPx, cacheKey) {
        bitmap = blurArtworkOffscreen(context, imageLoader, imageUrl, radiusDp, maxDimensionPx, cacheKey)
    }

    return bitmap
}

private suspend fun blurArtworkOffscreen(
    context: Context,
    imageLoader: coil3.ImageLoader,
    imageUrl: String,
    radiusDp: Dp,
    maxDimensionPx: Int,
    cacheKey: String,
): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val request =
                ImageRequest
                    .Builder(context)
                    .data(imageUrl)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .size(maxDimensionPx)
                    .precision(Precision.INEXACT)
                    .allowHardware(false)
                    .build()
            val result = imageLoader.execute(request)
            if (result !is SuccessResult) return@withContext null
            val source = softwarePixels(result.image.toBitmap()) ?: return@withContext null
            val density = context.resources.displayMetrics.density
            val radiusPx = (radiusDp.value * density).coerceIn(1f, 48f)
            ImageBlurUtils.blur(source, radiusPx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Artwork that will not fetch or decode leaves the caller on its unblurred fallback —
            // a dead image host must not take the lyrics page down.
            null
        }
    }

/**
 * [source] as a bitmap the CPU can read, or `null` when the conversion failed (the caller then
 * degrades to its fallback instead of crashing). Only [Bitmap.copy] may convert a hardware bitmap:
 * it reads the graphics buffer back through the render thread.
 */
private fun softwarePixels(source: Bitmap): Bitmap? =
    if (source.config == Bitmap.Config.ARGB_8888) {
        source
    } else {
        source.copy(Bitmap.Config.ARGB_8888, source.config != Bitmap.Config.HARDWARE)
    }
