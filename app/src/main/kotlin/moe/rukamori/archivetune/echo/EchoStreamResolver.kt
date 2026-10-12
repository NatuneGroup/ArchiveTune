/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * Echo-Music stream resolution (2026-09-05 port).
 *
 * This is a port of Echo-Music's `YTPlayerUtils.playerResponseForPlayback`
 * (https://github.com/EchoMusicApp/Echo-Music, GPL-3.0, "Metrolist Project (C) 2026"),
 * copied function-for-function: the measured VISIONOS-first fallback cascade, the
 * NewPipe StreamInfo URL substitution, the last-byte HEAD+Range validation probe,
 * the WebView po-token gating and the n-transform/pot append. It runs as the
 * PRIMARY YouTube stream path; ArchiveTune's own 13-client chain remains as the
 * fallback when the Echo cascade cannot produce a validated stream, so nothing
 * that previously worked is lost.
 *
 * Adaptations (mechanical, behaviour-preserving):
 *  - Echo's `YouTube.player(videoId, playlistId, client, sts, poToken)` maps onto
 *    ArchiveTune's `YouTube.player(..., setLogin, authState)` — the auth state is
 *    passed straight through.
 *  - Echo's `NewPipeExtractor.getStreamUrl(format, videoId)` maps onto
 *    [moe.rukamori.archivetune.playback.stream.NewPipeStreamUrlExtractor.streamUrl] (the same
 *    YoutubeJavaScriptPlayerManager deobfuscation) plus the StreamInfo fallback.
 *  - The Fix403 instrumentation is Echo's own object, copied verbatim into
 *    [moe.rukamori.archivetune.echo.utils.Fix403].
 */

package moe.rukamori.archivetune.echo

import android.net.ConnectivityManager
import android.net.Uri
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.echo.utils.Fix403
import moe.rukamori.archivetune.echo.utils.cipher.CipherDeobfuscator
import moe.rukamori.archivetune.echo.utils.potoken.PoTokenGenerator
import moe.rukamori.archivetune.echo.utils.potoken.PoTokenResult
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.utils.withAppProxy
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.IOS
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.IPADOS
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.TVHTML5
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.VISIONOS
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.WEB_REMIX
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.playback.stream.NewPipeStreamUrlExtractor
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.reportException
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object EchoStreamResolver {
    private const val logTag = "EchoResolver"
    private const val TAG = "EchoResolver"

    @Volatile
    var playbackEngine: PlaybackEngine = PlaybackEngine.AUTO

    private val httpClient =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private val poTokenGenerator = PoTokenGenerator()

    private const val VALIDATION_CHUNK_LENGTH = 512 * 1024L

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> =
        arrayOf(
            VISIONOS,
            ANDROID_VR_1_65_10,
            TVHTML5,
            ANDROID_VR_1_43_32,
            IPADOS,
            IOS,

            WEB_CREATOR,
        )

    private val NORMAL_CONTENT_STREAM_START_INDEX: Int = 0

    private val PRIVATE_TRACK_STREAM_START_INDEX: Int =
        STREAM_FALLBACK_CLIENTS.indexOf(TVHTML5).takeIf { it >= 0 } ?: 0

    private fun describeStreamUrl(url: String): String =
        try {
            val uri = Uri.parse(url)
            val expire = uri.getQueryParameter("expire")?.toLongOrNull()
            val nowSec = System.currentTimeMillis() / 1000
            buildString {
                append("host=").append(uri.host ?: "?")
                append(" itag=").append(uri.getQueryParameter("itag") ?: "-")
                append(" mime=").append(uri.getQueryParameter("mime") ?: "-")
                append(" c=").append(uri.getQueryParameter("c") ?: "-")
                append(" expire=").append(expire ?: "-")
                if (expire != null) append("(in ").append(expire - nowSec).append("s)")
                append(" hasPot=").append(uri.getQueryParameter("pot") != null)
                append(" nLen=").append(uri.getQueryParameter("n")?.length ?: -1)
                append(" cpn=").append(uri.getQueryParameter("cpn") ?: "-")
                append(" lmt=").append(uri.getQueryParameter("lmt") ?: "-")
                append(" sabr=").append(uri.getQueryParameter("sabr") ?: "-")
                append(" clen=").append(uri.getQueryParameter("clen") ?: "-")
            }
        } catch (e: Exception) {
            "unparseable url (${e.javaClass.simpleName})"
        }

    private fun describeResponse(client: YouTubeClient, response: PlayerResponse?): String =
        try {
            if (response == null) {
                Fix403.kv("client" to client.clientName, "response" to "NULL(requestFailed)")
            } else {
                val adaptive = response.streamingData?.adaptiveFormats.orEmpty()
                val audio = adaptive.filter { it.isAudio }
                Fix403.kv(
                    "client" to client.clientName,
                    "clientVersion" to client.clientVersion,
                    "status" to response.playabilityStatus.status,
                    "reason" to response.playabilityStatus.reason,
                    "hasStreamingData" to (response.streamingData != null),
                    "expiresInSeconds" to response.streamingData?.expiresInSeconds,
                    "adaptiveFormats" to adaptive.size,
                    "audioFormats" to audio.size,
                    "urls" to adaptive.count { !it.url.isNullOrEmpty() },
                    "ciphers" to adaptive.count { !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() },
                    "bare" to adaptive.count {
                        it.url.isNullOrEmpty() && it.signatureCipher.isNullOrEmpty() && it.cipher.isNullOrEmpty()
                    },
                    "musicVideoType" to response.videoDetails?.musicVideoType,
                    "title" to response.videoDetails?.title,
                )
            }
        } catch (e: Exception) {
            "describeResponse failed (${e.javaClass.simpleName}: ${e.message})"
        }

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): YTPlayerUtils.PlaybackData =
        runCatching {
            val fx = Fix403.nextId("res")
            Timber.tag(TAG).d("=== ECHO PLAYER RESPONSE FOR PLAYBACK ===")
            Timber.tag(TAG).d("videoId: $videoId")
            Timber.tag(TAG).d("playlistId: $playlistId")
            Timber.tag(TAG).d("audioQuality: $audioQuality")

            val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
            val isLoggedIn = YouTube.cookie != null
            Fix403.i(
                fx,
                "resolve.begin",
                Fix403.kv(
                    "videoId" to videoId,
                    "playlistId" to playlistId,
                    "quality" to audioQuality,
                    "uploadedTrack" to isUploadedTrack,
                    "loggedIn" to isLoggedIn,
                    "thread" to Thread.currentThread().name,
                ),
            )
            Fix403.i(
                fx,
                "resolve.session",
                Fix403.kv(
                    "cookie" to Fix403.redact(YouTube.cookie),
                    "visitorData" to Fix403.redact(YouTube.visitorData),
                    "dataSyncId" to Fix403.redact(YouTube.dataSyncId),
                    "proxy" to (YouTube.proxy?.toString() ?: "none"),
                ),
            )

            val signatureTimestamp = getSignatureTimestampOrNull(videoId)
            Timber.tag(logTag).d("Signature timestamp: $signatureTimestamp")
            Fix403.i(fx, "resolve.sts", Fix403.kv("sts" to signatureTimestamp, "source" to "NewPipeStreamUrlExtractor"))

            var poToken: PoTokenResult? = null
            val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
            val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens
            Fix403.i(
                fx,
                "potoken.decide",
                Fix403.kv(
                    "mainClientNeedsPoToken" to mainClientNeedsPoToken,
                    "sessionIdSource" to if (isLoggedIn) "dataSyncId" else "visitorData",
                    "sessionId" to Fix403.redact(sessionId),
                    "sessionIdEmpty" to (sessionId != null && sessionId.isEmpty()),
                ),
            )
            if (mainClientNeedsPoToken && sessionId != null) {
                try {
                    poToken = Fix403.timed(fx, "potoken.generate") {
                        poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    }
                    Fix403.i(
                        fx,
                        "potoken.result",
                        Fix403.kv(
                            "obtained" to (poToken != null),
                            "playerRequestPoToken" to Fix403.redact(poToken?.playerRequestPoToken),
                            "streamingDataPoToken" to Fix403.redact(poToken?.streamingDataPoToken),
                        ),
                    )
                } catch (e: Exception) {
                    Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
                    Fix403.fail(fx, "potoken.generate.failed", e)
                }
            }

            val skipMainClient = mainClientNeedsPoToken && poToken == null
            if (skipMainClient) {
                Timber.tag(TAG).w("PoToken unavailable — skipping MAIN_CLIENT and using fallback chain directly")
                Fix403.w(fx, "mainClient.skipped", Fix403.kv("reason" to "poTokenUnavailable"))
            }

            var mainPlayerResponse =
                Fix403.trapRethrow(fx, "mainClient.player") {
                    Fix403.timed(fx, "mainClient.request") {
                        YouTube
                            .player(
                                videoId = videoId,
                                playlistId = playlistId,
                                client = MAIN_CLIENT,
                                signatureTimestamp = signatureTimestamp,
                                poToken = poToken?.playerRequestPoToken,
                            ).getOrThrow()
                    }
                }
            Fix403.i(fx, "mainClient.response", describeResponse(MAIN_CLIENT, mainPlayerResponse))

            var usedAgeRestrictedClient: YouTubeClient? = null
            val wasOriginallyAgeRestricted: Boolean

            val mainStatus = mainPlayerResponse.playabilityStatus.status
            val isAgeRestrictedFromResponse =
                mainStatus in
                    listOf(
                        "AGE_CHECK_REQUIRED",
                        "AGE_VERIFICATION_REQUIRED",
                        "LOGIN_REQUIRED",
                        "CONTENT_CHECK_REQUIRED",
                    )
            wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

            if (isAgeRestrictedFromResponse && isLoggedIn) {
                Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
                Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
                val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
                if (creatorResponse?.playabilityStatus?.status == "OK") {
                    mainPlayerResponse = creatorResponse
                    usedAgeRestrictedClient = WEB_CREATOR
                }
            }

            val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
            val videoDetails = mainPlayerResponse.videoDetails
            val playbackTracking = mainPlayerResponse.playbackTracking
            var format: PlayerResponse.StreamingData.Format? = null
            var streamUrl: String? = null
            var streamExpiresInSeconds: Int? = null
            var streamPlayerResponse: PlayerResponse? = null
            val retryMainPlayerResponse: PlayerResponse? =
                if (usedAgeRestrictedClient != null) mainPlayerResponse else null

            val currentStatus = mainPlayerResponse.playabilityStatus.status
            val isAgeRestricted =
                currentStatus in
                    listOf(
                        "AGE_CHECK_REQUIRED",
                        "AGE_VERIFICATION_REQUIRED",
                        "LOGIN_REQUIRED",
                        "CONTENT_CHECK_REQUIRED",
                    )
            if (isAgeRestricted) {
                Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            }

            val isPrivateTrack =
                mainPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

            val startIndex =
                when {
                    isPrivateTrack -> PRIVATE_TRACK_STREAM_START_INDEX
                    isAgeRestricted -> 0
                    skipMainClient -> 0
                    else -> NORMAL_CONTENT_STREAM_START_INDEX
                }

            val cascade = mutableListOf<String>()
            fun logCascade(outcome: String) =
                Fix403.i(
                    fx,
                    "cascade.$outcome",
                    Fix403.kv("videoId" to videoId, "tried" to cascade.size) + " :: " + cascade.joinToString(" | "),
                )

            for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
                format = null
                streamUrl = null
                streamExpiresInSeconds = null

                val client: YouTubeClient = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    cascade += "${client.clientName}=SKIP(loginRequired)"
                    Fix403.w(fx, "client.skip", Fix403.kv("client" to client.clientName, "reason" to "loginRequiredButAnonymous"))
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")

                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp
                Fix403.i(
                    fx,
                    "client.request",
                    Fix403.kv(
                        "idx" to "${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}",
                        "client" to client.clientName,
                        "clientVersion" to client.clientVersion,
                        "useWebPoTokens" to client.useWebPoTokens,
                        "sts" to clientSigTimestamp,
                        "poToken" to Fix403.redact(clientPoToken),
                    ),
                )
                streamPlayerResponse =
                    Fix403.trap(fx, "client.request.${client.clientName}") {
                        Fix403.timed(fx, "client.http.${client.clientName}") {
                            YouTube
                                .player(
                                    videoId = videoId,
                                    playlistId = playlistId,
                                    client = client,
                                    signatureTimestamp = clientSigTimestamp,
                                    poToken = clientPoToken,
                                ).onFailure { Fix403.fail(fx, "client.player.failed.${client.clientName}", it) }
                                .getOrNull()
                        }
                    }
                Fix403.i(fx, "client.response", describeResponse(client, streamPlayerResponse))

                if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                    Timber.tag(logTag).d("Player response status OK for client: ${client.clientName}")

                    // 4nx3b's core re-resolves the player response through NewPipe here and falls
                    // back to the InnerTube one when that returns nothing. This fork's core has no
                    // such entry point, so the fallback is the only branch — the URL is still
                    // deobfuscated through NewPipeStreamUrlExtractor.streamUrl further down.
                    val responseToUse = streamPlayerResponse

                    format =
                        findFormat(
                            responseToUse,
                            audioQuality,
                            connectivityManager,
                        )

                    if (format == null) {
                        cascade += "${client.clientName}=NO_FORMAT"
                        Fix403.w(
                            fx,
                            "client.noFormat",
                            Fix403.kv("client" to client.clientName, "quality" to audioQuality) + " " +
                                describeResponse(client, responseToUse),
                        )
                        continue
                    }

                    Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                    val urlSource =
                        when {
                            !format.url.isNullOrEmpty() -> "FORMAT_URL"
                            !format.signatureCipher.isNullOrEmpty() || !format.cipher.isNullOrEmpty() -> "SIG_CIPHER"
                            else -> "NEWPIPE_OR_NONE"
                        }
                    streamUrl =
                        Fix403.trap(fx, "findUrl.${client.clientName}") {
                            findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                        }
                    Fix403.i(
                        fx,
                        "client.url",
                        Fix403.kv(
                            "client" to client.clientName,
                            "itag" to format.itag,
                            "mime" to format.mimeType,
                            "bitrate" to format.bitrate,
                            "urlSource" to urlSource,
                            "resolved" to (streamUrl != null),
                        ) + if (streamUrl != null) " " + describeStreamUrl(streamUrl!!) else "",
                    )
                    if (streamUrl == null) {
                        cascade += "${client.clientName}=NO_URL($urlSource)"
                        Fix403.w(fx, "client.noUrl", Fix403.kv("client" to client.clientName, "urlSource" to urlSource))
                        continue
                    }

                    val currentClient = STREAM_FALLBACK_CLIENTS[clientIndex]

                    val isPrivatelyOwnedTrack =
                        streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                    val needsNTransform =
                        currentClient.useWebPoTokens ||
                            currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                            isPrivatelyOwnedTrack
                    if (needsNTransform) {
                        try {
                            val originalUrl = streamUrl!!
                            streamUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl!!)

                            val needsPoToken =
                                (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) &&
                                    poToken?.streamingDataPoToken != null
                            if (needsPoToken) {
                                val separator = if ("?" in streamUrl!!) "&" else "?"
                                streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken!!.streamingDataPoToken)}"
                            }
                            if (originalUrl == streamUrl) {
                                Fix403.d(fx, "nTransform.noop", Fix403.kv("client" to currentClient.clientName))
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")

                        }
                    }

                    streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                    if (streamExpiresInSeconds == null) {
                        cascade += "${client.clientName}=NO_EXPIRE"
                        Fix403.w(
                            fx,
                            "client.noExpire",
                            Fix403.kv(
                                "client" to client.clientName,
                                "hasStreamingData" to (streamPlayerResponse.streamingData != null),
                            ),
                        )
                        continue
                    }

                    if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwnedTrack) {

                        Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwnedTrack")
                        cascade += "${currentClient.clientName}=ACCEPTED(unvalidated)"
                        Fix403.i(
                            fx,
                            "client.accepted",
                            Fix403.kv(
                                "client" to currentClient.clientName,
                                "validated" to false,
                                "why" to if (isPrivatelyOwnedTrack) "privatelyOwnedTrack" else "lastFallbackClient",
                                "expiresInSeconds" to streamExpiresInSeconds,
                            ) + " " + describeStreamUrl(streamUrl!!),
                        )
                        logCascade("resolved")
                        break
                    }

                    if (validateStatus(
                            streamUrl,
                            format.contentLength,
                            Fix403.kv("fx" to fx, "client" to currentClient.clientName, "itag" to format.itag),
                        )
                    ) {
                        cascade += "${currentClient.clientName}=ACCEPTED"
                        Fix403.i(
                            fx,
                            "client.accepted",
                            Fix403.kv(
                                "client" to currentClient.clientName,
                                "validated" to true,
                                "expiresInSeconds" to streamExpiresInSeconds,
                            ) + " " + describeStreamUrl(streamUrl!!),
                        )
                        logCascade("resolved")
                        break
                    } else {
                        cascade += "${currentClient.clientName}=REJECTED(validate)"
                    }
                } else {
                    cascade += "${client.clientName}=NOT_OK(${streamPlayerResponse?.playabilityStatus?.status ?: "null"})"
                    Fix403.w(
                        fx,
                        "client.notOk",
                        Fix403.kv(
                            "client" to client.clientName,
                            "status" to streamPlayerResponse?.playabilityStatus?.status,
                            "reason" to streamPlayerResponse?.playabilityStatus?.reason,
                        ),
                    )
                }
            }

            if (streamPlayerResponse == null) {
                logCascade("exhausted")
                Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "badStreamPlayerResponse"))
                throw Exception("Bad stream player response")
            }
            if (streamPlayerResponse.playabilityStatus.status != "OK") {
                val errorReason = streamPlayerResponse.playabilityStatus.reason
                logCascade("exhausted")
                Fix403.e(
                    fx,
                    "resolve.failed",
                    Fix403.kv(
                        "videoId" to videoId,
                        "why" to "playabilityNotOk",
                        "status" to streamPlayerResponse.playabilityStatus.status,
                        "reason" to errorReason,
                    ),
                )
                throw Exception(errorReason ?: "Playability status not OK")
            }
            if (streamExpiresInSeconds == null) {
                logCascade("exhausted")
                Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "missingExpireTime"))
                throw Exception("Missing stream expire time")
            }
            if (format == null) {
                logCascade("exhausted")
                Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "noFormat"))
                throw Exception("Could not find format")
            }
            if (streamUrl == null) {
                logCascade("exhausted")
                Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "noStreamUrl"))
                throw Exception("Could not find stream url")
            }

            Fix403.i(
                fx,
                "resolve.success",
                Fix403.kv("videoId" to videoId, "itag" to format.itag, "expiresInSeconds" to streamExpiresInSeconds) +
                    " " + describeStreamUrl(streamUrl!!),
            )
            Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")

            YTPlayerUtils.PlaybackData(
                audioConfig = audioConfig,
                videoDetails = videoDetails,
                playbackTracking = playbackTracking,
                format = format,
                streamUrl = streamUrl,
                streamExpiresInSeconds = streamExpiresInSeconds,
                authFingerprint = YouTube.currentPlaybackAuthState().streamCacheFingerprint,
            )
        }.onFailure { e ->
            Fix403.fail(
                Fix403.nextId("resolve-fail"),
                "resolve.exception",
                e,
                Fix403.kv("videoId" to videoId, "playlistId" to playlistId),
            )
        }.getOrThrow()

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format =
            playerResponse.streamingData?.adaptiveFormats
                // No isOriginal here — that field marks the undubbed track and this fork's
                // PlayerResponse does not carry it, so every audio format stays a candidate.
                ?.filter { it.isAudio }
                ?.maxByOrNull {
                    it.bitrate * 1 + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }

    private fun validateStatus(
        url: String,
        contentLength: Long? = null,
        label: String = "",
    ): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        return try {
            val range =
                if (contentLength != null && contentLength > 0) {
                    "bytes=${contentLength - 1}-${contentLength - 1}"
                } else {
                    "bytes=0-${VALIDATION_CHUNK_LENGTH - 1}"
                }
            val requestBuilder =
                okhttp3.Request
                    .Builder()
                    .head()
                    .url(url)
                    .addHeader("Range", range)

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.close()
            val code = response.code
            val accepted = response.isSuccessful || code == 405
            when {
                !accepted ->
                    Timber.tag(logTag).w("Stream URL REJECTED: code=$code range=$range $label ${describeStreamUrl(url)}")
                !response.isSuccessful ->
                    Timber.tag(logTag).w("Stream URL accepted on non-2xx code=$code (HEAD refused) range=$range $label")
                else ->
                    Timber.tag(logTag).d("Stream URL validation: code=$code range=$range accepted $label")
            }
            accepted
        } catch (e: java.io.IOException) {

            Timber.tag(logTag).w(e, "Stream URL HEAD probe failed (IO); accepting optimistically")
            true
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
            false
        }
    }

    private suspend fun getSignatureTimestampOrNull(videoId: String): Int? {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        return NewPipeStreamUrlExtractor
            .signatureTimestamp(videoId)
            .onFailure { error ->
                Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                reportException(error)
            }.getOrNull()
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false,
    ): String? {
        val engine = playbackEngine
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, engine: $engine, skipNewPipe: $skipNewPipe")

        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        val useCipher = engine == PlaybackEngine.POTOKEN || engine == PlaybackEngine.AUTO
        if (useCipher) {
            val signatureCipher = format.signatureCipher ?: format.cipher
            if (!signatureCipher.isNullOrEmpty()) {
                Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation (engine=$engine)")
                try {
                    val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
                    if (customDeobfuscatedUrl != null) {
                        Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                        return customDeobfuscatedUrl
                    }
                } catch (e: Exception) {
                    Timber.tag(logTag).e(e, "Custom cipher deobfuscation failed")
                }
                Timber.tag(logTag).d("Custom cipher deobfuscation failed or returned null")
            }
        }

        val useBravePipe = engine == PlaybackEngine.BRAVEPIPE || engine == PlaybackEngine.AUTO
        if (useBravePipe) {
            if (skipNewPipe) {
                Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            } else {

                try {
                    val deobfuscatedUrl =
                        NewPipeStreamUrlExtractor
                            .streamUrl(format, videoId)
                            .getOrNull()
                    if (deobfuscatedUrl != null) {
                        Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
                        return deobfuscatedUrl
                    }
                } catch (e: Exception) {
                    Timber.tag(logTag).e(e, "NewPipe deobfuscation failed")
                }

                // 4nx3b follow this with a StreamInfo pass that lists every (itag, url) pair and
                // picks one. It needs a core API this fork does not have, and the
                // NewPipeStreamUrlExtractor.streamUrl call above already resolves this same format
                // through NewPipe — so there is nothing a second pass could add.
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }
}
