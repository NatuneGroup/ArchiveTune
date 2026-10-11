/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

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
