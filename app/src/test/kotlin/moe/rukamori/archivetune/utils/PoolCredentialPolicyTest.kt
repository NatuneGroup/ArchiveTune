/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 401 from a credential that was already expired when it was sent is the expiry we saw — proof
 * of a stale cache, not a dead account — so it must never be reported to the pool. Only a genuine
 * auth rejection of a credential that was fresh at attempt time counts as account death.
 */
class PoolCredentialPolicyTest {
    @Test
    fun expiredTokenRejectionNeverReportsDeath() {
        assertFalse(
            PoolCredentialPolicy.countsAsAccountDeath(
                unauthorized = true,
                credentialExpiredAtAttempt = true,
            )
        )
    }

    @Test
    fun freshTokenRejectionReportsDeath() {
        assertTrue(
            PoolCredentialPolicy.countsAsAccountDeath(
                unauthorized = true,
                credentialExpiredAtAttempt = false,
            )
        )
    }

    @Test
    fun nonAuthFailuresNeverReportDeath() {
        assertFalse(
            PoolCredentialPolicy.countsAsAccountDeath(
                unauthorized = false,
                credentialExpiredAtAttempt = false,
            )
        )
        assertFalse(
            PoolCredentialPolicy.countsAsAccountDeath(
                unauthorized = false,
                credentialExpiredAtAttempt = true,
            )
        )
    }
}
