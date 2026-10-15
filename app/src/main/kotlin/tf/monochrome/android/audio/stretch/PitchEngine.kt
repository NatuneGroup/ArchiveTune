/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.stretch

enum class PitchEngine(val nativeId: Int, val label: String, val summary: String) {
    VOCODER(0, "Smooth", "Best on sustained, melodic material. Softens attacks."),

    WSOLA(1, "Punchy", "Keeps drum attacks intact. Can sound phasey on pads."),
    ;

    companion object {
        fun fromName(name: String?): PitchEngine =
            entries.firstOrNull { it.name == name } ?: VOCODER
    }
}

enum class PitchQuality(
    val nativeId: Int,
    val label: String,

    val bassFloorHz: Int,

    val vocoderErrorHz: String,
) {
    FAST(0, "Fast", 375, "1.40 Hz"),
    BALANCED(1, "Balanced", 188, "0.42 Hz"),
    HIGH(2, "High", 94, "0.18 Hz"),
    ;

    companion object {
        fun fromName(name: String?): PitchQuality =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
