/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Result of a canvas-video save attempt. */
sealed class CanvasSaveResult {
    data class Success(
        val uri: Uri,
        val filePath: String,
    ) : CanvasSaveResult()

    data class Failure(val message: String) : CanvasSaveResult()

    data class NotDownloadable(val message: String) : CanvasSaveResult()
}

/**
 * Downloads a canvas video from [videoUrl] and saves it to user-visible storage (Movies/ArchiveTune
 * Canvas/ on Android 10+ via MediaStore.Video, or the app's Movies directory on pre-Q).
 */
object CanvasSaver {
    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun isDownloadableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val normalized = url.lowercase(Locale.ROOT)
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        if (normalized.contains(".m3u8")) return false
        if (normalized.contains("application/x-mpegurl")) return false
        return true
    }

    suspend fun saveCanvasVideo(
        context: Context,
        videoUrl: String,
        songTitle: String,
        sourceName: String,
    ): CanvasSaveResult {
        if (!isDownloadableUrl(videoUrl)) {
            return CanvasSaveResult.NotDownloadable(
                "This canvas source uses HLS streaming and can't be saved as a single video file.",
            )
        }

        val safeTitle = sanitizeFileName(songTitle).ifBlank { "canvas" }
        val safeSource = sanitizeFileName(sourceName).ifBlank { "source" }
        val fileName = "$safeTitle ($safeSource)"

        return try {
            val bytes = downloadVideoBytes(videoUrl)
            if (bytes == null || bytes.isEmpty()) {
                return CanvasSaveResult.Failure("Downloaded file is empty.")
            }
            saveBytesToStorage(context, bytes, fileName)
        } catch (e: Exception) {
            CanvasSaveResult.Failure(e.message ?: "Unknown download error.")
        }
    }

    private fun downloadVideoBytes(url: String): ByteArray? {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "ArchiveTune/1.0 (Android canvas saver)")
                .header("Accept", "video/mp4, video/*, */*")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.bytes()
            return bytes
        }
    }

    private fun saveBytesToStorage(
        context: Context,
        bytes: ByteArray,
        fileName: String,
    ): CanvasSaveResult {
        val mimeType = "video/mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ArchiveTune Canvas")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri =
                context.contentResolver.insert(collection, contentValues)
                    ?: return CanvasSaveResult.Failure("Failed to create MediaStore record.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: return CanvasSaveResult.Failure("Failed to open output stream for $uri.")
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
                CanvasSaveResult.Success(
                    uri = uri,
                    filePath = "${Environment.DIRECTORY_MOVIES}/ArchiveTune Canvas/$fileName.mp4",
                )
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                CanvasSaveResult.Failure(e.message ?: "Failed to write video to storage.")
            }
        } else {
            // Pre-Q: write to public Movies directory directly.
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ArchiveTune Canvas")
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val file = File(moviesDir, "$fileName.mp4")
            try {
                file.outputStream().use { out ->
                    out.write(bytes)
                    out.flush()
                }
                CanvasSaveResult.Success(
                    uri = Uri.fromFile(file),
                    filePath = file.absolutePath,
                )
            } catch (e: Exception) {
                CanvasSaveResult.Failure(e.message ?: "Failed to write video to ${file.absolutePath}.")
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .replace('/', '-')
            .replace('\\', '-')
            .replace(':', '-')
            .replace('*', '-')
            .replace('?', '-')
            .replace('"', '-')
            .replace('<', '-')
            .replace('>', '-')
            .replace('|', '-')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifBlank { "canvas" }
    }
}
