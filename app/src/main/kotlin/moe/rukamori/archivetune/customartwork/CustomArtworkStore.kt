/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.customartwork

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.Locale

enum class CustomArtworkResult {
    Saved,
    TooLarge,
    Unsupported,
    Failed,
}

/**
 * User-picked replacements for song covers and canvases, kept as plain files under filesDir.
 *
 * Covers are keyed on the artwork they replace (a size-independent identity of the thumbnail
 * URL), not on the song row: the URL is what every image surface, the notification and the
 * widget actually request through Coil, and the database thumbnailUrl stays untouched so a
 * metadata refresh or a backup restore cannot clobber or leak a device-local path. Songs that
 * share one artwork URL (tracks of the same album) therefore share the replacement.
 *
 * Canvases are keyed on the song id, because canvas resolution already runs per media id.
 *
 * File names are `<sha256 prefix>_<epoch millis>[.ext]`; a new version gets a new path so
 * Coil's memory cache and ExoPlayer's canvas cache can never serve the replaced file.
 */
object CustomArtworkStore {
    const val MaxCoverBytes: Long = 20L * 1024 * 1024
    const val MaxCanvasBytes: Long = 50L * 1024 * 1024

    private const val CoverDirName = "custom_artwork"
    private const val CanvasDirName = "custom_canvas"
    private const val TempSuffix = ".tmp"
    private const val LogTag = "CustomArtwork"

    private val ImageCanvasExtensions = setOf("gif", "png", "jpg", "jpeg", "webp", "heic", "heif", "avif", "bmp")
    private val VideoCanvasExtensions = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")
    private val YtimgVideoIdRegex = Regex("/vi(?:_webp)?/([a-zA-Z0-9_-]{11})/")
    private val GoogleSizePathRegex = Regex("/w\\d+-h\\d+[^/]*")
    private val SizedFileNameRegex = Regex("^\\d+x\\d+[a-z]*(?:-\\d+)?\\.(?:jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)

    data class Overrides(
        val covers: Map<String, String> = emptyMap(),
        val canvases: Map<String, String> = emptyMap(),
    ) {
        fun hasCover(artworkUrl: String?): Boolean {
            if (covers.isEmpty() || artworkUrl.isNullOrBlank()) return false
            val identity = artworkIdentity(artworkUrl) ?: return false
            return hash(identity) in covers
        }

        fun hasCanvas(songId: String?): Boolean {
            if (canvases.isEmpty() || songId.isNullOrBlank()) return false
            return hash(songId) in canvases
        }
    }

    private val lock = Any()

    @Volatile private var rootDir: File? = null

    @Volatile private var loaded = false

    private val _overrides = MutableStateFlow(Overrides())
    val overrides: StateFlow<Overrides> = _overrides.asStateFlow()

    fun init(context: Context) {
        if (rootDir == null) rootDir = context.applicationContext.filesDir
    }

    /** Lists the override directories once; cheap, but still disk IO. */
    fun preload() {
        ensureLoaded()
    }

    fun coverFileFor(artworkUrl: String?): File? {
        if (artworkUrl.isNullOrBlank()) return null
        ensureLoaded()
        val covers = _overrides.value.covers
        if (covers.isEmpty()) return null
        val identity = artworkIdentity(artworkUrl) ?: return null
        val name = covers[hash(identity)] ?: return null
        return File(directory(CoverDirName) ?: return null, name).takeIf { it.isFile }
    }

    fun canvasFileFor(songId: String?): File? {
        if (songId.isNullOrBlank()) return null
        ensureLoaded()
        val canvases = _overrides.value.canvases
        if (canvases.isEmpty()) return null
        val name = canvases[hash(songId)] ?: return null
        return File(directory(CanvasDirName) ?: return null, name).takeIf { it.isFile }
    }

    /** The custom canvas for [songId] shaped like a fetched one, so every canvas surface can play it. */
    fun customCanvasArtwork(songId: String?): CanvasArtwork? {
        val file = canvasFileFor(songId) ?: return null
        val uri = Uri.fromFile(file).toString()
        return CanvasArtwork(
            static = uri.takeIf { file.extension.lowercase(Locale.ROOT) in ImageCanvasExtensions },
            animated = uri,
            animatedVertical = uri,
            videoUrl = uri,
            videoUrlVertical = uri,
        )
    }

    fun isCustomCanvasUrl(url: String?): Boolean =
        url != null && url.startsWith("file:") && url.contains("/$CanvasDirName/")

    fun isCustomImageCanvasUrl(url: String?): Boolean =
        isCustomCanvasUrl(url) &&
            url!!.substringAfterLast('.', "").lowercase(Locale.ROOT) in ImageCanvasExtensions

    suspend fun setCover(
        context: Context,
        artworkUrl: String,
        source: Uri,
    ): CustomArtworkResult =
        withContext(Dispatchers.IO + NonCancellable) {
            init(context)
            ensureLoaded()
            val identity = artworkIdentity(artworkUrl) ?: return@withContext CustomArtworkResult.Unsupported
            val dir = directory(CoverDirName) ?: return@withContext CustomArtworkResult.Failed
            val id = hash(identity)
            val copied =
                when (val outcome = copyInto(context, source, dir, id, extension = null, maxBytes = MaxCoverBytes)) {
                    is CopyOutcome.Copied -> outcome.file
                    is CopyOutcome.Rejected -> return@withContext outcome.result
                }
            if (!copied.isDecodableImage()) {
                copied.delete()
                return@withContext CustomArtworkResult.Unsupported
            }
            commit(dir, id, copied.name, cover = true)
            CustomArtworkResult.Saved
        }

    suspend fun setCanvas(
        context: Context,
        songId: String,
        source: Uri,
    ): CustomArtworkResult =
        withContext(Dispatchers.IO + NonCancellable) {
            init(context)
            ensureLoaded()
            if (songId.isBlank()) return@withContext CustomArtworkResult.Unsupported
            val extension = canvasExtension(context, source) ?: return@withContext CustomArtworkResult.Unsupported
            val dir = directory(CanvasDirName) ?: return@withContext CustomArtworkResult.Failed
            val id = hash(songId)
            val copied =
                when (val outcome = copyInto(context, source, dir, id, extension, MaxCanvasBytes)) {
                    is CopyOutcome.Copied -> outcome.file
                    is CopyOutcome.Rejected -> return@withContext outcome.result
                }
            if (extension in ImageCanvasExtensions && !copied.isDecodableImage()) {
                copied.delete()
                return@withContext CustomArtworkResult.Unsupported
            }
            commit(dir, id, copied.name, cover = false)
            CustomArtworkResult.Saved
        }

    suspend fun removeCover(artworkUrl: String?): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            ensureLoaded()
            val identity = artworkUrl?.let(::artworkIdentity) ?: return@withContext false
            remove(CoverDirName, hash(identity), cover = true)
        }

    suspend fun removeCanvas(songId: String?): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            ensureLoaded()
            if (songId.isNullOrBlank()) return@withContext false
            remove(CanvasDirName, hash(songId), cover = false)
        }

    /**
     * A size-independent identity for an artwork URL. Callers resize the same artwork for lists,
     * the player and the notification, so the size segments are dropped before keying.
     */
    internal fun artworkIdentity(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("file:") && trimmed.contains("/$CoverDirName/")) return null
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
        val rest = trimmed.substringAfter("://")
        if (scheme != "http" && scheme != "https") return trimmed

        val host = rest.substringBefore('/').lowercase(Locale.ROOT)
        if (host.endsWith("ytimg.com")) {
            YtimgVideoIdRegex.find(rest)?.let { return "yt:${it.groupValues[1]}" }
        }
        if (host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com")) {
            return "g:" + rest.substringBefore('=').substringBefore('?').replace(GoogleSizePathRegex, "").trimEnd('/')
        }
        val path = rest.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        return if (SizedFileNameRegex.matches(lastSegment)) path.substringBeforeLast('/') else path
    }

    private fun hash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(40)

    private fun directory(name: String): File? = rootDir?.let { File(it, name) }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            if (rootDir == null) return
            _overrides.value =
                Overrides(
                    covers = scan(directory(CoverDirName)),
                    canvases = scan(directory(CanvasDirName)),
                )
            loaded = true
        }
    }

    private fun scan(dir: File?): Map<String, String> {
        val files = dir?.listFiles()?.filter { it.isFile } ?: return emptyMap()
        val (temps, candidates) = files.partition { it.name.endsWith(TempSuffix) || '_' !in it.name }
        temps.forEach { runCatching { it.delete() } }
        val result = HashMap<String, String>()
        candidates
            .filter { it.length() > 0L }
            .groupBy { it.name.substringBefore('_') }
            .forEach { (id, group) ->
                val newest = group.maxBy { it.name.version() }
                group.filter { it != newest }.forEach { runCatching { it.delete() } }
                result[id] = newest.name
            }
        return result
    }

    private fun String.version(): Long = substringAfter('_').substringBefore('.').toLongOrNull() ?: 0L

    private fun commit(
        dir: File,
        id: String,
        name: String,
        cover: Boolean,
    ) {
        synchronized(lock) {
            val current = _overrides.value
            val map = if (cover) current.covers else current.canvases
            val previous = map[id]
            val updated = map + (id to name)
            _overrides.value = if (cover) current.copy(covers = updated) else current.copy(canvases = updated)
            if (previous != null && previous != name) runCatching { File(dir, previous).delete() }
        }
    }

    private fun remove(
        dirName: String,
        id: String,
        cover: Boolean,
    ): Boolean {
        val dir = directory(dirName) ?: return false
        synchronized(lock) {
            val current = _overrides.value
            val map = if (cover) current.covers else current.canvases
            val name = map[id] ?: return false
            val updated = map - id
            _overrides.value = if (cover) current.copy(covers = updated) else current.copy(canvases = updated)
            runCatching { File(dir, name).delete() }
            return true
        }
    }

    private fun canvasExtension(
        context: Context,
        source: Uri,
    ): String? {
        val mime = runCatching { context.contentResolver.getType(source) }.getOrNull()?.lowercase(Locale.ROOT)
        if (mime != null && !mime.startsWith("image/") && !mime.startsWith("video/")) return null
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val fromPath = source.lastPathSegment?.substringAfterLast('.', "")
        val extension =
            (fromMime ?: fromPath)
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return extension.takeIf { it in ImageCanvasExtensions || it in VideoCanvasExtensions }
    }

    private sealed interface CopyOutcome {
        data class Copied(
            val file: File,
        ) : CopyOutcome

        data class Rejected(
            val result: CustomArtworkResult,
        ) : CopyOutcome
    }

    private fun copyInto(
        context: Context,
        source: Uri,
        dir: File,
        id: String,
        extension: String?,
        maxBytes: Long,
    ): CopyOutcome {
        val temp = File(dir, "$id$TempSuffix")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return CopyOutcome.Rejected(CustomArtworkResult.Failed)
            val input =
                context.contentResolver.openInputStream(source)
                    ?: return CopyOutcome.Rejected(CustomArtworkResult.Failed)
            var total = 0L
            var tooLarge = false
            input.use { stream ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            when {
                tooLarge -> {
                    temp.delete()
                    CopyOutcome.Rejected(CustomArtworkResult.TooLarge)
                }

                total == 0L -> {
                    temp.delete()
                    CopyOutcome.Rejected(CustomArtworkResult.Failed)
                }

                else -> {
                    val name = "${id}_${System.currentTimeMillis()}" + (extension?.let { ".$it" } ?: "")
                    val target = File(dir, name)
                    if (temp.renameTo(target)) {
                        CopyOutcome.Copied(target)
                    } else {
                        temp.delete()
                        CopyOutcome.Rejected(CustomArtworkResult.Failed)
                    }
                }
            }
        } catch (error: CancellationException) {
            temp.delete()
            throw error
        } catch (error: Exception) {
            Timber.tag(LogTag).w(error, "Copying custom artwork failed")
            temp.delete()
            CopyOutcome.Rejected(CustomArtworkResult.Failed)
        }
    }

    private fun File.isDecodableImage(): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(absolutePath, options) }
        return options.outWidth > 0 && options.outHeight > 0
    }
}
