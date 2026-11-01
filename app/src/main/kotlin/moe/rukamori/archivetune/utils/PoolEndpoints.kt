/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * The ordered ArchivePool deployments one pool request is tried against while the service moves off
 * Vercel onto Cloudflare Workers: the build-configured deployment first, then the public
 * archivepool.vercel.app origin as the fallback. A deployment that produced a definitive API answer
 * is remembered for about [REMEMBER_NS] so a dead primary is not paid for on every call.
 *
 * A request moves to the next endpoint on a transport failure — connection refused, DNS failure,
 * timeout — or a host-level answer ([isFailoverStatus]: HTTP 402 or 5xx). API-level answers like
 * 400, 401, 403, 404 or 429 are definitive and stop the walk. POST /api/report is different again:
 * a transport failure means the report may already have reached the server, so only an explicit
 * 402 or 5xx answer advances it — a report is never delivered twice.
 */
internal object ArchivePoolEndpoints {

    /** The deployment every build falls back to when nothing better is configured or reachable. */
    const val FALLBACK_BASE_URL = "https://archivepool.vercel.app"

    /** How long the deployment that last produced a definitive answer is tried first. */
    private const val REMEMBER_NS = 10L * 60 * 1_000_000_000L

    /** Internal so tests can backdate or clear the memory; production writes go through [remember]. */
    @Volatile
    internal var lastGood: Pair<String, Long>? = null

    /**
     * The configured deployment normalized: trimmed, without a trailing slash, https only. A blank
     * or non-https value answers the public fallback, matching every host's unset-means-default rule.
     */
    fun primary(configured: String?): String =
        configured?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") } ?: FALLBACK_BASE_URL

    /**
     * The deduplicated endpoint order for one request: the remembered deployment first while it is
     * still fresh, then the configured primary, then the public fallback.
     */
    fun candidates(configured: String?): List<String> {
        val ordered = LinkedHashSet<String>(3)
        lastGood?.takeIf { System.nanoTime() - it.second < REMEMBER_NS }?.let { ordered += it.first }
        ordered += primary(configured)
        ordered += FALLBACK_BASE_URL
        return ordered.toList()
    }

    /**
     * Remembers the deployment that produced a definitive answer, so the next request tries it
     * first. Only the answering host is stored; failover statuses never reach this.
     */
    fun remember(baseUrl: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) return
        lastGood = normalized to System.nanoTime()
    }

    /**
     * True when [status] means the deployment itself failed rather than the API: 402 (the host is
     * down for billing) or any 5xx. API-level answers — 400/401/403/404/429 among them — are
     * definitive. For reports the same statuses are safe failover answers: an explicit 402 or 5xx
     * proves the request was never processed, so re-sending it cannot deliver twice.
     */
    fun isFailoverStatus(status: Int): Boolean = status == 402 || status >= 500

    /**
     * Runs [call] against each candidate endpoint until one answers definitively; the remembered
     * deployment leads the walk while fresh. [call] answers the (status, body) pair or null on a
     * transport failure. A failover status never reaches [remember]. The last answer is returned
     * when every endpoint fails; the caller keeps distinguishing "unreachable" (null) from "the
     * last host said no" (a status). For [report] a null stops the walk: the request may already
     * have reached the server, and only an explicit 402 or 5xx proves it did not.
     */
    suspend fun acrossEndpoints(
        configured: String?,
        report: Boolean = false,
        call: suspend (baseUrl: String) -> Pair<Int, String>?,
    ): Pair<Int, String>? {
        var last: Pair<Int, String>? = null
        for (endpoint in candidates(configured)) {
            val result = call(endpoint)
            if (result == null) {
                if (report) break
                continue
            }
            if (!isFailoverStatus(result.first)) {
                remember(endpoint)
                return result
            }
            last = result
        }
        return last
    }
}

/**
 * OkHttp transport for the ordered ArchivePool deployments [ArchivePoolEndpoints] computes: the
 * build-configured deployment first, the public vercel.app fallback after it, and the deployment
 * that last produced a definitive API answer first while that memory is fresh (~10 minutes). A call
 * moves to the next endpoint on a transport failure (connection refused, DNS failure, timeout) or a
 * host-level status (402/5xx); API-level answers like 400/401/403/404/429 are definitive and stop
 * the walk. A report advances only on an explicit 402/5xx answer: a transport failure may mean the
 * report already reached the server, so a transport failure stops its walk and a report is never
 * delivered twice. Nothing here logs a token.
 */
internal object PoolEndpoints {

    /**
     * The build-configured pool deployment: the dedicated ARCHIVEPOOL_BASE_URL build value when
     * set and https, else the legacy SOURCE_PROVIDER_URL build value, else the public default.
     */
    fun configuredBaseUrl(): String =
        ArchivePoolEndpoints.primary(
            listOf(BuildConfig.ARCHIVEPOOL_BASE_URL, BuildConfig.SOURCE_PROVIDER_URL)
                .firstOrNull { it.trim().startsWith("https://") },
        )

    /** The deduplicated deployments one request walks, remembered endpoint first when fresh. */
    fun bases(): List<String> = ArchivePoolEndpoints.candidates(configuredBaseUrl())

    /** GETs [path] against each candidate deployment until one answers definitively. */
    suspend fun get(
        client: OkHttpClient,
        path: String,
        readKey: String,
    ): Pair<Int, String>? = send(client, path, readKey, body = null)

    /**
     * POSTs [body] to a report endpoint (/api/report) against each candidate deployment. Only an
     * explicit 402/5xx answer moves the report to the next endpoint — that proves it was never
     * processed; a transport failure or a 2xx/4xx answer stops the walk so it is never sent twice.
     */
    suspend fun postReport(
        client: OkHttpClient,
        path: String,
        readKey: String,
        body: String,
    ): Pair<Int, String>? = send(client, path, readKey, body)

    private suspend fun send(
        client: OkHttpClient,
        path: String,
        readKey: String,
        body: String?,
    ): Pair<Int, String>? =
        try {
            ArchivePoolEndpoints.acrossEndpoints(configuredBaseUrl(), report = body != null) { base ->
                // A dead coroutine, a switched-off Playback Pool or an open 401 auth backoff
                // must stop the walk before the next endpoint is tried, not after the request
                // is already on the wire.
                coroutineContext.ensureActive()
                if (!PoolAccountManager.isPoolEnabled() || PoolAccountManager.authBackoffActive()) {
                    throw PoolUnavailableException()
                }
                suspendCancellableCoroutine<Pair<Int, String>?> { cont ->
                    val builder =
                        Request
                            .Builder()
                            .url("$base$path")
                            .header("User-Agent", "ArchiveTune-Android")
                            // v2 of the pool protocol: sensitive fields are encrypted with a key derived
                            // from the read key; older servers ignore the header.
                            .header("X-Pool-Client", "v2")
                    if (readKey.isNotBlank()) {
                        builder.header("Authorization", "Bearer $readKey")
                    }
                    if (body != null) {
                        builder.post(body.toRequestBody(JSON_MEDIA))
                    }
                    val call = client.newCall(builder.build())
                    // execute() would ignore coroutine cancellation for the whole socket wait;
                    // enqueue plus cancel() frees it and lets CancellationException propagate.
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                // null = transport failure: the caller walks the next endpoint
                                // (or stops, for a report). A cancelled call never resumes here —
                                // the continuation is already dead and throws on its own.
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val result =
                                    response.use {
                                        // Every definitive answer feeds the shared auth backoff:
                                        // 401 doubles the quiet window, a 2xx clears it.
                                        PoolAccountManager.notePoolAuthAnswer(it.code)
                                        runCatching {
                                            it.code to
                                                if (it.isSuccessful) it.body?.string().orEmpty() else ""
                                        }
                                    }
                                if (cont.isActive) cont.resumeWith(result)
                            }
                        },
                    )
                }
            }
        } catch (e: PoolUnavailableException) {
            null
        }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
}

/** Thrown inside an endpoint walk when the Playback Pool stops being usable mid-request. */
private class PoolUnavailableException : Exception()
