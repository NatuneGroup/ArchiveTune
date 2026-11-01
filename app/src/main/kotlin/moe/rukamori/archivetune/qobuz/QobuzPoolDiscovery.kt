/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.qobuz

import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.utils.ArchivePoolEndpoints
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.PoolEndpoints
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Source Pool discovery for Qobuz: which community proxy instances the pool currently lists
 * (the `{ streaming, instances, api }` feed shape).
 *
 * The feed is a paid shared resource, so this object owns the request-shaping rules for it:
 * answers (empty ones included) are cached for [CACHE_MS], concurrent callers share one fetch via
 * a single-flight lock, a pool 401 backs the whole feature off through PoolAuthBackoff, and the
 * request itself fails over across the configured deployment and the fallback base the way
 * [PoolEndpoints] does — remembering a working deployment once a definitive answer lands.
 *
 * A dead pool never clears the last good list: callers keep serving community instances that are
 * still valid on their own.
 */
internal object QobuzPoolDiscovery {

    /** Proxy base URLs that came from the pool feed, kept so they can be stripped again. */
    var urls: List<String> = emptyList()
        private set

    /** The last pool discovery answer (empty counts) and when it stops being fresh. */
    @Volatile
    private var discoveryCache: List<String> = emptyList()

    @Volatile
    private var expiresAtMs = 0L

    private val lock = Any()

    private const val CACHE_MS = 30L * 60 * 1000L

    /** Drops every pool-derived answer; the caller strips [urls] from its own list. */
    fun clear() {
        urls = emptyList()
        discoveryCache = emptyList()
        expiresAtMs = 0L
    }

    /**
     * Returns newly discovered valid base URLs (may be empty). Never throws; on failure it
     * returns the previous list. Blocking network I/O, so call off the main thread.
     */
    fun discover(client: OkHttpClient): List<String> {
        if (!PoolAccountManager.isPoolEnabled()) {
            QobuzAudioProvider.clearPoolDiscovery()
            return emptyList()
        }
        // Auth backoff and the freshness window both keep stale answers rather than billing
        // another feed read — discovered URLs are community instances, usable while the pool rests.
        if (PoolAccountManager.authBackoffActive()) return discoveryCache
        if (System.currentTimeMillis() < expiresAtMs) return discoveryCache
        // Single-flight: resolve paths call this per track, and concurrent callers used to each
        // spend a full feed fetch — the expiry re-check inside the lock hands followers the
        // copy the first caller just fetched.
        synchronized(lock) {
            if (!PoolAccountManager.isPoolEnabled()) {
                QobuzAudioProvider.clearPoolDiscovery()
                return emptyList()
            }
            if (PoolAccountManager.authBackoffActive()) return discoveryCache
            if (System.currentTimeMillis() < expiresAtMs) return discoveryCache
            val discovered = LinkedHashSet<String>()
            var answeredAny = false
            for (base in PoolEndpoints.bases()) {
                if (!PoolAccountManager.isPoolEnabled() || PoolAccountManager.authBackoffActive()) break
                // Remember the deployment itself — the configured base may already contain a path.
                val source = "$base/api/discovery/qobuz"
                val answered =
                    runCatching {
                        val builder =
                            Request
                                .Builder()
                                .url(source)
                                .header("User-Agent", QobuzAudioProvider.USER_AGENT)
                        // Present the per-app read key when the pool has gating enabled.
                        if (BuildConfig.SOURCE_PROVIDER_KEY.isNotBlank()) {
                            builder.header("Authorization", "Bearer ${BuildConfig.SOURCE_PROVIDER_KEY}")
                        }
                        val request = builder.get().build()
                        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                            if (!PoolAccountManager.isPoolEnabled() || ArchivePoolEndpoints.isFailoverStatus(response.code)) return@use false
                            PoolAccountManager.notePoolAuthAnswer(response.code)
                            if (!response.isSuccessful) {
                                ArchivePoolEndpoints.remember(base)
                                // A rejected read and a healthy pool with nothing contributed both end up
                                // as zero instances here, so say which one happened — this is the whole
                                // difference between "no Qobuz accounts" and "this build's key is wrong".
                                if (response.code == 401) {
                                    Timber
                                        .tag("QobuzDiscovery")
                                        .w(
                                            "Pool discovery %s rejected as unauthorized (HTTP 401); " +
                                                "SOURCE_PROVIDER_KEY is missing or invalid for this build.",
                                            source,
                                        )
                                }
                                return@use true
                            }
                            val body = response.body?.string().orEmpty()
                            // Remember only once the body arrived whole — a reset mid-200 is transport failure.
                            ArchivePoolEndpoints.remember(base)
                            if (body.isBlank()) return@use true
                            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@use true
                            for (key in listOf("streaming", "instances", "api")) {
                                val arr = obj.optJSONArray(key) ?: continue
                                for (i in 0 until arr.length()) {
                                    when (val item = arr.opt(i)) {
                                        is String -> QobuzAudioProvider.normalizeInstanceUrl(item)?.let(discovered::add)
                                        is JSONObject ->
                                            (item.optString("url").takeIf { it.isNotBlank() }
                                                ?: item.optString("host").takeIf { it.isNotBlank() })
                                                ?.let { QobuzAudioProvider.normalizeInstanceUrl(it)?.let(discovered::add) }
                                    }
                                }
                            }
                            true
                        }
                    }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrDefault(false)
                if (answered) {
                    answeredAny = true
                    break
                }
            }
            if (!PoolAccountManager.isPoolEnabled()) {
                QobuzAudioProvider.clearPoolDiscovery()
                return emptyList()
            }
            // No definitive answer at all keeps the previous list; either way the attempt stamps
            // the freshness window so a dead pool is not re-polled on every resolve.
            if (answeredAny) {
                val result = discovered.toList()
                discoveryCache = result
                if (result.isNotEmpty()) urls = result
            }
            expiresAtMs = System.currentTimeMillis() + CACHE_MS
            return discoveryCache
        }
    }
}
