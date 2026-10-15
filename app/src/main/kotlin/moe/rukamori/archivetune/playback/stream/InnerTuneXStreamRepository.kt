/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTuneXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.constants.AudioQuality
import timber.log.Timber
import moe.rukamori.archivetune.utils.withAppProxy

/**
 * The InnerTuneX tier of [ResolveAudioStreamUseCase], and the first extractor tried after the native
 * path. InnerTuneX (`com.github.MetrolistGroup.innertubex`, GPL-3.0) is used as a library — its
 * `StreamExtractor` is adapted here, never forked — and brings its own InnerTube client, SABR/cipher
 * stack and client-fallback strategy, so the tier takes nothing from `:core` but its proxy setting.
 *
 * The player consumes plain media URLs, so a segmented SABR, byte-range-only or HLS-manifest result
 * is refused rather than handed on: it would fail at fetch time inside the player, which is worse
 * than falling through to the next tier. The capability hints below are what refuse them — the
 * library then picks a client whose response carries a plain URL — and the guards after `extract`
 * catch a library regression that hands such a stream through anyway.
 */
@Singleton
class InnerTuneXStreamRepository
    @Inject
    constructor() : AudioStreamRepository {

        private val httpClient by lazy { createHttpClient() }

        private val extractor: InnerTubeExtractor by lazy { createExtractor() }

        @Volatile
        private var prewarmed = false

        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream {
            prewarmOnce()
            val requestedQuality = request.quality.toInnerTuneXQuality()
            // Downloads are tagged with jaudiotagger, which reads MP4 but not WebM, so AAC is asked
            // for first. Low-data mode already maps to LOW, whose selector prefers MP4 on its own.
            val stream =
                if (request.purpose == StreamPurpose.DOWNLOAD && requestedQuality != InnerTuneXAudioQuality.LOW) {
                    try {
                        extractStream(request.mediaId, InnerTuneXAudioQuality.MP4)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ignored: Exception) {
                        null
                    } ?: extractStream(request.mediaId, requestedQuality)
                } else {
                    extractStream(request.mediaId, requestedQuality)
                } ?: throw InnerTuneXExtractionException("InnerTuneX returned no stream for ${request.mediaId}")
            if (stream.audioUrl.isHlsManifestUrl()) {
                throw InnerTuneXExtractionException(
                    "InnerTuneX returned an HLS manifest for ${request.mediaId}",
                )
            }
            if (stream.sabrBootstrap != null) {
                throw InnerTuneXExtractionException(
                    "InnerTuneX returned a segmented SABR stream for ${request.mediaId}",
                )
            }
            if (stream.requireBoundedRange) {
                throw InnerTuneXExtractionException(
                    "InnerTuneX returned a byte-range-only stream for ${request.mediaId}",
                )
            }
            return ResolvedAudioStream(
                url = stream.audioUrl,
                requestHeaders = stream.headers,
                formatId = stream.itag,
                mimeType = stream.mimeType?.substringBefore(';').orEmpty(),
                codecs = stream.codecs.orEmpty(),
                bitrate = stream.bitrate ?: 0,
                sampleRate = stream.sampleRate,
                contentLength = stream.contentLengthBytes ?: 0L,
                expiresAtMs =
                    stream.expiresAt?.toEpochMilliseconds()
                        ?: (System.currentTimeMillis() + DEFAULT_EXPIRES_IN_MS),
                authFingerprint = request.authState.streamCacheFingerprint,
                source = StreamSource.INNERTUBE_X,
                title = stream.mediaMetadata?.title,
                durationSeconds = stream.mediaMetadata?.durationSeconds?.toInt(),
                loudnessDb = stream.loudnessDb,
                perceptualLoudnessDb = stream.perceptualLoudnessDb,
            )
        }

        private suspend fun extractStream(
            videoId: String,
            audioQuality: InnerTuneXAudioQuality,
        ): ExtractedStream? =
            extractor.extract(
                videoId = videoId,
                // Tells the library which transports this host can fetch: the player takes plain
                // media URLs, so segmented SABR, byte-range paging and HLS manifests are all
                // unavailable. These hints are the enforcement — the library skips those clients
                // and reselects, so the tier succeeds on a fetchable client rather than building a
                // stream that the guards in [resolve] then reject. Deleting the hints regresses that;
                // the guards are defence-in-depth against a library regression.
                hints =
                    ContentHints(wantVideo = false).withStreamCapabilities(
                        allowHls = false,
                        allowSabr = false,
                        allowBoundedRange = false,
                    ),
                audioQuality = audioQuality,
            )

        private fun String.isHlsManifestUrl(): Boolean = contains("/manifest/hls") || contains(".m3u8")

        /**
         * Warming the visitor data and player config is an optimisation: never let it fail the tier.
         * The flag is set only after a warm that actually ran, so a cancelled or failed attempt is
         * retried by the next resolve instead of leaving the singleton flagged for a warm it never got.
         */
        private suspend fun prewarmOnce() {
            if (prewarmed) return
            try {
                extractor.prewarm()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).d("InnerTuneX prewarm failed: %s", t.javaClass.simpleName)
                return
            }
            prewarmed = true
        }

        private fun createExtractor(): InnerTubeExtractor {
            val innerTube = InnerTube(httpClient = httpClient)
            val playerConfigStore =
                RemotePlayerConfigStore(
                    httpClient = httpClient,
                    repository = PlayerConfigRepository.disabled(),
                )
            return InnerTubeExtractor(
                configParser = YtConfigParserImpl(httpClient, innerTube, playerConfigStore),
                cipherService = YouTubeCipherService(httpClient, playerConfigStore),
                innerTube = innerTube,
            )
        }

        /** Mirrors [moe.rukamori.archivetune.innertube.InnerTube]'s client: the same JSON conventions. */
        @OptIn(ExperimentalSerializationApi::class)
        private fun createHttpClient() =
            HttpClient(OkHttp) {
                expectSuccess = true

                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = true
                        },
                    )
                }

                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }

                engine {
                    config {
                        withAppProxy()
                    }
                }
            }

        private fun AudioQuality.toInnerTuneXQuality(): InnerTuneXAudioQuality =
            when (this) {
                AudioQuality.AUTO -> InnerTuneXAudioQuality.AUTO
                AudioQuality.LOW -> InnerTuneXAudioQuality.LOW
                // InnerTuneX has no distinct highest tier; HIGH is its best lossy audio.
                AudioQuality.HIGH, AudioQuality.HIGHEST -> InnerTuneXAudioQuality.HIGH
            }

        private companion object {
            const val TAG = "InnerTuneXStreamRepository"
            const val CONNECT_TIMEOUT_MS = 15_000L
            const val REQUEST_TIMEOUT_MS = 30_000L

            /** Used when InnerTuneX reports no expiry, matching YouTube's usual streaming-URL lifetime. */
            const val DEFAULT_EXPIRES_IN_MS = 6 * 60 * 60 * 1000L
        }
    }

class InnerTuneXExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
