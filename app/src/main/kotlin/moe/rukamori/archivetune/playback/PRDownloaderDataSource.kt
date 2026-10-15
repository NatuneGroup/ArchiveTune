/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import com.downloader.Error
import com.downloader.OnDownloadListener
import com.downloader.PRDownloader
import com.downloader.request.DownloadRequest
import moe.rukamori.archivetune.utils.StreamClientUtils
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import okhttp3.Call
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import timber.log.Timber
import moe.rukamori.archivetune.utils.withAppProxy

/**
 * A Media3 [DataSource] that delegates the actual HTTP fetching to
 * [PRDownloader](https://github.com/amitshekhariitbhu/PRDownloader) — a lightweight (~45 KB) file
 * download library with pause/resume, retry, and progress callbacks.
 */
internal class PRDownloaderDataSource private constructor(
    private val context: Context,
    private val userAgent: String,
) : BaseDataSource(true) {

    private class RequestState(val directory: File) {
        val latch = CountDownLatch(1)
        val error = AtomicReference<Error?>(null)
        @Volatile var cancelled = false
        var downloadId = -1
        var probe: Call? = null
        var source: FileDataSource? = null
        var bytesRemaining = 0L
    }

    private val requestLock = Any()
    private var active: RequestState? = null

    private fun checkCurrent(state: RequestState) {
        if (state.cancelled || active !== state) throw IOException("Download request closed")
    }

    private fun cleanup(state: RequestState) {
        // This directory belongs only to this request, including PRDownloader's partial file.
        state.directory.listFiles()?.forEach { it.delete() }
        state.directory.delete()
    }

    private fun release(state: RequestState) {
        synchronized(requestLock) {
            state.cancelled = true
            if (active === state) active = null
            state.latch.countDown()
            state.probe?.cancel()
            state.probe = null
            if (state.downloadId != -1) runCatching { PRDownloader.cancel(state.downloadId) }
            state.downloadId = -1
            state.source?.let { runCatching { it.close() } }
            state.source = null
            state.bytesRemaining = 0L
        }
        cleanup(state)
    }

    /** Shared OkHttp client for HEAD requests — created lazily on first use. */
    private val headClient: OkHttpClient by lazy {
        OkHttpClient.Builder().withAppProxy()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 30_000, TimeUnit.MILLISECONDS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        // PRDownloader is initialized once at app start (see App.kt).
        // If it isn't initialized, we fail fast — there's no graceful
        // degradation possible without a fetcher.
        val tempDir = File(context.cacheDir, "prd_tmp").apply { mkdirs() }
        val requestDir = File(tempDir, "request_${UUID.randomUUID()}")
        if (!requestDir.mkdir()) throw IOException("Cannot create download staging directory")
        val safeName = "content"
        val target = File(requestDir, safeName)
        val state = RequestState(requestDir)
        synchronized(requestLock) {
            if (active != null) {
                cleanup(state)
                throw IOException("Data source already open")
            }
            active = state
        }
        try {
            // Build the download request with optional headers.
            // PRDownloader.download() returns a DownloadRequestBuilder — headers
            // and user-agent must be set on the builder BEFORE calling .build(),
            // which converts it into a DownloadRequest (which only supports
            // progress/pause/cancel listeners + start()).
            val requestBuilder = PRDownloader.download(url, requestDir.absolutePath, safeName)

            // YouTube's googlevideo CDN enforces strict User-Agent / Origin / Referer
            // matching against the `c` (client) query parameter embedded in the
            // stream URL. If the UA doesn't match what the client expects (e.g.
            // "ArchiveTune/1.2.3" sent for a `c=WEB_REMIX` URL that expects a
            // Firefox UA), googlevideo returns HTTP 403 and every download fails.
            //
            // Resolve the correct UA + Origin + Referer from the URL's `c` param
            // via StreamClientUtils (the same path the player's OkHttp interceptor
            // uses). For non-YouTube URLs, fall back to the configured default UA.
            val youTubeMediaProfile = runCatching {
                StreamClientUtils.resolveRequestProfile(url)
            }.getOrNull()
            val resolvedUserAgent = youTubeMediaProfile?.userAgent?.takeIf(String::isNotBlank)
                ?: userAgent
            if (resolvedUserAgent.isNotBlank()) requestBuilder.setUserAgent(resolvedUserAgent)
            if (youTubeMediaProfile != null) {
                youTubeMediaProfile.origin?.takeIf(String::isNotBlank)?.let {
                    requestBuilder.setHeader("Origin", it)
                }
                youTubeMediaProfile.referer?.takeIf(String::isNotBlank)?.let {
                    requestBuilder.setHeader("Referer", it)
                }
                // YouTube media endpoints reject transparent gzip on audio/mp4
                // streams — without this, PRDownloader's OkHttp client may
                // advertise gzip and the response comes back compressed, which
                // both corrupts the cached bytes and triggers
                // ParserException("Multiple Segment elements not supported")
                // when the player later tries to decode the gzipped payload as
                // an MP4 container.
                requestBuilder.setHeader("Accept-Encoding", "identity")
                requestBuilder.setHeader("Connection", "keep-alive")
            }
            dataSpec.httpRequestHeaders.forEach { (k, v) ->
                // PRDownloader doesn't accept "Range" via setHeader — it
                // manages its own range requests internally for resume.
                // We drop any caller-supplied Range header to avoid conflict.
                // We also drop User-Agent / Origin / Referer / Accept-Encoding
                // because we just set them from the stream-client profile.
                val lower = k.lowercase()
                if (lower != "range" &&
                    lower != "user-agent" &&
                    lower != "origin" &&
                    lower != "referer" &&
                    lower != "accept-encoding" &&
                    lower != "x-expected-content-length"
                ) {
                    requestBuilder.setHeader(k, v)
                }
            }
            val builder: DownloadRequest = requestBuilder.build()

            builder.setOnCancelListener {
                state.cancelled = true
                state.latch.countDown()
                cleanup(state)
            }
            synchronized(requestLock) {
                checkCurrent(state)
                state.downloadId = builder.start(object : OnDownloadListener {
                    override fun onDownloadComplete() {
                        state.latch.countDown()
                        if (state.cancelled) cleanup(state)
                    }

                    override fun onError(error: Error) {
                        state.error.set(error)
                        state.latch.countDown()
                        if (state.cancelled) cleanup(state)
                    }
                })
            }

            // Block until PRDownloader finishes or the timeout expires.
            // 30 min is generous — a 150 MB FLAC on a 10 Mbps link is ~2 min,
            // and we'd rather let a slow download finish than fail and force
            // a restart.
            if (!state.latch.await(DOWNLOAD_WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw IOException("PRDownloader timed out after $DOWNLOAD_WAIT_TIMEOUT_MINUTES min")
            }
            synchronized(requestLock) {
                checkCurrent(state)
                state.downloadId = -1
            }

            state.error.get()?.let { err ->
                runCatching { target.delete() }
                val msg = buildString {
                    append("PRDownloader failed")
                    if (err.isConnectionError) append(" (connection error)")
                    if (err.isServerError) append(" (server error)")
                    if (err.responseCode > 0) append(" (HTTP ${err.responseCode})")
                }
                Timber.tag("PlaybackDiagnostics").w(
                    "Download request failed connectionError=%s serverError=%s responseCode=%d",
                    err.isConnectionError,
                    err.isServerError,
                    err.responseCode,
                )
                throw IOException(msg)
            }

            if (!target.exists() || target.length() == 0L) {
                runCatching { target.delete() }
                throw IOException("PRDownloader reported success but the downloaded file is missing or empty")
            }

            // ── Real integrity verification ── PRDownloader 1.0.2 has a known issue where
            // `onDownloadComplete()` can fire on a partial file when the upstream connection drops
            // mid-stream (especially on chunked-transfer CDNs that don't send Content-Length).
            val expectedLength = resolveExpectedContentLength(url, dataSpec, state)
            val actualLength = target.length()
            if (isDownloadedContentTruncated(actualLength, expectedLength)) {
                Timber.tag("PlaybackDiagnostics").w(
                    "Download incomplete expectedBytes=%d actualBytes=%d",
                    expectedLength,
                    actualLength,
                )
                runCatching { target.delete() }
                throw IOException("PRDownloader returned an incomplete response")
            }

            // Build a child DataSpec pointing at the local file, preserving
            // position / length / key. FileDataSource honors position to
            // seek into the file (for range requests).
            synchronized(requestLock) {
                checkCurrent(state)
                val fileSpec = dataSpec.withUri(Uri.fromFile(target))
                val fs = FileDataSource()
                state.source = fs
                val reportedLength = fs.open(fileSpec)
                val totalRemaining = if (reportedLength < 0) target.length() - dataSpec.position else reportedLength
                state.bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(dataSpec.length, totalRemaining)
                } else {
                    totalRemaining
                }
                transferStarted(dataSpec)
                return state.bytesRemaining
            }
        } catch (e: InterruptedException) {
            release(state)
            Thread.currentThread().interrupt()
            throw IOException("Download wait interrupted", e)
        } catch (t: Throwable) {
            release(state)
            throw t
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(requestLock) {
        if (length == 0) return@synchronized 0
        val state = active ?: return@synchronized C.RESULT_END_OF_INPUT
        val fs = state.source ?: return@synchronized C.RESULT_END_OF_INPUT
        if (state.bytesRemaining <= 0) return@synchronized C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), state.bytesRemaining).toInt()
        val read = fs.read(buffer, offset, toRead)
        if (read > 0) {
            state.bytesRemaining -= read
            bytesTransferred(read)
        }
        read
    }

    override fun getUri(): Uri? = synchronized(requestLock) { active?.source?.uri }

    override fun close() {
        val state = synchronized(requestLock) { active } ?: return
        release(state)
    }

    /**
     * Resolves the expected content length for the download, used to verify that PRDownloader
     * didn't silently truncate the file.
     */
    private fun resolveExpectedContentLength(url: String, dataSpec: DataSpec, state: RequestState): Long {
        val expectedLengthHeader =
            dataSpec.httpRequestHeaders.entries
                .firstOrNull { it.key.equals("X-Expected-Content-Length", ignoreCase = true) }
                ?.value
        val explicitLength = resolveExpectedDownloadedLength(expectedLengthHeader, null, null, null, null)
        if (explicitLength > 0L) return explicitLength

        val youTubeMediaProfile = runCatching {
            StreamClientUtils.resolveRequestProfile(url)
        }.getOrNull()
        val resolvedUserAgent = youTubeMediaProfile?.userAgent?.takeIf(String::isNotBlank)
            ?: userAgent

        fun probeRequest(rangeProbe: Boolean): Request {
            val builder = Request.Builder().url(url)
            if (rangeProbe) {
                builder.get().header("Range", "bytes=0-0")
            } else {
                builder.head()
            }
            dataSpec.httpRequestHeaders.forEach { (name, value) ->
                val lower = name.lowercase()
                if (lower != "range" &&
                    lower != "user-agent" &&
                    lower != "origin" &&
                    lower != "referer" &&
                    lower != "accept-encoding" &&
                    lower != "x-expected-content-length"
                ) {
                    builder.header(name, value)
                }
            }
            builder.header("User-Agent", resolvedUserAgent)
            builder.header("Accept-Encoding", "identity")
            youTubeMediaProfile?.origin?.takeIf(String::isNotBlank)?.let {
                builder.header("Origin", it)
            }
            youTubeMediaProfile?.referer?.takeIf(String::isNotBlank)?.let {
                builder.header("Referer", it)
            }
            return builder.build()
        }

        val headLength =
            runCatching {
                probe(state, probeRequest(rangeProbe = false)).use { response ->
                    response.header("Content-Length").takeIf { response.code == 200 }
                }
            }.getOrNull()
        val knownHeadLength = resolveExpectedDownloadedLength(expectedLengthHeader, headLength, null, null, null)
        if (knownHeadLength > 0L) return knownHeadLength

        return runCatching {
            probe(state, probeRequest(rangeProbe = true)).use { response ->
                resolveExpectedDownloadedLength(
                    explicitExpectedLength = expectedLengthHeader,
                    headContentLength = headLength,
                    rangeResponseCode = response.code,
                    rangeContentRange = response.header("Content-Range"),
                    rangeContentLength = response.header("Content-Length"),
                )
            }
        }.getOrDefault(knownHeadLength)
    }

    private fun probe(state: RequestState, request: Request): okhttp3.Response {
        val call = headClient.newCall(request)
        synchronized(requestLock) {
            checkCurrent(state)
            state.probe = call
        }
        return call.execute()
    }

    /**
     * Factory for [PRDownloaderDataSource]. Each [createDataSource] call
     * returns an independent instance — PRDownloader itself is a process-
     * wide singleton initialized in [App.kt], so the factory is cheap
     * to construct.
     */
    class Factory(
        private val context: Context,
        private val userAgent: String = DEFAULT_USER_AGENT,
    ) : DataSource.Factory {
        override fun createDataSource(): PRDownloaderDataSource =
            PRDownloaderDataSource(context, userAgent)
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "ArchiveTune"
        private const val DOWNLOAD_WAIT_TIMEOUT_MINUTES = 30L
    }
}
