/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType

internal fun enabledPlaybackSources(
    candidates: List<AudioSourceType>,
    enabledSources: Set<AudioSourceType>,
): List<AudioSourceType> = candidates.distinct().filter { it in enabledSources }

internal fun canUseCachedPlaybackSource(
    source: AudioSourceType,
    enabled: Boolean,
    rejectedSources: Set<AudioSourceType>,
): Boolean = enabled && source !in rejectedSources
