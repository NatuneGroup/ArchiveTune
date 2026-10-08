/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.qobuz

import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.FlacStreamInfo
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Catalogue lookups for the **Qobuz backup** source (the community-hosted kouzu.in
 * mirror, which serves a FLAC per YouTube video id).
 */
object QobuzBackupProvider {
    /**
     * Live community mirror. The retired `mlc-ytify.kouzu.in` host stopped resolving
     * (DNS removed) and both upstreams still carry it in places, so the value is funnelled
     * through [normalizeEndpoint] before any request is built.
     */
    private const val DEFAULT_ENDPOINT = "https://mls.kouzu.in"

    /**
     * Heals a mirror URL before it is used. The app's network security policy blocks
     * cleartext, so an `http://` entry can never succeed — upgrade it instead of burning a
     * failure. References to the retired `mlc-ytify.kouzu.in` host move to `mls.kouzu.in`,
     * which serves the same API.
     */
    private fun normalizeEndpoint(raw: String): String {
        val https = if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
        return https.replace("mlc-ytify.kouzu.in", "mls.kouzu.in")
    }

    /**
     * Base URL every mirror call is built from, healed and https-checked: only an
     * `https://` result is usable, so a cleartext or retired-host endpoint can never shadow
     * the healthy default with a failure the mirror never had a chance to answer.
     */
    private val BASE_URL: String =
        normalizeEndpoint(DEFAULT_ENDPOINT).takeIf { it.startsWith("https://") } ?: DEFAULT_ENDPOINT

    private const val USER_AGENT = "ArchiveTune-Android"
    private const val SEARCH_CACHE_MS = 10 * 60 * 1000L

    /** YouTube video ids are exactly 11 chars of `[A-Za-z0-9_-]`. */
    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Ceiling on the requests one [searchCandidates] call may make. See [queryVariants]. */
    private const val MAX_QUERY_VARIANTS = 6

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    /** One track from the backup mirror's index. */
    data class Candidate(
        val videoId: String,
        val title: String,
        val artist: String?,
        /** True when the mirror advertises a FLAC mirror for this track. */
        val isLossless: Boolean,
    ) {
        /**
         * Cover art. The mirror's search response carries no artwork, but its key
         * *is* a YouTube video id, so the standard i.ytimg thumbnail always
         * exists. `hqdefault` (480x360) is the largest size guaranteed to be
         * present for every video — `maxresdefault` 404s for a large share of
         * the catalogue, which would leave the row with a placeholder icon.
         */
        val thumbnailUrl: String
            get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private data class CachedSearch(
        val candidates: List<Candidate>,
        val expiresAt: Long,
    )

    private val searchCache = ConcurrentHashMap<String, CachedSearch>()

    /**
     * Searches the backup mirror's catalogue. Blocking — call from `Dispatchers.IO`,
     * matching [QobuzAudioProvider.searchCandidates].
     *
     * Returns an empty list when the query is too short, the mirror is
     * unreachable, or it has nothing indexed for the query. Callers treat that as
     * "no results", never as a hard error.
     */
    fun searchCandidates(
        query: String,
        limit: Int = 8,
    ): List<Candidate> {
        val trimmed = query.trim()
        if (trimmed.length < 2 || limit <= 0) return emptyList()

        val cacheKey = "${trimmed.lowercase()}|$limit"
        val now = System.currentTimeMillis()
        searchCache[cacheKey]?.let { cached ->
            if (cached.expiresAt > now) return cached.candidates
            // Drop it rather than leaving it: the map was only ever read through the TTL check, so
            // expired entries accumulated for the life of the process, one per distinct query.
            searchCache.remove(cacheKey)
        }

        // Over-fetch, then rank locally: the mirror returns its matches in primary-key order, not
        // by relevance, so the first 8 rows for a broad term ("sabrina carpenter") are an
        // arbitrary 8 of however many it has.
        val fetchLimit = limit.coerceAtLeast(24).coerceAtMost(60)
        val candidates =
            queryVariants(trimmed)
                .firstNotNullOfOrNull { variant -> fetchSearch(variant, fetchLimit).takeIf { it.isNotEmpty() } }
                .orEmpty()
                .let { rows -> rankByQueryCoverage(rows, trimmed).take(limit) }

        // Cache successes only: a transient network failure should not pin an
        // empty result for the next ten minutes.
        if (candidates.isNotEmpty()) {
            searchCache[cacheKey] = CachedSearch(candidates, now + SEARCH_CACHE_MS)
        }
        return candidates
    }

    /** Query forms to try, in order, until one returns rows. */
    private fun queryVariants(query: String): List<String> {
        val words = query.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (words.size < 2) return listOf(query)
        return buildList {
            add(query)
            for (kept in words.size - 1 downTo 1) {
                add(words.take(kept).joinToString(" "))
                add(words.takeLast(kept).joinToString(" "))
            }
        }.distinct()
            .filter { it.length >= 2 }
            .take(MAX_QUERY_VARIANTS)
    }

    /**
     * Sorts [rows] by how much of the *original* query each one covers, so dropping words to get a
     * hit does not also cost the ranking: "espresso sabrina carpenter" falls back to "espresso",
     * and the row whose artist is Sabrina Carpenter still comes first.
     */
    private fun rankByQueryCoverage(
        rows: List<Candidate>,
        query: String,
    ): List<Candidate> {
        val tokens = query.lowercase().split(WHITESPACE_REGEX).filter { it.length > 1 }
        if (tokens.isEmpty()) return rows
        return rows.sortedWith(
            compareByDescending<Candidate> { candidate ->
                val haystack = "${candidate.title} ${candidate.artist.orEmpty()}".lowercase()
                tokens.count { token -> token in haystack }
            }.thenBy { candidate -> candidate.title.length },
        )
    }

    /** One `GET /api/search` call. Empty on any failure — callers treat it as "no rows". */
    private fun fetchSearch(
        query: String,
        limit: Int,
    ): List<Candidate> {
        val url =
            "$BASE_URL/api/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.toString())
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-request-source", "muzo")
                .build()

        return runCatching {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("QobuzBackup").d(
                        "search \"%s\" failed: HTTP %d",
                        query,
                        response.code,
                    )
                    return@use emptyList()
                }
                parseSearchResponse(response.body?.string().orEmpty(), limit)
            }
        }.onFailure { error ->
            Timber.tag("QobuzBackup").d(error, "search \"%s\" failed", query)
        }.getOrDefault(emptyList())
    }

    /**
     * Parses the mirror's search payload:
     * `[{"id":"J7p4bzqLvCw","name":"Blinding Lights","artists":"The Weeknd",
     *    "lossless":"4:flac","stream_sources":"m4a"}, …]`
     *
     * Rows without a usable video id or title are skipped rather than surfaced as
     * unplayable entries.
     */
    private fun parseSearchResponse(
        body: String,
        limit: Int,
    ): List<Candidate> {
        if (body.isBlank()) return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Candidate>()
        for (index in 0 until array.length()) {
            if (out.size >= limit) break
            val item = array.optJSONObject(index) ?: continue
            val videoId = item.optString("id").trim()
            if (!VIDEO_ID_REGEX.matches(videoId)) continue
            val title = item.optString("name").trim()
            if (title.isEmpty()) continue
            val artist = item.optString("artists").trim().takeIf { it.isNotEmpty() }
            // "lossless" is a tier descriptor ("4:flac") when a FLAC mirror exists
            // and absent/blank when only the lossy m4a transcode is available.
            val isLossless = item.optString("lossless").contains("flac", ignoreCase = true)
            out.add(
                Candidate(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    isLossless = isLossless,
                ),
            )
        }
        return out
    }

    /** A mirror that answered a probe with real audio bytes. */
    data class ResolvedStream(
        val uri: String,
        val mimeType: String,
        val codecs: String,
        val contentLength: Long?,
        val contentType: String,
        val isLossless: Boolean,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val durationMs: Long? = null,
    ) {
        /** Label for logs and the media-info card. */
        val label: String
            get() = if (isLossless) "Qobuz backup (lossless)" else "Qobuz backup (kouzu.in)"
    }

    /** Resolves a playable stream for [videoId] (a YouTube video id — the mirror's primary key). */
    fun resolveStream(
        videoId: String,
        client: OkHttpClient = this.client,
    ): ResolvedStream? =
        AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            resolveStreamWithinAttempt(videoId, client)
        }

    private fun resolveStreamWithinAttempt(
        videoId: String,
        client: OkHttpClient,
    ): ResolvedStream? {
        val id = videoId.trim()
        if (!VIDEO_ID_REGEX.matches(id)) {
            Timber.tag("QobuzBackup").d("skip: \"%s\" is not a YouTube video id", id)
            return null
        }

        val candidates = fetchMirrorCandidates(id, client)
        if (candidates.isEmpty()) return null

        val resolved = candidates.firstNotNullOfOrNull { candidate -> probeMirror(candidate, client) }
        if (resolved == null) {
            Timber.tag("QobuzBackup").d(
                "CDN miss for %s: no candidate mirror served audio (tried %d)",
                id,
                candidates.size,
            )
            return null
        }
        Timber.tag("QobuzBackup").i(
            "resolved %s → %s [%s%s]",
            id,
            resolved.uri.take(80),
            resolved.contentType,
            if (resolved.isLossless) ", lossless" else "",
        )
        return resolved
    }

    /** Reads the resolver envelope and returns its mirror URLs, best first. */
    private fun fetchMirrorCandidates(
        videoId: String,
        client: OkHttpClient,
    ): List<String> {
        val url =
            "$BASE_URL/api/stream"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("id", videoId)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-request-source", "muzo")
                .build()
        return runCatching {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("QobuzBackup").d("resolver miss for %s: HTTP %d", videoId, response.code)
                    return@use emptyList()
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    Timber.tag("QobuzBackup").d("resolver miss for %s: empty body", videoId)
                    return@use emptyList()
                }
                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) {
                    Timber.tag("QobuzBackup").d(
                        "resolver miss for %s: body is not JSON (first 100 chars: %s)",
                        videoId,
                        body.take(100),
                    )
                    return@use emptyList()
                }
                buildList {
                    root.optString("lossless").takeIf { it.isNotBlank() }?.let(::add)
                    root.optString("flac").takeIf { it.isNotBlank() }?.let(::add)
                    root.optString("url").takeIf { it.isNotBlank() }?.let(::add)
                    root.optString("canvas_url").takeIf { it.isNotBlank() }?.let(::add)
                    root.optString("video_url").takeIf { it.isNotBlank() }?.let(::add)
                }.distinct().also { mirrors ->
                    if (mirrors.isEmpty()) {
                        Timber.tag("QobuzBackup").d(
                            "resolver miss for %s: no url field in response (first 200 chars: %s)",
                            videoId,
                            body.take(200),
                        )
                    }
                }
            }
        }.onFailure { error ->
            error.rethrowIfAudioSourceCancelled()
            Timber.tag("QobuzBackup").d(error, "resolver call failed for %s", videoId)
        }.getOrDefault(emptyList())
    }

    /**
     * Probes one mirror with a ranged GET and describes it, or returns null when it does not serve
     * audio for this track.
     */
    private fun probeMirror(
        url: String,
        client: OkHttpClient,
    ): ResolvedStream? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("x-request-source", "muzo")
                    .header("Range", "bytes=0-${FlacStreamInfo.REQUIRED_BYTES - 1}")
                    .build()
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (!response.isSuccessful) return@use null
                val headerBytes = runCatching { response.body?.bytes() }.getOrNull()
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                // A JSON body here is the CDN's {"detail":"Not Found"} envelope for a mirror that
                // has not been populated for this track yet.
                if (contentType.contains("json") || contentType.contains("html")) return@use null
                if (contentType.isNotBlank() &&
                    !contentType.startsWith("audio/") &&
                    !contentType.startsWith("video/") &&
                    !contentType.contains("octet-stream")
                ) {
                    return@use null
                }
                // Prefer the Content-Range total ("bytes 0-41/40802970") — Content-Length on a 206
                // is just the probed bytes.
                val totalLength =
                    response
                        .header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                        ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code != 206 }
                // Trust the bytes over the labels: a mirror that serves FLAC is FLAC even when the
                // CDN mislabels the Content-Type, and the header is already in hand.
                val streamInfo = headerBytes?.let(FlacStreamInfo::parse)
                val isFlac = streamInfo != null || contentType.contains("flac") || url.contains("/lossless/")
                ResolvedStream(
                    uri = url,
                    mimeType = if (isFlac) "audio/flac" else "audio/mp4",
                    codecs = if (isFlac) "flac" else "mp4a.40.2",
                    contentLength = totalLength?.takeIf { it > 0 },
                    contentType = contentType.ifBlank { "unknown" },
                    isLossless = isFlac,
                    sampleRate = streamInfo?.sampleRate,
                    bitDepth = streamInfo?.bitDepth,
                    durationMs = streamInfo?.durationMs,
                )
            }
        }.onFailure { error ->
            error.rethrowIfAudioSourceCancelled()
            Timber.tag("QobuzBackup").d(error, "mirror probe failed for %s", url.take(80))
        }.getOrNull()
}
