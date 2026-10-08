/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Listen Together player bridge — ported from vivi-music (beta branch),
 * vivi-music's listentogether.ListenTogetherManager (GPL-3.0).
 * Adaptations for ArchiveTune (all marked with PORT-NOTE below):
 * - PlayerConnection exposes `player: Player` (the service's active player)
 * instead of vivi's `player: ExoPlayer`; every member used here exists on
 * the media3 Player interface, so no casts were needed.
 * - vivi's PlayerConnection carried Listen-Together hooks
 * (shouldBlockPlaybackChanges / allowInternalSync / onSkipPrevious /
 * onSkipNext / onRestartSong / setMuted/isMuted). ArchiveTune's
 * PlayerConnection has none of them yet, so the manager does not touch
 * them; guest playback gating is left to the integration phase.
 */



package moe.rukamori.archivetune.listentogether

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.constants.ListenTogetherAvatarIndexKey
import moe.rukamori.archivetune.constants.ListenTogetherChatHistoryKey
import moe.rukamori.archivetune.constants.ListenTogetherSmartResyncKey
import moe.rukamori.archivetune.constants.ListenTogetherSyncVolumeKey
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.MediaMetadata.Album
import moe.rukamori.archivetune.models.MediaMetadata.Artist
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager that bridges the Listen Together WebSocket client with the music player.
 * Handles syncing playback actions between connected users.
 */
@Singleton
class ListenTogetherManager @Inject constructor(
    private val client: ListenTogetherClient,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ListenTogetherManager"
        // Debounce threshold for playback syncs - prevents excessive seeking/pausing
        // Increased from 200ms to 1000ms to reduce choppy audio for guests
        private const val SYNC_DEBOUNCE_THRESHOLD_MS = 1000L
        // Position tolerance - only seek if difference exceeds this (prevents micro-adjustments)
        // Increased from 500ms to 2000ms to reduce unnecessary seeks that interrupt playback
        private const val POSITION_TOLERANCE_MS = 2000L
        // Large position tolerance - only seek during playback if difference exceeds this
        // This prevents interrupting active playback for small drifts
        private const val PLAYBACK_POSITION_TOLERANCE_MS = 3000L

        /** Typing indicators stay alive for this long after the last typing event. */
        private const val TYPING_TTL_MS = 4500L

        /** How often a typing client re-announces itself while composing. */
        private const val TYPING_THROTTLE_MS = 2500L

        /** Chat history persisted per local username (survives room switches). */
        private const val MAX_PERSISTED_CHAT_MESSAGES = 150
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        initialize()
        observePreferences()
    }

    private var playerConnection: PlayerConnection? = null
    private var playerFollowJob: Job? = null
    private var eventCollectorJob: Job? = null
    private var queueObserverJob: Job? = null
    private var volumeObserverJob: Job? = null
    private var playerListenerRegistered = false

    private val syncHostVolumeEnabled = MutableStateFlow(false)
    private val smartResyncEnabled = MutableStateFlow(true)
    private var lastSyncedVolume: Float? = null

    private var lastRole: RoomRole = RoomRole.NONE

    // Whether we're currently syncing (to prevent feedback loops)
    @Volatile
    private var isSyncing = false

    // Track the last state we synced to avoid duplicate events
    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSyncedTrackId: String? = null

    // Track last sync action time for debouncing (prevents excessive seeking/pausing)
    private var lastSyncActionTime: Long = 0L

    // Track ID being buffered
    private var bufferingTrackId: String? = null

    // Track active sync job to cancel it if a better update arrives
    private var activeSyncJob: Job? = null

    // Generation ID for track changes - incremented on each new track change
    // Used to prevent old coroutines from overwriting newer track loads
    private var currentTrackGeneration: Int = 0

    // Pending sync to apply after buffering completes for guest
    private var pendingSyncState: SyncStatePayload? = null

    // Track if a buffer-complete arrived before the pending sync was ready
    private var bufferCompleteReceivedForTrack: String? = null

    // Idle disconnect job to save resources
    private var idleDisconnectJob: Job? = null

    // Expose client state
    val connectionState = client.connectionState
    val roomState = client.roomState
    val role = client.role
    val userId = client.userId
    val pendingJoinRequests = client.pendingJoinRequests
    val bufferingUsers = client.bufferingUsers
    val logs = client.logs
    val events = client.events
    val blockedUsernames = client.blockedUsernames
    val pendingSuggestions = client.pendingSuggestions

    val isInRoom: Boolean get() = client.isInRoom
    val isHost: Boolean get() = client.isHost
    val hasPersistedSession: Boolean get() = client.hasPersistedSession

    // Chat state
    private val _chatMessages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())
    val chatMessages = _chatMessages

    /** Room members currently typing, freshest last; expires via [TYPING_TTL_MS]. */
    private val _typingUsers = MutableStateFlow<List<TypingUser>>(emptyList())
    val typingUsers: kotlinx.coroutines.flow.StateFlow<List<TypingUser>> = _typingUsers

    private var chatPersistJob: Job? = null
    private val chatHistoryJson = Json { ignoreUnknownKeys = true }
    private var lastTypingSentAt = 0L

    private fun hasOtherRoomMembers(): Boolean {
        val myId = userId.value ?: return false
        return (roomState.value?.users?.count { it.userId != myId } ?: 0) > 0
    }

    private val _unreadMessageCount = MutableStateFlow(0)
    val unreadMessageCount: kotlinx.coroutines.flow.StateFlow<Int> = _unreadMessageCount

    fun markChatAsRead() {
        _unreadMessageCount.value = 0
        client.cancelChatNotification()
    }

    /** Tells the client whether the chat screen is on top (suppresses its chat notifications). */
    fun setChatScreenVisible(visible: Boolean) {
        client.setChatScreenVisible(visible)
    }

    // PORT-NOTE: vivi's PlayerConnection exposed play()/pause() wrappers that routed
    // through Cast and enforced Listen-Together guest blocking (bypassed with
    // allowInternalSync during syncs). ArchiveTune's PlayerConnection exposes the raw
    // media3 Player, so the manager replicates vivi's local-player branch directly.
    private fun Player.playForSync() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            try {
                if (isSyncing || !isHost || !isInRoom) return

                val connection = playerConnection ?: return
                val player = connection.player

                Timber.tag(TAG).d("Play state changed: $playWhenReady (reason: $reason)")

                // ALWAYS ensure track is synced before play/pause
                val currentTrackId = player.currentMediaItem?.mediaId
                if (currentTrackId != null && currentTrackId != lastSyncedTrackId) {
                    Timber.tag(TAG)
                        .d("[SYNC] Sending track change before play state: track = $currentTrackId")
                    player.currentMetadata?.let { metadata ->
                        sendTrackChangeInternal(metadata)
                        lastSyncedTrackId = currentTrackId
                        // Reset play state since server resets IsPlaying on track change
                        lastSyncedIsPlaying = false
                    }
                    // ALWAYS send play state after track change if host is playing
                    // Server sets IsPlaying=false on track change, so we must send it
                    if (playWhenReady) {
                        Timber.tag(TAG).d("[SYNC] Host is playing, sending PLAY after track change")
                        lastSyncedIsPlaying = true
                        val position = player.currentPosition
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    }
                    return
                }

                // Only send play/pause if track is already synced
                sendPlayState(playWhenReady, player)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPlayWhenReadyChanged")
            }
        }

        private fun sendPlayState(playWhenReady: Boolean, player: Player) {
            try {
                val position = player.currentPosition

                if (playWhenReady) {
                    Timber.tag(TAG).d("Host sending PLAY at position $position")
                    client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    lastSyncedIsPlaying = true
                } else if (!playWhenReady && (lastSyncedIsPlaying == true)) {
                    Timber.tag(TAG).d("Host sending PAUSE at position $position")
                    client.sendPlaybackAction(PlaybackActions.PAUSE, position = position)
                    lastSyncedIsPlaying = false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in sendPlayState")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            try {
                if (isSyncing || !isInRoom) return
                if (mediaItem == null) return

                val connection = playerConnection ?: return
                val player = connection.player

                val trackId = mediaItem.mediaId

                if (!isHost) {
                    // Guests cannot broadcast playback actions — their local track
                    // change travels to the room as a suggestion instead, so the host
                    // (auto-)approves it and everyone, including the guest, syncs to it.
                    suggestLocalTrackChange(trackId, player)
                    return
                }

                if (trackId == lastSyncedTrackId) return

                lastSyncedTrackId = trackId
                // Reset play state tracking since server resets IsPlaying on track change
                lastSyncedIsPlaying = false

                // Get metadata and send track change
                player.currentMetadata?.let { metadata ->
                    Timber.tag(TAG).d("Host sending track change: ${metadata.title}")
                    sendTrackChange(metadata)

                    // ALWAYS send PLAY after track change if host is currently playing
                    // Server sets IsPlaying=false on track change, so we must re-send it
                    val isPlaying = player.playWhenReady
                    if (isPlaying) {
                        Timber.tag(TAG).d("Host is playing during track change, sending PLAY")
                        lastSyncedIsPlaying = true
                        val position = player.currentPosition
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onMediaItemTransition")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            try {
                if (isSyncing || !isHost || !isInRoom) return

                // Only send seek if it was a user-initiated seek
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    Timber.tag(TAG).d("Host sending SEEK to ${newPosition.positionMs}")
                    client.sendPlaybackAction(PlaybackActions.SEEK, position = newPosition.positionMs)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPositionDiscontinuity")
            }
        }
    }

    /**
     * Set the player connection for playback sync.
     * Should be called when PlayerConnection is available.
     */
    fun setPlayerConnection(connection: PlayerConnection?) {
        Timber.tag(TAG).d("setPlayerConnection: ${connection != null}, isInRoom: $isInRoom")

        try {
            // Remove old listener safely
            // PORT-NOTE: vivi also cleared PlayerConnection Listen-Together hooks here
            // (shouldBlockPlaybackChanges / onSkipPrevious / onSkipNext / onRestartSong).
            // ArchiveTune's PlayerConnection does not expose those hooks, so there is
            // nothing to detach; the guest playback gate is reintroduced during the
            // MusicService integration phase.
            val oldConnection = playerConnection
            if (playerListenerRegistered && oldConnection != null) {
                try {
                    oldConnection.player.removeListener(playerListener)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error removing old player listener")
                }
                playerListenerRegistered = false
            }

            playerConnection = connection
            followPlayerPromotions(connection)

            // PORT-NOTE: vivi registered `connection.shouldBlockPlaybackChanges = { isInRoom && !isHost }`
            // here so PlayerConnection could drop guest-initiated playback changes, plus
            // onSkipPrevious/onSkipNext/onRestartSong hooks that broadcast host skips.
            // ArchiveTune's PlayerConnection has no such hook surface; host skip/seek
            // broadcasting is still fully covered by [playerListener]'s
            // onMediaItemTransition / onPlayWhenReadyChanged / onPositionDiscontinuity.

            // Add listener if in room
            if (connection != null && isInRoom) {
                try {
                    connection.player.addListener(playerListener)
                    playerListenerRegistered = true
                    Timber.tag(TAG).d("Added player listener for room sync")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to add player listener")
                    playerListenerRegistered = false
                }
            }

            // Start/stop queue observation based on role
            if (connection != null && isInRoom && isHost) {
                startQueueSyncObservation()
                startHeartbeat()
                startVolumeSyncObservation()
            } else {
                stopQueueSyncObservation()
                stopHeartbeat()
                stopVolumeSyncObservation()
            }
            updateGuestMuteState()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in setPlayerConnection")
        }
    }

    private fun observePreferences() {
        scope.launch {
            context.dataStore.data
                .map { it[ListenTogetherSyncVolumeKey] ?: false }
                .distinctUntilChanged()
                .collect { enabled ->
                    syncHostVolumeEnabled.value = enabled
                }

            context.dataStore.data
                .map { it[ListenTogetherSmartResyncKey] ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    smartResyncEnabled.value = enabled
                }
        }
    }

    /**
    * Initialize event collection. Should be called once at app start.
     */
    fun initialize() {
        Timber.tag(TAG).d("Initializing ListenTogetherManager")
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            client.events.collect { event ->
                try {
                    Timber.tag(TAG).d("Received event: $event")
                    handleEvent(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling event: $event")
                }
            }
        }

        // Expire stale typing indicators.
        scope.launch {
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val fresh = _typingUsers.value.filter { it.expiresAt > now }
                if (fresh.size != _typingUsers.value.size) {
                    _typingUsers.value = fresh
                }
            }
        }

        // Idle Disconnect Timer (15 minutes) - saves resources if user connects but never joins
        scope.launch {
            combine(connectionState, client.roomState) { cState, rState ->
                cState to rState
            }.collect { (cState, rState) ->
                idleDisconnectJob?.cancel()
                if (cState == ConnectionState.CONNECTED && rState == null) {
                    idleDisconnectJob = scope.launch {
                        delay(15 * 60 * 1000L) // 15 minutes
                        Timber.tag(TAG).w("Idle disconnect timeout reached (15m without joining a room). Disconnecting...")
                        disconnect()
                    }
                }
            }
        }

        // Role change listener
        scope.launch {
            role.collect { newRole ->
                try {
                    val previousRole = lastRole
                    lastRole = newRole

                    val wasHost = previousRole == RoomRole.HOST
                    if (newRole == RoomRole.HOST && !wasHost) {
                        val connection = playerConnection
                        if (connection != null) {
                            Timber.tag(TAG).d("Role changed to HOST, starting sync services")
                            startQueueSyncObservation()
                            startHeartbeat()
                            startVolumeSyncObservation()
                            // Re-register listener if needed
                            if (!playerListenerRegistered) {
                                try {
                                    connection.player.addListener(playerListener)
                                    playerListenerRegistered = true
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Failed to add player listener on role change")
                                }
                            }
                        }
                    } else if (newRole != RoomRole.HOST && wasHost) {
                        Timber.tag(TAG).d("Role changed from HOST, stopping sync services")
                        stopQueueSyncObservation()
                        stopHeartbeat()
                        stopVolumeSyncObservation()
                    }

                    // Any in-room role needs the player listener: hosts broadcast
                    // playback actions from it, guests turn their local song
                    // changes into room-wide suggestions from it. It is attached
                    // at join time too, but a host-transfer that lands the local
                    // user in the GUEST role re-establishes it here.
                    if (newRole != RoomRole.NONE && newRole != RoomRole.HOST && !playerListenerRegistered) {
                        val connection = playerConnection
                        if (connection != null) {
                            try {
                                connection.player.addListener(playerListener)
                                playerListenerRegistered = true
                                Timber.tag(TAG).d("Added player listener as guest on role change")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to add player listener on role change")
                            }
                        }
                    }
                    updateGuestMuteState()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in role change handler")
                }
            }
        }
    }

    private fun handleEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.Connected -> {
                Timber.tag(TAG).d("Connected to server with userId: ${event.userId}")
            }

            is ListenTogetherEvent.RoomCreated -> {
                Timber.tag(TAG).d("Room created: ${event.roomCode}")
                try {
                    // Register player listener for host
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Added player listener as host")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to add player listener on room create")
                        }
                    }
                    // Initialize sync state
                    lastSyncedIsPlaying = player?.playWhenReady
                    lastSyncedTrackId = player?.currentMediaItem?.mediaId

                    // If there's already a track loaded, send it to the server
                    player?.currentMetadata?.let { metadata ->
                        Timber.tag(TAG).d("Room created with existing track: ${metadata.title}")
                        // Send track change so server has the current track info
                        sendTrackChangeInternal(metadata)
                        // If host is already playing, immediately send PLAY with current position
                        val isPlaying = player.playWhenReady
                        if (isPlaying) {
                            lastSyncedIsPlaying = true
                            val position = player.currentPosition
                            Timber.tag(TAG).d("Host already playing on room create, sending PLAY at $position")
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                        }
                    }
                    startQueueSyncObservation()
                    startHeartbeat()
                    startVolumeSyncObservation()
                    broadcastCustomAvatar()
                    // Deliberately NO chat-history restore here: the host is
                    // alone in a freshly created room, and older chats must not
                    // show while the room has a single member. They restore once
                    // someone joins (UserJoined below).
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling RoomCreated event")
                }
            }

            is ListenTogetherEvent.JoinApproved -> {
                Timber.tag(TAG).d("Join approved for room: ${event.roomCode}")

                // Guests need the player listener too: their local song changes
                // travel to the room as suggestions (suggestLocalTrackChange in
                // onMediaItemTransition), which requires observing transitions.
                // The listener is normally attached when the player connection
                // is set — but that happens long before joining a room, so it
                // must be attached here, mirroring the host's RoomCreated path.
                runCatching {
                    val connection = playerConnection
                    if (connection != null && !playerListenerRegistered) {
                        connection.player.addListener(playerListener)
                        playerListenerRegistered = true
                        Timber.tag(TAG).d("Added player listener as guest")
                    }
                }.onFailure {
                    Timber.tag(TAG).e(it, "Failed to add player listener on join")
                }

                saveMuteStateOnJoin()
                broadcastCustomAvatar()
                restorePersistedChatHistory(
                    otherMembers = event.state.users.filterNot { it.userId == userId.value },
                )

                applyPlaybackState(
                    currentTrack = event.state.currentTrack,
                    isPlaying = event.state.isPlaying,
                    position = event.state.position,
                    queue = event.state.queue
                    // bypassBuffer=false (default) for initial join buffer sync
                )
                applyHostVolumeIfNeeded(event.state.volume)
                updateGuestMuteState()
            }

            is ListenTogetherEvent.PlaybackSync -> {
                Timber.tag(TAG).d("PlaybackSync received: ${event.action.action}")
                // Guests handle all sync actions. Host should also apply queue ops.
                val actionType = event.action.action
                val isQueueOp = actionType == PlaybackActions.QUEUE_ADD ||
                        actionType == PlaybackActions.QUEUE_REMOVE ||
                        actionType == PlaybackActions.QUEUE_CLEAR
                if (!isHost || isQueueOp) {
                    handlePlaybackSync(event.action)
                }
            }

            is ListenTogetherEvent.UserJoined -> {
                Timber.tag(TAG).d("[SYNC] User joined: ${event.username}")
                // When a new user joins, host should send current track immediately

                // Re-share our custom avatar so the newcomer can see it too.
                broadcastCustomAvatar()

                // The newcomer's username is in roomState by the time the event
                // is emitted, so the restore below only brings back the older
                // chats with the people actually present — the newcomer's own
                // history with the local user included.
                restorePersistedChatHistory(
                    otherMembers = (roomState.value?.users ?: emptyList())
                        .filterNot { it.userId == userId.value },
                )

                if (isHost) {
                    try {
                        val connection = playerConnection
                        val player = connection?.player
                        player?.currentMetadata?.let { metadata ->
                            Timber.tag(TAG).d("[SYNC] Sending current track to newly joined user: ${metadata.title}")
                            sendTrackChangeInternal(metadata)
                            // If host is currently playing, also send PLAY with current position so the guest jumps to the live position
                            if (player.playWhenReady) {
                                val pos = player.currentPosition
                                Timber.tag(TAG).d("[SYNC] Host playing, sending PLAY at $pos for new joiner")
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                            }
                            // Don't send play state - let buffering complete first
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling UserJoined event")
                    }
                }
            }

            is ListenTogetherEvent.BufferWait -> {
                Timber.tag(TAG).d("BufferWait: waiting for ${event.waitingFor.size} users")
            }

            is ListenTogetherEvent.BufferComplete -> {
                Timber.tag(TAG).d("BufferComplete for track: ${event.trackId}")
                if (!isHost && bufferingTrackId == event.trackId) {
                    bufferCompleteReceivedForTrack = event.trackId
                    applyPendingSyncIfReady()
                }
            }

            is ListenTogetherEvent.SyncStateReceived -> {
                Timber.tag(TAG).d("SyncStateReceived: playing=${event.state.isPlaying}, pos=${event.state.position}, track=${event.state.currentTrack?.id}")
                if (!isHost) {
                    handleSyncState(event.state)
                }
            }

            is ListenTogetherEvent.Kicked -> {
                Timber.tag(TAG).d("Kicked from room: ${event.reason}")
                cleanup()
            }

            is ListenTogetherEvent.Disconnected -> {
                Timber.tag(TAG).d("Disconnected from server")
                // Don't cleanup on disconnect - we might reconnect
                // cleanup() is called when leaving room intentionally or when kicked
            }

            is ListenTogetherEvent.Reconnecting -> {
                Timber.tag(TAG).d("Reconnecting: attempt ${event.attempt}/${event.maxAttempts}")
            }

            is ListenTogetherEvent.Reconnected -> {
                Timber.tag(TAG).d("Reconnected to room: ${event.roomCode}, isHost: ${event.isHost}")
                restorePersistedChatHistory(
                    otherMembers = event.state.users.filterNot { it.userId == userId.value },
                )
                try {
                    // Re-register player listener
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Re-added player listener after reconnect")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to re-add player listener after reconnect")
                        }
                    }

                    // Sync state based on role
                    if (event.isHost) {
                        // Host: only send sync if necessary
                        lastSyncedIsPlaying = player?.playWhenReady
                        lastSyncedTrackId = player?.currentMediaItem?.mediaId

                        val currentMetadata = player?.currentMetadata
                        if (currentMetadata != null) {
                            // Check if server already has the right track (from event.state)
                            val serverTrackId = event.state.currentTrack?.id
                            if (serverTrackId != currentMetadata.id) {
                                Timber.tag(TAG).d("Reconnected as host, server track ($serverTrackId) differs from local (${currentMetadata.id}), syncing")
                                sendTrackChangeInternal(currentMetadata)
                            } else {
                                Timber.tag(TAG).d("Reconnected as host, server already has current track $serverTrackId")
                            }

                            // Small delay before sending play state to let connection stabilize
                            scope.launch {
                                delay(500)
                                try {
                                    val currentPlayer = playerConnection?.player
                                    if (currentPlayer?.playWhenReady == true) {
                                        val pos = currentPlayer.currentPosition
                                        Timber.tag(TAG)
                                            .d("Reconnected host is playing, sending PLAY at $pos")
                                        client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                                    }
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Error sending play state after reconnect")
                                }
                            }
                        }
                    } else {
                        // Guest: ALWAYS sync to host's state after reconnection
                        Timber.tag(TAG).d("Reconnected as guest, syncing to host's current state")
                        applyPlaybackState(
                            currentTrack = event.state.currentTrack,
                            isPlaying = event.state.isPlaying,
                            position = event.state.position,
                            queue = event.state.queue,
                            bypassBuffer = true  // Reconnect: bypass buffer protocol
                        )
                        applyHostVolumeIfNeeded(event.state.volume)

                        // Immediately request fresh sync after a short delay to catch live position
                        // but only if smart resync is enabled
                        scope.launch {
                            delay(1000)
                            if (isInRoom && !isHost && smartResyncEnabled.value) {
                                Timber.tag(TAG).d("Requesting fresh sync after reconnect (Smart Resync)")
                                requestSync()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling Reconnected event")
                }
            }

            is ListenTogetherEvent.UserReconnected -> {
                Timber.tag(TAG).d("User reconnected: ${event.username}")
                // No action needed - reconnected user already synced via reconnect state
            }

            is ListenTogetherEvent.UserDisconnected -> {
                Timber.tag(TAG).d("User temporarily disconnected: ${event.username}")
                // User might reconnect, no action needed
            }

            is ListenTogetherEvent.HostChanged -> {
                Timber.tag(TAG).d("Host changed: new host is ${event.newHostName} (${event.newHostId})")
                val wasHost = isHost
                val nowIsHost = event.newHostId == userId.value

                if (wasHost && !nowIsHost) {
                    // Lost host role
                    Timber.tag(TAG).d("Local user lost host role")
                    stopQueueSyncObservation()
                    stopVolumeSyncObservation()
                    if (playerListenerRegistered) {
                        playerConnection?.player?.removeListener(playerListener)
                        playerListenerRegistered = false
                    }
                    // Restore guest mute state since we're now a guest
                    updateGuestMuteState()
                } else if (!wasHost && nowIsHost) {
                    // Gained host role
                    Timber.tag(TAG).d("Local user gained host role")
                    updateGuestMuteState() // This will restore mute state since we're now host

                    // Register player listener
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Added player listener as new host")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to add player listener on host transfer")
                        }
                    }

                    // Start the queue and volume sync observations now that we're host
                    startQueueSyncObservation()
                    startVolumeSyncObservation()

                    // Send current player state to guests
                    val metadata = player?.currentMetadata
                    if (metadata != null) {
                        Timber.tag(TAG).d("New host sending current track: ${metadata.title}")
                        sendTrackChangeInternal(metadata)

                        // If currently playing, send play state
                        if (player.playWhenReady) {
                            val position = player.currentPosition
                            Timber.tag(TAG).d("New host is playing, sending PLAY at $position")
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                        }
                    }
                }
            }

            is ListenTogetherEvent.JoinRequestReceived -> {
                Timber.tag(TAG).d("Join request received from ${event.username}")
                // UI already handles this via pendingJoinRequests flow
            }

            is ListenTogetherEvent.LocalSuggestionApproved -> {
                applyApprovedSuggestion(event.payload.trackInfo, event.playImmediately)
            }

            is ListenTogetherEvent.SuggestionRejected -> {
                // Drop the one-shot dedup so the same track can be suggested again: the previous
                // attempt was explicitly turned down, not silently lost.
                lastSuggestedTrackId = null
            }

            is ListenTogetherEvent.ConnectionError -> {
                Timber.tag(TAG).e("Connection error: ${event.error}")
                cleanup()
            }

            is ListenTogetherEvent.ChatMessageReceived -> {
                Timber.tag(TAG).d("Chat message received from ${event.payload.username}")

                // The local user's own message that arrives while they are ALONE
                // in the room is a chat with themselves — flagged on the payload so
                // it never persists (the flag survives restore, unlike a volatile
                // key set, so restored solo messages can never re-enter the store).
                val payload =
                    if (event.payload.userId == userId.value && !hasOtherRoomMembers()) {
                        event.payload.copy(solo = true)
                    } else {
                        event.payload
                    }

                val exists = _chatMessages.value.any { it.timestamp == payload.timestamp && it.userId == payload.userId }
                if (!exists) {
                    _chatMessages.value = _chatMessages.value + payload
                    if (payload.userId != userId.value) {
                        _unreadMessageCount.value++
                    }
                    scheduleChatPersist()
                } else {
                    Timber.tag(TAG).w("Ignoring duplicate chat message from ${payload.username}")
                }
            }

            is ListenTogetherEvent.ChatControlReceived -> {
                applyChatControl(event.userId, event.username, event.event)
            }

            else -> { /* Other events handled by UI */ }
        }
    }

    private fun cleanup() {
        if (lastRole == RoomRole.GUEST) {
            restoreGuestMuteState()
        }
        if (playerListenerRegistered) {
            playerConnection?.player?.removeListener(playerListener)
            playerListenerRegistered = false
        }
        stopQueueSyncObservation()
        stopHeartbeat()
        stopVolumeSyncObservation()
        // Note: Don't clear shouldBlockPlaybackChanges callback - it checks isInRoom dynamically
        // (PORT-NOTE: that callback lived on vivi's PlayerConnection; ArchiveTune has no
        // equivalent hook yet, so there is nothing to clear here.)
        lastSyncedIsPlaying = null
        lastSyncedTrackId = null
        lastSuggestedTrackId = null
        bufferingTrackId = null
        isSyncing = false
        bufferCompleteReceivedForTrack = null
        lastRole = RoomRole.NONE
        lastSyncActionTime = 0L  // Reset sync debouncing
        ++currentTrackGeneration  // Increment to invalidate any pending track-change coroutines
        _chatMessages.value = emptyList() // Clear chat on room leave
        _unreadMessageCount.value = 0
        _typingUsers.value = emptyList()
    }

    // PORT-NOTE: vivi's PlayerConnection/MusicService carried a mute state
    // (isMuted/setMuted) that this manager saved on guest join and restored on
    // leave. ArchiveTune's player has no mute surface, and vivi's own
    // updateGuestMuteState had already degraded to "guests are never muted",
    // so these functions are retained as no-ops to keep the flow structure.

    private fun updateGuestMuteState() {
        // Guests are no longer forced to mute - they can hear the music too
        // (PORT-NOTE: vivi restored a saved mute state here; ArchiveTune's PlayerConnection
        // exposes no mute state, and vivi's own logic had already degraded to a no-op.)
        restoreGuestMuteState()
    }

    /**
     * Save the current mute state when joining a room as guest.
     * This allows us to restore it when leaving the room.
     */
    private fun saveMuteStateOnJoin() {
        // PORT-NOTE: no mute state on ArchiveTune's PlayerConnection - nothing to save.
    }

    /**
     * Restore the mute state that was saved when joining the room.
     * This is called when leaving the room to ensure the user's
     * mute preference is restored to what it was before joining Listen Together.
     */
    private fun restoreGuestMuteState() {
        // PORT-NOTE: no mute state on ArchiveTune's PlayerConnection - nothing to restore.
    }

    private fun applyHostVolumeIfNeeded(volume: Float?) {
        if (!syncHostVolumeEnabled.value || isHost || !isInRoom) return
        val connection = playerConnection ?: return
        val target = volume?.coerceIn(0f, 1f) ?: return
        connection.service.playerVolume.value = target
    }

    private fun applyPendingSyncIfReady() {
        val pending = pendingSyncState ?: return
        val pendingTrackId = pending.currentTrack?.id ?: bufferingTrackId ?: return
        val completeForTrack = bufferCompleteReceivedForTrack

        if (completeForTrack != pendingTrackId) return

        val connection = playerConnection ?: return
        val player = connection.player

        Timber.tag(TAG).d("Applying pending sync: track=$pendingTrackId, pos=${pending.position}, play=${pending.isPlaying}")
        isSyncing = true

        val targetPos = pending.position
        val posDiff = kotlin.math.abs(player.currentPosition - targetPos)
        val willPlay = pending.isPlaying

        // PORT-NOTE: vivi toggled connection.allowInternalSync around the seek/play/pause
        // to bypass its PlayerConnection guest gate. ArchiveTune has no such gate.

        // Use appropriate tolerance based on whether we're about to play
        val tolerance = if (willPlay && player.playWhenReady) PLAYBACK_POSITION_TOLERANCE_MS else POSITION_TOLERANCE_MS

        if (posDiff > tolerance) {
            Timber.tag(TAG).d("Applying pending sync: seeking ${player.currentPosition} -> $targetPos (diff ${posDiff}ms > ${tolerance}ms)")
            player.seekTo(targetPos)
        } else {
            Timber.tag(TAG).d("Applying pending sync: skipping seek (diff ${posDiff}ms < ${tolerance}ms)")
        }

        // Apply play/pause state only if it needs to change
        if (willPlay && !player.playWhenReady) {
            Timber.tag(TAG).d("Applying pending sync: starting playback")
            player.playForSync()
        } else if (!willPlay && player.playWhenReady) {
            Timber.tag(TAG).d("Applying pending sync: pausing playback")
            player.pause()
        }

        scope.launch {
            delay(200)
            isSyncing = false
        }

        bufferingTrackId = null
        pendingSyncState = null
        bufferCompleteReceivedForTrack = null
    }

    private fun handlePlaybackSync(action: PlaybackActionPayload) {
        val connection = playerConnection
        if (connection == null) {
            Timber.tag(TAG).w("Cannot sync playback - no player connection")
            return
        }
        val player = connection.player

        Timber.tag(TAG).d("Handling playback sync: ${action.action}, position: ${action.position}")

        isSyncing = true

        try {
            // PORT-NOTE: vivi set connection.allowInternalSync = true here to bypass its
            // PlayerConnection guest gate during remote syncs. ArchiveTune has no such gate.
            when (action.action) {
                PlaybackActions.PLAY -> {
                    val basePos = action.position ?: 0L
                    val now = System.currentTimeMillis()
                    val adjustedPos = action.serverTime?.let { serverTime ->
                        basePos + kotlin.math.max(0L, now - serverTime)
                    } ?: basePos

                    Timber.tag(TAG).d("Guest: PLAY at position $adjustedPos, currently playing=${player.playWhenReady}")

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = true,
                            position = adjustedPos,
                            lastUpdate = now
                        )).copy(
                            isPlaying = true,
                            position = adjustedPos,
                            lastUpdate = now
                        )
                        applyPendingSyncIfReady()
                        return
                    }

                    // Debounce PLAY actions when already playing and in sync
                    val posDiff = kotlin.math.abs(player.currentPosition - adjustedPos)
                    val alreadyPlaying = player.playWhenReady

                    if (alreadyPlaying && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PLAY debounced - already playing and in sync (diff ${posDiff}ms)")
                        return
                    }

                    // CRITICAL: Only seek during active playback if position is VERY far off
                    // This prevents interrupting the audio for small drifts
                    if (alreadyPlaying) {
                        if (posDiff > PLAYBACK_POSITION_TOLERANCE_MS) {
                            Timber.tag(TAG).d("Guest: PLAY seeking during playback ${player.currentPosition} -> $adjustedPos (diff ${posDiff}ms)")
                            player.seekTo(adjustedPos)
                        } else {
                            Timber.tag(TAG).d("Guest: PLAY skipping seek - already playing, drift acceptable (${posDiff}ms < ${PLAYBACK_POSITION_TOLERANCE_MS}ms)")
                        }
                    } else {
                        // When paused/stopped, we can seek more aggressively
                        if (posDiff > POSITION_TOLERANCE_MS) {
                            Timber.tag(TAG).d("Guest: PLAY seeking while paused ${player.currentPosition} -> $adjustedPos (diff ${posDiff}ms)")
                            player.seekTo(adjustedPos)
                        }
                        // Start playback
                        Timber.tag(TAG).d("Guest: Starting playback")
                        player.playForSync()
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.PAUSE -> {
                    val pos = action.position ?: 0L
                    val now = System.currentTimeMillis()

                    Timber.tag(TAG).d("Guest: PAUSE at position $pos, currently playing=${player.playWhenReady}")

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = false,
                            position = pos,
                            lastUpdate = now
                        )).copy(
                            isPlaying = false,
                            position = pos,
                            lastUpdate = now
                        )
                        applyPendingSyncIfReady()
                        return
                    }

                    // Debounce PAUSE actions when already paused and in sync
                    val posDiff = kotlin.math.abs(player.currentPosition - pos)
                    val alreadyPaused = !player.playWhenReady

                    if (alreadyPaused && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PAUSE debounced - already paused and in sync (diff ${posDiff}ms)")
                        return
                    }

                    // Pause playback first
                    if (player.playWhenReady) {
                        Timber.tag(TAG).d("Guest: Pausing playback")
                        player.pause()
                    }

                    // Only seek if position difference is significant
                    if (posDiff > POSITION_TOLERANCE_MS) {
                        Timber.tag(TAG).d("Guest: PAUSE seeking ${player.currentPosition} -> $pos (diff ${posDiff}ms)")
                        player.seekTo(pos)
                    } else {
                        Timber.tag(TAG).d("Guest: PAUSE skipping seek (diff ${posDiff}ms < ${POSITION_TOLERANCE_MS}ms)")
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.SEEK -> {
                    val pos = action.position ?: 0L
                    val now = System.currentTimeMillis()

                    // Debounce SEEK actions - don't seek if one just happened
                    if (now - lastSyncActionTime < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: SEEK debounced (only ${now - lastSyncActionTime}ms since last sync)")
                        return
                    }

                    // Use larger position tolerance
                    if (kotlin.math.abs(player.currentPosition - pos) > POSITION_TOLERANCE_MS) {
                        Timber.tag(TAG).d("Guest: SEEK to $pos from ${player.currentPosition} (diff > ${POSITION_TOLERANCE_MS}ms)")
                        player.seekTo(pos)
                        lastSyncActionTime = now
                    } else {
                        Timber.tag(TAG).d("Guest: SEEK ignored (position diff < ${POSITION_TOLERANCE_MS}ms)")
                    }
                }

                PlaybackActions.CHANGE_TRACK -> {
                    action.trackInfo?.let { track ->
                        Timber.tag(TAG).d("Guest: CHANGE_TRACK to ${track.title}, queue size=${action.queue?.size}")

                        // Reset sync debounce timer on track change - this is a fresh sync cycle
                        lastSyncActionTime = 0L
                        // The room moved on from whatever we last suggested, so the local one-shot
                        // dedup has to move on with it — otherwise picking the same song twice in a
                        // row never re-suggests it.
                        lastSuggestedTrackId = null

                        // If we have a queue, use it! This is the "smart" sync path.
                        if (action.queue != null && action.queue.isNotEmpty()) {
                            val queueTitle = action.queueTitle
                            applyPlaybackState(
                                currentTrack = track,
                                isPlaying = false, // Will be updated by subsequent PLAY or pending sync
                                position = 0,
                                queue = action.queue,
                                queueTitle = queueTitle
                            )
                        } else {
                            // Fallback to old behavior (network fetch) if no queue provided
                            bufferingTrackId = track.id
                            syncToTrack(track, false, 0)
                        }
                    }
                }

                PlaybackActions.SKIP_NEXT -> {
                    Timber.tag(TAG).d("Guest: SKIP_NEXT")
                    connection.seekToNext()
                }

                PlaybackActions.SKIP_PREV -> {
                    Timber.tag(TAG).d("Guest: SKIP_PREV")
                    connection.seekToPrevious()
                }

                PlaybackActions.QUEUE_ADD -> {
                    val track = action.trackInfo
                    if (track == null) {
                        Timber.tag(TAG).w("QUEUE_ADD missing trackInfo")
                    } else {
                        Timber.tag(TAG).d("Guest: QUEUE_ADD ${track.title}, insertNext=${action.insertNext == true}")
                        scope.launch(Dispatchers.IO) {
                            // Fetch MediaItem via YouTube metadata
                            YouTube.queue(listOf(track.id)).onSuccess { list ->
                                val mediaItem = list.firstOrNull()?.toMediaMetadata()?.copy(
                                    suggestedBy = track.suggestedBy
                                )?.toMediaItem()
                                if (mediaItem != null) {
                                    launch(Dispatchers.Main) {
                                        // PORT-NOTE: vivi toggled connection.allowInternalSync around
                                        // playNext/addToQueue to bypass the guest gate; ArchiveTune's
                                        // PlayerConnection queue methods have no gate.
                                        if (action.insertNext == true) {
                                            connection.playNext(mediaItem)
                                        } else {
                                            connection.addToQueue(mediaItem)
                                        }
                                    }
                                } else {
                                    Timber.tag(TAG).w("QUEUE_ADD failed to resolve media item for ${track.id}")
                                }
                            }.onFailure {
                                Timber.tag(TAG).e(it, "QUEUE_ADD metadata fetch failed")
                            }
                        }
                    }
                }

                PlaybackActions.QUEUE_REMOVE -> {
                    val removeId = action.trackId
                    if (removeId.isNullOrEmpty()) {
                        Timber.tag(TAG).w("QUEUE_REMOVE missing trackId")
                    } else {
                        // Find first queue item with matching mediaId after current index
                        val startIndex = player.currentMediaItemIndex + 1
                        var removeIndex = -1
                        val total = player.mediaItemCount
                        for (i in startIndex until total) {
                            val id = player.getMediaItemAt(i).mediaId
                            if (id == removeId) { removeIndex = i; break }
                        }
                        if (removeIndex >= 0) {
                            Timber.tag(TAG).d("Guest: QUEUE_REMOVE index=$removeIndex id=$removeId")
                            player.removeMediaItem(removeIndex)
                        } else {
                            Timber.tag(TAG).w("QUEUE_REMOVE id not found in queue: $removeId")
                        }
                    }
                }

                PlaybackActions.QUEUE_CLEAR -> {
                    val currentIndex = player.currentMediaItemIndex
                    val count = player.mediaItemCount
                    val itemsAfter = count - (currentIndex + 1)
                    if (itemsAfter > 0) {
                        Timber.tag(TAG).d("Guest: QUEUE_CLEAR removing $itemsAfter items after current")
                        player.removeMediaItems(currentIndex + 1, count - (currentIndex + 1))
                    }
                }

                PlaybackActions.SET_VOLUME -> {
                    applyHostVolumeIfNeeded(action.volume)
                }

                PlaybackActions.SYNC_QUEUE -> {
                    val queue = action.queue
                    val queueTitle = action.queueTitle
                    if (queue != null) {
                        Timber.tag(TAG).d("Guest: SYNC_QUEUE size=${queue.size}")
                        // Cancel any pending "smart" sync (e.g. YouTube radio fetch) in favor of this authoritative queue
                        activeSyncJob?.cancel()

                        scope.launch(Dispatchers.Main) {
                            if (playerConnection !== connection) return@launch
                            val player = connection.player

                            // Map TrackInfo to MediaItems
                            val mediaItems = queue.map { track ->
                                track.toMediaMetadata().toMediaItem()
                            }

                            // Try to find current track in new queue to preserve playback state
                            val currentId = player.currentMediaItem?.mediaId
                            var newIndex = -1
                            if (currentId != null) {
                                newIndex = mediaItems.indexOfFirst { it.mediaId == currentId }
                            }

                            val currentPos = player.currentPosition
                            val wasPlaying = player.isPlaying

                            if (newIndex != -1) {
                                player.setMediaItems(mediaItems, newIndex, currentPos)
                            } else {
                                player.setMediaItems(mediaItems)
                            }

                            // Restore playing state if needed
                            if (wasPlaying && !player.isPlaying) {
                                player.playForSync()
                            }

                            // Sync queue title
                            try {
                                connection.service.queueTitle = queueTitle
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to set queue title during SYNC_QUEUE")
                            }
                        }
                    }
                }
            }
        } finally {
            // PORT-NOTE: vivi reset connection.allowInternalSync here; no such flag exists
            // on ArchiveTune's PlayerConnection.
            // Minimal delay to prevent feedback loops
            scope.launch {
                delay(200)
                isSyncing = false
            }
        }
    }

    private fun handleSyncState(state: SyncStatePayload) {
        val now = System.currentTimeMillis()
        val adjustedPos = if (state.isPlaying) {
            state.position + kotlin.math.max(0L, now - state.lastUpdate)
        } else {
            state.position
        }

        Timber.tag(TAG).d("handleSyncState: playing=${state.isPlaying}, pos=${state.position} -> adj=$adjustedPos, track=${state.currentTrack?.id}")

        applyPlaybackState(
            currentTrack = state.currentTrack,
            isPlaying = state.isPlaying,
            position = adjustedPos,
            queue = state.queue,
            bypassBuffer = true  // Manual sync: bypass buffer
        )
        applyHostVolumeIfNeeded(state.volume)
    }

    private fun applyPlaybackState(
        currentTrack: TrackInfo?,
        isPlaying: Boolean,
        position: Long,
        queue: List<TrackInfo>?,
        queueTitle: String? = null,  // New param
        bypassBuffer: Boolean = false
    ) {
        val connection = playerConnection
        if (connection == null) {
            Timber.tag(TAG).w("Cannot apply playback state - no player")
            return
        }
        val player = connection.player

        Timber.tag(TAG).d("Applying playback state: track=${currentTrack?.id}, pos=$position, queue=${queue?.size}, bypassBuffer=$bypassBuffer")

        // Cancel any pending sync job
        activeSyncJob?.cancel()

        // If no track, just pause and clear/set queue
        if (currentTrack == null) {
            Timber.tag(TAG).d("No track in state, pausing")
            val generation = ++currentTrackGeneration
            scope.launch(Dispatchers.Main) {
                // Verify we're still on the same track generation (no newer track change arrived)
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration")
                    return@launch
                }

                if (playerConnection !== connection) return@launch
                isSyncing = true
                if (queue != null && queue.isNotEmpty()) {
                    val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }
                    player.setMediaItems(mediaItems)
                } else if (queue != null) {
                    player.clearMediaItems()
                }
                player.pause()
                try {
                    connection.service.queueTitle = queueTitle
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to set queue title for empty state")
                }
                isSyncing = false
            }
            return
        }

        bufferingTrackId = currentTrack.id
        val generation = ++currentTrackGeneration

        scope.launch(Dispatchers.Main) {
            // Verify we're still on the same track generation (no newer track change arrived)
            if (currentTrackGeneration != generation) {
                Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration (track ${currentTrack.id})")
                return@launch
            }

            if (playerConnection !== connection) return@launch
            isSyncing = true

            try {
                // Re-verify generation before applying media items (critical section)
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Stale generation detected before setMediaItems: $generation vs $currentTrackGeneration")
                    return@launch
                }

                // Apply queue/media (same)
                if (queue != null && queue.isNotEmpty()) {
                    val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }

                    // Find index of current track
                    var startIndex = mediaItems.indexOfFirst { it.mediaId == currentTrack.id }
                    if (startIndex == -1) {
                        Timber.tag(TAG).w("Current track ${currentTrack.id} not found in queue, defaulting to 0")
                        val singleItem = currentTrack.toMediaMetadata().toMediaItem()
                        // Prepend or fallback? Let's just play the track alone if not in queue
                        player.setMediaItems(listOf(singleItem), 0, position)
                    } else {
                        player.setMediaItems(mediaItems, startIndex, position)
                    }
                } else {
                    // No queue provided, fallback to loading just the track (or radio) via syncToTrack logic
                    // But we want to avoid double loading.
                    // If queue is null, we might be in a state where we should fetch radio?
                    // But here we assume authoritative state.
                    Timber.tag(TAG).d("No queue in state, loading single track")
                    // Construct single item
                    val item = currentTrack.toMediaMetadata().toMediaItem()
                    player.setMediaItems(listOf(item), 0, position)
                }

                player.seekTo(position)  // Always seek immediately to target pos

                // Sync queue title
                try {
                    connection.service.queueTitle = queueTitle ?: "Listen Together"
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to set queue title during applyPlaybackState")
                }

                if (bypassBuffer) {
                    // Manual sync/reconnect: apply play/pause immediately, no buffer protocol
                    Timber.tag(TAG).d("Bypass buffer: immediately applying play=$isPlaying at pos=$position")

                    // Wait for player to be ready before seek/play
                    var attempts = 0
                    while (player.playbackState != Player.STATE_READY && attempts < 100) {
                        delay(50)
                        attempts++
                    }
                    if (player.playbackState == Player.STATE_READY) {
                        Timber.tag(TAG).d("Player ready after ${attempts * 50}ms, seeking to $position")
                        player.seekTo(position)
                        if (isPlaying) {
                            player.playForSync()
                            Timber.tag(TAG).d("Bypass: PLAY issued")
                        } else {
                            player.pause()
                            Timber.tag(TAG).d("Bypass: PAUSE issued")
                        }
                    } else {
                        Timber.tag(TAG).w("Player not ready after 5s timeout during bypass sync")
                    }

                    // Clear sync state
                    pendingSyncState = null
                    bufferingTrackId = null
                    bufferCompleteReceivedForTrack = null
                } else {
                    // Normal sync: pause, store pending, send buffer_ready
                    player.pause()
                    pendingSyncState = SyncStatePayload(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        position = position,
                        lastUpdate = System.currentTimeMillis()
                    )
                    applyPendingSyncIfReady()
                    client.sendBufferReady(currentTrack.id)
                }

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error applying playback state")
            } finally {
                delay(200)
                isSyncing = false
            }
        }
    }

    private fun syncToTrack(track: TrackInfo, shouldPlay: Boolean, position: Long) {
        Timber.tag(TAG).d("syncToTrack: ${track.title}, play: $shouldPlay, pos: $position")

        // Track which buffer-complete we expect for this load
        bufferingTrackId = track.id
        val generation = currentTrackGeneration

        activeSyncJob?.cancel()
        activeSyncJob = scope.launch(Dispatchers.IO) {
            try {
                // Check if a newer track change arrived - skip this load if stale
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Skipping stale syncToTrack for ${track.id} (generation $generation vs $currentTrackGeneration)")
                    isSyncing = false
                    return@launch
                }

                // Use YouTube API to play the track by ID
                YouTube.queue(listOf(track.id)).onSuccess { queue ->
                    Timber.tag(TAG).d("Got queue for track ${track.id}")
                    launch(Dispatchers.Main) {
                        // Final generation check before applying changes
                        if (currentTrackGeneration != generation) {
                            Timber.tag(TAG).d("Skipping stale track application for ${track.id} (generation $generation vs $currentTrackGeneration)")
                            isSyncing = false
                            return@launch
                        }

                        val connection = playerConnection ?: run {
                            isSyncing = false
                            return@launch
                        }
                        if (playerConnection !== connection) {
                            isSyncing = false
                            return@launch
                        }
                        isSyncing = true
                        // PORT-NOTE: vivi set connection.allowInternalSync = true around playQueue to
                        // bypass the guest gate; ArchiveTune's PlayerConnection has no such gate.
                        connection.playQueue(
                            YouTubeQueue(
                                endpoint = WatchEndpoint(videoId = track.id),
                                preloadItem = queue.firstOrNull()?.toMediaMetadata()
                            )
                        )
                        try {
                            connection.service.queueTitle = "Listen Together" // Set default title
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to set queue title")
                        }

                        // Wait for player to be ready - monitor actual player state
                        var waitCount = 0
                        while (waitCount < 40) { // Max 2 seconds (40 * 50ms)
                            // Check generation again while waiting
                            if (currentTrackGeneration != generation) {
                                Timber.tag(TAG).d("Generation changed while waiting for player ready - aborting sync for ${track.id}")
                                isSyncing = false
                                return@launch
                            }
                            try {
                                val player = connection.player
                                if (player.playbackState == Player.STATE_READY) {
                                    Timber.tag(TAG).d("Player ready after ${waitCount * 50}ms")
                                    break
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Error checking player state")
                                break
                            }
                            delay(50)
                            waitCount++
                        }

                        // Do NOT seek here; defer the exact seek until after the server signals buffer-complete
                        // Ensure paused state before signaling ready
                        connection.player.pause()

                        // Store pending sync (guest will apply seek + play/pause after BufferComplete)
                        pendingSyncState = SyncStatePayload(
                            currentTrack = track,
                            isPlaying = shouldPlay,
                            position = position,
                            lastUpdate = System.currentTimeMillis()
                        )

                        // Apply immediately if buffer-complete already arrived
                        applyPendingSyncIfReady()

                        // Signal we're ready to play
                        client.sendBufferReady(track.id)
                        Timber.tag(TAG).d("Sent buffer ready for ${track.id}, pending sync stored: pos=$position, play=$shouldPlay")

                        // Minimal delay before accepting sync commands
                        delay(100)
                        isSyncing = false
                    }
                }.onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to load track ${track.id}")
                    isSyncing = false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error syncing to track")
                isSyncing = false
            }
        }
    }

    // Public API for host actions

    /**
     * Connect to the Listen Together server
     */
    fun connect() {
        Timber.tag(TAG).d("Connecting to server")
        client.connect()
    }

    /**
     * Drops the held connection when its service is destroyed. This singleton deliberately keeps
     * the connection across MainActivity's onStop (its player getter follows the live service, so
     * room sync keeps working in the background), but once the service itself is gone the
     * connection only pins the destroyed service and its released players until the next launch.
     */
    fun onMusicServiceDestroyed(service: moe.rukamori.archivetune.playback.MusicService) {
        if (playerConnection?.service === service) setPlayerConnection(null)
    }

    /**
     * Keeps [playerListener] on the service's live player. A crossfade or automix promotion
     * replaces the session player and releases the old one; the listener used to stay on the
     * released player, so after the first blend a host stopped broadcasting play/pause/seek/track
     * changes and a guest stopped suggesting songs, with nothing in the UI to show it.
     * [PlayerConnection] already follows promotions the same way for its own listener.
     */
    private fun followPlayerPromotions(connection: PlayerConnection?) {
        playerFollowJob?.cancel()
        playerFollowJob = null
        if (connection == null) return
        playerFollowJob =
            scope.launch {
                var previous: Player = connection.player
                connection.service.playerFlow.collect { newPlayer ->
                    if (newPlayer == null || newPlayer === previous) return@collect
                    if (playerListenerRegistered) {
                        runCatching { previous.removeListener(playerListener) }
                        runCatching {
                            // Remove first so a listener an event handler already put on the
                            // new player is never registered twice.
                            newPlayer.removeListener(playerListener)
                            newPlayer.addListener(playerListener)
                        }.onFailure { Timber.tag(TAG).e(it, "Failed to move player listener after promotion") }
                    }
                    previous = newPlayer
                }
            }
    }

    /**
     * Disconnect from the server
     */
    fun disconnect() {
        Timber.tag(TAG).d("Disconnecting from server")
        cleanup()
        client.disconnect()
    }

    /**
     * Create a new room
     */
    fun createRoom(username: String) {
        Timber.tag(TAG).d("Creating room with username: $username")
        client.createRoom(username)
    }

    /**
     * Join an existing room
     */
    fun joinRoom(roomCode: String, username: String) {
        Timber.tag(TAG).d("Joining room $roomCode as $username")
        client.joinRoom(roomCode, username)
    }

    /**
     * Leave the current room
     */
    fun leaveRoom() {
        Timber.tag(TAG).d("Leaving room")
        cleanup()
        client.leaveRoom()
    }

    /**
     * Approve a join request
     */
    fun approveJoin(userId: String) = client.approveJoin(userId)

    /**
     * Reject a join request
     */
    fun rejectJoin(userId: String, reason: String? = null) = client.rejectJoin(userId, reason)

    /**
     * Kick a user
     */
    fun kickUser(userId: String, reason: String? = null) = client.kickUser(userId, reason)

    /**
     * Block a user permanently (internal list)
     */
    fun blockUser(username: String) = client.blockUser(username)

    /**
     * Unblock a previously blocked user
     */
    fun unblockUser(username: String) = client.unblockUser(username)

    /**
     * Get all currently blocked usernames
     */
    fun getBlockedUsernames(): Set<String> = blockedUsernames.value

    /**
     * Transfer host role to another user
     */
    fun transferHost(newHostId: String) = client.transferHost(newHostId)

    /**
     * Send track change (host only) - called when host changes track
     */
    fun sendTrackChange(metadata: MediaMetadata) {
        if (!isHost || isSyncing) return
        sendTrackChangeInternal(metadata)
    }

    /**
     * Internal track change - bypasses isSyncing check for initial state sync
     */
    private fun sendTrackChangeInternal(metadata: MediaMetadata) {
        if (!isHost) return

        // Use a default duration of 3 minutes if duration is 0 or negative
        val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L

        val trackInfo = TrackInfo(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(", ") { it.name },
            album = metadata.album?.title,
            duration = durationMs,
            thumbnail = metadata.thumbnailUrl,
            suggestedBy = metadata.suggestedBy
        )

        Timber.tag(TAG).d("Sending track change: ${trackInfo.title}, duration: $durationMs")

        // Also grab current queue to send along with track change
        val currentQueue = try {
            playerConnection?.queueWindows?.value?.map { it.toTrackInfo() }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current queue")
            null
        }
        val currentTitle = try {
            playerConnection?.queueTitle?.value
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current title")
            null
        }

        client.sendPlaybackAction(
            PlaybackActions.CHANGE_TRACK,
            queueTitle = currentTitle,
            trackInfo = trackInfo,
            queue = currentQueue
        )
    }

    private fun startQueueSyncObservation() {
        if (queueObserverJob?.isActive == true) return

        Timber.tag(TAG).d("Starting queue sync observation")
        queueObserverJob = scope.launch {
            playerConnection?.queueWindows
                ?.map { windows ->
                    windows.map { it.toTrackInfo() }
                }
                ?.distinctUntilChanged()
                ?.collectLatest { tracks ->
                    if (!isHost || !isInRoom || isSyncing) return@collectLatest

                    delay(500) // Debounce rapid playlist manipulations

                    Timber.tag(TAG).d("Sending SYNC_QUEUE with ${tracks.size} items")
                    val queueTitle = try {
                        playerConnection?.queueTitle?.value
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to get queue title")
                        null
                    }
                    client.sendPlaybackAction(
                        PlaybackActions.SYNC_QUEUE,
                        queueTitle = queueTitle,
                        queue = tracks
                    )
                }
        }
    }

    private fun startVolumeSyncObservation() {
        if (volumeObserverJob?.isActive == true) return

        Timber.tag(TAG).d("Starting volume sync observation")
        volumeObserverJob = scope.launch {
            playerConnection?.service?.playerVolume
                ?.collectLatest { volume ->
                    if (!isHost || !isInRoom || !syncHostVolumeEnabled.value) return@collectLatest

                    val normalized = volume.coerceIn(0f, 1f)
                    val last = lastSyncedVolume
                    if (last != null && kotlin.math.abs(last - normalized) < 0.01f) return@collectLatest

                    lastSyncedVolume = normalized
                    client.sendPlaybackAction(PlaybackActions.SET_VOLUME, volume = normalized)
                }
        }
    }

    private fun stopVolumeSyncObservation() {
        volumeObserverJob?.cancel()
        volumeObserverJob = null
        lastSyncedVolume = null
    }

    private var lastSuggestedTrackId: String? = null

    /**
     * Shares the locally picked custom profile picture with the room (only when the
     * avatar index says we actually use one). Piggybacks on the chat relay.
     */
    fun broadcastCustomAvatar() {
        try {
            if (!isInRoom) return
            if (context.dataStore.get(ListenTogetherAvatarIndexKey, 0) != ListenTogetherAvatar.CUSTOM_AVATAR_INDEX) return
            val bytes = ListenTogetherAvatar.loadCustomAvatarBytes(context) ?: return
            scope.launch(Dispatchers.IO) {
                client.sendCustomAvatar(bytes)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error broadcasting custom avatar")
        }
    }

    /** Custom profile pictures received from room members, keyed by user id. */
    val customAvatars: kotlinx.coroutines.flow.StateFlow<Map<String, ByteArray>> get() = client.customAvatars

    /** Custom profile picture for a member: our own file for ourselves, the received broadcast otherwise. */
    fun customAvatarFor(userId: String?): ByteArray? {
        val selfId = this.userId.value
        val isSelf = userId == null || userId == selfId
        if (isSelf) {
            if (context.dataStore.get(ListenTogetherAvatarIndexKey, 0) != ListenTogetherAvatar.CUSTOM_AVATAR_INDEX) return null
            return ListenTogetherAvatar.loadCustomAvatarBytes(context)
        }
        return client.customAvatars.value[userId]
    }

    private fun suggestLocalTrackChange(trackId: String, player: Player) {
        try {
            if (trackId == lastSuggestedTrackId) return
            val roomTrackId = roomState.value?.currentTrack?.id
            if (trackId == roomTrackId) return

            val metadata = player.currentMetadata ?: return
            lastSuggestedTrackId = trackId
            val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
            val trackInfo =
                TrackInfo(
                    id = metadata.id,
                    title = metadata.title,
                    artist = metadata.artists.joinToString(", ") { it.name },
                    album = metadata.album?.title,
                    duration = durationMs,
                    thumbnail = metadata.thumbnailUrl,
                )
            Timber.tag(TAG).d("Guest track change sent as suggestion: ${metadata.title}")
            client.suggestTrack(trackInfo)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error suggesting local track change")
        }
    }

    private fun androidx.media3.common.Timeline.Window.toTrackInfo(): TrackInfo {
        val metadata = mediaItem.metadata ?: return TrackInfo("unknown", "Unknown", "Unknown", "", 0, "")
        val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
        return TrackInfo(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(", ") { it.name },
            album = metadata.album?.title,
            duration = durationMs,
            thumbnail = metadata.thumbnailUrl,
            suggestedBy = metadata.suggestedBy
        )
    }

    private fun stopQueueSyncObservation() {
        queueObserverJob?.cancel()
        queueObserverJob = null
    }

    private fun TrackInfo.toMediaMetadata(): MediaMetadata {
        return MediaMetadata(
            id = id,
            title = title,
            artists = listOf(Artist(id = "", name = artist)),
            album = if (album != null) Album(id = "", title = album) else null,
            duration = (duration / 1000).toInt(),
            thumbnailUrl = thumbnail,
            suggestedBy = suggestedBy
        )
    }

    /**
     * Request sync state from server (for guests to re-sync)
     * Call this when a guest presses play/pause to sync with host
     */
    fun requestSync() {
        if (!isInRoom || isHost) {
            Timber.tag(TAG).d("requestSync: not applicable (isInRoom=$isInRoom, isHost=$isHost)")
            return
        }
        Timber.tag(TAG).d("Requesting sync from server")
        client.requestSync()
    }

    /**
     * Clear logs
     */
    fun clearLogs() = client.clearLogs()

    /** The username this device joined/created the room with (chat bookkeeping). */
    val currentUsername: String? get() = client.currentUsername

    // Suggestions API

    /**
     * Suggest the given track to the host (guest only)
     */
    fun suggestTrack(track: TrackInfo) = client.suggestTrack(track)

    /**
     * The track the local player is on right now (host-side source for sharing the current song
     * into the chat when the room state's currentTrack is stale or absent).
     */
    fun currentLocalTrack(): TrackInfo? {
        if (!isInRoom) return null
        return try {
            val player = playerConnection?.player ?: return null
            val window =
                player.currentTimeline.getWindow(
                    player.currentMediaItemIndex,
                    androidx.media3.common.Timeline.Window(),
                )
            window.toTrackInfo()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read the current local track")
            null
        }
    }

    /**
     * Approve a suggestion (host only)
     */
    fun approveSuggestion(suggestionId: String) {
        if (!isHost) return
        // Send approval; server will insert-next and broadcast once
        client.approveSuggestion(suggestionId)
    }

    /**
     * Reject a suggestion (host only)
     */
    fun rejectSuggestion(suggestionId: String, reason: String? = null) = client.rejectSuggestion(suggestionId, reason)

    /**
     * Force reconnection to server (for manual recovery)
     */
    fun forceReconnect() {
        Timber.tag(TAG).d("Forcing reconnection")
        client.forceReconnect()
    }

    /**
     * Get persisted room code if available
     */
    fun getPersistedRoomCode(): String? = client.getPersistedRoomCode()

    /**
     * Get current session age
     */
    fun getSessionAge(): Long = client.getSessionAge()

    // Heartbeat timer
    private var heartbeatJob: Job? = null

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (heartbeatJob?.isActive == true && isInRoom && isHost) {
                delay(10000L) // 10 seconds (increased frequency from 15s to 10s)
                playerConnection?.player?.let { player ->
                    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                        val pos = player.currentPosition
                        Timber.tag(TAG).d("Host heartbeat: sending PLAY at pos $pos")
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                    }
                }
            }
        }
        Timber.tag(TAG).d("Host heartbeat started (10s interval)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.tag(TAG).d("Host heartbeat stopped")
    }

    /**
     * Applies an approved suggestion (or a locally shared song on the host) through the full
     * manual-skip path: playNext + prepareForManualSkip so an in-flight crossfade never keeps
     * streaming the previous song.
     */
    private fun applyApprovedSuggestion(trackInfo: TrackInfo, playImmediately: Boolean) {
        try {
            val connection = playerConnection
            if (connection == null) {
                Timber.tag(TAG).w("Cannot apply approved suggestion - no player connection")
                return
            }
            val mediaMetadata = trackInfo.toMediaMetadata()
            val mediaItem = mediaMetadata.toMediaItem()
            connection.playNext(mediaItem)
            if (playImmediately) {
                val player = connection.player
                val nextIndex = player.currentMediaItemIndex + 1
                if (nextIndex < player.mediaItemCount &&
                    player.getMediaItemAt(nextIndex).mediaId == mediaItem.mediaId
                ) {
                    // Full manual-skip semantics: an in-flight crossfade (or its
                    // pauseAtEnd handoff) would otherwise keep the OLD song's
                    // audio playing through the secondary player while the
                    // queue already moved on, so cancel it first — exactly what
                    // a tap on the skip button does.
                    //
                    // Force playback on: an auto-approved suggestion starts the
                    // room's FIRST song while the host is still idle, so
                    // restoring the pre-suggestion playWhenReady (false) left
                    // the room paused until someone tapped play — and the
                    // follow-up CHANGE_TRACK then paused the guests too.
                    runCatching { connection.service.prepareForManualSkip() }
                    player.seekToNext()
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    Timber.tag(TAG).w("Approved suggestion not adjacent after queue insert; leaving it queued")
                }
            }
            Timber.tag(TAG).d("Approved suggestion applied: ${mediaMetadata.title} (playImmediately=$playImmediately)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error applying approved suggestion")
        }
    }

    fun sendChatMessage(message: String, replyTo: RepliedMessage? = null) {
        if (message.isBlank()) return
        client.sendChatMessage(message, replyTo)
    }

    /**
     * Shares a song into the chat as a rich tappable card (sent as an [LTS:] envelope on the chat
     * relay, optionally with a caption).
     */
    fun shareTrackToChat(track: TrackInfo, caption: String = "") {
        client.sendChatMessage(caption.trim(), null, sharedTrack = track)
    }

    /**
     * Plays a song shared in the chat: the host applies it directly through the
     * approved-suggestion path; a guest suggests it (auto-approved by default) so the song starts
     * in the room for everyone.
     */
    fun playSharedTrack(track: TrackInfo) {
        if (!isInRoom) return
        if (isHost) {
            applyApprovedSuggestion(track, playImmediately = true)
        } else {
            suggestTrack(track)
        }
    }

    /**
     * Applies a reactions/edit/delete/pin/typing control event to the local chat
     * state. All applications are idempotent, so the sender can apply locally and
     * again through its own server echo without harm. Typing echoes from self are
     * ignored so the composer never shows their own indicator.
     */
    private fun applyChatControl(fromUserId: String, fromUsername: String, control: ChatControlEvent) {
        when (control.action) {
            ChatControlEvent.ACTION_TYPING -> {
                if (fromUserId == userId.value) return
                val now = System.currentTimeMillis()
                _typingUsers.value =
                    _typingUsers.value.filter { it.userId != fromUserId } +
                        TypingUser(userId = fromUserId, username = fromUsername, expiresAt = now + TYPING_TTL_MS)
            }

            ChatControlEvent.ACTION_REACT,
            ChatControlEvent.ACTION_UNREACT -> {
                val emoji = control.emoji ?: return
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    val current = message.reactions[emoji].orEmpty()
                    val updated =
                        if (control.action == ChatControlEvent.ACTION_REACT) {
                            if (current.contains(fromUsername)) current else current + fromUsername
                        } else {
                            current - fromUsername
                        }
                    val newReactions =
                        if (updated.isEmpty()) message.reactions - emoji
                        else message.reactions + (emoji to updated)
                    message.copy(reactions = newReactions)
                }
            }

            ChatControlEvent.ACTION_EDIT -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                if (targetUserId != fromUserId) return // only one's own messages
                val newText = control.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    message.copy(message = newText, edited = true)
                }
            }

            ChatControlEvent.ACTION_DELETE -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                // Authors delete their own messages; the room host may
                // additionally delete anyone's (moderation).
                val senderIsRoomHost = roomState.value?.hostId == fromUserId
                if (targetUserId != fromUserId && !senderIsRoomHost) return
                // Tombstone, not removal: everyone (and the persisted history)
                // keeps seeing that a message existed and was deleted.
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    message.copy(deleted = true, message = "", sharedTrack = null)
                }
            }

            ChatControlEvent.ACTION_PIN,
            ChatControlEvent.ACTION_UNPIN -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                val pinned = control.action == ChatControlEvent.ACTION_PIN
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    // The stamp orders the pinned carousel latest-pin-first;
                    // it is reset to zero when unpinned.
                    if (pinned) {
                        message.copy(pinned = true, pinnedAt = System.currentTimeMillis())
                    } else {
                        message.copy(pinned = false, pinnedAt = 0L)
                    }
                }
            }
        }
    }

    private inline fun updateChatMessage(
        targetUserId: String,
        targetTimestamp: Long,
        transform: (ChatMessagePayload) -> ChatMessagePayload,
    ) {
        val messages = _chatMessages.value
        val index = messages.indexOfFirst { it.userId == targetUserId && it.timestamp == targetTimestamp }
        if (index == -1) return
        val updated = messages.toMutableList()
        updated[index] = transform(updated[index])
        _chatMessages.value = updated
        scheduleChatPersist()
    }

    /** Toggles the local user's emoji reaction on a message, room-wide. */
    fun toggleReaction(message: ChatMessagePayload, emoji: String) {
        val me = client.currentUsername ?: return
        val action =
            if (message.reactions[emoji]?.contains(me) == true) ChatControlEvent.ACTION_UNREACT
            else ChatControlEvent.ACTION_REACT
        val control = ChatControlEvent(
            action = action,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
            emoji = emoji,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", me, control)
    }

    /** Pins or unpins a message for everyone in the room. */
    fun setPinned(message: ChatMessagePayload, pinned: Boolean) {
        val control = ChatControlEvent(
            action = if (pinned) ChatControlEvent.ACTION_PIN else ChatControlEvent.ACTION_UNPIN,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", client.currentUsername ?: "", control)
    }

    /** Edits one of the local user's own messages, room-wide. */
    fun editMessage(message: ChatMessagePayload, newText: String) {
        if (newText.isBlank() || message.userId != userId.value) return
        val control = ChatControlEvent(
            action = ChatControlEvent.ACTION_EDIT,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
            text = newText,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", client.currentUsername ?: "", control)
    }

    /** Deletes a message for everyone in the room. Members may delete their own
     * messages; the host may additionally delete anyone's (moderation). */
    fun deleteMessageForEveryone(message: ChatMessagePayload) {
        if (message.userId != userId.value && !isHost) return
        val control = ChatControlEvent(
            action = ChatControlEvent.ACTION_DELETE,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", client.currentUsername ?: "", control)
    }

    /** Hides a message from the local user's view only. Nothing is broadcast —
     * other members keep seeing the message — and the persisted history is
     * rewritten without it, so it does not come back on restore. */
    fun deleteMessageForMe(message: ChatMessagePayload) {
        val remaining =
            _chatMessages.value.filterNot {
                it.userId == message.userId && it.timestamp == message.timestamp
            }
        _chatMessages.value = remaining

        // The post-removal snapshot is written directly rather than through
        // the debounced persist: a delayed write would race the leave-room
        // wipe of the live list (which must never erase the stored
        // conversation). Removing the last kept message clears the store
        // instead of silently leaving it stale.
        chatPersistJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val username = client.currentUsername ?: return@launch
            val trimmed = remaining.takeLast(MAX_PERSISTED_CHAT_MESSAGES).filterNot { it.solo }
            runCatching {
                context.dataStore.edit { prefs ->
                    if (trimmed.isEmpty()) {
                        prefs.remove(ListenTogetherChatHistoryKey)
                    } else {
                        prefs[ListenTogetherChatHistoryKey] =
                            chatHistoryJson.encodeToString(
                                PersistedChatHistory.serializer(),
                                PersistedChatHistory(username = username, messages = trimmed),
                            )
                    }
                }
            }.onFailure { Timber.tag(TAG).e(it, "Failed to persist chat history") }
        }
    }

    /** Re-announces that the local user is composing, throttled to one frame per 2.5s. */
    fun notifyTyping() {
        if (!isInRoom) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < TYPING_THROTTLE_MS) return
        lastTypingSentAt = now
        client.sendChatControl(ChatControlEvent(action = ChatControlEvent.ACTION_TYPING))
    }

    /** Debounced persistence of the chat list, keyed by the local username. Only
     * messages exchanged with other members are written — messages flagged
     * [ChatMessagePayload.solo] (the user talking to an empty room) are
     * deliberately dropped. When nothing worth keeping remains, the stored
     * history is CLEARED instead of silently left stale. */
    private fun scheduleChatPersist() {
        chatPersistJob?.cancel()
        chatPersistJob = scope.launch(Dispatchers.IO) {
            delay(600)
            val username = client.currentUsername ?: return@launch
            // Leaving the room wipes the live list before this debounced job
            // runs — that must never erase a previously stored conversation.
            if (_chatMessages.value.isEmpty()) return@launch
            val trimmed =
                _chatMessages.value
                    .takeLast(MAX_PERSISTED_CHAT_MESSAGES)
                    .filterNot { it.solo }
            runCatching {
                context.dataStore.edit { prefs ->
                    if (trimmed.isEmpty()) {
                        prefs.remove(ListenTogetherChatHistoryKey)
                    } else {
                        prefs[ListenTogetherChatHistoryKey] =
                            chatHistoryJson.encodeToString(
                                PersistedChatHistory.serializer(),
                                PersistedChatHistory(username = username, messages = trimmed),
                            )
                    }
                }
            }.onFailure { Timber.tag(TAG).e(it, "Failed to persist chat history") }
        }
    }

    /**
     * Restores the per-username chat history, scoped to the members that are
     * actually in the room right now:
     *  - nothing restores while the local user is alone (an empty room has no
     *    conversation to continue), and
     *  - only messages between the local user and the given members come back —
     *    history with people who are not present stays hidden until they join.
     *
     * Restored messages carry [ChatMessagePayload.restored] so the chat list can
     * draw the "older messages" divider where history ends, and their user ids
     * are remapped to the CURRENT session ids, so everything keyed by user id
     * downstream — own-message alignment (right side), avatar lookup, reactions,
     * pins, edits and deletes — works on them exactly as on live messages.
     *
     * Histories written by an older persistence scheme (before solo messages
     * were flagged) are discarded once — they may contain the user's alone-room
     * chatter, which must never come back.
     */
    private fun restorePersistedChatHistory(otherMembers: List<UserInfo>) {
        if (otherMembers.isEmpty()) return
        val username = client.currentUsername
        if (username.isNullOrBlank()) return
        val myId = userId.value
        val memberIdByName = otherMembers.associate { it.username to it.userId }
        scope.launch(Dispatchers.IO) {
            val raw = runCatching { context.dataStore.data.first()[ListenTogetherChatHistoryKey] }.getOrNull()
                ?: return@launch
            val stored = runCatching {
                chatHistoryJson.decodeFromString(PersistedChatHistory.serializer(), raw)
            }.getOrNull() ?: return@launch
            if (stored.username != username) return@launch
            if (stored.version != PersistedChatHistory.CURRENT_VERSION) {
                Timber.tag(TAG).d("Discarding chat history from an older persistence scheme")
                runCatching {
                    context.dataStore.edit { it.remove(ListenTogetherChatHistoryKey) }
                }
                return@launch
            }

            val memberNames = otherMembers.map { it.username }.toSet()
            val restored = stored.messages
                .filter { it.username == username || it.username in memberNames }
                .map { message ->
                    val currentId =
                        if (message.username == username) myId
                        else memberIdByName[message.username]
                    if (currentId != null && currentId != message.userId) {
                        message.copy(userId = currentId, restored = true)
                    } else {
                        message.copy(restored = true)
                    }
                }
            if (restored.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                val existing = _chatMessages.value
                val existingKeys = existing.map { it.username to it.timestamp }.toHashSet()
                val fresh = restored.filterNot { (it.username to it.timestamp) in existingKeys }
                if (fresh.isEmpty()) return@withContext
                // Timestamp-ordered merge: restored history is older than the
                // live session's messages, but a mid-session restore (someone
                // joins after the local user already chatted) must still land in
                // the right position, not blindly on top.
                _chatMessages.value = (fresh + existing).sortedBy { it.timestamp }
                Timber.tag(TAG)
                    .d("Restored ${fresh.size} chat messages for $username with ${otherMembers.map { it.username }}")
            }
        }
    }
}

/** A room member that is currently typing; expires after [ListenTogetherManager]'s typing TTL. */
@kotlinx.serialization.Serializable
data class TypingUser(
    val userId: String,
    val username: String,
    val expiresAt: Long,
)

/** Locally persisted chat history, keyed by the username that produced it.
 * [version] gates the persistence scheme: bump it whenever the message filter
 * semantics change, so histories written by older builds (which may contain
 * entries the new rules would never store) are discarded instead of restored. */
@kotlinx.serialization.Serializable
data class PersistedChatHistory(
    val version: Int = CURRENT_VERSION,
    val username: String,
    val messages: List<ChatMessagePayload>,
) {
    companion object {
        /** 1: unfiltered history. 2: solo messages never persisted. */
        const val CURRENT_VERSION = 2
    }
}
