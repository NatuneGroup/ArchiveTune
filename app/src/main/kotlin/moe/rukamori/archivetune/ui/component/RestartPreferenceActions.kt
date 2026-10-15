/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.utils.PreferenceSnapshot
import moe.rukamori.archivetune.viewmodels.RestartPromptViewModel

val navigationTreatmentKeys = setOf(
    NavigationBarFrostedBlurKey, NavigationBarTintFrostedBlurKey, LiquidGlassNavBarEnabledKey,
)

internal fun MutablePreferences.applyNavigationTreatment(key: Preferences.Key<Boolean>, enabled: Boolean) {
    this[key] = enabled
    if (enabled) navigationTreatmentKeys.filterNot { it == key }.forEach { this[it] = false }
}

fun setNavigationTreatment(
    controller: RestartPromptViewModel,
    preferences: PreferenceSnapshot,
    key: Preferences.Key<Boolean>,
    enabled: Boolean,
) {
    controller.edit(preferences) {
        applyNavigationTreatment(key, enabled)
    }
}

/** Shared by direct rows and search switches; no restart scope is added to the live DSP engines. */
fun setPlaybackOutputPreference(
    controller: RestartPromptViewModel,
    preferences: PreferenceSnapshot,
    context: Context,
    key: Preferences.Key<Boolean>,
    enabled: Boolean,
) {
    controller.edit(preferences, onCommitted = { result ->
        if ((enabled || key == BitPerfectUsbOutputKey) &&
            result.after[BitPerfectUsbOutputKey] != true && BitPerfectUsbOutput.sinkActive
        ) {
            // Preserve immediate DAC release, but never release it on a failed save.
            BitPerfectUsbOutput.setEnabled(context, false)
        }
    }) {
        this[key] = enabled
        if (enabled) {
            when (key) {
                CrossfadeEnabledKey, AutomixEnabledKey -> {
                    this[AudioOffload] = false
                    this[if (key == CrossfadeEnabledKey) AutomixEnabledKey else CrossfadeEnabledKey] = false
                    this[BitPerfectUsbOutputKey] = false
                    this[UsbExclusiveAudioKey] = false
                }
                AudioOffload -> {
                    this[SkipSilenceKey] = false
                    this[CrossfadeEnabledKey] = false
                    this[AutomixEnabledKey] = false
                    this[BitPerfectUsbOutputKey] = false
                    this[UsbExclusiveAudioKey] = false
                    this[TryptifyAudioProcessingKey] = false
                    this[LastwaveAudioProcessingKey] = false
                    this[FloatDspEnabledKey] = false
                }
                BitPerfectUsbOutputKey, UsbExclusiveAudioKey -> {
                    this[AudioOffload] = false
                    this[CrossfadeEnabledKey] = false
                    this[AutomixEnabledKey] = false
                    this[if (key == BitPerfectUsbOutputKey) UsbExclusiveAudioKey else BitPerfectUsbOutputKey] = false
                }
            }
        }
    }
}
