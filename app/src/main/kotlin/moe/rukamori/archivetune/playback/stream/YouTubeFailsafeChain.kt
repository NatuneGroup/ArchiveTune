/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.echo.EchoStreamResolver
import moe.rukamori.archivetune.echo.utils.cipher.CipherDeobfuscator
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.Thumbnail
import moe.rukamori.archivetune.innertube.models.Thumbnails
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.utils.YTPlayerUtils
import timber.log.Timber

/**
 * What playback and downloads run once the shipped `YTPlayerUtils` resolver has failed: the extractor
 * tiers in [ResolveAudioStreamUseCase.resolveShippedFallback] (InnerTubeX, then NewPipe), then the
 * echo resolver as the very last resort. Every tier hands back a [YTPlayerUtils.PlaybackData], so the
 * URL cache, format persistence, expiry and the URL-derived request headers downstream do not care
 * which tier won.
 */
object YouTubeFailsafeChain {
    /** Per extractor tier. A tier that needs longer than this is not one worth waiting for. */
    const val TIER_TIMEOUT_MS = 12_000L

    /** Echo parses the player script with a real JavaScript parser on first use, so it gets longer. */
    private const val ECHO_TIMEOUT_MS = 20_000L

    private const val DIAGNOSTICS_TAG = "PlaybackDiagnostics"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ResolverEntryPoint {
        fun resolveAudioStream(): ResolveAudioStreamUseCase
    }

    /**
     * Failures the listener can act on. No other extractor can fix them, so the chain stops there and
     * the actionable message is kept rather than replaced by a generic one.
     */
    fun isActionable(failure: Throwable): Boolean =
        failure is YTPlayerUtils.InvalidPlaybackLoginContextException ||
            failure is YTPlayerUtils.LoginRequiredForPlaybackException

    /**
     * Recovers from [primaryFailure], the shipped resolver's, or rethrows it — always the original,
     * since that is the failure the error mapping can turn into a meaningful message.
     */
    suspend fun recover(
        context: Context,
        mediaId: String,
        primaryFailure: Throwable,
        shippedStartedAtMs: Long,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
        purpose: StreamPurpose,
        connectivityManager: ConnectivityManager,
    ): YTPlayerUtils.PlaybackData {
        logTier("shipped", false, shippedStartedAtMs, primaryFailure.javaClass.simpleName)
        if (isActionable(primaryFailure)) throw primaryFailure

        val request =
            AudioStreamRequest(
                mediaId = mediaId,
                quality = audioQuality,
                networkMetered = networkMetered,
                purpose = purpose,
                authState = YouTube.currentPlaybackAuthState(),
            )
        val resolver =
            EntryPointAccessors
                .fromApplication(context.applicationContext, ResolverEntryPoint::class.java)
                .resolveAudioStream()
        resolver.resolveShippedFallback(request, TIER_TIMEOUT_MS)?.let { return it.toPlaybackData(mediaId) }

        val echoStartedAt = SystemClock.elapsedRealtime()
        var echoFailureReason = "timeout"
        val echo =
            try {
                withTimeoutOrNull(ECHO_TIMEOUT_MS) {
                    CipherDeobfuscator.ensureInitialized(context)
                    EchoStreamResolver.playerResponseForPlayback(
                        videoId = mediaId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                    )
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                echoFailureReason = e.javaClass.simpleName
                null
            } catch (t: Throwable) {
                echoFailureReason = t.javaClass.simpleName
                null
            }
        logTier("echo", echo != null, echoStartedAt, if (echo == null) echoFailureReason else null)
        return echo ?: throw primaryFailure
    }

    private fun logTier(
        name: String,
        resolved: Boolean,
        startedAtMs: Long,
        reason: String?,
    ) {
        Timber.tag(DIAGNOSTICS_TAG).i(
            "YouTube tier=%s resolved=%s elapsedMs=%d%s",
            name,
            resolved,
            SystemClock.elapsedRealtime() - startedAtMs,
            reason?.let { " reason=$it" }.orEmpty(),
        )
    }

    private fun ResolvedAudioStream.toPlaybackData(mediaId: String): YTPlayerUtils.PlaybackData {
        // The container decides how the player chunks its reads, so a tier that reported no mime
        // type falls back to the one YouTube's audio itags are fixed to.
        val containerMimeType =
            mimeType.ifBlank {
                when (formatId) {
                    139, 140, 141 -> "audio/mp4"
                    249, 250, 251, 774 -> "audio/webm"
                    else -> ""
                }
            }
        val format =
            PlayerResponse.StreamingData.Format(
                itag = formatId,
                url = url,
                mimeType = if (codecs.isNotBlank()) "$containerMimeType; codecs=\"$codecs\"" else containerMimeType,
                bitrate = bitrate,
                width = null,
                height = null,
                contentLength = contentLength.takeIf { it > 0L },
                quality = "tiny",
                fps = null,
                qualityLabel = null,
                averageBitrate = null,
                audioQuality = null,
                approxDurationMs = null,
                audioSampleRate = sampleRate,
                audioChannels = null,
                loudnessDb = loudnessDb,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
            )
        val audioConfig =
            if (loudnessDb != null || perceptualLoudnessDb != null) {
                PlayerResponse.PlayerConfig.AudioConfig(
                    loudnessDb = loudnessDb,
                    perceptualLoudnessDb = perceptualLoudnessDb,
                )
            } else {
                null
            }
        val videoDetails =
            PlayerResponse.VideoDetails(
                videoId = mediaId,
                title = title,
                lengthSeconds = durationSeconds?.toString(),
                thumbnail = thumbnailUrl?.let { Thumbnails(listOf(Thumbnail(url = it, width = null, height = null))) },
            )
        val playbackTracking =
            playbackTrackingUrl?.let {
                PlayerResponse.PlaybackTracking(
                    videostatsPlaybackUrl = PlayerResponse.PlaybackTracking.VideostatsPlaybackUrl(baseUrl = it),
                    videostatsWatchtimeUrl = null,
                    atrUrl = null,
                )
            }
        return YTPlayerUtils.PlaybackData(
            audioConfig = audioConfig,
            videoDetails = videoDetails,
            playbackTracking = playbackTracking,
            format = format,
            streamUrl = url,
            streamExpiresInSeconds = ((expiresAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt(),
            authFingerprint = authFingerprint,
        )
    }
}
