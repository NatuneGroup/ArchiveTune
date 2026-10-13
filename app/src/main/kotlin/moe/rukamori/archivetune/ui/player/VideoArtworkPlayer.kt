/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.ui.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.VideoPlaybackSpeedKey
import moe.rukamori.archivetune.constants.AutoChoosePlaybackClientKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.playback.stream.NewPipeStreamUrlExtractor
import moe.rukamori.archivetune.utils.ImageBlurUtils
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.extensions.toEnum
import okhttp3.OkHttpClient
import java.net.SocketTimeoutException
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.utils.withAppStreamProxy

// A/V sync tuning. The rationale for every threshold below is in docs/video-sync.md.

/** Below this, no correction: ITU-R BT.1359-1 puts it inside the imperceptible band. */
private const val VideoSyncIgnoreToleranceMs = 60L

/**
 * Drift above this threshold triggers a **soft seek** (a re-anchor) instead of speed-based
 * correction.
 */
private const val VideoSoftSeekDriftThresholdMs = 2000L

/** Maximum proportional speed adjustment applied for drift correction. */
private const val VideoSyncSpeedCorrectionFactorMax = 0.20f

/**
 * Time during which drift checks are suppressed after a seek (soft seek, manual resync, or the
 * initial-load snap in [onRenderedFirstFrame]).
 */
private const val VideoSeekSettlingTimeMs = 2000L

/** Minimum time between [VideoArtworkState.kickRenderer] micro-cycles. */
private const val SurfaceReanchorMinIntervalMs = 2000L

/**
 * Number of consecutive drift-poll cycles over which the video position must fail to advance (while
 * the audio position advances) before we conclude the renderer is frozen and fire a
 * [VideoArtworkState.kickRenderer] micro-cycle.
 */
private const val VideoFrozenRendererCycles = 3

/**
 * A video position is considered "not advancing" in the frozen-renderer
 * detector if it moved less than this many milliseconds over a poll cycle.
 * A renderer that is presenting frames advances its position by roughly
 * the poll interval each cycle; a frozen one barely moves at all.
 */
private const val VideoFrozenRendererMaxAdvanceMs = 50L

/**
 * Tight drift tolerance used ONLY for the initial sync in
 * [onRenderedFirstFrame]. When the video's first frame renders, we snap the
 * video to the audio's current position — but only if the drift exceeds this
 * threshold. 200ms is imperceptible to the user while avoiding a redundant
 * seekTo (which would trigger a brief re-buffer) when the video is already
 * close enough.
 */
private const val VideoInitialSyncToleranceMs = 200L

/**
 * Drift threshold above which we fire a HARD pause-load-resume resync even without an explicit
 * seekbar seek.
 */
private const val VideoHardResyncThresholdMs = 5000L

/**
 * Minimum time between automatic hard resyncs. If a hard resync fires and
 * another is requested within this window, the second request is dropped and
 * a "stuck" flag is set that disables automatic hard resync until the video
 * id changes.
 */
private const val VideoHardResyncCooldownMs = 30_000L

/**
 * How often to poll the main player's position and re-sync the video.
 *
 * 250ms lets the frozen-renderer detector respond within ~750ms (3 cycles) and keeps speed
 * correction responsive. The polling cost is negligible (a couple of reads every 250ms).
 */
private const val VideoSyncPollIntervalMs = 250L

/**
 * Maximum time the video ExoPlayer is allowed to stay in STATE_BUFFERING before we force a
 * re-prepare to break out of a stuck state.
 */
private const val VideoStuckBufferingTimeoutMs = 20000L

/** Cap on the resolution we attempt to play, derived from the user's quality choice. */
private fun maxVideoHeightFor(preferredHeight: Int?): Int = VideoQualityPreference.ceilingFor(preferredHeight)

/**
 * Maximum time the main audio player stays paused while we wait for the
 * video's first frame. After this the hold is released and playback falls
 * back to audio-only (with the artwork) so the user is never stuck in
 * silence if the video is slow to resolve or the network is degraded.
 */
private const val VideoReadyHoldTimeoutMs = 10000L
private const val VideoClientAttemptTimeoutMs = 8000L

/**
 * Delay between the video's first frame rendering and the actual resume of both audio and video
 * playback.
 */
private const val VideoLoadResumeDelayMs = 1000L

/**
 * Resolved information about a video stream — the playable URL plus the menu of formats YouTube
 * offered, so the user can pick a different quality after playback has started.
 */
data class VideoStreamInfo(
    val streamUrl: String,
    val availableHeights: List<Int>,
    val captionTracks: List<PlayerResponse.CaptionTrack>,
    val selectedHeight: Int? = null,
)

/** State holder for the video artwork player. */
@Stable
class VideoArtworkState internal constructor(
    val exoPlayer: ExoPlayer,
) {
    var streamUrl: String? by mutableStateOf(null)
        internal set
    var isVideoReady: Boolean by mutableStateOf(false)
        internal set
    var hasPlaybackFailed: Boolean by mutableStateOf(false)
        internal set
    var isChangingQuality: Boolean by mutableStateOf(false)
        internal set
    var wasPlayingBeforeQualityChange: Boolean by mutableStateOf(false)
        internal set
    var isResyncing: Boolean by mutableStateOf(false)
        internal set
    var wasPlayingBeforeResync: Boolean by mutableStateOf(false)
        internal set
    var isResolvingUrl: Boolean by mutableStateOf(true)
        internal set
    var bufferingStartedAtMs: Long by mutableLongStateOf(0L)
        internal set

    /**
     * Current speed-correction factor applied on top of the user's preferred playback speed
     * ([VideoPlaybackSpeedKey]) to gently bring the video back into sync with the main audio clock.
     */
    var currentSpeedCorrectionFactor by mutableStateOf(1.0f)
        internal set

    /**
     * Epoch-millis (from [SystemClock.elapsedRealtime]) of the last seek performed on the video
     * ExoPlayer — soft seek, manual resync, or the initial-load snap in onRenderedFirstFrame.
     */
    var lastSeekAtMs: Long by mutableLongStateOf(0L)
        internal set

    /**
     * Epoch-millis (from [SystemClock.elapsedRealtime]) of the last **renderer restart** — a
     * video-only pause/resume micro-cycle performed by [kickRenderer], either from
     * [Player.Listener.onRenderedFirstFrame] when the video surface is re-attached while already
     * playing (fullscreen toggle / orientation change) or by the drift poller's frozen-renderer
     * detector.
     */
    var lastSurfaceReanchorAtMs: Long by mutableLongStateOf(0L)
        internal set

    /**
     * Epoch-millis (from [SystemClock.elapsedRealtime]) at which the pending resume should fire.
     * Set by [Player.Listener.onRenderedFirstFrame] to `now + [VideoLoadResumeDelayMs]` when the
     * first frame renders.
     */
    var pendingResumeAtMs: Long by mutableLongStateOf(0L)
        internal set

    /**
     * Whether the pending resume (see [pendingResumeAtMs]) should call
     * `updatedOnRequestResumeMain()` to resume the main audio player.
     */
    var pendingResumeMainAudio: Boolean by mutableStateOf(false)
        internal set

    /**
     * Whether the pending resume (see [pendingResumeAtMs]) should
     * `exoPlayer.play()` the video ExoPlayer.
     *
     * Mirrors [pendingResumeMainAudio] but for the video side. Kept as a
     * separate flag in case a future code path wants to resume only one
     * of the two (currently they always resume together).
     */
    var pendingResumeVideo: Boolean by mutableStateOf(false)
        internal set

    /**
     * Caption tracks offered by YouTube for the current video, from the
     * resolved [VideoStreamInfo]. Empty when the video has no captions.
     */
    var captionTracks: List<PlayerResponse.CaptionTrack> by mutableStateOf(emptyList())
        internal set

    /**
     * The caption track the user has chosen to display. Null = captions off.
     * Selecting a non-null track re-loads the media item with that track's
     * WebVTT URL embedded as a subtitle configuration.
     */
    var selectedCaptionTrack: PlayerResponse.CaptionTrack? by mutableStateOf(null)

    /**
     * The text of the currently active caption cue (if [selectedCaptionTrack]
     * is set and the track is producing cues), used by [VideoArtworkSurface]
     * to render a subtitle overlay. Null when captions are off or idle.
     */
    var currentCaptionText: String? by mutableStateOf(null)
        internal set

    /**
     * Pending resync request set by [requestResync]. Consumed by a [LaunchedEffect] in
     * [rememberVideoArtworkState] which performs the actual pause-load-resume protocol.
     */
    internal var pendingResync: Triple<Long, Boolean, Boolean>? by mutableStateOf(null)

    /**
     * Epoch-millis timestamp of the last automatic (drift-triggered) hard
     * resync. Used together with [VideoHardResyncCooldownMs] to suppress
     * runaway resync loops. Reset to 0 when the video id changes.
     */
    internal var lastAutoResyncAtMs: Long by mutableLongStateOf(0L)

    /**
     * Sticky flag set when two automatic hard resyncs fire within
     * [VideoHardResyncCooldownMs]. While true, the drift poller stops
     * requesting automatic hard resyncs (the decoder is clearly stuck and
     * re-syncing just makes it worse). The user can still seek manually.
     * Reset when the video id changes.
     */
    internal var autoResyncDisabled: Boolean by mutableStateOf(false)

    /** Request a pause-load-resume resync to [position]. */
    fun requestResync(position: Long, isPlaying: Boolean) {
        if (hasPlaybackFailed) return
        if (isResyncing) return
        pendingResync = Triple(position, isPlaying, false)
    }

    /**
     * Request an automatic (drift-triggered) hard resync. Subject to a cooldown: if two
     * auto-resyncs fire within [VideoHardResyncCooldownMs], [autoResyncDisabled] is latched true
     * and subsequent auto-resync requests are dropped until the video id changes.
     */
    internal fun requestAutoResync(position: Long, isPlaying: Boolean): Boolean {
        if (hasPlaybackFailed) return false
        if (isResyncing) return false
        if (autoResyncDisabled) return false
        val now = SystemClock.elapsedRealtime()
        if (lastAutoResyncAtMs != 0L && now - lastAutoResyncAtMs < VideoHardResyncCooldownMs) {
            Timber
                .tag(VideoPlaybackLogTag)
                .w("Auto-resync cooldown hit — disabling automatic resync for this video")
            autoResyncDisabled = true
            return false
        }
        lastAutoResyncAtMs = now
        pendingResync = Triple(position, isPlaying, true)
        return true
    }

    /**
     * Video-only pause/resume micro-cycle — the automated version of the user's manual "pause and
     * resume" that fixes a laggy video.
     */
    internal fun kickRenderer(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (hasPlaybackFailed) return false
        if (isResyncing) return false
        if (exoPlayer.playbackState != Player.STATE_READY) return false
        if (!exoPlayer.playWhenReady) return false
        if (lastSurfaceReanchorAtMs != 0L && now - lastSurfaceReanchorAtMs < SurfaceReanchorMinIntervalMs) {
            return false
        }
        lastSurfaceReanchorAtMs = now
        // Pause then resume in the same call. ExoPlayer processes these in
        // order on the playback thread: the renderer stops presenting,
        // re-derives its presentation clock from the current position, and
        // resumes presenting fresh frames. No seek → no re-buffer stall.
        exoPlayer.pause()
        exoPlayer.play()
        return true
    }
}

/** Create and remember a [VideoArtworkState] for the given [videoId]. */
@Composable
fun rememberVideoArtworkState(
    videoId: String,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    preferredHeight: Int?,
    holdAudioUntilVideoReady: Boolean,
    onStreamResolved: (VideoStreamInfo?) -> Unit,
    onPlaybackFailed: () -> Unit,
    onLoadingStateChange: (Boolean) -> Unit,
    onRequestPauseMain: () -> Unit,
    onRequestResumeMain: () -> Unit,
): VideoArtworkState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shouldPlay by rememberUpdatedState(isPlaying)
    val currentPosition by rememberUpdatedState(positionProvider)
    val updatedPreferredHeight by rememberUpdatedState(preferredHeight)
    val updatedOnStreamResolved by rememberUpdatedState(onStreamResolved)
    val updatedOnPlaybackFailed by rememberUpdatedState(onPlaybackFailed)
    val updatedOnLoadingStateChange by rememberUpdatedState(onLoadingStateChange)
    val updatedOnRequestPauseMain by rememberUpdatedState(onRequestPauseMain)
    val updatedOnRequestResumeMain by rememberUpdatedState(onRequestResumeMain)
    val updatedHoldAudioUntilVideoReady by rememberUpdatedState(holdAudioUntilVideoReady)

    // ── OkHttp client with the YouTube stream proxy + request profile headers ── MIRRORS
    // MusicService.mediaOkHttpClient — the audio player's client.
    val okHttpClient =
        remember {
            OkHttpClient
                .Builder()
                .withAppStreamProxy()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) {
                        // Match the audio client: pass through non-YouTube hosts unchanged
                        // (no User-Agent override). The audio client doesn't override the
                        // User-Agent for non-YouTube hosts either.
                        return@addInterceptor chain.proceed(request)
                    }

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    chain.proceed(
                        StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                }.build()
        }

    val mediaSourceFactory =
        remember(okHttpClient) {
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    OkHttpDataSource.Factory(okHttpClient),
                ),
            )
        }

    val renderersFactory =
        remember(context) {
            DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        }

    // Disable the audio track — the main MusicService ExoPlayer is the
    // source of truth for audio. Playing audio here would double it.
    //
    // Text tracks (captions) are explicitly ENABLED so that the
    // onCues(CueGroup) listener fires when a caption track is selected.
    // Without setTrackTypeDisabled(TEXT, false) some default track
    // selector configurations leave text tracks off, which is why
    // captions were detected but never displayed.
    val trackSelector =
        remember(context) {
            DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setForceHighestSupportedBitrate(true)
                        .build(),
                )
            }
        }

    // ── Single ExoPlayer for the whole composition lifetime ──
    //
    // Deliberately NOT keyed on videoId: recreating the player per video
    // released the old player while its surface was still attached, which
    // crashed the app when switching between two music videos. Reusing the
    // player means switching videos just loads a new media item into the
    // same player. The player is only released when this composable (and
    // therefore the whole video UI) leaves the tree.
    val state =
        remember(mediaSourceFactory, renderersFactory, trackSelector) {
            val exoPlayer =
                ExoPlayer
                    .Builder(context)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setRenderersFactory(renderersFactory)
                    .setTrackSelector(trackSelector)
                    .build()
                    .apply {
                        volume = 0f
                        playWhenReady = isPlaying
                    }
            VideoArtworkState(exoPlayer)
        }

    val exoPlayer = state.exoPlayer

    // "Start together" audio hold state.
    //
    // While a music video is loading we pause the main audio player and hold
    // it until the video's first frame is rendered, then resume both. This
    // prevents the "audio starts first, video loads later" desync.
    var awaitingVideoReady by remember { mutableStateOf(false) }
    var resumeAudioAfterVideoReady by remember { mutableStateOf(false) }

    // Track what's currently loaded into the ExoPlayer so we can detect
    // caption-only changes (stream URL unchanged, caption track changed).
    // When a caption-only change is detected, we pause both audio and video
    // before reloading the media item, and resume both only after the first
    // frame renders — matching the quality-change pause-load-resume protocol.
    var lastLoadedStreamUrl by remember { mutableStateOf<String?>(null) }
    var lastLoadedCaptionTrack by remember { mutableStateOf<PlayerResponse.CaptionTrack?>(null) }

    fun beginAudioHold() {
        if (!updatedHoldAudioUntilVideoReady) return
        if (awaitingVideoReady) return
        awaitingVideoReady = true
        resumeAudioAfterVideoReady = shouldPlay
        // PAUSE the main audio player for the duration of the video load + the
        // [VideoLoadResumeDelayMs] settling period after the first frame renders.
        if (shouldPlay) updatedOnRequestPauseMain()
        Timber
            .tag(VideoPlaybackLogTag)
            .d("Video for $videoId loading — audio paused for 1s settling")
    }

    fun releaseAudioHold(resumeMainAudio: Boolean = false) {
        if (!awaitingVideoReady) return
        val shouldResumeAudio = resumeAudioAfterVideoReady
        awaitingVideoReady = false
        // A failed or disposed video must not leave the main audio paused. The caller supplies
        // resumeMainAudio only for those terminal paths; the first-frame path keeps the flag until
        // it schedules the coordinated resume.
        if (resumeMainAudio && shouldResumeAudio) {
            resumeAudioAfterVideoReady = false
            updatedOnRequestResumeMain()
        }
        // The first-frame path intentionally leaves `resumeAudioAfterVideoReady` intact until it
        // has copied the value into the pending-resume flags below.
        Timber
            .tag(VideoPlaybackLogTag)
            .d("Video ready — clearing hold flag (resume scheduled=${!resumeMainAudio})")
    }

    // Propagate loading state to the parent so it can render a spinner.
    LaunchedEffect(
        state.isVideoReady,
        state.isChangingQuality,
        state.isResyncing,
        state.streamUrl,
        state.hasPlaybackFailed,
        state.isResolvingUrl,
    ) {
        val loading =
            !state.hasPlaybackFailed &&
                (
                    state.isResolvingUrl ||
                        state.isResyncing ||
                        (state.streamUrl != null && !state.isVideoReady)
                )
        updatedOnLoadingStateChange(loading)
    }

    // ── Resolve the video stream URL off the main thread ──
    LaunchedEffect(videoId) {
        state.streamUrl = null
        state.isVideoReady = false
        state.hasPlaybackFailed = false
        state.isResolvingUrl = true
        state.isResyncing = false
        state.wasPlayingBeforeResync = false
        state.bufferingStartedAtMs = 0L
        state.selectedCaptionTrack = null
        state.captionTracks = emptyList()
        state.currentCaptionText = null
        // Reset the automatic-resync cooldown trackers for the new video.
        // A new video gets a fresh chance at automatic drift resync even if
        // the previous video's decoder got stuck.
        state.lastAutoResyncAtMs = 0L
        state.autoResyncDisabled = false
        // Reset the drift-correction state for the new video. The speed
        // factor returns to 1.0 (no correction) and the settling timer
        // is cleared so the drift poller can run as soon as the video's
        // first frame renders (the initial-load snap in
        // onRenderedFirstFrame will set lastSeekAtMs to start a fresh
        // settling window if it actually seeks).
        state.currentSpeedCorrectionFactor = 1.0f
        state.lastSeekAtMs = 0L
        // Clear the surface re-anchor timestamp — the new video's own
        // first-frame flow handles its initial sync, so a stale timestamp
        // from the previous video must not suppress it.
        state.lastSurfaceReanchorAtMs = 0L
        // Cancel any pending resume from the previous video. Setting
        // `pendingResumeAtMs = 0L` causes the LaunchedEffect keyed on
        // it to re-launch and immediately return (the early-return
        // guard), so no stale resume fires for the new video. The new
        // video will schedule its own resume when its first frame
        // renders.
        state.pendingResumeAtMs = 0L
        state.pendingResumeMainAudio = false
        state.pendingResumeVideo = false
        // Reset the "last loaded" trackers so the next media-item load is
        // treated as a fresh load (not a caption-only change).
        lastLoadedStreamUrl = null
        lastLoadedCaptionTrack = null

        beginAudioHold()

        val resolved =
            withContext(Dispatchers.IO) {
                resolveVideoStreamUrl(videoId, updatedPreferredHeight)
            }

        state.isResolvingUrl = false

        if (resolved == null) {
            state.hasPlaybackFailed = true
            releaseAudioHold(resumeMainAudio = true)
            updatedOnPlaybackFailed()
            updatedOnStreamResolved(null)
        } else {
            state.streamUrl = resolved.streamUrl
            state.captionTracks = resolved.captionTracks
            updatedOnStreamResolved(resolved)
        }
    }

    // ── Re-resolve when the user changes preferred quality ──
    LaunchedEffect(preferredHeight) {
        if (state.streamUrl == null) return@LaunchedEffect
        state.wasPlayingBeforeQualityChange = shouldPlay
        state.isChangingQuality = true
        state.isVideoReady = false
        state.isResolvingUrl = true
        exoPlayer.pause()
        updatedOnRequestPauseMain()
        state.streamUrl = null
        val resolved =
            withContext(Dispatchers.IO) {
                resolveVideoStreamUrl(videoId, updatedPreferredHeight)
            }
        state.isResolvingUrl = false
        if (resolved != null) {
            state.streamUrl = resolved.streamUrl
            state.captionTracks = resolved.captionTracks
            updatedOnStreamResolved(resolved)
        } else {
            state.isChangingQuality = false
            val fallback =
                withContext(Dispatchers.IO) {
                    resolveVideoStreamUrl(videoId, updatedPreferredHeight)
                }
            if (fallback != null) {
                state.streamUrl = fallback.streamUrl
                state.captionTracks = fallback.captionTracks
                updatedOnStreamResolved(fallback)
            } else {
                state.hasPlaybackFailed = true
                updatedOnPlaybackFailed()
            }
            if (state.wasPlayingBeforeQualityChange) {
                updatedOnRequestResumeMain()
            }
        }
    }

    // ── Load the resolved URL into the ExoPlayer ── Reloads when the stream URL changes (new video
    // / quality swap) and when the selected caption track changes (so the caption's WebVTT URL can
    // be embedded as a subtitle configuration on the media item).
    LaunchedEffect(state.streamUrl, state.selectedCaptionTrack, exoPlayer) {
        val url = state.streamUrl ?: return@LaunchedEffect

        // Detect caption-only change: stream URL is unchanged but the caption
        // track differs from what's currently loaded. We track this with a
        // remembered "last loaded caption" so we can pause the main audio
        // before the reload and resume it after the first frame renders.
        val captionBeingChanged =
            url == lastLoadedStreamUrl &&
                state.selectedCaptionTrack != lastLoadedCaptionTrack
        if (captionBeingChanged) {
            state.wasPlayingBeforeQualityChange = shouldPlay
            state.isChangingQuality = true
            exoPlayer.pause()
            if (shouldPlay) updatedOnRequestPauseMain()
        }
        lastLoadedStreamUrl = url
        lastLoadedCaptionTrack = state.selectedCaptionTrack

        state.isVideoReady = false
        state.hasPlaybackFailed = false
        state.currentCaptionText = null

        val lowercaseUrl = url.lowercase(Locale.ROOT)
        val mimeType =
            when {
                lowercaseUrl.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
                lowercaseUrl.contains("mp4") || lowercaseUrl.contains("avc") -> MimeTypes.VIDEO_MP4
                lowercaseUrl.contains("webm") || lowercaseUrl.contains("vp9") -> MimeTypes.VIDEO_WEBM
                lowercaseUrl.contains("av01") || lowercaseUrl.contains("av1") -> MimeTypes.VIDEO_AV1
                else -> MimeTypes.VIDEO_MP4
            }

        val mediaItemBuilder =
            MediaItem
                .Builder()
                .setUri(url)
                .setMimeType(mimeType)

        // Embed the user-selected caption track. YouTube's timedtext endpoint
        // returns WebVTT when `fmt=vtt` is appended (see CaptionTrack.webVttUrl),
        // which ExoPlayer parses natively. Marking it SELECTION_FLAG_DEFAULT
        // makes the default track selector pick it automatically so the
        // onCues listener below receives the subtitle text.
        state.selectedCaptionTrack?.let { track ->
            mediaItemBuilder.setSubtitleConfigurations(
                listOf(
                    MediaItem
                        .SubtitleConfiguration
                        .Builder(track.webVttUrl().toUri())
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(track.languageCode)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }

        val mediaItem = mediaItemBuilder.build()

        exoPlayer.stop()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        val targetPosition = currentPosition()
        if (targetPosition > 0) {
            exoPlayer.seekTo(targetPosition)
            // Start a settling window so the drift poller doesn't
            // immediately re-seek while the new media item buffers. This
            // covers BOTH the initial-load and quality-change paths.
            state.lastSeekAtMs = SystemClock.elapsedRealtime()
        }
        exoPlayer.playWhenReady = shouldPlay && !awaitingVideoReady
    }

    // ── Play/pause follower ──
    LaunchedEffect(isPlaying, awaitingVideoReady, state.isChangingQuality) {
        if (state.hasPlaybackFailed) {
            exoPlayer.pause()
        } else if (awaitingVideoReady) {
            // The video isn't ready yet — keep the video paused. The main audio is ALSO paused
            // (see [beginAudioHold], which calls `updatedOnRequestPauseMain()` when the load
            // begins). Both stay paused until the video's first frame renders, at which point
            // `onRenderedFirstFrame` schedules a delayed resume via `state.pendingResumeAtMs`, so
            // audio and video resume together 1 second later. Holding BOTH until that first frame
            // keeps them from desyncing, with one starting ahead of the other.
            exoPlayer.pause()
        } else if (state.isChangingQuality) {
            // A quality or caption change is in progress — keep BOTH
            // paused until the first frame of the new media item
            // renders. This ensures neither audio nor video resumes on
            // its own while the other is still loading. The actual
            // resume is scheduled by `onRenderedFirstFrame` (see the
            // `pendingResumeAtMs` LaunchedEffect).
            if (isPlaying) updatedOnRequestPauseMain()
            exoPlayer.pause()
        } else {
            exoPlayer.setVideoPlayback(isPlaying)
        }
    }

    // ── Update track selector when caption selection changes ──
    //
    // Setting the preferred text language on the DefaultTrackSelector makes
    // it actively select the caption track that matches the user's choice.
    // Without this, even with SELECTION_FLAG_DEFAULT on the subtitle config,
    // the track selector may not pick it up if there are multiple text
    // tracks or if the default selection logic doesn't match. When captions
    // are off (selectedCaptionTrack == null), we clear the preferred language
    // so no text track is selected.
    LaunchedEffect(state.selectedCaptionTrack, trackSelector) {
        val lang = state.selectedCaptionTrack?.languageCode
        val params =
            trackSelector
                .buildUponParameters()
                .apply {
                    if (!lang.isNullOrBlank()) {
                        setPreferredTextLanguage(lang)
                    }
                }.build()
        trackSelector.setParameters(params)
    }

    // ── Safety net: release the audio hold if the video never becomes ready ──
    LaunchedEffect(state.streamUrl) {
        if (state.streamUrl == null) return@LaunchedEffect
        delay(VideoReadyHoldTimeoutMs)
        if (awaitingVideoReady && !state.isVideoReady) {
            Timber
                .tag(VideoPlaybackLogTag)
                .w("Video not ready within ${VideoReadyHoldTimeoutMs}ms — falling back to artwork")
            state.hasPlaybackFailed = true
            exoPlayer.stop()
            releaseAudioHold(resumeMainAudio = true)
            // Cancel any pending resume — the video isn't coming, so
            // don't let a stale scheduled resume fire later.
            state.pendingResumeAtMs = 0L
            state.pendingResumeMainAudio = false
            state.pendingResumeVideo = false
            // Resume is guarded by the media id in Player.kt, so a late timeout from an old video
            // cannot start a newly selected track.
            updatedOnPlaybackFailed()
        }
    }

    // ── Playback speed follower (user preference × drift-correction factor) ── The video
    // ExoPlayer's effective speed is the product of: - the user's preferred playback speed
    // ([VideoPlaybackSpeedKey]), which the audio-side follower in FullscreenVideoOverlay also
    // applies to the main MusicService ExoPlayer so both stay at the same nominal speed; AND -
    // [VideoArtworkState.currentSpeedCorrectionFactor], a proportional correction factor
    // (0.80–1.20) updated by the drift poller below to gently bring the video into sync with the
    // audio clock WITHOUT seeking.
    val (videoPlaybackSpeed, _) = rememberPreference(VideoPlaybackSpeedKey, defaultValue = 1.0f)
    LaunchedEffect(videoPlaybackSpeed, exoPlayer, state.currentSpeedCorrectionFactor) {
        val safeSpeed = videoPlaybackSpeed.coerceIn(0.25f, 2f)
        val effectiveSpeed =
            (safeSpeed * state.currentSpeedCorrectionFactor).coerceIn(0.1f, 4f)
        val current = exoPlayer.playbackParameters.speed
        if (kotlin.math.abs(current - effectiveSpeed) > 0.001f) {
            exoPlayer.playbackParameters = PlaybackParameters(effectiveSpeed)
        }
    }

    // ── Manual resync (seekbar) ──
    //
    // Triggered by [VideoArtworkState.requestResync], which is called from
    // the seekbar's onValueChangeFinished in BottomSheetPlayer. This is the
    // ONLY path that performs a pause-load-resume — an automatic drift-based
    // resync must not be reintroduced, as it loops indefinitely.
    LaunchedEffect(state.pendingResync) {
        val (position, wasPlaying, isAutomatic) = state.pendingResync ?: return@LaunchedEffect
        // Consume the request immediately so subsequent calls re-trigger.
        state.pendingResync = null
        if (state.hasPlaybackFailed || state.isResyncing) return@LaunchedEffect
        Timber
            .tag(VideoPlaybackLogTag)
            .d(
                "Resync to ${position}ms (wasPlaying=$wasPlaying, auto=$isAutomatic) — pause-load-resume",
            )
        state.wasPlayingBeforeResync = wasPlaying
        state.isResyncing = true
        state.isVideoReady = false
        // Reset the speed-correction factor — the seek will bring the video
        // back into sync, so any in-flight speed nudge is no longer needed.
        // The drift poller is also suppressed for [VideoSeekSettlingTimeMs]
        // via [lastSeekAtMs] so it won't fight the seek during re-buffer.
        state.currentSpeedCorrectionFactor = 1.0f
        state.lastSeekAtMs = SystemClock.elapsedRealtime()
        if (wasPlaying) updatedOnRequestPauseMain()
        exoPlayer.pause()
        exoPlayer.seekTo(position)
        state.bufferingStartedAtMs = SystemClock.elapsedRealtime()
    }

    // ── Delayed resume after the video's first frame renders ── The user's explicit request: when
    // a music video loads, pause BOTH audio and video for ~1 second, then resume them together.
    LaunchedEffect(state.pendingResumeAtMs) {
        if (state.pendingResumeAtMs == 0L) return@LaunchedEffect
        val resumeMainAudio = state.pendingResumeMainAudio
        val resumeVideo = state.pendingResumeVideo
        val now = SystemClock.elapsedRealtime()
        val delayMs = (state.pendingResumeAtMs - now).coerceAtLeast(0L)
        delay(delayMs)
        // Re-check: if playback failed or the player errored during the
        // delay window, don't resume. The user will see the error UI.
        if (state.hasPlaybackFailed || exoPlayer.playerError != null) {
            Timber
                .tag(VideoPlaybackLogTag)
                .w("Pending resume cancelled — playback failed during delay window")
            state.pendingResumeAtMs = 0L
            state.pendingResumeMainAudio = false
            state.pendingResumeVideo = false
            return@LaunchedEffect
        }
        Timber
            .tag(VideoPlaybackLogTag)
            .d("Firing delayed resume (audio=$resumeMainAudio, video=$resumeVideo)")
        if (resumeMainAudio) {
            updatedOnRequestResumeMain()
        }
        if (resumeVideo) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
        // Clear the pending state so the effect doesn't re-fire on
        // recomposition.
        state.pendingResumeAtMs = 0L
        state.pendingResumeMainAudio = false
        state.pendingResumeVideo = false
    }

    // ── Periodic position sync + stuck-buffering recovery ──
    LaunchedEffect(state.streamUrl, exoPlayer) {
        if (state.streamUrl == null) return@LaunchedEffect
        // Positions from the previous poll cycle, used by the frozen-renderer
        // detector below to tell whether the video advanced at all while the
        // audio did. Declared inside the effect so they reset whenever the
        // video (streamUrl) changes.
        var prevVideoPos = -1L
        var prevAudioPos = -1L
        var frozenCycles = 0
        while (isActive) {
            delay(VideoSyncPollIntervalMs)
            if (state.hasPlaybackFailed) continue

            // Stuck-buffering recovery
            if (state.bufferingStartedAtMs > 0L) {
                val bufferingForMs = SystemClock.elapsedRealtime() - state.bufferingStartedAtMs
                if (bufferingForMs > VideoStuckBufferingTimeoutMs) {
                    Timber
                        .tag(VideoPlaybackLogTag)
                        .w("Video stuck in BUFFERING for ${bufferingForMs}ms — forcing re-prepare")
                    state.bufferingStartedAtMs = SystemClock.elapsedRealtime()
                    exoPlayer.prepare()
                }
            }

            // Five tiers: settle after a seek, ignore, speed-correct, re-anchor, hard resync.
            // Thresholds and the reasoning behind each are in docs/video-sync.md.
            if (state.isChangingQuality) continue
            if (state.isResyncing) continue
            if (!state.isVideoReady) continue
            if (awaitingVideoReady) continue

            // Tier 0: settling — suppress drift checks for 2s after any
            // seek so the video has time to re-buffer fully before we
            // trust its currentPosition again.
            val now = SystemClock.elapsedRealtime()
            if (state.lastSeekAtMs > 0L && now - state.lastSeekAtMs < VideoSeekSettlingTimeMs) {
                // Also clamp the correction factor to 1.0 during settling
                // so we don't fight the seek with a stale speed nudge.
                if (state.currentSpeedCorrectionFactor != 1.0f) {
                    state.currentSpeedCorrectionFactor = 1.0f
                }
                continue
            }

            // The video must be actively rendering. While it is BUFFERING
            // (or ENDED/IDLE) its currentPosition is stale — it has frozen
            // at the last rendered frame while the audio kept advancing, so
            // any drift computed now is meaningless and would either trigger
            // a redundant seek into still-loading data or a runaway
            // correction. The stuck-buffering recovery above handles
            // genuinely stuck decoders; once the video returns to READY the
            // tiers below snap it back into sync (drift has meanwhile grown
            // past the re-anchor threshold, which is what we want).
            if (exoPlayer.playbackState != Player.STATE_READY) {
                if (state.currentSpeedCorrectionFactor != 1.0f) {
                    state.currentSpeedCorrectionFactor = 1.0f
                }
                continue
            }

            val mainPos = currentPosition()
            if (mainPos <= 0) continue
            val videoPos = exoPlayer.currentPosition
            // SIGNED drift: positive = video AHEAD of audio, negative =
            // video BEHIND audio. The sign drives the direction of the
            // speed correction (tier 2).
            val signedDrift = videoPos - mainPos
            val absDrift = kotlin.math.abs(signedDrift)

            // ── Frozen-renderer detection ── A video renderer can get STUCK while still reporting
            // STATE_READY: it stops presenting frames to the surface (the displayed frame freezes)
            // and its own clock stops advancing.
            if (exoPlayer.playWhenReady && prevVideoPos >= 0L) {
                val audioAdvanced = mainPos - prevAudioPos
                val videoAdvanced = videoPos - prevVideoPos
                if (audioAdvanced >= VideoSyncPollIntervalMs && videoAdvanced <= VideoFrozenRendererMaxAdvanceMs) {
                    frozenCycles++
                    if (frozenCycles >= VideoFrozenRendererCycles) {
                        Timber
                            .tag(VideoPlaybackLogTag)
                            .w(
                                "Video renderer frozen: position stuck at ${videoPos}ms while " +
                                    "audio advanced to ${mainPos}ms (${frozenCycles} cycles) — " +
                                    "restarting renderer",
                            )
                        frozenCycles = 0
                        prevVideoPos = -1L
                        state.kickRenderer(now)
                    }
                } else {
                    frozenCycles = 0
                }
            } else if (!exoPlayer.playWhenReady) {
                // Video paused — a paused position naturally doesn't advance.
                // Reset the freeze counter so a pause doesn't look like a
                // frozen renderer.
                frozenCycles = 0
            }
            prevVideoPos = videoPos
            prevAudioPos = mainPos

            if (absDrift > VideoHardResyncThresholdMs) {
                // Tier 4: hard resync.
                Timber
                    .tag(VideoPlaybackLogTag)
                    .w("Hard resync: drift=${signedDrift}ms (main=$mainPos, video=$videoPos, playing=$shouldPlay)")
                state.currentSpeedCorrectionFactor = 1.0f
                val accepted = state.requestAutoResync(mainPos, shouldPlay)
                if (!accepted) {
                    // Cooldown/latch hit — never leave the video desynced
                    // with no automatic path. Fall back to a plain re-anchor
                    // seek (no audio pause) so it still self-heals.
                    exoPlayer.seekTo(mainPos)
                    state.lastSeekAtMs = now
                    state.lastSurfaceReanchorAtMs = now
                }
            } else if (absDrift > VideoSoftSeekDriftThresholdMs) {
                // Tier 3: re-anchor / soft seek. Triggered for both playing
                // AND paused states — when paused, a large drift means the
                // user seeked the audio while the video was idle, so we need
                // to snap the video to the new audio position.
                Timber
                    .tag(VideoPlaybackLogTag)
                    .d("Re-anchor: drift=${signedDrift}ms (main=$mainPos, video=$videoPos)")
                state.currentSpeedCorrectionFactor = 1.0f
                exoPlayer.seekTo(mainPos)
                state.lastSeekAtMs = now
                // Stamp the re-anchor so the onRenderedFirstFrame triggered by
                // this seek doesn't perform a redundant (and self-perpetuating)
                // forced re-anchor on top of it.
                state.lastSurfaceReanchorAtMs = now
            } else if (absDrift > VideoSyncIgnoreToleranceMs) {
                // Tier 2: proportional speed correction.
                //
                // normalizedDrift ∈ [-1, 1]: +1 = video ahead by
                // [VideoSoftSeekDriftThresholdMs]; -1 = video behind by
                // the same. We cap at ±1 so a borderline drift just
                // before the re-anchor threshold still applies the max
                // correction (no runaway).
                val normalizedDrift =
                    (signedDrift.toFloat() / VideoSoftSeekDriftThresholdMs.toFloat())
                        .coerceIn(-1f, 1f)
                // If drift > 0 (video ahead), we want factor < 1 (slow
                // down) → subtract. If drift < 0 (video behind), we
                // want factor > 1 (speed up) → the subtraction of a
                // negative adds. Hence the single formula:
                //   factor = 1 - (normalizedDrift * maxFactor)
                val targetFactor =
                    1.0f - (normalizedDrift * VideoSyncSpeedCorrectionFactorMax)
                if (kotlin.math.abs(state.currentSpeedCorrectionFactor - targetFactor) > 0.005f) {
                    Timber
                        .tag(VideoPlaybackLogTag)
                        .d("Speed-correct: drift=${signedDrift}ms factor=$targetFactor (main=$mainPos, video=$videoPos)")
                    state.currentSpeedCorrectionFactor = targetFactor
                }
            } else {
                // Tier 1: within ignore tolerance. Reset the factor so
                // the video returns to the user's preferred speed once
                // drift is corrected.
                if (state.currentSpeedCorrectionFactor != 1.0f) {
                    state.currentSpeedCorrectionFactor = 1.0f
                }
            }
        }
    }

    // ── Lifecycle observer ──
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (
                    (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) &&
                    !state.hasPlaybackFailed &&
                    exoPlayer.playerError == null &&
                    state.streamUrl != null
                ) {
                    exoPlayer.setVideoPlayback(shouldPlay)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── Player event listener ──
    DisposableEffect(exoPlayer, state.streamUrl) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.tag(VideoPlaybackLogTag).w(error, "Video playback failed for $videoId")
                    state.hasPlaybackFailed = true
                    state.isVideoReady = false
                    state.isChangingQuality = false
                    state.isResyncing = false
                    state.bufferingStartedAtMs = 0L
                    releaseAudioHold(resumeMainAudio = true)
                    updatedOnPlaybackFailed()
                }

                override fun onCues(cueGroup: CueGroup) {
                    val text =
                        cueGroup.cues
                            .joinToString("\n") { it.text?.toString().orEmpty() }
                            .takeIf { it.isNotBlank() }
                    state.currentCaptionText = text
                }

                override fun onRenderedFirstFrame() {
                    // `wasAlreadyReady` distinguishes a re-render on an
                    // already-playing player (surface re-attached during a
                    // fullscreen toggle / orientation change / window resize,
                    // or a seek re-rendered a new frame) from the first frame
                    // of a fresh load / quality change / resync. It must be
                    // captured BEFORE `isVideoReady` is set true below.
                    val wasAlreadyReady = state.isVideoReady
                    state.isVideoReady = true
                    // Capture `resumeAudioAfterVideoReady` BEFORE
                    // releaseAudioHold() — releaseAudioHold() clears
                    // `awaitingVideoReady` (which lets the play/pause
                    // follower stop force-pausing the video) but no
                    // longer clears `resumeAudioAfterVideoReady`, so
                    // we can read it here to decide whether to
                    // schedule an audio resume.
                    val shouldResumeAudioAfterHold = resumeAudioAfterVideoReady
                    releaseAudioHold()
                    val wasChangingQuality = state.isChangingQuality
                    state.isChangingQuality = false
                    val wasResync = state.isResyncing
                    state.isResyncing = false
                    val wasPlayingBeforeResyncLocal = state.wasPlayingBeforeResync
                    state.wasPlayingBeforeResync = false
                    state.bufferingStartedAtMs = 0L

                    // ── Snap video to the audio's CURRENT position ── During the initial load the
                    // audio was paused (see [beginAudioHold]), so its position has been frozen
                    // while the video buffered.
                    val now = SystemClock.elapsedRealtime()
                    if (!wasResync) {
                        val mainPos = currentPosition()
                        if (mainPos > 0) {
                            if (wasAlreadyReady) {
                                // ── Surface re-attach: restart the renderer ── The video was
                                // already rendering and its surface got re-created (fullscreen
                                // toggle, orientation change, window resize).
                                if (state.kickRenderer(now)) {
                                    Timber
                                        .tag(VideoPlaybackLogTag)
                                        .d("Surface re-attached while playing — restarted video renderer")
                                }
                            } else {
                                val videoPos = exoPlayer.currentPosition
                                val drift = kotlin.math.abs(videoPos - mainPos)
                                if (drift > VideoInitialSyncToleranceMs) {
                                    exoPlayer.seekTo(mainPos)
                                    // Start the settling window so the drift
                                    // poller doesn't immediately re-seek while
                                    // the video re-buffers from this snap.
                                    state.lastSeekAtMs = now
                                }
                            }
                        }
                    }

                    // ── Schedule the resume after a 1-second settling delay ── The user's explicit
                    // request: when a music video loads, pause BOTH audio and video for ~1 second,
                    // then resume them together.
                    val effectiveShouldPlay =
                        shouldPlay ||
                            (wasResync && wasPlayingBeforeResyncLocal) ||
                            (wasChangingQuality && state.wasPlayingBeforeQualityChange) ||
                            shouldResumeAudioAfterHold
                    // A pure surface re-attach (video already playing) has
                    // nothing to resume — no audio was paused and the video
                    // never stopped. Skipping the delayed resume here avoids
                    // a pointless 1s "resume" log and any edge-case double
                    // play() call.
                    val nothingToResume = wasAlreadyReady && !wasChangingQuality && !wasResync
                    if (effectiveShouldPlay && !nothingToResume && !state.hasPlaybackFailed &&
                        exoPlayer.playerError == null
                    ) {
                        // Compute the resume flags. The main audio
                        // should be resumed if it was paused — which
                        // is true for all three paths now that
                        // `beginAudioHold` also pauses the audio for
                        // the initial-load path.
                        val resumeMainAudio =
                            (wasChangingQuality && state.wasPlayingBeforeQualityChange) ||
                                (wasResync && wasPlayingBeforeResyncLocal) ||
                                shouldResumeAudioAfterHold
                        state.pendingResumeMainAudio = resumeMainAudio
                        state.pendingResumeVideo = true
                        state.pendingResumeAtMs =
                            SystemClock.elapsedRealtime() + VideoLoadResumeDelayMs
                        Timber
                            .tag(VideoPlaybackLogTag)
                            .d(
                                "First frame rendered — resume scheduled in ${VideoLoadResumeDelayMs}ms " +
                                    "(audio=$resumeMainAudio, video=true)",
                            )
                    } else if (!nothingToResume) {
                        // We're not resuming (e.g. the user paused
                        // during loading, or playback failed). Clear
                        // any stale pending resume so a previously-
                        // scheduled one doesn't fire on this state
                        // change.
                        state.pendingResumeAtMs = 0L
                        state.pendingResumeMainAudio = false
                        state.pendingResumeVideo = false
                    }
                    // When `nothingToResume` (pure surface re-attach), leave
                    // any in-flight pending resume untouched — e.g. a load's
                    // first-frame already scheduled the resume and a surface
                    // re-attach happened inside the 1s window; cancelling it
                    // here would strand the main audio paused forever.
                    // `resumeAudioAfterVideoReady` has now served its
                    // purpose (captured into `shouldResumeAudioAfterHold`
                    // above). Clear it so it doesn't leak into the next
                    // load cycle.
                    resumeAudioAfterVideoReady = false
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            if (state.bufferingStartedAtMs == 0L) {
                                state.bufferingStartedAtMs = SystemClock.elapsedRealtime()
                            }
                        }
                        Player.STATE_READY -> {
                            state.bufferingStartedAtMs = 0L
                            // The player has buffered enough to start playing.
                            val effectiveShouldPlay =
                                shouldPlay ||
                                    (state.isResyncing && state.wasPlayingBeforeResync) ||
                                    (state.isChangingQuality && state.wasPlayingBeforeQualityChange) ||
                                    resumeAudioAfterVideoReady
                            if (effectiveShouldPlay && !state.hasPlaybackFailed &&
                                exoPlayer.playerError == null
                            ) {
                                val resumeMainAudio =
                                    (state.isChangingQuality && state.wasPlayingBeforeQualityChange) ||
                                        (state.isResyncing && state.wasPlayingBeforeResync) ||
                                        resumeAudioAfterVideoReady
                                state.pendingResumeMainAudio = resumeMainAudio
                                state.pendingResumeVideo = true
                                state.pendingResumeAtMs =
                                    SystemClock.elapsedRealtime() + VideoLoadResumeDelayMs
                            }
                        }
                        Player.STATE_ENDED -> {
                            state.bufferingStartedAtMs = 0L
                            val mainPos = currentPosition()
                            exoPlayer.seekTo(mainPos)
                            // The video ended (likely a brief stall at the
                            // end of a chunk); snap it back to the audio
                            // position and start a settling window so the
                            // drift poller doesn't immediately re-seek.
                            state.lastSeekAtMs = SystemClock.elapsedRealtime()
                            exoPlayer.setVideoPlayback(shouldPlay)
                        }
                        Player.STATE_IDLE -> {
                            state.bufferingStartedAtMs = 0L
                        }
                    }
                }
            }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // ── Release the ExoPlayer when the composable leaves the tree ──
    //
    // The release is deferred to the next main-looper pass so any surface
    // still attached in this frame (e.g. when switching to a non-video song)
    // can detach cleanly first — accessing a released player throws.
    //
    // The audio hold is released IMMEDIATELY (not deferred): if the hold was
    // active (main audio paused while waiting for the video's first frame)
    // and this composable leaves the tree — e.g. the user skips from a music
    // video to a non-video song while the video is still loading — the main
    // audio must be resumed right away or it would stay paused forever.
    DisposableEffect(exoPlayer) {
        onDispose {
            releaseAudioHold(resumeMainAudio = true)
            Handler(Looper.getMainLooper()).post {
                exoPlayer.release()
            }
        }
    }

    return state
}

/**
 * Conditional wrapper around [rememberVideoArtworkState] that returns `null` when [videoId] is
 * blank.
 */
@Composable
fun rememberVideoArtworkStateOrNull(
    videoId: String?,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    preferredHeight: Int?,
    holdAudioUntilVideoReady: Boolean,
    onStreamResolved: (VideoStreamInfo?) -> Unit,
    onPlaybackFailed: () -> Unit,
    onLoadingStateChange: (Boolean) -> Unit,
    onRequestPauseMain: () -> Unit,
    onRequestResumeMain: () -> Unit,
): VideoArtworkState? {
    return if (videoId.isNullOrBlank()) {
        onPlaybackFailed()
        null
    } else {
        rememberVideoArtworkState(
            videoId = videoId,
            isPlaying = isPlaying,
            positionProvider = positionProvider,
            preferredHeight = preferredHeight,
            holdAudioUntilVideoReady = holdAudioUntilVideoReady,
            onStreamResolved = onStreamResolved,
            onPlaybackFailed = onPlaybackFailed,
            onLoadingStateChange = onLoadingStateChange,
            onRequestPauseMain = onRequestPauseMain,
            onRequestResumeMain = onRequestResumeMain,
        )
    }
}

/** Renders the video surface for the given [state]. */
@Composable
fun VideoArtworkSurface(
    state: VideoArtworkState,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    ambientMode: Boolean = false,
    thumbnailUrl: String? = null,
) {
    val alpha by animateFloatAsState(
        targetValue = if (state.isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "videoAlpha",
    )

    // Media3's ContentFrame with SURFACE_TYPE_TEXTURE_VIEW is deliberate. Flow's PlayerView +
    // SurfaceManager pattern (inflating PlayerView from XML, managing SurfaceHolder.Callbacks with
    // PlaceholderSurface) compiles but produces a black screen: the explicit surface wiring races
    // the PlayerView's own internal surface lifecycle and the codec never attaches. The Flow files
    // (VideoSurfacePolicy, VideoSurfaceManager, the two XML layouts) are kept in the tree for a
    // future attempt — the PlaceholderSurface codec-preservation insight is sound; the wiring needs
    // more careful integration with VideoArtworkState's existing Player.Listener + kickRenderer
    // paths.
    Box(modifier = modifier) {
        // ── Ambient mode background ──
        // Render a slowly drifting, blurred copy of the song thumbnail
        // behind the video surface. The black letterbox area around a FIT
        // video then glows with the artwork's colors instead of being pure
        // black — mimicking YouTube's "ambient mode" effect.
        if (ambientMode && !thumbnailUrl.isNullOrBlank() && !moe.rukamori.archivetune.LocalBlurPolicy.current.any) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                moe.rukamori.archivetune.ui.utils.BlurFreeArtworkBackdrop(
                    mediaId = thumbnailUrl,
                    artworkUrl = thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (ambientMode && !thumbnailUrl.isNullOrBlank()) {
            VideoAmbientBackdrop(
                thumbnailUrl = thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Plain black background so letterbox bars look clean.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
            )
        }

        ContentFrame(
            player = state.exoPlayer,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = resizeMode.toContentScale(),
            keepContentOnReset = false,
            shutter = {},
            modifier = Modifier.fillMaxSize().alpha(alpha),
        )

        // Caption overlay — rendered from the ExoPlayer's current cue text.
        val captionText = state.currentCaptionText
        if (state.selectedCaptionTrack != null && !captionText.isNullOrBlank()) {
            androidx.compose.material3.Text(
                text = captionText,
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Soft drifting blurred artwork backdrop for ambient mode. */
@Composable
private fun VideoAmbientBackdrop(
    thumbnailUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    val transition = rememberInfiniteTransition(label = "video-ambient-drift")
    val animatedDriftX by transition.animateFloat(
        initialValue = -90f,
        targetValue = 90f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 19_000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "video-ambient-x",
    )
    val animatedDriftY by transition.animateFloat(
        initialValue = -60f,
        targetValue = 60f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 27_000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "video-ambient-y",
    )
    val driftX = if (isPreS) 0f else animatedDriftX
    val driftY = if (isPreS) 0f else animatedDriftY

    val blurredBitmap by produceState<Bitmap?>(null, thumbnailUrl) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(thumbnailUrl)
                            .allowHardware(false)
                            .memoryCacheKey("ambient:$thumbnailUrl")
                            .diskCacheKey("ambient:$thumbnailUrl")
                            .size(Size(540, 540))
                            .build()
                    val result = context.imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val bitmap = result.image.toBitmap().copy(Bitmap.Config.ARGB_8888, true)
                        val density = context.resources.displayMetrics.density
                        ImageBlurUtils.blur(bitmap, 48f * density)
                    } else {
                        null
                    }
                }.getOrNull()
            }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .background(Color.Black),
    ) {
        blurredBitmap?.let { bm ->
            Image(
                bitmap = bm.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            translationX = driftX,
                            translationY = driftY,
                            scaleX = 1.4f,
                            scaleY = 1.4f,
                            alpha = 0.85f,
                        ),
            )
        }
        // Darkening scrim so the ambient glow doesn't compete with the
        // video for attention — keeps the video the focal point.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
        )
    }
}

/** Pick the best video format from a [PlayerResponse], honoring a preferred height. */
private fun pickVideoFormat(
    playerResponse: PlayerResponse,
    preferredHeight: Int?,
): PlayerResponse.StreamingData.Format? {
    val streamingData = playerResponse.streamingData ?: return null
    val heightCeiling = maxVideoHeightFor(preferredHeight)

    val allVideoFormats =
        (streamingData.formats.orEmpty() + streamingData.adaptiveFormats.orEmpty())
            .asSequence()
            .filter {
                // Local val enables smart-casting — `height` is a public
                // API property declared in a different module (core), so
                // Kotlin cannot smart-cast `it.height` directly after the
                // null check. Using `h` works around that limitation.
                val h = it.height
                h != null && h > 0
            }
            .filter { (it.height ?: 0) <= heightCeiling }
            .filter { it.url != null || it.signatureCipher != null || it.cipher != null }
            .toList()

    if (allVideoFormats.isEmpty()) {
        // The ceiling excluded everything — every format YouTube offered is taller than this
        // device (or this mode) allows. Fall back to the smallest format rather than returning
        // null, which the caller treats as "this client has no video at all" and would otherwise
        // drop playback to album artwork.
        return (streamingData.formats.withUsableHeight() + streamingData.adaptiveFormats.withUsableHeight())
            .filter { it.url != null || it.signatureCipher != null || it.cipher != null }
            .minByOrNull { it.height ?: Int.MAX_VALUE }
    }

    val comparator =
        compareByDescending<PlayerResponse.StreamingData.Format> { it.height ?: 0 }
            .thenByDescending { it.url != null }
            .thenByDescending { it.audioQuality != null }

    return allVideoFormats.sortedWith(comparator).firstOrNull()
}

/** Formats that declare a usable frame height. */
private fun List<PlayerResponse.StreamingData.Format>?.withUsableHeight(): List<PlayerResponse.StreamingData.Format> =
    orEmpty().filter {
        val h = it.height
        h != null && h > 0
    }

/**
 * Resolve a playable video stream URL for [videoId] by trying multiple YouTube
 * clients in order.
 */
private suspend fun resolveVideoStreamUrl(
    videoId: String,
    preferredHeight: Int?,
): VideoStreamInfo? {
    // Use the same bounded, preference-aware client order as audio playback. Video and audio
    // requests must agree on client context; otherwise a video URL can be minted by one profile
    // and fetched with another profile's headers or token.
    val authState = YouTube.currentPlaybackAuthState()
    val preferredClient =
        PreferenceStore
            .get(PlayerStreamClientKey)
            .toEnum(PlayerStreamClient.WEB_REMIX)
    val autoChoose = PreferenceStore.get(AutoChoosePlaybackClientKey) ?: true
    val clients = YTPlayerUtils.buildStreamClientOrder(preferredClient, authState)

    val usableClients =
        clients.filterNot { client ->
            YTPlayerUtils.isStreamClientBlocked(
                    videoId = videoId,
                    clientKey = StreamClientUtils.buildClientKey(client),
                    authFingerprint = authState.fingerprint,
                )
        }

    for (client in usableClients) {
        val usesCookieAuthentication = authState.hasPlaybackLoginContext && client.supportsCookieAuthentication
        if (client.loginRequired && !usesCookieAuthentication) continue

        val result =
            withTimeoutOrNull(VideoClientAttemptTimeoutMs) {
                runCatching {
                    val signatureTimestamp =
                        if (client.useSignatureTimestamp) {
                            NewPipeStreamUrlExtractor.signatureTimestamp(videoId).getOrNull()
                        } else {
                            null
                        }
                    val poToken = authState.resolvePlayerPoToken(client, videoId = videoId)?.takeIf { it.isNotBlank() }
                    val playerResponse =
                        YouTube
                            .player(
                                videoId = videoId,
                                client = client,
                                signatureTimestamp = signatureTimestamp,
                                poToken = poToken,
                                setLogin = usesCookieAuthentication,
                                authState = authState,
                            ).getOrThrow()
                    if (playerResponse.playabilityStatus.status != "OK") {
                        throw IllegalStateException(
                            "${client.clientName} returned ${playerResponse.playabilityStatus.status}: " +
                                playerResponse.playabilityStatus.reason.orEmpty(),
                        )
                    }

                    val availableHeights =
                        (playerResponse.streamingData?.formats.orEmpty() +
                            playerResponse.streamingData?.adaptiveFormats.orEmpty())
                            .mapNotNull { format ->
                                // Bounded by what this device can decode, NOT by the current
                                // quality mode: the Advanced list has to offer every resolution
                                // the user could switch to, including the 4K original that Auto
                                // itself would never pick.
                                format.height?.takeIf { height ->
                                    height in 1..VideoDecoderCapabilities.maxSupportedHeight()
                                }
                            }.distinct()
                            .sorted()
                    val captionTracks =
                        playerResponse.captions
                            ?.playerCaptionsTracklistRenderer
                            ?.captionTracks
                            .orEmpty()
                    val format = pickVideoFormat(playerResponse, preferredHeight) ?: return@runCatching null
                    // NewPipeStreamUrlExtractor already appends the GVS PO token via
                    // YouTube.appendGvsPoToken, so we just use the URL directly.
                    val finalUrl = NewPipeStreamUrlExtractor.streamUrl(format = format, videoId = videoId).getOrThrow()
                    VideoStreamInfo(
                        streamUrl = finalUrl,
                        availableHeights = availableHeights,
                        captionTracks = captionTracks,
                        selectedHeight = format.height?.takeIf { it > 0 },
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }
            } ?: Result.failure(
                SocketTimeoutException(
                    "Video client ${client.clientName} timed out after ${VideoClientAttemptTimeoutMs}ms",
                ),
            )

        val streamInfo = result.getOrNull()
        if (streamInfo != null && streamInfo.streamUrl.isNotBlank()) {
            YTPlayerUtils.markStreamUrlSuccessful(streamInfo.streamUrl)
            Timber
                .tag(VideoPlaybackLogTag)
                .i("Resolved video stream for $videoId via ${client.clientName}@${client.clientVersion}")
            return streamInfo
        }

        if (autoChoose) {
            YTPlayerUtils.markStreamClientFailed(
                videoId = videoId,
                clientKey = StreamClientUtils.buildClientKey(client),
                httpStatusCode = null,
                authFingerprint = authState.fingerprint,
            )
        }
        result.exceptionOrNull()?.let { error ->
            Timber
                .tag(VideoPlaybackLogTag)
                .w(error, "Video stream resolution failed for $videoId via ${client.clientName}@${client.clientVersion}")
        }
    }

    Timber.tag(VideoPlaybackLogTag).w("All video stream clients exhausted for $videoId")
    return null
}

private fun Int.toContentScale(): ContentScale =
    when (this) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> ContentScale.Crop
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> ContentScale.FillBounds
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        -> ContentScale.Fit
        else -> ContentScale.Fit
    }

private fun ExoPlayer.setVideoPlayback(isPlaying: Boolean) {
    if (isPlaying) {
        if (playbackState == Player.STATE_IDLE && mediaItemCount > 0) prepare()
        if (playbackState == Player.STATE_ENDED) seekTo(0)
        play()
    } else {
        pause()
    }
}

internal const val VideoPlaybackLogTag = "VideoArtworkPlayback"
