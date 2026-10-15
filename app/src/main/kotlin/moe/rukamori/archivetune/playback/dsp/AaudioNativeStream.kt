/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer

/** JNI host for `AaudioExclusiveStream` in libarchivetune_dsp (loaded by [FloatDsp]). */
class AaudioNativeStream {
    private var handle: Long = 0L

    val isOpen: Boolean
        get() = handle != 0L

    /** Returns 0 or a negative AAudio error code. */
    fun open(
        sampleRate: Int,
        channels: Int,
        deviceId: Int,
        exclusive: Boolean,
        usage: Int = USAGE_MEDIA,
        contentType: Int = CONTENT_TYPE_MUSIC,
        bufferCapacityFrames: Int = 0,
    ): Int {
        if (!FloatDsp.available) return ERROR_UNAVAILABLE
        release()
        handle = nativeOpen(sampleRate, channels, deviceId, exclusive, usage, contentType, bufferCapacityFrames)
        if (handle == 0L) {
            val error = nativeLastOpenError()
            return if (error != 0) error else ERROR_INTERNAL
        }
        return 0
    }

    /** Frames written (possibly fewer than [frames]), or a negative AAudio error. */
    fun write(
        buffer: ByteBuffer,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
        volume: Float,
        timeoutMs: Int,
    ): Int =
        if (handle == 0L) {
            ERROR_INVALID_STATE
        } else {
            nativeWrite(handle, buffer, buffer.position(), frames, channels, isFloat, volume, timeoutMs)
        }

    fun start() {
        if (handle != 0L) nativeStart(handle)
    }

    fun pause() {
        if (handle != 0L) nativePause(handle)
    }

    fun flush() {
        if (handle != 0L) nativeFlush(handle)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun release() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            nativeClose(current)
            nativeRelease(current)
        }
    }

    /** Presented frames, or -1 while the stream reports no timestamp. */
    fun positionFrames(extrapolate: Boolean): Long =
        if (handle == 0L) -1L else nativePositionFrames(handle, extrapolate)

    fun sampleRate(): Int = if (handle == 0L) 0 else nativeSampleRate(handle)

    fun channelCount(): Int = if (handle == 0L) 0 else nativeChannelCount(handle)

    fun xRunCount(): Int = if (handle == 0L) 0 else nativeXRunCount(handle)

    fun bufferCapacityFrames(): Long = if (handle == 0L) 0L else nativeBufferCapacityFrames(handle)

    fun bufferSizeFrames(): Long = if (handle == 0L) 0L else nativeBufferSizeFrames(handle)

    fun isExclusive(): Boolean = handle != 0L && nativeIsExclusive(handle)

    private external fun nativeOpen(
        sampleRate: Int,
        channels: Int,
        deviceId: Int,
        exclusive: Boolean,
        usage: Int,
        contentType: Int,
        bufferCapacityFrames: Int,
    ): Long

    private external fun nativeLastOpenError(): Int

    private external fun nativeClose(handle: Long)

    private external fun nativeRelease(handle: Long)

    private external fun nativeStart(handle: Long)

    private external fun nativePause(handle: Long)

    private external fun nativeFlush(handle: Long)

    private external fun nativeStop(handle: Long)

    private external fun nativeWrite(
        handle: Long,
        buffer: ByteBuffer,
        offsetBytes: Int,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
        volume: Float,
        timeoutMs: Int,
    ): Int

    private external fun nativePositionFrames(handle: Long, extrapolate: Boolean): Long

    private external fun nativeSampleRate(handle: Long): Int

    private external fun nativeChannelCount(handle: Long): Int

    private external fun nativeXRunCount(handle: Long): Int

    private external fun nativeBufferCapacityFrames(handle: Long): Long

    private external fun nativeBufferSizeFrames(handle: Long): Long

    private external fun nativeIsExclusive(handle: Long): Boolean

    companion object {
        // AAudio's aaudio_usage_t / aaudio_content_type_t values (AAUDIO_USAGE_MEDIA,
        // AAUDIO_CONTENT_TYPE_MUSIC).
        const val USAGE_MEDIA = 1
        const val CONTENT_TYPE_MUSIC = 2

        const val ERROR_INVALID_STATE = -895
        const val ERROR_INTERNAL = -896
        const val ERROR_UNAVAILABLE = -889
    }
}
