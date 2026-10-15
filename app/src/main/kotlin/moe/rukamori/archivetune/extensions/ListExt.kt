/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.extensions

import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.Song

fun <T> List<T>.reversed(reversed: Boolean) = if (reversed) asReversed() else this

fun <T> MutableList<T>.move(
    fromIndex: Int,
    toIndex: Int,
): MutableList<T> {
    add(toIndex, removeAt(fromIndex))
    return this
}

// Extension function to filter explicit content for local Song entities
fun List<Song>.filterExplicit(enabled: Boolean = true) =
    filter { song ->
        song.artists.none { it.blockedAt != null } && (!enabled || !song.song.explicit)
    }

// Extension function to filter explicit content for local Album entities
fun List<Album>.filterExplicitAlbums(enabled: Boolean = true) =
    filter { album ->
        album.artists.none { it.blockedAt != null } && (!enabled || !album.album.explicit)
    }

fun List<Song>.filterBlockedArtists() = filter { song -> song.artists.none { it.blockedAt != null } }

fun List<Song>.filterVideo(enabled: Boolean = true) =
    if (enabled) {
        filterNot { song -> song.song.isMusicVideo }
    } else {
        this
    }
