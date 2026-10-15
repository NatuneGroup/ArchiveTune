/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.auth.YouTubeLoginRepository
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.utils.potoken.PoTokenResult

/** A single player's repair operation keeps its originating revision through every suspension. */
internal class YouTubePlaybackAuthRepair(private val owner: YouTubeLoginRepository) {
    val origin = owner.origin()

    fun publish(authState: PlaybackAuthState): Boolean = owner.publishRepair(origin, authState)

    suspend fun refreshVisitor(
        authState: PlaybackAuthState,
        fetchVisitor: suspend () -> String?,
    ): PlaybackAuthState {
        val visitor = fetchVisitor()?.takeIf { it.isNotBlank() } ?: return authState
        val refreshed = authState.copy(visitorData = visitor).normalized()
        publish(refreshed)
        return refreshed
    }

    fun withGeneratedPoTokens(
        authState: PlaybackAuthState,
        videoId: String,
        tokenResult: PoTokenResult,
    ): PlaybackAuthState {
        val updated = authState.copy(
            poTokenGvs = tokenResult.playerToken,
            poTokenGvsSession = tokenResult.sessionToken,
            poTokenGvsVideoId = videoId,
            poTokenPlayer = tokenResult.playerToken,
            poTokenPlayerVideoId = videoId,
            poTokenSubs = tokenResult.playerToken,
            poTokenSubsVideoId = videoId,
            webClientPoTokenEnabled = true,
        ).normalized()
        val current = origin.authState
        if (authState.cookie == current.cookie && authState.visitorData == current.visitorData &&
            authState.dataSyncId == current.dataSyncId) {
            publish(current.copy(
                poTokenGvs = tokenResult.playerToken,
                poTokenGvsSession = tokenResult.sessionToken,
                poTokenGvsVideoId = videoId,
                poTokenPlayer = tokenResult.playerToken,
                poTokenPlayerVideoId = videoId,
                poTokenSubs = tokenResult.playerToken,
                poTokenSubsVideoId = videoId,
                webClientPoTokenEnabled = true,
            ).normalized())
        }
        return updated
    }

    suspend fun afterBotDetection(
        authState: PlaybackAuthState,
        refreshAccountContext: suspend (PlaybackAuthState) -> PlaybackAuthState,
        refreshVisitor: suspend (PlaybackAuthState, YouTubePlaybackAuthRepair) -> PlaybackAuthState,
        mintTokens: suspend (PlaybackAuthState, YouTubePlaybackAuthRepair) -> PlaybackAuthState,
        hasGvsPoToken: (PlaybackAuthState) -> Boolean,
        clearCaches: () -> Unit,
    ): PlaybackAuthState {
        var repaired = refreshAccountContext(authState)
        if (repaired.visitorData.isNullOrBlank() || !hasGvsPoToken(repaired)) {
            repaired = refreshVisitor(repaired, this)
        }
        if (!hasGvsPoToken(repaired)) {
            repaired = mintTokens(repaired, this)
        }
        if (repaired.fingerprint != authState.fingerprint) {
            publish(repaired)
            clearCaches()
        }
        return repaired
    }
}
