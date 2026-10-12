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

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import tf.monochrome.android.audio.dsp.DspNativeLoader

/**
 * One per sink. With no engine wanted and the float DSP off, [onConfigure] returns
 * [AudioProcessor.AudioFormat.NOT_SET], which leaves [isActive] false so Media3 skips this
 * processor entirely and the samples are untouched.
 *
 * Engine changes requested in [onConfigure] wait for [onFlush]: Media3 configures a sink while the
 * previous track is still draining through it and flushes only once that drain is done.
 */
class AudioEngineRouterProcessor internal constructor(
    private val host: AudioEngineHost,
    private val stockDsp: FloatDspProcessor,
) : BaseAudioProcessor() {
    enum class Engine { NONE, TRYPTIFY, LASTWAVE }

    @Volatile
    var outputFloat: Boolean = false

    @Volatile
    var activeEngine: Engine = Engine.NONE
        private set

    private var engineDataEncoding: Int = C.ENCODING_PCM_16BIT
    private var stockPassthrough: Boolean = false

    private var pendingEngine: Engine = Engine.NONE
    private var pendingDataEncoding: Int = C.ENCODING_PCM_16BIT
    private var pendingStockPassthrough: Boolean = false

    @Volatile
    private var seenSettingsGeneration: Int = host.settingsGeneration

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        seenSettingsGeneration = host.settingsGeneration
        pendingEngine = Engine.NONE
        pendingStockPassthrough = false
        if (BitPerfectRuntime.chainBypassActive) return AudioProcessor.AudioFormat.NOT_SET
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT &&
            encoding != C.ENCODING_PCM_24BIT && encoding != C.ENCODING_PCM_32BIT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val outputFloatNow = outputFloat
        val outEncoding = if (outputFloatNow) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

        when (host.wantedEngine()) {
            Engine.TRYPTIFY -> {
                val chain = if (host.acquire(this)) host.tryptifyChain() else null
                val chainOut =
                    chain?.let { runCatching { it.configure(inputAudioFormat) }.getOrNull() }
                        ?.takeIf { it != AudioProcessor.AudioFormat.NOT_SET }
                if (chainOut != null) {
                    pendingEngine = Engine.TRYPTIFY
                    pendingDataEncoding = chainOut.encoding.takeIf { it > 0 } ?: inputAudioFormat.encoding
                    Log.i(
                        TAG,
                        "Tryptify engine selected (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "in=${encodingName(inputAudioFormat.encoding)} chainData=${encodingName(pendingDataEncoding)} " +
                            "floatOut=$outputFloatNow)",
                    )
                    return AudioProcessor.AudioFormat(
                        chainOut.sampleRate.takeIf { it > 0 } ?: inputAudioFormat.sampleRate,
                        chainOut.channelCount.takeIf { it > 0 } ?: inputAudioFormat.channelCount,
                        outEncoding,
                    )
                }
            }
            Engine.LASTWAVE -> {
                val lastwave =
                    if (inputAudioFormat.channelCount <= 2 && host.acquire(this)) host.lastwave() else null
                val lastwaveOut =
                    lastwave?.let { runCatching { it.configure(inputAudioFormat) }.getOrNull() }
                        ?: AudioProcessor.AudioFormat.NOT_SET
                if (lastwaveOut != AudioProcessor.AudioFormat.NOT_SET) {
                    pendingEngine = Engine.LASTWAVE
                    pendingDataEncoding = C.ENCODING_PCM_FLOAT
                    Log.i(
                        TAG,
                        "LastWave engine selected (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "in=${encodingName(inputAudioFormat.encoding)} out=${lastwaveOut.sampleRate}Hz floatOut=$outputFloatNow)",
                    )
                    return AudioProcessor.AudioFormat(lastwaveOut.sampleRate, lastwaveOut.channelCount, outEncoding)
                }
            }
            Engine.NONE -> Unit
        }
        val floatDsp = host.floatDspWanted()
        stockDsp.outputFloat = outputFloatNow
        stockDsp.setEngaged(floatDsp)
        // Not even a configure call while the float DSP is off: FloatDspProcessor loads its
        // native library on first configure.
        if (!floatDsp) return AudioProcessor.AudioFormat.NOT_SET
        return configureStock(inputAudioFormat)
    }

    private fun configureStock(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        runCatching { stockDsp.configure(inputAudioFormat) }
            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val generation = host.settingsGeneration
        if (generation != seenSettingsGeneration) {
            seenSettingsGeneration = generation
            rerouteEngineIfChanged()
        }
        when (activeEngine) {
            Engine.TRYPTIFY -> queueTryptify(inputBuffer)
            Engine.LASTWAVE -> queueLastwave(inputBuffer)
            Engine.NONE -> queueStock(inputBuffer)
        }
    }

    // Mid-track switches are only taken when the new path keeps the format this processor already
    // declared to the sink; anything else waits for the next configure.
    private fun rerouteEngineIfChanged() {
        val input = inputAudioFormat
        val declaredOut = outputAudioFormat
        if (input == AudioProcessor.AudioFormat.NOT_SET || declaredOut == AudioProcessor.AudioFormat.NOT_SET) return

        val desired = host.wantedEngine()
        if (desired == activeEngine) return
        if (desired != Engine.NONE && !host.acquire(this)) {
            logSwitchDeferred("engine in use by another player")
            return
        }

        var newDataEncoding = input.encoding
        var newStockPassthrough = false
        val newOut: AudioProcessor.AudioFormat =
            when (desired) {
                Engine.TRYPTIFY -> {
                    val chainOut =
                        host.tryptifyChain()?.let { chain -> runCatching { chain.configure(input) }.getOrNull() }
                            ?.takeIf { it != AudioProcessor.AudioFormat.NOT_SET }
                    if (chainOut == null) {
                        AudioProcessor.AudioFormat.NOT_SET
                    } else {
                        newDataEncoding = chainOut.encoding.takeIf { it > 0 } ?: input.encoding
                        AudioProcessor.AudioFormat(
                            chainOut.sampleRate.takeIf { it > 0 } ?: input.sampleRate,
                            chainOut.channelCount.takeIf { it > 0 } ?: input.channelCount,
                            declaredOut.encoding,
                        )
                    }
                }
                Engine.LASTWAVE -> {
                    val lastwave = host.lastwave()
                    val out =
                        if (lastwave == null || input.channelCount > 2) {
                            AudioProcessor.AudioFormat.NOT_SET
                        } else {
                            runCatching { lastwave.configure(input) }.getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                        }
                    newDataEncoding = C.ENCODING_PCM_FLOAT
                    if (out == AudioProcessor.AudioFormat.NOT_SET) {
                        out
                    } else {
                        AudioProcessor.AudioFormat(out.sampleRate, out.channelCount, declaredOut.encoding)
                    }
                }
                Engine.NONE -> {
                    stockDsp.setEngaged(host.floatDspWanted())
                    val out =
                        if (stockDsp.engaged) configureStock(input) else AudioProcessor.AudioFormat.NOT_SET
                    if (out != AudioProcessor.AudioFormat.NOT_SET) {
                        out
                    } else {
                        newStockPassthrough = true
                        input
                    }
                }
            }
        val mismatch =
            when {
                newOut == AudioProcessor.AudioFormat.NOT_SET -> "new path refused the format"
                newOut.sampleRate != declaredOut.sampleRate -> "rate ${newOut.sampleRate}!=${declaredOut.sampleRate}"
                newOut.channelCount != declaredOut.channelCount -> "channels ${newOut.channelCount}!=${declaredOut.channelCount}"
                newOut.encoding != declaredOut.encoding -> "encoding ${encodingName(newOut.encoding)}!=${encodingName(declaredOut.encoding)}"
                else -> null
            }
        if (mismatch != null) {
            if (activeEngine == Engine.NONE) host.release(this)
            logSwitchDeferred(mismatch)
            return
        }

        when (desired) {
            Engine.TRYPTIFY -> host.tryptifyChain()?.flush()
            Engine.LASTWAVE -> runCatching { host.lastwave()?.flush() }
            Engine.NONE -> if (!newStockPassthrough) stockDsp.flush()
        }
        val old = activeEngine
        activeEngine = desired
        pendingEngine = desired
        engineDataEncoding = newDataEncoding
        pendingDataEncoding = newDataEncoding
        stockPassthrough = newStockPassthrough
        pendingStockPassthrough = newStockPassthrough
        if (desired == Engine.NONE) host.release(this)
        publishActive()
        Log.i(
            TAG,
            "engine SWITCHED mid-track $old -> $desired " +
                "(in=${encodingName(input.encoding)} data=${encodingName(engineDataEncoding)} out=${encodingName(declaredOut.encoding)})",
        )
    }

    private fun logSwitchDeferred(reason: String) {
        Log.i(TAG, "engine switch deferred to next configure: $reason")
    }

    private fun queueTryptify(inputBuffer: ByteBuffer) {
        val chain = host.builtTryptifyChain() ?: return queueCopy(inputBuffer)
        chain.refreshActive()
        emitEngineOutput(chain.process(inputBuffer))
    }

    private fun queueLastwave(inputBuffer: ByteBuffer) {
        val lastwave = host.builtLastwave() ?: return queueCopy(inputBuffer)
        lastwave.queueInput(inputBuffer)
        emitEngineOutput(lastwave.output)
    }

    private fun queueStock(inputBuffer: ByteBuffer) {
        if (stockPassthrough) return queueCopy(inputBuffer)
        stockDsp.queueInput(inputBuffer)
        val out = stockDsp.output
        if (!out.hasRemaining()) return
        replaceOutputBuffer(out.remaining()).put(out).flip()
    }

    private fun queueCopy(inputBuffer: ByteBuffer) {
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    private fun emitEngineOutput(engineOutput: ByteBuffer) {
        if (!engineOutput.hasRemaining()) return

        val encoded = EnginePcmCodec.encode(
            data = engineOutput,
            dataEncoding = engineDataEncoding,
            outputEncoding = outputAudioFormat.encoding,
            channels = outputAudioFormat.channelCount,
        )
        replaceOutputBuffer(encoded.remaining()).put(encoded).flip()
    }

    override fun onFlush() {
        activeEngine = pendingEngine
        engineDataEncoding = pendingDataEncoding
        stockPassthrough = pendingStockPassthrough
        when (activeEngine) {
            Engine.TRYPTIFY -> host.builtTryptifyChain()?.flush()
            Engine.LASTWAVE -> runCatching { host.builtLastwave()?.flush() }
            Engine.NONE -> host.release(this)
        }
        stockDsp.flush()
        publishActive()
    }

    override fun onReset() {
        if (host.owns(this)) {
            runCatching { host.builtTryptifyChain()?.reset() }
            runCatching { host.builtLastwave()?.reset() }
            host.release(this)
        }
        stockDsp.reset()
        activeEngine = Engine.NONE
        pendingEngine = Engine.NONE
        stockPassthrough = false
        pendingStockPassthrough = false
        engineDataEncoding = C.ENCODING_PCM_16BIT
        pendingDataEncoding = C.ENCODING_PCM_16BIT
        publishActive()
    }

    override fun onQueueEndOfStream() {
        when (activeEngine) {
            Engine.TRYPTIFY -> {
                val tail = host.builtTryptifyChain()?.queueEndOfStreamAndDrain()
                if (tail != null && tail.hasRemaining()) emitEngineOutput(tail)
            }
            Engine.LASTWAVE -> runCatching {
                val lastwave = host.builtLastwave() ?: return@runCatching
                lastwave.queueEndOfStream()
                val tail = lastwave.output
                if (tail.hasRemaining()) emitEngineOutput(tail)
            }
            Engine.NONE -> if (!stockPassthrough) {
                stockDsp.queueEndOfStream()
                val tail = stockDsp.output
                if (tail.hasRemaining()) replaceOutputBuffer(tail.remaining()).put(tail).flip()
            }
        }
    }

    private fun publishActive() {
        if (!host.mayPublish(this)) return
        val engine = activeEngine
        val floatOut = engine != Engine.NONE && outputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (EngineRuntime.activeEngine != engine) EngineRuntime.publishActiveEngine(engine)
        if (EngineRuntime.outputFloat != floatOut) EngineRuntime.publishOutputFloat(floatOut)
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
