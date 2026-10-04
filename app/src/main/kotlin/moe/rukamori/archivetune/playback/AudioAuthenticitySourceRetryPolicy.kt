/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.constants.AudioSourceType

internal object AudioAuthenticitySourceRetryPolicy {
    fun nextChain(
        configuredChain: List<AudioSourceType>,
        fallbackChain: List<AudioSourceType>,
        rejectedSources: Set<AudioSourceType>,
    ): List<AudioSourceType> {
        val configuredAvailable = configuredChain.filterNot(rejectedSources::contains)
        if (configuredAvailable.isNotEmpty() || configuredChain.isEmpty() || rejectedSources.isEmpty()) {
            return configuredAvailable
        }
        return fallbackChain.filterNot(rejectedSources::contains)
    }
}
