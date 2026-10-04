/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.extensions.getCurrentQueueIndex
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.MusicService.MusicBinder
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.player.refetchCanvasArtworkForPlayback
import moe.rukamori.archivetune.telegram.TelegramClient
import moe.rukamori.archivetune.telegram.TelegramMediaId
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.defaultStorefront
import moe.rukamori.archivetune.utils.isLocalMediaId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.utils.reportException

internal data class CanvasArtworkUpdate(
    val mediaId: String,
    val artwork: CanvasArtwork,
)

internal enum class CanvasArtworkRefetchResult {
    Success,
    Failure,
    AlreadyRunning,
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    context: Context,
    binder: MusicBinder,
    val database: MusicDatabase,
    scope: CoroutineScope,
) : Player.Listener {
    val service = binder.service

    /**
     * Everything this connection launches runs here, a child of the caller's scope that [dispose]
     * cancels. MainActivity hands in its lifecycleScope and builds a new connection on every
     * onStart, so work launched straight into that scope outlived the disposed connection until
     * the activity itself was destroyed: each background/foreground cycle left one more
     * playerFlow collector behind, pinning the old connection and the service, and re-adding the
     * disposed connection as a player listener on the next crossfade promotion.
     */
    private val connectionScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    @Volatile
    private var isDisposed = false

    /**
     * Always the CURRENT active player. The service may promote a new player instance
     * (crossfade promotion), so this must be a live getter, not a captured reference.
     */
    val player: Player
        get() = service.player
    val localPlayer: ExoPlayer
        get() = service.localPlayer

    private var attachedPlayer: Player? = null

    val playbackState = MutableStateFlow(player.playbackState)
    private val playWhenReady = MutableStateFlow(player.playWhenReady)
    val playbackParameters = MutableStateFlow(player.playbackParameters)
    val isPlaying =
        combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
            playWhenReady && playbackState != STATE_ENDED
        }.stateIn(
            connectionScope,
            SharingStarted.Lazily,
            player.playWhenReady && player.playbackState != STATE_ENDED,
        )

    /** Media3's own definition: ready, wanted, and not suppressed. */
    val isAudioAdvancing = MutableStateFlow(player.isPlaying)
    val mediaMetadata = service.currentMediaMetadata
    val currentSong =
        mediaMetadata.flatMapLatest {
            database.song(it?.id)
        }
    val currentLyrics =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.lyrics(mediaMetadata?.id)
        }
    val currentFormat =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    val queueTitle = MutableStateFlow<String?>(null)
    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())
    val currentMediaItemIndex = MutableStateFlow(-1)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val aodModeEnabled = MutableStateFlow(false)

    val error = MutableStateFlow<PlaybackException?>(null)
    private var dismissedPlaybackError: PlaybackException? = null
    val waitingForNetworkConnection = service.waitingForNetworkConnection
    val queueRestoreCompleted = service.queueRestoreCompleted

    private val canvasArtworkRefetchMutex = Mutex()
    private val _isCanvasArtworkRefetching = MutableStateFlow(false)
    internal val isCanvasArtworkRefetching = _isCanvasArtworkRefetching.asStateFlow()
    private val _canvasArtworkUpdates = MutableSharedFlow<CanvasArtworkUpdate>(extraBufferCapacity = 1)
    internal val canvasArtworkUpdates = _canvasArtworkUpdates.asSharedFlow()

    private var metadataExtractionJob: Job? = null

    init {
        attachToPlayer(service.player)

        queueTitle.value = service.queueTitle
        if (player.mediaItemCount > 0 && service.currentMediaMetadata.value == null) {
            service.currentMediaMetadata.value = player.currentMetadata
        }

        // Follow player promotions (e.g. crossfade) and re-attach to the new active player.
        connectionScope.launch {
            service.playerFlow.collect { newPlayer ->
                if (!isDisposed && newPlayer != null && newPlayer !== attachedPlayer) {
                    attachToPlayer(newPlayer)
                }
            }
        }

        metadataExtractionJob =
            connectionScope.launch(Dispatchers.IO) {
                mediaMetadata
                    .distinctUntilChangedBy { it?.id }
                    .collectLatest { metadata ->
                        val mediaId = metadata?.id ?: return@collectLatest
                        if (mediaId.isLocalMediaId()) {
                            val storedFormat = database.format(mediaId).first()
                            if (storedFormat != null && storedFormat.bitrate == 0 && storedFormat.sampleRate == null) {
                                val result =
                                    extractLocalAudioProperties(context, mediaId)
                                        ?: return@collectLatest
                                ensureActive()
                                val finalBitrate =
                                    if (result.first <= 0 && result.second == null) {
                                        -1
                                    } else {
                                        result.first
                                    }
                                database.updateLocalAudioMetadata(mediaId, finalBitrate, result.second)
                            }
                        } else if (mediaId.isTelegramMediaId()) {
                            refineTelegramFormat(mediaId)
                        }
                    }
            }
    }

    /**
     * Refines a Telegram track's format row (seeded at enqueue with size + container + an average
     * bitrate) by pulling the real sample rate — and a more accurate bitrate when the container
     * reports one — from the downloaded audio header. Polls briefly for the header to arrive as the
     * stream buffers; if the sample rate is already known it does nothing.
     */
    private suspend fun refineTelegramFormat(mediaId: String) {
        val existing = database.format(mediaId).first() ?: return
        if (existing.sampleRate != null) return
        val decoded = TelegramMediaId.decode(mediaId) ?: return
        repeat(TELEGRAM_FORMAT_REFINE_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val path = TelegramClient.readyFilePath(decoded.fileId)
            if (path != null) {
                val result = extractAudioPropertiesFromPath(path)
                if (result != null && (result.second != null || result.first > 0)) {
                    currentCoroutineContext().ensureActive()
                    val current = database.format(mediaId).first() ?: existing
                    database.query {
                        upsert(
                            current.copy(
                                bitrate = if (result.first > 0) result.first else current.bitrate,
                                sampleRate = result.second ?: current.sampleRate,
                            ),
                        )
                    }
                    return
                }
            }
            delay(TELEGRAM_FORMAT_REFINE_INTERVAL_MS)
        }
    }

    private suspend fun extractAudioPropertiesFromPath(path: String): Pair<Int, Int?>? =
        withContext(Dispatchers.IO) {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(path)
                var bitrate = 0
                var sampleRate: Int? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        if (format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) {
                            bitrate = format.getInteger(android.media.MediaFormat.KEY_BIT_RATE)
                        }
                        if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        }
                        break
                    }
                }
                Pair(bitrate, sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                timber.log.Timber
                    .tag("TelegramMetadataExtractor")
                    .w(e, "Could not extract audio properties from %s", path)
                null
            } finally {
                runCatching { extractor.release() }
            }
        }

    private suspend fun extractLocalAudioProperties(
        context: Context,
        uriString: String,
    ): Pair<Int, Int?>? =
        withContext(Dispatchers.IO) {
            val extractor = android.media.MediaExtractor()
            var bitrate = 0
            var sampleRate: Int? = null
            try {
                val uri = android.net.Uri.parse(uriString)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    timber.log.Timber
                        .tag("LocalMetadataExtractor")
                        .w("Could not open file descriptor for %s", uriString)
                    return@withContext null
                }
                pfd.use { descriptor ->
                    extractor.setDataSource(descriptor.fileDescriptor)
                    if (extractor.trackCount == 0) {
                        return@withContext Pair(-1, null)
                    }
                    var foundAudioTrack = false
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            foundAudioTrack = true
                            if (format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) {
                                bitrate = format.getInteger(android.media.MediaFormat.KEY_BIT_RATE)
                            }
                            if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            }
                            break
                        }
                    }
                    if (!foundAudioTrack) {
                        return@withContext Pair(-1, null)
                    }
                }
                Pair(bitrate, sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                timber.log.Timber
                    .tag("LocalMetadataExtractor")
                    .w(e, "Permission denied extracting metadata for %s", uriString)
                null
            } catch (e: java.io.FileNotFoundException) {
                timber.log.Timber
                    .tag("LocalMetadataExtractor")
                    .w(e, "File not found for %s", uriString)
                null
            } catch (e: java.io.IOException) {
                val message = e.message?.lowercase() ?: ""
                if ("unsupported" in message || "malformed" in message || "invalid" in message || "failed to instantiate" in message) {
                    timber.log.Timber
                        .tag("LocalMetadataExtractor")
                        .w(e, "Confirmed unsupported file %s", uriString)
                    Pair(-1, null)
                } else {
                    timber.log.Timber
                        .tag("LocalMetadataExtractor")
                        .w(e, "Transient I/O error extracting metadata for %s", uriString)
                    null
                }
            } catch (e: Exception) {
                timber.log.Timber
                    .tag("LocalMetadataExtractor")
                    .w(e, "Unexpected error extracting metadata for %s", uriString)
                null
            } finally {
                runCatching { extractor.release() }
            }
        }

    private fun attachToPlayer(newPlayer: Player) {
        attachedPlayer?.removeListener(this)
        attachedPlayer = newPlayer
        newPlayer.addListener(this)

        playbackState.value = newPlayer.playbackState
        playWhenReady.value = newPlayer.playWhenReady
        isAudioAdvancing.value = newPlayer.isPlaying
        playbackParameters.value = newPlayer.playbackParameters
        queueWindows.value = newPlayer.getQueueWindows()
        currentWindowIndex.value = newPlayer.getCurrentQueueIndex()
        currentMediaItemIndex.value = newPlayer.currentMediaItemIndex
        shuffleModeEnabled.value = newPlayer.shuffleModeEnabled
        repeatMode.value = newPlayer.repeatMode
        updateCanSkipPreviousAndNext()
    }

    fun playQueue(queue: Queue) {
        service.playQueue(queue)
    }

    /**
     * Plays one shelf's [songs] as a queue starting at the tapped song, so Next walks that
     * shelf in order rather than starting a per-song radio.
     */
    fun playShelfFrom(songs: List<MediaItem>, tappedSongId: String, title: String) {
        val index = songs.indexOfFirst { song -> song.mediaId == tappedSongId }
        if (index < 0) return
        playQueue(ListQueue(title = title, items = songs, startIndex = index))
    }

    fun startRadioSeamlessly() {
        service.startRadioSeamlessly()
    }

    /**
     * Start radio from [seed]: seamless hand-off when it is already the playing
     * song, a fresh radio queue otherwise (from rukamori PR #1164).
     */
    fun startRadio(seed: MediaMetadata) {
        if (mediaMetadata.value?.id == seed.id) {
            startRadioSeamlessly()
        } else {
            playQueue(YouTubeQueue.radio(seed))
        }
    }

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun playNext(items: List<MediaItem>) {
        service.playNext(items)
    }

    fun moveQueueItemToNext(mediaItemIndex: Int) {
        service.moveQueueItemToNext(mediaItemIndex)
    }

    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))

    fun addToQueue(items: List<MediaItem>) {
        service.addToQueue(items)
    }

    fun playFromVoiceSearch(query: String) {
        service.playFromVoiceSearch(query)
    }

    fun toggleLike() {
        service.toggleLike()
    }

    internal suspend fun refetchCanvasArtwork(
        metadata: MediaMetadata,
        requireVertical: Boolean,
    ): CanvasArtworkRefetchResult {
        if (!canvasArtworkRefetchMutex.tryLock()) return CanvasArtworkRefetchResult.AlreadyRunning

        _isCanvasArtworkRefetching.value = true
        return try {
            val storefront = defaultStorefront()
            val artwork =
                refetchCanvasArtworkForPlayback(
                    mediaId = metadata.id,
                    songTitleRaw = metadata.title,
                    artistNameRaw = metadata.artists.firstOrNull()?.name.orEmpty(),
                    storefront = storefront,
                    requireVertical = requireVertical,
                    albumTitle = metadata.album?.title,
                ) ?: return CanvasArtworkRefetchResult.Failure

            _canvasArtworkUpdates.emit(
                CanvasArtworkUpdate(
                    mediaId = metadata.id,
                    artwork = artwork,
                ),
            )
            CanvasArtworkRefetchResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            timber.log.Timber.tag("CanvasArtwork").w(error, "Canvas refetch failed for %s", metadata.id)
            CanvasArtworkRefetchResult.Failure
        } finally {
            _isCanvasArtworkRefetching.value = false
            canvasArtworkRefetchMutex.unlock()
        }
    }

    fun dismissPlaybackError() {
        dismissedPlaybackError = error.value ?: player.playerError
        error.value = null
    }

    fun seekToNext() {
        service.prepareForManualSkip()
        player.seekToNext()
        player.prepare()
        player.playWhenReady = true
    }

    fun seekToPrevious() {
        service.prepareForManualSkip()
        player.seekToPrevious()
        player.prepare()
        player.playWhenReady = true
    }

    fun seekBy(deltaMs: Long) {
        if (deltaMs == 0L) return
        val activePlayer = player
        if (!activePlayer.isCurrentMediaItemSeekable) return
        activePlayer.seekTo(
            resolveSeekByTargetMs(
                currentPositionMs = activePlayer.currentPosition,
                deltaMs = deltaMs,
                durationMs = activePlayer.duration,
            ),
        )
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        updatePlaybackError(player.playerError)
    }

    override fun onIsPlayingChanged(playing: Boolean) {
        isAudioAdvancing.value = playing
    }

    override fun onPlayWhenReadyChanged(
        newPlayWhenReady: Boolean,
        reason: Int,
    ) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        this.playbackParameters.value = playbackParameters
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        queueWindows.value = player.getQueueWindows()
        queueTitle.value = service.queueTitle
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        updatePlaybackError(playbackError)
    }

    private fun updatePlaybackError(playbackError: PlaybackException?) {
        when {
            playbackError == null -> {
                dismissedPlaybackError = null
                error.value = null
            }

            // Suppress the error dialog for recoverable MediaCodec decoder-state faults.
            // MusicService.onPlayerError handles these silently by re-preparing the player,
            // and the song resumes playback automatically after recovery. Surfacing the
            // dialog mid-recovery is misleading UX — it flashes briefly then auto-dismisses
            // once recovery succeeds, which the user perceives as a spurious error popup.
            // We still log via reportException above (in onPlayerErrorChanged) for diagnostics.
            isRecoverableMediaCodecStateError(playbackError) -> {
                // Intentionally do NOT update error.value; let recovery run silently —
                // on success onPlayerErrorChanged(null) fires and resets error state.
            }

            playbackError !== dismissedPlaybackError -> {
                dismissedPlaybackError = null
                error.value = playbackError
            }
        }
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window =
                player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                !window.isLive ||
                player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive &&
                window.isDynamic ||
                player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        isDisposed = true
        attachedPlayer?.removeListener(this)
        attachedPlayer = null
        metadataExtractionJob?.cancel()
        metadataExtractionJob = null
        connectionScope.cancel()
    }

    private companion object {
        // How long to poll for a streamed Telegram track's header before giving up refining its
        // sample rate (attempts × interval ≈ 15s).
        const val TELEGRAM_FORMAT_REFINE_ATTEMPTS = 10
        const val TELEGRAM_FORMAT_REFINE_INTERVAL_MS = 1_500L
    }
}
