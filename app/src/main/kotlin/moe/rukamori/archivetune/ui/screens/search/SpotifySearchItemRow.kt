/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.spotify.SpotifySearchItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.ui.component.SpotifyTrackListItem
import moe.rukamori.archivetune.utils.joinByBullet

@Composable
internal fun SpotifySearchItemRow(
    item: SpotifySearchItem,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    when (item) {
        is SpotifySearchItem.Track -> {
            SpotifyTrackListItem(
                track = item.value,
                isActive = isActive,
                isPlaying = isPlaying,
                trailingContent = {
                    trailingContent()
                    SpotifyProviderIcon()
                },
                modifier = modifier,
            )
        }

        is SpotifySearchItem.Album -> {
            val album = item.value
            SpotifyCatalogRow(
                title = album.name,
                subtitle = albumSubtitle(album),
                thumbnailUrl = album.images.firstOrNull()?.url,
                modifier = modifier,
                trailingContent = trailingContent,
            )
        }

        is SpotifySearchItem.Artist -> {
            val artist = item.value
            SpotifyCatalogRow(
                title = artist.name,
                subtitle = stringResource(R.string.artist_subtitle),
                thumbnailUrl = artist.images.firstOrNull()?.url,
                modifier = modifier,
                trailingContent = trailingContent,
            )
        }

        is SpotifySearchItem.Playlist -> {
            val playlist = item.value
            SpotifyCatalogRow(
                title = playlist.name,
                subtitle = playlistSubtitle(playlist),
                thumbnailUrl = playlist.images.firstOrNull()?.url,
                modifier = modifier,
                trailingContent = trailingContent,
            )
        }
    }
}

@Composable
private fun SpotifyCatalogRow(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    modifier: Modifier,
    trailingContent: @Composable RowScope.() -> Unit,
) {
    ListItem(
        title = title,
        subtitle = subtitle,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = thumbnailUrl,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                placeholderIconRes = R.drawable.album,
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = {
            trailingContent()
            SpotifyProviderIcon()
        },
        modifier = modifier,
    )
}

@Composable
private fun SpotifyProviderIcon() {
    // The asset is the full-colour logo (green disc, opaque white waves). Icon's default tint
    // painted every opaque pixel one colour, leaving a featureless disc that also changed colour
    // with the row's active highlight. The inset keeps it off the highlight's edge: ListItem gives
    // trailing content none of its own, and the resolving spinner sits right beside it.
    Icon(
        painter = painterResource(R.drawable.spotify_icon),
        contentDescription = stringResource(R.string.spotify_account),
        tint = Color.Unspecified,
        modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
    )
}

private fun albumSubtitle(album: SpotifyAlbum): String? =
    joinByBullet(
        album.artists.joinToString { it.name },
        album.releaseDate?.take(4),
    )

private fun playlistSubtitle(playlist: SpotifyPlaylist): String? =
    joinByBullet(
        playlist.owner?.displayName,
        playlist.tracks?.total?.takeIf { it > 0 }?.let { count -> "$count ${if (count == 1) "track" else "tracks"}" },
    )

internal fun SpotifySearchItem.queryText(): String =
    when (this) {
        is SpotifySearchItem.Track -> {
            val track: SpotifyTrack = value
            listOf(track.artists.firstOrNull()?.name, track.name)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
        }

        is SpotifySearchItem.Album -> value.name
        is SpotifySearchItem.Artist -> value.name
        is SpotifySearchItem.Playlist -> value.name
    }
