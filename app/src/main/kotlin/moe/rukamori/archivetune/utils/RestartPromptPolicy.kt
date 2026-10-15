/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import moe.rukamori.archivetune.constants.*

enum class RestartReason { UI_SCALE, FONT, TABLET_MODE, GLASS, NAVIGATION_GLASS, USB_OUTPUT, STORAGE, REGION }

internal fun MutablePreferences.setCustomFont(uri: String, name: String) {
    this[CustomFontUriKey] = uri
    this[CustomFontNameKey] = name
    this[FontPreferenceKey] = AppFontPreference.CUSTOM.name
}

/** Effective values, not raw-key existence: writing a default or migrating a font is not an edit. */
fun restartSettingValues(preferences: Preferences, bitPerfectSupported: Boolean): Map<RestartReason, String> {
    val settings = RestartScopedSettings.from(preferences)
    val bitPerfect = bitPerfectSupported &&
        preferences[BitPerfectUsbOutputKey] == true &&
        preferences[CrossfadeEnabledKey] != true && preferences[AutomixEnabledKey] != true
    val exclusive = !bitPerfect && preferences[UsbExclusiveAudioKey] == true
    return restartSettingValues(settings) + mapOf(
        RestartReason.USB_OUTPUT to "$bitPerfect|$exclusive",
        RestartReason.REGION to (preferences[YouTubeMusicRegionKey] ?: SYSTEM_DEFAULT),
    )
}

fun restartSettingValues(settings: RestartScopedSettings): Map<RestartReason, String> = mapOf(
    RestartReason.UI_SCALE to settings.uiScale.toString(),
    RestartReason.FONT to "${settings.fontPreference}|${settings.customFontUri.takeIf { settings.fontPreference == AppFontPreference.CUSTOM }.orEmpty()}",
    RestartReason.TABLET_MODE to settings.tabletModeEnabled.toString(),
    RestartReason.GLASS to settings.liquidGlassEnabled.toString(),
    RestartReason.NAVIGATION_GLASS to
        "${settings.liquidGlassNavBarEnabled}|${settings.navigationBarFrostedBlur}|${settings.navigationBarTintFrostedBlur}",
)

/** Event-driven only: initial reads, navigation and recomposition never call this policy. */
class RestartPromptPolicy {
    private val pending = linkedMapOf<RestartReason, String>()
    private val dismissed = mutableMapOf<RestartReason, String>()

    val reasons: Set<RestartReason> get() = pending.keys.toSet()
    val visible: Boolean get() = pending.any { (reason, value) -> dismissed[reason] != value }

    fun committed(
        before: Map<RestartReason, String>,
        after: Map<RestartReason, String>,
        running: Map<RestartReason, String>,
    ) {
        after.forEach { (reason, value) ->
            if (running[reason] == value) {
                pending.remove(reason)
                dismissed.remove(reason)
            } else if (before[reason] != value) {
                pending[reason] = value
            }
        }
    }

    fun storageCommitted(token: String) {
        pending[RestartReason.STORAGE] = token
    }

    fun later() {
        dismissed.putAll(pending)
    }

    /** Consume before invoking the platform restart so duplicate confirms cannot relaunch twice. */
    fun consumeRestart(busy: Boolean): Boolean {
        if (!visible || busy) return false
        later()
        return true
    }
}
