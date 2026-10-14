/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.AppFontPreference
import moe.rukamori.archivetune.constants.CustomFontUriKey
import moe.rukamori.archivetune.constants.FontPreferenceKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.TabletModeEnabledKey
import moe.rukamori.archivetune.constants.UiScaleFactorKey
import moe.rukamori.archivetune.constants.UseSystemFontKey
import moe.rukamori.archivetune.extensions.toEnum

/**
 * Settings whose live application relayouts or re-records the whole app: the UI scale overrides
 * the root Density, fonts swap the typography of every text node, tablet mode rebuilds the
 * navigation scaffold, and the glass/frosted surfaces record the NavHost into a backdrop layer
 * every frame. They are applied once per process, like the bit-perfect USB output; changing one
 * stores the value and asks for a restart.
 */
data class RestartScopedSettings(
    val uiScale: Float,
    val fontPreference: AppFontPreference,
    val customFontUri: String,
    val tabletModeEnabled: Boolean,
    val liquidGlassEnabled: Boolean,
    val liquidGlassNavBarEnabled: Boolean,
    val navigationBarFrostedBlur: Boolean,
    val navigationBarTintFrostedBlur: Boolean,
) {
    companion object {
        const val UI_SCALE_MIN = 0.85f
        const val UI_SCALE_MAX = 1.30f

        val Defaults =
            RestartScopedSettings(
                uiScale = 1.0f,
                fontPreference = AppFontPreference.DEFAULT,
                customFontUri = "",
                tabletModeEnabled = false,
                liquidGlassEnabled = false,
                liquidGlassNavBarEnabled = false,
                navigationBarFrostedBlur = false,
                navigationBarTintFrostedBlur = false,
            )

        fun from(preferences: Preferences?): RestartScopedSettings {
            if (preferences == null) return Defaults
            return RestartScopedSettings(
                uiScale = (preferences[UiScaleFactorKey] ?: 1.0f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                fontPreference = resolveFontPreference(preferences[FontPreferenceKey], preferences[UseSystemFontKey]),
                customFontUri = preferences[CustomFontUriKey].orEmpty(),
                tabletModeEnabled = preferences[TabletModeEnabledKey] ?: false,
                liquidGlassEnabled = preferences[LiquidGlassEnabledKey] ?: false,
                liquidGlassNavBarEnabled = preferences[LiquidGlassNavBarEnabledKey] ?: false,
                navigationBarFrostedBlur = preferences[NavigationBarFrostedBlurKey] ?: false,
                navigationBarTintFrostedBlur = preferences[NavigationBarTintFrostedBlurKey] ?: false,
            )
        }

        /**
         * The legacy "use system font" switch is migrated to [FontPreferenceKey] after launch;
         * resolving it here keeps that migration from reading as a pending restart.
         */
        fun resolveFontPreference(
            stored: String?,
            legacyUseSystemFont: Boolean?,
        ): AppFontPreference =
            when {
                stored != null -> stored.toEnum(AppFontPreference.DEFAULT)
                legacyUseSystemFont == true -> AppFontPreference.SYSTEM
                else -> AppFontPreference.DEFAULT
            }
    }
}

/** Which restart-scoped settings differ between what is running and what is stored. */
data class PendingRestart(
    val uiScale: Boolean,
    val font: Boolean,
    val tabletMode: Boolean,
    val liquidGlass: Boolean,
    val navigationBarGlass: Boolean,
) {
    val any: Boolean get() = uiScale || font || tabletMode || liquidGlass || navigationBarGlass

    companion object {
        fun between(
            running: RestartScopedSettings,
            stored: RestartScopedSettings,
        ): PendingRestart =
            PendingRestart(
                uiScale = running.uiScale != stored.uiScale,
                font =
                    running.fontPreference != stored.fontPreference ||
                        (stored.fontPreference == AppFontPreference.CUSTOM && running.customFontUri != stored.customFontUri),
                tabletMode = running.tabletModeEnabled != stored.tabletModeEnabled,
                liquidGlass = running.liquidGlassEnabled != stored.liquidGlassEnabled,
                navigationBarGlass =
                    running.liquidGlassNavBarEnabled != stored.liquidGlassNavBarEnabled ||
                        running.navigationBarFrostedBlur != stored.navigationBarFrostedBlur ||
                        running.navigationBarTintFrostedBlur != stored.navigationBarTintFrostedBlur,
            )
    }
}

/** The values this process started with. Read once, on first access, and never again. */
object StartupSettings {
    val values: RestartScopedSettings by lazy {
        // Bounded: a slow first load starts the app on defaults rather than hanging it.
        PreferenceStore.blockUntilLoaded()
        RestartScopedSettings.from(PreferenceStore.snapshot())
    }

    val uiScale: Float get() = values.uiScale
    val fontPreference: AppFontPreference get() = values.fontPreference
    val customFontUri: String get() = values.customFontUri
    val tabletModeEnabled: Boolean get() = values.tabletModeEnabled
    val liquidGlassEnabled: Boolean get() = values.liquidGlassEnabled
    val liquidGlassNavBarEnabled: Boolean get() = values.liquidGlassNavBarEnabled
    val navigationBarFrostedBlur: Boolean get() = values.navigationBarFrostedBlur
    val navigationBarTintFrostedBlur: Boolean get() = values.navigationBarTintFrostedBlur

    fun pending(stored: Preferences?): PendingRestart = PendingRestart.between(values, RestartScopedSettings.from(stored))
}

fun Context.relaunchApp() {
    packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launchIntent)
    }
    kotlin.system.exitProcess(0)
}
