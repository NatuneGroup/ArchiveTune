@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class FloatDspProcessor : BaseAudioProcessor() {
    @Volatile
    var engaged: Boolean = false
        private set

    @Volatile
    var outputFloat: Boolean = false

    @Volatile
    private var activeOutputFloat: Boolean = false

    @Volatile
    private var dspHandle: Long = 0L

    @Volatile
    private var handleFormat: AudioProcessor.AudioFormat? = null

    private val handleLock = Any()

    fun setEngaged(value: Boolean) {
        engaged = value
        synchronized(handleLock) {
            if (dspHandle != 0L) FloatDsp.nativeSetEngaged(dspHandle, value)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (moe.rukamori.archivetune.playback.dsp.BitPerfectRuntime.chainBypassActive) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (!FloatDsp.available) return AudioProcessor.AudioFormat.NOT_SET
        val encoding = inputAudioFormat.encoding
        if (engaged && (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)) {
            activeOutputFloat = outputFloat
            dropHandleIfFormatChanged(inputAudioFormat)
            return AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
            )
        }
        activeOutputFloat = false
        return AudioProcessor.AudioFormat.NOT_SET
    }

    private fun dropHandleIfFormatChanged(format: AudioProcessor.AudioFormat) {
        val current = handleFormat ?: return
        if (sameFormat(current, format)) return
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                FloatDsp.nativeRelease(dspHandle)
                dspHandle = 0L
            }
        }
        handleFormat = null
    }

    private fun sameFormat(
        a: AudioProcessor.AudioFormat?,
        b: AudioProcessor.AudioFormat,
    ): Boolean = a != null && a.sampleRate == b.sampleRate && a.channelCount == b.channelCount && a.encoding == b.encoding

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!engaged || dspHandle == 0L && !ensureHandle()) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        if (!sameFormat(handleFormat, inputAudioFormat)) {
            releaseHandleForRebuild()
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        val inputFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (inputFloat) 4 else 2
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0 || (!inputFloat && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        val inOffset = inputBuffer.position()

        if (activeOutputFloat) {
            val outBytes = frames * channels * 4
            val output = replaceOutputBuffer(outBytes)
            if (inputFloat) {
                FloatDsp.nativeProcessFloatToFloat(dspHandle, inputBuffer, inOffset, output, frames, channels)
            } else {
                FloatDsp.nativeProcessShortToFloat(dspHandle, inputBuffer, inOffset, output, frames, channels)
            }

            output.position(0)
            output.limit(outBytes)
            inputBuffer.position(inputBuffer.limit())
        } else {
            if (inputFloat) {
                replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
                return
            }
            val outBytes = frames * channels * 2
            val output = replaceOutputBuffer(outBytes)
            FloatDsp.nativeProcessShortToShort(dspHandle, inputBuffer, inOffset, output, frames, channels)
            output.position(0)
            output.limit(outBytes)
            inputBuffer.position(inputBuffer.limit())
        }
    }

    private fun ensureHandle(): Boolean {
        if (!FloatDsp.available) return false
        synchronized(handleLock) {
            dspHandle = FloatDsp.nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            if (dspHandle == 0L) return false
            handleFormat = inputAudioFormat
            FloatDsp.nativeSetEngaged(dspHandle, engaged)
        }
        return true
    }

    private fun releaseHandleForRebuild() {
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                FloatDsp.nativeRelease(dspHandle)
                dspHandle = 0L
            }
        }
        handleFormat = null
    }

    override fun onFlush() {
        if (dspHandle != 0L) FloatDsp.nativeReset(dspHandle)
    }

    override fun onReset() {
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                FloatDsp.nativeRelease(dspHandle)
                dspHandle = 0L
            }
        }
        handleFormat = null
        activeOutputFloat = false
    }

    @Suppress("FinalPrivate")
    protected fun finalize() {
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                runCatching { FloatDsp.nativeRelease(dspHandle) }
                dspHandle = 0L
            }
        }
    }
}
