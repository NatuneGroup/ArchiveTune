/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.stream

import android.content.ComponentCallbacks2
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResolveAudioStreamUseCase
    @Inject
    constructor(
        private val nativeRepository: NativeStreamRepository,
        private val innerTuneXRepository: InnerTuneXStreamRepository,
        private val newPipeRepository: NewPipeStreamRepository,
        private val ytdlnisRepository: YtdlnisStreamRepository,
    ) {
    /**
     * The tiers live behind the shipped resolver, in order: `MusicService` and `DownloadUtil` walk
     * these through [resolveShippedFallback] after `YTPlayerUtils` fails and before echo.
     */
    private val shippedFallbackTiers: List<Pair<String, AudioStreamRepository>> =
        listOf("innertubex" to innerTuneXRepository, "newpipe" to newPipeRepository)

    /**
     * [shippedFallbackTiers] plus the external yt-dlp plugin, which only this class's own [invoke]
     * reaches: the plugin is an optional APK, so it stays out of the live chain.
     */
    private val fallbackTiers: List<AudioStreamRepository> =
        shippedFallbackTiers.map { it.second } + ytdlnisRepository

    private data class CacheKey(
        val mediaId: String,
        val quality: String,
        val networkMetered: Boolean,
        val purpose: StreamPurpose,
        val authFingerprint: String,
        val pinnedFormatId: Int?,
    )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cache = ConcurrentHashMap<CacheKey, ResolvedAudioStream>()
        private val inFlight = ConcurrentHashMap<CacheKey, Deferred<ResolvedAudioStream>>()

        suspend operator fun invoke(request: AudioStreamRequest): ResolvedAudioStream {
            val key = request.cacheKey()
            cache[key]?.takeIf(::isFresh)?.let { return it }
            cache.remove(key)

            val candidate =
                scope.async(start = CoroutineStart.LAZY) {
                    resolveUncached(request).also { resolved ->
                        storeResolvedStream(key, resolved)
                    }
                }
            val active = inFlight.putIfAbsent(key, candidate)
            if (active == null) {
                candidate.invokeOnCompletion { inFlight.remove(key, candidate) }
                candidate.start()
                return candidate.await()
            }
            candidate.cancel()
            return active.await()
        }

        /**
         * Best-effort warm of the cache for an upcoming track (see NextStreamPreloader).
         * Failures are swallowed — a preload must never surface an error for a song the
         * user may never hear; the foreground resolve will retry and report properly.
         */
        suspend fun preload(request: AudioStreamRequest) {
            try {
                invoke(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
        }

        @WorkerThread
        fun resolveBlocking(request: AudioStreamRequest): ResolvedAudioStream {
            check(Looper.myLooper() != Looper.getMainLooper())
            val timeoutSeconds =
                if (request.purpose == StreamPurpose.DOWNLOAD) {
                    DOWNLOAD_RESOLUTION_TIMEOUT_SECONDS
                } else {
                    PLAYBACK_RESOLUTION_TIMEOUT_SECONDS
                }
            val startedAt = SystemClock.elapsedRealtime()
            Timber.tag(TAG).d(
                "Resolving mediaId=%s purpose=%s watchdogSeconds=%d",
                request.mediaId,
                request.purpose,
                timeoutSeconds,
            )
            val future = scope.future { invoke(request) }
            return try {
                future.get(timeoutSeconds, TimeUnit.SECONDS).also {
                    Timber.tag(TAG).d("Resolution delivered elapsedMs=%d", SystemClock.elapsedRealtime() - startedAt)
                }
            } catch (throwable: TimeoutException) {
                future.cancel(true)
                Timber.tag(TAG).w("Resolution watchdog expired elapsedMs=%d", SystemClock.elapsedRealtime() - startedAt)
                throw SocketTimeoutException(
                    "Audio stream resolution timed out after $timeoutSeconds seconds",
                ).apply { initCause(throwable) }
            } catch (throwable: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Audio stream resolution interrupted").apply {
                    initCause(throwable)
                }
            } catch (throwable: ExecutionException) {
                future.cancel(true)
                throw throwable.cause ?: throwable
            } catch (throwable: Throwable) {
                future.cancel(true)
                throw throwable
            }
        }

        fun invalidate(mediaId: String) {
            cache.keys.removeIf { it.mediaId == mediaId }
            inFlight.entries.forEach { (key, resolution) ->
                if (key.mediaId == mediaId && inFlight.remove(key, resolution)) {
                    resolution.cancel()
                }
            }
        }

        fun invalidateUrl(url: String) {
            cache.entries.removeIf { it.value.url == url }
        }

        fun peek(request: AudioStreamRequest): ResolvedAudioStream? {
            val key = request.cacheKey()
            val resolved = cache[key] ?: return null
            if (isFresh(resolved)) return resolved
            cache.remove(key, resolved)
            return null
        }

        fun clear() {
            clearCache()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }

        /**
         * Cache-only clear: entries go, in-flight resolutions are left alone.
         * Cancelling an in-flight foreground resolve hands the player a
         * CancellationException, so session invalidation (fingerprint rotation,
         * logout) must never take the resolving jobs down with the entries.
         */
        fun clearCache() {
            cache.clear()
        }

        /**
         * Background trim hook. A music player lives backgrounded — that is its
         * normal state, not memory pressure — so the gate sits at BACKGROUND
         * (40), not UI_HIDDEN (20): below that the preloaded next stream and
         * the current queue's resolved URLs must survive, or every background
         * advance forces a full InnerTube/BotGuard re-resolution. Upstream
         * trimmed heavyweight QuickJS runtimes at these levels; the fork's URL
         * cache is cheap by comparison, so it is dropped only under real
         * pressure, never for merely leaving the screen.
         */
        fun trimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                clearCache()
            }
        }

        /**
         * Walks [shippedFallbackTiers] for a request the shipped resolver already failed, each tier
         * bounded by [tierTimeoutMs]. Returns null when every tier failed; the caller then tries echo
         * and reports its own original failure. Bypasses this class's cache on purpose: the callers
         * keep their own URL caches, keyed the way the shipped path keys them.
         */
        suspend fun resolveShippedFallback(
            request: AudioStreamRequest,
            tierTimeoutMs: Long,
        ): ResolvedAudioStream? {
            for ((name, tier) in shippedFallbackTiers) {
                val startedAt = SystemClock.elapsedRealtime()
                val outcome =
                    try {
                        withTimeoutOrNull(tierTimeoutMs) { tier.resolve(request) }
                    } catch (e: CancellationException) {
                        // A timeout raised inside the tier's own code is that tier failing; only the
                        // caller's cancellation may stop the chain.
                        currentCoroutineContext().ensureActive()
                        logTierOutcome(name, null, startedAt, e.javaClass.simpleName)
                        continue
                    } catch (t: Throwable) {
                        logTierOutcome(name, null, startedAt, t.javaClass.simpleName)
                        continue
                    }
                logTierOutcome(name, outcome, startedAt, if (outcome == null) "timeout" else null)
                if (outcome != null) return outcome
            }
            return null
        }

        private fun logTierOutcome(
            name: String,
            outcome: ResolvedAudioStream?,
            startedAt: Long,
            reason: String?,
        ) {
            Timber.tag(DIAGNOSTICS_TAG).i(
                "YouTube tier=%s resolved=%s elapsedMs=%d%s",
                name,
                outcome != null,
                SystemClock.elapsedRealtime() - startedAt,
                reason?.let { " reason=$it" }.orEmpty(),
            )
        }

    // Hybrid resolver: InnerTube (native, BotGuard/QuickJS) first — fast, ~30 MB, no Python — then
    // the tiers in [fallbackTiers]. History can be returned by both native and yt-dlp: InnerTube via
    // YouTube.history() (browse), YTDLnis via yt-dlp watch history with cookies
    // (ytdlp_watch_history), but ArchiveTune's History uses InnerTube.
    private suspend fun resolveUncached(request: AudioStreamRequest): ResolvedAudioStream {
        var failure =
            try {
                return nativeRepository.resolve(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Native InnerTube failed for %s, trying the extractor tiers", request.mediaId)
                t
            }
        for (tier in fallbackTiers) {
            try {
                return tier.resolve(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                t.addSuppressed(failure)
                failure = t
                Timber.tag(TAG).w(t, "Fallback tier failed for %s, trying the next one", request.mediaId)
            }
        }
        throw failure
    }

        private fun AudioStreamRequest.cacheKey(): CacheKey =
            CacheKey(
                mediaId = mediaId,
                quality = quality.name,
                networkMetered = networkMetered,
                purpose = purpose,
                authFingerprint = authState.streamCacheFingerprint,
                pinnedFormatId = pinnedFormatId,
            )

        private fun storeResolvedStream(
            key: CacheKey,
            resolved: ResolvedAudioStream,
        ) {
            cache[key] = resolved
            Timber.tag(TAG).d(
                "Resolved %s via %s",
                key.mediaId,
                resolved.source,
            )
            if (cache.size <= MAX_CACHE_ENTRIES) return
            cache.entries.removeIf { !isFresh(it.value) }
            val excess = cache.size - MAX_CACHE_ENTRIES
            if (excess > 0) {
                cache.entries
                    .sortedBy { it.value.expiresAtMs }
                    .take(excess)
                    .forEach { entry -> cache.remove(entry.key, entry.value) }
            }
        }

        private fun isFresh(stream: ResolvedAudioStream): Boolean =
            stream.expiresAtMs > System.currentTimeMillis() + STREAM_EXPIRY_SAFETY_MS

        private companion object {
            const val TAG = "AudioStreamResolver"
            const val DIAGNOSTICS_TAG = "PlaybackDiagnostics"
            const val STREAM_EXPIRY_SAFETY_MS = 60_000L
            const val MAX_CACHE_ENTRIES = 256
            const val PLAYBACK_RESOLUTION_TIMEOUT_SECONDS = 120L
            const val DOWNLOAD_RESOLUTION_TIMEOUT_SECONDS = 180L
        }
    }
