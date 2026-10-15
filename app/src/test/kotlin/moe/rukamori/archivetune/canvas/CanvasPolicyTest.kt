/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.canvas

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.constants.*
import org.junit.Assert.*
import org.junit.Test

class CanvasPolicyTest {
    @Test
    fun persistedDefaults_preserveDisabledWifiAndCachePolicy() {
        val defaults = canvasConfigurationFrom(emptyPreferences())
        assertFalse(defaults.enabled)
        assertFalse(defaults.wifiOnly)
        assertEquals(256, defaults.cacheLimitMb)
        assertEquals(CanvasSource.ALL, defaults.source)
        assertTrue(defaults.lowDataMode)
        for (limit in listOf(-9, -1, 0, 128)) {
            assertEquals(limit.coerceAtLeast(-1), canvasConfigurationFrom(mutablePreferencesOf(MaxCanvasCacheSizeKey to limit)).cacheLimitMb)
        }
    }

    @Test
    fun sourceAliasesAndSeparateSpotifyToggle_doNotEnableCanvas() {
        for (raw in listOf(null, "invalid", "BOTH", "ALL")) assertEquals(CanvasSource.ALL, CanvasSource.fromPreference(raw))
        CanvasSource.entries.forEach { assertEquals(it, CanvasSource.fromPreference(it.name)) }
        val separate = mutablePreferencesOf(SpotifyCanvasKey to true, CanvasSourceKey to CanvasSource.SPOTIFY.name)
        val configuration = canvasConfigurationFrom(separate)
        assertFalse(configuration.enabled)
        assertEquals(CanvasSource.SPOTIFY, configuration.source)
        assertFalse(CanvasSource.ALL.accepts(null))
        assertFalse(CanvasSource.ALL.accepts(CanvasSource.ALL))
        assertTrue(CanvasSource.ALL.accepts(CanvasSource.SPOTIFY))
        assertFalse(CanvasSource.TIDAL.accepts(CanvasSource.SPOTIFY))
    }

    @Test
    fun networkGate_requiresReadinessEnabledOnlineAndConfiguredNetwork() {
        val configuration = CanvasConfiguration(enabled = true)
        val online = CanvasConnectivity(online = true, wifi = true, metered = false)
        val policy = CanvasPolicy(configuration, online, ready = true)
        assertTrue(policy.networkAllowed)
        assertFalse(policy.copy(ready = false).networkAllowed)
        assertFalse(policy.copy(configuration = configuration.copy(enabled = false)).networkAllowed)
        assertFalse(policy.copy(connectivity = online.copy(online = false)).networkAllowed)
        assertFalse(policy.copy(configuration = configuration.copy(wifiOnly = true), connectivity = online.copy(wifi = false)).networkAllowed)
        assertFalse(policy.copy(configuration = configuration.copy(lowDataMode = true), connectivity = online.copy(metered = true)).networkAllowed)
        assertTrue(policy.copy(configuration = configuration.copy(lowDataMode = false), connectivity = online.copy(wifi = false, metered = true)).networkAllowed)
    }
}
