/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.MutablePreferences
import moe.rukamori.archivetune.constants.AppleMusicExperienceKey
import moe.rukamori.archivetune.constants.InterfaceStyle
import moe.rukamori.archivetune.constants.LibraryStyle
import moe.rukamori.archivetune.constants.LibraryStyleKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.StyleBeforeAppleMusicKey
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * The Library tab's layout style and its setter.
 *
 * A read helper rather than the raw preference so the call sites cannot disagree about the key, the
 * default or the seeding rule.
 */
@Composable
fun rememberLibraryStyle(): Pair<LibraryStyle, (LibraryStyle) -> Unit> {
    // The style lays out the Library tab and nothing else. It used to also write the switch and
    // force the player style, so picking it silently restyled the player, the tab bar and the
    // headers and left the switch on afterwards. The style reads the switch back only to seed the
    // default for data that predates the style key; choosing a style never writes it.
    val (legacyEnabled) = rememberPreference(AppleMusicExperienceKey, defaultValue = false)
    val (style, setStyle) =
        rememberEnumPreference(
            LibraryStyleKey,
            defaultValue = if (legacyEnabled) LibraryStyle.APPLE_MUSIC else LibraryStyle.DEFAULT,
        )
    return style to setStyle
}

/**
 * True when the Apple Music presentation is on: the player design, the tab bar, the page headers and
 * the menus all ask this.
 *
 * It is the experience switch and nothing else. It used to be true whenever the library style was
 * Apple Music as well, which is what made a layout choice for one tab restyle the rest of the app.
 */
@Composable
fun rememberAppleMusicExperience(): Boolean {
    val (enabled) = rememberPreference(AppleMusicExperienceKey, defaultValue = false)
    return enabled
}

/**
 * Sets the Apple Music Experience: the switch, the library style and the player design style move
 * together, because the switch is the control that promises the whole presentation.
 *
 * The experience owns the player style while it is on, and it records what it displaced so turning
 * it off can give that back. A style picked by hand in the meantime is newer than ours and wins.
 */
@Composable
fun rememberAppleMusicExperienceToggle(): (Boolean) -> Unit {
    val dataStore = LocalContext.current.dataStore
    return { enabled ->
        PreferenceStore.launchEdit(dataStore) {
            applyInterfaceStyle(if (enabled) InterfaceStyle.APPLE_MUSIC else InterfaceStyle.MATERIAL_EXPRESSIVE)
        }
    }
}

internal fun MutablePreferences.applyInterfaceStyle(style: InterfaceStyle) {
    val enabled = style == InterfaceStyle.APPLE_MUSIC
    if ((this[AppleMusicExperienceKey] ?: false) == enabled) return

    val playerStyle = this[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    this[AppleMusicExperienceKey] = enabled
    this[LibraryStyleKey] = if (enabled) LibraryStyle.APPLE_MUSIC.name else LibraryStyle.DEFAULT.name

    if (enabled) {
        if (playerStyle != PlayerDesignStyle.APPLE_MUSIC) {
            this[StyleBeforeAppleMusicKey] = playerStyle.name
        }
        this[PlayerDesignStyleKey] = PlayerDesignStyle.APPLE_MUSIC.name
    } else if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) {
        this[PlayerDesignStyleKey] =
            this[StyleBeforeAppleMusicKey]
                .toEnum(PlayerDesignStyle.Default)
                .takeUnless { it == PlayerDesignStyle.APPLE_MUSIC }
                ?.name ?: PlayerDesignStyle.Default.name
    }
}

@Composable
fun rememberInterfaceStyle(): Pair<InterfaceStyle, (InterfaceStyle) -> Unit> {
    val appleMusic = rememberAppleMusicExperience()
    val setAppleMusic = rememberAppleMusicExperienceToggle()
    val style = if (appleMusic) InterfaceStyle.APPLE_MUSIC else InterfaceStyle.MATERIAL_EXPRESSIVE
    return style to { selected -> setAppleMusic(selected == InterfaceStyle.APPLE_MUSIC) }
}
