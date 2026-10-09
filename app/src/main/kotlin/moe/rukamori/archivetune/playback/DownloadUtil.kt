/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.SongSourceOverride
import moe.rukamori.archivetune.audiosource.SongSourceQobuzBackupVideoId
import moe.rukamori.archivetune.audiosource.SongSourceQobuzTrackId
import moe.rukamori.archivetune.audiosource.TitleMatch
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.constants.DownloadSourceOrderKey
import moe.rukamori.archivetune.constants.DeezerAudioQuality
import moe.rukamori.archivetune.constants.DeezerAudioQualityKey
import moe.rukamori.archivetune.constants.QobuzAudioQuality
import moe.rukamori.archivetune.constants.QobuzAudioQualityKey
import moe.rukamori.archivetune.constants.SaavnAudioQuality
import moe.rukamori.archivetune.constants.SaavnAudioQualityKey
import moe.rukamori.archivetune.constants.SongSourceOverrideKey
import moe.rukamori.archivetune.constants.SongSourceQobuzBackupVideoIdKey
import moe.rukamori.archivetune.constants.SongSourceQobuzTrackIdKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.TidalAudioQualityKey
import moe.rukamori.archivetune.constants.toFormatId
import moe.rukamori.archivetune.constants.toFormatName
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.SongArtistMap
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.deezer.DeezerCrypto
import moe.rukamori.archivetune.deezer.DeezerDecryptingDataSource
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.playback.stream.codecsFromMimeType
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.preference
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: Cache,
        @PlayerCache val playerCache: Cache,
    ) {
        /** Private hold on the injected [Context]. */
        private val appContext: Context = context

        private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        // Reads the user's drag-and-drop download source priority list (CSV of DownloadSource
        // names). Falls back to DEFAULT_ORDER when blank. The legacy single-pick DownloadSourceKey
        // this path used to consult is no longer read: the settings UI only ever writes this order,
        // so the order list is the one source of truth for which sources a download may use.
        private val downloadSourceOrderCsv by preference(context, DownloadSourceOrderKey, "")
        private val qobuzAudioQuality by enumPreference(context, QobuzAudioQualityKey, QobuzAudioQuality.FLAC)
        private val tidalAudioQuality by enumPreference(context, TidalAudioQualityKey, TidalAudioQuality.FLAC)
        private val saavnAudioQuality by enumPreference(context, SaavnAudioQualityKey, SaavnAudioQuality.QUALITY_320)
        private val deezerAudioQuality by enumPreference(context, DeezerAudioQualityKey, DeezerAudioQuality.FLAC)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()

        /** Download worker pool. */
        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)

        /**
         * OkHttp client used for media HTTP requests.
         *
         * Used directly by [moe.rukamori.archivetune.innertube.YouTube] for
         * stream URL resolution and indirectly by [PRDownloaderDataSource]
         * (PRDownloader has its own internal OkHttp client, configured via
         * [com.downloader.PRDownloaderConfig] in [App.kt]).
         */
        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS) // no overall cap — let large FLAC files download to completion
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        // PRDownloader (used by PRDownloaderDataSource) manages
                        // its own internal OkHttp dispatcher — leave these caps
                        // generous so the player's own streaming requests
                        // (which share this client) don't get starved when
                        // multiple downloads are in flight.
                        maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                        maxRequestsPerHost = MAX_DOWNLOAD_HTTP_REQUESTS_PER_HOST
                    },
                ).connectionPool(
                    ConnectionPool(
                        MAX_IDLE_DOWNLOAD_CONNECTIONS,
                        DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES,
                    ),
                ).protocols(
                    // Force HTTP/2 over HTTP/1.1 when available — HTTP/2 multiplexes
                    // many requests over a single TCP connection, eliminating the
                    // per-request TCP+TLS handshake cost (~100-300ms each).
                    listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1),
                ).addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) {
                        // For non-YouTube hosts (Qobuz / Tidal / iTunes / Deezer), hint
                        // that we want a binary stream and disable any transparent
                        // gzip/br compression — compressed audio is already efficiently
                        // encoded and re-compressing it just wastes CPU.
                        val patched =
                            request
                                .newBuilder()
                                .header("Accept-Encoding", "identity")
                                .header("Connection", "keep-alive")
                        // The Qobuz backup mirror rate-limits aggressively unless every request
                        // carries this header — including the byte fetch, not just the resolve.
                        // MusicService's client does the same for the playback path; without it
                        // here the pre-warm fetch for a Qobuz-backup download gets throttled.
                        if (host.endsWith("kouzu.in") && request.header("x-request-source").isNullOrEmpty()) {
                            patched.header("x-request-source", "muzo")
                        }
                        return@addInterceptor chain.proceed(patched.build())
                    }

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    chain.proceed(
                        StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                }.build()
        }

        /**
         * Pre-warms DNS + TLS connections to the most common download hosts so
         * the first download of a session doesn't pay the ~300-800ms
         * DNS+TCP+TLS handshake cost. Safe to call on a background coroutine
         * at app start; failures are silently swallowed (it's only an
         * optimization, not a hard requirement).
         */
        fun prewarmDownloadConnections() {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                for (host in PREWARM_HOSTS) {
                    runCatching {
                        val request = Request.Builder()
                            .url("https://$host/")
                            .head()
                            .build()
                        mediaOkHttpClient.newCall(request).execute().use { /* discard */ }
                    }
                }
            }
        }

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        /** PRDownloader-backed HTTP data source factory. */
        private val okHttpDataSourceFactory =
            PRDownloaderDataSource.Factory(context)

        /** Read-only view of [playerCache] used as an inner upstream of the download chain. */
        private val playerCacheDownloadUpstreamFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setUpstreamDataSourceFactory(okHttpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        private val youtubeDataSourceFactory =
            ResolvingDataSource.Factory(
                CacheDataSource
                    .Factory()
                    .setCache(downloadCache)
                    .setCacheKeyFactory(DownloadRequestCacheKeyFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .setUpstreamDataSourceFactory(playerCacheDownloadUpstreamFactory)
                    .setCacheWriteDataSinkFactory(
                        // fragmentSize = DOWNLOAD_FRAGMENT_SIZE means a
                        // 40 MB FLAC is stored as 2 fragments instead of the
                        // default 8 (5 MB). Fewer fragments = fewer open file
                        // handles + fewer fsync syscalls = higher write
                        // throughput. The bufferSize controls the in-memory
                        // write buffer (4 MB is plenty — CacheDataSink flushes
                        // to disk when it fills).
                        CacheDataSink
                            .Factory()
                            .setCache(downloadCache)
                            .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                            .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE),
                    ),
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                // Don't short-circuit on a 1-byte cache — Media3 opens downloads
                // with `dataSpec.length == C.LENGTH_UNSET`, and clamping that
                // to `1` here means even a single cached byte (e.g. from a
                // one-second playback preview) makes the resolver return the
                // bare mediaId dataSpec, which downstream tries to open as
                // `Uri.parse("dJth8oW7CAQ")` (a bare YouTube ID, not a URL) →
                // Malformed URL → STATE_FAILED.
                // Instead, sum the actual cached spans and compare against
                // FormatEntity.contentLength — only treat the cache as
                // complete when we actually have the full file.
                val expectedLength = database.getSongByIdBlocking(mediaId)?.format?.contentLength ?: 0L
                if (expectedLength > 0L) {
                    val cachedBytes = runCatching {
                        playerCache.getCachedSpans(mediaId).sumOf { it.length }
                    }.getOrDefault(0L)
                    if (cachedBytes >= expectedLength) {
                        return@Factory dataSpec
                    }
                }
                // Same check for source-prefixed cache keys — only return
                // when we actually have the full file, not on partial cache.
                for (sourcePrefix in DownloadSourceConfig.CACHE_KEY_PREFIXES) {
                    val sourceKey = "$sourcePrefix$mediaId"
                    val sourceExpected = expectedLength // same expected length across sources
                    if (sourceExpected > 0L) {
                        val cachedBytes = runCatching {
                            playerCache.getCachedSpans(sourceKey).sumOf { it.length }
                        }.getOrDefault(0L)
                        if (cachedBytes >= sourceExpected) {
                            return@Factory dataSpec.buildUpon().setKey(sourceKey).build()
                        }
                    }
                }
                // Fallback: if we have no expected length info at all, do a
                // bounded check that requires the WHOLE dataSpec.length to be
                // cached (only meaningful when dataSpec.length is set).
                if (dataSpec.length >= 0 && playerCache.isCached(mediaId, dataSpec.position, dataSpec.length)) {
                    return@Factory dataSpec
                }
                // No usable cache — proceed to resolve a fresh URL.
                val lowDataModeActive = context.isLowDataModeActive()
                if (!lowDataModeActive) {
                    resolvePreferredDownloadDataSpec(dataSpec, mediaId)?.let { return@Factory it }
                }
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
                val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                songUrlCache[streamCacheKey]
                    ?.takeIf {
                        it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    }?.let {
                        return@Factory dataSpec.withUri(it.url.toUri())
                    }
                val playbackData =
                    runBlocking(Dispatchers.IO) {
                        context.retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForDownload(
                                mediaId,
                                audioQuality = requestedAudioQuality,
                                connectivityManager = connectivityManager,
                                networkMetered = lowDataModeActive,
                            )
                        }
                    }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)

                val streamUrl = playbackData.streamUrl

                songUrlCache[streamCacheKey] =
                    AuthScopedCacheValue(
                        url = streamUrl,
                        expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                        authFingerprint = playbackData.authFingerprint,
                    )
                dataSpec.withUri(streamUrl.toUri())
            }

        // Route downloads by scheme: telegram:// tracks stream through TDLib (same as playback),
        // everything else through the YouTube-resolving factory above.
        private val telegramDataSourceFactory = moe.rukamori.archivetune.telegram.TelegramDataSource.Factory()

        /** Upstream for `deezer://` downloads. */
        private val deezerDownloadDataSourceFactory =
            CacheDataSource
                .Factory()
                .setCache(downloadCache)
                .setCacheKeyFactory(DownloadRequestCacheKeyFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setUpstreamDataSourceFactory(
                    DeezerDecryptingDataSource.Factory(okHttpDataSourceFactory),
                ).setCacheWriteDataSinkFactory(
                    CacheDataSink
                        .Factory()
                        .setCache(downloadCache)
                        .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                        .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE),
                )

        private val dataSourceFactory =
            DataSource.Factory {
                DownloadSchemeRoutingDataSource(
                    youtubeFactory = youtubeDataSourceFactory,
                    telegramFactory = telegramDataSourceFactory,
                    deezerFactory = deezerDownloadDataSourceFactory,
                )
            }

        val downloadNotificationHelper =
            DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                downloadExecutor,
            ).apply {
                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            if (finalException != null || download.state == Download.STATE_FAILED) {
                                songUrlCache.keys.removeIf { it.startsWith("${download.request.id}:") }
                                // Purge both the failed download's own bytes and any partial bytes
                                // written to playerCache during the prewarm (prewarmSongForDownload)
                                // or a prior playback attempt.
                                purgeSongCacheEntries(download.request.id)
                            }
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download)
                                }
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            // Mirror the failure path: when a download is removed
                            // (user-initiated delete), also purge the related cache
                            // spans so the bytes are actually freed and stale partial
                            // data can't cause parser errors on the next playback
                            // attempt.
                            purgeSongCacheEntries(download.request.id)
                            downloads.update { map -> map - download.request.id }
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        result[cursor.download.request.id] = cursor.download
                    }
                }
                downloads.value = result
            }
            downloadScope.launch {
                var previousFingerprint: String? = null
                YouTube.authStateFlow
                    .map { it.fingerprint }
                    .distinctUntilChanged()
                    .collect { fingerprint ->
                        if (previousFingerprint != null && previousFingerprint != fingerprint) {
                            songUrlCache.clear()
                        }
                        previousFingerprint = fingerprint
                    }
            }
        }

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

        /**
         * Frees every byte a song may occupy in both caches: its bare media id and each
         * source-prefixed key. [resolvePreferredDownloadDataSpec] re-keys a lossless download to the
         * source it resolved, so that copy lives under e.g. "qobuz:$mediaId" — and Media3's
         * ProgressiveDownloader.remove() can only remove the download request's own key. Left alone,
         * the prefixed copy survives the delete: real bytes under filesDir/download that nothing
         * references and no evictor reclaims.
         */
        private fun purgeSongCacheEntries(mediaId: String) {
            for (key in DownloadSourceConfig.cacheKeysFor(mediaId)) {
                runCatching { downloadCache.removeResource(key) }
                runCatching { playerCache.removeResource(key) }
            }
        }

        /**
         * Pre-warms the cache for [mediaId] by resolving the highest-quality stream available
         * (Qobuz → Tidal → Deezer → YouTube Music) and fetching the bytes into [playerCache] under
         * the source-prefixed key (e.g. "qobuz:$mediaId") BEFORE handing the download off to the
         * Media3 DownloadManager.
         */
        suspend fun prewarmSongForDownload(mediaId: String): String? {
            // Refresh the community Source Pool accounts (Qobuz/Tidal subscriber
            // tokens) before resolving — the pool refresh is throttled to once
            // per 30 min inside PoolAccountManager.refresh(), so this is a cheap
            // no-op on the hot path. Doing it here (instead of only at app start)
            // means newly-contributed accounts become available to downloads
            // without an app restart, and avoids the "downloads always fall back
            // to YouTube .webm" failure mode when the pool cache has been evicted
            // by the OS or never loaded on this cold start.
            if (PoolAccountManager.isEnabled) {
                runCatching { PoolAccountManager.refresh(appContext) }
            }

            // Fast path: bytes already cached under any source-prefixed key — no work to do. The
            // DownloadManager will pick them up via the resolver in [youtubeDataSourceFactory].
            for (key in DownloadSourceConfig.cacheKeysFor(mediaId)) {
                val spans = runCatching { playerCache.getCachedSpans(key) }.getOrNull().orEmpty()
                if (spans.isNotEmpty()) {
                    val expected = database.getSongByIdBlocking(mediaId)?.format?.contentLength ?: 0L
                    val cachedBytes = spans.sumOf { it.length }
                    if (expected > 0L && cachedBytes >= expected) {
                        return key
                    }
                    // If expected is unknown OR cached < expected, evict the
                    // partial spans so we fetch cleanly below.
                    if (expected <= 0L || cachedBytes < expected) {
                        runCatching {
                            spans.forEach { playerCache.removeSpan(it) }
                        }
                    }
                }
            }

            // Resolve the preferred source — literally the same call as the resolver inside
            // [youtubeDataSourceFactory], so the pre-warm fetch uses the same URL + cache key the
            // DownloadManager would use on cache miss.
            val lowDataModeActive = appContext.isLowDataModeActive()
            val resolved = resolveDownloadStream(mediaId)
            if (resolved != null) {
                val cacheKey = "${resolved.source.name.lowercase(java.util.Locale.US)}:$mediaId"
                // Fetch the bytes into playerCache via the shared mediaOkHttpClient. We use a
                // streaming GET and write directly to the playerCache via CacheDataSink so there's
                // no intermediate temp file (faster than PRDownloaderDataSource's temp-file
                // approach).
                val fetched = runCatching {
                    fetchStreamIntoPlayerCache(resolved.stream.uri, cacheKey, resolved.stream.contentLength)
                }.isSuccess
                if (fetched) return cacheKey
            }

            // YouTube Music fallback — prefer M4A/AAC over Opus/WebM so the
            // resulting file is .m4a (jaudiotagger-readable) rather than .webm
            // (jaudiotagger-unreadable).
            val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
            val playbackData = runCatching {
                appContext.retryWithoutPlaybackLoginContext {
                    YTPlayerUtils.playerResponseForDownload(
                        mediaId,
                        audioQuality = requestedAudioQuality,
                        connectivityManager = connectivityManager,
                        networkMetered = lowDataModeActive,
                    )
                }.getOrThrow()
            }.getOrNull() ?: return null
            persistPlaybackMetadata(mediaId, playbackData)
            val fetched = runCatching {
                fetchStreamIntoPlayerCache(
                    playbackData.streamUrl,
                    mediaId,
                    // format.contentLength is nullable — pass null when blank
                    // so fetchStreamIntoPlayerCache falls back to the
                    // Content-Length response header.
                    playbackData.format.contentLength,
                )
            }.isSuccess
            return if (fetched) mediaId else null
        }

        /**
         * Streams [url] into [playerCache] under [cacheKey] using a single OkHttp GET, writing
         * bytes through [CacheDataSink] (no intermediate temp file). Returns true on success, false
         * on any error.
         */
        private fun fetchStreamIntoPlayerCache(
            url: String,
            cacheKey: String,
            knownContentLength: Long?,
        ): Boolean {
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "keep-alive")
                .build()
            return runCatching {
                mediaOkHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} fetching $url")
                    }
                    val contentLength = knownContentLength
                        ?: response.header("Content-Length")?.toLongOrNull()
                        ?: -1L
                    // Build a DataSpec that CacheDataSink can write under.
                    val dataSpec = DataSpec.Builder()
                        .setUri(url.toUri())
                        .setKey(cacheKey)
                        .setPosition(0L)
                        .setLength(if (contentLength > 0) contentLength else C.LENGTH_UNSET.toLong())
                        .build()
                    val cacheSink = CacheDataSink.Factory()
                        .setCache(playerCache)
                        .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                        .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE)
                        .createDataSink()
                    val buffer = ByteArray(DOWNLOAD_WRITE_BUFFER_SIZE)
                    try {
                        cacheSink.open(dataSpec)
                        try {
                            response.body?.byteStream()?.use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    cacheSink.write(buffer, 0, read)
                                }
                            }
                            // CacheDataSink has no flush() — close() is what
                            // finalizes the last fragment. Don't call flush.
                        } finally {
                            runCatching { cacheSink.close() }
                        }
                        // Verify the cached spans actually cover the full range
                        // before declaring success. A partial cache would cause
                        // a corrupt export.
                        val spans = playerCache.getCachedSpans(cacheKey)
                        if (spans.isEmpty()) throw IOException("Cache empty after fetch for $cacheKey")
                        if (contentLength > 0) {
                            val cachedBytes = spans.sumOf { it.length }
                            if (cachedBytes < contentLength) {
                                // Partial — remove what we have so the next attempt
                                // starts fresh instead of serving partial bytes.
                                runCatching { playerCache.removeResource(cacheKey) }
                                throw IOException("Partial cache: $cachedBytes / $contentLength bytes for $cacheKey")
                            }
                        }
                        true
                    } catch (e: Exception) {
                        // Any exception during the streaming body read (network
                        // drop, server-side abort, etc.) leaves a partial cache
                        // that would later trigger
                        // "ParserException: Multiple Segment elements not supported"
                        // when the player tries to decode the truncated MP4.
                        // Purge the cacheKey on any failure so the next attempt
                        // starts from scratch.
                        runCatching { playerCache.removeResource(cacheKey) }
                        throw e
                    }
                }
            }.onFailure { e ->
                Timber.tag("DownloadUtil").w(e, "prewarm fetch failed for %s", cacheKey)
            }.getOrDefault(false)
        }


        /**
         * Resolves the stream a download for [mediaId] must fetch, applying the playback layer's
         * rules instead of a lossless-first search of its own:
         *
         *  - the source order the user chose for downloads, cut at YouTube Music
         *    ([DownloadSourceConfig.fetchChain]) — a lossless source only stands in for YouTube
         *    when the user placed it above YouTube;
         *  - the per-song "Play from" pin, so the download targets the recording playback picked,
         *    including the exact catalogue id a Qobuz or Qobuz-backup pick stored;
         *  - the shared [TitleMatch] gate, which rejects a candidate that is a different version
         *    or recording of the requested track — a remix, say — and lets the chain continue.
         *
         * Returns null when no lossless source accepts the track. The caller then falls back to the
         * YouTube resolver, which is also where an empty chain (YouTube Music pinned or placed
         * first) lands.
         */
        private fun resolveDownloadStream(mediaId: String): ResolvedDownload? {
            val song = database.getSongByIdBlocking(mediaId) ?: return null
            val title = song.song.title.takeIf { it.isNotBlank() } ?: return null
            val artists = song.artists.mapNotNull { it.name.takeIf(String::isNotBlank) }
            // Same precedence as playback's buildSourceQuery, so a source the gate accepts while
            // streaming is accepted for its download too.
            val album =
                song.song.albumName?.takeIf { it.isNotBlank() }
                    ?: song.album?.title?.takeIf { it.isNotBlank() }
            val durationMs = song.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L)

            // Read the per-song pins fresh from the store rather than the cached snapshot: the
            // "Play from" pick writes them immediately before playback resolves, so a preference
            // object constructed earlier can still hold the previous value.
            val pinnedSource = SongSourceOverride.get(readPreference(SongSourceOverrideKey), mediaId)
            val directQobuzTrackId = SongSourceQobuzTrackId.get(readPreference(SongSourceQobuzTrackIdKey), mediaId)
            val directQobuzBackupVideoId =
                SongSourceQobuzBackupVideoId.get(readPreference(SongSourceQobuzBackupVideoIdKey), mediaId)

            val chain = DownloadSourceConfig.resolutionChain(downloadSourceOrderCsv, pinnedSource)
            val pinnedDownloadSource = pinnedSource?.let(DownloadSourceConfig::fromAudioSource)
            for (source in chain) {
                // Deezer's public catalogue lookup fills in ISRC / album name / thumbnail, which is
                // worth doing whether or not the stream itself resolved: a fall-through to YouTube
                // still gets correct tags.
                if (source == DownloadSource.DEEZER) {
                    enrichSongMetadataFromDeezer(mediaId, title, artists, album, durationMs)
                }
                val stream =
                    runCatching {
                        resolveSourceStream(
                            source = source,
                            mediaId = mediaId,
                            title = title,
                            artists = artists,
                            album = album,
                            durationMs = durationMs,
                            directQobuzTrackId = directQobuzTrackId,
                            directQobuzBackupVideoId = directQobuzBackupVideoId,
                        )
                    }.getOrNull() ?: continue
                // A per-song pin is the user's explicit choice of recording, so the gate is skipped
                // for that source — the same bypass playback grants it.
                val match =
                    if (source == pinnedDownloadSource) {
                        TitleMatch.Result(true, 1.0, 1.0, 1.0, 1.0, "per-song override bypass")
                    } else {
                        TitleMatch.evaluate(
                            wantedTitle = title,
                            wantedArtists = artists,
                            wantedAlbum = album,
                            wantedDurationMs = durationMs,
                            stream = stream,
                        )
                    }
                if (!match.accepted) {
                    Timber.tag("DownloadUtil").i(
                        "Download source %s rejected for \"%s\": %s (matched \"%s\")",
                        source.name,
                        title,
                        match.reason,
                        stream.matchedTitle ?: "?",
                    )
                    continue
                }
                // Persist the FormatEntity before returning so future exports read the correct
                // MIME/codec (FLAC for Qobuz/Tidal lossless) instead of defaulting to MP3.
                persistSourceFormatEntity(
                    mediaId = mediaId,
                    mimeType = stream.mimeType,
                    codecs = stream.codecs,
                    contentLength = stream.contentLength,
                )
                return ResolvedDownload(source, stream)
            }
            return null
        }

        private fun resolvePreferredDownloadDataSpec(
            dataSpec: DataSpec,
            mediaId: String,
        ): DataSpec? {
            val resolved = resolveDownloadStream(mediaId) ?: return null
            return dataSpec.buildUpon()
                .setUri(resolved.stream.uri.toUri())
                .setKey("${resolved.source.name.lowercase(java.util.Locale.US)}:$mediaId")
                // The DownloadManager fetches these bytes through PRDownloader, not through
                // mediaOkHttpClient, so the mirror's required header has to ride on the
                // DataSpec — the interceptor above never sees this request. Merged rather than
                // replaced so nothing Media3 already put on the spec is dropped.
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + requiredHeadersFor(resolved.stream.uri))
                .build()
        }

        /**
         * Resolves a single source's direct stream. Returns null when the source can't resolve the
         * track (so [resolveDownloadStream] can fall through to the next source in the chain). The
         * provider's match metadata rides along on the [DirectStream] so the caller can run the
         * shared [TitleMatch] gate against it.
         */
        private fun resolveSourceStream(
            source: DownloadSource,
            mediaId: String,
            title: String,
            artists: List<String>,
            album: String?,
            durationMs: Long?,
            directQobuzTrackId: String?,
            directQobuzBackupVideoId: String?,
        ): DirectStream? = when (source) {
            DownloadSource.QOBUZ ->
                LosslessStreamResolver.resolveQobuz(
                    context = appContext,
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    formatId = qobuzAudioQuality.toFormatId(),
                    directTrackId = directQobuzTrackId,
                )
            DownloadSource.TIDAL ->
                LosslessStreamResolver.resolveTidal(
                    context = appContext,
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    audioQuality = tidalAudioQuality,
                    cacheDir = appContext.cacheDir,
                )
            DownloadSource.QOBUZ_BACKUP ->
                // Keyed by the YouTube video id, so it needs neither a Source Pool account nor a
                // catalogue search — which makes it the one lossless source that works on a fresh
                // install with nothing configured. A "Play from" pick addresses a mirror entry's
                // own video id, usually not the playing song's, so honour that pin when present.
                LosslessStreamResolver.resolveQobuzBackup((directQobuzBackupVideoId ?: mediaId).trim())
            DownloadSource.DEEZER ->
                // Returns a `deezer://` URI whose bytes are Blowfish-encrypted;
                // downloadDataSourceFactory routes that scheme through
                // DeezerDecryptingDataSource so the cache receives plain FLAC/MP3.
                LosslessStreamResolver.resolveDeezer(
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    format = deezerAudioQuality.toFormatName(),
                )
            DownloadSource.JIOSAAVN ->
                LosslessStreamResolver.resolveJioSaavn(
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    qualityApiValue = saavnAudioQuality.toApiValue(),
                )
            DownloadSource.AUTO, DownloadSource.YOUTUBE_MUSIC -> null
        }

        /**
         * Headers a resolved stream URL cannot be fetched without.
         *
         * Only the Qobuz backup mirror needs one today: `mlc-ytify.kouzu.in` rate-limits any
         * request that arrives without `x-request-source`, and that applies to the byte fetch as
         * much as to the resolve call. Every other source serves plain authenticated-by-URL CDN
         * links, so the map stays empty for them.
         */
        private fun requiredHeadersFor(uri: String): Map<String, String> =
            if (runCatching { uri.toUri().host }.getOrNull()?.endsWith("kouzu.in") == true) {
                mapOf("x-request-source" to "muzo")
            } else {
                emptyMap()
            }

        /** The stream a download settled on, with the source whose cache-key prefix holds it. */
        private data class ResolvedDownload(
            val source: DownloadSource,
            val stream: DirectStream,
        )

        /**
         * Reads a preference straight from the store. Used for the per-song pins, whose cached
         * snapshot can lag the write that "Play from" just made.
         */
        private fun readPreference(
            key: androidx.datastore.preferences.core.Preferences.Key<String>,
        ): String =
            runCatching { runBlocking { appContext.dataStore.data.first()[key] } }
                .getOrNull()
                .orEmpty()

        /**
         * Queries Deezer's public catalogue for [title]/[artists] and, if a
         * match is found, persists the album name + cover URL to the song
         * entity so the downloaded song has correct metadata + thumbnail.
         * Best-effort — failures are silently swallowed.
         */
        private fun enrichSongMetadataFromDeezer(
            mediaId: String,
            title: String,
            artists: List<String>,
            album: String?,
            durationMs: Long?,
        ) {
            downloadScope.launch {
                runCatching {
                    val resolved = moe.rukamori.archivetune.deezer.DeezerAudioProvider.lookup(
                        moe.rukamori.archivetune.deezer.DeezerAudioProvider.Query(
                            mediaId = mediaId,
                            title = title,
                            artists = artists,
                            album = album,
                            durationMs = durationMs,
                        ),
                    ) ?: return@launch
                    database.query {
                        val existing = getSongByIdBlocking(mediaId)?.song ?: return@query
                        val newThumb = existing.thumbnailUrl?.takeIf(String::isNotBlank)
                            ?: resolved.coverUrl
                        val newAlbum = existing.albumName?.takeIf(String::isNotBlank)
                            ?: resolved.album
                        if (newThumb == existing.thumbnailUrl && newAlbum == existing.albumName) {
                            return@query
                        }
                        upsert(
                            existing.copy(
                                thumbnailUrl = newThumb,
                                albumName = newAlbum,
                            ),
                        )
                    }
                }
            }
        }

        /**
         * Writes a [FormatEntity] reflecting the resolved lossless/lossy stream
         * (Qobuz/Tidal) so that subsequent exports read the correct codec + MIME
         * type instead of defaulting to MP3.
         */
        private fun persistSourceFormatEntity(
            mediaId: String,
            mimeType: String,
            codecs: String,
            contentLength: Long?,
        ) {
            val normalizedMime = mimeType.ifBlank { "audio/flac" }.substringBefore(";")
            // Synchronous write — we're already on a background thread (the
            // resolver runs inside the DownloadManager's worker pool) and
            // the FormatEntity MUST be persisted before the download begins
            // so subsequent exports read the correct MIME/codec. The
            // previous async version caused "missing metadata on
            // downloaded songs" because the export screen read the
            // FormatEntity before the coroutine had a chance to write it.
            // Calling the DAO directly bypasses MusicDatabase.query's
            // fire-and-forget executor.
            runCatching {
                database.upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = 0,
                        mimeType = normalizedMime,
                        codecs = codecs,
                        bitrate = 0,
                        sampleRate = null,
                        contentLength = contentLength ?: 0L,
                        loudnessDb = null,
                        perceptualLoudnessDb = null,
                        playbackUrl = null,
                    ),
                )
            }
        }

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            if (lowDataModeActive) AudioQuality.LOW else audioQuality

        private fun buildSongUrlCacheKey(
            mediaId: String,
            requestedAudioQuality: AudioQuality,
        ): String = "$mediaId:${requestedAudioQuality.name}"

        private fun persistPlaybackMetadata(
            mediaId: String,
            playbackData: YTPlayerUtils.PlaybackData,
        ) {
            downloadScope.launch {
                runCatching {
                    val format = playbackData.format
                    val contentLength = format.contentLength ?: 0L
                    val resolvedCodecs = format.mimeType.codecsFromMimeType()

                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = format.mimeType.split(";")[0],
                                codecs = resolvedCodecs,
                                bitrate = format.bitrate,
                                sampleRate = format.audioSampleRate,
                                contentLength = contentLength,
                                loudnessDb = playbackData.audioConfig?.loudnessDb,
                                perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                                playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                            ),
                        )

                        val now = LocalDateTime.now()
                        val existingSongRow = getSongByIdBlocking(mediaId)
                        val existing = existingSongRow?.song
                        val resolvedThumbnailUrl =
                            playbackData.videoDetails
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                                ?.takeIf { it.isNotBlank() }

                        val updatedSong =
                            if (existing != null) {
                                existing.copy(
                                    thumbnailUrl = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedThumbnailUrl,
                                    dateDownload = existing.dateDownload ?: now,
                                )
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = playbackData.videoDetails?.title ?: "Unknown",
                                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                                    thumbnailUrl = resolvedThumbnailUrl,
                                    dateDownload = now,
                                )
                            }

                        upsert(updatedSong)

                        // Persist the YouTube channel (videoDetails.author) as an ArtistEntity +
                        // SongArtistMap so that exported YT downloads have an artist tag.
                        val videoDetails = playbackData.videoDetails
                        val hasArtistMap = existingSongRow?.artists?.isNotEmpty() == true
                        if (!hasArtistMap && videoDetails != null) {
                            val authorName = videoDetails.author?.takeIf { it.isNotBlank() }
                            val channelId = videoDetails.channelId?.takeIf { it.isNotBlank() }
                            if (authorName != null) {
                                // Use the YouTube channelId as the artist id when
                                // available (matches the convention used by
                                // insert(MediaMetadata) in DatabaseDao). Fall back
                                // to a deterministic pseudo-id derived from the
                                // mediaId so we don't pollute the artist table
                                // with random UUIDs for the same video.
                                val artistId = channelId ?: "UCYT:${mediaId}"
                                // Strip the trailing " - Topic" suffix that
                                // YouTube Music auto-generated artist channels
                                // have — it's not part of the artist's name and
                                // would show up ugly in the exported metadata.
                                val cleanArtistName = authorName
                                    .removeSuffix(" - Topic")
                                    .removeSuffix("- Topic")
                                    .trim()
                                    .ifBlank { authorName }
                                upsert(
                                    ArtistEntity(
                                        id = artistId,
                                        name = cleanArtistName,
                                        channelId = channelId,
                                    ),
                                )
                                insert(
                                    SongArtistMap(
                                        songId = mediaId,
                                        artistId = artistId,
                                        position = 0,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        /**
         * Picks the download upstream by URI scheme: `telegram://` tracks go through TDLib and
         * `deezer://` through the Blowfish-decrypting chain (both mirroring playback's
         * SchemeRoutingDataSource), everything else through the YouTube-resolving factory.
         */
        private class DownloadSchemeRoutingDataSource(
            private val youtubeFactory: DataSource.Factory,
            private val telegramFactory: DataSource.Factory,
            private val deezerFactory: DataSource.Factory,
        ) : DataSource {
            private val transferListeners = mutableListOf<TransferListener>()
            private var delegate: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                transferListeners += transferListener
                delegate?.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme?.lowercase(java.util.Locale.US)
                val selected =
                    when (scheme) {
                        "telegram" -> telegramFactory
                        DeezerCrypto.SCHEME -> deezerFactory
                        else -> youtubeFactory
                    }
                val source = selected.createDataSource()
                transferListeners.forEach(source::addTransferListener)
                delegate = source
                return source.open(dataSpec)
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = checkNotNull(delegate).read(buffer, offset, length)

            override fun getUri(): Uri? = delegate?.uri

            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

            override fun close() {
                delegate?.close()
                delegate = null
            }
        }

        private object DownloadRequestCacheKeyFactory : CacheKeyFactory {
            override fun buildCacheKey(dataSpec: DataSpec): String = dataSpec.key ?: dataSpec.uri.toString()
        }

        companion object {
            // 12 concurrent songs. Increased from 8 — PRDownloader now
            // manages its own internal OkHttp dispatcher (configured in
            // App.kt) with generous per-host caps, and the cache-first
            // strategy means most downloads hit the local playerCache (no
            // network fetch, just disk read) so we can afford even more
            // parallelism without starving the player's streaming requests.
            // 12 saturates a 300+ Mbps link on the cache-miss path while
            // keeping per-song latency reasonable on the cache-hit path
            // (~1-2 s for a 40 MB FLAC).
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 12

            // PRDownloader manages its own internal OkHttp dispatcher for
            // downloads. The constants below configure the OkHttp client
            // shared by PRDownloader and the player's streaming requests.
            // Bumped from 64/128/64 → 96/256/96 to give PRDownloader more
            // concurrent streams per host (Apple Music / Tidal CDN often
            // serves multiple songs from the same host — the higher
            // per-host cap lets those requests multiplex over a single
            // HTTP/2 connection instead of queuing behind each other).
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 96
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 256
            private const val MAX_DOWNLOAD_HTTP_REQUESTS_PER_HOST = 96
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 10L

            // 16 MB in-memory write buffer for CacheDataSink — increased
            // from 12 MB. Larger buffer = fewer fsync syscalls = higher
            // write throughput on flash storage. 12 parallel downloads ×
            // 16 MB = 192 MB peak heap, at the large-heap limit on modern
            // Android — safe because the buffer is only allocated lazily
            // inside `CacheDataSink.open()`, and most downloads don't
            // allocate the full buffer at the same instant.
            internal const val DOWNLOAD_WRITE_BUFFER_SIZE = 16 * 1024 * 1024

            // 128 MB fragment size — increased from 64 MB. A 40 MB FLAC is
            // stored as 1 fragment, a 150 MB hi-res FLAC as 2 (was 3).
            // Fewer fragments = fewer open file handles + fewer fsync
            // syscalls + smaller index file in the cache directory.
            internal const val DOWNLOAD_FRAGMENT_SIZE = 128L * 1024 * 1024

            // Hosts that downloads frequently hit. Pre-warming these at app
            // start means the first download of a session skips the
            // DNS+TCP+TLS handshake (~300-800ms each).
            private val PREWARM_HOSTS = listOf(
                "www.youtube.com",
                "music.youtube.com",
                "r1---sn.googlevideo.com",
                "api.qobuz.com",
                "api.tidal.com",
                "amp-api.tidal.com",
                "www.jiosaavn.com",
                "api.deezer.com",
                "media.deezer.com",
                "www.deezer.com",
            )
        }
    }
