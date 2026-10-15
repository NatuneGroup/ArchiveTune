/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupAccountPreferenceKeysTest {
    @Test
    fun discordSessionAndProfilePreferencesAreAccountOnly() {
        assertTrue("discordToken" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
        assertTrue("discordRefreshToken" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
        assertTrue("discordTokenExpiresAt" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
        assertTrue("discordUsername" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
        assertTrue("discordName" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
        assertTrue("discordAvatarUrl" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
    }

    @Test
    fun pooledCredentialCachesDoNotTravelWithSettingsOnlyBackups() {
        listOf(
            "poolTidalAccounts",
            "poolQobuzAccounts",
            "poolDeezerAccounts",
            "poolAppleMusicAccounts",
            "poolAmazonAccounts",
            "poolAccountsLastRefreshAtMillis",
        ).forEach { key ->
            assertTrue(key in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
        }
    }

    @Test
    fun thePoolOptOutChoiceRemainsAnExportableSetting() {
        assertFalse("use_pool_accounts" in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS)
    }
}
