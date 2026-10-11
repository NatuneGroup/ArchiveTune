package tf.monochrome.android.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
@OptIn(UnstableApi::class)
class DownmixProcessor @Inject constructor() : AudioProcessor {
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var coefL = FloatArray(0)
    private var coefR = FloatArray(0)

    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(e: Boolean) {
        enabled = e
    }

    @Volatile
    private var preampDb: Float = 0f

    fun setPreampDb(db: Float) {
        preampDb = db
    }

    @Volatile
    private var lfeLowpassEnabled: Boolean = false

    fun setLfeLowpass(e: Boolean) {
        lfeLowpassEnabled = e
    }

    private val lfeFilter = LfeLowPassFilter()
    private var lfeActive = false
    private var lfeIndex = -1
    private var lfeGainL = 0f
    private var lfeGainR = 0f

    private var appliedPreampDb = Float.NaN
    private var appliedLfe = false

    private fun rebuildCoefs(resetLfeState: Boolean) {
        val pre = preampDb
        val lfeOn = lfeLowpassEnabled
        val rows = COEF_TABLES.getValue(inputFormat.channelCount)
        val gain = 10f.pow(pre / 20f)
        coefL = FloatArray(rows.first.size) { rows.first[it] * gain }
        coefR = FloatArray(rows.second.size) { rows.second[it] * gain }
        lfeIndex = KIND_TABLES.getValue(inputFormat.channelCount).indexOf(Kind.LFE_CH)
        val nowActive = lfeOn && lfeIndex >= 0
        if (nowActive && (resetLfeState || !lfeActive)) {
            lfeFilter.configure(inputFormat.sampleRate)
        }
        lfeActive = nowActive
        if (lfeActive) {
            lfeGainL = coefL[lfeIndex]
            lfeGainR = coefR[lfeIndex]
            coefL[lfeIndex] = 0f
            coefR[lfeIndex] = 0f
        }
        appliedPreampDb = pre
        appliedLfe = lfeOn
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount < 1 ||
            inputAudioFormat.channelCount > MAX_INPUT_CHANNELS) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!enabled || inputAudioFormat.channelCount <= 2 ||
            !KIND_TABLES.containsKey(inputAudioFormat.channelCount)) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        pendingFormat = inputAudioFormat
        return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun isActive(): Boolean = pendingFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val channels = inputFormat.channelCount
        if (channels < 3) return
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) return

        if (preampDb != appliedPreampDb || lfeLowpassEnabled != appliedLfe) {
            rebuildCoefs(resetLfeState = false)
        }

        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val cL = coefL
        val cR = coefR
        val startPos = inputBuffer.position()
        for (i in 0 until numFrames) {
            val base = startPos + i * frameSize
            var accL = 0f
            var accR = 0f
            if (isFloat) {
                for (c in 0 until channels) {
                    val s = inputBuffer.getFloat(base + c * 4)
                    accL += cL[c] * s
                    accR += cR[c] * s
                }
            } else {
                for (c in 0 until channels) {
                    val s = inputBuffer.getShort(base + c * 2).toFloat() / 32768f
                    accL += cL[c] * s
                    accR += cR[c] * s
                }
            }

            if (lfeActive) {
                val lfeS = if (isFloat) {
                    inputBuffer.getFloat(base + lfeIndex * 4)
                } else {
                    inputBuffer.getShort(base + lfeIndex * 2).toFloat() / 32768f
                }
                val f = lfeFilter.filterLfe(lfeS)
                accL = lfeFilter.delayDryL(accL) + lfeGainL * f
                accR = lfeFilter.delayDryR(accR) + lfeGainR * f
            }
            if (isFloat) {
                val off = i * 8
                outputBuffer.putFloat(off, accL)
                outputBuffer.putFloat(off + 4, accR)
            } else {
                val off = i * 4
                outputBuffer.putShort(off, (accL * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, (accR * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)
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

        inputFormat = pendingFormat
        if (inputFormat != AudioFormat.NOT_SET) {
            rebuildCoefs(resetLfeState = true)
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        coefL = FloatArray(0)
        coefR = FloatArray(0)
        lfeActive = false
        lfeIndex = -1
        lfeFilter.reset()
    }

    companion object {
        const val MAX_INPUT_CHANNELS = 16

        private const val CENTER_COEF = 0.70710678f

        private const val LFE_COEF = 2.26464431f

        private enum class Kind { L_FRONT, R_FRONT, CENTER, LFE_CH, L_SURR, R_SURR, C_SURR }

        private val KIND_TABLES: Map<Int, Array<Kind>> = mapOf(
            3 to arrayOf(Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER),
            4 to arrayOf(Kind.L_FRONT, Kind.R_FRONT, Kind.L_SURR, Kind.R_SURR),
            5 to arrayOf(Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.L_SURR, Kind.R_SURR),
            6 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR,
            ),
            7 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.C_SURR, Kind.L_SURR, Kind.R_SURR,
            ),
            8 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
            ),
            16 to arrayOf(
                Kind.L_FRONT, Kind.R_FRONT, Kind.CENTER, Kind.LFE_CH,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
                Kind.L_SURR, Kind.R_SURR, Kind.L_FRONT, Kind.R_FRONT,
                Kind.L_SURR, Kind.R_SURR, Kind.L_SURR, Kind.R_SURR,
            ),
        )

        private fun gains(k: Kind): Pair<Float, Float> = when (k) {
            Kind.L_FRONT, Kind.L_SURR -> 1f to 0f
            Kind.R_FRONT, Kind.R_SURR -> 0f to 1f
            Kind.CENTER, Kind.C_SURR -> CENTER_COEF to CENTER_COEF
            Kind.LFE_CH -> LFE_COEF to LFE_COEF
        }

        private val COEF_TABLES: Map<Int, Pair<FloatArray, FloatArray>> =
            KIND_TABLES.mapValues { (_, kinds) ->
                Pair(
                    FloatArray(kinds.size) { gains(kinds[it]).first },
                    FloatArray(kinds.size) { gains(kinds[it]).second },
                )
            }
    }
}
