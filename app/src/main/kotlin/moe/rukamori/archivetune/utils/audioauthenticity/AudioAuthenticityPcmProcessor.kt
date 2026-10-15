/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.utils.audioauthenticity

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.min

data class AudioAuthenticityPlaybackContext(
    val playbackKey: String,
    val sourceId: String,
    val rejectSuspectedUpscaled: Boolean,
    val advertisedSampleRateHz: Int?,
    val claimedLossless: Boolean = false,
)

data class AudioAuthenticityProcessorResult(
    val playbackKey: String,
    val sourceId: String,
    val assessment: AudioAuthenticityAssessment,
    val shouldReject: Boolean,
)

class AudioAuthenticityPcmProcessor(
    private val playbackContextProvider: () -> AudioAuthenticityPlaybackContext?,
    private val resultListener: (AudioAuthenticityProcessorResult) -> Unit,
) : BaseAudioProcessor() {
    private val lock = Any()
    private var analysisSamples = FloatArray(0)
    private var capturedFrames = 0
    private var playbackKey: String? = null
    private var sourceId: String? = null
    private var advertisedSampleRateHz: Int? = null
    private var claimedLossless: Boolean? = null
    private var generation = 0L
    private var nextAnalysisFrame = AudioAuthenticityAnalyzer.MIN_ANALYSIS_FRAMES
    private var analysisInFlight = false
    private var resultDelivered = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        clearCapture()
        if ((inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.channelCount !in 1..2
        ) {
            reportUnsupportedFormatAsUnknown()
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        clearCapture()
    }

    override fun onReset() {
        clearCapture()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val context = runCatching { playbackContextProvider() }.getOrNull()
        if (context != null && isAnalysisEligible(inputAudioFormat, context)) {
            if (captureCanAccept(context)) {
                collect(inputBuffer.duplicate().order(ByteOrder.nativeOrder()), context)
            }
        } else {
            clearCaptureIfActive()
        }
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    private fun isAnalysisEligible(
        format: AudioProcessor.AudioFormat,
        context: AudioAuthenticityPlaybackContext,
    ): Boolean =
        context.rejectSuspectedUpscaled && context.claimedLossless &&
            context.playbackKey.isNotBlank() && context.sourceId.isNotBlank() &&
            format.encoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) &&
            format.channelCount in 1..2 &&
            format.sampleRate >= AudioAuthenticityAnalyzer.MIN_LOSSLESS_SAMPLE_RATE_HZ &&
            context.advertisedSampleRateHz?.let {
                it >= AudioAuthenticityAnalyzer.MIN_LOSSLESS_SAMPLE_RATE_HZ
            } == true

    private fun captureCanAccept(context: AudioAuthenticityPlaybackContext): Boolean =
        synchronized(lock) {
            playbackKey != context.playbackKey || sourceId != context.sourceId ||
                advertisedSampleRateHz != context.advertisedSampleRateHz || claimedLossless != context.claimedLossless ||
                (!resultDelivered && capturedFrames < AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES)
        }

    private fun collect(inputBuffer: ByteBuffer, context: AudioAuthenticityPlaybackContext) {
        val format = inputAudioFormat
        val sampleRate = format.sampleRate
        val channels = format.channelCount
        var snapshot: AnalysisSnapshot? = null
        synchronized(lock) {
            if (playbackKey != context.playbackKey || sourceId != context.sourceId ||
                advertisedSampleRateHz != context.advertisedSampleRateHz ||
                claimedLossless != context.claimedLossless
            ) {
                clearCaptureLocked()
                playbackKey = context.playbackKey
                sourceId = context.sourceId
                advertisedSampleRateHz = context.advertisedSampleRateHz
                claimedLossless = context.claimedLossless
                analysisSamples = FloatArray(AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES * channels)
            }
            if (resultDelivered || capturedFrames >= AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES) {
                return
            }

            val bytesPerSample = if (format.encoding == C.ENCODING_PCM_16BIT) 2 else 4
            val bytesPerFrame = bytesPerSample * channels
            val framesToCopy =
                min(
                    inputBuffer.remaining() / bytesPerFrame,
                    AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES - capturedFrames,
                )
            val initialPosition = inputBuffer.position()
            for (frame in 0 until framesToCopy) {
                for (channel in 0 until channels) {
                    val byteOffset = initialPosition + frame * bytesPerFrame + channel * bytesPerSample
                    analysisSamples[(capturedFrames + frame) * channels + channel] =
                        if (format.encoding == C.ENCODING_PCM_16BIT) {
                            inputBuffer.getShort(byteOffset) / 32768f
                        } else {
                            inputBuffer.getFloat(byteOffset)
                        }
                }
            }
            capturedFrames += framesToCopy
            snapshot = snapshotIfReadyLocked(sampleRate, channels, context)
        }
        snapshot?.let(::analyze)
    }

    private fun snapshotIfReadyLocked(
        sampleRateHz: Int,
        channels: Int,
        context: AudioAuthenticityPlaybackContext,
    ): AnalysisSnapshot? {
        if (analysisInFlight || resultDelivered || capturedFrames < nextAnalysisFrame) return null
        analysisInFlight = true
        return AnalysisSnapshot(
            generation = generation,
            playbackKey = context.playbackKey,
            sourceId = context.sourceId,
            pcmSampleRateHz = sampleRateHz,
            advertisedSampleRateHz = context.advertisedSampleRateHz,
            claimedLossless = context.claimedLossless,
            channelCount = channels,
            frameCount = capturedFrames,
            samples = analysisSamples.copyOf(capturedFrames * channels),
            rejectSuspectedUpscaledAudio = context.rejectSuspectedUpscaled,
        )
    }

    private fun analyze(snapshot: AnalysisSnapshot) {
        ANALYSIS_EXECUTOR.execute {
            val (assessment, analysisFailed) =
                runCatching {
                    AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
                        samples = snapshot.samples,
                        pcmSampleRateHz = snapshot.pcmSampleRateHz,
                        channelCount = snapshot.channelCount,
                        advertisedSampleRateHz = snapshot.advertisedSampleRateHz,
                        claimedLossless = snapshot.claimedLossless,
                    )
                }.fold(
                    onSuccess = { it to false },
                    onFailure = {
                        AudioAuthenticityAssessment(AudioAuthenticityVerdict.UNKNOWN, confidence = 0.0) to true
                    },
                )
            var nextSnapshot: AnalysisSnapshot? = null
            var result: AudioAuthenticityProcessorResult? = null
            synchronized(lock) {
                if (snapshot.generation != generation) return@execute
                analysisInFlight = false
                if (analysisFailed || assessment.verdict != AudioAuthenticityVerdict.UNKNOWN ||
                    snapshot.frameCount >= AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES
                ) {
                    resultDelivered = true
                    val currentContext = runCatching { playbackContextProvider() }.getOrNull()
                    val rejectionEnabled =
                        currentContext != null &&
                            currentContext.playbackKey == snapshot.playbackKey &&
                            currentContext.sourceId == snapshot.sourceId &&
                            currentContext.rejectSuspectedUpscaled &&
                            currentContext.claimedLossless == snapshot.claimedLossless
                    result = AudioAuthenticityProcessorResult(
                        playbackKey = snapshot.playbackKey,
                        sourceId = snapshot.sourceId,
                        assessment = assessment,
                        shouldReject = AudioAuthenticityPolicy.shouldReject(
                            assessment = assessment,
                            rejectSuspectedUpscaled = rejectionEnabled,
                        ),
                    )
                } else {
                    nextAnalysisFrame =
                        min(
                            AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES,
                            snapshot.frameCount + AudioAuthenticityAnalyzer.REANALYSIS_INTERVAL_FRAMES,
                    )
                    val currentContext = runCatching { playbackContextProvider() }.getOrNull()
                    if (currentContext != null) {
                        if (currentContext.playbackKey == playbackKey &&
                            currentContext.sourceId == sourceId &&
                            currentContext.rejectSuspectedUpscaled &&
                            currentContext.claimedLossless == claimedLossless &&
                            currentContext.advertisedSampleRateHz == advertisedSampleRateHz
                        ) {
                            nextSnapshot =
                                snapshotIfReadyLocked(
                                    sampleRateHz = snapshot.pcmSampleRateHz,
                                    channels = snapshot.channelCount,
                                    context = currentContext,
                                )
                        }
                    }
                }
            }
            result?.let { delivered -> runCatching { resultListener(delivered) } }
            nextSnapshot?.let(::analyze)
        }
    }

    private fun clearCapture() {
        synchronized(lock) { clearCaptureLocked() }
    }

    private fun clearCaptureIfActive() {
        synchronized(lock) {
            if (playbackKey == null && analysisSamples.isEmpty() && !analysisInFlight && !resultDelivered) return
            clearCaptureLocked()
        }
    }

    private fun reportUnsupportedFormatAsUnknown() {
        val context = runCatching { playbackContextProvider() }.getOrNull() ?: return
        if (!context.rejectSuspectedUpscaled || !context.claimedLossless ||
            context.playbackKey.isBlank() || context.sourceId.isBlank()
        ) {
            return
        }
        runCatching {
            resultListener(
                AudioAuthenticityProcessorResult(
                    playbackKey = context.playbackKey,
                    sourceId = context.sourceId,
                    assessment = AudioAuthenticityAssessment(AudioAuthenticityVerdict.UNKNOWN, confidence = 0.0),
                    shouldReject = false,
                ),
            )
        }
    }

    private fun clearCaptureLocked() {
        generation++
        analysisSamples = FloatArray(0)
        capturedFrames = 0
        playbackKey = null
        sourceId = null
        advertisedSampleRateHz = null
        claimedLossless = null
        nextAnalysisFrame = AudioAuthenticityAnalyzer.MIN_ANALYSIS_FRAMES
        analysisInFlight = false
        resultDelivered = false
    }

    private data class AnalysisSnapshot(
        val generation: Long,
        val playbackKey: String,
        val sourceId: String,
        val pcmSampleRateHz: Int,
        val advertisedSampleRateHz: Int?,
        val claimedLossless: Boolean,
        val channelCount: Int,
        val frameCount: Int,
        val samples: FloatArray,
        val rejectSuspectedUpscaledAudio: Boolean,
    )

    private companion object {
        val ANALYSIS_EXECUTOR: Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "audio-authenticity-analysis").apply { isDaemon = true }
            }
    }
}
