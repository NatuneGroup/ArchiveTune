/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.dsp.model

import kotlinx.serialization.Serializable
import tf.monochrome.android.audio.dsp.SnapinType

@Serializable
data class PluginInstance(
    val slotIndex: Int,
    val typeOrdinal: Int,
    val bypassed: Boolean = false,
    val dryWet: Float = 1f,
    val parameters: Map<Int, Float> = emptyMap(),

    val oversampling: Int = 1
) {
    val type: SnapinType?
        get() = SnapinType.fromOrdinal(typeOrdinal)

    val displayName: String
        get() = type?.displayName ?: "Unknown"
}
