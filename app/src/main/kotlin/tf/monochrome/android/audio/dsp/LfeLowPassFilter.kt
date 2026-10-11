package tf.monochrome.android.audio.dsp

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class LfeLowPassFilter {
    private val b0 = FloatArray(2)
    private val b1 = FloatArray(2)
    private val b2 = FloatArray(2)
    private val a1 = FloatArray(2)
    private val a2 = FloatArray(2)
    private val z1 = FloatArray(2)
    private val z2 = FloatArray(2)

    private var dryL = FloatArray(0)
    private var dryR = FloatArray(0)
    private var idxL = 0
    private var idxR = 0

    var delaySamples: Int = 0
        private set

    fun configure(sampleRate: Int) {
        val w0 = 2.0 * Math.PI * CUTOFF_HZ / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        for (s in 0 until 2) {
            val alpha = sinW / (2.0 * STAGE_Q[s])
            val a0 = 1.0 + alpha
            b0[s] = ((1.0 - cosW) / 2.0 / a0).toFloat()
            b1[s] = ((1.0 - cosW) / a0).toFloat()
            b2[s] = b0[s]
            a1[s] = (-2.0 * cosW / a0).toFloat()
            a2[s] = ((1.0 - alpha) / a0).toFloat()
        }
        delaySamples = (sampleRate * DC_GROUP_DELAY_S).roundToInt().coerceAtLeast(1)
        if (dryL.size != delaySamples) {
            dryL = FloatArray(delaySamples)
            dryR = FloatArray(delaySamples)
        }
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(z1, 0f)
        java.util.Arrays.fill(z2, 0f)
        java.util.Arrays.fill(dryL, 0f)
        java.util.Arrays.fill(dryR, 0f)
        idxL = 0
        idxR = 0
    }

    fun filterLfe(x: Float): Float {
        var v = x
        for (s in 0 until 2) {
            val y = b0[s] * v + z1[s]
            z1[s] = b1[s] * v - a1[s] * y + z2[s]
            z2[s] = b2[s] * v - a2[s] * y
            v = y
        }
        return v
    }

    fun delayDryL(x: Float): Float {
        val out = dryL[idxL]
        dryL[idxL] = x
        if (++idxL == dryL.size) idxL = 0
        return out
    }

    fun delayDryR(x: Float): Float {
        val out = dryR[idxR]
        dryR[idxR] = x
        if (++idxR == dryR.size) idxR = 0
        return out
    }

    companion object {
        const val CUTOFF_HZ = 125.0

        private val STAGE_Q = doubleArrayOf(0.54119610, 1.30656296)

        private const val DC_GROUP_DELAY_S = 2.6131 / (2.0 * Math.PI * CUTOFF_HZ)
    }
}
