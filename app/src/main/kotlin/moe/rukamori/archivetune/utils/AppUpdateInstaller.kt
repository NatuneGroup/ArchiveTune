/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import okhttp3.ConnectionPool
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Downloads an update APK into `cache/app_update` and hands the staged file to the system
 * installer.
 *
 * The download belongs to this process-scoped object, not to the screen that starts it: leaving the
 * update page (or any recomposition that disposes it) used to cancel the coroutine mid-stream and
 * throw away every byte fetched so far. The screen observes [downloadState] instead, so progress
 * survives navigation and a completed download waits for the reader to come back rather than
 * launching the installer from a background process.
 *
 * Nothing here resumes a *partial* file: a half-streamed APK has no central directory to validate,
 * so an interrupted attempt is deleted and the next one starts from byte zero. A *completed*
 * download is a different matter and is kept — see [startDownload].
 */
object AppUpdateInstaller {
    sealed interface DownloadState {
        /** Nothing staged and nothing in flight. */
        object Idle : DownloadState

        /** Bytes are being streamed into the staging file. [fraction] is null until the length is known. */
        data class Downloading(
            val fraction: Float?,
        ) : DownloadState

        /**
         * The APK is staged and validated; [installStagedUpdate] hands it to the installer.
         * [reused] marks a staged file from an earlier attempt rather than a fresh download.
         */
        data class ReadyToInstall(
            val reused: Boolean,
        ) : DownloadState

        /** The attempt failed before anything could be installed. [message] is safe to display. */
        data class Failed(
            val message: String?,
        ) : DownloadState
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    withAppProxy()
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
                    retryOnConnectionFailure(true)
                    followRedirects(true)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /**
     * Runs the update the reader asked for, unless one is already running — two taps must not race
     * each other into the same staging file.
     *
     * An APK already staged for this exact release is installed as-is: re-fetching it would throw
     * away a finished download, which is what "tap Update, dismiss the system installer, tap Update
     * again" used to do, and what reopening the app after a download used to do.
     */
    fun startDownload(
        context: Context,
        url: String,
    ) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _downloadState.value = DownloadState.Downloading(null)
        downloadJob =
            scope.launch {
                val result = download(appContext, url)
                _downloadState.value =
                    result.fold(
                        onSuccess = { reused -> DownloadState.ReadyToInstall(reused) },
                        onFailure = { DownloadState.Failed(it.message) },
                    )
            }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
    }

    /** Clears a terminal state the caller has already acted on, so a returning screen does not repeat it. */
    fun acknowledgeResult() {
        if (_downloadState.value is DownloadState.Downloading) return
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Hands the staged APK to the system installer. Returns false when there is nothing installable
     * left to hand over — the reader may have swiped the app away long enough for the cache to be
     * evicted, and re-downloading is the only honest answer at that point.
     */
    fun installStagedUpdate(context: Context): Boolean {
        val stagedFile = File(updateDirectory(context), ApkFileName)
        if (!stagedFile.isFile || !stagedFile.containsApkManifest()) return false
        installApk(context, stagedFile)
        return true
    }

    /**
     * Sweeps the staging directory at process start.
     *
     * A partial file is debris by definition. A staged APK is debris once the running build has
     * reached it — that is the update having installed, or an artifact older than what is already
     * here — and dropping it then is what stops a previous version's APK outliving its install.
     * One that is still newer is kept, so a completed download survives closing the app and can be
     * installed later without fetching it again.
     */
    fun pruneStagedUpdate(context: Context) {
        val updateDir = updateDirectory(context)
        File(updateDir, DownloadFileName).delete()

        val apkFile = File(updateDir, ApkFileName)
        val sourceFile = File(updateDir, SourceFileName)
        val pendingUpgrade =
            apkFile.isFile &&
                sourceFile.isFile &&
                (stagedVersionCode(context, apkFile) ?: 0L) > BuildConfig.VERSION_CODE.toLong()
        if (pendingUpgrade) return

        apkFile.delete()
        sourceFile.delete()
    }

    private suspend fun download(
        context: Context,
        url: String,
    ): Result<Boolean> {
        if (BuildConfig.DISTRIBUTION != "gms") {
            return Result.failure(IllegalStateException("In-app updates are only available for GMS builds"))
        }

        return try {
            val reused = withContext(Dispatchers.IO) { reuseStagedUpdate(context, url) }
            if (!reused) withContext(Dispatchers.IO) { downloadApk(context, url) }
            Result.success(reused)
        } catch (e: CancellationException) {
            // Half a file is worse than none: it cannot be validated or installed, and it would sit
            // in the cache until some later attempt happened to wipe it. Cleanup has to outlive the
            // cancellation that got us here.
            withContext(NonCancellable + Dispatchers.IO) { clearStagedUpdate(context) }
            throw e
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { clearStagedUpdate(context) }
            Result.failure(e)
        }
    }

    /**
     * True when the staged APK is already the artifact this URL serves. The record of where it came
     * from is a sidecar file next to it rather than a preference, so cache eviction can only ever
     * take both or neither; the build number comes from the APK itself, so a file that is somehow
     * the version already running is never handed back to the installer.
     */
    private fun reuseStagedUpdate(
        context: Context,
        url: String,
    ): Boolean {
        val updateDir = updateDirectory(context)
        val apkFile = File(updateDir, ApkFileName)
        if (!apkFile.isFile) return false

        val sourceFile = File(updateDir, SourceFileName)
        val sourceUrl =
            sourceFile
                .takeIf { it.isFile }
                ?.let { file -> runCatching { file.readText().trim() }.getOrNull() }
        if (sourceUrl != url) return false

        return (stagedVersionCode(context, apkFile) ?: 0L) > BuildConfig.VERSION_CODE.toLong()
    }

    private suspend fun clearStagedUpdate(context: Context) {
        updateDirectory(context).deleteRecursively()
    }

    private suspend fun downloadApk(
        context: Context,
        url: String,
    ): File {
        if (url.isBlank()) {
            throw IOException("Update download URL is empty")
        }

        val updateDir = updateDirectory(context)
        updateDir.mkdirs()
        // Wipe first, so nothing left by an earlier attempt — including the sidecar that would let
        // it be reused — can be mistaken for this attempt's result. Downloads start from byte zero.
        updateDir.listFiles()?.forEach { file -> file.deleteRecursively() }

        val downloadedFile = File(updateDir, DownloadFileName)

        var expectedBytes = -1L
        var receivedBytes = 0L

        client.prepareGet(url).execute { response ->
            val responseCode = response.status.value
            if (responseCode !in 200..299) {
                throw IOException("Update download failed: HTTP $responseCode")
            }

            expectedBytes = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            downloadedFile.outputStream().use { output ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                var downloadedBytes = 0L
                var lastUpdateMs = 0L
                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= PROGRESS_UPDATE_INTERVAL_MS) {
                        emitProgress(downloadedBytes, expectedBytes)
                        lastUpdateMs = now
                    }
                }
                receivedBytes = downloadedBytes
                emitProgress(downloadedBytes, expectedBytes)
            }
        }

        // A truncated body reaches here when the server closes a chunked response early, or when a
        // captive portal answers the asset URL with a 200 and an HTML page. Either way the file is
        // not an APK, and handing it to the installer only produces "package appears to be invalid".
        if (expectedBytes > 0L && receivedBytes != expectedBytes) {
            throw IOException("Update download was truncated: got $receivedBytes of $expectedBytes bytes")
        }

        val apkFile =
            if (url.lowercase(Locale.US).substringBefore('?').endsWith(".apk")) {
                downloadedFile.renameAsApk()
            } else {
                extractGmsApk(downloadedFile, File(updateDir, ApkFileName))
                    ?: if (downloadedFile.containsApkManifest()) {
                        downloadedFile.renameAsApk()
                    } else {
                        throw IOException("No GMS APK found in update artifact")
                    }
            }

        if (!apkFile.containsApkManifest()) {
            throw IOException("Downloaded update is not a valid APK")
        }

        // Written last, so a killed download can never leave a staged APK that claims to be a
        // completed one.
        File(updateDir, SourceFileName).writeText(url)

        return apkFile
    }

    private fun emitProgress(
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        _downloadState.value = DownloadState.Downloading(fractionOrNull(downloadedBytes, totalBytes))
    }

    private fun fractionOrNull(
        downloadedBytes: Long,
        totalBytes: Long,
    ): Float? =
        totalBytes
            .takeIf { it > 0L }
            ?.let { total -> (downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) }

    /** The versionCode the staged APK declares, or null when it cannot be read as a package. */
    @Suppress("DEPRECATION")
    private fun stagedVersionCode(
        context: Context,
        apkFile: File,
    ): Long? =
        runCatching {
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info?.longVersionCode
            } else {
                info?.versionCode?.toLong()
            }
        }.getOrNull()

    private fun extractGmsApk(
        sourceFile: File,
        targetFile: File,
    ): File? =
        runCatching {
            ZipFile(sourceFile).use { zip ->
                val entries =
                    zip.entries().asSequence().filter { entry ->
                        val fileName = entry.name.substringAfterLast('/')
                        !entry.isDirectory &&
                            entry.name.endsWith(".apk", ignoreCase = true) &&
                            !fileName.contains("foss-", ignoreCase = true) &&
                            !fileName.contains("izzy-", ignoreCase = true)
                    }
                val preferredArtifactNames =
                    listOf(
                        "app-gms-${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-",
                        "app-${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-",
                    )
                val selectedEntry =
                    entries
                        .sortedBy { entry ->
                            val fileName = entry.name.substringAfterLast('/')
                            val preferredIndex =
                                preferredArtifactNames.indexOfFirst { preferredArtifactName ->
                                    fileName.contains(preferredArtifactName, ignoreCase = true)
                                }
                            if (preferredIndex >= 0) preferredIndex else preferredArtifactNames.size
                        }.firstOrNull()
                        ?: return@runCatching null

                zip.getInputStream(selectedEntry).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                targetFile
            }
        }.getOrNull()

    private fun File.containsApkManifest(): Boolean =
        runCatching {
            ZipFile(this).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)

    private fun File.renameAsApk(): File {
        val apkFile = File(parentFile, ApkFileName)
        if (this == apkFile) return this
        if (!renameTo(apkFile)) {
            copyTo(apkFile, overwrite = true)
            delete()
        }
        return apkFile
    }

    private fun installApk(
        context: Context,
        apkFile: File,
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                apkFile,
            )
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ApkMimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun updateDirectory(context: Context): File = File(context.cacheDir, UpdateDirectoryName)

    private const val UpdateDirectoryName = "app_update"
    private const val DownloadFileName = "archive-tune-update.download"
    private const val ApkFileName = "archive-tune-update.apk"
    private const val SourceFileName = "archive-tune-update.source"
    private const val ApkMimeType = "application/vnd.android.package-archive"
    private const val STREAM_BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_UPDATE_INTERVAL_MS = 200L
}
