/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer

object FloatDsp {
    val available: Boolean = runCatching { System.loadLibrary("archivetune_dsp") }.isSuccess

    @JvmStatic
    external fun nativeCreate(sampleRate: Int, channels: Int): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

    @JvmStatic
    external fun nativeSetEngaged(handle: Long, engaged: Boolean)

    @JvmStatic
    external fun nativeReset(handle: Long)

    @JvmStatic
    external fun nativeSetLimiterCeiling(handle: Long, ceilingDb: Float)

    @JvmStatic
    external fun nativeProcessShortToFloat(
        handle: Long,
        inBuffer: ByteBuffer,
        inOffsetBytes: Int,
        outBuffer: ByteBuffer,
        frames: Int,
        channels: Int,
    )

    @JvmStatic
    external fun nativeProcessFloatToFloat(
        handle: Long,
        inBuffer: ByteBuffer,
        inOffsetBytes: Int,
        outBuffer: ByteBuffer,
        frames: Int,
        channels: Int,
    )

    @JvmStatic
    external fun nativeProcessShortToShort(
        handle: Long,
        inBuffer: ByteBuffer,
        inOffsetBytes: Int,
        outBuffer: ByteBuffer,
        frames: Int,
        channels: Int,
    )
}
