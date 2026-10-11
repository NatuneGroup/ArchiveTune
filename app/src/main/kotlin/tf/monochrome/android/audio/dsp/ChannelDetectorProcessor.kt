package tf.monochrome.android.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10

@Singleton
@OptIn(UnstableApi::class)
class ChannelDetectorProcessor @Inject constructor() : AudioProcessor {
    data class ChannelState(
        val channelCount: Int,
        val sampleRate: Int,
        val isFloat: Boolean,
        val layoutName: String,
        val channelNames: List<String>,
        val peaksDb: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ChannelState && other.channelCount == channelCount &&
                other.sampleRate == sampleRate && other.isFloat == isFloat &&
                other.layoutName == layoutName && other.channelNames == channelNames &&
                other.peaksDb.contentEquals(peaksDb)

        override fun hashCode(): Int = 31 * channelCount + peaksDb.contentHashCode()
    }

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    @Volatile private var peaks = FloatArray(0)
    @Volatile private var meterActive = false

    private val subscriberCount = AtomicInteger(0)
    private val lifecycleLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var publishJob: Job? = null

    private val _state = MutableStateFlow<ChannelState?>(null)
    val state: StateFlow<ChannelState?> = _state.asStateFlow()

    fun acquire() {
        val count = subscriberCount.incrementAndGet()
        if (count == 1) {
            synchronized(lifecycleLock) {
                if (subscriberCount.get() > 0 && !meterActive) startMeterLocked()
            }
        }
    }

    fun release() {
        val count = subscriberCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (count == 0) {
            synchronized(lifecycleLock) {
                if (subscriberCount.get() == 0 && meterActive) stopMeterLocked()
            }
        }
    }

    private fun startMeterLocked() {
        meterActive = true
        java.util.Arrays.fill(peaks, 0f)
        publishJob?.cancel()
        publishJob = scope.launch {
            while (isActive) {
                publishState()

                val p = peaks
                for (c in p.indices) p[c] *= 0.72f
                delay(100)
            }
        }
    }

    private fun stopMeterLocked() {
        meterActive = false
        publishJob?.cancel()
        publishJob = null
        java.util.Arrays.fill(peaks, 0f)
        publishState()
    }

    private fun publishState() {
        val fmt = inputFormat
        if (fmt == AudioFormat.NOT_SET) {
            _state.value = null
            return
        }
        val p = peaks
        val db = FloatArray(fmt.channelCount) { c ->
            val v = if (c < p.size) p[c] else 0f
            if (v > 1e-6f) 20f * log10(v) else PEAK_FLOOR_DB
        }
        _state.value = ChannelState(
            channelCount = fmt.channelCount,
            sampleRate = fmt.sampleRate,
            isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT,
            layoutName = layoutName(fmt.channelCount),
            channelNames = channelNames(fmt.channelCount),
            peaksDb = db,
        )
    }

    fun currentPeaks(): FloatArray = peaks.copyOf()

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        if (channels < 1) return
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }

        val byteCount = numFrames * frameSize
        val startPos = inputBuffer.position()

        if (meterActive) {
            val p = peaks
            if (p.size >= channels) {
                for (i in 0 until numFrames) {
                    val base = startPos + i * frameSize
                    for (c in 0 until channels) {
                        val v = if (isFloat) {
                            abs(inputBuffer.getFloat(base + c * 4))
                        } else {
                            abs(inputBuffer.getShort(base + c * 2).toFloat()) / 32768f
                        }
                        if (v > p[c]) p[c] = v
                    }
                }
            }
        }

        if (outputBuffer.capacity() < byteCount) {
            outputBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        val savedLimit = inputBuffer.limit()
        inputBuffer.limit(startPos + byteCount)
        outputBuffer.put(inputBuffer)
        inputBuffer.limit(savedLimit)
        outputBuffer.flip()
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
        if (pendingFormat != AudioFormat.NOT_SET) {
            val changed = inputFormat != pendingFormat
            inputFormat = pendingFormat
            pendingFormat = AudioFormat.NOT_SET
            if (changed) {
                peaks = FloatArray(inputFormat.channelCount)
                publishState()
            }
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        peaks = FloatArray(0)
        _state.value = null
    }

    companion object {
        const val PEAK_FLOOR_DB = -120f

        const val ACTIVE_THRESHOLD_DB = -60f

        fun layoutName(count: Int): String = when (count) {
            1 -> "Mono"
            2 -> "Stereo"
            3 -> "3.0"
            4 -> "Quad"
            5 -> "5.0"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            16 -> "9.1.6"
            else -> "$count ch"
        }

        fun channelNames(count: Int): List<String> = when (count) {
            1 -> listOf("M")
            2 -> listOf("FL", "FR")
            3 -> listOf("FL", "FR", "FC")
            4 -> listOf("FL", "FR", "BL", "BR")
            5 -> listOf("FL", "FR", "FC", "BL", "BR")
            6 -> listOf("FL", "FR", "FC", "LFE", "BL", "BR")
            7 -> listOf("FL", "FR", "FC", "LFE", "BC", "SL", "SR")
            8 -> listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
            16 -> listOf(
                "FL", "FR", "FC", "LFE", "BL", "BR", "BLC", "BRC",
                "SL", "SR", "TFL", "TFR", "TSL", "TSR", "TBL", "TBR",
            )
            else -> List(count) { "Ch ${it + 1}" }
        }
    }
}
