/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import androidx.compose.runtime.MutableState
import moe.rukamori.archivetune.constants.FlamingoShowVolumeBarKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.ShowPlayerVolumeBarKey

fun effectivePlayerVolumeVisibility(
    style: PlayerDesignStyle,
    canonical: Boolean?,
    legacyFlamingo: Boolean?,
): Boolean = if (style == PlayerDesignStyle.FLAMINGO) {
    legacyFlamingo ?: canonical ?: true
} else {
    canonical ?: true
}

/** Only an explicit edit consolidates the old per-style choice; reads are non-destructive. */
fun PreferenceSnapshot.playerVolumeVisibility(style: PlayerDesignStyle): MutableState<Boolean> =
    SnapshotPreferenceState(
        read = {
            effectivePlayerVolumeVisibility(
                style,
                current[ShowPlayerVolumeBarKey],
                current[FlamingoShowVolumeBarKey],
            )
        },
        write = { value ->
            PreferenceStore.launchEdit(dataStore) {
                this[ShowPlayerVolumeBarKey] = value
                remove(FlamingoShowVolumeBarKey)
            }
        },
    )
