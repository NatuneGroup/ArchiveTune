/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.IOS
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.WEB_REMIX
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.utils.YTPlayerUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import timber.log.Timber

/**
 * The NewPipe tier of [ResolveAudioStreamUseCase]. It runs NewPipe's own YouTube stream extractor
 * first — a player request, client set and signature/n-param path that share nothing with
 * `YTPlayerUtils`, so it still works when a YouTube change breaks the shipped path. NewPipe's
 * process-wide downloader is core's, installed through [NewPipeStreamUrlExtractor].
 *
 * If that extractor yields nothing usable, it falls back to an anonymous player response minted
 * through NewPipe's JavaScript player (the injected [StreamUrlExtractor]). Neither path carries the
 * signed-in session: a track the signed-in pipeline refuses (403, age gate, a client that demands
 * auth) is often served to a plain visitor, and a tier that reused the failing session would fail
 * with it. Both rank formats with the shared [YTPlayerUtils.rankAudioFormatCandidates], so a quality
 * setting means the same thing on every tier.
 */
@Singleton
class NewPipeStreamRepository
    @Inject
    constructor(
        private val extractor: StreamUrlExtractor,
    ) : AudioStreamRepository {

        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream {
            var failure: Throwable? = null
            val direct = runCatching { resolveWithStreamExtractor(request) }
            direct.getOrNull()?.let { return it }
            direct.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                failure = error
                Timber.tag(TAG).d("NewPipe stream extractor failed for %s: %s", request.mediaId, error.summary())
            }
            for (client in ANONYMOUS_CLIENTS) {
                if (client.loginRequired) continue
                val outcome = runCatching { resolveWithClient(request, client) }
                outcome.getOrNull()?.let { return it }
                val error = outcome.exceptionOrNull() ?: continue
                if (error is CancellationException) throw error
                failure?.let(error::addSuppressed)
                failure = error
                Timber.tag(TAG).d(
                    "NewPipe player failed with %s for %s: %s",
                    client.clientName,
                    request.mediaId,
                    error.summary(),
                )
            }
            throw NewPipeExtractionException("NewPipe could not resolve a stream for ${request.mediaId}", failure)
        }

        private suspend fun resolveWithStreamExtractor(request: AudioStreamRequest): ResolvedAudioStream? {
            NewPipeStreamUrlExtractor.ensureStreamExtractorReady()
            // NewPipe's extractor is blocking; runInterruptible lets the tier timeout interrupt its
            // OkHttp calls instead of leaving the thread to finish them after the tier gave up.
            val streamExtractor =
                runInterruptible(Dispatchers.IO) {
                    ServiceList.YouTube
                        .getStreamExtractor(WATCH_URL_PREFIX + request.mediaId)
                        .also { it.fetchPage() }
                }
            val audioStreams = runInterruptible(Dispatchers.IO) { streamExtractor.audioStreams }
            val formats =
                audioStreams
                    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotBlank() }
                    .map { it.toFormat() }
            // Downloads are tagged with jaudiotagger, which reads MP4 but not WebM.
            val pool =
                if (request.purpose == StreamPurpose.DOWNLOAD) {
                    formats.filter { it.mimeType.startsWith("audio/mp4") }.ifEmpty { formats }
                } else {
                    formats
                }
            val format =
                YTPlayerUtils
                    .rankAudioFormatCandidates(pool, request.quality, request.networkMetered)
                    .firstOrNull()
                    ?: return null
            val url = format.url ?: return null
            return ResolvedAudioStream(
                url = url,
                requestHeaders = emptyMap(),
                formatId = format.itag,
                mimeType = format.mimeType.substringBefore(';'),
                codecs = format.mimeType.codecsFromMimeType(),
                bitrate = format.bitrate,
                sampleRate = format.audioSampleRate,
                contentLength = format.contentLength ?: 0L,
                expiresAtMs = url.expiresAtMs(),
                authFingerprint = request.authState.streamCacheFingerprint,
                source = StreamSource.NEWPIPE,
                title = runCatching { streamExtractor.name }.getOrNull(),
                durationSeconds = runCatching { streamExtractor.length.toInt() }.getOrNull()?.takeIf { it > 0 },
            )
        }

        private suspend fun resolveWithClient(
            request: AudioStreamRequest,
            client: YouTubeClient,
        ): ResolvedAudioStream? {
            val authState = request.authState
            val playerResponse =
                YouTube
                    .player(
                        videoId = request.mediaId,
                        playlistId = request.playlistId,
                        client = client,
                        signatureTimestamp =
                            if (client.useSignatureTimestamp) {
                                extractor.signatureTimestamp(request.mediaId).getOrNull()
                            } else {
                                null
                            },
                        setLogin = false,
                        authState = authState,
                    ).getOrThrow()
            val status = playerResponse.playabilityStatus.status
            if (status != "OK") {
                Timber.tag(TAG).d(
                    "%s returned %s for %s: %s",
                    client.clientName,
                    status,
                    request.mediaId,
                    playerResponse.playabilityStatus.reason.orEmpty(),
                )
                return null
            }

            val candidates =
                YTPlayerUtils.selectAudioFormatCandidates(
                    playerResponse = playerResponse,
                    audioQuality = request.quality,
                    networkMetered = request.networkMetered,
                )
            for (candidate in candidates) {
                val url =
                    extractor
                        .streamUrl(candidate, request.mediaId, client, authState)
                        .getOrNull()
                if (url == null) {
                    Timber.tag(TAG).d("NewPipe could not mint itag %s for %s", candidate.itag, request.mediaId)
                    continue
                }
                return ResolvedAudioStream(
                    url = url,
                    requestHeaders = emptyMap(),
                    formatId = candidate.itag,
                    mimeType = candidate.mimeType.substringBefore(';'),
                    codecs = candidate.mimeType.codecsFromMimeType(),
                    bitrate = candidate.bitrate,
                    sampleRate = candidate.audioSampleRate,
                    contentLength = candidate.contentLength ?: 0L,
                    expiresAtMs =
                        System.currentTimeMillis() +
                            (playerResponse.streamingData?.expiresInSeconds ?: DEFAULT_EXPIRES_IN_SECONDS) * 1000L,
                    authFingerprint = authState.streamCacheFingerprint,
                    source = StreamSource.NEWPIPE,
                    title = playerResponse.videoDetails?.title,
                    durationSeconds = playerResponse.videoDetails?.lengthSeconds?.toIntOrNull(),
                    thumbnailUrl = playerResponse.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                    loudnessDb = playerResponse.playerConfig?.audioConfig?.loudnessDb,
                    perceptualLoudnessDb = playerResponse.playerConfig?.audioConfig?.perceptualLoudnessDb,
                    playbackTrackingUrl = playerResponse.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                )
            }
            return null
        }

        private fun AudioStream.toFormat(): PlayerResponse.StreamingData.Format {
            val item = this.itagItem
            val codecName = item?.codec?.takeIf { it.isNotBlank() } ?: this.codec?.takeIf { it.isNotBlank() }
            val baseMimeType = format?.mimeType ?: "audio/webm"
            val trackName = audioTrackName
            return PlayerResponse.StreamingData.Format(
                itag = itag,
                url = content,
                mimeType = if (codecName != null) "$baseMimeType; codecs=\"$codecName\"" else baseMimeType,
                bitrate = bitrate.takeIf { it > 0 } ?: (averageBitrate.coerceAtLeast(0) * 1000),
                width = null,
                height = null,
                contentLength = item?.contentLength?.takeIf { it > 0L },
                quality = "tiny",
                fps = null,
                qualityLabel = null,
                averageBitrate = averageBitrate.takeIf { it > 0 }?.times(1000),
                audioQuality = null,
                approxDurationMs = null,
                audioSampleRate = item?.sampleRate?.takeIf { it > 0 },
                audioChannels = item?.audioChannels?.takeIf { it > 0 },
                loudnessDb = null,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
                // NewPipe marks the released track by appending "(original)" to its name; without
                // this the ranking would treat every dub as a candidate on equal terms.
                audioTrack =
                    audioTrackId?.let { trackId ->
                        PlayerResponse.StreamingData.Format.AudioTrack(
                            displayName = trackName,
                            id = trackId,
                            audioIsDefault = trackName?.contains("(original)") == true,
                        )
                    },
            )
        }

        /** YouTube stamps each stream URL with its own expiry; six hours is its usual lifetime. */
        private fun String.expiresAtMs(): Long =
            toHttpUrlOrNull()
                ?.queryParameter("expire")
                ?.toLongOrNull()
                ?.times(1000L)
                ?: (System.currentTimeMillis() + DEFAULT_EXPIRES_IN_SECONDS * 1000L)

        private fun Throwable.summary(): String = "${javaClass.simpleName}: ${message.orEmpty().take(160)}"

        private companion object {
            const val TAG = "NewPipeStreamRepository"
            const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="

            /**
             * Visitor-capable clients only, most likely first: NewPipe's deobfuscation is what makes a
             * cipher-only response usable here, so this avoids the ones that demand a login.
             */
            val ANONYMOUS_CLIENTS = listOf(ANDROID_MUSIC, IOS, WEB_REMIX)

            /** YouTube's own streaming URLs age out in about six hours; used when the response omits it. */
            const val DEFAULT_EXPIRES_IN_SECONDS = 6 * 60 * 60
        }
    }

class NewPipeExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
