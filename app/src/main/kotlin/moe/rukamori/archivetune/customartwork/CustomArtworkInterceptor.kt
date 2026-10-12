/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.customartwork

import coil3.intercept.Interceptor
import coil3.request.ImageResult

/**
 * Swaps a song artwork request for the user's custom cover. Every artwork surface (lists, the
 * player styles, the mini player, the media notification through CoilBitmapLoader and the
 * widget) loads through the singleton ImageLoader, so this one seam covers them all without
 * touching the stored thumbnail URL.
 */
class CustomArtworkInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url =
            when (val data = request.data) {
                is String -> data
                is android.net.Uri -> data.toString()
                is coil3.Uri -> data.toString()
                else -> null
            } ?: return chain.proceed()
        val file = CustomArtworkStore.coverFileFor(url) ?: return chain.proceed()
        val overridden =
            request
                .newBuilder()
                .data(file)
                // A caller-pinned cache key would otherwise hand back the original artwork.
                .memoryCacheKey(null as String?)
                .diskCacheKey(null)
                .build()
        return chain.withRequest(overridden).proceed()
    }
}
