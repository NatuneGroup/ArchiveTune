package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ToneControls(
    val enabled: Boolean = true,
    val bassGainDb: Float = 0f,
    val bassFreq: Float = 110f,
    val bassQ: Float = 0.7f,
    val trebleGainDb: Float = 0f,
    val trebleFreq: Float = 9000f,
    val trebleQ: Float = 0.7f,
) {
    fun clamped(): ToneControls = ToneControls(
        enabled = enabled,
        bassGainDb = bassGainDb.finiteOr(0f).coerceIn(GAIN_MIN, GAIN_MAX),
        bassFreq = bassFreq.finiteOr(110f).coerceIn(BASS_FREQ_MIN, BASS_FREQ_MAX),
        bassQ = bassQ.finiteOr(0.7f).coerceIn(Q_MIN, Q_MAX),
        trebleGainDb = trebleGainDb.finiteOr(0f).coerceIn(GAIN_MIN, GAIN_MAX),
        trebleFreq = trebleFreq.finiteOr(9000f).coerceIn(TREBLE_FREQ_MIN, TREBLE_FREQ_MAX),
        trebleQ = trebleQ.finiteOr(0.7f).coerceIn(Q_MIN, Q_MAX),
    )

    fun toBands(): List<EqBand> {
        if (!enabled) return emptyList()
        return listOf(
        EqBand(
            id = BASS_BAND_ID, type = FilterType.LOWSHELF,
            freq = bassFreq, gain = bassGainDb, q = bassQ, enabled = bassGainDb != 0f,
        ),
        EqBand(
            id = TREBLE_BAND_ID, type = FilterType.HIGHSHELF,
            freq = trebleFreq, gain = trebleGainDb, q = trebleQ, enabled = trebleGainDb != 0f,
        ),
        )
    }

    val isFlat: Boolean get() = !enabled || (bassGainDb == 0f && trebleGainDb == 0f)

    companion object {
        val DEFAULT = ToneControls()

        const val GAIN_MIN = -12f
        const val GAIN_MAX = 12f
        const val BASS_FREQ_MIN = 40f
        const val BASS_FREQ_MAX = 500f
        const val TREBLE_FREQ_MIN = 2000f
        const val TREBLE_FREQ_MAX = 16000f
        const val Q_MIN = 0.3f
        const val Q_MAX = 1.5f

        private const val BASS_BAND_ID = -1001
        private const val TREBLE_BAND_ID = -1002

        private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
    }
}
