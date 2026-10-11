

package tf.monochrome.android.audio.dsp.crossfeed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

enum class CrossfeedAlgorithm(
    val label: String,
    val blurb: String,
    val feedDb: Float,
    val cutoffHz: Double,
    val delayUs: Double,
) {
    SPEAKER(
        "Speaker angle",
        "Physical model. Set the virtual speaker angle with the slider below.",
        feedDb = 0f, cutoffHz = 700.0, delayUs = 0.0,
    ),
    BS2B(
        "BS2B",
        "Bauer stereophonic-to-binaural. 700 Hz cut, -4.5 dB feed, 260 µs delay.",
        feedDb = -4.5f, cutoffHz = 700.0, delayUs = 260.0,
    ),
    CMOY(
        "Chu Moy",
        "Chu Moy crossfeeder. 700 Hz cut, -6 dB feed, 260 µs delay.",
        feedDb = -6.0f, cutoffHz = 700.0, delayUs = 260.0,
    ),
    JMEIER(
        "Jan Meier",
        "Jan Meier CORDA network. 650 Hz cut, -9.5 dB feed, 280 µs delay.",
        feedDb = -9.5f, cutoffHz = 650.0, delayUs = 280.0,
    );
}

data class CrossfeedState(
    val enabled: Boolean = false,
    val algorithm: CrossfeedAlgorithm = CrossfeedAlgorithm.SPEAKER,

    val speakerAngleDeg: Float = DEFAULT_ANGLE_DEG,
) {
    companion object {
        const val MIN_ANGLE_DEG = 30f
        const val MAX_ANGLE_DEG = 180f
        const val DEFAULT_ANGLE_DEG = 60f
    }
}

@Singleton
class CrossfeedEffect @Inject constructor() {
    private val _state = MutableStateFlow(CrossfeedState())
    val state: StateFlow<CrossfeedState> = _state.asStateFlow()

    @Volatile private var targetCrossGain = 0f
    @Volatile private var targetDelaySamples = 0f
    @Volatile private var lowpassCoeff = 0f
    @Volatile private var smoothCoeff = 0f

    private var sampleRate = 48000.0

    private var crossGain = 0f
    private var delaySamples = 0f

    private val ringL = FloatArray(RING_SIZE)
    private val ringR = FloatArray(RING_SIZE)
    private var ringPos = 0

    private var lpL = 0f
    private var lpR = 0f

    init { recompute() }

    fun prepare(sampleRate: Double) {
        this.sampleRate = sampleRate
        recompute()

        java.util.Arrays.fill(ringL, 0f)
        java.util.Arrays.fill(ringR, 0f)
        lpL = 0f; lpR = 0f
        crossGain = targetCrossGain
        delaySamples = targetDelaySamples
    }

    fun update(transform: (CrossfeedState) -> CrossfeedState) {
        _state.value = transform(_state.value)
        recompute()
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun setAlgorithm(algorithm: CrossfeedAlgorithm) = update { it.copy(algorithm = algorithm) }

    fun setSpeakerAngleDeg(deg: Float) = update {
        it.copy(speakerAngleDeg = deg.coerceIn(CrossfeedState.MIN_ANGLE_DEG, CrossfeedState.MAX_ANGLE_DEG))
    }

    fun processArrays(l: FloatArray, r: FloatArray, frames: Int) {
        val gT = targetCrossGain

        if (gT <= 0f && crossGain < 1e-4f) {
            if (crossGain != 0f) {
                crossGain = 0f
                java.util.Arrays.fill(ringL, 0f)
                java.util.Arrays.fill(ringR, 0f)
                lpL = 0f; lpR = 0f
            }
            return
        }

        val dT = targetDelaySamples
        val lpC = lowpassCoeff
        val smC = smoothCoeff
        var g = crossGain
        var d = delaySamples
        var pos = ringPos
        var fL = lpL
        var fR = lpR

        for (i in 0 until frames) {
            g += (gT - g) * smC
            d += (dT - d) * smC

            val inL = l[i]
            val inR = r[i]
            ringL[pos] = inL
            ringR[pos] = inR

            val di = d.toInt()
            val frac = d - di
            val i0 = (pos - di) and RING_MASK
            val i1 = (i0 - 1) and RING_MASK
            val tapL = ringL[i0] + (ringL[i1] - ringL[i0]) * frac
            val tapR = ringR[i0] + (ringR[i1] - ringR[i0]) * frac

            fL += (tapL - fL) * lpC
            fR += (tapR - fR) * lpC

            val norm = 1f / (1f + g)
            l[i] = (inL + fR * g) * norm
            r[i] = (inR + fL * g) * norm

            pos = (pos + 1) and RING_MASK
        }

        crossGain = g
        delaySamples = d
        ringPos = pos
        lpL = fL
        lpR = fR
    }

    private fun recompute() {
        val s = _state.value
        if (s.algorithm == CrossfeedAlgorithm.SPEAKER) {
            val halfAngleRad = Math.toRadians(s.speakerAngleDeg / 2.0)

            val itdSec = HEAD_RADIUS_M / SPEED_OF_SOUND_MS * (halfAngleRad + sin(halfAngleRad))
            targetDelaySamples = (itdSec * sampleRate).toFloat().coerceIn(0f, RING_SIZE - 2f)

            targetCrossGain = if (!s.enabled) 0f
                else MAX_CROSS_GAIN * cos(halfAngleRad).toFloat().coerceAtLeast(0f)

            lowpassCoeff = onePoleCoeff(HEAD_SHADOW_CUTOFF_HZ)
        } else {
            targetDelaySamples = (s.algorithm.delayUs * 1e-6 * sampleRate)
                .toFloat().coerceIn(0f, RING_SIZE - 2f)
            targetCrossGain = if (!s.enabled) 0f
                else 10f.pow(s.algorithm.feedDb / 20f)
            lowpassCoeff = onePoleCoeff(s.algorithm.cutoffHz)
        }
        smoothCoeff = onePoleCoeff(1.0 / (2.0 * PI * SMOOTH_TIME_SEC))
    }

    private fun onePoleCoeff(cutoffHz: Double): Float =
        (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()

    private companion object {
        const val RING_SIZE = 256
        const val RING_MASK = RING_SIZE - 1
        const val HEAD_RADIUS_M = 0.0875
        const val SPEED_OF_SOUND_MS = 343.0
        const val HEAD_SHADOW_CUTOFF_HZ = 700.0
        const val MAX_CROSS_GAIN = 0.65f
        const val SMOOTH_TIME_SEC = 0.005
    }
}
