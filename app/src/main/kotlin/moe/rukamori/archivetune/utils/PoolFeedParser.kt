/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The pure half of the pool account feed: locating the accounts arrays, selecting and applying the
 * feed's decryptor, and parsing each service's entries into the cache shapes [PoolAccountManager]
 * holds. No state lives here — feeds parse the same way whether they arrive via a refresh, a
 * report's replacement block or a discovery answer.
 */
internal object PoolFeedParser {

    private const val TAG = "PoolAccountManager"

    /**
     * Accounts array for one service, tolerant of both feed shapes: the canonical `{ "tidal": {
     * "accounts": [...] } }` and a bare `{ "tidal": [...] }`. Null when absent.
     */
    fun accountsArray(root: JSONObject, service: String): JSONArray? =
        root.optJSONObject(service)?.optJSONArray("accounts") ?: root.optJSONArray(service)

    /**
     * Decrypts a sensitive field with the feed's decryptor. Blank/failed decryptions yield null.
     */
    fun field(
        obj: JSONObject,
        key: String,
        decryptor: (String) -> String?,
    ): String? {
        val raw = obj.optString(key, "").takeIf { it.isNotBlank() } ?: return null
        val decoded = decryptor(raw)?.takeIf { it.isNotBlank() }
        if (decoded == null && PoolCrypto.isEncrypted(raw)) {
            Timber.tag(TAG)
                .w("Dropped encrypted pool field %s because no available key could decrypt it", key)
        }
        return decoded
    }

    /**
     * Builds the decryptor lambda for a feed response, selecting the indicated encryption scheme.
     */
    fun decryptorFor(root: JSONObject, readKey: String): (String) -> String? {
        val derivedKey = PoolCrypto.deriveClientKey(readKey)
        val encryptionScheme = root.optString("encryption", "")
        return { raw ->
            if (encryptionScheme == "read-key") {
                PoolCrypto.maybeDecryptWith(raw, derivedKey) ?: PoolCrypto.maybeDecrypt(raw)
            } else {
                PoolCrypto.maybeDecrypt(raw) ?: PoolCrypto.maybeDecryptWith(raw, derivedKey)
            }
        }
    }

    /**
     * Replaces the dead-id element of [cacheList] with the parsed replacement, preserving order.
     * Null return means "nothing to persist": the dead entry is no longer cached (a refresh landed
     * while the report was in flight, so that cache is authoritative) or the replacement failed to
     * parse. An empty [replacementArr] drops the dead entry with no substitute.
     */
    fun <T> mergeList(
        cacheList: List<T>,
        deadId: Long,
        idOf: (T) -> Long?,
        replacementArr: JSONArray,
        parse: (JSONArray?, (String) -> String?) -> List<T>,
        decryptor: (String) -> String?,
    ): List<T>? {
        val deadIndex = cacheList.indexOfFirst { idOf(it) == deadId }
        if (deadIndex < 0) return null

        if (replacementArr.length() == 0) {
            return cacheList.filterIndexed { idx, _ -> idx != deadIndex }
        }

        val replacementEntry = parse(replacementArr, decryptor).firstOrNull() ?: return null

        // Replacement already cached (a concurrent report raced to the same one) — just drop the
        // dead entry.
        return if (cacheList.any { idOf(it) == idOf(replacementEntry) }) {
            cacheList.filterIndexed { idx, _ -> idx != deadIndex }
        } else {
            cacheList.mapIndexed { idx, entry -> if (idx == deadIndex) replacementEntry else entry }
        }
    }

    fun parseTidal(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<PoolAccountManager.TidalPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PoolAccountManager.TidalPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val token = field(obj, "token", decryptor) ?: continue
            out +=
                PoolAccountManager.TidalPoolAccount(
                    id = entryId(obj),
                    token = token,
                    refreshToken = field(obj, "refreshToken", decryptor),
                    countryCode = field(obj, "countryCode", decryptor),
                    premium = obj.optBoolean("premium", false),
                )
        }
        return out
    }

    fun parseQobuz(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<PoolAccountManager.QobuzPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PoolAccountManager.QobuzPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val token = field(obj, "token", decryptor) ?: continue
            val appId = field(obj, "appId", decryptor) ?: continue
            // Without an app secret the app cannot sign Qobuz stream URLs, so such an account is
            // useless for playback and is skipped rather than cached as a dead entry.
            val appSecret = field(obj, "appSecret", decryptor) ?: continue
            out +=
                PoolAccountManager.QobuzPoolAccount(
                    id = entryId(obj),
                    token = token,
                    appId = appId,
                    appSecret = appSecret,
                    premium = obj.optBoolean("premium", false),
                )
        }
        return out
    }

    fun parseDeezer(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<PoolAccountManager.DeezerPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PoolAccountManager.DeezerPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val arl = field(obj, "arl", decryptor) ?: continue
            out +=
                PoolAccountManager.DeezerPoolAccount(
                    id = entryId(obj),
                    arl = arl,
                    premium = obj.optBoolean("premium", false),
                    masterSecret = field(obj, "masterSecret", decryptor),
                )
        }
        return out
    }

    fun parseAmazon(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<PoolAccountManager.AmazonPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PoolAccountManager.AmazonPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            // Either tier is useful on its own: the account tier carries a session, the instance
            // tier a base URL. An entry with neither is not something playback can use.
            val baseUrl = obj.optString("baseUrl", "").trim().ifBlank { null }
            val session = field(obj, "session", decryptor)
            val turnstileJwtExpiresAtMs =
                obj.optLong("turnstileJwtExpiresAtMs", 0L).takeIf { it > 0L }
                    ?: parseIsoMillis(obj.optString("turnstileJwtExpiresAt", ""))
            if (baseUrl == null && session == null) continue
            out +=
                PoolAccountManager.AmazonPoolAccount(
                    id = entryId(obj),
                    session = session,
                    premium = obj.optBoolean("premium", false),
                    baseUrl = baseUrl,
                    bypassToken = field(obj, "bypassToken", decryptor),
                    turnstileJwt = field(obj, "turnstileJwt", decryptor),
                    turnstileJwtExpiresAtMs = turnstileJwtExpiresAtMs,
                )
        }
        return out
    }

    /**
     * Plaintext pool timestamps (the JWT expiry) as epoch millis; unparseable ones read as absent.
     */
    private fun parseIsoMillis(raw: String): Long? =
        raw.takeIf { it.isNotBlank() }
            ?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            }

    /**
     * Pool entry id from the accounts feed (positive when present); null for manual/legacy entries.
     */
    private fun entryId(obj: JSONObject): Long? = obj.optLong("id", 0L).takeIf { it > 0L }

    fun parseAppleMusic(
        arr: JSONArray?,
        decryptor: (String) -> String?,
    ): List<PoolAccountManager.AppleMusicPoolAccount> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PoolAccountManager.AppleMusicPoolAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val token = field(obj, "token", decryptor) ?: continue
            if (!token.startsWith("0.")) continue // media-user-tokens always start with "0."
            out +=
                PoolAccountManager.AppleMusicPoolAccount(
                    id = entryId(obj),
                    mediaUserToken = token,
                    premium = obj.optBoolean("premium", true),
                )
        }
        return out
    }
}
