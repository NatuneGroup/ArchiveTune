/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.applemusic

sealed interface AppleMusicSearchItem {
    val id: String
    val title: String
    val key: String
    val artworkUrl: String?

    data class Track(
        override val id: String,
        override val title: String,
        val artist: String,
        val album: String?,
        override val artworkUrl: String?,
        val durationMs: Long,
        val viewUrl: String?,
        val explicit: Boolean,
    ) : AppleMusicSearchItem {
        override val key: String get() = "track:$id"
    }

    data class Album(
        override val id: String,
        override val title: String,
        val artist: String,
        override val artworkUrl: String?,
        val trackCount: Int,
        val releaseYear: String?,
        val viewUrl: String?,
    ) : AppleMusicSearchItem {
        override val key: String get() = "album:$id"
    }

    data class Artist(
        override val id: String,
        override val title: String,
        val genre: String?,
        val viewUrl: String?,
        override val artworkUrl: String? = null,
    ) : AppleMusicSearchItem {
        override val key: String get() = "artist:$id"
    }
}

fun AppleMusicSearchItem.queryText(): String =
    when (this) {
        is AppleMusicSearchItem.Track ->
            listOf(artist, title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        is AppleMusicSearchItem.Album -> title
        is AppleMusicSearchItem.Artist -> title
    }
