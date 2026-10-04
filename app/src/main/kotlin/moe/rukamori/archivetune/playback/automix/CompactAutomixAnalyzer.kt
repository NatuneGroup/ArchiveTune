/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import moe.rukamori.archivetune.constants.AutomixPerformanceMode
import moe.rukamori.archivetune.playback.smart.EnergySample
import moe.rukamori.archivetune.playback.smart.MixCandidate
import moe.rukamori.archivetune.playback.smart.TrackFeatures
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object CompactAutomixAnalyzer {
    private const val SAMPLE_RATE = 11_025
    private const val MAX_ANALYSIS_SECONDS = 600.0
    private const val MAX_RESAMPLED_SAMPLES = SAMPLE_RATE * 600L
    private const val CURVE_STEP_SECONDS = 0.25
    private const val MIN_TEMPO_SECONDS = 16.0
    private const val TEMPO_WINDOW_SECONDS = 16.0
    private const val MIN_BPM = 40.0
    private const val MAX_BPM = 220.0
    private const val MIN_TEMPO_CONFIDENCE = 0.15
    private const val MIN_GRID_CONFIDENCE = 0.30
    private const val MAX_LOCAL_TEMPO_DEVIATION = 0.14
    private const val FINAL_FADE_WINDOW_SECONDS = 45.0
    private const val FINAL_FADE_QUIET_RATIO = 0.60
    private const val LOW_BAND_MAX_HZ = 250.0
    private const val ENERGY_FLOOR_RATIO = 0.10
    private const val ACTIVE_ENERGY_RATIO = 0.35

    private data class TempoEstimate(
        val bpm: Double,
        val confidence: Double,
        val firstBeatSeconds: Double,
    )

    private data class SpectrumFeatures(
        val flux: FloatArray,
        val lowEnergyByCurve: DoubleArray,
    )

    fun analyze(
        samples: FloatArray,
        durationSeconds: Double,
        performanceMode: AutomixPerformanceMode,
        checkActive: () -> Unit = { throwIfInterrupted() },
    ): TrackFeatures.Features? {
        checkActive()
        if (samples.isEmpty() || !durationSeconds.isFinite() || durationSeconds <= 0.0 || durationSeconds > MAX_ANALYSIS_SECONDS) {
            return null
        }
        val sampleCount = min(samples.size.toLong(), (durationSeconds * SAMPLE_RATE).toLong()).toInt()
        if (sampleCount <= 0) return null
        val duration = min(durationSeconds, sampleCount.toDouble() / SAMPLE_RATE)
        val curveWindowSamples = (SAMPLE_RATE * CURVE_STEP_SECONDS).roundToInt()
        val curveCount = ceil(sampleCount.toDouble() / curveWindowSamples).toInt()
        val rawEnergy = DoubleArray(curveCount)
        var maximumEnergy = 0.0

        for (curveIndex in 0 until curveCount) {
            checkActive()
            val start = curveIndex * curveWindowSamples
            val end = min(sampleCount, start + curveWindowSamples)
            var squareSum = 0.0
            for (index in start until end) {
                if ((index - start) % 1024 == 0) checkActive()
                val sample = samples[index].takeIf(Float::isFinite)?.toDouble() ?: 0.0
                squareSum += sample * sample
            }
            val energy = sqrt(squareSum / max(1, end - start))
            rawEnergy[curveIndex] = energy
            maximumEnergy = max(maximumEnergy, energy)
        }
        if (maximumEnergy <= 1e-7) return null

        val energyValues = DoubleArray(curveCount) { (rawEnergy[it] / maximumEnergy).coerceIn(0.0, 1.0) }
        val energyCurve = List(curveCount) { index ->
            EnergySample(index * CURVE_STEP_SECONDS, energyValues[index])
        }
        val energyMean = robustMean(energyValues)
        val energyFloor = max(energyMean * ENERGY_FLOOR_RATIO, 0.015)
        val activeThreshold = max(energyMean * ACTIVE_ENERGY_RATIO, 0.04)
        val audibleStartIndex = energyValues.indexOfFirst { it >= energyFloor }.takeIf { it >= 0 } ?: return null
        val contentEndIndex = energyValues.indexOfLast { it >= energyFloor }.takeIf { it >= audibleStartIndex } ?: return null
        val contentEnd = min(duration, (contentEndIndex + 1) * CURVE_STEP_SECONDS)
        val audibleStart = audibleStartIndex * CURVE_STEP_SECONDS
        val introEndIndex = firstSustainedActiveIndex(energyValues, audibleStartIndex, contentEndIndex, activeThreshold)
        val outroStartIndex = energyValues.indexOfLast { it >= activeThreshold }
            .takeIf { it >= audibleStartIndex }
            ?: contentEndIndex
        val introEnd = introEndIndex * CURVE_STEP_SECONDS
        val outroStart = outroStartIndex * CURVE_STEP_SECONDS

        val spectrum = extractSpectrum(samples, sampleCount, curveCount, performanceMode, checkActive)
        val fluxRate = spectrum.fluxRate(performanceMode)
        val tempo = estimateTempo(spectrum.flux, fluxRate, duration, checkActive)
        val tempoConfidence = tempo?.confidence ?: 0.0
        val bpm = tempo?.bpm?.takeIf { tempoConfidence >= MIN_TEMPO_CONFIDENCE } ?: 0.0
        val beatInterval = if (bpm > 0.0) 60.0 / bpm else 0.0
        val firstBeat = if (bpm > 0.0 && tempoConfidence >= MIN_GRID_CONFIDENCE) tempo?.firstBeatSeconds ?: 0.0 else 0.0
        val downbeats =
            if (bpm > 0.0 && tempoConfidence >= MIN_GRID_CONFIDENCE) {
                beatGrid(firstBeat, beatInterval * 4.0, contentEnd)
            } else {
                emptyList()
            }
        val phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 }
        val mixInCandidates = findMixInCandidates(energyValues, audibleStartIndex, introEndIndex, activeThreshold)
        val mixOutCandidates = findMixOutCandidates(energyValues, audibleStartIndex, contentEndIndex)
        val finalFadeOnset = detectFinalFadeOnset(energyValues, audibleStartIndex, contentEndIndex, introEndIndex)
        val lowEnergyCurve = List(curveCount) { index ->
            EnergySample(index * CURVE_STEP_SECONDS, spectrum.lowEnergyByCurve[index].coerceIn(0.0, 1.0))
        }

        return TrackFeatures.Features(
            duration = duration,
            bpm = bpm,
            beatInterval = beatInterval,
            firstBeat = firstBeat,
            beatConfidence = tempoConfidence,
            key = "",
            keyConfidence = 0.0,
            audibleStartTime = audibleStart,
            pickupTime = audibleStart,
            introEndTime = introEnd,
            outroStartTime = outroStart,
            contentEndTime = contentEnd,
            mixInTime = mixInCandidates.firstOrNull()?.time ?: introEnd,
            mixOutTime = 0.0,
            vocalProbability = 0.0,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            vocalActivityMask = emptyList(),
            energyCurve = energyCurve,
            lowEnergyCurve = lowEnergyCurve,
            mixInCandidates = mixInCandidates,
            mixOutCandidates = mixOutCandidates,
            finalFadeOnsetTime = finalFadeOnset,
        )
    }

    fun resample(
        samples: FloatArray,
        inputRate: Double,
        outputRate: Double,
        checkActive: () -> Unit = { throwIfInterrupted() },
    ): FloatArray? {
        checkActive()
        if (samples.isEmpty() || !inputRate.isFinite() || !outputRate.isFinite() || inputRate <= 0.0 || outputRate <= 0.0) {
            return null
        }
        val inputDuration = samples.size / inputRate
        if (!inputDuration.isFinite() || inputDuration > MAX_ANALYSIS_SECONDS) return null
        if (abs(inputRate - outputRate) < 0.001) return samples
        val outputCount = samples.size.toDouble() * outputRate / inputRate
        if (
            !outputCount.isFinite() ||
            outputCount <= 0.0 ||
            outputCount > Int.MAX_VALUE ||
            outputCount > MAX_RESAMPLED_SAMPLES
        ) return null
        val outputCountLong = outputCount.toLong()
        val output = FloatArray(outputCountLong.toInt())
        val ratio = outputRate / inputRate
        val cutoff = min(1.0, ratio)
        val radius = 12
        for (outIndex in output.indices) {
            if (outIndex % 256 == 0) checkActive()
            val position = outIndex / ratio
            val center = floor(position).toInt()
            val first = max(0, center - radius + 1)
            val last = min(samples.lastIndex, center + radius)
            var weightedSum = 0.0
            var weightSum = 0.0
            for (inputIndex in first..last) {
                val distance = position - inputIndex
                val scaledDistance = distance * cutoff
                val sinc = if (abs(scaledDistance) < 1e-9) 1.0 else sin(PI * scaledDistance) / (PI * scaledDistance)
                val windowPosition = abs(distance) / radius
                if (windowPosition >= 1.0) continue
                val window = 0.5 + 0.5 * cos(PI * windowPosition)
                val weight = sinc * window * cutoff
                val sample = samples[inputIndex].takeIf(Float::isFinite)?.toDouble() ?: 0.0
                weightedSum += sample * weight
                weightSum += weight
            }
            output[outIndex] = if (abs(weightSum) > 1e-9) (weightedSum / weightSum).toFloat() else 0f
        }
        return output
    }

    private fun extractSpectrum(
        samples: FloatArray,
        sampleCount: Int,
        curveCount: Int,
        performanceMode: AutomixPerformanceMode,
        checkActive: () -> Unit,
    ): SpectrumFeatures {
        val fftSize = if (performanceMode == AutomixPerformanceMode.EFFICIENT) 512 else 1024
        val hopSize = when (performanceMode) {
            AutomixPerformanceMode.EFFICIENT, AutomixPerformanceMode.BALANCED -> fftSize / 2
            AutomixPerformanceMode.PERFORMANCE -> fftSize / 4
        }
        val frameCount = if (sampleCount < fftSize) 0 else (sampleCount - fftSize) / hopSize + 1
        val flux = FloatArray(frameCount)
        val lowEnergySums = DoubleArray(curveCount)
        val lowEnergyCounts = IntArray(curveCount)
        val window = DoubleArray(fftSize) { index ->
            0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))
        }
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        val previousMagnitude = DoubleArray(fftSize / 2)
        val lowBandEnd = (LOW_BAND_MAX_HZ * fftSize / SAMPLE_RATE).toInt().coerceIn(1, fftSize / 2 - 1)

        for (frame in 0 until frameCount) {
            checkActive()
            val start = frame * hopSize
            for (index in 0 until fftSize) {
                real[index] = (samples[start + index].takeIf(Float::isFinite)?.toDouble() ?: 0.0) * window[index]
                imaginary[index] = 0.0
            }
            fft(real, imaginary)
            var positiveFlux = 0.0
            var totalMagnitude = 0.0
            var totalPower = 0.0
            var lowPower = 0.0
            for (bin in 1 until fftSize / 2) {
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                positiveFlux += max(0.0, magnitude - previousMagnitude[bin])
                previousMagnitude[bin] = magnitude
                totalMagnitude += magnitude
                totalPower += magnitude * magnitude
                if (bin <= lowBandEnd) lowPower += magnitude * magnitude
            }
            flux[frame] = (positiveFlux / max(totalMagnitude, 1e-12)).toFloat()
            val centerSeconds = (start + fftSize / 2.0) / SAMPLE_RATE
            val curveIndex = floor(centerSeconds / CURVE_STEP_SECONDS).toInt().coerceIn(0, curveCount - 1)
            lowEnergySums[curveIndex] += lowPower / max(totalPower, 1e-12)
            lowEnergyCounts[curveIndex] += 1
        }

        val lowEnergy = DoubleArray(curveCount) { index ->
            if (lowEnergyCounts[index] > 0) lowEnergySums[index] / lowEnergyCounts[index] else 0.0
        }
        return SpectrumFeatures(flux, lowEnergy)
    }

    private fun SpectrumFeatures.fluxRate(performanceMode: AutomixPerformanceMode): Double {
        val fftSize = if (performanceMode == AutomixPerformanceMode.EFFICIENT) 512 else 1024
        val hopSize = when (performanceMode) {
            AutomixPerformanceMode.EFFICIENT, AutomixPerformanceMode.BALANCED -> fftSize / 2
            AutomixPerformanceMode.PERFORMANCE -> fftSize / 4
        }
        return SAMPLE_RATE.toDouble() / hopSize
    }

    private fun estimateTempo(
        flux: FloatArray,
        fluxRate: Double,
        duration: Double,
        checkActive: () -> Unit,
    ): TempoEstimate? {
        checkActive()
        val minimumFrames = (fluxRate * MIN_TEMPO_SECONDS).roundToInt()
        if (flux.size < minimumFrames) return null
        val global = estimateTempoWindow(flux, 0, flux.size, fluxRate, checkActive) ?: return null
        val windowFrames = (fluxRate * TEMPO_WINDOW_SECONDS).roundToInt()
        val stepFrames = max(1, windowFrames / 2)
        val localEstimates = ArrayList<TempoEstimate>()
        var start = 0
        while (start + windowFrames <= flux.size) {
            checkActive()
            estimateTempoWindow(flux, start, windowFrames, fluxRate, checkActive)
                ?.takeIf { it.confidence >= 0.18 }
                ?.let(localEstimates::add)
            start += stepFrames
        }
        val tailStart = flux.size - windowFrames
        if (tailStart >= 0 && (localEstimates.isEmpty() || tailStart > start - stepFrames)) {
            checkActive()
            estimateTempoWindow(flux, tailStart, windowFrames, fluxRate, checkActive)
                ?.takeIf { it.confidence >= 0.18 }
                ?.let(localEstimates::add)
        }

        val localBpms = localEstimates.map { normalizeTempo(it.bpm) }
        val localMedian = median(localBpms)
        val localDeviation =
            if (localBpms.size >= 2 && localMedian > 0.0) {
                median(localBpms.map { abs(it - localMedian) / localMedian })
            } else {
                0.0
            }
        val stability =
            if (localBpms.size >= 2) {
                (1.0 - localDeviation / MAX_LOCAL_TEMPO_DEVIATION).coerceIn(0.0, 1.0)
            } else {
                0.75
            }
        val durationConfidence = ((duration - 8.0) / 24.0).coerceIn(0.35, 1.0)
        val confidence = (global.confidence * stability * durationConfidence).coerceIn(0.0, 1.0)
        val bpm = if (localBpms.size >= 2 && stability >= 0.5) localMedian else global.bpm
        val firstBeat = estimatePhase(flux, fluxRate, bpm, checkActive)
        return TempoEstimate(bpm, confidence, firstBeat)
    }

    private fun estimateTempoWindow(
        flux: FloatArray,
        offset: Int,
        length: Int,
        fluxRate: Double,
        checkActive: () -> Unit,
    ): TempoEstimate? {
        if (length < (fluxRate * MIN_TEMPO_SECONDS).roundToInt()) return null
        val mean = (offset until offset + length).sumOf { flux[it].toDouble() } / length
        val centered = DoubleArray(length) { index -> flux[offset + index].toDouble() - mean }
        val minimumLag = floor(fluxRate * 60.0 / MAX_BPM).toInt().coerceAtLeast(2)
        val maximumLag = ceil(fluxRate * 60.0 / MIN_BPM).toInt().coerceAtMost(length / 4)
        if (maximumLag <= minimumLag) return null
        val power = centered.sumOf { it * it }
        if (!power.isFinite() || power <= 1e-12) return null

        val lags = IntArray(maximumLag - minimumLag + 1)
        val scores = DoubleArray(lags.size)
        var bestIndex = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minimumLag..maximumLag) {
            checkActive()
            var dot = 0.0
            var leftPower = 0.0
            var rightPower = 0.0
            for (index in 0 until length - lag) {
                if (index % 1024 == 0) checkActive()
                val left = centered[index]
                val right = centered[index + lag]
                dot += left * right
                leftPower += left * left
                rightPower += right * right
            }
            val score = if (leftPower > 1e-12 && rightPower > 1e-12) dot / sqrt(leftPower * rightPower) else 0.0
            val scoreIndex = lag - minimumLag
            lags[scoreIndex] = lag
            scores[scoreIndex] = score
            if (score > bestScore) {
                bestScore = score
                bestIndex = scoreIndex
            }
        }
        if (bestIndex < 0 || bestScore <= 0.0) return null

        val baseline = median(scores.toList())
        val prominence = ((bestScore - baseline) / max(0.15, 1.0 - baseline)).coerceIn(0.0, 1.0)
        val confidence = (bestScore.coerceIn(0.0, 1.0) * prominence).coerceIn(0.0, 1.0)
        var bpm = 60.0 * fluxRate / lags[bestIndex]
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        if (!bpm.isFinite() || bpm !in MIN_BPM..MAX_BPM) return null
        return TempoEstimate(bpm, confidence, 0.0)
    }

    private fun estimatePhase(
        flux: FloatArray,
        fluxRate: Double,
        bpm: Double,
        checkActive: () -> Unit,
    ): Double {
        if (flux.isEmpty() || bpm <= 0.0) return 0.0
        val barFrames = (240.0 * fluxRate / bpm).roundToInt().coerceIn(1, flux.size)
        var bestPhase = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until min(barFrames, flux.size)) {
            checkActive()
            var score = 0.0
            var count = 0
            var index = phase
            while (index < flux.size) {
                if (index % 1024 == 0) checkActive()
                score += flux[index]
                count += 1
                index += barFrames
            }
            val mean = score / max(1, count)
            if (mean > bestScore) {
                bestScore = mean
                bestPhase = phase
            }
        }
        return bestPhase / fluxRate
    }

    private fun firstSustainedActiveIndex(
        values: DoubleArray,
        first: Int,
        last: Int,
        threshold: Double,
    ): Int {
        val run = 4
        for (index in first..max(first, last - run + 1)) {
            if ((index until min(last + 1, index + run)).all { values[it] >= threshold }) return index
        }
        return first
    }

    private fun findMixInCandidates(
        values: DoubleArray,
        first: Int,
        introEnd: Int,
        activeThreshold: Double,
    ): List<MixCandidate> {
        val candidates = ArrayList<MixCandidate>()
        val stop = min(introEnd + 12, min(values.lastIndex, first + 120))
        for (index in max(first + 1, 1)..max(max(first + 1, 1), stop - 2)) {
            val before = average(values, max(first, index - 3), index - 1)
            val after = average(values, index, min(stop, index + 2))
            val rise = after - before
            if (after >= activeThreshold && rise >= 0.10) {
                candidates += MixCandidate(
                    time = index * CURVE_STEP_SECONDS,
                    score = (rise / max(after, 0.05)).coerceIn(0.0, 1.0),
                    type = if (after >= activeThreshold * 1.5) "main_drop" else "intro_drop",
                )
            }
        }
        return candidates
            .sortedByDescending { it.score }
            .fold(ArrayList<MixCandidate>()) { chosen, candidate ->
                if (chosen.none { abs(it.time - candidate.time) < 1.0 }) chosen.add(candidate)
                chosen
            }
            .take(4)
            .sortedBy { it.time }
    }

    private fun findMixOutCandidates(values: DoubleArray, first: Int, contentEnd: Int): List<MixCandidate> {
        val start = max(first + 1, contentEnd - (30.0 / CURVE_STEP_SECONDS).roundToInt())
        val candidates = ArrayList<MixCandidate>()
        for (index in start..max(start, contentEnd - 2)) {
            val before = average(values, max(first, index - 4), index - 1)
            val after = average(values, index, min(contentEnd, index + 2))
            val drop = before - after
            if (before >= 0.20 && drop >= 0.10) {
                candidates += MixCandidate(
                    time = index * CURVE_STEP_SECONDS,
                    score = (drop / max(before, 0.05)).coerceIn(0.0, 1.0),
                    type = "energy_cliff",
                )
            }
        }
        return candidates
            .sortedByDescending { it.score }
            .fold(ArrayList<MixCandidate>()) { chosen, candidate ->
                if (chosen.none { abs(it.time - candidate.time) < 1.0 }) chosen.add(candidate)
                chosen
            }
            .take(5)
            .sortedBy { it.time }
    }

    private fun detectFinalFadeOnset(
        values: DoubleArray,
        audibleStart: Int,
        contentEnd: Int,
        introEnd: Int,
    ): Double? {
        val windowSize = (FINAL_FADE_WINDOW_SECONDS / CURVE_STEP_SECONDS).roundToInt()
        val start = max(audibleStart, contentEnd - windowSize)
        if (contentEnd - start < 8) return null
        val segment = values.copyOfRange(start, contentEnd + 1)
        val smoothed = smooth(segment, 2)
        val sorted = smoothed.sorted()
        val loud = sorted[((sorted.size - 1) * 0.90).roundToInt().coerceIn(0, sorted.lastIndex)]
        val quiet = loud * FINAL_FADE_QUIET_RATIO
        if (quiet <= 0.02) return null
        val lastLoud = smoothed.indexOfLast { it >= quiet }
        if (lastLoud < 0 || lastLoud >= smoothed.lastIndex) return null
        val onsetIndex = start + lastLoud + 1
        if (onsetIndex <= introEnd || onsetIndex > contentEnd) return null
        val onsetTime = onsetIndex * CURVE_STEP_SECONDS
        val fadeDuration = contentEnd * CURVE_STEP_SECONDS - onsetTime
        if (fadeDuration !in 1.5..FINAL_FADE_WINDOW_SECONDS) return null
        if (smoothed.last() >= quiet || smoothed[lastLoud] < quiet) return null
        return onsetTime
    }

    private fun smooth(values: DoubleArray, radius: Int): DoubleArray = DoubleArray(values.size) { index ->
        val start = max(0, index - radius)
        val end = min(values.lastIndex, index + radius)
        average(values, start, end)
    }

    private fun beatGrid(firstBeat: Double, barSeconds: Double, contentEnd: Double): List<Double> {
        if (!firstBeat.isFinite() || !barSeconds.isFinite() || barSeconds <= 0.0) return emptyList()
        var time = firstBeat
        while (time < 0.0) time += barSeconds
        val values = ArrayList<Double>()
        while (time <= contentEnd && values.size < 512) {
            values += time
            time += barSeconds
        }
        return values
    }

    private fun average(values: DoubleArray, start: Int, end: Int): Double {
        if (values.isEmpty() || end < start) return 0.0
        val boundedStart = start.coerceIn(0, values.lastIndex)
        val boundedEnd = end.coerceIn(boundedStart, values.lastIndex)
        var sum = 0.0
        for (index in boundedStart..boundedEnd) sum += values[index]
        return sum / (boundedEnd - boundedStart + 1)
    }

    private fun robustMean(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val start = sorted.size / 4
        val end = max(start + 1, sorted.size * 3 / 4)
        return sorted.subList(start, end).average()
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.filter(Double::isFinite).sorted()
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun normalizeTempo(value: Double): Double {
        var bpm = value
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        return bpm
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
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

        var blockSize = 2
        while (blockSize <= size) {
            val angle = -2.0 * PI / blockSize
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            val half = blockSize / 2
            for (blockStart in 0 until size step blockSize) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until half) {
                    val even = blockStart + offset
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]
                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary
                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary
                    val nextTwiddleReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
            }
            blockSize *= 2
        }
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Compact automix analysis interrupted")
        }
    }
}
