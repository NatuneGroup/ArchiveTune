/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.addons

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface AddonState {
    /** This build was made without a pinned copy to fetch (a local build outside git). */
    data object Unavailable : AddonState

    data object NotInstalled : AddonState

    data class Downloading(
        val progressPercent: Int,
    ) : AddonState

    data class Installed(
        val file: File,
    ) : AddonState

    data class Failed(
        val message: String,
    ) : AddonState
}

/**
 * One optional file kept out of the APK and fetched on request from the repository's `addons/`
 * directory.
 *
 * The URL names the commit the build was made from and the SHA-256 is that build's copy of the
 * file, both baked in by Gradle. A later release that changes the file therefore never breaks an
 * older install: the older app keeps asking for its own commit's bytes and checks them against its
 * own digest. The installed file carries the digest in its name, so a release that ships a new
 * version sees the old file as stale and drops it.
 */
class AddonPack(
    private val directory: String,
    private val baseName: String,
    private val extension: String,
    private val relativePath: String,
    private val sha256: String,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<AddonState>(AddonState.NotInstalled)

    val state: StateFlow<AddonState> = mutableState.asStateFlow()

    @Volatile
    private var applicationContext: Context? = null

    private val available: Boolean
        get() = BuildConfig.ADDONS_BASE_URL.isNotEmpty() && sha256.isNotEmpty()

    private val fileName: String
        get() = "$baseName-${sha256.take(16)}.$extension"

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        val dir = File(context.filesDir, directory)
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("$baseName-") && file.name != fileName) file.delete()
        }
        val target = File(dir, fileName)
        mutableState.value =
            when {
                target.isFile -> AddonState.Installed(target)
                !available -> AddonState.Unavailable
                else -> AddonState.NotInstalled
            }
    }

    fun fileOrNull(): File? = (mutableState.value as? AddonState.Installed)?.file

    suspend fun install(): Result<Unit> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val context = requireNotNull(applicationContext) { "Additional downloads are not initialized" }
                    check(available) { "This build has no pinned download for this item" }
                    val dir = File(context.filesDir, directory).apply { mkdirs() }
                    val target = File(dir, fileName)
                    val partial = File(dir, "$fileName.partial")
                    partial.delete()
                    try {
                        download("${BuildConfig.ADDONS_BASE_URL}/$relativePath", partial)
                        check(sha256Of(partial).equals(sha256, ignoreCase = true)) {
                            "The download failed its integrity check"
                        }
                        check(partial.renameTo(target)) { "Could not install the download" }
                        mutableState.value = AddonState.Installed(target)
                    } catch (error: Throwable) {
                        partial.delete()
                        mutableState.value = AddonState.Failed(error.message ?: "Download failed")
                        throw error
                    }
                }
            }
        }

    suspend fun remove(): Boolean =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val context = applicationContext ?: return@withContext false
                val target = File(File(context.filesDir, directory), fileName)
                val removed = !target.exists() || target.delete()
                if (removed) {
                    mutableState.value = if (available) AddonState.NotInstalled else AddonState.Unavailable
                }
                removed
            }
        }

    private fun download(
        url: String,
        destination: File,
    ) {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
        try {
            check(connection.responseCode in 200..299) {
                "The download server returned HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            mutableState.value = AddonState.Downloading(0)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            mutableState.value =
                                AddonState.Downloading(((downloaded * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}

/** The optional files listed on Settings › Additional downloads, apart from the language packs. */
object AdditionalDownloads {
    /** Google Sans Flex, the variable typeface the SpatialFlow player is drawn in (about 4 MB). */
    val spatialFlowFont =
        AddonPack(
            directory = "addons",
            baseName = "spatialflow_google_sans_flex",
            extension = "ttf",
            relativePath = "fonts/spatialflow_google_sans_flex.ttf",
            sha256 = BuildConfig.SPATIALFLOW_FONT_SHA256,
        )

    fun initialize(context: Context) {
        spatialFlowFont.initialize(context)
    }
}
