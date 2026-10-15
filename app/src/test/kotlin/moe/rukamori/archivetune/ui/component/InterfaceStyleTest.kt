/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.constants.AppleMusicExperienceKey
import moe.rukamori.archivetune.constants.InterfaceStyle
import moe.rukamori.archivetune.constants.LibraryStyle
import moe.rukamori.archivetune.constants.LibraryStyleKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.StyleBeforeAppleMusicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterfaceStyleTest {
    @Test
    fun `Material default leaves independent presentation choices unchanged`() {
        val preferences = mutablePreferencesOf(PlayerDesignStyleKey to PlayerDesignStyle.SIMPMUSIC.name)

        preferences.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertNull(preferences[AppleMusicExperienceKey])
        assertEquals(PlayerDesignStyle.SIMPMUSIC.name, preferences[PlayerDesignStyleKey])
    }

    @Test
    fun `Apple Music sets the complete experience from fresh preferences`() {
        val preferences = mutablePreferencesOf()

        preferences.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)

        assertEquals(true, preferences[AppleMusicExperienceKey])
        assertEquals(LibraryStyle.APPLE_MUSIC.name, preferences[LibraryStyleKey])
        assertEquals(PlayerDesignStyle.APPLE_MUSIC.name, preferences[PlayerDesignStyleKey])
        assertEquals(PlayerDesignStyle.Default.name, preferences[StyleBeforeAppleMusicKey])
    }

    @Test
    fun `reselecting Apple Music preserves the displaced player style`() {
        val preferences = mutablePreferencesOf(PlayerDesignStyleKey to PlayerDesignStyle.SIMPMUSIC.name)

        preferences.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        preferences.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        preferences.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertEquals(false, preferences[AppleMusicExperienceKey])
        assertEquals(LibraryStyle.DEFAULT.name, preferences[LibraryStyleKey])
        assertEquals(PlayerDesignStyle.SIMPMUSIC.name, preferences[PlayerDesignStyleKey])
    }

    @Test
    fun `disabling Apple Music keeps a subsequently chosen player style`() {
        val preferences = mutablePreferencesOf()
        preferences.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        preferences[PlayerDesignStyleKey] = PlayerDesignStyle.TIKTOK.name

        preferences.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertEquals(false, preferences[AppleMusicExperienceKey])
        assertEquals(PlayerDesignStyle.TIKTOK.name, preferences[PlayerDesignStyleKey])
    }

    @Test
    fun `invalid saved player style falls back to Material player`() {
        for (savedStyle in listOf("removed-style", PlayerDesignStyle.APPLE_MUSIC.name)) {
            val preferences =
                mutablePreferencesOf(
                    AppleMusicExperienceKey to true,
                    PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name,
                    StyleBeforeAppleMusicKey to savedStyle,
                )

            preferences.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

            assertEquals(PlayerDesignStyle.Default.name, preferences[PlayerDesignStyleKey])
        }
    }
}
