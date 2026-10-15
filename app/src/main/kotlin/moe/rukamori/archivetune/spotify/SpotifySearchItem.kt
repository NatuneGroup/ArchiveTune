/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifySearchResult
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

/** A provider-neutral wrapper used by the Spotify search UI without changing YouTube's item model. */
sealed interface SpotifySearchItem {
    val id: String
    val title: String
    val key: String

    data class Track(val value: SpotifyTrack) : SpotifySearchItem {
        override val id: String get() = value.id
        override val title: String get() = value.name
        override val key: String get() = "track:$id"
    }

    data class Album(val value: SpotifyAlbum) : SpotifySearchItem {
        override val id: String get() = value.id
        override val title: String get() = value.name
        override val key: String get() = "album:$id"
    }

    data class Artist(val value: SpotifyArtist) : SpotifySearchItem {
        override val id: String get() = value.id
        override val title: String get() = value.name
        override val key: String get() = "artist:$id"
    }

    data class Playlist(val value: SpotifyPlaylist) : SpotifySearchItem {
        override val id: String get() = value.id
        override val title: String get() = value.name
        override val key: String get() = "playlist:$id"
    }
}

fun SpotifySearchResult.toSearchItems(): List<SpotifySearchItem> = buildList {
    tracks?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(SpotifySearchItem.Track(it)) }
    albums?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(SpotifySearchItem.Album(it)) }
    artists?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(SpotifySearchItem.Artist(it)) }
    playlists?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(SpotifySearchItem.Playlist(it)) }
}.distinctBy(SpotifySearchItem::key)
