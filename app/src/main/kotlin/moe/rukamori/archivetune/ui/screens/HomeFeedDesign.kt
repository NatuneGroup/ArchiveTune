/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import android.os.Build
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.constants.CropThumbnailToSquareKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.AlbumMenu
import moe.rukamori.archivetune.ui.menu.ArtistMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubeArtistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.utils.preferredThumbnailRatio
import moe.rukamori.archivetune.utils.rememberPreference
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

// ============================================================================
// BitChord home-feed design system (ported from kushagrasinghx/BitChord).
//
// The metrics below are the single source of truth for the home page's visual
// language: the left/right gutter every shelf aligns to, the width of a
// compact shelf card, the hero card's share-of-row and ceiling, and the
// vertical breathing room a shelf leaves below itself. Everything the feed
// renders — the real cards and the skeletons that stand in for them — reads
// these so nothing jumps when data lands.
// ============================================================================

/** The left and right inset every home page's content sits at (BitChord PAGE_GUTTER). */
val HomeFeedGutter = 10.dp

/** Width of a card in the compact carousels (BitChord SHELF_CARD_WIDTH). */
val HomeShelfCardWidth = 150.dp

/** Corner radius of a compact shelf card. */
val HomeShelfCardCorner = 12.dp

/** Horizontal gap between cards in a shelf row (BitChord: 14dp). */
val HomeShelfCardSpacing = 14.dp

/** Vertical gap between the end of one shelf and the header of the next (BitChord: 26dp). */
val HomeShelfBottomSpacing = 26.dp

/** Share of the row a lead-shelf hero card takes, so the next one peeks in past it. */
private const val HomeHeroCardFraction = 0.70f

/** How wide a hero card is ever allowed to get (tablet ceiling). */
private val HomeHeroCardMaxWidth = 320.dp

/** A hero card's proportions: a touch taller than it is wide. */
const val HomeHeroCardRatio = 0.92f

/** Corner radius of a hero card. */
val HomeHeroCardCorner = 18.dp

/**
 * How wide a hero card should be in a row [available] wide — the shared answer
 * for the real shelf and for the skeleton that stands in for it, which have to
 * agree to the pixel or the feed jumps when the data lands.
 */
fun homeHeroCardWidth(available: Dp): Dp = minOf(available * HomeHeroCardFraction, HomeHeroCardMaxWidth)

/**
 * The hairline border every thumbnail carries (BitChord thumbnailBorder):
 * 1dp at 15% alpha, white on dark themes and black on light ones, clipped to
 * the artwork's shape. Keeps pale sleeves from dissolving into the background
 * (and dark ones into a dark theme).
 */
fun Modifier.homeFeedThumbnailBorder(shape: Shape): Modifier =
    composed {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        then(
            Modifier.border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
                shape = shape,
            ),
        )
    }

/**
 * Shared HazeState for the home page's top progressive blur (BitChord
 * TopFadeBlur). MainActivity provides the instance and renders the blurred
 * strip inside the top bar for the Home route; HomeScreen tags its own root
 * Box as the blur's source. A CompositionLocal is used because the bar and the
 * feed live in different parts of the tree (Scaffold.topBar vs NavHost
 * content) and must share the exact same state object.
 */
val LocalHomeHazeState = compositionLocalOf<HazeState?> { null }

// ============================================================================
// Shimmer skeletons (ported from BitChord Skeletons.kt). Grey stand-ins for
// content still on the wire, laid out to the same metrics as the real rows
// and cards so nothing jumps when the data lands.
// ============================================================================

/** How long one highlight sweep takes to cross a placeholder. */
private const val HomeShimmerPeriodMs = 1400

private val HomeBlockShape = RoundedCornerShape(6.dp)
private val HomeLineShape = RoundedCornerShape(4.dp)

// Ragged widths, so a run of rows reads as text rather than as a barcode.
private val HomeTitleWidths = listOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val HomeSubtitleWidths = listOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/**
 * One placeholder block, with a highlight sweeping across it. The sweep is
 * read inside the draw block rather than the composable body: a screenful of
 * these would otherwise recompose on every animation frame, and all any of
 * them needs per frame is a fresh gradient.
 */
@Composable
fun HomeShimmerBox(modifier: Modifier = Modifier, shape: Shape = HomeBlockShape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight =
        MaterialTheme.colorScheme.onSurfaceVariant
            .copy(alpha = 0.16f)
            .compositeOver(base)
    val sweep =
        rememberInfiniteTransition(label = "home_skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(HomeShimmerPeriodMs, easing = LinearEasing)),
            label = "home_sweep",
        )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                // The band travels from fully off one edge to fully off the other, which
                // leaves a beat of flat grey between passes rather than a highlight
                // permanently parked somewhere on the block.
                val band = size.width * 0.5f
                val startX = -band + sweep.value * (size.width + band * 2)
                val brush =
                    Brush.horizontalGradient(
                        colors = listOf(base, highlight, base),
                        startX = startX,
                        endX = startX + band,
                    )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** A placeholder for one line of text, sized as a fraction of its parent. */
@Composable
private fun HomeSkeletonLine(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    HomeShimmerBox(
        modifier = modifier.fillMaxWidth(fraction).height(height),
        shape = HomeLineShape,
    )
}

/** Stands in for a section heading. */
@Composable
private fun HomeSectionHeaderSkeleton(index: Int = 0) {
    Column(Modifier.padding(horizontal = HomeFeedGutter, vertical = 10.dp)) {
        HomeSkeletonLine(fraction = HomeTitleWidths[index % HomeTitleWidths.size] * 0.7f, height = 18.dp)
    }
}

/**
 * The lead shelf skeleton: near-page-width cards. Built on a [LazyRow] with
 * scrolling off rather than a plain [Row], so a card running past the right
 * edge is measured and clipped exactly as the real shelf's is.
 */
@Composable
private fun HomeHeroShelfSkeleton() {
    Column(Modifier.padding(bottom = HomeShelfBottomSpacing)) {
        HomeSectionHeaderSkeleton()
        BoxWithConstraints {
            val cardWidth = homeHeroCardWidth(maxWidth)
            LazyRow(
                contentPadding = PaddingValues(horizontal = HomeFeedGutter),
                horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                userScrollEnabled = false,
            ) {
                items(2) {
                    HomeShimmerBox(
                        modifier =
                            Modifier
                                .width(cardWidth)
                                .aspectRatio(HomeHeroCardRatio),
                        shape = RoundedCornerShape(HomeHeroCardCorner),
                    )
                }
            }
        }
    }
}

/** The compact carousel of square cards used by every shelf below the first. */
@Composable
fun HomeShelfSkeleton(index: Int = 0) {
    Column(Modifier.padding(bottom = HomeShelfBottomSpacing)) {
        HomeSectionHeaderSkeleton(index = index)
        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeFeedGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
            userScrollEnabled = false,
        ) {
            items(3) { card ->
                Column(Modifier.width(HomeShelfCardWidth)) {
                    HomeShimmerBox(
                        modifier =
                            Modifier
                                .width(HomeShelfCardWidth)
                                .aspectRatio(1f),
                        shape = RoundedCornerShape(HomeShelfCardCorner),
                    )
                    Spacer(Modifier.height(10.dp))
                    HomeSkeletonLine(
                        fraction = HomeTitleWidths[card % HomeTitleWidths.size],
                        height = 13.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    HomeSkeletonLine(
                        fraction = HomeSubtitleWidths[card % HomeSubtitleWidths.size],
                        height = 11.dp,
                    )
                }
            }
        }
    }
}

/** Home while the first page of shelves is still loading. */
fun LazyListScope.homeFeedSkeleton(shelves: Int = 3) {
    item(key = "home_skeleton_hero") { HomeHeroShelfSkeleton() }
    items(shelves - 1, key = { "home_skeleton_shelf_$it" }) { index ->
        HomeShelfSkeleton(index = index + 1)
    }
}

/** Appended to the feed while a further page of shelves is on its way. */
fun LazyListScope.homeFeedMoreSkeleton() {
    item(key = "home_skeleton_more") { HomeShelfSkeleton() }
}

// ============================================================================
// Section header — the BitChord "Shelf" heading: a heavy title with an
// optional second line, typography-led rather than chrome-led.
// ============================================================================

/**
 * BitChord-style section header: title at BitChord's headlineMedium scale (22sp, W700, tight
 * tracking) with an optional subtitle below at the card title scale, sitting at the page gutter
 * with 10dp of vertical padding.
 */
@Composable
fun HomeFeedSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    thumbnail: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .let { m -> if (onClick != null) m.headerClickable(onClick) else m }
                .padding(horizontal = HomeFeedGutter, vertical = 10.dp),
    ) {
        leadingIcon?.invoke()
        thumbnail?.invoke()
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = (-0.2).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ============================================================================
// Cards
// ============================================================================

private fun Modifier.headerClickable(onClick: () -> Unit): Modifier =
    composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }

/**
 * The compact card every non-hero shelf renders (BitChord ShelfCard):
 * artwork with the hairline border, the title one line below, the subtitle
 * under that. Interactions (tap to open / play, hold for the menu) and the
 * active-track visuals come from the fork's existing components.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedShelfCard(
    thumbnailUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    isCircular: Boolean = false,
    thumbnailAspectRatio: Float = 1f,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = if (isCircular) CircleShape else RoundedCornerShape(HomeShelfCardCorner)
    // A frame that is not square — a video still, a podcast episode — keeps its own proportions
    // and loses the hairline border: that border exists to pull a square sleeve off the page, and
    // on a wide frame it would draw a line through the middle of the picture instead.
    val isWideFrame = !isCircular && kotlin.math.abs(thumbnailAspectRatio - 1f) > 0.001f
    Column(
        modifier =
            modifier
                .width(HomeShelfCardWidth)
                .let { m ->
                    if (onLongClick != null) {
                        m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        m.combinedClickable(onClick = onClick)
                    }
                },
    ) {
        // The artwork box keeps the square height whatever frame goes in it, so a shelf that mixes
        // a wide still with square sleeves stays one row high with one title baseline, and the
        // skeleton standing in for it — which cannot know a frame's proportions before the data
        // lands — does not have to move anything when the data does. The frame sits centred in
        // the box; the dead space either side of a wide one is the cost of a single row height.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(HomeShelfCardWidth),
            contentAlignment = Alignment.Center,
        ) {
            ItemThumbnail(
                thumbnailUrl = thumbnailUrl,
                isActive = isActive,
                isPlaying = isPlaying,
                shape = shape,
                thumbnailRatio = thumbnailAspectRatio,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(thumbnailAspectRatio)
                        .let { m -> if (isWideFrame) m else m.homeFeedThumbnailBorder(shape) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = (-0.2).sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            trailing?.invoke()
        }
        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The big card the lead shelf pages sideways (BitChord HeroCard): artwork with
 * the caption laid over a scrim, as on Listen Now. The whole card toggles
 * play/pause when the track is the active one, matching the fork's hero
 * behaviour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedHeroCard(
    thumbnailUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(HomeHeroCardRatio)
                .clip(RoundedCornerShape(HomeHeroCardCorner))
                .homeFeedThumbnailBorder(RoundedCornerShape(HomeHeroCardCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let { m ->
                    if (onLongClick != null) {
                        m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        m.combinedClickable(onClick = onClick)
                    }
                },
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
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
                letterSpacing = (-0.3).sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Active / playing indicator — small dot at top-right (kept from the
        // fork's hero so the current track stays findable at a glance).
        if (isActive) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
            )
        }
    }
}

// ============================================================================
// Typed cards — map the fork's data types onto the generic cards above while
// preserving every existing interaction (section queue playback, navigation,
// long-press menus).
// ============================================================================

/** A compact shelf card for a local [Song]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedSongCard(
    song: Song,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    onPlayFromSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = song.id == mediaMetadata?.id
    HomeFeedShelfCard(
        thumbnailUrl = song.song.thumbnailUrl,
        title = song.song.title,
        subtitle = song.artists.joinToString { it.name },
        isActive = isActive,
        isPlaying = isPlaying,
        onClick = {
            if (isActive) {
                playerConnection.player.togglePlayPause()
            } else {
                onPlayFromSection()
            }
        },
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                SongMenu(
                    originalSong = song,
                    navController = navController,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        modifier = modifier,
    )
}

/** A compact shelf card for a remote YouTube item (song/album/artist/playlist/episode). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedYTItemCard(
    item: YTItem,
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    onPlaySongFromSection: (String) -> Unit = {},
    onPlayEpisode: ((EpisodeItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
) {
    val subtitle =
        when (item) {
            is SongItem -> item.artists.joinToString { it.name }
            is AlbumItem -> item.artists?.joinToString { it.name } ?: item.year?.toString().orEmpty()
            is ArtistItem -> item.subscriberCountText.orEmpty()
            is PlaylistItem -> item.songCountText.orEmpty()
            is PodcastItem -> item.author?.name.orEmpty()
            is EpisodeItem -> listOfNotNull(item.podcast?.name, item.dateText, item.durationText).joinToString(" • ")
        }

    val (cropThumbnailToSquare, _) = rememberPreference(CropThumbnailToSquareKey, false)
    val resolvedThumbnailRatio = item.preferredThumbnailRatio(cropThumbnailToSquare)
    // A sheet for either would come up empty, so neither card has a hold.
    val hasMenu = item !is PodcastItem && item !is EpisodeItem
    val longClickHandler: (() -> Unit)? =
        if (hasMenu) {
            {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menuState.show {
                    when (item) {
                        is SongItem ->
                            YouTubeSongMenu(
                                song = item,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )

                        is AlbumItem ->
                            YouTubeAlbumMenu(
                                albumItem = item,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )

                        is ArtistItem ->
                            YouTubeArtistMenu(
                                artist = item,
                                onDismiss = menuState::dismiss,
                            )

                        is PlaylistItem ->
                            YouTubePlaylistMenu(
                                playlist = item,
                                coroutineScope = scope,
                                onDismiss = menuState::dismiss,
                            )
                    }
                }
            }
        } else {
            null
        }
    HomeFeedShelfCard(
        thumbnailUrl = item.thumbnail,
        title = item.title,
        subtitle = subtitle,
        isCircular = item is ArtistItem,
        isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
        isPlaying = isPlaying,
        thumbnailAspectRatio = resolvedThumbnailRatio,
        onClick = {
            when (item) {
                is SongItem -> onPlaySongFromSection(item.id)
                is AlbumItem -> navController.navigate("album/${item.id}")
                is ArtistItem -> navController.navigate("artist/${item.id}")
                is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                is PodcastItem -> navController.navigate("podcast/${Uri.encode(item.browseId)}")
                is EpisodeItem -> onPlayEpisode?.invoke(item)
            }
        },
        onLongClick = longClickHandler,
        modifier = modifier,
    )
}

/** A compact shelf card for a library [LocalItem] (song/album/artist). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedLocalItemCard(
    item: LocalItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    onPlaySongFromSection: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (item) {
        is Song ->
            HomeFeedSongCard(
                song = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                onPlayFromSection = { onPlaySongFromSection(item.id) },
                modifier = modifier,
            )

        is Album ->
            HomeFeedShelfCard(
                thumbnailUrl = item.album.thumbnailUrl,
                title = item.album.title,
                subtitle = item.artists.joinToString { it.name },
                isActive = item.id == mediaMetadata?.album?.id,
                isPlaying = isPlaying,
                onClick = { navController.navigate("album/${item.id}") },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        AlbumMenu(
                            originalAlbum = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                modifier = modifier,
            )

        is Artist ->
            HomeFeedShelfCard(
                thumbnailUrl = item.artist.thumbnailUrl,
                title = item.artist.name,
                subtitle = "",
                isCircular = true,
                onClick = { navController.navigate("artist/${item.id}") },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        ArtistMenu(
                            originalArtist = item,
                            coroutineScope = scope,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                modifier = modifier,
            )

        is Playlist -> Unit
    }
}

// ============================================================================
// Pull-to-refresh — BitChord behaviour: the usual circular puck suppressed,
// the drag feedback instead being a loader line along the bottom edge of the
// top bar. The line fills left-to-right as the pull approaches the threshold,
// then sweeps indefinitely once the refresh is away, so the two phases read
// as one continuous gesture.
// ============================================================================

private val HomeRefreshLineHeight = 2.5.dp

/**
 * The refresh indicator line, tracking the drag on [distanceFraction] and
 * sweeping while [refreshing]. Position it at the bottom edge of the top bar.
 */
@Composable
fun HomePullRefreshLine(
    refreshing: Boolean,
    distanceFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = refreshing || distanceFraction() > 0.01f,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val lineModifier =
            Modifier
                .fillMaxWidth()
                .height(HomeRefreshLineHeight)
        if (refreshing) {
            LinearProgressIndicator(
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { distanceFraction().coerceIn(0f, 1f) },
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

// ============================================================================
// TopFadeBlur — the glass behind the home top bar (BitChord TopFadeBlur).
// Full blur along the top edge, ramping to nothing on the way down: a bar
// carrying a uniform pane is a rectangle sitting on the page, and its bottom
// edge is a line drawn across whatever scrolls under it. Fading out instead
// leaves the title and actions something to be legible against and the page
// nothing to be interrupted by.
// ============================================================================

/**
 * The run the fade needs below the bar to get from full blur to none without
 * the eye finding where it got there. Shortened from BitChord's 120 to 88:
 * the page's first heading sits a fixed distance down the screen, well
 * inside this run, and over 120dp the ramp still had a tenth of its blur
 * left there. The tail is what hides the layer's end, so it cannot be cut;
 * 88 is as short as it goes before the ramp starts to be findable.
 */
private val HomeTopFadeRun = 88.dp

/** How much blur the fade reaches at its outer edge — short of all of it. */
private const val HomeTopFadePeak = 0.75f

/**
 * How dark the readability scrim starts, at the very top of the strip.
 * Modest on purpose: it is there to give the glyphs a floor on a pale sleeve,
 * not to grey out the artwork.
 */
private const val HomeTopScrimPeak = 0.42f

/** Enough stops that the ramp does not band across a near-flat colour. */
private const val HomeTopScrimStops = 12

/**
 * The progressive top-fade blur behind the home top bar. Keyed to the colour
 * of the page underneath rather than the theme's background: both halves of
 * the material are flat colour, and wherever the blur has least to say, that
 * flat colour is most of what is left. The scrim is laid *over* the blur
 * (the only order that works: haze samples the content tagged as its source,
 * so a scrim underneath would be painted over by the blurred content).
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun HomeTopFadeBlur(
    hazeState: HazeState,
    pageColor: Color,
    barHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val height = barHeight + HomeTopFadeRun
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .hazeEffect(
                    state = hazeState,
                    // Keyed to the colour of the page underneath, not the theme's.
                    style = HazeMaterials.ultraThin(pageColor),
                ) {
                    // Cubic rather than haze's quadratic, and eased out rather than
                    // in: the ramp falls away quickly under the bar and then spends
                    // the rest of its run near nothing, which is what hides where
                    // the layer ends.
                    progressive =
                        HazeProgressive.verticalGradient(
                            easing = EaseOutCubic,
                            startIntensity = HomeTopFadePeak,
                            endIntensity = 0f,
                        )
                    // Uniform across the layer, so it would show as texture over
                    // the untouched foot of the ramp — the edge being hidden.
                    noiseFactor = 0f
                },
    )
    val scrim =
        remember(pageColor) {
            Brush.verticalGradient(
                // The same eased-out shape as the blur above it, so the two arrive
                // at nothing together. A scrim that outlasted the blur would leave
                // a tinted band hanging below a fade that had already finished.
                colorStops =
                    Array(HomeTopScrimStops) { i ->
                        val t = i / (HomeTopScrimStops - 1f)
                        t to pageColor.copy(alpha = HomeTopScrimPeak * (1f - EaseOutCubic.transform(t)))
                    },
            )
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(scrim),
    )
}

/**
 * The Home route's backdrop: three soft colour pools over a base the theme owns, so the page
 * reads as a place rather than as a flat sheet the feed happens to sit on. Drawn full-bleed behind
 * everything, loading and empty states included, and only where the blur it sits under is wanted —
 * it is atmosphere for the glass, and on its own it would just be a tinted page.
 */
@Composable
fun HomeAtmosphereBackground(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    // The page base is drawn from the theme rather than being a colour of its own, so the top-fade
    // scrim — keyed to the colour of the page underneath it — keeps matching, and a pure-black
    // page has nothing to mismatch with either.
    val base =
        if (dark && surface != Color.Black) {
            MaterialTheme.colorScheme.surfaceContainerLowest
        } else {
            surface
        }
    val glow = if (dark) 0.17f else 0.12f
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val violet = Color(0xFF7B4DFF)
                    val teal = Color(0xFF00B8A9)
                    val blue = Color(0xFF2E6BFF)
                    val topWash =
                        if (dark) {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.22f,
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.16f,
                            )
                        }
                    val violetBrush =
                        Brush.radialGradient(
                            colors = listOf(violet.copy(alpha = glow), Color.Transparent),
                            center = Offset(w * 0.12f, h * 0.10f),
                            radius = w * 0.62f,
                        )
                    val tealBrush =
                        Brush.radialGradient(
                            colors = listOf(teal.copy(alpha = glow * 0.8f), Color.Transparent),
                            center = Offset(w * 0.98f, h * 0.30f),
                            radius = w * 0.55f,
                        )
                    val blueBrush =
                        Brush.radialGradient(
                            colors = listOf(blue.copy(alpha = glow * 0.85f), Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.92f),
                            radius = w * 0.70f,
                        )
                    val bottomShade =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.30f else 0.05f)),
                            startY = h * 0.55f,
                            endY = h,
                        )
                    onDrawBehind {
                        drawRect(violetBrush)
                        drawRect(tealBrush)
                        drawRect(blueBrush)
                        drawRect(bottomShade)
                        drawRect(topWash)
                    }
                },
    )
}

// ============================================================================
// Shared glass screen-header haze (used by GlassScreenHeader.kt). Delegates to
// HomeTopFadeBlur: ultraThin material keyed to the page colour, progressive
// fade plus readability scrim. Early-returns unless liquid glass is on and
// the RuntimeShader stack exists (Android 12+), so with glass OFF nothing is
// composed and rendering is unchanged.
// ============================================================================

/** Header bar height the haze fades below (status-bar inset sits above it). */
val ScreenHeaderHazeBarZone = 64.dp

@Composable
fun rememberScreenHeaderHaze(): HazeState = remember { HazeState() }

@Composable
fun ScreenHeaderHaze(
    hazeState: HazeState,
    systemBarsTopPadding: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!enabled) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    if (!liquidGlassEnabled) return
    HomeTopFadeBlur(
        hazeState = hazeState,
        pageColor = MaterialTheme.colorScheme.surface,
        barHeight = systemBarsTopPadding + ScreenHeaderHazeBarZone,
        modifier = modifier,
    )
}
