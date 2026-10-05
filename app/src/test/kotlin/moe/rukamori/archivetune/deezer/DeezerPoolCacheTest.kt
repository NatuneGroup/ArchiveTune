/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.deezer

import org.junit.Assert.assertEquals
import org.junit.Test

class DeezerPoolCacheTest {
    @Test
    fun poolInvalidationRetainsOnlyTheManualSession() {
        val manualArl = "manual-arl-fixture"
        val pooledArl = "pooled-arl-fixture"

        assertEquals(
            setOf(pooledArl),
            DeezerAudioProvider.pooledSessionKeysToEvict(setOf(manualArl, pooledArl), manualArl),
        )
    }

    @Test
    fun poolInvalidationRemovesEverySessionWithoutAManualAccount() {
        val pooledArls = setOf("pooled-arl-a-fixture", "pooled-arl-b-fixture")

        assertEquals(pooledArls, DeezerAudioProvider.pooledSessionKeysToEvict(pooledArls, null))
    }
}
