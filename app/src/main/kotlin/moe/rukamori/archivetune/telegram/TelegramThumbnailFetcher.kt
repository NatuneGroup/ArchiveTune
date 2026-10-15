/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Coil fetcher for Telegram artwork. Artwork is addressed by a `tgart://<fileId>?t=<title>&a=<artist>`
 * model so cover art is resolved lazily and only for images actually shown. Resolution order:
 *   1. a high-resolution catalogue cover looked up online by title/artist (TelegramCoverProvider),
 *   2. the full album cover embedded in the Telegram file (downloaded from TDLib),
 *   3. nothing (Coil shows the placeholder).
 * This keeps the player art crisp without eagerly downloading covers for the whole queue.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.telegram

import android.net.Uri as AndroidUri
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Path.Companion.toPath
import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.utils.withAppProxy

private const val TELEGRAM_ART_SCHEME = "tgart"

/**
 * Builds the Coil model string for a Telegram track's artwork. [title]/[artist] drive the online
 * lookup; [fileId] is the embedded-cover fallback. Returns null when there is nothing to show.
 */
fun telegramArtworkModel(
    fileId: Int,
    title: String?,
    artist: String?,
): String? {
    if (fileId <= 0 && title.isNullOrBlank()) return null
    val builder = AndroidUri.Builder().scheme(TELEGRAM_ART_SCHEME).authority(fileId.coerceAtLeast(0).toString())
    if (!title.isNullOrBlank()) builder.appendQueryParameter("t", title)
    if (!artist.isNullOrBlank()) builder.appendQueryParameter("a", artist)
    return builder.build().toString()
}

class TelegramThumbnailFetcher(
    private val data: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val parsed = AndroidUri.parse(data.toString())
        val fileId = parsed.authority?.toIntOrNull() ?: 0
        val title = parsed.getQueryParameter("t")
        val artist = parsed.getQueryParameter("a")

        // 1. High-resolution catalogue cover from the internet.
        if (!title.isNullOrBlank()) {
            val coverUrl = withContext(Dispatchers.IO) { TelegramCoverProvider.coverUrl(title, artist) }
            if (coverUrl != null) {
                val remote = fetchRemote(coverUrl)
                if (remote != null) return remote
            }
        }

        // 2. Embedded album cover from the Telegram file.
        // We read the TDLib-downloaded file into a Buffer and return it as
        // DataSource.NETWORK so Coil writes it to its own disk cache. Without
        // this, the TDLib file is treated as already-on-disk (DataSource.DISK)
        // and never enters Coil's cache — so the next time the same track's
        // artwork is requested, we'd re-download from TDLib (and possibly
        // re-fetch the embedded cover) every time.
        if (fileId > 0) {
            val path = TelegramClient.downloadFileBlocking(fileId) ?: return null
            return withContext(Dispatchers.IO) {
                runCatching {
                    val source = java.io.File(path)
                    val bytes = source.readBytes()
                    SourceFetchResult(
                        source =
                            ImageSource(
                                source = Buffer().write(bytes),
                                fileSystem = options.fileSystem,
                            ),
                        mimeType = null,
                        // Mark as NETWORK so Coil persists the bytes into its
                        // disk cache for future lookups.
                        dataSource = DataSource.NETWORK,
                    )
                }.getOrNull()
            }
        }
        return null
    }

    private suspend fun fetchRemote(url: String): FetchResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    SourceFetchResult(
                        source =
                            ImageSource(
                                source = Buffer().write(bytes),
                                fileSystem = options.fileSystem,
                            ),
                        mimeType = response.body?.contentType()?.toString(),
                        dataSource = DataSource.NETWORK,
                    )
                }
            }.getOrNull()
        }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (data.scheme != TELEGRAM_ART_SCHEME) return null
            return TelegramThumbnailFetcher(data, options)
        }
    }

    private companion object {
        val httpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .withAppProxy()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
