/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.constants.*
import org.junit.Assert.*
import org.junit.Test

class RestartPromptPolicyTest {
    private val running = restartSettingValues(emptyPreferences(), bitPerfectSupported = true)
    private fun changed(reason: RestartReason, value: String) = running + (reason to value)

    @Test
    fun initialAndNoOpCommit_doNotPrompt() {
        val policy = RestartPromptPolicy()
        assertFalse(policy.visible)
        policy.committed(running, running, running)
        assertFalse(policy.visible)
        assertTrue(policy.reasons.isEmpty())
    }

    @Test
    fun laterHandlesExactValue_reentryDoesNotReplayButNewValuePrompts() {
        val policy = RestartPromptPolicy()
        val first = changed(RestartReason.UI_SCALE, "1.1")
        policy.committed(running, first, running)
        assertTrue(policy.visible)
        policy.later()
        assertFalse(policy.visible)
        policy.committed(first, first, running)
        assertFalse(policy.visible)
        assertEquals(setOf(RestartReason.UI_SCALE), policy.reasons)
        policy.committed(first, changed(RestartReason.UI_SCALE, "1.2"), running)
        assertTrue(policy.visible)
    }

    @Test
    fun reasonsMergeAndRevert_removesOnlyObsoleteReason() {
        val policy = RestartPromptPolicy()
        val scale = changed(RestartReason.UI_SCALE, "1.1")
        val both = scale + (RestartReason.GLASS to "true")
        policy.committed(running, scale, running)
        policy.committed(scale, both, running)
        assertEquals(setOf(RestartReason.UI_SCALE, RestartReason.GLASS), policy.reasons)
        policy.later()
        policy.committed(both, changed(RestartReason.GLASS, "true"), running)
        assertFalse(policy.visible)
        assertEquals(setOf(RestartReason.GLASS), policy.reasons)
        policy.committed(changed(RestartReason.GLASS, "true"), running, running)
        assertTrue(policy.reasons.isEmpty())
        policy.committed(running, scale, running)
        assertTrue(policy.visible)
    }

    @Test
    fun confirmWhileBusy_doesNotConsumeAndDoubleConfirmConsumesOnce() {
        val policy = RestartPromptPolicy()
        policy.committed(running, changed(RestartReason.TABLET_MODE, "true"), running)
        assertFalse(policy.consumeRestart(busy = true))
        assertTrue(policy.visible)
        var restarts = 0
        repeat(2) { if (policy.consumeRestart(busy = false)) restarts++ }
        assertEquals(1, restarts)
        assertFalse(policy.visible)
    }

    @Test
    fun laterNeverConfirmsAndStorageIdentity_onlyNewOperationPrompts() {
        val policy = RestartPromptPolicy()
        policy.storageCommitted("migration-a")
        assertTrue(policy.visible)
        policy.later()
        assertFalse(policy.consumeRestart(busy = false))
        policy.storageCommitted("migration-a")
        assertFalse(policy.visible)
        policy.storageCommitted("migration-b")
        assertTrue(policy.visible)
        assertEquals(setOf(RestartReason.STORAGE), policy.reasons)
    }

    @Test
    fun normalizedFontMigrationClampedScaleAndUnusedUri_areNoOps() {
        val legacy = mutablePreferencesOf(UseSystemFontKey to true, UiScaleFactorKey to 9f, CustomFontUriKey to "old")
        val normalized = legacy.toMutablePreferences().apply {
            this[FontPreferenceKey] = AppFontPreference.SYSTEM.name
            this[UiScaleFactorKey] = RestartScopedSettings.UI_SCALE_MAX
            this[CustomFontUriKey] = "unused-new-uri"
        }
        assertEquals(restartSettingValues(legacy, true), restartSettingValues(normalized, true))
        val policy = RestartPromptPolicy()
        policy.committed(restartSettingValues(legacy, true), restartSettingValues(normalized, true), running)
        assertFalse(policy.visible)
    }

    @Test
    fun customFontIdentity_usesUriAndSelectionButNotDisplayName() {
        val custom = emptyPreferences().toMutablePreferences().apply { setCustomFont("content://font/a", "a.ttf") }
        val renamed = custom.toMutablePreferences().apply { setCustomFont("content://font/a", "renamed.ttf") }
        val replaced = custom.toMutablePreferences().apply { setCustomFont("content://font/b", "a.ttf") }
        assertEquals(restartSettingValues(custom, true), restartSettingValues(renamed, true))
        assertNotEquals(
            restartSettingValues(custom, true)[RestartReason.FONT],
            restartSettingValues(replaced, true)[RestartReason.FONT],
        )
    }

    @Test
    fun usbUsesSupportedEffectiveChoiceAndActualRunningSinkFacts() {
        val enabled = mutablePreferencesOf(BitPerfectUsbOutputKey to true)
        assertEquals("false|false", restartSettingValues(enabled, false)[RestartReason.USB_OUTPUT])
        assertEquals("true|false", restartSettingValues(enabled, true)[RestartReason.USB_OUTPUT])
        val conflicts = enabled.toMutablePreferences().apply { this[CrossfadeEnabledKey] = true }
        assertEquals("false|false", restartSettingValues(conflicts, true)[RestartReason.USB_OUTPUT])
        val exclusive = mutablePreferencesOf(UsbExclusiveAudioKey to true)
        assertEquals("false|true", restartSettingValues(exclusive, true)[RestartReason.USB_OUTPUT])
        val policy = RestartPromptPolicy()
        // The sink may have started after the activity. Disabling it differs from that real sink.
        val actualRunning = running + (RestartReason.USB_OUTPUT to "true|false")
        policy.committed(restartSettingValues(enabled, true), restartSettingValues(conflicts, true), actualRunning)
        assertEquals(setOf(RestartReason.USB_OUTPUT), policy.reasons)
        assertTrue(policy.visible)
        policy.committed(restartSettingValues(conflicts, true), restartSettingValues(enabled, true), actualRunning)
        assertFalse(policy.visible)
    }
}
