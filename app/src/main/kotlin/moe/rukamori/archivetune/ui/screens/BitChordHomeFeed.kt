/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * BitChord-style home feed presentation (2026-10-08: "Redesign the entire
 * home screen, remove the current ui elements and everything else and port
 * everything like fonts, typography, dimensions, features, behaviour,
 * spacing, and everything else from bitchord repo"):
 * SF Pro Display typography, 16dp page gutter, 150dp shelf cards (12dp
 * corners), 0.92-ratio hero cards (18dp corners), the recents shelf with its
 * list/grid view toggle, playing-bars indicator, hairline thumbnail borders
 * and metric-matched shimmer skeletons. Adapted from BitChord
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0) as an original
 * re-implementation over ArchiveTune's data model.
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.models.MediaMetadata

val BitChordFontFamily =
    FontFamily(
        Font(R.font.sf_pro_display_regular, FontWeight.W400),
        Font(R.font.sf_pro_display_medium, FontWeight.W500),
        Font(R.font.sf_pro_display_semibold, FontWeight.W600),
        Font(R.font.sfprodisplaybold, FontWeight.W700),
        Font(R.font.sf_pro_display_heavy, FontWeight.W800),
    )

val BitChordPageGutter = 16.dp
val BitChordShelfCardWidth = 150.dp
val BitChordShelfCardCorner = 12.dp
val BitChordShelfCardSpacing = 14.dp
val BitChordShelfBottomSpacing = 26.dp
const val BitChordHeroCardRatio = 0.92f
val BitChordHeroCardCorner = 18.dp
private const val BitChordHeroCardFraction = 0.70f
private val BitChordHeroCardMaxWidth = 320.dp
private val BitChordTrackColumnMaxWidth = 400.dp
private const val BitChordRecentTracksPerColumn = 4
val BitChordPlayingAccent = Color(0xFFFB4A62)

fun bitChordHeroCardWidth(available: Dp): Dp = (available * BitChordHeroCardFraction).coerceAtMost(BitChordHeroCardMaxWidth)

fun Modifier.bitChordThumbnailBorder(shape: RoundedCornerShape): Modifier =
    drawWithCache {
        val border = 1.dp.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            drawOutline(outline, Color.White.copy(alpha = 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(border))
        }
    }

@Composable
fun BitChordSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = BitChordPageGutter, vertical = 10.dp),
    ) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.W700,
            fontFamily = BitChordFontFamily,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.W400,
                fontFamily = BitChordFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun BitChordPlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "BitChordPlayingBars")
    val bars =
        (0..2).map { index ->
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            keyframes {
                                durationMillis = 520 + index * 130
                                0.38f at 0
                                1f at (durationMillis / 2)
                                0.52f at durationMillis
                            },
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
                    ),
                label = "bar$index",
            )
        }
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEach { bar ->
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height((14.dp * bar.value))
                        .background(Color.White),
            )
        }
    }
}

@Composable
fun BitChordHeroCard(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlayBarsAlignment: Alignment = Alignment.Center,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier =
            modifier
                .aspectRatio(BitChordHeroCardRatio)
                .clip(RoundedCornerShape(BitChordHeroCardCorner))
                .bitChordThumbnailBorder(RoundedCornerShape(BitChordHeroCardCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrent && isPlaying) {
            BitChordPlayingBars(
                modifier =
                    Modifier
                        .align(onPlayBarsAlignment)
                        .padding(6.dp),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.W700,
                fontFamily = BitChordFontFamily,
                color = if (isCurrent) BitChordPlayingAccent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
                fontFamily = BitChordFontFamily,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun BitChordShelfCard(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier =
            modifier
                .width(BitChordShelfCardWidth)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .size(BitChordShelfCardWidth)
                    .clip(RoundedCornerShape(BitChordShelfCardCorner))
                    .bitChordThumbnailBorder(RoundedCornerShape(BitChordShelfCardCorner))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent && isPlaying) {
                BitChordPlayingBars(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.W600,
            fontFamily = BitChordFontFamily,
            letterSpacing = (-0.2).sp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.W400,
            fontFamily = BitChordFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun BitChordCompactTrackRow(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .padding(vertical = 4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .bitChordThumbnailBorder(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                fontFamily = BitChordFontFamily,
                letterSpacing = (-0.2).sp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
                fontFamily = BitChordFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private const val BitChordShimmerPeriodMs = 1400

@Composable
private fun BitChordShimmerBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp),
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight =
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f).compositeOver(base)
    val transition = rememberInfiniteTransition(label = "BitChordShimmer")
    val sweep by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BitChordShimmerPeriodMs, easing = LinearEasing)),
            label = "sweep",
        )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                val band = size.width * 0.5f
                val startX = -band + sweep * (size.width + band * 2f)
                val brush =
                    Brush.horizontalGradient(
                        listOf(base, highlight, base),
                        startX,
                        startX + band,
                    )
                onDrawBehind { drawRect(brush) }
            },
    )
}

@Composable
fun BitChordShelfSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = BitChordPageGutter),
        horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
    ) {
        repeat(3) {
            Column(Modifier.width(BitChordShelfCardWidth)) {
                BitChordShimmerBox(
                    Modifier
                        .size(BitChordShelfCardWidth)
                        .clip(RoundedCornerShape(BitChordShelfCardCorner)),
                    RoundedCornerShape(BitChordShelfCardCorner),
                )
                Spacer(Modifier.height(10.dp))
                BitChordShimmerBox(Modifier.fillMaxWidth().height(14.dp))
                Spacer(Modifier.height(6.dp))
                BitChordShimmerBox(Modifier.size(width = 90.dp, height = 11.dp))
            }
        }
    }
}

private enum class BitChordRecentsView { LIST, GRID }

@Composable
fun BitChordRecentsShelf(
    songs: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    onPlaySong: (Song, Int) -> Unit,
    onSongLongClick: (Song) -> Unit,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    var view by rememberSaveable { mutableStateOf(BitChordRecentsView.LIST) }
    val distinctSongs = remember(songs) { songs.distinctBy { it.id } }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitChordPageGutter, vertical = 10.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.home_recently_played),
            fontSize = 22.sp,
            fontWeight = FontWeight.W700,
            fontFamily = BitChordFontFamily,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                view = if (view == BitChordRecentsView.LIST) BitChordRecentsView.GRID else BitChordRecentsView.LIST
            },
        ) {
            Icon(
                painter =
                    painterResource(
                        if (view == BitChordRecentsView.LIST) {
                            R.drawable.grid_view
                        } else {
                            R.drawable.bitchord_list_view
                        },
                    ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    when (view) {
        BitChordRecentsView.LIST -> {
            val columns =
                remember(songs) {
                    songs
                        .distinctBy { it.id }
                        .chunked(BitChordRecentTracksPerColumn)
                }
            LazyRow(
                contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier.fillMaxWidth(),
                userScrollEnabled = columns.size > 1,
            ) {
                itemsIndexed(columns, key = { ci, _ -> "recent_col_$ci" }) { columnIndex, column ->
                    Column(
                        modifier =
                            Modifier.width(
                                (availableWidth * 0.88f).coerceAtMost(BitChordTrackColumnMaxWidth) -
                                    BitChordPageGutter,
                            ),
                    ) {
                        column.forEachIndexed { rowIndexInColumn, song ->
                            val globalIndex = columnIndex * BitChordRecentTracksPerColumn + rowIndexInColumn
                            BitChordCompactTrackRow(
                                artworkUrl = song.thumbnailUrl,
                                title = song.song.title,
                                subtitle = song.artists.joinToString { it.name },
                                isCurrent = mediaMetadata?.id == song.id,
                                onClick = { onPlaySong(song, globalIndex) },
                                onLongClick = { onSongLongClick(song) },
                            )
                        }
                    }
                }
            }
        }

        BitChordRecentsView.GRID -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
                modifier = modifier.fillMaxWidth(),
            ) {
                items(distinctSongs, key = { "recent_${it.id}" }) { song ->
                    BitChordHeroCard(
                        artworkUrl = song.thumbnailUrl,
                        title = song.song.title,
                        subtitle = song.artists.joinToString { it.name },
                        isCurrent = mediaMetadata?.id == song.id,
                        isPlaying = isPlaying,
                        modifier = Modifier.width(bitChordHeroCardWidth(availableWidth)),
                        onClick = { onPlaySong(song, distinctSongs.indexOf(song)) },
                        onLongClick = { onSongLongClick(song) },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(BitChordShelfBottomSpacing))
}

@Composable
fun <T> BitChordShelf(
    items: List<T>,
    itemId: (T) -> String,
    itemContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = BitChordPageGutter),
        horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(items, key = itemId) { item -> itemContent(item) }
    }
}

fun Song.artistLine(): String = artists.joinToString { it.name }
