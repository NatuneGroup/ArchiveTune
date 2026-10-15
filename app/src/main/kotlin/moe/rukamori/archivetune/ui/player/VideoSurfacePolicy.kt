/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.player

import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Port of Flow's [io.github.aedev.flow.player.surface.VideoSurfacePolicy]. */
@UnstableApi
object VideoSurfacePolicy {
    /** Use SurfaceView for SDK 34+ (supports seamless surface switching). */
    fun usesSurfaceView(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Whether we can restore the video output without a same-position seek after a
     * surface re-attach. Flow's original condition:
     *   isDisplayInteractive && (!usesSurfaceView(sdkInt) || isSurfaceValid)
     */
    fun canRestoreVideoOutput(
        sdkInt: Int,
        isDisplayInteractive: Boolean,
        isSurfaceValid: Boolean,
    ): Boolean = isDisplayInteractive && (!usesSurfaceView(sdkInt) || isSurfaceValid)

    /** Whether a same-position seek is needed after a destroyed video surface comes back. */
    fun shouldResyncOnSurfaceReattach(
        playWhenReady: Boolean,
        isLive: Boolean,
        playbackState: Int,
    ): Boolean =
        !playWhenReady && !isLive &&
            (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
}
