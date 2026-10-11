/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.usb

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BypassVolumeController @Inject constructor() {
    @Volatile
    private var volume: Float = 1.0f

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = volume
}
