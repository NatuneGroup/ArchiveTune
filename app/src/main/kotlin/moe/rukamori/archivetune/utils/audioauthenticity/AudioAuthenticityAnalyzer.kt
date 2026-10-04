/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils.audioauthenticity

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

enum class AudioAuthenticityVerdict {
    UNKNOWN,
    NO_SUSPICIOUS_CUTOFF,
    SUSPECTED_UPSCALED,
}

data class AudioAuthenticityAssessment(
    val verdict: AudioAuthenticityVerdict,
    val confidence: Double,
    val cutoffFrequencyHz: Int? = null,
    val activeWindows: Int = 0,
)

object AudioAuthenticityAnalyzer {
    const val MIN_REJECTION_CONFIDENCE = 0.92
    internal const val MIN_ANALYSIS_SAMPLE_RATE_HZ = 88_200
    internal const val MIN_LOSSLESS_SAMPLE_RATE_HZ = 44_100
    internal const val ANALYSIS_WINDOW_FRAMES = 8_192
    internal const val MIN_ANALYSIS_FRAMES = ANALYSIS_WINDOW_FRAMES * 12
    internal const val MAX_ANALYSIS_FRAMES = ANALYSIS_WINDOW_FRAMES * 48
    internal const val REANALYSIS_INTERVAL_FRAMES = ANALYSIS_WINDOW_FRAMES * 8

    private const val CD_BAND_EDGE_HZ = 22_050
    private const val WINDOW_SIZE = ANALYSIS_WINDOW_FRAMES
    private const val MAX_WINDOWS = MAX_ANALYSIS_FRAMES / WINDOW_SIZE
    private const val MIN_ACTIVE_WINDOWS = MIN_ANALYSIS_FRAMES / WINDOW_SIZE
    private const val MIN_EDGE_WINDOWS = 8
    private const val MIN_ACTIVE_RMS = 0.00001
    private const val MIN_EDGE_EXTENSION_DB = -50.0
    private const val MIN_CUTOFF_ATTENUATION_DB = 24.0
    private const val MIN_CUTOFF_CONSISTENCY = 0.75
    private const val PRE_BAND_LOW_HZ = 18_000
    private const val PRE_BAND_HIGH_HZ = 21_500
    private const val POST_BAND_LOW_HZ = 23_000
    private const val POST_BAND_HIGH_HZ = 32_000
    private const val REFERENCE_BAND_LOW_HZ = 4_000
    private const val REFERENCE_BAND_HIGH_HZ = 15_000
    private const val CODEC_REFERENCE_BAND_LOW_HZ = 4_000
    private const val CODEC_REFERENCE_BAND_HIGH_HZ = 12_500
    private const val CODEC_EDGE_BAND_LOW_HZ = 15_000
    private const val CODEC_EDGE_BAND_HIGH_HZ = 21_500
    private const val CODEC_PRE_BAND_BELOW_HZ = 1_000
    private const val CODEC_PRE_BAND_MARGIN_HZ = 200
    private const val CODEC_POST_BAND_MARGIN_HZ = 250
    private const val CODEC_POST_BAND_WIDTH_HZ = 1_000
    private const val MIN_CODEC_EDGE_EXTENSION_DB = -42.0
    private const val MIN_CODEC_CUTOFF_ATTENUATION_DB = 30.0
    private const val MIN_CODEC_CUTOFF_CONSISTENCY = 0.80
    private val codecCutoffCandidatesHz = intArrayOf(
        15_500,
        16_000,
        16_500,
        17_000,
        17_500,
        18_000,
        18_500,
        19_000,
        19_500,
        20_000,
        20_500,
    )

    private val window = DoubleArray(WINDOW_SIZE) { index ->
        0.5 * (1.0 - cos(2.0 * PI * index / (WINDOW_SIZE - 1)))
    }

    private data class CodecCutoffStats(
        val cutoffFrequencyHz: Int,
        var extensionWindows: Int = 0,
        var cutoffWindows: Int = 0,
        var prePower: Double = 0.0,
        var postPower: Double = 0.0,
        var referencePower: Double = 0.0,
    )

    fun analyzeInterleavedPcm(
        samples: FloatArray,
        pcmSampleRateHz: Int,
        channelCount: Int,
        advertisedSampleRateHz: Int?,
        claimedLossless: Boolean = false,
    ): AudioAuthenticityAssessment {
        if (!claimedLossless || channelCount !in 1..8 ||
            pcmSampleRateHz < MIN_LOSSLESS_SAMPLE_RATE_HZ ||
            advertisedSampleRateHz == null || advertisedSampleRateHz < MIN_LOSSLESS_SAMPLE_RATE_HZ
        ) {
            return unknown()
        }
        return if (pcmSampleRateHz >= MIN_ANALYSIS_SAMPLE_RATE_HZ &&
            advertisedSampleRateHz >= MIN_ANALYSIS_SAMPLE_RATE_HZ
        ) {
            analyzeHiResInterleavedPcm(samples, pcmSampleRateHz, channelCount)
        } else {
            analyzeStandardRateLosslessPcm(samples, pcmSampleRateHz, channelCount)
        }
    }

    private fun analyzeHiResInterleavedPcm(
        samples: FloatArray,
        pcmSampleRateHz: Int,
        channelCount: Int,
    ): AudioAuthenticityAssessment {
        val totalFrames = samples.size / channelCount
        val maximumFrames = minOf(totalFrames, WINDOW_SIZE * MAX_WINDOWS)
        if (maximumFrames < WINDOW_SIZE * MIN_ACTIVE_WINDOWS) return unknown()

        val real = DoubleArray(WINDOW_SIZE)
        val imaginary = DoubleArray(WINDOW_SIZE)
        var activeWindows = 0
        var extensionWindows = 0
        var cutoffWindows = 0
        var preBandPower = 0.0
        var postBandPower = 0.0
        var referenceBandPower = 0.0
        var windowsAnalyzed = 0

        var startFrame = 0
        while (startFrame + WINDOW_SIZE <= maximumFrames && windowsAnalyzed < MAX_WINDOWS) {
            var framePower = 0.0
            var finiteWindow = true
            for (index in 0 until WINDOW_SIZE) {
                var samplePower = 0.0
                val sampleOffset = (startFrame + index) * channelCount
                for (channel in 0 until channelCount) {
                    val sample = samples[sampleOffset + channel].toDouble()
                    if (!sample.isFinite()) {
                        finiteWindow = false
                        break
                    }
                    samplePower += sample * sample
                }
                if (!finiteWindow) break
                framePower += samplePower / channelCount
            }
            windowsAnalyzed++
            startFrame += WINDOW_SIZE
            if (!finiteWindow) continue
            val frameRms = sqrt(framePower / WINDOW_SIZE)
            if (frameRms < MIN_ACTIVE_RMS) continue
            activeWindows++

            var windowPrePower = 0.0
            var windowPostPower = 0.0
            var windowReferencePower = 0.0
            for (channel in 0 until channelCount) {
                for (index in 0 until WINDOW_SIZE) {
                    val sample = samples[((startFrame - WINDOW_SIZE + index) * channelCount) + channel].toDouble()
                    real[index] = sample * window[index]
                    imaginary[index] = 0.0
                }
                fft(real, imaginary)
                windowPrePower += bandPower(real, imaginary, pcmSampleRateHz, PRE_BAND_LOW_HZ, PRE_BAND_HIGH_HZ)
                windowPostPower += bandPower(real, imaginary, pcmSampleRateHz, POST_BAND_LOW_HZ, POST_BAND_HIGH_HZ)
                windowReferencePower += bandPower(real, imaginary, pcmSampleRateHz, REFERENCE_BAND_LOW_HZ, REFERENCE_BAND_HIGH_HZ)
            }
            if (windowPrePower <= 0.0 || windowReferencePower <= 0.0) continue

            val extensionDb = ratioDb(windowPrePower, windowReferencePower)
            if (extensionDb < MIN_EDGE_EXTENSION_DB) continue
            extensionWindows++
            preBandPower += windowPrePower
            postBandPower += windowPostPower
            referenceBandPower += windowReferencePower
            if (ratioDb(windowPrePower, windowPostPower) >= MIN_CUTOFF_ATTENUATION_DB) cutoffWindows++
        }

        if (activeWindows < MIN_ACTIVE_WINDOWS || extensionWindows < MIN_EDGE_WINDOWS) return unknown(activeWindows)

        val attenuationDb = ratioDb(preBandPower, postBandPower)
        val extensionDb = ratioDb(preBandPower, referenceBandPower)
        val consistency = cutoffWindows.toDouble() / extensionWindows
        if (attenuationDb < MIN_CUTOFF_ATTENUATION_DB || consistency < MIN_CUTOFF_CONSISTENCY) {
            return AudioAuthenticityAssessment(
                verdict = AudioAuthenticityVerdict.NO_SUSPICIOUS_CUTOFF,
                confidence = (1.0 - (attenuationDb / 60.0).coerceIn(0.0, 1.0)).coerceIn(0.0, 0.8),
                activeWindows = activeWindows,
            )
        }

        val attenuationConfidence = ((attenuationDb - MIN_CUTOFF_ATTENUATION_DB) / 30.0).coerceIn(0.0, 1.0)
        val extensionConfidence = ((extensionDb - MIN_EDGE_EXTENSION_DB) / 15.0).coerceIn(0.0, 1.0)
        val confidence = (0.55 * attenuationConfidence + 0.25 * extensionConfidence + 0.20 * consistency).coerceIn(0.0, 1.0)
        return AudioAuthenticityAssessment(
            verdict = AudioAuthenticityVerdict.SUSPECTED_UPSCALED,
            confidence = confidence,
            cutoffFrequencyHz = CD_BAND_EDGE_HZ,
            activeWindows = activeWindows,
        )
    }

    private fun analyzeStandardRateLosslessPcm(
        samples: FloatArray,
        pcmSampleRateHz: Int,
        channelCount: Int,
    ): AudioAuthenticityAssessment {
        val totalFrames = samples.size / channelCount
        val maximumFrames = minOf(totalFrames, MAX_ANALYSIS_FRAMES)
        if (maximumFrames < MIN_ANALYSIS_FRAMES) return unknown()

        val nyquistHz = pcmSampleRateHz / 2
        val cutoffs =
            codecCutoffCandidatesHz.filter {
                it + CODEC_POST_BAND_MARGIN_HZ + CODEC_POST_BAND_WIDTH_HZ <= nyquistHz - 100
            }
        if (cutoffs.isEmpty()) return unknown()

        val cutoffStats = cutoffs.map { CodecCutoffStats(it) }
        val real = DoubleArray(WINDOW_SIZE)
        val imaginary = DoubleArray(WINDOW_SIZE)
        var activeWindows = 0
        var broadbandExtensionWindows = 0
        var windowsAnalyzed = 0
        var startFrame = 0

        while (startFrame + WINDOW_SIZE <= maximumFrames && windowsAnalyzed < MAX_WINDOWS) {
            var framePower = 0.0
            var finiteWindow = true
            for (index in 0 until WINDOW_SIZE) {
                var samplePower = 0.0
                val sampleOffset = (startFrame + index) * channelCount
                for (channel in 0 until channelCount) {
                    val sample = samples[sampleOffset + channel].toDouble()
                    if (!sample.isFinite()) {
                        finiteWindow = false
                        break
                    }
                    samplePower += sample * sample
                }
                if (!finiteWindow) break
                framePower += samplePower / channelCount
            }
            windowsAnalyzed++
            startFrame += WINDOW_SIZE
            if (!finiteWindow || sqrt(framePower / WINDOW_SIZE) < MIN_ACTIVE_RMS) continue
            activeWindows++

            var referencePower = 0.0
            var broadEdgePower = 0.0
            val prePowers = DoubleArray(cutoffs.size)
            val postPowers = DoubleArray(cutoffs.size)
            for (channel in 0 until channelCount) {
                for (index in 0 until WINDOW_SIZE) {
                    val sample = samples[((startFrame - WINDOW_SIZE + index) * channelCount) + channel].toDouble()
                    real[index] = sample * window[index]
                    imaginary[index] = 0.0
                }
                fft(real, imaginary)
                referencePower +=
                    bandPower(
                        real,
                        imaginary,
                        pcmSampleRateHz,
                        CODEC_REFERENCE_BAND_LOW_HZ,
                        CODEC_REFERENCE_BAND_HIGH_HZ,
                    )
                broadEdgePower +=
                    bandPower(
                        real,
                        imaginary,
                        pcmSampleRateHz,
                        CODEC_EDGE_BAND_LOW_HZ,
                        minOf(CODEC_EDGE_BAND_HIGH_HZ, nyquistHz - 100),
                    )
                for (candidateIndex in cutoffs.indices) {
                    val cutoffHz = cutoffs[candidateIndex]
                    prePowers[candidateIndex] +=
                        bandPower(
                            real,
                            imaginary,
                            pcmSampleRateHz,
                            cutoffHz - CODEC_PRE_BAND_BELOW_HZ,
                            cutoffHz - CODEC_PRE_BAND_MARGIN_HZ,
                        )
                    postPowers[candidateIndex] +=
                        bandPower(
                            real,
                            imaginary,
                            pcmSampleRateHz,
                            cutoffHz + CODEC_POST_BAND_MARGIN_HZ,
                            cutoffHz + CODEC_POST_BAND_MARGIN_HZ + CODEC_POST_BAND_WIDTH_HZ,
                        )
                }
            }
            if (referencePower <= 0.0) continue
            if (ratioDb(broadEdgePower, referencePower) >= MIN_CODEC_EDGE_EXTENSION_DB) {
                broadbandExtensionWindows++
            }
            for (candidateIndex in cutoffs.indices) {
                val prePower = prePowers[candidateIndex]
                if (prePower <= 0.0 || ratioDb(prePower, referencePower) < MIN_CODEC_EDGE_EXTENSION_DB) continue
                val stats = cutoffStats[candidateIndex]
                stats.extensionWindows++
                stats.prePower += prePower
                stats.postPower += postPowers[candidateIndex]
                stats.referencePower += referencePower
                if (ratioDb(prePower, postPowers[candidateIndex]) >= MIN_CODEC_CUTOFF_ATTENUATION_DB) {
                    stats.cutoffWindows++
                }
            }
        }

        if (activeWindows < MIN_ACTIVE_WINDOWS) return unknown(activeWindows)

        val suspected =
            cutoffStats
                .asSequence()
                .filter { it.extensionWindows >= MIN_EDGE_WINDOWS }
                .mapNotNull { stats ->
                    val attenuationDb = ratioDb(stats.prePower, stats.postPower)
                    val extensionDb = ratioDb(stats.prePower, stats.referencePower)
                    val consistency = stats.cutoffWindows.toDouble() / stats.extensionWindows
                    if (attenuationDb < MIN_CODEC_CUTOFF_ATTENUATION_DB ||
                        consistency < MIN_CODEC_CUTOFF_CONSISTENCY
                    ) {
                        null
                    } else {
                        val attenuationConfidence =
                            ((attenuationDb - MIN_CODEC_CUTOFF_ATTENUATION_DB) / 25.0).coerceIn(0.0, 1.0)
                        val extensionConfidence = ((extensionDb + 42.0) / 24.0).coerceIn(0.0, 1.0)
                        val consistencyConfidence =
                            ((consistency - MIN_CODEC_CUTOFF_CONSISTENCY) / 0.20).coerceIn(0.0, 1.0)
                        AudioAuthenticityAssessment(
                            verdict = AudioAuthenticityVerdict.SUSPECTED_UPSCALED,
                            confidence =
                                (0.55 * attenuationConfidence +
                                    0.25 * extensionConfidence +
                                    0.20 * consistencyConfidence
                                ).coerceIn(0.0, 1.0),
                            cutoffFrequencyHz = stats.cutoffFrequencyHz,
                            activeWindows = activeWindows,
                        )
                    }
                }.maxByOrNull { it.confidence }
        if (suspected != null) return suspected
        if (broadbandExtensionWindows >= MIN_EDGE_WINDOWS) {
            return AudioAuthenticityAssessment(
                verdict = AudioAuthenticityVerdict.NO_SUSPICIOUS_CUTOFF,
                confidence = 0.0,
                activeWindows = activeWindows,
            )
        }
        return unknown(activeWindows)
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while ((reversed and bit) != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var stageSize = 2
        while (stageSize <= size) {
            val angle = -2.0 * PI / stageSize
            val stageCosine = cos(angle)
            val stageSine = sin(angle)
            val halfSize = stageSize shr 1
            var stageStart = 0
            while (stageStart < size) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until halfSize) {
                    val evenIndex = stageStart + offset
                    val oddIndex = evenIndex + halfSize
                    val oddReal = real[oddIndex] * twiddleReal - imaginary[oddIndex] * twiddleImaginary
                    val oddImaginary = real[oddIndex] * twiddleImaginary + imaginary[oddIndex] * twiddleReal
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]
                    real[evenIndex] = evenReal + oddReal
                    imaginary[evenIndex] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imaginary[oddIndex] = evenImaginary - oddImaginary
                    val nextTwiddleReal = twiddleReal * stageCosine - twiddleImaginary * stageSine
                    twiddleImaginary = twiddleReal * stageSine + twiddleImaginary * stageCosine
                    twiddleReal = nextTwiddleReal
                }
                stageStart += stageSize
            }
            stageSize = stageSize shl 1
        }
    }

    private fun bandPower(
        real: DoubleArray,
        imaginary: DoubleArray,
        sampleRateHz: Int,
        lowFrequencyHz: Int,
        highFrequencyHz: Int,
    ): Double {
        val firstBin = ((lowFrequencyHz.toLong() * WINDOW_SIZE) / sampleRateHz).toInt().coerceAtLeast(1)
        val lastBin = ((highFrequencyHz.toLong() * WINDOW_SIZE) / sampleRateHz).toInt().coerceAtMost(WINDOW_SIZE / 2)
        if (firstBin > lastBin) return 0.0
        var sum = 0.0
        for (bin in firstBin..lastBin) {
            val realPart = real[bin]
            val imaginaryPart = imaginary[bin]
            sum += realPart * realPart + imaginaryPart * imaginaryPart
        }
        return sum
    }

    private fun ratioDb(numerator: Double, denominator: Double): Double {
        if (numerator <= 0.0) return Double.NEGATIVE_INFINITY
        if (denominator <= 0.0) return 60.0
        return (10.0 * log10(numerator / denominator)).coerceIn(-120.0, 60.0)
    }

    private fun unknown(activeWindows: Int = 0) =
        AudioAuthenticityAssessment(
            verdict = AudioAuthenticityVerdict.UNKNOWN,
            confidence = 0.0,
            activeWindows = activeWindows,
        )
}

object AudioAuthenticityPolicy {
    fun shouldReject(
        assessment: AudioAuthenticityAssessment,
        rejectSuspectedUpscaled: Boolean,
    ): Boolean =
        rejectSuspectedUpscaled &&
            assessment.verdict == AudioAuthenticityVerdict.SUSPECTED_UPSCALED &&
            assessment.confidence >= AudioAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE
}
