/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.PoolCredentialPolicy
import moe.rukamori.archivetune.utils.PoolExpiryReleaser
import timber.log.Timber

/**
 * Sequential Tidal pool-account resolve for playback: premium-first, one account at a time.
 *
 * Pooled Tidal access tokens live about an hour while the pool cache is held for hours, so a cached
 * one is usually already stale — firing it guarantees a 401 that is NOT a rejection. An
 * expired-looking token therefore earns a bounded AWAITED re-lease before it is tried:
 * single-flight (concurrent resolves share one fetch), capped at a few seconds, and throttled only
 * by its own failures — never by the routine 5–24h cache TTL, which would leave dead tokens in
 * service for hours. That is what lets the current play be rescued by fresh credentials instead of
 * reporting a healthy account dead off a stale token.
 *
 * Each account's freshness is snapshotted before firing: only a 401 from a token that was fresh at
 * attempt time may be reported as account death (a stale-token 401 is logged, never reported — see
 * [PoolCredentialPolicy]).
 */
internal object TidalPoolResolve {

    /**
     * Tries each pooled Tidal account in order until one yields a stream. Returns null when none
     * resolve — the caller falls through to the public-instance path.
     */
    fun resolve(
        context: Context,
        mediaId: String,
        title: String,
        artists: List<String>,
        durationMs: Long?,
        apiQuality: String,
        cacheDir: File,
    ): DirectStream? {
        // Awaited, bounded, single-flight re-lease — one call rescues this play.
        val poolAccounts = PoolExpiryReleaser.rescuedTidalAccounts(context)

        for (poolAccount in poolAccounts) {
            val poolCountry = poolAccount.countryCode?.trim()?.ifBlank { null } ?: "US"
            // Freshness snapshot before firing: only a fresh-token 401 may report death.
            val expiredAtAttempt = TidalAccountManager.isAccessTokenExpired(poolAccount.token)
            val poolStream = runCatching {
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    TidalAccountManager.resolveDirectStream(
                        accessToken = poolAccount.token,
                        title = title,
                        artists = artists,
                        durationMs = durationMs,
                        audioQuality = apiQuality,
                        cacheDir = cacheDir,
                        countryCode = poolCountry,
                    )
                }
            }
                .onFailure { error ->
                    when {
                        PoolCredentialPolicy.countsAsAccountDeath(
                            TidalAccountManager.isUnauthorized(error),
                            expiredAtAttempt,
                        ) -> {
                            PoolAccountManager.report(
                                "tidal",
                                "account",
                                poolAccount.id,
                                "dead",
                            )
                            // A genuine fresh-token rejection earns the bounded forced
                            // re-lease — fire-and-forget, per-provider bounded.
                            PoolAccountManager.refreshOnRejection(context, "tidal")
                        }
                        TidalAccountManager.isUnauthorized(error) ->
                            Timber.tag("TidalPoolResolve")
                                .d(
                                    "Tidal pool account %s rejected an already-expired token; not reporting it dead",
                                    poolAccount.id,
                                )
                        else ->
                            Timber.tag("TidalPoolResolve")
                                .w(error, "Tidal pool account resolve failed for %s", mediaId)
                    }
                }
                .getOrNull()
            if (poolStream != null) {
                Timber.tag("TidalPoolResolve")
                    .d("Tidal resolved via pool account (premium=%s)", poolAccount.premium)
                PoolAccountManager.noteAccountSuccess("tidal", poolAccount.id)
                return poolStream
            }
            // A failed account cost up to ~20s, so it sorts last for a while rather than paying
            // that again on the next track — demotion, not removal.
            PoolAccountManager.noteAccountFailure("tidal", poolAccount.id)
        }
        return null
    }
}
