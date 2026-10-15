/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.player

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.PlaceholderSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Port of Flow's [io.github.aedev.flow.player.surface.SurfaceManager]. */
@UnstableApi
internal class VideoSurfaceManager {
    companion object {
        private const val TAG = "VideoSurfaceManager"
    }

    private var surfaceHolder: SurfaceHolder? = null
    private var placeholderSurface: PlaceholderSurface? = null

    /**
     * True when the current surface has been successfully attached and is
     * presenting frames. Read by [VideoArtworkSurface] to decide whether to
     * render the loading spinner overlay.
     */
    @Volatile
    var isSurfaceReady: Boolean = false
        private set

    private val surfaceReadyFlow = MutableStateFlow(false)

    /**
     * Tracks whether a real (non-placeholder) surface is currently attached —
     * used by the attach path's dedup check to avoid redundant
     * `setVideoSurface` calls. Android can reuse the same SurfaceHolder Java
     * object while replacing the underlying native buffer queue, so a plain
     * equality check isn't enough — callers must pass `forceAttach = true`
     * from `surfaceCreated` / `surfaceChanged` callbacks to bypass the dedup.
     */
    private val realSurfaceAttached = AtomicBoolean(false)

    /** Get the current surface holder (may be null). */
    fun getSurfaceHolder(): SurfaceHolder? = surfaceHolder

    /**
     * Attach a video surface to the player. Uses Flow's `getSurface()` approach like NewPipe for
     * better compatibility across surface types.
     */
    fun attachVideoSurface(
        holder: SurfaceHolder?,
        player: ExoPlayer?,
        forceAttach: Boolean = false,
    ): Boolean {
        if (holder == null) {
            Log.w(TAG, "attachVideoSurface called with null holder")
            surfaceHolder = null
            return false
        }

        surfaceHolder = holder
        Log.d(TAG, "attachVideoSurface: stored holder. Player instance is ${if (player == null) "null" else "not null"}")

        // A real surface is back, drop any placeholder we were using
        placeholderSurface?.let { placeholder ->
            runCatching { placeholder.release() }
            placeholderSurface = null
        }

        if (player == null) {
            Log.d(TAG, "Player not initialized yet; surface will be attached later")
            return false
        }

        return runCatching {
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                if (!forceAttach && realSurfaceAttached.get()) {
                    val existingSurface = runCatching { surfaceHolder?.surface }.getOrNull()
                    if (existingSurface != null && existingSurface.isValid && existingSurface == surface) {
                        Log.d(TAG, "Surface already attached and ready — skipping redundant setVideoSurface (update fallback)")
                        return@runCatching true
                    }
                }
                Log.d(TAG, "Attempting to attach surface ${surface.hashCode()} to player (forceAttach=$forceAttach)")
                player.setVideoSurface(surface)
                realSurfaceAttached.set(true)
                Log.d(TAG, "Surface attached to player via getSurface() (NewPipe approach)")
                surfaceReadyFlow.value = true
                setSurfaceReady(true)
                true
            } else {
                Log.w(TAG, "Surface holder not yet valid; awaiting callback")
                false
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to bind surface to player", error)
            false
        }
    }

    /** Detach the video surface from the player. */
    fun detachVideoSurface(
        holder: SurfaceHolder?,
        player: ExoPlayer?,
        context: Context?,
    ) {
        // If specific holder provided, check if it matches current
        if (holder != null && holder != surfaceHolder) {
            Log.d(TAG, "detachVideoSurface ignored: holder mismatch (stale surface)")
            return
        }

        Log.d(TAG, "detachVideoSurface called")
        surfaceHolder = null
        realSurfaceAttached.set(false)

        try {
            if (player != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context != null) {
                    // Try to reuse the placeholder surface if valid
                    if (placeholderSurface == null || placeholderSurface?.isValid == false) {
                        try {
                            runCatching { placeholderSurface?.release() }
                            placeholderSurface = PlaceholderSurface.newInstance(context, false)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create placeholder surface", e)
                        }
                    }

                    placeholderSurface?.let {
                        player.setVideoSurface(it)
                        Log.d(TAG, "Attached placeholder surface (surface detached temporarily)")
                    } ?: run {
                        player.clearVideoSurface()
                    }
                } else {
                    player.clearVideoSurface()
                }
            } else {
                player?.clearVideoSurface()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach placeholder surface", e)
        }

        setSurfaceReady(false)
    }

    /**
     * Reattach surface to player if holder is still valid. Used after player
     * initialization or recreation.
     */
    fun reattachSurfaceIfValid(player: ExoPlayer?): Boolean {
        surfaceHolder?.let { holder ->
            return runCatching {
                val surface = holder.surface
                if (surface != null && surface.isValid) {
                    player?.setVideoSurface(surface)
                    Log.d(TAG, "Reattached preserved surface ${surface.hashCode()}")
                    setSurfaceReady(true)
                    true
                } else {
                    Log.w(TAG, "Surface holder present but surface invalid or null")
                    false
                }
            }.getOrElse { e ->
                Log.e(TAG, "Failed to reattach surface: ${e.message}", e)
                false
            }
        }
        return false
    }

    /** Set surface readiness state. */
    fun setSurfaceReady(ready: Boolean) {
        isSurfaceReady = ready
        surfaceReadyFlow.value = ready
        Log.d(TAG, "Surface ready: $ready")
    }

    /**
     * Suspend function that waits for a real surface to be attached. Returns
     * true if surface became ready within timeout.
     */
    suspend fun awaitSurfaceReady(timeoutMillis: Long = 1000): Boolean {
        if (surfaceHolder == null) {
            Log.d(TAG, "No SurfaceHolder (TextureView mode) — surface assumed ready")
            isSurfaceReady = true
            surfaceReadyFlow.value = true
            return true
        }

        // Check if surfaceHolder is valid
        val validSurface = runCatching { surfaceHolder?.surface?.isValid == true }.getOrDefault(false)
        if (validSurface) {
            Log.d(TAG, "Surface already ready, proceeding immediately")
            surfaceReadyFlow.value = true
            return true
        }

        Log.d(TAG, "Waiting for surface to be ready (timeout: ${timeoutMillis}ms)...")

        val result =
            withTimeoutOrNull(timeoutMillis) {
                surfaceReadyFlow.first { it }
                true
            }

        return if (result == true) {
            Log.d(TAG, "Surface became ready!")
            true
        } else {
            Log.w(TAG, "Timeout waiting for surface after ${timeoutMillis}ms")
            val nowValid = runCatching { surfaceHolder?.surface?.isValid == true }.getOrDefault(false)
            if (nowValid) {
                Log.d(TAG, "Surface valid now despite timeout - proceeding")
                surfaceReadyFlow.value = true
                return true
            }
            false
        }
    }

    /** Check if the current surface is valid and ready for rendering. */
    fun isSurfaceValid(): Boolean =
        runCatching { surfaceHolder?.surface?.isValid == true }.getOrDefault(false)

    /**
     * Release all surface resources. Called when the owning composable leaves
     * the composition tree.
     */
    fun release(player: ExoPlayer?) {
        try {
            player?.clearVideoSurface()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear surface during release", e)
        }

        placeholderSurface?.let { surface ->
            runCatching { surface.release() }
        }
        placeholderSurface = null
        realSurfaceAttached.set(false)

        isSurfaceReady = false
        surfaceReadyFlow.value = false
        surfaceHolder = null
    }
}
