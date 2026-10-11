package tf.monochrome.android.audio.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
internal class ToFloatPcmAudioProcessor : AudioProcessor {
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (bytesPerSample(inputAudioFormat.encoding) == 0) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT,
        )
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputFormat
        val stride = bytesPerSample(format.encoding)
        if (stride == 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val samples = inputBuffer.remaining() / stride
        if (samples <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val out = ensureCapacity(samples * 4)
        repeat(samples) { out.putFloat(readSample(inputBuffer, stride)) }
        out.flip()
        outputBuffer = out
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        if (pendingFormat != AudioFormat.NOT_SET) inputFormat = pendingFormat
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    private fun ensureCapacity(bytes: Int): ByteBuffer {
        var b = buffer
        if (b.capacity() < bytes) {
            b = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            buffer = b
        }
        b.clear()
        return b
    }
}

@OptIn(UnstableApi::class)
internal fun bytesPerSample(encoding: Int): Int = when (encoding) {
    C.ENCODING_PCM_16BIT -> 2
    C.ENCODING_PCM_24BIT -> 3
    C.ENCODING_PCM_32BIT -> 4
    C.ENCODING_PCM_FLOAT -> 4
    else -> 0
}

internal fun readSample(input: ByteBuffer, stride: Int): Float = when (stride) {
    2 -> {
        val b0 = input.get().toInt() and 0xFF
        val b1 = input.get().toInt()
        ((b1 shl 8) or b0) / 32_768f
    }
    3 -> {
        val b0 = input.get().toInt() and 0xFF
        val b1 = input.get().toInt() and 0xFF
        val b2 = input.get().toInt()
        ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
    }
    else -> {
        val b0 = input.get().toInt() and 0xFF
        val b1 = input.get().toInt() and 0xFF
        val b2 = input.get().toInt() and 0xFF
        val b3 = input.get().toInt()
        ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) / 2_147_483_648f
    }
}
