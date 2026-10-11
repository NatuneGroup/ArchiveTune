package tf.monochrome.android.audio.stretch

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
@OptIn(UnstableApi::class)
class StretchAudioProcessor @Inject constructor() : AudioProcessor {
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET

    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var handle = 0L
    private var maxBlock = 0

    private var nativeIn: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var nativeOut: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    @Volatile private var semitones: Float = 0f

    private val engaged: Boolean get() = abs(semitones) >= SEMITONE_DEADZONE

    private var wasEngaged = false

    fun setSemitones(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(MIN_SEMITONES, MAX_SEMITONES)
        if (clamped == semitones) return
        semitones = clamped
        val h = handle
        if (h != 0L) StretchNative.nativeSetSemitones(h, clamped)
    }

    fun getSemitones(): Float = semitones

    @Volatile private var engine: PitchEngine = PitchEngine.VOCODER
    @Volatile private var quality: PitchQuality = PitchQuality.BALANCED

    fun setEngine(engine: PitchEngine, quality: PitchQuality) {
        if (engine == this.engine && quality == this.quality) return
        this.engine = engine
        this.quality = quality
        val h = handle
        if (h != 0L) StretchNative.nativeSetEngine(h, engine.nativeId, quality.nativeId)
    }

    fun getEngine(): PitchEngine = engine

    fun getQuality(): PitchQuality = quality

    fun latencyFrames(): Int {
        val h = handle
        return if (h != 0L && engaged) StretchNative.nativeLatencyFrames(h) else 0
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        StretchNative.isAvailable &&
            (pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val h = handle
        if (h == 0L || !engaged) {
            wasEngaged = false
            passThrough(inputBuffer)
            return
        }
        if (!wasEngaged) {
            if (!StretchNative.nativeReset(h)) {
                passThrough(inputBuffer)
                return
            }
            wasEngaged = true
        }
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * channels
        val totalFrames = inputBuffer.remaining() / frameSize
        if (totalFrames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        ensureOutput(totalFrames * frameSize)
        var done = 0
        val start = inputBuffer.position()
        while (done < totalFrames) {
            val n = minOf(maxBlock, totalFrames - done)

            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until n * channels) {
                    nativeIn.putFloat(
                        i * 4,
                        inputBuffer.getFloat(start + (done * channels + i) * 4),
                    )
                }
            } else {
                for (i in 0 until n * channels) {
                    val s = inputBuffer.getShort(start + (done * channels + i) * 2)
                    nativeIn.putFloat(i * 4, s.toFloat() / 32768f)
                }
            }

            StretchNative.nativeProcess(h, nativeIn, nativeOut, n)

            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until n * channels) {
                    outputBuffer.putFloat(
                        (done * channels + i) * 4,
                        nativeOut.getFloat(i * 4),
                    )
                }
            } else {
                for (i in 0 until n * channels) {
                    val v = nativeOut.getFloat(i * 4)
                    outputBuffer.putShort(
                        (done * channels + i) * 2,
                        (v * 32768f).toInt().coerceIn(-32768, 32767).toShort(),
                    )
                }
            }
            done += n
        }
        inputBuffer.position(start + totalFrames * frameSize)
        outputBuffer.position(0)
        outputBuffer.limit(totalFrames * frameSize)
    }

    private fun passThrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        ensureOutput(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        wasEngaged = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
            releaseEngine()
        }
        if (StretchNative.isAvailable && inputFormat != AudioFormat.NOT_SET && handle == 0L) {
            handle = StretchNative.nativeCreate(
                inputFormat.channelCount, inputFormat.sampleRate,
            )
            if (handle != 0L) {
                maxBlock = StretchNative.nativeMaxBlockFrames().coerceAtLeast(1)
                StretchNative.nativeSetSemitones(handle, semitones)
                StretchNative.nativeSetEngine(handle, engine.nativeId, quality.nativeId)
                val bytes = maxBlock * inputFormat.channelCount * 4
                nativeIn = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                nativeOut = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            }
        } else if (handle != 0L) {
            StretchNative.nativeReset(handle)
        }
    }

    override fun reset() {
        releaseOutput()
        inputEnded = false
        wasEngaged = false
        releaseEngine()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
    }

    private fun releaseEngine() {
        if (handle != 0L) {
            StretchNative.nativeDestroy(handle)
            handle = 0L
        }
        nativeIn = AudioProcessor.EMPTY_BUFFER
        nativeOut = AudioProcessor.EMPTY_BUFFER
    }

    private fun releaseOutput() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        buffer = AudioProcessor.EMPTY_BUFFER
    }

    private fun ensureOutput(bytes: Int) {
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
    }

    private companion object {
        const val MIN_SEMITONES = -24f
        const val MAX_SEMITONES = 24f
        const val SEMITONE_DEADZONE = 0.01f
    }
}
