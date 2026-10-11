/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.dsp.model

data class BusLevels(
    val peakDbL: Float = -60f,
    val peakDbR: Float = -60f,
    val holdDbL: Float = -60f,
    val holdDbR: Float = -60f
)
