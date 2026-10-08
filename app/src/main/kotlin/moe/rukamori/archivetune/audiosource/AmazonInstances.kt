/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The Amazon instances to try, and the authorization material each one needs.
 * Two independent things feed this list: the base URLs the user typed into
 * [moe.rukamori.archivetune.constants.AmazonInstancesKey], and the ones the ArchivePool serves for
 * `amazon-music` (an instance host plus, when its operator published them, that host's own
 * credentials). The user's own entries always come first — a host they configured deliberately
 * should not be queued behind a pooled one — and a pooled host never receives the user's material.
 */



package moe.rukamori.archivetune.audiosource

/** One instance to try, with the material that instance accepts. */
data class AmazonInstance(
    val baseUrl: String,
    val bypassToken: String? = null,
    val turnstileJwt: String? = null,
    /** When the JWT stops being worth sending; null when no expiry was recorded for it. */
    val turnstileJwtExpiresAtMs: Long? = null,
    /** True when this host came from the pool rather than from the user's own list. */
    val fromPool: Boolean = false,
) {
    /**
     * Whether there is anything to authorize with. The provider refuses a request with neither
     * material (it would be a guaranteed 401/428), so such an instance is not worth a round trip.
     */
    val hasAuthMaterial: Boolean
        get() = !bypassToken.isNullOrBlank() || !turnstileJwt.isNullOrBlank()
}

object AmazonInstances {
    /** Trailing slashes are noise: the provider appends its own path segments. */
    fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

    /** The user's list: one base URL per line, tolerating commas and stray whitespace. */
    fun parseBaseUrls(stored: String): List<String> =
        stored
            .split('\n', ',')
            .map(::normalizeUrl)
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * A pooled instance may carry a Turnstile JWT whose expiry the pool publishes in plain text, so
     * a stale one can be skipped instead of spent on a call that must fail. No expiry means the pool
     * did not record one; the instance itself still rejects a stale token, exactly as it does today.
     */
    fun isJwtUsable(
        expiresAtMs: Long?,
        nowMs: Long,
    ): Boolean = expiresAtMs == null || expiresAtMs > nowMs

    /**
     * The ordered, de-duplicated list to try: the user's instances first, then the pooled ones.
     *
     * Material is per instance and never crosses over. An operator's bypass token is issued by that
     * operator and a solved Turnstile JWT is bound to the origin that handed out the challenge, so
     * the user's token is not offered to a pooled host (nor the pool's to the user's). A stale
     * Turnstile JWT is dropped [isJwtUsable], and a pooled host left with no material at all is
     * dropped too: it can only answer 401/428.
     */
    fun merge(
        local: List<String>,
        localBypassToken: String?,
        localTurnstileJwt: String?,
        pooled: List<AmazonInstance>,
        nowMs: Long,
    ): List<AmazonInstance> {
        val out = mutableListOf<AmazonInstance>()
        val seen = mutableSetOf<String>()
        local.forEach { url ->
            val normalized = normalizeUrl(url)
            if (normalized.isNotEmpty() && seen.add(normalized)) {
                out +=
                    AmazonInstance(
                        baseUrl = normalized,
                        bypassToken = localBypassToken?.ifBlank { null },
                        turnstileJwt = localTurnstileJwt?.ifBlank { null },
                    )
            }
        }
        pooled.forEach { instance ->
            val normalized = normalizeUrl(instance.baseUrl)
            if (normalized.isEmpty() || !seen.add(normalized)) return@forEach
            val usable =
                instance.copy(
                    baseUrl = normalized,
                    turnstileJwt =
                        instance.turnstileJwt
                            ?.takeIf { it.isNotBlank() && isJwtUsable(instance.turnstileJwtExpiresAtMs, nowMs) },
                )
            if (usable.hasAuthMaterial) out += usable
        }
        return out
    }
}
