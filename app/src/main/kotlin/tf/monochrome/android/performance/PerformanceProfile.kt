/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */



package tf.monochrome.android.performance

data class PerformanceProfile(
    val spectrumFps: Int = 60,
) {
    companion object {
        val HIGH = PerformanceProfile(spectrumFps = 60)
    }
}
