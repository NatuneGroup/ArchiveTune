/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

/**
 * What a provider's rejection of a pooled credential actually tells us. A 401 from a token that was
 * already expired when it was sent is the expiry we saw — proof of a stale cache, not of a dead
 * account — so it is never reported to the pool. Only a genuine auth rejection of a credential that
 * was fresh at attempt time may count as account death.
 */
object PoolCredentialPolicy {

    /**
     * True when an [unauthorized] provider failure may be reported to the pool as account death:
     * only when the credential was NOT already expired at attempt time
     * ([credentialExpiredAtAttempt]). Firing an expired token and taking its guaranteed 401 must
     * not kill a healthy account.
     */
    fun countsAsAccountDeath(
        unauthorized: Boolean,
        credentialExpiredAtAttempt: Boolean,
    ): Boolean = unauthorized && !credentialExpiredAtAttempt
}
