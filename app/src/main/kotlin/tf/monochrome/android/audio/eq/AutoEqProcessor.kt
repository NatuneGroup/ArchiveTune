package tf.monochrome.android.audio.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow

@Singleton
@OptIn(UnstableApi::class)
class AutoEqProcessor @Inject constructor() : AudioProcessor {
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var scratchL = FloatArray(0)
    private var scratchR = FloatArray(0)

    private class Design(
        val enabled: Boolean,
        val preampLinear: Float,
        val coefsL: Array<FloatArray>,
        val coefsR: Array<FloatArray>,
    )

    private class Source(
        val bandsL: List<EqBand>,
        val bandsR: List<EqBand>,
        val preamp: Float,
        val enabled: Boolean,
    )

    private val designRef = AtomicReference(
        Design(false, 1f, emptyArray(), emptyArray())
    )
    private var appliedDesign: Design? = null

    private var filtersL = arrayOf<EqBiquad>()
    private var filtersR = arrayOf<EqBiquad>()

    @Volatile private var source = Source(emptyList(), emptyList(), 0f, false)

    @Volatile private var sampleRate = 44100.0

    @Volatile private var targetRatio = 1f

    @Volatile private var glidedOctaves = 0f

    @Volatile private var snapToTarget = false

    @Volatile private var glideTauMs: Double = DEFAULT_GLIDE_TAU_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val redesignRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unused in redesignRequests) runGlide()
        }
    }

    fun applyBands(bands: List<EqBand>, preamp: Float, enabled: Boolean) =
        applyBands(bands, bands, preamp, enabled)

    fun applyBands(
        bandsL: List<EqBand>,
        bandsR: List<EqBand>,
        preamp: Float,
        enabled: Boolean,
    ) {
        source = Source(bandsL, bandsR, preamp, enabled)

        publishDesign()
    }

    @JvmOverloads
    fun setPitchRatio(
        ratio: Float,
        immediate: Boolean = false,
        glideMillis: Int = DEFAULT_GLIDE_MILLIS,
    ) {
        if (!ratio.isFinite() || ratio <= 0f) return

        glideTauMs = (glideMillis.toDouble() / 5.0)
            .coerceIn(MIN_GLIDE_TAU_MS, MAX_GLIDE_TAU_MS)

        targetRatio = ratio.coerceIn(MIN_RATIO, MAX_RATIO)

        if (immediate) {
            snapToTarget = true
            redesignRequests.trySend(Unit)

            glidedOctaves = -log2(targetRatio)
            publishDesign()
            return
        }
        redesignRequests.trySend(Unit)
    }

    fun release() {
        scope.cancel()
        redesignRequests.close()
    }

    private suspend fun runGlide() {
        while (true) {
            val target = -log2(targetRatio)
            if (snapToTarget) {
                snapToTarget = false
                glidedOctaves = target
                publishDesign()
                return
            }
            val delta = target - glidedOctaves
            if (abs(delta) <= SETTLE_OCTAVES) {
                glidedOctaves = target
                publishDesign()
                return
            }

            val alpha = (1.0 - exp(-GLIDE_TICK_MS / glideTauMs)).toFloat()
            glidedOctaves += delta * alpha
            publishDesign()
            delay(GLIDE_TICK_MS)
        }
    }

    private val designPublishLock = Any()

    private fun publishDesign() = synchronized(designPublishLock) {
        val src = source

        val warpFactor = 2f.pow(glidedOctaves)
        val sr = sampleRate
        designRef.set(
            Design(
                enabled = src.enabled,
                preampLinear = if (src.preamp == 0f) 1f else 10f.pow(src.preamp / 20f),
                coefsL = designChain(src.bandsL, warpFactor, sr),
                coefsR = designChain(src.bandsR, warpFactor, sr),
            )
        )
    }

    private fun designChain(
        bands: List<EqBand>,
        warpFactor: Float,
        sr: Double,
    ): Array<FloatArray> {
        val active = bands.filter { it.enabled && it.gain != 0f }
        if (active.isEmpty()) return emptyArray()
        val maxHz = sr * 0.49

        return Array(active.size) { i ->
            val band = active[i]
            val type = when (band.type) {
                FilterType.LOWSHELF -> EqBiquadType.LOW_SHELF
                FilterType.HIGHSHELF -> EqBiquadType.HIGH_SHELF
                else -> EqBiquadType.PEAKING
            }
            val warped = (band.freq.toDouble() * warpFactor).coerceIn(MIN_HZ, maxHz)
            designBiquadCoefficients(
                type, sr, warped, band.q.toDouble(), band.gain.toDouble(),
                matched = true,
            )
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
        val encoding = inputFormat.encoding
        val inputChannels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) return

        if (scratchL.size < numFrames) {
            scratchL = FloatArray(numFrames)
            scratchR = FloatArray(numFrames)
        }

        val startPos = inputBuffer.position()
        if (inputChannels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getFloat(startPos + i * 4)
                    scratchL[i] = s; scratchR[i] = s
                }
            } else {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getShort(startPos + i * 2).toFloat() / 32768f
                    scratchL[i] = s; scratchR[i] = s
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 8
                    scratchL[i] = inputBuffer.getFloat(off)
                    scratchR[i] = inputBuffer.getFloat(off + 4)
                }
            } else {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 4
                    scratchL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                    scratchR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)

        val design = designRef.get()
        if (design.enabled) {
            if (design !== appliedDesign) {
                installDesign(design)
                appliedDesign = design
            }

            val hasWork = filtersL.isNotEmpty() || filtersR.isNotEmpty() ||
                design.preampLinear != 1f
            if (hasWork) {
                applyEq(scratchL, scratchR, numFrames, design.preampLinear)
            }
        }

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
                outputBuffer.putFloat(off, scratchL[i])
                outputBuffer.putFloat(off + 4, scratchR[i])
            }
        } else {
            for (i in 0 until numFrames) {
                val off = i * 4
                outputBuffer.putShort(off, (scratchL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, (scratchR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
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
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            val formatChanged = inputFormat == AudioFormat.NOT_SET
                || inputFormat.sampleRate != pendingFormat.sampleRate
                || inputFormat.encoding != pendingFormat.encoding
                || inputFormat.channelCount != pendingFormat.channelCount
            if (formatChanged) {
                inputFormat = pendingFormat
                sampleRate = inputFormat.sampleRate.toDouble()

                filtersL = emptyArray()
                filtersR = emptyArray()
                appliedDesign = null
                redesignRequests.trySend(Unit)
            }
            pendingFormat = AudioFormat.NOT_SET
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        filtersL = emptyArray()
        filtersR = emptyArray()
        appliedDesign = null
    }

    private fun installDesign(design: Design) {
        if (filtersL.size != design.coefsL.size) {
            filtersL = Array(design.coefsL.size) { EqBiquad() }
        }
        if (filtersR.size != design.coefsR.size) {
            filtersR = Array(design.coefsR.size) { EqBiquad() }
        }
        for (i in filtersL.indices) filtersL[i].retune(design.coefsL[i])
        for (i in filtersR.indices) filtersR[i].retune(design.coefsR[i])
    }

    private fun applyEq(bufL: FloatArray, bufR: FloatArray, numFrames: Int, preampLinear: Float) {
        if (preampLinear != 1f) {
            for (i in 0 until numFrames) {
                bufL[i] *= preampLinear
                bufR[i] *= preampLinear
            }
        }

        for (i in filtersL.indices) filtersL[i].processBlock(bufL, numFrames)
        for (i in filtersR.indices) filtersR[i].processBlock(bufR, numFrames)
    }

    private companion object {
        const val GLIDE_TICK_MS = 16L

        const val DEFAULT_GLIDE_MILLIS = 700

        const val DEFAULT_GLIDE_TAU_MS = 140.0

        const val MIN_GLIDE_TAU_MS = 20.0
        const val MAX_GLIDE_TAU_MS = 600.0

        const val SETTLE_OCTAVES = 0.0005f

        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 20f
        const val MIN_HZ = 5.0
    }
}
