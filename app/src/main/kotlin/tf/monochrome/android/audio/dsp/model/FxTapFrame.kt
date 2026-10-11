package tf.monochrome.android.audio.dsp.model

class FxTapFrame(
    val seq: Long,
    val busIndex: Int,
    private val meters: FloatArray,
    val wave: FloatArray,
    val waveLen: Int,
) {
    fun inDb(slot: Int): Float = meters.getOrElse(slot * 2) { -60f }

    fun outDb(slot: Int): Float = meters.getOrElse(slot * 2 + 1) { -60f }
}
