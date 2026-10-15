/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.tidal

import moe.rukamori.archivetune.playback.artwork.ArtworkRequest
import moe.rukamori.archivetune.playback.artwork.TidalArtworkFetcher
import moe.rukamori.archivetune.playback.artwork.TidalArtworkMatch

/**
 * Bridges the [ArtworkResolver]'s Tidal artwork abstraction to [TidalAudioProvider]'s
 * instance/account search infrastructure. Contains no auth state of its own and never
 * logs credentials.
 */
object TidalArtworkProvider {
    /** Production fetcher backed by the configured Tidal instances/account. Blocking. */
    fun fetcher(): TidalArtworkFetcher =
        TidalArtworkFetcher { request: ArtworkRequest ->
            val query =
                TidalAudioProvider.Query(
                    mediaId = request.mediaId,
                    title = request.title,
                    artists = request.artists,
                    album = request.album,
                    isrc = request.isrc,
                    durationMs = request.durationMs,
                )
            TidalAudioProvider.resolveArtwork(query)?.let { result ->
                TidalArtworkMatch(
                    trackId = result.trackId,
                    releaseId = result.releaseId,
                    artworkUrl = buildTidalArtworkUrl(result.coverId, ARTWORK_RESOLVER_SIZE),
                    matchMethod = if (result.exactIsrc) "ISRC" else "SEARCH",
                    confidence = result.confidence,
                )
            }
        }

    /**
     * Tidal cover UUIDs map to the static image CDN by replacing dashes with slashes.
     * Available sizes are typically 80, 160, 320, 640, 1080 and 1280 (square).
     */
    fun buildTidalArtworkUrl(
        coverId: String,
        size: Int,
    ): String = "https://resources.tidal.com/images/${coverId.replace('-', '/')}/${size}x$size.jpg"

    private const val ARTWORK_RESOLVER_SIZE = 1080
}
