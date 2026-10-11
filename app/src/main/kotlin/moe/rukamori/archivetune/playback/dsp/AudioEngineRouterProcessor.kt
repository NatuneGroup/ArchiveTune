@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.lastwave.app.playback.NativePcmAudioProcessor
import java.nio.ByteBuffer
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.DownmixProcessor
import tf.monochrome.android.audio.dsp.DspNativeLoader
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.usb.ToFloatPcmAudioProcessor

class AudioEngineRouterProcessor(

    private val tryptifyEnabled: () -> Boolean,

    private val lastwaveEnabled: () -> Boolean,

    private val tryptifyMixBus: MixBusProcessor,
    private val tryptifyAutoEq: AutoEqProcessor,
    private val tryptifyParamEq: ParametricEqProcessor,

    private val channelDetector: ChannelDetectorProcessor,

    private val downmix: DownmixProcessor,

    private val spectrumTap: SpectrumAnalyzerTap,

    private val variRate: tf.monochrome.android.audio.resample.VariRateAudioProcessor,

    private val stretch: tf.monochrome.android.audio.stretch.StretchAudioProcessor,

    private val lastwaveProcessor: NativePcmAudioProcessor,

    private val stockDsp: FloatDspProcessor,
) : BaseAudioProcessor() {
    enum class Engine { NONE, TRYPTIFY, LASTWAVE }

    @Volatile
    var outputFloat: Boolean = false

    @Volatile
    private var engineDataEncoding: Int = C.ENCODING_PCM_16BIT

    @Volatile
    private var declaredOutputEncoding: Int = C.ENCODING_PCM_16BIT

    @Volatile
    var activeEngine: Engine = Engine.NONE
        private set

    @Volatile
    private var activeOutputFloat: Boolean = false

    @Volatile
    private var reevaluateRequested: Boolean = false

    fun requestEngineReevaluate() {
        reevaluateRequested = true
    }

    private val tryptifyChain = tf.monochrome.android.audio.usb.AudioProcessorChain(
        listOf(
            ToFloatPcmAudioProcessor(),
            channelDetector,
            downmix,
            tryptifyMixBus,
            tryptifyAutoEq,
            tryptifyParamEq,
            spectrumTap,
            variRate,
            stretch,
        ),
    )

    private var engineAvailableTryptify: Boolean = tryptifyNativeAvailable()
    private var engineAvailableLastwave: Boolean = lastwaveProcessor.isAvailable

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (moe.rukamori.archivetune.playback.dsp.BitPerfectRuntime.chainBypassActive) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT &&
            encoding != C.ENCODING_PCM_24BIT && encoding != C.ENCODING_PCM_32BIT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        engineAvailableTryptify = tryptifyNativeAvailable()
        engineAvailableLastwave = lastwaveProcessor.isAvailable
        EngineRuntime.publishEngineAvailability(engineAvailableTryptify, engineAvailableLastwave)

        val selected = when {
            tryptifyEnabled() && engineAvailableTryptify -> Engine.TRYPTIFY
            lastwaveEnabled() && engineAvailableLastwave -> Engine.LASTWAVE
            else -> Engine.NONE
            }
        val changed = selected != activeEngine
        activeEngine = selected
        activeOutputFloat = outputFloat

        EngineRuntime.publishActiveEngine(selected)
        EngineRuntime.publishOutputFloat(outputFloat)

        return when (selected) {
            Engine.TRYPTIFY -> {
                val chainOut = tryptifyChain.configure(inputAudioFormat)
                engineDataEncoding =
                    chainOut.encoding.takeIf { it > 0 } ?: inputAudioFormat.encoding
                val outEncoding =
                    if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                declaredOutputEncoding = outEncoding
                if (changed) {
                    Log.i(
                        TAG,
                        "Tryptify engine ENGAGED (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "in=${encodingName(inputAudioFormat.encoding)} " +
                            "chainData=${encodingName(engineDataEncoding)} " +
                            "floatOut=$activeOutputFloat chainOut=${chainOut.encoding})",
                    )
                }
                AudioProcessor.AudioFormat(
                    chainOut.sampleRate.takeIf { it > 0 } ?: inputAudioFormat.sampleRate,
                    chainOut.channelCount.takeIf { it > 0 } ?: inputAudioFormat.channelCount,
                    outEncoding,
                )
            }
            Engine.LASTWAVE -> {
                if (inputAudioFormat.channelCount > 2) {
                    activeEngine = Engine.NONE
                    EngineRuntime.publishActiveEngine(Engine.NONE)
                    return configureStock(inputAudioFormat)
                }

                val lastwaveOut = runCatching { lastwaveProcessor.configure(inputAudioFormat) }
                    .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                if (lastwaveOut == AudioProcessor.AudioFormat.NOT_SET) {
                    activeEngine = Engine.NONE
                    EngineRuntime.publishActiveEngine(Engine.NONE)
                    return configureStock(inputAudioFormat)
                }

                engineDataEncoding = C.ENCODING_PCM_FLOAT
                val outEncoding =
                    if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                declaredOutputEncoding = outEncoding
                if (changed) {
                    Log.i(
                        TAG,
                        "Lastwave engine ENGAGED (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "in=${encodingName(inputAudioFormat.encoding)} " +
                            "chainData=float floatOut=$activeOutputFloat out=${lastwaveOut.sampleRate}Hz)",
                    )
                }
                AudioProcessor.AudioFormat(
                    lastwaveOut.sampleRate,
                    lastwaveOut.channelCount,
                    outEncoding,
                )
            }
            Engine.NONE -> configureStock(inputAudioFormat)
        }
    }

    private fun configureStock(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        runCatching { stockDsp.configure(inputAudioFormat) }
            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (reevaluateRequested) {
            reevaluateRequested = false
            rerouteEngineIfChanged()
        }
        when (activeEngine) {
            Engine.TRYPTIFY -> queueTryptify(inputBuffer)
            Engine.LASTWAVE -> queueLastwave(inputBuffer)
            Engine.NONE -> queueStock(inputBuffer)
        }
    }

    private fun rerouteEngineIfChanged() {
        val input = inputAudioFormat
        if (input == AudioProcessor.AudioFormat.NOT_SET) return

        activeOutputFloat = outputFloat
        engineAvailableTryptify = tryptifyNativeAvailable()
        engineAvailableLastwave = lastwaveProcessor.isAvailable
        EngineRuntime.publishEngineAvailability(engineAvailableTryptify, engineAvailableLastwave)
        val desired =
            when {
                tryptifyEnabled() && engineAvailableTryptify -> Engine.TRYPTIFY
                lastwaveEnabled() && engineAvailableLastwave -> Engine.LASTWAVE
                else -> Engine.NONE
            }
        if (desired == activeEngine) return

        val declaredOut = outputAudioFormat

        var newDataEncoding = input.encoding
        val newOut: AudioProcessor.AudioFormat =
            when (desired) {
                Engine.TRYPTIFY -> {
                    val chainOut = tryptifyChain.configure(input)
                    newDataEncoding =
                        chainOut.encoding.takeIf { it > 0 } ?: input.encoding
                    AudioProcessor.AudioFormat(
                        chainOut.sampleRate.takeIf { it > 0 } ?: input.sampleRate,
                        chainOut.channelCount.takeIf { it > 0 } ?: input.channelCount,
                        if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
                    )
                }
                Engine.LASTWAVE -> {
                    if (input.channelCount > 2) {
                        logSwitchDeferred("lastwave rejects >2ch")
                        return
                    }
                    val out =
                        runCatching { lastwaveProcessor.configure(input) }
                            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                    if (out == AudioProcessor.AudioFormat.NOT_SET) {
                        logSwitchDeferred("lastwave configure failed")
                        return
                    }
                    newDataEncoding = C.ENCODING_PCM_FLOAT
                    AudioProcessor.AudioFormat(
                        out.sampleRate,
                        out.channelCount,
                        if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
                    )
                }
                Engine.NONE -> {
                    val out =
                        runCatching { stockDsp.configure(input) }
                            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                    out
                }
            }
        if (newOut == AudioProcessor.AudioFormat.NOT_SET) {
            logSwitchDeferred("new path refused the format")
            return
        }
        if (declaredOut != AudioProcessor.AudioFormat.NOT_SET &&
            newOut.sampleRate != declaredOut.sampleRate &&
            (newOut.sampleRate > 0 && declaredOut.sampleRate > 0)
        ) {
            logSwitchDeferred("rate ${newOut.sampleRate}!=${declaredOut.sampleRate}")
            return
        }
        if (declaredOut != AudioProcessor.AudioFormat.NOT_SET &&
            newOut.channelCount != declaredOut.channelCount &&
            (newOut.channelCount > 0 && declaredOut.channelCount > 0)
        ) {
            logSwitchDeferred("channels ${newOut.channelCount}!=${declaredOut.channelCount}")
            return
        }

        val old = activeEngine

        runCatching { tryptifyChain.flush() }
        runCatching { lastwaveProcessor.flush() }
        runCatching { stockDsp.flush() }
        activeEngine = desired
        engineDataEncoding = newDataEncoding
        EngineRuntime.publishActiveEngine(desired)
        Log.i(
            TAG,
            "engine SWITCHED mid-track $old -> $desired " +
                "(in=${encodingName(input.encoding)} data=${encodingName(engineDataEncoding)} " +
                "floatOut=$activeOutputFloat)",
        )
    }

    private fun logSwitchDeferred(reason: String) {
        Log.i(TAG, "engine switch deferred to next configure: $reason")
    }

    private fun queueTryptify(inputBuffer: ByteBuffer) {
        tryptifyChain.refreshActive()
        val chainOut = tryptifyChain.process(inputBuffer)
        emitEngineOutput(chainOut)
    }

    private fun queueLastwave(inputBuffer: ByteBuffer) {
        lastwaveProcessor.queueInput(inputBuffer)
        emitEngineOutput(lastwaveProcessor.output)
    }

    private fun queueStock(inputBuffer: ByteBuffer) {
        stockDsp.queueInput(inputBuffer)
        val out = stockDsp.output
        if (!out.hasRemaining()) return
        replaceOutputBuffer(out.remaining()).put(out).flip()
    }

    private fun emitEngineOutput(engineOutput: ByteBuffer) {
        if (!engineOutput.hasRemaining()) return

        val encoded = EnginePcmCodec.encode(
            data = engineOutput,
            dataEncoding = engineDataEncoding,
            outputEncoding = if (activeOutputFloat) C.ENCODING_PCM_FLOAT else declaredOutputEncoding,
            channels = outputAudioFormat.channelCount,
        )
        replaceOutputBuffer(encoded.remaining()).put(encoded).flip()
    }

    override fun onFlush() {
        tryptifyChain.flush()
        runCatching { lastwaveProcessor.flush() }
        stockDsp.flush()
    }

    override fun onReset() {
        tryptifyChain.reset()
        runCatching { lastwaveProcessor.reset() }
        stockDsp.reset()
        activeEngine = Engine.NONE
        activeOutputFloat = false
        engineDataEncoding = C.ENCODING_PCM_16BIT
        EngineRuntime.publishActiveEngine(Engine.NONE)
        EngineRuntime.publishOutputFloat(false)
    }

    override fun onQueueEndOfStream() {
        val tryptifyTail = tryptifyChain.queueEndOfStreamAndDrain()
        if (tryptifyTail.hasRemaining()) {
            emitEngineOutput(tryptifyTail)
        }
        runCatching {
            lastwaveProcessor.queueEndOfStream()
            val lastwaveTail = lastwaveProcessor.output
            if (lastwaveTail.hasRemaining()) {
                emitEngineOutput(lastwaveTail)
            }
        }
        stockDsp.queueEndOfStream()
    }

    companion object {
        private const val TAG = "AudioEngineRouter"

        @Volatile
        private var tryptifyNativeProbe: Boolean? = null

        fun tryptifyNativeAvailable(): Boolean {
            tryptifyNativeProbe?.let { return it }
            val ok = runCatching { DspNativeLoader.ensureLoaded() }.isSuccess
            tryptifyNativeProbe = ok
            return ok
        }

        internal fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_16BIT -> "pcm16"
            C.ENCODING_PCM_FLOAT -> "float"
            C.ENCODING_PCM_24BIT -> "pcm24"
            C.ENCODING_PCM_32BIT -> "pcm32"
            else -> "encoding[$encoding]"
        }
    }
}
