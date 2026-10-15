/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem

internal const val SPOTIFY_CATALOG_RESOLVE_TIMEOUT_MS = 20_000L

/** The one resolver for a tapped Spotify album: home, search and the Library all go through it. */
internal suspend fun resolveSpotifyAlbumTarget(
    albumId: String,
    albumName: String,
    artistName: String?,
): SpotifyReleaseTarget? =
    resolveSpotifyRelease(
        query = listOfNotNull(albumName, artistName).filter(String::isNotBlank).joinToString(" "),
        searchAlbum = { searchYouTubeCatalogItem<AlbumItem>(it, YouTube.SearchFilter.FILTER_ALBUM) },
        searchSong = { searchYouTubeCatalogItem<SongItem>(it, YouTube.SearchFilter.FILTER_SONG) },
        spotifyTracks = { Spotify.album(albumId).getOrNull()?.tracks?.items.orEmpty() },
    )

internal suspend fun resolveSpotifyArtistId(artistName: String): String? =
    searchYouTubeCatalogItem<ArtistItem>(artistName, YouTube.SearchFilter.FILTER_ARTIST)?.id
