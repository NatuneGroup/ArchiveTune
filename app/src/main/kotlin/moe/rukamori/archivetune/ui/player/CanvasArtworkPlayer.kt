/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.ui.player

import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.customartwork.CustomArtworkStore
import moe.rukamori.archivetune.di.CanvasCacheEntryPoint
import moe.rukamori.archivetune.utils.StreamClientUtils
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Locale
import moe.rukamori.archivetune.utils.withAppStreamProxy

private const val CanvasPlaybackStallCheckIntervalMs = 1_000L
private const val CanvasPlaybackStallTimeoutMs = 5_000L

@Composable
fun CanvasArtworkPlayer(
    primaryUrl: String?,
    fallbackUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    // When false, the ExoPlayer is kept alive (paused) but the TextureView
    // (ContentFrame) is NOT rendered. This is used by the Apple Music player's
    // backdrop canvas when lyrics is open: the canvas's Modifier.blur(72.dp)
    // on a live TextureView is a heavy per-frame GPU cost even when the video
    // is paused, because the blur RenderEffect is re-applied every frame.
    // Hiding the TextureView frees the entire GPU frame budget for the
    // karaoke syllable sweep, so the lyrics do not lag while the canvas is
    // playing. When `visible` flips back to true, the TextureView is
    // re-created and the ExoPlayer attaches to it — no reload delay because
    // the player instance was retained.
    visible: Boolean = true,
    /**
     * Caps the decoded video edge, in pixels. A canvas loop is a backdrop — often blurred, always
     * behind something — so decoding it at source resolution spends frame budget on detail nobody
     * sees. Null keeps whatever the track selector would have picked.
     */
    maxVideoEdgePx: Int? = null,
    /**
     * When set, the loop renders through a [CanvasSnapshotView] that hands back one small frame
     * per URL (rukamori 7c19e3191); the Immersive style blurs that still instead of blurring a live
     * video surface every frame. Null (every other style) keeps the regular content frame.
     */
    onFrameCaptured: ((Bitmap?) -> Unit)? = null,
) {
    val frameCallback by rememberUpdatedState(onFrameCaptured)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val primary = primaryUrl?.trim()?.takeIf { it.isNotBlank() }
    val fallback =
        fallbackUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it == primary }
    val initial = primary ?: fallback ?: return
    if (CustomArtworkStore.isCustomImageCanvasUrl(initial)) {
        CustomImageCanvas(
            url = initial,
            visible = visible,
            resizeMode = resizeMode,
            onFrameCaptured = onFrameCaptured,
            modifier = modifier,
        )
        return
    }
    var currentUrl by remember(initial) { mutableStateOf(initial) }
    var isVideoReady by remember(initial) { mutableStateOf(false) }
    var hasPlaybackFailed by remember(initial) { mutableStateOf(false) }

    // Video aspect (width over height, pixel-width-height-ratio applied), tracked from the
    // player's own onVideoSizeChanged. The media3 compose ContentFrame sizes its surface from
    // PresentationState.videoSizeDp — but that state can stay null/stale for some streams
    // (longer Apple Music motion canvases observed stuck stretched to the container), so this
    // composable enforces the aspect itself for the ZOOM path instead of trusting it.
    var videoDisplayAspectRatio by remember(initial) { mutableStateOf<Float?>(null) }
    val shouldPlay by rememberUpdatedState(isPlaying)

    val okHttpClient =
        remember {
            OkHttpClient
                .Builder()
                .withAppStreamProxy()
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
                        return@addInterceptor chain.proceed(
                            request
                                .newBuilder()
                                .header("User-Agent", CanvasPlaybackUserAgent)
                                .build(),
                        )
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
    // Player cache — wraps the OkHttp DataSource with a CacheDataSource so
    // canvas video segments are cached across ExoPlayer re-creations. This
    // is critical for the Apple Music player: when the user closes the
    // lyrics/queue panel, the COVER branch is re-entered and a brand-new
    // ExoPlayer is created. Without caching, the new ExoPlayer re-fetches
    // the canvas from the network (multi-second delay during which the
    // user sees a static artwork flash). With caching, the segments are
    // served from the player cache (sub-second load).
    val playerCache =
        remember {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    context,
                    CanvasCacheEntryPoint::class.java,
                )
            entryPoint.playerCache()
        }
    val mediaSourceFactory =
        remember(okHttpClient, playerCache) {
            val upstreamFactory =
                DefaultDataSource.Factory(
                    context,
                    OkHttpDataSource.Factory(okHttpClient),
                )
            val cacheFactory =
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            DefaultMediaSourceFactory(cacheFactory)
        }
    val renderersFactory =
        remember(context) {
            DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        }
    val trackSelector =
        remember(context, maxVideoEdgePx) {
            DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setForceHighestSupportedBitrate(true)
                        .let { parameters ->
                            if (maxVideoEdgePx != null) {
                                parameters.setMaxVideoSize(maxVideoEdgePx, maxVideoEdgePx)
                            } else {
                                parameters
                            }
                        }.build(),
                )
            }
        }
    // LoadControl tuned for short looping canvas videos. The default
    // DefaultLoadControl has bufferForPlayback = 2.5s which delays the
    // first frame render after a re-creation. A 500ms buffer is plenty
    // for a 5-10s looping clip and lets the first frame render as soon
    // as the initial segment is read (often from cache, so near-instant).
    val loadControl =
        remember {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 30_000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    val exoPlayer =
        remember(initial, mediaSourceFactory, renderersFactory, trackSelector, loadControl) {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = isPlaying
                }
        }

    LaunchedEffect(isPlaying) {
        if (hasPlaybackFailed) {
            exoPlayer.pause()
        } else {
            exoPlayer.setCanvasPlayback(isPlaying)
        }
    }

    // When `visible` flips from false → true (e.g. lyrics closing in the
    // Apple Music player), a NEW TextureView is created and the retained
    // ExoPlayer re-attaches to it. The `isVideoReady` state is then stale,
    // which would make the alpha animate to 1 immediately — showing a black
    // TextureView surface (no frame yet) over the blurred AsyncImage
    // fallback below. Resetting isVideoReady to false keeps the new
    // TextureView invisible until `onRenderedFirstFrame` fires again,
    // letting the fallback show through during the brief re-attach gap.
    LaunchedEffect(visible) {
        if (visible) {
            isVideoReady = false
        }
    }

    LaunchedEffect(currentUrl, isPlaying, primary, fallback, exoPlayer) {
        if (!isPlaying || fallback.isNullOrBlank() || currentUrl != primary) return@LaunchedEffect

        var lastPosition = exoPlayer.currentPosition
        var stalledForMs = 0L

        while (isActive && isPlaying && currentUrl == primary) {
            delay(CanvasPlaybackStallCheckIntervalMs)

            val currentPosition = exoPlayer.currentPosition
            val playbackState = exoPlayer.playbackState
            val positionAdvanced = currentPosition != lastPosition
            val isActivelyRendering =
                playbackState == Player.STATE_READY &&
                    exoPlayer.isPlaying &&
                    positionAdvanced

            stalledForMs =
                if (isActivelyRendering) {
                    0L
                } else {
                    stalledForMs + CanvasPlaybackStallCheckIntervalMs
                }

            if (stalledForMs >= CanvasPlaybackStallTimeoutMs) {
                currentUrl = fallback
                isVideoReady = false
                return@LaunchedEffect
            }

            lastPosition = currentPosition
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (
                    (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) &&
                    !hasPlaybackFailed &&
                    exoPlayer.playerError == null
                ) {
                    exoPlayer.setCanvasPlayback(shouldPlay)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(exoPlayer, primary, fallback) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.tag(CanvasPlaybackLogTag).w(error, "Canvas playback failed")
                    hasPlaybackFailed = true
                    isVideoReady = false
                    frameCallback?.invoke(null)
                    val next =
                        when (currentUrl) {
                            primary -> fallback?.takeIf { it != currentUrl }
                            else -> null
                        }
                    if (!next.isNullOrBlank()) {
                        currentUrl = next
                    } else {
                        exoPlayer.stop()
                    }
                }

                override fun onRenderedFirstFrame() {
                    isVideoReady = true
                    if (shouldPlay && !hasPlaybackFailed && exoPlayer.playerError == null) {
                        exoPlayer.setCanvasPlayback(isPlaying = true)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!shouldPlay || hasPlaybackFailed || exoPlayer.playerError != null) return
                    exoPlayer.setCanvasPlayback(isPlaying = true)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val width = videoSize.width
                    val height = videoSize.height
                    videoDisplayAspectRatio =
                        if (width > 0 && height > 0) {
                            val par = videoSize.pixelWidthHeightRatio
                            (width.toFloat() * (if (par > 0f) par else 1f)) / height.toFloat()
                        } else {
                            null
                        }
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (shouldPlay && !playWhenReady && !hasPlaybackFailed && exoPlayer.playerError == null) {
                        exoPlayer.setCanvasPlayback(isPlaying = true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (shouldPlay && !isPlaying && !hasPlaybackFailed && exoPlayer.playerError == null) {
                        exoPlayer.setCanvasPlayback(isPlaying = true)
                    }
                }
            }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentUrl, exoPlayer) {
        val normalized = currentUrl.trim()
        isVideoReady = false
        hasPlaybackFailed = false
        videoDisplayAspectRatio = null
        frameCallback?.invoke(null)
        val lowercaseUrl = normalized.lowercase(Locale.ROOT)
        val mimeType =
            when {
                // User-picked canvases come in any container; let ExoPlayer infer it from the extension.
                CustomArtworkStore.isCustomCanvasUrl(normalized) -> null
                lowercaseUrl.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
                lowercaseUrl.contains("mp4") -> MimeTypes.VIDEO_MP4
                primary != null && currentUrl == primary -> MimeTypes.APPLICATION_M3U8
                fallback != null && currentUrl == fallback -> MimeTypes.VIDEO_MP4
                else -> MimeTypes.APPLICATION_M3U8
            }

        val mediaItem =
            MediaItem
                .Builder()
                .setUri(normalized)
                .setMimeType(mimeType)
                .build()

        exoPlayer.stop()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.setCanvasPlayback(isPlaying)
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "canvasAlpha",
    )

    // Only render the ContentFrame (TextureView) when `visible` is true.
    // When false, the ExoPlayer stays alive (paused) but the TextureView is
    // removed from the composition tree — eliminating the per-frame GPU cost
    // of compositing + blurring a video surface that isn't changing. When
    // `visible` flips back to true, a new TextureView is created and the
    // ExoPlayer re-attaches to it, showing the current frame immediately.
    if (visible && onFrameCaptured != null) {
        key(exoPlayer, currentUrl) {
            AndroidView(
                factory = { viewContext ->
                    CanvasSnapshotView(viewContext).apply {
                        bind(exoPlayer) { bitmap ->
                            if (!hasPlaybackFailed) frameCallback?.invoke(bitmap)
                        }
                    }
                },
                onRelease = { it.release() },
                modifier = modifier.alpha(alpha),
            )
        }
    } else if (visible) {
        val aspect = videoDisplayAspectRatio
        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM && aspect != null && aspect > 0f) {
            // Self-enforced cover: the frame is laid out at the video's aspect, scaled to COVER
            // the container (overflowing one axis), and the wrapper Box clips the overflow. This
            // renders aspect-correct even when the ContentFrame's internal video-size state is
            // missing, and degenerates to the same geometry when it is not.
            Box(modifier = modifier.clipToBounds()) {
                ContentFrame(
                    player = exoPlayer,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = resizeMode.toContentScale(),
                    keepContentOnReset = false,
                    shutter = {},
                    modifier =
                        Modifier
                            .matchParentSize()
                            .alpha(alpha)
                            .canvasCoverLayout(aspect),
                )
            }
        } else {
            ContentFrame(
                player = exoPlayer,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = resizeMode.toContentScale(),
                keepContentOnReset = false,
                shutter = {},
                modifier = modifier.alpha(alpha),
            )
        }
    }
}

/**
 * Lays the content out at the cover geometry of the incoming constraints for a video with the
 * given display aspect: the content keeps its aspect ratio and fully covers the container,
 * overflowing (and getting clipped by the caller) whichever axis does not match.
 */
private fun Modifier.canvasCoverLayout(videoAspect: Float): Modifier =
    layout { measurable, constraints ->
        val containerWidth = constraints.maxWidth
        val containerHeight = constraints.maxHeight
        if (containerWidth <= 0 || containerHeight <= 0) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
            val targetWidth: Int
            val targetHeight: Int
            if (videoAspect >= containerAspect) {
                // Video relatively wider: match the height, overflow the width.
                targetHeight = containerHeight
                targetWidth =
                    (containerHeight.toFloat() * videoAspect + 0.5f).toInt().coerceAtLeast(containerWidth)
            } else {
                // Video relatively taller: match the width, overflow the height.
                targetWidth = containerWidth
                targetHeight =
                    (containerWidth.toFloat() / videoAspect + 0.5f).toInt().coerceAtLeast(containerHeight)
            }
            val placeable =
                measurable.measure(
                    Constraints.fixed(
                        targetWidth.coerceAtLeast(1),
                        targetHeight.coerceAtLeast(1),
                    )
                )
            layout(containerWidth, containerHeight) {
                placeable.place(
                    -((targetWidth - containerWidth) / 2),
                    -((targetHeight - containerHeight) / 2),
                )
            }
        }
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

/** Still or animated (GIF/WebP via Coil's animated decoder) user canvas; no video pipeline needed. */
@Composable
private fun CustomImageCanvas(
    url: String,
    visible: Boolean,
    resizeMode: Int,
    onFrameCaptured: ((Bitmap?) -> Unit)?,
    modifier: Modifier,
) {
    val frameCallback by rememberUpdatedState(onFrameCaptured)
    // Snapshot consumers blur a captured video frame; with no frame they keep the cover behind
    // this image, which is the right backdrop for a still canvas.
    LaunchedEffect(url) { frameCallback?.invoke(null) }
    if (!visible) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = resizeMode.toContentScale(),
        modifier = modifier.clipToBounds(),
    )
}

private fun ExoPlayer.setCanvasPlayback(isPlaying: Boolean) {
    if (isPlaying) {
        if (playbackState == Player.STATE_ENDED) seekTo(0)
        if (playbackState == Player.STATE_IDLE && mediaItemCount > 0) prepare()
        play()
    } else {
        pause()
    }
}

private const val CanvasPlaybackLogTag = "CanvasPlayback"
private const val CanvasPlaybackUserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
