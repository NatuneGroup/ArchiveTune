/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.atilika.kuromoji.util.ResourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.extensions.isInternetConnected
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.withAppProxy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

sealed interface JapaneseLanguagePackState {
    data object Checking : JapaneseLanguagePackState
    data object NotInstalled : JapaneseLanguagePackState
    /** A negative percentage is indeterminate (connecting, unknown length, or validating). */
    data class Downloading(val progressPercent: Int = -1) : JapaneseLanguagePackState
    data object Removing : JapaneseLanguagePackState
    data class Installed(val sizeBytes: Long) : JapaneseLanguagePackState
    data class Failed(val message: String, val removalFailed: Boolean = false) : JapaneseLanguagePackState
}

/** Application-owned, data-only IPADIC installation. No executable classes are loaded from the JAR. */
object JapaneseLanguagePackManager {
    private const val PACK_DIRECTORY = "language_packs"
    private const val PACK_FILE = "kuromoji-ipadic-0.9.0.jar"
    private const val RESOURCE_PREFIX = "com/atilika/kuromoji/ipadic/"
    const val EXPECTED_SIZE_BYTES = 13_343_016L
    private const val DOWNLOAD_URL =
        "https://repo.maven.apache.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"
    private const val EXPECTED_SHA256 =
        "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
    private const val TRANSFER_DEADLINE_MS = 180_000L
    private val autoAttemptedKey = booleanPreferencesKey("japanesePackAutoAttemptedV1")
    private val removedKey = booleanPreferencesKey("japanesePackRemoved")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val requestLock = Any()
    private val consideredForeground = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<JapaneseLanguagePackState>(JapaneseLanguagePackState.Checking)
    val state: StateFlow<JapaneseLanguagePackState> = mutableState.asStateFlow()

    @Volatile private var applicationContext: Context? = null
    @Volatile private var cachedTokenizer: DownloadedJapaneseTokenizer? = null
    @Volatile private var generation = 0L
    private var initialization: Job? = null
    private var operation: Job? = null

    fun initialize(context: Context) = synchronized(requestLock) {
        if (applicationContext != null) return@synchronized
        applicationContext = context.applicationContext
        initialization = scope.launch {
            operationMutex.withLock {
                val target = packFile(context)
                partialFile(context).delete()
                try {
                    if (target.isFile) {
                        val tokenizer = validateAndOpen(target)
                        if (mutableState.value != JapaneseLanguagePackState.Removing) cachedTokenizer = tokenizer
                        if (mutableState.value == JapaneseLanguagePackState.Checking) {
                            mutableState.value = JapaneseLanguagePackState.Installed(target.length())
                        }
                    } else if (mutableState.value == JapaneseLanguagePackState.Checking) {
                        mutableState.value = JapaneseLanguagePackState.NotInstalled
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    target.delete()
                    cachedTokenizer = null
                    if (mutableState.value == JapaneseLanguagePackState.Checking) {
                        mutableState.value = failure(R.string.japanese_pack_invalid)
                    }
                }
            }
        }
    }

    /** Called only from the application foreground event; offline/failure never starts a retry loop. */
    fun onForeground() {
        if (!consideredForeground.compareAndSet(false, true)) return
        scope.launch {
            initialization?.join()
            val context = applicationContext ?: return@launch
            try {
                // System resources, not app language overrides, SIM, or the YouTube content country.
                val region = Resources.getSystem().configuration.locales.get(0)?.country
                operationMutex.withLock {
                    val preferences = context.dataStore.data.first()
                    if (!automaticEligible(
                            region, cachedTokenizer != null, preferences[LyricsRomanizeJapaneseKey],
                            preferences[removedKey] == true, preferences[autoAttemptedKey] == true,
                        )) return@withLock
                    // Consume the one consideration even when offline. Manual retry stays available.
                    context.dataStore.edit { it[autoAttemptedKey] = true }
                    if (context.isInternetConnected()) requestInstall(automatic = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Best effort only: playback/startup never depend on automatic data installation.
            }
        }
    }

    internal fun automaticEligible(
        primaryRegion: String?,
        installed: Boolean,
        japaneseChoice: Boolean?,
        removed: Boolean,
        attempted: Boolean,
    ): Boolean = primaryRegion.equals("JP", ignoreCase = true) && !installed &&
        japaneseChoice != false && !removed && !attempted

    fun install() = requestInstall(automatic = false)

    private fun requestInstall(automatic: Boolean) = synchronized(requestLock) {
        val context = applicationContext ?: return@synchronized
        if (operation?.isActive == true || cachedTokenizer != null) return@synchronized
        val id = ++generation
        mutableState.value = JapaneseLanguagePackState.Downloading()
        operation = scope.launch(start = CoroutineStart.LAZY) {
            initialization?.join()
            operationMutex.withLock {
                val partial = partialFile(context)
                val target = packFile(context)
                var promoted = false
                var published = false
                try {
                    currentCoroutineContext().ensureActive()
                    if (cachedTokenizer != null) {
                        mutableState.value = JapaneseLanguagePackState.Installed(packFile(context).length())
                        return@withLock
                    }
                    val startingChoice = context.dataStore.data.first()[LyricsRomanizeJapaneseKey]
                    if (automatic) {
                        val preferences = context.dataStore.data.first()
                        if (preferences[LyricsRomanizeJapaneseKey] == false || preferences[removedKey] == true) {
                            mutableState.value = JapaneseLanguagePackState.NotInstalled
                            return@withLock
                        }
                    }
                    check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
                    check(target.parentFile!!.usableSpace >= EXPECTED_SIZE_BYTES * 2 + 4 * 1024 * 1024) {
                        "Insufficient dictionary storage"
                    }
                    check(!partial.exists() || partial.delete())
                    downloadPack(partial, id)
                    val tokenizer = validateAndOpen(partial)
                    currentCoroutineContext().ensureActive()
                    check(id == generation)
                    // Same-directory rename is atomic; all validation completed before publication.
                    check(partial.renameTo(target)) { "Could not install dictionary data" }
                    promoted = true
                    context.dataStore.edit { preferences ->
                        if (id == generation) {
                            if (!automatic) preferences[removedKey] = false
                            val choice = preferences[LyricsRomanizeJapaneseKey]
                            if ((automatic && choice != false && preferences[removedKey] != true) ||
                                (!automatic && choice == startingChoice)
                            ) preferences[LyricsRomanizeJapaneseKey] = true
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(requestLock) {
                        if (id == generation) {
                            cachedTokenizer = tokenizer
                            published = true
                            mutableState.value = JapaneseLanguagePackState.Installed(target.length())
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    if (id == generation) mutableState.value = failure(R.string.japanese_pack_failed)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        partial.delete()
                        if (promoted && !published) target.delete()
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                // Includes cancellation before the lazy job starts or while waiting for validation.
                synchronized(requestLock) {
                    if (id == generation && mutableState.value is JapaneseLanguagePackState.Downloading) {
                        mutableState.value = if (cachedTokenizer != null) JapaneseLanguagePackState.Installed(EXPECTED_SIZE_BYTES)
                            else JapaneseLanguagePackState.NotInstalled
                    }
                }
            }
            job.start()
        }
    }

    /** Cancellation waits for stream cleanup before a new request can acquire the operation mutex. */
    fun cancel() = synchronized(requestLock) {
        if (mutableState.value is JapaneseLanguagePackState.Downloading) operation?.cancel()
    }

    fun remove() = synchronized(requestLock) {
        val context = applicationContext ?: return@synchronized
        if (mutableState.value == JapaneseLanguagePackState.Removing) return@synchronized
        val previous = operation
        val id = ++generation
        cachedTokenizer = null
        previous?.cancel()
        mutableState.value = JapaneseLanguagePackState.Removing
        operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                context.dataStore.edit {
                    it[removedKey] = true
                    it[LyricsRomanizeJapaneseKey] = false
                }
                previous?.join()
                initialization?.join()
                operationMutex.withLock {
                    cachedTokenizer = null
                    val target = packFile(context)
                    val removed = (!target.exists() || target.delete()) &&
                        (!partialFile(context).exists() || partialFile(context).delete())
                    if (id == generation) mutableState.value = if (removed) JapaneseLanguagePackState.NotInstalled
                        else failure(R.string.japanese_pack_remove_failed, removalFailed = true)
                }
            } catch (_: Exception) {
                if (id == generation) mutableState.value = failure(R.string.japanese_pack_remove_failed, removalFailed = true)
            }
        }.also { it.start() }
    }

    /** Never hashes, opens archives, initializes dictionaries or accesses disk on a renderer thread. */
    fun tokenizerOrNull(): DownloadedJapaneseTokenizer? = cachedTokenizer

    private fun packFile(context: Context): File = File(File(context.filesDir, PACK_DIRECTORY), PACK_FILE)
    private fun partialFile(context: Context): File = File(packFile(context).parentFile, "$PACK_FILE.partial")
    private fun failure(message: Int, removalFailed: Boolean = false): JapaneseLanguagePackState.Failed =
        JapaneseLanguagePackState.Failed(requireNotNull(applicationContext).getString(message), removalFailed)

    private suspend fun validateAndOpen(file: File): DownloadedJapaneseTokenizer {
        check(file.length() == EXPECTED_SIZE_BYTES && sha256(file) == EXPECTED_SHA256) { "Invalid dictionary checksum" }
        currentCoroutineContext().ensureActive()
        return ZipFile(file).use { archive ->
            val resolver = ResourceResolver { resourceName ->
                val entry = archive.getEntry(RESOURCE_PREFIX + resourceName)
                    ?: throw IOException("Missing dictionary resource: $resourceName")
                archive.getInputStream(entry)
            }
            DownloadedJapaneseTokenizer.create(resolver)
        }
    }

    private suspend fun downloadPack(destination: File, id: Long) = coroutineScope {
        val client = OkHttpClient.Builder().withAppProxy()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(TRANSFER_DEADLINE_MS, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        val call = client.newCall(Request.Builder().url(DOWNLOAD_URL).build())
        // A sibling cancels blocking network IO as soon as this operation is cancelled.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        val started = SystemClock.elapsedRealtime()
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Dictionary server returned HTTP ${response.code}" }
                val body = response.body ?: throw IOException("Missing dictionary response")
                val total = body.contentLength()
                check(total < 0L || total == EXPECTED_SIZE_BYTES) { "Unexpected dictionary size" }
                var downloaded = 0L
                body.byteStream().buffered().use { input ->
                    FileOutputStream(destination).use { fileOutput ->
                        val output = fileOutput.buffered()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(SystemClock.elapsedRealtime() - started <= TRANSFER_DEADLINE_MS) { "Dictionary download timed out" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            check(downloaded <= EXPECTED_SIZE_BYTES) { "Dictionary exceeded size limit" }
                            output.write(buffer, 0, count)
                            if (id == generation) mutableState.value = JapaneseLanguagePackState.Downloading(
                                if (total > 0) ((downloaded * 100L) / total).toInt().coerceAtMost(99) else -1,
                            )
                        }
                        output.flush()
                        fileOutput.fd.sync()
                        check(downloaded == EXPECTED_SIZE_BYTES) { "Incomplete dictionary download" }
                    }
                }
            }
            if (id == generation) mutableState.value = JapaneseLanguagePackState.Downloading()
        } finally {
            call.cancel()
            cancellation.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
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
