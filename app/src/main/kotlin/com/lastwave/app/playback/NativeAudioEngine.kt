package com.lastwave.app.playback

import android.util.Log
import com.lastwave.app.data.local.EQ_MAX_GAIN_DB
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import java.io.Closeable
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class NativePcmEncoding(internal val nativeValue: Int, internal val bytesPerSample: Int) {
    PCM_I16(0, 2),
    PCM_I24_PACKED(1, 3),
    PCM_I32(2, 4),
    PCM_FLOAT(3, 4),
}

@Singleton
class NativeAudioEngine @Inject constructor(
    settingsPreferences: SettingsPreferences,
    equalizerPreferences: EqualizerPreferences,
    applicationScope: CoroutineScope,
) : Closeable {
    private val handleLock = Any()
    @Volatile
    private var nativeHandle = if (libraryLoaded) {
        try {
            nativeCreate()
        } catch (error: LinkageError) {
            Log.e(TAG, "Native audio engine is unavailable; using Android audio", error)
            0L
        }
    } else {
        0L
    }

    val isAvailable: Boolean
        get() = nativeHandle != 0L

    init {
        if (isAvailable) {
            applicationScope.launch(Dispatchers.Default) {
                settingsPreferences.settings
                    .map { it.isStudioMasterClarityEnabled }
                    .distinctUntilChanged()
                    .collect { setStudioMasterClarity(it) }
            }
            applicationScope.launch(Dispatchers.Default) {
                settingsPreferences.settings
                    .map { it.clarityPreset }
                    .distinctUntilChanged()
                    .collect { setClarityPreset(ClarityPresets.fromIndex(it)) }
            }
            applicationScope.launch(Dispatchers.Default) {
                settingsPreferences.settings
                    .map { it.clarityAtmosBypass }
                    .distinctUntilChanged()
                    .collect { setClarityAtmosBypass(it) }
            }
            applicationScope.launch(Dispatchers.Default) {
                equalizerPreferences.settings.collect { settings ->
                    setEqualizer(settings.enabled, settings.gainsDb.toFloatArray())
                }
            }
        }
    }

    fun start(preferredOutputSampleRate: Int = 0): Boolean =
        withHandle(false) { nativeStart(it, preferredOutputSampleRate.coerceAtLeast(0)) }

    fun stop() {
        withHandle(Unit) { nativeStop(it) }
    }

    val isRunning: Boolean
        get() = withHandle(false, ::nativeIsRunning)

    fun flushOutput() {
        withHandle(Unit) { nativeFlushOutput(it) }
    }

    internal fun setPlaying(playing: Boolean) {
        withHandle(Unit) { nativeSetPlaying(it, playing) }
    }

    internal fun setOutputVolume(volume: Float) {
        withHandle(Unit) { nativeSetOutputVolume(it, volume.coerceIn(0f, 1f)) }
    }

    fun setStudioMasterClarity(enabled: Boolean) {
        withHandle(Unit) { nativeSetStudioMasterClarity(it, enabled) }
    }

    fun setBitPerfect(enabled: Boolean) {
        withHandle(Unit) { nativeSetBitPerfect(it, enabled) }
    }

    fun isBitPerfectActive(): Boolean =
        withHandle(false, ::nativeIsBitPerfect)

    fun setEqualizer(enabled: Boolean, gainsDb: FloatArray) {
        require(gainsDb.size == EQUALIZER_BAND_COUNT) { "Expected 15 equalizer bands" }
        val safeGains = FloatArray(gainsDb.size) { index ->
            val gain = gainsDb[index]
            if (gain.isFinite()) gain.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f
        }
        withHandle(Unit) { nativeSetEqualizer(it, enabled, safeGains) }
    }

    fun setClarityWet(wet: Float) {
        withHandle(Unit) { nativeSetClarityWet(it, wet.coerceIn(0f, 1f)) }
    }

    fun setClarityTrims(trimsDb: FloatArray) {
        require(trimsDb.size == ClarityPresets.TRIM_COUNT) { "Expected 8 clarity trims" }
        val safeTrims = FloatArray(trimsDb.size) { index ->
            val trim = trimsDb[index]
            if (trim.isFinite()) trim.coerceIn(-CLARITY_TRIM_MAX_DB, CLARITY_TRIM_MAX_DB) else 0f
        }
        withHandle(Unit) { nativeSetClarityTrims(it, safeTrims) }
    }

    fun setClarityPreset(preset: ClarityPreset) {
        withHandle(Unit) { nativeSetClarityPreset(it, preset.index) }
    }

    fun setClarityAtmosBypass(bypass: Boolean) {
        withHandle(Unit) { nativeSetClarityAtmosBypass(it, bypass) }
    }

    internal fun configureMediaProcessor(
        inputSampleRate: Int,
        outputSampleRate: Int,
        channelCount: Int,
    ): Boolean {
        require(inputSampleRate > 0 && outputSampleRate > 0)
        require(channelCount in 1..2)
        return withHandle(false) {
            nativeConfigureMediaProcessor(it, inputSampleRate, outputSampleRate, channelCount)
        }
    }

    internal fun processMediaPcm(
        input: ByteBuffer,
        inputByteOffset: Int,
        output: ByteBuffer,
        outputByteOffset: Int,
        frameCount: Int,
        encoding: NativePcmEncoding,
        channelCount: Int,
    ): Int {
        require(input.isDirect && output.isDirect) { "Native PCM buffers must be direct" }
        return withHandle(-1) { handle ->
            nativeProcessMediaPcm(
                handle,
                input,
                inputByteOffset,
                output,
                outputByteOffset,
                frameCount,
                encoding.nativeValue,
                channelCount,
            )
        }
    }

    internal fun flushMediaProcessor(output: ByteBuffer, channelCount: Int): Int {
        require(output.isDirect)
        return withHandle(-1) {
            nativeFlushMediaProcessor(it, output, output.position(), channelCount)
        }
    }

    internal fun resetMediaProcessor() {
        withHandle(Unit) { nativeResetMediaProcessor(it) }
    }

    fun writePcm(
        buffer: ByteBuffer,
        frameCount: Int,
        encoding: NativePcmEncoding,
        inputSampleRate: Int,
        inputChannelCount: Int,
    ): Int {
        require(buffer.isDirect) { "NativeAudioEngine requires a direct ByteBuffer" }
        require(inputChannelCount == 1 || inputChannelCount == 2) {
            "Only mono or stereo PCM is supported"
        }
        require(inputSampleRate > 0) { "inputSampleRate must be positive" }
        require(frameCount >= 0) { "frameCount must not be negative" }
        val requiredBytes = frameCount.toLong() * inputChannelCount * encoding.bytesPerSample
        require(requiredBytes <= buffer.remaining().toLong()) { "PCM buffer is too small" }
        if (frameCount == 0) return 0

        val accepted = withHandle(0) { handle ->
            nativeWritePcm(
                handle,
                buffer,
                buffer.position(),
                frameCount,
                encoding.nativeValue,
                inputSampleRate,
                inputChannelCount,
            )
        }
        if (accepted > 0) {
            buffer.position(buffer.position() + accepted * inputChannelCount * encoding.bytesPerSample)
        }
        return accepted
    }

    internal fun writeProcessedPcm(
        buffer: ByteBuffer,
        frameCount: Int,
        inputSampleRate: Int,
        inputChannelCount: Int,
    ): Int {
        require(buffer.isDirect) { "Processed PCM must use a direct ByteBuffer" }
        require(inputChannelCount in 1..2)
        require(inputSampleRate > 0)
        require(frameCount >= 0)
        val requiredBytes = frameCount.toLong() * inputChannelCount * Float.SIZE_BYTES
        require(requiredBytes <= buffer.remaining().toLong()) { "Processed PCM buffer is too small" }
        if (frameCount == 0) return 0

        val accepted = withHandle(0) { handle ->
            nativeWriteProcessedPcm(
                handle,
                buffer,
                buffer.position(),
                frameCount,
                inputSampleRate,
                inputChannelCount,
            )
        }
        if (accepted > 0) {
            buffer.position(
                buffer.position() + accepted * inputChannelCount * Float.SIZE_BYTES,
            )
        }
        return accepted
    }

    fun flushResampler() {
        withHandle(Unit) { nativeFlushResampler(it) }
    }

    val outputSampleRate: Int
        get() = withHandle(0, ::nativeOutputSampleRate)

    val requestedSampleRate: Int
        get() = withHandle(0, ::nativeRequestedSampleRate)

    val bufferedFrames: Long
        get() = withHandle(0L, ::nativeBufferedFrames)

    val renderedFrames: Long
        get() = withHandle(0L, ::nativeRenderedFrames)

    val underrunCount: Long
        get() = withHandle(0L, ::nativeUnderrunCount)

    val streamOpenCount: Long
        get() = withHandle(0L, ::nativeStreamOpenCount)

    val streamRestartCount: Long
        get() = withHandle(0L, ::nativeStreamRestartCount)

    val rateAdaptationCount: Long
        get() = withHandle(0L, ::nativeRateAdaptationCount)

    override fun close() {
        synchronized(handleLock) {
            val handle = nativeHandle
            nativeHandle = 0L
            if (handle != 0L) {
                try {
                    nativeDestroy(handle)
                } catch (error: LinkageError) {
                    Log.e(TAG, "Could not release native audio engine", error)
                }
            }
        }
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        return synchronized(handleLock) {
            val handle = nativeHandle
            if (handle == 0L) {
                fallback
            } else {
                try {
                    block(handle)
                } catch (error: LinkageError) {
                    nativeHandle = 0L
                    Log.e(TAG, "Native audio call failed; falling back to Android audio", error)
                    fallback
                }
            }
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, preferredOutputSampleRate: Int): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeFlushOutput(handle: Long)
    private external fun nativeSetPlaying(handle: Long, playing: Boolean)
    private external fun nativeSetOutputVolume(handle: Long, volume: Float)
    private external fun nativeSetStudioMasterClarity(handle: Long, enabled: Boolean)
    private external fun nativeSetBitPerfect(handle: Long, enabled: Boolean)
    private external fun nativeIsBitPerfect(handle: Long): Boolean
    private external fun nativeSetEqualizer(handle: Long, enabled: Boolean, gainsDb: FloatArray)
    private external fun nativeSetClarityWet(handle: Long, wet: Float)
    private external fun nativeSetClarityTrims(handle: Long, trimsDb: FloatArray)
    private external fun nativeSetClarityPreset(handle: Long, preset: Int)
    private external fun nativeSetClarityAtmosBypass(handle: Long, bypass: Boolean)
    private external fun nativeConfigureMediaProcessor(
        handle: Long,
        inputSampleRate: Int,
        outputSampleRate: Int,
        channelCount: Int,
    ): Boolean
    private external fun nativeProcessMediaPcm(
        handle: Long,
        input: ByteBuffer,
        inputByteOffset: Int,
        output: ByteBuffer,
        outputByteOffset: Int,
        frameCount: Int,
        encoding: Int,
        channelCount: Int,
    ): Int
    private external fun nativeFlushMediaProcessor(
        handle: Long,
        output: ByteBuffer,
        outputByteOffset: Int,
        channelCount: Int,
    ): Int
    private external fun nativeResetMediaProcessor(handle: Long)
    private external fun nativeWritePcm(
        handle: Long,
        buffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int,
        encoding: Int,
        inputSampleRate: Int,
        inputChannelCount: Int,
    ): Int
    private external fun nativeWriteProcessedPcm(
        handle: Long,
        buffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int,
        inputSampleRate: Int,
        inputChannelCount: Int,
    ): Int
    private external fun nativeFlushResampler(handle: Long)
    private external fun nativeOutputSampleRate(handle: Long): Int
    private external fun nativeRequestedSampleRate(handle: Long): Int
    private external fun nativeBufferedFrames(handle: Long): Long
    private external fun nativeUnderrunCount(handle: Long): Long
    private external fun nativeRenderedFrames(handle: Long): Long
    private external fun nativeStreamOpenCount(handle: Long): Long
    private external fun nativeStreamRestartCount(handle: Long): Long
    private external fun nativeRateAdaptationCount(handle: Long): Long

    companion object {
        const val TAG = "NativeAudioEngine"
        const val EQUALIZER_BAND_COUNT = 15
        const val CLARITY_TRIM_MAX_DB = 12f

        val libraryLoaded = try {
            System.loadLibrary("lastwave_audio")
            true
        } catch (error: LinkageError) {
            Log.e(TAG, "Could not load native audio library", error)
            false
        } catch (error: SecurityException) {
            Log.e(TAG, "Native audio loading was blocked", error)
            false
        }
    }
}
