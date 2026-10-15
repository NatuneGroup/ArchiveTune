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

@Serializable
data class BusConfig(
    val index: Int,
    val name: String,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val inputEnabled: Boolean = false,
    val plugins: List<PluginInstance> = emptyList()
) {
    val isMaster: Boolean get() = index == 4

    companion object {
        fun defaultBuses(): List<BusConfig> = listOf(
            BusConfig(index = 0, name = "Bus 1", inputEnabled = true),
            BusConfig(index = 1, name = "Bus 2"),
            BusConfig(index = 2, name = "Bus 3"),
            BusConfig(index = 3, name = "Bus 4"),
            BusConfig(index = 4, name = "Master")
        )
    }
}
