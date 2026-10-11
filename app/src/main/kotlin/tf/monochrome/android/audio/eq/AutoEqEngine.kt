package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max

enum class AutoEqAlgorithm(val label: String) {
    PEAKING("Peaking"),
    SHELF_ENDS("Shelf ends"),
}

object AutoEqEngine {
    fun smoothCurve(
        points: List<FrequencyPoint>,
        percent: Float,
    ): List<FrequencyPoint> {
        if (percent <= 0f || points.size < 3) return points
        val radius = kotlin.math.max(1, (percent / 2.5f).toInt())
        val out = ArrayList<FrequencyPoint>(points.size)
        for (i in points.indices) {
            var sum = 0f
            var wSum = 0f
            for (c in -radius..radius) {
                val j = i + c
                if (j in points.indices) {
                    val w = 1f - kotlin.math.abs(c).toFloat() / (radius + 1)
                    sum += points[j].gain * w
                    wSum += w
                }
            }
            out.add(FrequencyPoint(points[i].freq, sum / wSum))
        }
        return out
    }

    private const val MAX_BOOST = 12.0
    private const val MAX_CUT = 12.0
    private const val MIN_Q = 0.6
    private const val MAX_Q = 5.0
    private const val DEFAULT_SAMPLE_RATE = 48000f

    fun calculateBiquadResponse(
        freqHz: Float,
        band: EqBand,
        sampleRate: Float = DEFAULT_SAMPLE_RATE
    ): Float {
        if (!band.enabled) return 0f
        val c = coeffsFor(band, sampleRate)
        val phi = 2.0 * PI * freqHz.toDouble() / sampleRate.toDouble()
        return magnitudeDb(c, cos(phi), cos(2.0 * phi)).toFloat()
    }

    class ResponseGrid(freqs: FloatArray, private val sampleRate: Float = DEFAULT_SAMPLE_RATE) {
        private val cosPhi = DoubleArray(freqs.size)
        private val cos2Phi = DoubleArray(freqs.size)

        val size: Int get() = cosPhi.size

        init {
            for (i in freqs.indices) {
                val phi = 2.0 * PI * freqs[i].toDouble() / sampleRate.toDouble()
                cosPhi[i] = cos(phi)
                cos2Phi[i] = cos(2.0 * phi)
            }
        }

        fun accumulate(band: EqBand, out: FloatArray) {
            if (!band.enabled) return
            val c = AutoEqEngine.coeffsFor(band, sampleRate)
            for (i in out.indices) {
                out[i] += AutoEqEngine.magnitudeDb(c, cosPhi[i], cos2Phi[i]).toFloat()
            }
        }

        fun response(band: EqBand): FloatArray = FloatArray(size).also { accumulate(band, it) }

        fun sum(bands: List<EqBand>): FloatArray =
            FloatArray(size).also { out -> bands.forEach { accumulate(it, out) } }
    }

    private class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    )

    private fun coeffsFor(band: EqBand, sampleRate: Float): BiquadCoeffs {
        val w0 = 2.0 * PI * band.freq.toDouble() / sampleRate.toDouble()
        val alpha = sin(w0) / (2.0 * band.q.toDouble())
        val A = 10.0.pow(band.gain.toDouble() / 40.0)
        val cosW0 = cos(w0)

        val m = when (band.type) {
            FilterType.PEAKING -> matchedPeakingCoefficients(
                sampleRate.toDouble(), band.freq.toDouble(),
                band.q.toDouble(), band.gain.toDouble())
            FilterType.LOWSHELF -> matchedShelfCoefficients(
                sampleRate.toDouble(), band.freq.toDouble(),
                band.q.toDouble(), band.gain.toDouble(), high = false)
            FilterType.HIGHSHELF -> matchedShelfCoefficients(
                sampleRate.toDouble(), band.freq.toDouble(),
                band.q.toDouble(), band.gain.toDouble(), high = true)
        }
        if (m != null) return BiquadCoeffs(m[0], m[1], m[2], m[3], m[4])
        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (band.type) {
            FilterType.LOWSHELF -> {
                val sq = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + sq)
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - sq)
                a0 = (A + 1.0) + (A - 1.0) * cosW0 + sq
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                a2 = (A + 1.0) + (A - 1.0) * cosW0 - sq
            }
            FilterType.HIGHSHELF -> {
                val sq = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + sq)
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - sq)
                a0 = (A + 1.0) - (A - 1.0) * cosW0 + sq
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                a2 = (A + 1.0) - (A - 1.0) * cosW0 - sq
            }
            else -> {
                b0 = 1.0 + alpha * A
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * A
                a0 = 1.0 + alpha / A
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / A
            }
        }
        val inv = 1.0 / a0
        return BiquadCoeffs(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv)
    }

    private fun magnitudeDb(c: BiquadCoeffs, cp: Double, c2p: Double): Double {
        val num = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 +
            2.0 * (c.b0 * c.b1 + c.b1 * c.b2) * cp + 2.0 * c.b0 * c.b2 * c2p
        val den = 1.0 + c.a1 * c.a1 + c.a2 * c.a2 +
            2.0 * (c.a1 + c.a1 * c.a2) * cp + 2.0 * c.a2 * c2p
        return 10.0 * log10(num / den)
    }

    private fun interpolate(freq: Float, data: List<FrequencyPoint>): Float {
        if (data.isEmpty()) return 0f
        if (freq <= data.first().freq) return data.first().gain
        if (freq >= data.last().freq) return data.last().gain
        for (i in 0 until data.size - 1) {
            if (freq >= data[i].freq && freq <= data[i + 1].freq) {
                val t = (freq - data[i].freq) / (data[i + 1].freq - data[i].freq)
                return data[i].gain + t * (data[i + 1].gain - data[i].gain)
            }
        }
        return 0f
    }

    private fun getNormalizationOffset(data: List<FrequencyPoint>): Float {
        var sum = 0f; var count = 0
        for (p in data) if (p.freq in 250f..2500f) { sum += p.gain; count++ }
        return if (count > 0) sum / count else interpolate(1000f, data)
    }

    fun runAutoEqAlgorithm(
        measurement: List<FrequencyPoint>,
        target: List<FrequencyPoint>,
        bandCount: Int,
        maxFrequency: Float = 16000f,
        minFrequency: Float = 20f,
        @Suppress("UNUSED_PARAMETER") maxQ: Float = MAX_Q.toFloat(),
        sampleRate: Float = DEFAULT_SAMPLE_RATE,
        algorithm: AutoEqAlgorithm = AutoEqAlgorithm.PEAKING,
    ): List<EqBand> {
        val offset = getNormalizationOffset(target) - getNormalizationOffset(measurement)

        val error = measurement.map { p ->
            FrequencyPoint(p.freq, (p.gain + offset) - interpolate(p.freq, target))
        }.toMutableList()

        val bands = mutableListOf<EqBand>()

        if (algorithm == AutoEqAlgorithm.SHELF_ENDS) {
            fitEndShelf(
                error, FilterType.LOWSHELF,
                corner = 105f, regionLo = minFrequency, regionHi = 105f,
                maxBoost = MAX_BOOST, sampleRate = sampleRate, id = bands.size,
            )?.let(bands::add)

            val hiCorner = kotlin.math.min(10_000f, maxFrequency * 0.75f)
            fitEndShelf(
                error, FilterType.HIGHSHELF,
                corner = hiCorner, regionLo = hiCorner, regionHi = maxFrequency,
                maxBoost = 4.0, sampleRate = sampleRate, id = bands.size,
            )?.let(bands::add)
        }

        while (bands.size < bandCount) {
            var maxDev = 0.0
            var maxWeightedDev = 0.0
            var peakFreq = 1000.0
            var peakIdx = 0

            for (j in error.indices) {
                val freq = error[j].freq.toDouble()
                if (freq < minFrequency || freq > maxFrequency) continue

                var v = error[j].gain.toDouble()
                if (j > 0 && j < error.size - 1) {
                    v = (error[j - 1].gain + v + error[j + 1].gain) / 3.0
                }

                val priority = priorityWeight(freq)

                val weightedAbs = abs(v * priority)
                if (weightedAbs > abs(maxWeightedDev)) {
                    maxWeightedDev = weightedAbs
                    maxDev = v
                    peakFreq = freq
                    peakIdx = j
                }
            }

            var gain = -maxDev

            var safeBoost = MAX_BOOST
            if (peakFreq > 3000.0) safeBoost = 6.0
            if (peakFreq > 6000.0) safeBoost = 3.0

            if (gain > safeBoost) gain = safeBoost
            if (gain < -MAX_CUT) gain = -MAX_CUT

            if (abs(gain) < 0.2) break

            val targetEnergy = maxDev / 2.0
            var lowerFreq = peakFreq
            var upperFreq = peakFreq

            for (k in peakIdx downTo 0) {
                if (abs(error[k].gain) < abs(targetEnergy)) {
                    lowerFreq = error[k].freq.toDouble()
                    break
                }
            }
            for (k in peakIdx until error.size) {
                if (abs(error[k].gain) < abs(targetEnergy)) {
                    upperFreq = error[k].freq.toDouble()
                    break
                }
            }

            var bandwidth = log2(upperFreq / max(1.0, lowerFreq))
            if (bandwidth < 0.1) bandwidth = 0.1

            var q = sqrt(2.0.pow(bandwidth)) / (2.0.pow(bandwidth) - 1.0)

            if (q < MIN_Q) q = MIN_Q
            if (q > MAX_Q) q = MAX_Q
            if (peakFreq > 5000.0 && q > 3.0) q = 3.0
            if (gain > 0.0 && q > 2.0) q = 2.0

            val newBand = EqBand(
                id = bands.size,
                type = FilterType.PEAKING,
                freq = peakFreq.toFloat(),
                gain = gain.toFloat(),
                q = q.toFloat(),
                enabled = true
            )
            bands.add(newBand)

            for (j in error.indices) {
                val response = calculateBiquadResponse(error[j].freq, newBand, sampleRate)
                error[j] = FrequencyPoint(error[j].freq, error[j].gain + response)
            }
        }

        refineBands(bands, error, minFrequency, maxFrequency, sampleRate)

        return bands.sortedBy { it.freq }.mapIndexed { idx, b -> b.copy(id = idx) }
    }

    private fun priorityWeight(freq: Double): Double = when {
        freq < 300.0  -> 1.5
        freq < 4000.0 -> 1.0
        freq < 8000.0 -> 0.5
        else          -> 0.25
    }

    private fun clampGain(g: Double, type: FilterType, freq: Double): Double {
        val boostCap = when {
            type == FilterType.LOWSHELF -> MAX_BOOST
            type == FilterType.HIGHSHELF -> 4.0
            freq > 6000.0 -> 3.0
            freq > 3000.0 -> 6.0
            else -> MAX_BOOST
        }
        return g.coerceIn(-MAX_CUT, boostCap)
    }

    private fun refineBands(
        bands: MutableList<EqBand>,
        residual: MutableList<FrequencyPoint>,
        minFrequency: Float,
        maxFrequency: Float,
        sampleRate: Float,
        sweeps: Int = 4,
    ) {
        if (bands.isEmpty()) return
        val n = residual.size

        val cp = DoubleArray(n)
        val c2p = DoubleArray(n)
        val weights = DoubleArray(n)
        for (j in 0 until n) {
            val phi = 2.0 * PI * residual[j].freq.toDouble() / sampleRate.toDouble()
            cp[j] = cos(phi)
            c2p[j] = cos(2.0 * phi)
            val f = residual[j].freq.toDouble()
            weights[j] = if (f < minFrequency || f > maxFrequency) 0.0 else priorityWeight(f)
        }
        val res = DoubleArray(n) { residual[it].gain.toDouble() }

        var prevTotal = Double.MAX_VALUE
        for (sweep in 0 until sweeps) {
            for (k in bands.indices) {
                val band = bands[k]

                val ck = coeffsFor(band, sampleRate)
                val eWo = DoubleArray(n)
                for (j in 0 until n) eWo[j] = res[j] - magnitudeDb(ck, cp[j], c2p[j])

                val freqCands: List<Float>
                val qCands: List<Float>
                if (band.type == FilterType.PEAKING) {
                    freqCands = listOf(0.71f, 0.84f, 1f, 1.19f, 1.41f)
                        .map { (band.freq * it).coerceIn(minFrequency, maxFrequency) }
                        .distinct()
                    qCands = listOf(0.5f, 0.7f, 1f, 1.4f, 2f)
                        .map { (band.q * it).coerceIn(MIN_Q.toFloat(), MAX_Q.toFloat()) }
                        .distinct()
                } else {
                    freqCands = listOf(band.freq)
                    qCands = listOf(band.q)
                }

                var best = band
                var bestScore = Double.MAX_VALUE
                for (fc in freqCands) for (qc0 in qCands) {
                    var qc = qc0
                    if (band.type == FilterType.PEAKING && fc > 5000f && qc > 3f) qc = 3f

                    val cProbe = coeffsFor(band.copy(freq = fc, q = qc, gain = 1f), sampleRate)
                    var num = 0.0
                    var den = 1e-9
                    for (j in 0 until n) {
                        val sj = magnitudeDb(cProbe, cp[j], c2p[j])
                        num += weights[j] * eWo[j] * sj
                        den += weights[j] * sj * sj
                    }
                    var g = clampGain(-num / den, band.type, fc.toDouble())
                    if (band.type == FilterType.PEAKING && g > 0.0 && qc > 2f) qc = 2f
                    val cand = band.copy(freq = fc, q = qc, gain = g.toFloat())

                    val cCand = coeffsFor(cand, sampleRate)
                    var score = 0.0
                    for (j in 0 until n) {
                        val r = eWo[j] + magnitudeDb(cCand, cp[j], c2p[j])
                        score += weights[j] * r * r
                    }
                    if (score < bestScore) {
                        bestScore = score
                        best = cand
                    }
                }

                bands[k] = best
                val cBest = coeffsFor(best, sampleRate)
                for (j in 0 until n) res[j] = eWo[j] + magnitudeDb(cBest, cp[j], c2p[j])
            }

            var total = 0.0
            for (j in 0 until n) total += weights[j] * res[j] * res[j]
            if (prevTotal - total < prevTotal * 0.005) break
            prevTotal = total
        }

        for (j in 0 until n) residual[j] = FrequencyPoint(residual[j].freq, res[j].toFloat())
        bands.removeAll { abs(it.gain) < 0.2f }
    }

    private fun fitEndShelf(
        error: MutableList<FrequencyPoint>,
        type: FilterType,
        corner: Float,
        regionLo: Float,
        regionHi: Float,
        maxBoost: Double,
        sampleRate: Float,
        id: Int,
    ): EqBand? {
        var sum = 0.0
        var count = 0
        for (p in error) {
            if (p.freq in regionLo..regionHi) {
                sum += p.gain
                count++
            }
        }
        if (count == 0) return null
        var gain = -(sum / count)
        if (gain > maxBoost) gain = maxBoost
        if (gain < -MAX_CUT) gain = -MAX_CUT
        if (abs(gain) < 1.0) return null

        val band = EqBand(
            id = id,
            type = type,
            freq = corner,
            gain = gain.toFloat(),
            q = 0.707f,
            enabled = true,
        )
        for (j in error.indices) {
            val response = calculateBiquadResponse(error[j].freq, band, sampleRate)
            error[j] = FrequencyPoint(error[j].freq, error[j].gain + response)
        }
        return band
    }
}
