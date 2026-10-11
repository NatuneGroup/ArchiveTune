package tf.monochrome.android.audio.resample

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class SincKernel(

    val halfWidth: Int,

    val phases: Int,

    val taps: Array<FloatArray>,
) {
    companion object {
        const val DEFAULT_BETA = 9.0

        const val DEFAULT_CUTOFF = 0.46

        fun design(
            ratio: Double,
            halfWidth: Int = 32,
            phases: Int = 256,
            beta: Double = DEFAULT_BETA,
            cutoffFraction: Double = DEFAULT_CUTOFF,
        ): SincKernel {
            require(halfWidth > 0 && phases > 0)
            val fc = cutoffFraction * min(1.0, 1.0 / ratio.coerceAtLeast(1e-6))
            val width = 2 * halfWidth
            val denom = besselI0(beta)
            val rows = Array(phases + 1) { p ->
                val frac = p.toDouble() / phases
                val row = DoubleArray(width)
                var sum = 0.0
                for (k in 0 until width) {
                    val u = (k - halfWidth + 1) - frac
                    val windowArg = u / halfWidth
                    val w = if (abs(windowArg) >= 1.0) {
                        0.0
                    } else {
                        besselI0(beta * sqrt(1.0 - windowArg * windowArg)) / denom
                    }
                    val v = 2.0 * fc * sinc(2.0 * fc * u) * w
                    row[k] = v
                    sum += v
                }

                val norm = if (abs(sum) > 1e-12) 1.0 / sum else 1.0
                FloatArray(width) { (row[it] * norm).toFloat() }
            }
            return SincKernel(halfWidth, phases, rows)
        }

        private fun sinc(x: Double): Double {
            if (abs(x) < 1e-9) return 1.0
            val px = PI * x
            return sin(px) / px
        }

        internal fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 64) {
                val r = x / (2.0 * k)
                term *= r * r
                sum += term
                if (term < 1e-17 * sum) break
                k++
            }
            return sum
        }
    }
}
