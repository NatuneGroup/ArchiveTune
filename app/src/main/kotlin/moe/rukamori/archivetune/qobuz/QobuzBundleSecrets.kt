/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.qobuz

import java.util.Base64

/**
 * Turns what the Qobuz web player's bundle scripts yield into an ordered list of app_secret
 * candidates.
 *
 * The bundle never labels the secret unambiguously, and it is not the only 32-character hex string
 * in there — chunk hashes and asset digests look identical. So nothing here decides which candidate
 * is real; the caller verifies each against the API in order and keeps the first that signs
 * requests. That ordering is all this file owns, and it is why the result is a list rather than a
 * set: a secret found beside the app id is worth trying before an anonymous hex string.
 *
 * Two bundle generations are covered. The current one writes the secret next to the app id, so the
 * scraper can key on it. The older one splits it across a `seed` and a timezone-keyed `info`/
 * `extras` pair that concatenate into base64 with 44 trailing characters of filler.
 *
 * The payload is one candidate per line, because the hook that produces it runs in a WebView and
 * `org.json` is not available to the unit tests that cover this:
 * ```
 * keyed:<32 hex>
 * hex:<32 hex>
 * legacy:<seed>:<info>:<extras>
 * ```
 */
object QobuzBundleSecrets {
    const val DEFAULT_LIMIT = 12

    private const val LEGACY_PADDING_LENGTH = 44
    private val HexSecret = Regex("^[a-f0-9]{32}$")

    /** @param limit how many candidates the caller is willing to verify over the network. */
    fun candidates(
        payload: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        val keyed = mutableListOf<String>()
        val legacy = mutableListOf<String>()
        val bare = mutableListOf<String>()

        payload.lineSequence().forEach { line ->
            val parts = line.trim().split(':')
            when (parts.firstOrNull()) {
                "keyed" -> parts.getOrNull(1)?.takeIf(HexSecret::matches)?.let { keyed += it }
                "hex" -> parts.getOrNull(1)?.takeIf(HexSecret::matches)?.let { bare += it }
                "legacy" ->
                    if (parts.size >= 4) {
                        decodeLegacy(seed = parts[1], info = parts[2], extras = parts[3])
                            ?.let { legacy += it }
                    }
            }
        }

        return (keyed + legacy + bare).distinct().take(limit)
    }

    /**
     * Reassembles an older bundle's split secret. The three fragments concatenate into one base64
     * string whose last [LEGACY_PADDING_LENGTH] characters are filler.
     */
    fun decodeLegacy(
        seed: String,
        info: String,
        extras: String,
    ): String? {
        val joined = seed.trim() + info.trim() + extras.trim()
        if (joined.length <= LEGACY_PADDING_LENGTH) return null
        val decoded =
            runCatching { String(Base64.getDecoder().decode(joined.dropLast(LEGACY_PADDING_LENGTH))) }
                .getOrNull()
                ?: return null
        return decoded.takeIf(HexSecret::matches)
    }
}
