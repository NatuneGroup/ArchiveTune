/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.tidal

import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.utils.ArchivePoolEndpoints
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.PoolAuthBackoff
import moe.rukamori.archivetune.utils.PoolEndpoints
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Source Pool discovery for Tidal: which community instances the pool currently lists.
 *
 * The feed is a paid shared resource, so this object owns the request-shaping rules for it: answers
 * (empty ones included) are cached for [TTL_MS], concurrent callers share one fetch via a
 * single-flight lock, a pool 401 backs the whole feature off through PoolAuthBackoff, and the
 * request itself fails over across the configured deployment and the fallback base the way
 * [PoolEndpoints] does — remembering a working deployment once a definitive answer lands.
 *
 * A dead pool never clears the last good list: callers keep serving community instances that are
 * still valid on their own.
 */
internal object TidalPoolDiscovery {

    /** Instance base URLs that came from the pool feed, kept so they can be stripped again. */
    var discoveredUrls: Set<String> = emptySet()
        private set

    /** The last pool discovery answer (empty counts) — feeds are not free. */
    @Volatile private var discoveryCache: List<String> = emptyList()

    @Volatile private var fetchedAtMs = 0L

    private val lock = Any()

    private const val TTL_MS = 30L * 60 * 1000L

    /** Drops every pool-derived answer; the caller strips [discoveredUrls] from its own list. */
    fun clear() {
        discoveredUrls = emptySet()
        discoveryCache = emptyList()
        fetchedAtMs = 0L
    }

    /**
     * Returns the list of newly discovered, valid base URLs (may be empty). Never throws; on
     * failure it returns the previous list so the caller can keep the manual list. Runs blocking
     * network calls, so invoke it off the main thread.
     */
    fun discoverInstances(client: OkHttpClient): List<String> {
        if (!PoolAccountManager.isPoolEnabled()) {
            TidalAudioProvider.clearPoolDiscovery()
            return emptyList()
        }
        // Auth backoff and the discovery TTL both keep stale answers rather than billing another
        // feed read — discovered URLs are community instances, usable while the pool rests.
        if (!PoolAuthBackoff.isReady || PoolAccountManager.authBackoffActive())
            return discoveryCache
        val now = System.currentTimeMillis()
        if (fetchedAtMs != 0L && now - fetchedAtMs < TTL_MS) return discoveryCache
        // Single-flight: resolve paths and health scans call this often, and concurrent callers
        // used to each spend a full feed fetch — the TTL re-check inside the lock hands
        // followers the copy the first caller just fetched.
        synchronized(lock) {
            if (!PoolAccountManager.isPoolEnabled()) {
                TidalAudioProvider.clearPoolDiscovery()
                return emptyList()
            }
            if (!PoolAuthBackoff.isReady || PoolAccountManager.authBackoffActive())
                return discoveryCache
            if (fetchedAtMs != 0L && System.currentTimeMillis() - fetchedAtMs < TTL_MS) {
                return discoveryCache
            }
            val discovered =
                AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
                    discoverInstancesWithinAttempt(client)
                }
            if (!PoolAccountManager.isPoolEnabled()) {
                TidalAudioProvider.clearPoolDiscovery()
                return emptyList()
            }
            // A null answer means no deployment produced a definitive reply: keep the previous
            // list. Either way the attempt stamps the TTL so a dead pool is not re-polled on
            // every resolve.
            if (discovered != null) {
                if (discovered.isNotEmpty()) discoveredUrls = discoveredUrls + discovered
                discoveryCache = discovered
            }
            fetchedAtMs = System.currentTimeMillis()
            return discoveryCache
        }
    }

    private fun discoverInstancesWithinAttempt(client: OkHttpClient): List<String>? {
        val discovered = LinkedHashSet<String>()
        var answeredAny = false
        for (base in PoolEndpoints.bases()) {
            if (!PoolAccountManager.isPoolEnabled() || PoolAccountManager.authBackoffActive()) break
            // Remember the deployment itself — the configured base may already contain a path.
            val source = "$base/api/discovery/tidal"
            val answered = runCatching {
                val builder =
                    Request.Builder()
                        .url(source)
                        .header("User-Agent", TidalAudioProvider.DOWNLOAD_USER_AGENT)
                // Present the per-app read key when the pool has gating enabled.
                if (BuildConfig.SOURCE_PROVIDER_KEY.isNotBlank()) {
                    builder.header("Authorization", "Bearer ${BuildConfig.SOURCE_PROVIDER_KEY}")
                }
                val request = builder.get().build()
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    if (
                        !PoolAccountManager.isPoolEnabled() ||
                            ArchivePoolEndpoints.isFailoverStatus(response.code)
                    )
                        return@use false
                    PoolAccountManager.notePoolAuthAnswer(response.code)
                    if (!response.isSuccessful) {
                        return@use noteUnsuccessfulDiscovery(response.code, base, source)
                    }
                    val body = response.body?.string().orEmpty()
                    // Remember only once the body arrived whole — a reset mid-200 is transport
                    // failure.
                    ArchivePoolEndpoints.remember(base)
                    parseDiscoveredInstances(body).forEach { url ->
                        TidalAudioProvider.normalizeInstanceUrl(url)?.let(discovered::add)
                    }
                    true
                }
            }
                .onFailure { it.rethrowIfAudioSourceCancelled() }
                .getOrDefault(false)
            if (answered) {
                answeredAny = true
                break
            }
        }
        return if (answeredAny) discovered.toList() else null
    }

    /**
     * Records a definitive non-2xx discovery answer. Makes 401 visible: the pool rejects
     * unauthenticated reads with HTTP 401 and an empty body, which otherwise looks identical to
     * a healthy pool with no instances contributed.
     */
    private fun noteUnsuccessfulDiscovery(
        code: Int,
        base: String,
        source: String,
    ): Boolean {
        ArchivePoolEndpoints.remember(base)
        if (code == 401) {
            Timber.tag("TidalHealth")
                .w(
                    "Pool discovery %s rejected as unauthorized (HTTP 401); " +
                        "SOURCE_PROVIDER_KEY is missing or invalid for this build.",
                    source,
                )
        }
        return true
    }

    /**
     * Parses a discovery payload. Supports:
     * - a JSON object whose values are arrays of URLs, e.g. Monochrome's `{ "api": [...],
     *   "streaming": [...] }` (streaming instances are preferred);
     * - a JSON array of URL strings or objects with a "url"/"host" field;
     * - plain newline / whitespace separated URLs.
     */
    private fun parseDiscoveredInstances(body: String): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        // JSON object with array-valued fields (Monochrome/tidal-uptime format). "streaming"
        // instances serve audio and are listed first, then "api". We deliberately ignore "qobuz"
        // (a different backend) and, like the official frontend, drop dead ".squid.wtf" hosts.
        runCatching {
            val obj = JSONObject(trimmed)
            val result = LinkedHashSet<String>()
            // Only Tidal-serving categories; qobuz is a separate backend and must not be mixed in.
            val keys = listOf("streaming", "instances", "api")
            for (key in keys) {
                val arr = obj.optJSONArray(key) ?: continue
                collectStringsFromArray(arr, result)
            }
            val filtered = result.filterNot { it.contains(".squid.wtf", ignoreCase = true) }
            if (filtered.isNotEmpty()) return filtered
        }

        // JSON array of strings or objects with a "url"/"host" field.
        runCatching {
            val result = LinkedHashSet<String>()
            collectStringsFromArray(JSONArray(trimmed), result)
            if (result.isNotEmpty()) return result.toList()
        }

        // Plain newline / whitespace separated URLs.
        return trimmed
            .split('\n', '\r', ' ', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun collectStringsFromArray(
        arr: JSONArray,
        into: MutableCollection<String>,
    ) {
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> item.takeIf { it.isNotBlank() }?.let(into::add)
                is JSONObject -> {
                    (item.optString("url").takeIf { it.isNotBlank() }
                            ?: item.optString("host").takeIf { it.isNotBlank() })
                        ?.let(into::add)
                }
            }
        }
    }
}
