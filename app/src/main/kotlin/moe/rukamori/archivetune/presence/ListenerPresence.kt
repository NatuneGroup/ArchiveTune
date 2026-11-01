/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.utils.withAppProxy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/**
 * A separate DataStore file rather than the main settings store, and the backup rules exclude it:
 * the random listener ID never reaches Android backup or device transfer.
 */
private val Context.listenerPresenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "listener_presence",
)

private val enabledKey = booleanPreferencesKey(ListenerPresenceKeys.ENABLED)
private val listenerIdKey = stringPreferencesKey(ListenerPresenceKeys.LISTENER_ID)
private val consentVersionKey = intPreferencesKey(ListenerPresenceKeys.CONSENT_VERSION)

/**
 * The Android host for Natune status (opt-in listener presence). [start] runs one playback-driven
 * heartbeat loop for the process, and the playback service reports play state through
 * [onPlayingChanged]. Nothing is sent unless the user has opted in, the build carries an HTTPS
 * endpoint and the device is online — and nothing is ever retried: the next playback tick sends a
 * fresh payload instead.
 */
object ListenerPresence {

    private const val TAG = "ListenerPresence"
    private const val HEARTBEAT_PATH = "/api/heartbeat"
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val CALL_TIMEOUT_MS = 15_000L
    private const val HTTPS_PREFIX = "https://"
    private val JSON_MEDIA = "application/json".toMediaType()

    private val baseUrl: String = BuildConfig.LISTENER_PRESENCE_URL.trim().trimEnd('/')

    /** False when the build carries no HTTPS endpoint; the feature then stays silent. */
    val isConfigured: Boolean =
        baseUrl.startsWith(HTTPS_PREFIX) && baseUrl.length > HTTPS_PREFIX.length

    private val playing = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private var controllerJob: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // One call per beat, no silent transport retries and no redirect chasing: the
            // host either takes the frame or the beat is simply dropped.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** The opt-in as a flow: enabled flag, random identifier and recorded consent version. */
    fun state(context: Context): Flow<ListenerPresenceState> =
        context.applicationContext.listenerPresenceDataStore.data
            .map { it.toState() }
            .distinctUntilChanged()

    /** Opts in with a fresh random identifier. Does nothing while an opt-in is already current. */
    suspend fun enable(context: Context) {
        context.applicationContext.listenerPresenceDataStore.edit { prefs ->
            prefs.write(ListenerPresencePolicy.enable(prefs.toState(), ::newListenerId))
        }
    }

    /** Opts out and deletes the identifier; the loop sends one best-effort `stopped` and idles. */
    suspend fun disable(context: Context) {
        context.applicationContext.listenerPresenceDataStore.edit { prefs ->
            prefs.write(ListenerPresencePolicy.disable())
        }
    }

    /** Replaces the identifier while the opt-in is active. Does nothing otherwise. */
    suspend fun resetId(context: Context) {
        context.applicationContext.listenerPresenceDataStore.edit { prefs ->
            prefs.write(ListenerPresencePolicy.resetId(prefs.toState(), ::newListenerId))
        }
    }

    /** Starts the heartbeat loop once. Later calls do nothing. */
    fun start(context: Context) {
        synchronized(this) {
            if (controllerJob != null || !isConfigured) return
            val appContext = context.applicationContext
            val appVersion = ListenerPresencePolicy.sanitizedAppVersion(BuildConfig.VERSION_NAME)
            controllerJob =
                scope.launch {
                    combine(state(appContext), playing, onlineFlow(appContext)) { optIn, isPlaying, online ->
                        val id = optIn.listenerId
                        if (optIn.active && isPlaying && online && id != null &&
                            ListenerPresencePolicy.isValidListenerId(id)
                        ) {
                            id
                        } else {
                            null
                        }
                    }
                        .distinctUntilChanged()
                        .collectLatest { id -> if (id != null) beat(appContext, id, appVersion) }
                }
        }
    }

    /** Called from the playback service whenever the player's playing state changes. */
    fun onPlayingChanged(isPlaying: Boolean) {
        playing.value = isPlaying
    }

    /**
     * While [id] is the current key: one `playing` payload immediately, then one every
     * [ListenerPresencePolicy.HEARTBEAT_INTERVAL_MS] plus up to [HEARTBEAT_JITTER_MS][ListenerPresencePolicy.HEARTBEAT_JITTER_MS]
     * of uniform jitter so the fleet does not beat in lockstep. A pause, a stop, an opt-out, an
     * identifier change or lost connectivity cancels the loop and sends one best-effort `stopped`.
     */
    private suspend fun beat(
        context: Context,
        id: String,
        appVersion: String,
    ) {
        try {
            while (true) {
                sendQuietly(context, ListenerPresencePolicy.payloadJson(id, appVersion, ListenerPresencePolicy.STATE_PLAYING))
                delay(
                    ListenerPresencePolicy.HEARTBEAT_INTERVAL_MS +
                        Random.nextLong(ListenerPresencePolicy.HEARTBEAT_JITTER_MS + 1),
                )
            }
        } finally {
            withContext(NonCancellable) {
                sendQuietly(context, ListenerPresencePolicy.payloadJson(id, appVersion, ListenerPresencePolicy.STATE_STOPPED))
            }
        }
    }

    private suspend fun sendQuietly(context: Context, payloadJson: String) {
        try {
            send(context, payloadJson).onFailure {
                // Dropped on purpose: the next playback tick sends a fresh payload instead of retrying.
                Timber.tag(TAG).d(it, "heartbeat dropped")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.tag(TAG).d(failure, "heartbeat dropped")
        }
    }

    private suspend fun send(context: Context, payloadJson: String): Result<Unit> {
        if (!isOnline(context)) {
            return Result.failure(IllegalStateException("Listener presence skipped while offline"))
        }
        // enqueue + invokeOnCancellation keeps a beat cancellable like the rest of the loop; the
        // final "stopped" beat runs inside withContext(NonCancellable), where cancellation cannot
        // reach it and callTimeout bounds the whole call instead of a manual delay.
        return suspendCancellableCoroutine { cont ->
            val request =
                Request
                    .Builder()
                    .url(baseUrl + HEARTBEAT_PATH)
                    .header("Accept", "application/json")
                    // A ByteArray body keeps the Content-Type exactly "application/json" — the
                    // String overload would append "; charset=utf-8".
                    .post(payloadJson.encodeToByteArray().toRequestBody(JSON_MEDIA))
                    .build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result =
                            response.use {
                                if (it.isSuccessful) {
                                    Result.success(Unit)
                                } else {
                                    Result.failure(IllegalStateException("Listener presence rejected (HTTP ${it.code})"))
                                }
                            }
                        if (cont.isActive) cont.resume(result)
                    }
                },
            )
        }
    }

    /** Validated-internet check at send time: no heartbeat leaves while the device is offline. */
    private fun isOnline(context: Context): Boolean {
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Emits the current online state, then every change, so a reconnect beats immediately. */
    private fun onlineFlow(context: Context): Flow<Boolean> =
        callbackFlow {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager == null) {
                trySend(false)
                close()
                return@callbackFlow
            }
            trySend(isOnline(context))
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(isOnline(context))
                    }

                    override fun onLost(network: Network) {
                        trySend(isOnline(context))
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        trySend(isOnline(context))
                    }
                }
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
        }.distinctUntilChanged()

    private fun newListenerId(): String =
        ListenerPresencePolicy.newListenerId { count ->
            ByteArray(count).also { secureRandom.nextBytes(it) }
        }

    private fun Preferences.toState(): ListenerPresenceState =
        ListenerPresenceState(
            enabled = this[enabledKey] ?: false,
            listenerId = this[listenerIdKey],
            consentVersion = this[consentVersionKey] ?: 0,
        )

    private fun MutablePreferences.write(state: ListenerPresenceState) {
        this[enabledKey] = state.enabled
        val id = state.listenerId
        if (id == null) {
            remove(listenerIdKey)
        } else {
            this[listenerIdKey] = id
        }
        this[consentVersionKey] = state.consentVersion
    }
}
