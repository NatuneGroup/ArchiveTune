/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import timber.log.Timber
import kotlin.math.roundToInt

private const val MaxFrameCaptureAttempts = 5
private const val FrameCaptureRetryDelayMs = 100L

internal class CanvasSnapshotView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: Player? = null
    private var output: Surface? = null
    private var captured = false
    private var captureAttempts = 0
    private var onFrame: ((Bitmap?) -> Unit)? = null
    private val videoListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = updateTransform()
    }

    // getBitmap can return null (or throw) while the surface texture is still initializing, so a
    // failed capture retries briefly before reporting the failure — one missed read must not
    // strand a snapshot consumer without a still for the whole song.
    private val capture: Runnable = Runnable {
        if (isAvailable && output != null) {
            val bitmap: Bitmap? =
                try {
                    val scale = 256f / maxOf(width, height, 1)
                    getBitmap(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                } catch (error: RuntimeException) {
                    Timber.w(error, "Canvas frame capture failed")
                    null
                }
            if (bitmap != null) {
                onFrame?.invoke(bitmap)
            } else {
                Timber.w("Canvas frame capture returned no image")
                if (++captureAttempts <= MaxFrameCaptureAttempts) {
                    postDelayed(capture, FrameCaptureRetryDelayMs)
                } else {
                    onFrame?.invoke(null)
                }
            }
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun bind(player: Player, onFrame: (Bitmap?) -> Unit) {
        this.player = player
        this.onFrame = onFrame
        player.addListener(videoListener)
    }

    fun release() {
        removeCallbacks(capture)
        player?.removeListener(videoListener)
        output?.let { player?.clearVideoSurface(it) }
        output?.release()
        output = null
        player = null
        onFrame = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        output = Surface(surface).also { player?.setVideoSurface(it) }
        updateTransform()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        removeCallbacks(capture)
        captured = false
        output?.let { player?.clearVideoSurface(it) }
        output?.release()
        output = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!captured && width > 0 && height > 0) {
            captured = true
            post(capture)
        }
    }

    private fun updateTransform() {
        val video = player?.videoSize ?: return
        if (video.width <= 0 || video.height <= 0 || width <= 0 || height <= 0) return
        val videoRatio = video.width * video.pixelWidthHeightRatio / video.height
        val viewRatio = width.toFloat() / height
        val scaleX = maxOf(1f, videoRatio / viewRatio)
        val scaleY = maxOf(1f, viewRatio / videoRatio)
        setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }
}
