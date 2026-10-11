package tf.monochrome.android.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(UnstableApi::class)
class MixBusProcessor @Inject constructor(
    private val inflator: InflatorEffect,
    private val compressor: CompressorEffect,
    private val crossfeed: CrossfeedEffect,
) : AudioProcessor {
    private var enginePtr: Long = 0L
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    private var scratchInL = FloatArray(0)
    private var scratchInR = FloatArray(0)
    private var scratchOutL = FloatArray(0)
    private var scratchOutR = FloatArray(0)

    private var chunkScratchInL = FloatArray(0)
    private var chunkScratchInR = FloatArray(0)
    private var chunkScratchOutL = FloatArray(0)
    private var chunkScratchOutR = FloatArray(0)

    private var ditherState: Long = 1L

    private external fun nativeCreate(sampleRate: Int, maxBlockSize: Int): Long
    private external fun nativeDestroy(enginePtr: Long)

    private external fun nativeReconfigure(enginePtr: Long, sampleRate: Int, maxBlockSize: Int)
    private external fun nativeProcess(
        enginePtr: Long,
        inputL: FloatArray, inputR: FloatArray,
        outputL: FloatArray, outputR: FloatArray,
        numFrames: Int
    )

    external fun nativeSetBusGain(enginePtr: Long, busIndex: Int, gainDb: Float)
    external fun nativeSetBusPan(enginePtr: Long, busIndex: Int, pan: Float)
    external fun nativeSetBusMute(enginePtr: Long, busIndex: Int, muted: Boolean)
    external fun nativeSetBusSolo(enginePtr: Long, busIndex: Int, soloed: Boolean)
    external fun nativeAddPlugin(enginePtr: Long, busIndex: Int, slotIndex: Int, pluginType: Int): Int
    external fun nativeRemovePlugin(enginePtr: Long, busIndex: Int, slotIndex: Int)
    external fun nativeMovePlugin(enginePtr: Long, busIndex: Int, fromSlot: Int, toSlot: Int)
    external fun nativeSetParameter(enginePtr: Long, busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float)
    external fun nativeSetPluginBypassed(enginePtr: Long, busIndex: Int, slotIndex: Int, bypassed: Boolean)
    external fun nativeSetPluginDryWet(enginePtr: Long, busIndex: Int, slotIndex: Int, dryWet: Float)

    external fun nativeSetPluginOversampling(enginePtr: Long, busIndex: Int, slotIndex: Int, factor: Int)
    external fun nativeSetBusInputEnabled(enginePtr: Long, busIndex: Int, enabled: Boolean)
    external fun nativeGetBusLevels(enginePtr: Long, outLevels: FloatArray)

    external fun nativeGetPluginMeters(enginePtr: Long, busIndex: Int, outMeters: FloatArray)

    external fun nativeGetBusWaveform(enginePtr: Long, busIndex: Int, outWave: FloatArray): Int
    external fun nativeGetAndResetClipped(enginePtr: Long): Boolean
    external fun nativeResetPluginState(enginePtr: Long)
    external fun nativeSetMixBypassed(enginePtr: Long, bypassed: Boolean)
    external fun nativeGetStateJson(enginePtr: Long): String
    external fun nativeLoadStateJson(enginePtr: Long, stateJson: String)

    companion object {
        init { DspNativeLoader.ensureLoaded() }

        const val MAX_BLOCK_SIZE = 16384
    }

    fun getEnginePtr(): Long = enginePtr

    fun setMixBypassed(bypassed: Boolean) {
        val ptr = enginePtr
        if (ptr != 0L) nativeSetMixBypassed(ptr, bypassed)
    }

    @Volatile
    private var blockSize: Int = 1024

    @Volatile
    private var bypassed: Boolean = false

    fun setBypassed(b: Boolean) {
        bypassed = b
    }

    fun setBlockSize(size: Int) {
        val clamped = size.coerceIn(64, MAX_BLOCK_SIZE)
        if (clamped == blockSize) return
        blockSize = clamped
        val ptr = enginePtr
        if (ptr != 0L && inputFormat != AudioFormat.NOT_SET) {
            nativeReconfigure(ptr, inputFormat.sampleRate, MAX_BLOCK_SIZE)
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount > 2) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        pendingFormat = inputAudioFormat

        return if (inputAudioFormat.channelCount == 1) {
            AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
        } else {
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (bypassed || enginePtr == 0L) {
            val size = inputBuffer.remaining()

            if (size == 0 || inputBuffer === outputBuffer) return
            if (outputBuffer.capacity() < size) {
                outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val encoding = inputFormat.encoding
        val inputChannels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val numFrames = inputBuffer.remaining() / frameSize

        if (numFrames <= 0) return

        if (scratchInL.size < numFrames) {
            scratchInL = FloatArray(numFrames)
            scratchInR = FloatArray(numFrames)
            scratchOutL = FloatArray(numFrames)
            scratchOutR = FloatArray(numFrames)
        }

        val startPos = inputBuffer.position()
        if (inputChannels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getFloat(startPos + i * 4)
                    scratchInL[i] = s
                    scratchInR[i] = s
                }
            } else {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getShort(startPos + i * 2).toFloat() / 32768f
                    scratchInL[i] = s
                    scratchInR[i] = s
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 8
                    scratchInL[i] = inputBuffer.getFloat(off)
                    scratchInR[i] = inputBuffer.getFloat(off + 4)
                }
            } else {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 4
                    scratchInL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                    scratchInR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)

        val chunk = blockSize
        val needChunkScratch = chunk < numFrames
        if (needChunkScratch && chunkScratchInL.size < chunk) {
            chunkScratchInL = FloatArray(chunk)
            chunkScratchInR = FloatArray(chunk)
            chunkScratchOutL = FloatArray(chunk)
            chunkScratchOutR = FloatArray(chunk)
        }

        var processed = 0
        while (processed < numFrames) {
            val n = minOf(chunk, numFrames - processed)
            if (needChunkScratch) {
                System.arraycopy(scratchInL, processed, chunkScratchInL, 0, n)
                System.arraycopy(scratchInR, processed, chunkScratchInR, 0, n)
                nativeProcess(enginePtr, chunkScratchInL, chunkScratchInR, chunkScratchOutL, chunkScratchOutR, n)
                inflator.processArrays(chunkScratchOutL, chunkScratchOutR, n)
                compressor.processArrays(chunkScratchOutL, chunkScratchOutR, n)
                crossfeed.processArrays(chunkScratchOutL, chunkScratchOutR, n)
                System.arraycopy(chunkScratchOutL, 0, scratchOutL, processed, n)
                System.arraycopy(chunkScratchOutR, 0, scratchOutR, processed, n)
            } else {
                nativeProcess(enginePtr, scratchInL, scratchInR, scratchOutL, scratchOutR, n)
                inflator.processArrays(scratchOutL, scratchOutR, n)
                compressor.processArrays(scratchOutL, scratchOutR, n)
                crossfeed.processArrays(scratchOutL, scratchOutR, n)
            }
            processed += n
        }

        val useL = scratchOutL
        val useR = scratchOutR

        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until numFrames) {
                val off = i * 8
                outputBuffer.putFloat(off, useL[i])
                outputBuffer.putFloat(off + 4, useR[i])
            }
        } else {
            for (i in 0 until numFrames) {
                val d1 = nextDitherSample()
                val d2 = nextDitherSample()
                val dither = d1 + d2
                val off = i * 4
                outputBuffer.putShort(off, ((useL[i] * 32768f) + dither).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, ((useR[i] * 32768f) + dither).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        outputBuffer.position(0)
        outputBuffer.limit(outBytes)
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false

        if (pendingFormat == AudioFormat.NOT_SET) return

        val formatChanged = inputFormat == AudioFormat.NOT_SET
            || inputFormat.sampleRate != pendingFormat.sampleRate
            || inputFormat.encoding != pendingFormat.encoding
            || inputFormat.channelCount != pendingFormat.channelCount

        if (formatChanged) {
            inputFormat = pendingFormat
            if (enginePtr == 0L) {
                enginePtr = nativeCreate(inputFormat.sampleRate, MAX_BLOCK_SIZE)
            } else {
                nativeReconfigure(enginePtr, inputFormat.sampleRate, MAX_BLOCK_SIZE)
            }

            inflator.prepare(inputFormat.sampleRate.toDouble(), 2)
            compressor.prepare(inputFormat.sampleRate.toDouble(), 2)
            crossfeed.prepare(inputFormat.sampleRate.toDouble())

            _engineReady.value = false
            _engineReady.value = true
        }

        pendingFormat = AudioFormat.NOT_SET
    }

    override fun reset() {
        _engineReady.value = false
        flush()
        if (enginePtr != 0L) {
            nativeDestroy(enginePtr)
            enginePtr = 0L
        }
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
    }

    private fun nextDitherSample(): Float {
        ditherState = ditherState * 1103515245L + 12345L
        return ((ditherState shr 16) and 0x7FFF).toFloat() / 32768f - 0.5f
    }
}
