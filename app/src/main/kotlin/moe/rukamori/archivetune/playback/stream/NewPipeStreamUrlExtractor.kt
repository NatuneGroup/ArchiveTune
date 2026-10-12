/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import moe.rukamori.archivetune.innertube.NewPipeUtils
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList

/**
 * [StreamUrlExtractor] backed by MetrolistExtractor's NewPipe extractor, reached through core's
 * [NewPipeUtils] (its `YoutubeJavaScriptPlayerManager` deobfuscation, the n-param transform and the
 * GVS PO-token append). This is the only place in the app that touches that core object, so which
 * artifact ends up in `org.schabi.newpipe.extractor` is decided here and in `:core`'s dependency
 * block, and nowhere else.
 */
object NewPipeStreamUrlExtractor : StreamUrlExtractor {
    override suspend fun signatureTimestamp(videoId: String): Result<Int> = NewPipeUtils.getSignatureTimestamp(videoId)

    override suspend fun streamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient?,
        authState: PlaybackAuthState,
    ): Result<String> = NewPipeUtils.getStreamUrl(format, videoId, client, authState)

    /**
     * For callers with no client preference of their own: no GVS PO token beyond whatever the live
     * session already carries, exactly what core's own default arguments did before this seam.
     */
    suspend fun streamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): Result<String> = streamUrl(format, videoId, null, YouTube.currentPlaybackAuthState())

    @Volatile
    private var streamExtractorReady = false

    /**
     * Readies NewPipe for a caller that drives its stream extractor directly. NewPipe holds a single
     * process-wide downloader, and [NewPipeUtils]'s initializer is what installs core's (with the
     * stream proxy), so touching it here reuses that one rather than initialising NewPipe twice.
     */
    fun ensureStreamExtractorReady() {
        if (streamExtractorReady) return
        synchronized(this) {
            if (streamExtractorReady) return
            if (NewPipe.getDownloader() == null) NewPipeUtils.hashCode()
            // The stream extractor otherwise asks returnyoutubedislikeapi.com for every track, a
            // third-party request with nothing to do with audio. Nothing else in the app reads it.
            ServiceList.YouTube.setFetchDislike(false)
            streamExtractorReady = true
        }
    }
}
