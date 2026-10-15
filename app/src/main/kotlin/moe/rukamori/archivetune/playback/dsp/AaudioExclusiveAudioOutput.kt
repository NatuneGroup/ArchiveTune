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

import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A Media3 [AudioOutput] over an already opened, exclusive AAudio stream (see
 * [UsbExclusiveAudioOutputProvider], which opens it so a refusal can fall back before Media3 holds
 * an output). The stream is always float; 16-bit input is widened on the way in, which is exact.
 *
 * Positions are relative to the last [flush], as Media3 expects: AAudio's own frame counters
 * keep running across a flush.
 */
internal class AaudioExclusiveAudioOutput(
    private val config: OutputConfig,
    private val stream: AaudioNativeStream,
    private val onWriteFailed: (errorCode: Int) -> Unit,
    private val onReleased: (AaudioExclusiveAudioOutput) -> Unit,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private val isFloat = config.encoding == C.ENCODING_PCM_FLOAT
    private val channelCount = stream.channelCount()
    private val bytesPerFrame = (if (isFloat) 4 else 2) * channelCount
    private val streamSampleRate = stream.sampleRate()

    private var volume = 1f
    private var playing = false
    private var stopped = false
    private var released = false
    private var failed = false
    private var positionAdvancingReported = false
    private var totalWrittenFrames = 0L
    private var flushBaseFrames = 0L
    private var lastPositionFrames = 0L
    private var lastXRunCount = 0

    @Synchronized
    override fun play() {
        if (released || failed) return
        stream.start()
        playing = true
        stopped = false
    }

    @Synchronized
    override fun pause() {
        if (released) return
        stream.pause()
        playing = false
    }

    @Synchronized
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released || failed) {
            throw AudioOutput.WriteException(AaudioNativeStream.ERROR_INVALID_STATE, true)
        }
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) {
            buffer.position(buffer.limit())
            return true
        }
        // Never block: this runs on the playback thread, which would stop decoding while the DAC
        // drains. A short write returns false and Media3 offers the rest on its next pass.
        val written = stream.write(buffer, frames, channelCount, isFloat, volume, timeoutMs = 0)
        if (written < 0) {
            failed = true
            playing = false
            Log.w(TAG, "Exclusive AAudio write failed (code=$written); the sink will rebuild its output")
            onWriteFailed(written)
            // Recoverable: Media3 then recreates the output, and the provider hands it the
            // standard AudioTrack path once this device is marked as refused.
            throw AudioOutput.WriteException(written, true)
        }
        if (written > 0) {
            buffer.position(buffer.position() + written * bytesPerFrame)
            totalWrittenFrames += written
        }
        reportUnderruns()
        return written >= frames
    }

    @Synchronized
    override fun flush() {
        if (released) return
        stream.pause()
        stream.flush()
        playing = false
        stopped = false
        positionAdvancingReported = false
        flushBaseFrames = totalWrittenFrames
        lastPositionFrames = 0L
    }

    @Synchronized
    override fun stop() {
        if (released) return
        // AAudio plays out what is already buffered before it stops, which is what Media3 wants
        // at end of stream.
        stream.stop()
        stopped = true
    }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        playing = false
        stream.release()
        listeners.forEach { it.onReleased() }
        listeners.clear()
        onReleased(this)
    }

    /** Closes the stream at once so a replacement output can claim the device exclusively. */
    @Synchronized
    fun closeStreamNow() {
        if (released) return
        playing = false
        failed = true
        stream.release()
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun isOffloadedPlayback(): Boolean = false

    // Media3's own session stays the reported one: the AAudio stream carries no effects session,
    // and handing back a different id would re-attach the app's effects to nothing.
    override fun getAudioSessionId(): Int = config.audioSessionId

    override fun getSampleRate(): Int = streamSampleRate

    @Synchronized
    override fun getBufferSizeInFrames(): Long = if (released || failed) 0L else stream.bufferSizeFrames()

    @Synchronized
    override fun getPositionUs(): Long {
        val pendingFrames = totalWrittenFrames - flushBaseFrames
        if (!released && !failed) {
            val raw = stream.positionFrames(extrapolate = playing || stopped)
            val relative =
                when {
                    raw >= 0 -> raw - flushBaseFrames
                    // A stopped stream stops reporting timestamps once it has drained.
                    stopped -> pendingFrames
                    else -> lastPositionFrames
                }
            lastPositionFrames = relative.coerceIn(lastPositionFrames, maxOf(lastPositionFrames, pendingFrames))
        }
        if (playing && !positionAdvancingReported && lastPositionFrames > 0) {
            positionAdvancingReported = true
            val playoutStartMs = System.currentTimeMillis() - framesToUs(lastPositionFrames) / 1000
            listeners.forEach { it.onPositionAdvancing(playoutStartMs) }
        }
        return framesToUs(lastPositionFrames)
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    // Speed and pitch are applied by Sonic in the processor chain (usePlaybackParameters = false).
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    private fun framesToUs(frames: Long): Long =
        if (streamSampleRate > 0) frames * 1_000_000L / streamSampleRate else 0L

    private fun reportUnderruns() {
        val xRuns = stream.xRunCount()
        if (xRuns > lastXRunCount) {
            lastXRunCount = xRuns
            listeners.forEach { it.onUnderrun() }
        }
    }

    private companion object {
        const val TAG = "UsbExclusiveAudio"
    }
}
