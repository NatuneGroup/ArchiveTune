/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class JapanesePackPolicyRegressionTest {
    private fun eligible(region: String? = "JP", installed: Boolean = false, choice: Boolean? = null,
        removed: Boolean = false, attempted: Boolean = false) =
        JapaneseLanguagePackManager.automaticEligible(region, installed, choice, removed, attempted)

    @Test fun onlyPrimaryDeviceRegionJpQualifiesRegardlessOfLanguage() {
        assertTrue(eligible(Locale.forLanguageTag("en-JP").country))
        assertTrue(eligible(Locale.forLanguageTag("ja-JP").country))
        assertFalse(eligible(Locale.forLanguageTag("ja-US").country))
        assertFalse(eligible(null))
        assertFalse(eligible(""))
        val deviceLocales = listOf(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("ja-JP"))
        assertFalse(eligible(deviceLocales.first().country))
    }

    @Test fun usablePackExplicitOffRemovalAndPersistedAttemptBlockAutomation() {
        assertTrue(eligible(choice = null))
        assertTrue(eligible(choice = true))
        assertFalse(eligible(choice = false))
        assertFalse(eligible(installed = true))
        assertFalse(eligible(removed = true))
        assertFalse(eligible(attempted = true))
        // The persisted consideration, not network type, governs later foregrounds/restarts.
        assertFalse(eligible(attempted = true, choice = true))
    }

    @Test fun automaticCompletionNeverOverridesOffOrRemovalWrittenDuringTransfer() {
        listOf<Boolean?>(null, true, false).forEach { starting ->
            assertFalse(shouldEnableJapaneseAfterInstall(true, starting, false, false))
            assertFalse(shouldEnableJapaneseAfterInstall(true, starting, null, true))
            assertFalse(shouldEnableJapaneseAfterInstall(true, starting, true, true))
            assertTrue(shouldEnableJapaneseAfterInstall(true, starting, null, false))
            assertTrue(shouldEnableJapaneseAfterInstall(true, starting, true, false))
        }
        assertTrue(shouldEnableJapaneseAfterInstall(false, false, false, false))
        assertFalse(shouldEnableJapaneseAfterInstall(false, true, false, false))
        assertFalse(shouldEnableJapaneseAfterInstall(false, null, false, false))
    }

    @Test fun connectingUnknownLengthAndValidationProgressCannotClaimInstalled() {
        assertEquals(-1, JapaneseLanguagePackState.Downloading().progressPercent)
        assertEquals(-1, japanesePackProgress(100, -1))
        assertEquals(-1, japanesePackProgress(100, 0))
        assertEquals(0, japanesePackProgress(0, 1000))
        assertEquals(50, japanesePackProgress(500, 1000))
        assertEquals(99, japanesePackProgress(1000, 1000))
        assertEquals(99, japanesePackProgress(1001, 1000))
        assertFalse(JapaneseLanguagePackState.Failed("fixture").removalFailed)
        assertTrue(JapaneseLanguagePackState.Failed("fixture", true).removalFailed)
    }

    @Test fun integrityRequiresBothExactLengthAndPinnedDigest() {
        val digest = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
        assertTrue(validJapanesePackIntegrity(13_343_016, digest))
        listOf(0L, 13_343_015L, 13_343_017L, Long.MAX_VALUE).forEach {
            assertFalse(validJapanesePackIntegrity(it, digest))
        }
        assertFalse(validJapanesePackIntegrity(13_343_016, "0".repeat(64)))
        assertFalse(validJapanesePackIntegrity(13_343_016, ""))
    }
}
