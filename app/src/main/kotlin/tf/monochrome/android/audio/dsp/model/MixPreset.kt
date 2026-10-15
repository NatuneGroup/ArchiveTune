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
data class MixPreset(
    val id: Long = 0,
    val name: String,
    val stateJson: String,
    val isCustom: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
