/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityAnalyzer
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityVerdict

internal object AcousticSpectrogramAnalyzer {
    private const val FFT_SIZE = 2048
    private const val BINS = FFT_SIZE / 2
    private const val FLOOR_DB = AcousticHeatPalette.FLOOR_DB

    fun analyze(
        mediaId: String,
        sourceLabel: String,
        advertisedRate: Int?,
        claimedLossless: Boolean,
        sourceLimitations: List<String>,
        pcm: AcousticPcm,
        checkActive: () -> Unit,
        progress: (Float) -> Unit = {},
    ): AcousticAuditResult {
        checkActive()
        require(pcm.sampleRate in 8_000..192_000 && pcm.channelCount in 1..8)
        require(pcm.frames in FFT_SIZE..AcousticAuditLimits.MAX_PCM_FRAMES)
        require(pcm.frames.toLong() * pcm.channelCount <= pcm.samples.size)
        require(pcm.samples.size.toLong() * Float.SIZE_BYTES <= AcousticAuditLimits.MAX_PCM_BYTES)
        val possibleWindows = (pcm.frames - FFT_SIZE) / (FFT_SIZE / 2) + 1
        val width = possibleWindows.coerceIn(1, AcousticAuditLimits.MAX_HEAT_WIDTH)
        val heat = FloatArray(width * BINS)
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        val normalization = window.sum().let { it * it }
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val powers = DoubleArray(BINS)
        val meanPower = DoubleArray(BINS)
        var activeWindows = 0
        for (column in 0 until width) {
            checkActive()
            // Thin time windows, not the PCM rate: high-resolution bandwidth remains observable.
            val start = if (width == 1) 0 else ((pcm.frames - FFT_SIZE).toLong() * column / (width - 1)).toInt()
            powers.fill(0.0)
            var framePower = 0.0
            for (channel in 0 until pcm.channelCount) {
                checkActive()
                for (index in 0 until FFT_SIZE) {
                    val sample = pcm.samples[(start + index) * pcm.channelCount + channel].toDouble()
                    require(sample.isFinite())
                    real[index] = sample * window[index]
                    imaginary[index] = 0.0
                    framePower += sample * sample
                }
                fft(real, imaginary)
                for (bin in 0 until BINS) {
                    powers[bin] += (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) /
                        (normalization * pcm.channelCount)
                }
            }
            val active = framePower / (FFT_SIZE * pcm.channelCount) > 1e-10
            if (active) activeWindows++
            for (bin in 0 until BINS) {
                val power = powers[bin]
                // 1e-30, not 1e-10: a 1e-10 power clamp would stop heat at -100 dB, so -120 would be unreachable.
                heat[bin * width + column] =
                    (10 * log10(power.coerceAtLeast(1e-30)) + AcousticHeatPalette.WINDOW_OFFSET_DB)
                        .toFloat().coerceIn(FLOOR_DB, AcousticHeatPalette.CEILING_DB)
                if (active) meanPower[bin] += power
            }
            progress((column + 1f) / width)
        }
        checkActive()
        val adequate = activeWindows >= 12 && pcm.frames >= pcm.sampleRate * 2
        val lastActiveBin = if (adequate) meanPower.indexOfLast {
            it / activeWindows > 3.162277660168379e-7 // -65 dB relative to normalized PCM amplitude.
        } else -1
        val cutoff = lastActiveBin.takeIf { it > 0 }?.let { it.toFloat() * pcm.sampleRate / FFT_SIZE }
        // The existing analyzer reads at most MAX_ANALYSIS_FRAMES. Only invoke it when that
        // entire prefix is populated: unused capacity must not masquerade as decoded silence.
        val assessment = if (adequate && pcm.frames >= AudioAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES) {
            when (AudioAuthenticityAnalyzer.analyzeInterleavedPcm(
                pcm.samples, pcm.sampleRate, pcm.channelCount, advertisedRate, claimedLossless, checkActive,
            ).verdict) {
                AudioAuthenticityVerdict.UNKNOWN -> AcousticAssessment.INCONCLUSIVE
                AudioAuthenticityVerdict.NO_SUSPICIOUS_CUTOFF -> AcousticAssessment.NO_SUSPICIOUS_CUTOFF
                AudioAuthenticityVerdict.SUSPECTED_UPSCALED -> AcousticAssessment.SUSPECTED_UPSCALED
            }
        } else AcousticAssessment.INCONCLUSIVE
        checkActive()
        return AcousticAuditResult(
            mediaId = mediaId,
            sourceLabel = sourceLabel,
            sampleRate = pcm.sampleRate,
            channelCount = pcm.channelCount,
            startMs = pcm.startUs / 1000,
            durationMs = pcm.frames * 1000L / pcm.sampleRate,
            cutoffHz = cutoff,
            nyquistHz = pcm.sampleRate / 2f,
            width = width,
            height = BINS,
            magnitudeDb = heat,
            assessment = assessment,
            limitations = buildList {
                addAll(sourceLimitations)
                add("spectral_heuristic_not_provenance")
                add("normalized_window_power")
                add("time_windows_may_be_thinned")
                add("assessment_uses_bounded_prefix")
                add(if (pcm.isFloat) "decoded_pcm_float" else "decoded_pcm16")
                if (!adequate) add("insufficient_active_evidence")
                if (assessment == AcousticAssessment.INCONCLUSIVE) add("unknown")
                if (pcm.truncated) add("pcm_budget_shortened_excerpt")
            },
        )
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var reversed = 0
        for (index in 1 until real.size) {
            var bit = real.size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val r = real[index]
                real[index] = real[reversed]
                real[reversed] = r
                val i = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = i
            }
        }
        var size = 2
        while (size <= real.size) {
            val angle = -2 * PI / size
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in real.indices step size) {
                var rotationReal = 1.0
                var rotationImaginary = 0.0
                for (offset in 0 until size / 2) {
                    val left = start + offset
                    val right = left + size / 2
                    val r = real[right] * rotationReal - imaginary[right] * rotationImaginary
                    val i = real[right] * rotationImaginary + imaginary[right] * rotationReal
                    real[right] = real[left] - r
                    imaginary[right] = imaginary[left] - i
                    real[left] += r
                    imaginary[left] += i
                    val next = rotationReal * stepReal - rotationImaginary * stepImaginary
                    rotationImaginary = rotationReal * stepImaginary + rotationImaginary * stepReal
                    rotationReal = next
                }
            }
            size = size shl 1
        }
    }
}
