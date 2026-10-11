package tf.monochrome.android.audio.eq

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class EqBiquadType { PEAKING, LOW_SHELF, HIGH_SHELF }

internal fun matchedPeakingCoefficients(
    sr: Double,
    freq: Double,
    q: Double,
    gainDb: Double,
): DoubleArray? {
    if (sr <= 0.0 || !freq.isFinite() || !q.isFinite() || !gainDb.isFinite()) return null
    val f = freq.coerceIn(1.0, sr * 0.499)
    val qq = q.coerceAtLeast(0.05)
    val g = 10.0.pow(gainDb / 20.0)
    val w0 = 2.0 * Math.PI * f / sr

    val qp = 1.0 / (2.0 * sqrt(g) * qq)
    val a2 = exp(-2.0 * qp * w0)
    val a1 = if (qp <= 1.0) {
        -2.0 * exp(-qp * w0) * cos(sqrt(1.0 - qp * qp) * w0)
    } else {
        -2.0 * exp(-qp * w0) * cosh(sqrt(qp * qp - 1.0) * w0)
    }

    val p1 = sin(w0 / 2.0).let { it * it }
    val p0 = 1.0 - p1
    val p2 = 4.0 * p0 * p1
    val bigA0 = (1.0 + a1 + a2).let { it * it }
    val bigA1 = (1.0 - a1 + a2).let { it * it }
    val bigA2 = -4.0 * a2
    val bigB0 = bigA0
    val r1 = (bigA0 * p0 + bigA1 * p1 + bigA2 * p2) * g * g
    val r2 = (-bigA0 + bigA1 + 4.0 * (p0 - p1) * bigA2) * g * g
    val bigB2 = (r1 - r2 * p1 - bigB0) / (4.0 * p1 * p1)
    val bigB1 = r2 + bigB0 + 4.0 * (p1 - p0) * bigB2
    if (bigB0 < 0.0 || bigB1 < 0.0) return null
    val w = 0.5 * (sqrt(bigB0) + sqrt(bigB1))
    val disc = w * w + bigB2
    if (disc < 0.0) return null
    val b0 = 0.5 * (w + sqrt(disc))
    if (b0 == 0.0) return null
    val b1 = 0.5 * (sqrt(bigB0) - sqrt(bigB1))
    val b2 = -bigB2 / (4.0 * b0)

    val out = doubleArrayOf(b0, b1, b2, a1, a2)
    return if (out.all { it.isFinite() }) out else null
}

internal fun matchedShelfCoefficients(
    sr: Double,
    freq: Double,
    q: Double,
    gainDb: Double,
    high: Boolean,
): DoubleArray? {
    if (sr <= 0.0 || !freq.isFinite() || !q.isFinite() || !gainDb.isFinite()) return null
    val f0 = freq.coerceIn(1.0, sr * 0.499)
    val qq = q.coerceAtLeast(0.05)
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * Math.PI * f0 / sr

    fun analogDb(f: Double): Double {
        val x = f / f0
        val im = sqrt(a) / qq * x
        val num: Double
        val den: Double
        if (high) {
            num = hypot(1.0 - a * x * x, im); den = hypot(a - x * x, im)
        } else {
            num = hypot(a - x * x, im); den = hypot(1.0 - a * x * x, im)
        }
        return 20.0 * log10(a * num / den)
    }

    fun rbjCoeffs(): DoubleArray {
        val cw = cos(w0); val sw = sin(w0)
        val al = sw / (2.0 * qq); val sq = 2.0 * sqrt(a) * al
        val b: DoubleArray; val d: DoubleArray
        if (high) {
            b = doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + sq),
                -2.0 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - sq))
            d = doubleArrayOf((a + 1) - (a - 1) * cw + sq,
                2.0 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sq)
        } else {
            b = doubleArrayOf(
                a * ((a + 1) - (a - 1) * cw + sq),
                2.0 * a * ((a - 1) - (a + 1) * cw),
                a * ((a + 1) - (a - 1) * cw - sq))
            d = doubleArrayOf((a + 1) + (a - 1) * cw + sq,
                -2.0 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sq)
        }
        return doubleArrayOf(b[0] / d[0], b[1] / d[0], b[2] / d[0], d[1] / d[0], d[2] / d[0])
    }

    fun numeratorFor(a1: Double, a2: Double): DoubleArray? {
        val bigA0 = (1.0 + a1 + a2).let { it * it }
        val bigA1 = (1.0 - a1 + a2).let { it * it }
        val bigA2 = -4.0 * a2
        val gdc = 10.0.pow(analogDb(1e-3) / 20.0)
        val gny = 10.0.pow(analogDb(sr / 2.0) / 20.0)
        val bigB0 = bigA0 * gdc * gdc
        val bigB1 = bigA1 * gny * gny
        val p1 = sin(w0 / 2.0).let { it * it }
        val p0 = 1.0 - p1
        val p2 = 4.0 * p0 * p1
        if (p2 == 0.0 || bigB0 < 0.0 || bigB1 < 0.0) return null
        val bigB2 = ((bigA0 * p0 + bigA1 * p1 + bigA2 * p2) * a * a - bigB0 * p0 - bigB1 * p1) / p2
        val w = 0.5 * (sqrt(bigB0) + sqrt(bigB1))
        val disc = w * w + bigB2
        if (disc < 0.0) return null
        val b0 = 0.5 * (w + sqrt(disc))
        if (b0 == 0.0) return null
        return doubleArrayOf(b0, 0.5 * (sqrt(bigB0) - sqrt(bigB1)), -bigB2 / (4.0 * b0), a1, a2)
    }

    fun iiCoeffs(): DoubleArray? {
        val wd = if (high) w0 * sqrt(a) else w0 / sqrt(a)
        if (wd >= Math.PI) return null
        val z = 1.0 / (2.0 * qq)
        val a2 = exp(-2.0 * z * wd)
        val a1 = if (z <= 1.0) -2.0 * exp(-z * wd) * cos(sqrt(1.0 - z * z) * wd)
                 else -2.0 * exp(-z * wd) * cosh(sqrt(z * z - 1.0) * wd)
        return numeratorFor(a1, a2)
    }

    fun digitalDb(c: DoubleArray, f: Double): Double {
        val w = 2.0 * Math.PI * f / sr
        val cw = cos(w); val c2w = cos(2.0 * w)
        val sw = sin(w); val s2w = sin(2.0 * w)
        val nr = c[0] + c[1] * cw + c[2] * c2w
        val ni = c[1] * sw + c[2] * s2w
        val dr = 1.0 + c[3] * cw + c[4] * c2w
        val di = c[3] * sw + c[4] * s2w
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    fun stable(c: DoubleArray): Boolean =
        c.all { it.isFinite() } && abs(c[4]) < 1.0 && abs(c[3]) < 1.0 + c[4]

    val rbj = rbjCoeffs()
    val candidates = listOfNotNull(rbj, iiCoeffs(), numeratorFor(rbj[3], rbj[4]))
    var best: DoubleArray? = null
    var bestErr = Double.MAX_VALUE
    val fLo = 40.0
    val fHi = sr * 0.475
    for (c in candidates) {
        if (!stable(c)) continue
        var worst = 0.0
        for (i in 0..11) {
            val p = fLo * (fHi / fLo).pow(i / 11.0)
            val e = abs(digitalDb(c, p) - analogDb(p))
            if (e > worst) worst = e
        }
        if (worst < bestErr) { bestErr = worst; best = c }
    }
    return best
}

internal fun designBiquadCoefficients(
    type: EqBiquadType,
    sr: Double,
    freq: Double,
    q: Double,
    gainDb: Double,
    matched: Boolean = false,
): FloatArray {
    if (matched) {
        val c = when (type) {
            EqBiquadType.PEAKING -> matchedPeakingCoefficients(sr, freq, q, gainDb)
            EqBiquadType.LOW_SHELF -> matchedShelfCoefficients(sr, freq, q, gainDb, high = false)
            EqBiquadType.HIGH_SHELF -> matchedShelfCoefficients(sr, freq, q, gainDb, high = true)
        }
        if (c != null) {
            return floatArrayOf(
                c[0].toFloat(), c[1].toFloat(), c[2].toFloat(),
                c[3].toFloat(), c[4].toFloat(),
            )
        }

    }
    val w0 = 2.0 * Math.PI * freq / sr
    val cosw0 = cos(w0)
    val sinw0 = sin(w0)
    val alpha = sinw0 / (2.0 * q)
    val nb0: Double; val nb1: Double; val nb2: Double
    val na0: Double; val na1: Double; val na2: Double

    when (type) {
        EqBiquadType.PEAKING -> {
            val a = 10.0.pow(gainDb / 40.0)
            nb0 = 1.0 + alpha * a; nb1 = -2.0 * cosw0; nb2 = 1.0 - alpha * a
            na0 = 1.0 + alpha / a; na1 = -2.0 * cosw0; na2 = 1.0 - alpha / a
        }
        EqBiquadType.LOW_SHELF -> {
            val a = 10.0.pow(gainDb / 40.0)
            val sq = 2.0 * sqrt(a) * alpha
            nb0 = a * ((a + 1) - (a - 1) * cosw0 + sq)
            nb1 = 2.0 * a * ((a - 1) - (a + 1) * cosw0)
            nb2 = a * ((a + 1) - (a - 1) * cosw0 - sq)
            na0 = (a + 1) + (a - 1) * cosw0 + sq
            na1 = -2.0 * ((a - 1) + (a + 1) * cosw0)
            na2 = (a + 1) + (a - 1) * cosw0 - sq
        }
        EqBiquadType.HIGH_SHELF -> {
            val a = 10.0.pow(gainDb / 40.0)
            val sq = 2.0 * sqrt(a) * alpha
            nb0 = a * ((a + 1) + (a - 1) * cosw0 + sq)
            nb1 = -2.0 * a * ((a - 1) + (a + 1) * cosw0)
            nb2 = a * ((a + 1) + (a - 1) * cosw0 - sq)
            na0 = (a + 1) - (a - 1) * cosw0 + sq
            na1 = 2.0 * ((a - 1) - (a + 1) * cosw0)
            na2 = (a + 1) - (a - 1) * cosw0 - sq
        }
    }
    if (abs(na0) < 1e-20 || !na0.isFinite()) {
        return floatArrayOf(1f, 0f, 0f, 0f, 0f)
    }
    val nb0f = (nb0 / na0).toFloat()
    val nb1f = (nb1 / na0).toFloat()
    val nb2f = (nb2 / na0).toFloat()
    val na1f = (na1 / na0).toFloat()
    val na2f = (na2 / na0).toFloat()
    return if (!nb0f.isFinite() || !nb1f.isFinite() || !nb2f.isFinite() ||
        !na1f.isFinite() || !na2f.isFinite()) {
        floatArrayOf(1f, 0f, 0f, 0f, 0f)
    } else {
        floatArrayOf(nb0f, nb1f, nb2f, na1f, na2f)
    }
}

internal class EqBiquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun configure(
        type: EqBiquadType,
        sr: Double,
        freq: Double,
        q: Double,
        gainDb: Double,
        matched: Boolean = false,
    ) {
        retune(designBiquadCoefficients(type, sr, freq, q, gainDb, matched))
        z1 = 0f; z2 = 0f
    }

    fun retune(c: FloatArray) {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4]
    }

    fun processBlock(data: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = data[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            data[i] = y
        }
    }
}
