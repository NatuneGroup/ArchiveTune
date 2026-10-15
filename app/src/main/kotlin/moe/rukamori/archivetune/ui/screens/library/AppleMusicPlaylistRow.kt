/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.ui.component.ItemThumbnail

/**
 * Apple Music's playlist row: a flat row on the page, not a card.
 *
 * Deliberately not a variant of [PlaylistListCard]. That card is the fork's own look — a tinted,
 * 32dp-rounded container whose colour is sampled from the artwork, with a play button and an
 * overflow button in the trailing slot. Apple Music's row shares none of that: no container, a
 * 6dp-rounded square, one chevron where the two buttons were, and a divider inset to the text
 * column instead of spacing between cards. Parameterising one row into the other would have meant a
 * flag for every one of those, and every future change to either look would have to reason about
 * both. The row is the smaller thing to keep separate.
 *
 * The long-press still opens the same menu the card's overflow button does, so nothing is
 * unreachable in this look — it is reached the way Apple Music reaches it.
 */
@Composable
fun AppleMusicPlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val subtitle = playlistSubtitle(playlist)
    val hiddenAlpha = if (playlist.playlist.isHidden) 0.45f else 1f

    Column(modifier = modifier.fillMaxWidth().graphicsLayer { alpha = hiddenAlpha }) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = onMenuClick)
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemThumbnail(
                thumbnailUrl = playlist.thumbnails.getOrNull(0),
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(AppleMusicListArtworkCorner),
                showPlaceholder = true,
                modifier = Modifier.size(AppleMusicListArtworkSize),
            )

            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = playlist.playlist.name,
                    // Apple Music sets a playlist name in the body weight, not a heading weight;
                    // the row reads as a list entry rather than as a series of titles.
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp).padding(start = 4.dp),
            )
        }

        if (showDivider) {
            // Inset to the text column, which is what separates an Apple Music list from a stack of
            // full-bleed rules: the artwork column reads as one continuous edge.
            Box(
                modifier =
                    Modifier
                        .padding(start = AppleMusicListArtworkSize + 12.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * The grey line under the name, or null when there is nothing worth saying.
 *
 * Apple Music leaves it off rather than printing "0 songs" — an empty playlist the reader just made
 * should not announce that it is empty.
 */
@Composable
private fun playlistSubtitle(playlist: Playlist): String? =
    when {
        playlist.playlist.id == PlaylistEntity.LIKED_PLAYLIST_ID -> null
        playlist.songCount > 0 -> "${playlist.songCount} ${stringResource(R.string.tracks_label)}"
        else -> null
    }

/**
 * The artwork column of the Apple Music library lists.
 *
 * Shared by the playlist rows and the section rows' glyph tiles: the two lists stack in the same
 * tab, so their artwork has to line up as one column.
 */
val AppleMusicListArtworkSize = 56.dp
val AppleMusicListArtworkCorner = 6.dp

/** Side padding for the list. Apple Music sits tighter to the edge than the fork's card list does. */
val AppleMusicListSidePadding = 16.dp

/** Width of the trailing gap the chevron needs, so callers can align a header to the same column. */
val AppleMusicRowTextInset = AppleMusicListArtworkSize + 12.dp
