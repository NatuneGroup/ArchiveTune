/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.scrobbling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.constants.LastFMSessionKey
import moe.rukamori.archivetune.constants.LastFMUsernameKey
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Plays that reached the scrobble point but have not been accepted yet.
 *
 * Every scrobble goes through here rather than straight to the API, so a play made offline, during
 * a Last.fm outage or while rate limited is sent later instead of being lost. Entries are written
 * to disk before the first send attempt, survive process death, and are flushed in batches of
 * [LastFM.MAX_SCROBBLES_PER_BATCH] with their original start timestamps.
 */
object LastFmScrobbleQueue {
    private const val TAG = "LastFmScrobbleQueue"
    private const val FILE_NAME = "lastfm_scrobble_queue.json"
    private const val MAX_ENTRIES = 2_000

    // Last.fm ignores timestamps older than 14 days (ignoredMessage code 3); keeping them only
    // grows the file.
    private const val MAX_AGE_SECONDS = 14L * 24 * 60 * 60
    private const val MIN_RETRY_DELAY_MS = 30_000L
    private const val MAX_RETRY_DELAY_MS = 30 * 60_000L

    private const val ERROR_INVALID_PARAMETERS = 6
    private const val ERROR_INVALID_RESOURCE = 7
    private const val ERROR_INVALID_SESSION = 9

    @Serializable
    private data class PendingScrobble(
        val artist: String,
        val track: String,
        val timestamp: Long,
        val album: String? = null,
        val duration: Int? = null,
        // endpoint|username: a queue filled on one account must never be sent to another.
        val account: String,
    )

    private sealed interface Failure {
        data object Retry : Failure

        data object Drop : Failure

        data object InvalidSession : Failure

        data object NoSession : Failure
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val flushMutex = Mutex()
    private val entries = ArrayList<PendingScrobble>()
    private val pending = MutableStateFlow(0)

    @Volatile
    private var appContext: Context? = null
    private var loaded = false

    @Volatile
    private var retryJob: Job? = null

    @Volatile
    private var retryDelayMs = MIN_RETRY_DELAY_MS

    /** Number of plays waiting to be sent, across all accounts. */
    val pendingCount: StateFlow<Int> = pending.asStateFlow()

    fun attach(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        registerNetworkCallback(context.applicationContext)
        scope.launch {
            stateMutex.withLock { ensureLoaded() }
            flush()
        }
    }

    fun enqueue(
        metadata: LastFmTrackMetadata,
        startedAtEpochSeconds: Long,
    ) {
        val context = appContext ?: return
        scope.launch {
            val entry =
                PendingScrobble(
                    artist = metadata.artist,
                    track = metadata.track,
                    timestamp = startedAtEpochSeconds,
                    album = metadata.album,
                    duration = metadata.durationSeconds,
                    account = currentAccount(context),
                )
            stateMutex.withLock {
                ensureLoaded()
                val duplicate =
                    entries.any {
                        it.account == entry.account &&
                            it.timestamp == entry.timestamp &&
                            it.artist == entry.artist &&
                            it.track == entry.track
                    }
                if (!duplicate) {
                    entries += entry
                    while (entries.size > MAX_ENTRIES) entries.removeAt(0)
                    persistLocked()
                }
            }
            flush()
        }
    }

    fun flushSoon() {
        if (appContext == null) return
        scope.launch { flush() }
    }

    private suspend fun flush() {
        val context = appContext ?: return
        flushMutex.withLock {
            stateMutex.withLock {
                ensureLoaded()
                pruneExpiredLocked()
            }
            val config = LastFM.currentConfig()
            val sessionKey = config.sessionKey?.takeIf { it.isNotBlank() } ?: return
            if (!LastFM.isInitialized()) return
            val account = currentAccount(context)
            var batchLimit = LastFM.MAX_SCROBBLES_PER_BATCH

            while (true) {
                val batch =
                    stateMutex.withLock {
                        entries.asSequence().filter { it.account == account }.take(batchLimit).toList()
                    }
                if (batch.isEmpty()) {
                    retryDelayMs = MIN_RETRY_DELAY_MS
                    return
                }

                val result =
                    LastFM.scrobbleBatch(
                        batch.map {
                            LastFM.ScrobbleSubmission(
                                artist = it.artist,
                                track = it.track,
                                timestamp = it.timestamp,
                                album = it.album,
                                duration = it.duration,
                            )
                        },
                    )
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    result.getOrNull()?.takeIf { it.ignored > 0 }?.let {
                        Timber.tag(TAG).w("Last.fm ignored %d of %d scrobbles, codes=%s", it.ignored, batch.size, it.ignoredCodes)
                    }
                    remove(batch)
                    retryDelayMs = MIN_RETRY_DELAY_MS
                    continue
                }
                if (failure is CancellationException) throw failure

                when (classify(failure)) {
                    Failure.Drop -> {
                        if (batch.size > 1) {
                            // One malformed entry rejects the whole batch; isolate it instead of
                            // discarding the valid plays sent alongside it.
                            batchLimit = 1
                        } else {
                            Timber.tag(TAG).w(failure, "Dropping rejected scrobble: %s - %s", batch[0].artist, batch[0].track)
                            remove(batch)
                        }
                    }
                    Failure.InvalidSession -> {
                        handleInvalidSession(context, sessionKey)
                        return
                    }
                    Failure.NoSession -> return
                    Failure.Retry -> {
                        Timber.tag(TAG).d(failure, "Scrobble batch deferred, retry in %d ms", retryDelayMs)
                        scheduleRetry()
                        return
                    }
                }
            }
        }
    }

    /**
     * Shared by now-playing and love calls so a revoked session is handled the same way whichever
     * request discovers it.
     */
    fun onRequestFailed(
        error: Throwable,
        sessionKeyUsed: String?,
    ) {
        val context = appContext ?: return
        if (error is LastFM.LastFmException && error.isApiError && error.code == ERROR_INVALID_SESSION && sessionKeyUsed != null) {
            scope.launch { handleInvalidSession(context, sessionKeyUsed) }
        }
    }

    private fun classify(error: Throwable): Failure {
        if (error !is LastFM.LastFmException) return Failure.Retry
        if (!error.isApiError) {
            return if (error.code == ERROR_INVALID_SESSION && error.httpStatus == null) Failure.NoSession else Failure.Retry
        }
        return when (error.code) {
            ERROR_INVALID_SESSION -> Failure.InvalidSession
            ERROR_INVALID_PARAMETERS, ERROR_INVALID_RESOURCE -> Failure.Drop
            // 11/16 service offline, 29 rate limited, and 4/10/13/26 (bad key, signature or
            // suspended key) are not the plays' fault: keep them until the service or the
            // configuration recovers.
            else -> Failure.Retry
        }
    }

    /**
     * Code 9 means the user revoked the app on last.fm (or the key was otherwise invalidated):
     * every later call fails the same way, so the session is cleared and settings fall back to
     * "sign in". Queued plays stay on disk and are sent after the same account signs in again.
     */
    private suspend fun handleInvalidSession(
        context: Context,
        sessionKeyUsed: String,
    ) {
        var cleared = false
        context.dataStore.edit { preferences ->
            // A newer login may have replaced the key while the failing request was in flight.
            if (preferences[LastFMSessionKey] == sessionKeyUsed) {
                preferences.remove(LastFMSessionKey)
                cleared = true
            }
        }
        if (cleared) {
            if (LastFM.sessionKey == sessionKeyUsed) LastFM.sessionKey = null
            Timber.tag(TAG).w("Last.fm rejected the session key (code 9); signed out")
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        retryJob =
            scope.launch {
                delay(delayMs)
                flush()
            }
    }

    private suspend fun remove(batch: List<PendingScrobble>) {
        stateMutex.withLock {
            val sent = batch.toHashSet()
            entries.removeAll { it in sent }
            persistLocked()
        }
    }

    private suspend fun currentAccount(context: Context): String {
        val username =
            context.dataStore.data
                .first()[LastFMUsernameKey]
                .orEmpty()
                .trim()
                .lowercase()
        return "${LastFM.currentConfig().endpoint}|$username"
    }

    private fun pruneExpiredLocked() {
        val cutoff = System.currentTimeMillis() / 1000 - MAX_AGE_SECONDS
        if (entries.removeAll { it.timestamp < cutoff }) persistLocked()
    }

    private fun queueFile(): AtomicFile? = appContext?.let { AtomicFile(File(it.filesDir, FILE_NAME)) }

    private fun ensureLoaded() {
        if (loaded) return
        val file = queueFile() ?: return
        loaded = true
        val restored =
            try {
                if (!file.baseFile.exists()) {
                    emptyList()
                } else {
                    json.decodeFromString<List<PendingScrobble>>(file.readFully().decodeToString())
                }
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Discarding unreadable scrobble queue")
                emptyList()
            }
        entries.clear()
        entries.addAll(restored)
        pending.value = entries.size
    }

    private fun persistLocked() {
        pending.value = entries.size
        val file = queueFile() ?: return
        val stream =
            try {
                file.startWrite()
            } catch (error: IOException) {
                Timber.tag(TAG).w(error, "Could not open scrobble queue for writing")
                return
            }
        try {
            stream.write(json.encodeToString(entries.toList()).encodeToByteArray())
            file.finishWrite(stream)
        } catch (error: IOException) {
            file.failWrite(stream)
            Timber.tag(TAG).w(error, "Could not persist scrobble queue")
        }
    }

    private fun registerNetworkCallback(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (pending.value > 0) {
                            retryDelayMs = MIN_RETRY_DELAY_MS
                            flushSoon()
                        }
                    }
                },
            )
        }.onFailure { Timber.tag(TAG).w(it, "Network callback unavailable; relying on timed retries") }
    }
}
