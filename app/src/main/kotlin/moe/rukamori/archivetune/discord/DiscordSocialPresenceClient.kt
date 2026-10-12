/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.discord

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object DiscordSocialPresenceClient {
    /** Presence goes over the Discord gateway with the linked account's OAuth token. */
    const val isAvailable = true

    private const val TAG = "DiscordSocialPresenceClient"
    private const val MAX_SEND_ATTEMPTS = 2

    // A gateway that Discord closes sooner than this after READY counts as unstable. Each
    // consecutive unstable close doubles the wait before the next connect, so a server that
    // keeps dropping the session right after READY cannot drive a tight reconnect loop.
    private const val STABLE_CONNECTION_MS = 60_000L
    private const val RECONNECT_BACKOFF_BASE_MS = 5_000L
    private const val RECONNECT_BACKOFF_MAX_MS = 5 * 60_000L

    // Past this many unstable closes in a row, a close no longer asks for a resync on its own;
    // only the next playback-driven update reconnects (still behind the backoff).
    private const val MAX_UNSTABLE_AUTO_RESYNCS = 5

    private val mutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var gateway: GatewayClient? = null

    @Volatile private var activeToken: String? = null

    @Volatile private var transportInvalidatedListener: ((String) -> Unit)? = null

    @Volatile private var connectedAtMs = 0L

    @Volatile private var unstableCloseCount = 0

    @Volatile private var reconnectNotBeforeMs = 0L

    @Volatile private var lastConnectedToken: String? = null

    val isStarted: Boolean
        get() = isConnectionUsable()

    /** Time left before the gateway may be reconnected after an unstable close; 0 when allowed. */
    fun reconnectBackoffRemainingMs(): Long = (reconnectNotBeforeMs - monotonicNowMs()).coerceAtLeast(0L)

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private fun isConnectionUsable(): Boolean {
        val currentGateway = gateway
        val currentToken = activeToken
        return currentGateway != null &&
            !currentToken.isNullOrBlank() &&
            currentGateway.isReady()
    }

    fun setOnTransportInvalidated(listener: ((String) -> Unit)?) {
        transportInvalidatedListener = listener
    }

    suspend fun updatePresence(
        accessToken: String,
        activity: DiscordPresenceActivity,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val token = accessToken.trim()
                if (token.isBlank()) {
                    return@withLock Result.failure(DiscordAuthorizationRequiredException())
                }

                val presenceJson = buildPresencePayload(token, activity)
                var lastError: Throwable? = null

                repeat(MAX_SEND_ATTEMPTS) { attempt ->
                    val connectResult = ensureConnected(token, force = attempt > 0)
                    if (connectResult.isFailure) return@withLock connectResult

                    val currentGateway = gateway
                    val sent =
                        if (currentGateway != null && currentGateway.isReady()) {
                            currentGateway.sendPresenceUpdate(presenceJson)
                        } else {
                            false
                        }

                    if (sent) {
                        if (attempt > 0) {
                            Timber.tag(TAG).i("updatePresence: sent after reconnect attempt=%d", attempt)
                        }
                        return@withLock Result.success(Unit)
                    }

                    lastError = Exception("Failed to send presence update (attempt=$attempt)")
                    Timber.tag(TAG).w("updatePresence: send failed, reconnecting attempt=%d", attempt)
                    tearDownLocked("presence_send_failed_$attempt")
                }

                Result.failure(lastError ?: Exception("Failed to send presence update"))
            }
        }

    suspend fun clearPresence(accessToken: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val token = accessToken?.trim().orEmpty()
                val empty =
                    JSONObject().apply {
                        put("activities", JSONArray())
                        put("afk", false)
                        put("since", JSONObject.NULL)
                        put("status", "online")
                    }

                var lastError: Throwable? = null
                repeat(MAX_SEND_ATTEMPTS) { attempt ->
                    val currentGateway = gateway
                    if (currentGateway == null || !currentGateway.isReady()) {
                        if (token.isBlank()) {
                            return@withLock Result.failure(Exception("Not connected"))
                        }
                        val connectResult = ensureConnected(token, force = currentGateway != null || attempt > 0)
                        if (connectResult.isFailure) return@withLock connectResult
                    }

                    val activeGateway = gateway
                    val sent =
                        if (activeGateway != null && activeGateway.isReady()) {
                            activeGateway.sendPresenceUpdate(empty)
                        } else {
                            false
                        }

                    if (sent) return@withLock Result.success(Unit)

                    lastError = Exception("Failed to clear presence (attempt=$attempt)")
                    Timber.tag(TAG).w("clearPresence: send failed, reconnecting attempt=%d", attempt)
                    tearDownLocked("clear_send_failed_$attempt")
                    if (token.isBlank()) return@withLock Result.failure(lastError!!)
                }

                Result.failure(lastError ?: Exception("Failed to clear presence"))
            }
        }

    suspend fun close(): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                tearDownLocked("close")
                DiscordAssetRegistrar.clearCache()
                Result.success(Unit)
            }
        }

    private suspend fun ensureConnected(
        token: String,
        force: Boolean = false,
    ): Result<Unit> {
        val currentGateway = gateway
        val currentToken = activeToken
        if (!force && currentToken == token && currentGateway != null && currentGateway.isReady()) {
            return Result.success(Unit)
        }

        // The backoff belongs to the token whose sessions kept closing; a relink starts clean.
        val backoffRemainingMs = reconnectBackoffRemainingMs()
        if (backoffRemainingMs > 0L && token == lastConnectedToken) {
            Timber.tag(TAG).d(
                "ensureConnected: backing off %dms after %d unstable closes",
                backoffRemainingMs,
                unstableCloseCount,
            )
            return Result.failure(DiscordReconnectBackoffException(backoffRemainingMs))
        }

        if (currentGateway != null) {
            Timber.tag(TAG).d(
                "ensureConnected: reconnecting force=%s tokenMatch=%s ready=%s",
                force,
                currentToken == token,
                currentGateway.isReady(),
            )
        }

        tearDownLocked(if (force) "ensure_force" else "ensure_reconnect")

        val newGateway = GatewayClient()
        attachCallbacks(newGateway)

        return try {
            newGateway.connect(token)
            if (!newGateway.isReady()) {
                throw IllegalStateException(
                    "Gateway connected but not ready (isConnected=${newGateway.isConnected()}, isReady=${newGateway.isReady()})",
                )
            }

            gateway = newGateway
            activeToken = token
            if (token != lastConnectedToken) {
                unstableCloseCount = 0
                reconnectNotBeforeMs = 0L
            }
            lastConnectedToken = token
            connectedAtMs = monotonicNowMs()
            Timber.tag(TAG).i("ensureConnected: connected")
            Result.success(Unit)
        } catch (e: CancellationException) {
            cleanNewGateway(newGateway)
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "ensureConnected failed")
            cleanNewGateway(newGateway)
            Result.failure(e)
        }
    }

    private fun attachCallbacks(newGateway: GatewayClient) {
        newGateway.onClose = { info ->
            Timber.tag(TAG).w(
                "gateway closed code=%d reason=%s resumable=%s",
                info.code,
                info.reason,
                info.resumable,
            )
            invalidateGatewayAsync(newGateway, "gateway_closed_${info.code}")
        }
        newGateway.onError = { error ->
            Timber.tag(TAG).w(error, "gateway error")
        }
        newGateway.onReady = {
            Timber.tag(TAG).d("gateway ready")
        }
    }

    private fun invalidateGatewayAsync(
        expectedGateway: GatewayClient,
        reason: String,
    ) {
        callbackScope.launch {
            var invalidated = false
            var shouldNotify = false
            mutex.withLock {
                if (gateway === expectedGateway) {
                    tearDownLocked(reason)
                    invalidated = true
                    shouldNotify = recordCloseLocked()
                }
            }
            if (invalidated && shouldNotify) {
                notifyTransportInvalidated(reason)
            } else if (invalidated) {
                Timber.tag(TAG).w(
                    "gateway unstable (%d closes in a row); waiting for the next playback update",
                    unstableCloseCount,
                )
            }
        }
    }

    // must be called with mutex held; returns whether the close may request a resync by itself
    private fun recordCloseLocked(): Boolean {
        val now = monotonicNowMs()
        if (now - connectedAtMs >= STABLE_CONNECTION_MS) {
            unstableCloseCount = 0
            reconnectNotBeforeMs = 0L
            return true
        }
        val count = unstableCloseCount + 1
        unstableCloseCount = count
        val backoffMs =
            (RECONNECT_BACKOFF_BASE_MS shl (count - 1).coerceAtMost(16))
                .coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
        reconnectNotBeforeMs = now + backoffMs
        return count <= MAX_UNSTABLE_AUTO_RESYNCS
    }

    private fun notifyTransportInvalidated(reason: String) {
        runCatching { transportInvalidatedListener?.invoke(reason) }
            .onFailure { error ->
                Timber.tag(TAG).w(error, "transport invalidation listener failed")
            }
    }

    private fun cleanNewGateway(newGateway: GatewayClient) {
        newGateway.onClose = null
        newGateway.onError = null
        newGateway.onReady = null
        newGateway.onDebug = null
        runCatching { newGateway.disconnect() }
    }

    // must be called with mutex held
    private fun tearDownLocked(reason: String) {
        val currentGateway = gateway
        if (currentGateway != null) {
            Timber.tag(TAG).d("tearDownLocked: %s", reason)
            currentGateway.onClose = null
            currentGateway.onError = null
            currentGateway.onReady = null
            currentGateway.onDebug = null
            runCatching { currentGateway.disconnect() }
        }
        gateway = null
        activeToken = null
    }

    private suspend fun buildPresencePayload(
        token: String,
        activity: DiscordPresenceActivity,
    ): JSONObject {
        val (resolvedLarge, resolvedSmall) =
            DiscordAssetRegistrar.resolveImages(
                accessToken = token,
                largeImage = activity.assets.largeImage,
                smallImage = activity.assets.smallImage,
            )

        val activityJson = JSONObject()

        activityJson.put("name", activity.name ?: "ArchiveTune")
        activityJson.put("type", activity.type.nativeValue)

        activity.details?.let { activityJson.put("details", it) }
        activity.state?.let { activityJson.put("state", it) }

        activityJson.put("application_id", activity.applicationId)

        val timestamps = JSONObject()
        activity.timestamps.startEpochSeconds?.let {
            timestamps.put("start", it * 1000L)
        }
        activity.timestamps.endEpochSeconds?.let {
            timestamps.put("end", it * 1000L)
        }
        if (timestamps.length() > 0) {
            activityJson.put("timestamps", timestamps)
        }

        val assets = JSONObject()
        resolvedLarge?.let { assets.put("large_image", it) }
        activity.assets.largeText?.let { assets.put("large_text", it) }
        resolvedSmall?.let { assets.put("small_image", it) }
        activity.assets.smallText?.let { assets.put("small_text", it) }
        if (assets.length() > 0) {
            activityJson.put("assets", assets)
        }

        if (activity.buttons.isNotEmpty()) {
            val buttonsArray = JSONArray()
            val metadataUrls = JSONArray()
            for (button in activity.buttons.take(2)) {
                buttonsArray.put(button.label)
                metadataUrls.put(button.url)
            }
            activityJson.put("buttons", buttonsArray)
            val metadata = JSONObject()
            metadata.put("button_urls", metadataUrls)
            activityJson.put("metadata", metadata)
        }

        activityJson.put(
            "platform",
            DiscordActivityPlatform.wireValueFromBit(activity.supportedPlatforms),
        )

        val activities = JSONArray()
        activities.put(activityJson)

        val payload = JSONObject()
        payload.put("activities", activities)
        payload.put("afk", false)
        payload.put("since", JSONObject.NULL)
        payload.put(
            "status",
            when (activity.onlineStatus) {
                DiscordOnlineStatus.Idle -> "idle"
                DiscordOnlineStatus.Dnd -> "dnd"
                else -> "online"
            },
        )

        return payload
    }
}

class DiscordAuthorizationRequiredException : IllegalStateException("Link and authorize a Discord account first")

class DiscordReconnectBackoffException(
    val retryAfterMs: Long,
) : IllegalStateException("Discord gateway reconnect deferred for ${retryAfterMs}ms after repeated closes")
