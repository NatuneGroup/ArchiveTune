/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * YumaPlayer (2026) | Modified work by MuwMx
 */


package moe.rukamori.archivetune.spotify

import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

/** One shelf on the Spotify home. */
@Immutable
sealed interface SpotifyHomeSection {
    val title: String

    /** Track shelves come from `topTracks`, which returns one kind, so this list stays typed. */
    @Immutable
    data class Tracks(
        override val title: String,
        val tracks: List<SpotifyTrack>,
    ) : SpotifyHomeSection

    /** Playlists, albums and artists, in the order the feed sent them. */
    @Immutable
    data class Cards(
        override val title: String,
        val items: List<SpotifyHomeFeedItem>,
    ) : SpotifyHomeSection
}
