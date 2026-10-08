/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * TikTok player style — the action rail.
 *
 * The vertical stack of circular actions riding the right edge of the media,
 * in TikTok's order: the artist's avatar (with the small follow button the
 * app's existing subscribe feature backs), like, comment (here: lyrics),
 * bookmark (here: add to playlist), share, more. Every action
 * operates on THIS page's song — the feed's pages are real queue entries, so
 * the rail acts straight on the same Room rows, download manager and menus
 * the rest of the app uses.
 *
 * While the inline lyrics pane owns the page, EVERY rail action — profile,
 * like, the lyrics toggle, bookmark, share, the overflow more — fades out
 * and the rail leaves the page to the lyrics (user reports 2026-09-02:
 * "when I open lyrics the like button, share, profile, overflow icon etc
 * only should hide", then "the lyrics icon should disable too"). The
 * pane's own controls live in the caption row instead: a close (X) chip
 * and the horizontal-dots overflow button right of the queue chip (see
 * TikTokSongInfo) — the reference's inline caption actions.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalSyncUtils
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.formatCompactCount
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.shareLocalAudio
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** TikTok's brand red, the same one the reference feed uses for its active heart. */
internal val TIKTOK_RED = Color(0xFFFE2C55)

/**
 * The rail's fade for the lyrics-open hide: quick and unadorned, the
 * reference's vocabulary for things that leave.
 */
private const val TIKTOK_RAIL_FADE_MS = 180

@Composable
internal fun TikTokRail(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    playerConnection: PlayerConnection,
    sheetState: BottomSheetState,
    lyricsActive: Boolean,
    onToggleLyrics: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenLyricsMenu: () -> Unit,
    navController: NavController,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val database = LocalDatabase.current

    // This page's song row — liked state and format come from the same Room
    // row the rest of the app reads; there is no separate feed-side state.
    val librarySong by database.song(pageMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val isLocal = librarySong?.song?.isLocal == true

    // The page's one like action, shared with the artwork's double-tap —
    // see rememberTikTokLikeAction below.
    val likeAction =
        rememberTikTokLikeAction(
            pageMetadata = pageMetadata,
            isCurrentPage = isCurrentPage,
            playerConnection = playerConnection,
        )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .padding(end = 10.dp)
                .padding(vertical = 6.dp),
    ) {
        // ── The artist's avatar (TikTok's profile picture) ──
        // Tap opens the artist page (the app's real destination, the player
        // collapsing first exactly like other in-player links); the small
        // badge on its rim is the app's existing subscribe feature. Sized
        // at ~1.3x the rail icons — the reference's avatar:icon ratio — not
        // the oversized 1.7x it used to be. Fades out while the lyrics pane
        // is open ("profile ... should hide"), spacer and all, so no orphan
        // gap survives it.
        AnimatedVisibility(
            visible = !lyricsActive,
            enter = fadeIn(tween(TIKTOK_RAIL_FADE_MS)),
            exit = fadeOut(tween(TIKTOK_RAIL_FADE_MS)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TikTokArtistAvatar(
                    pageMetadata = pageMetadata,
                    sheetState = sheetState,
                    navController = navController,
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        // ── Like ──
        // The heart acts on THIS page's song, not on whatever is playing —
        // the shared action walks the same Room + sync path the song menu
        // uses, and pops its glyph whenever the row flips to liked, whether
        // that came from this button or a double-tap on the media. Hides
        // with the rest of the song actions while the lyrics pane is open.
        val liked = librarySong?.song?.liked == true
        AnimatedVisibility(
            visible = !lyricsActive,
            enter = fadeIn(tween(TIKTOK_RAIL_FADE_MS)),
            exit = fadeOut(tween(TIKTOK_RAIL_FADE_MS)),
        ) {
            TikTokLikeRailButton(
                liked = liked,
                onLike = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    likeAction(false)
                },
            )
        }

        // ── Lyrics (TikTok's comment bubble) ──
        // Toggles the Apple Music inline lyrics pane in place of the
        // artwork; the red accent while open is the rail's own active colour
        // (the same one the liked heart uses). Hides with every other rail
        // action while the pane is open (user request 2026-09-02: "when I
        // click on lyrics icon the lyrics icon should disable too") — the
        // pane closes from the caption row's X instead, and reopens from
        // this bubble the moment the pane is closed.
        AnimatedVisibility(
            visible = !lyricsActive,
            enter = fadeIn(tween(TIKTOK_RAIL_FADE_MS)),
            exit = fadeOut(tween(TIKTOK_RAIL_FADE_MS)),
        ) {
            TikTokRailButton(
                iconRes = R.drawable.solar_chat_round_linear,
                contentDescription = stringResource(R.string.lyrics),
                tint = if (lyricsActive) TIKTOK_RED else Color.White,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggleLyrics()
            }
        }

        // ── Add to playlist (TikTok's bookmark) ──
        // Hides with the other song actions while the lyrics pane is open
        // ("... etc only should hide").
        AnimatedVisibility(
            visible = !lyricsActive,
            enter = fadeIn(tween(TIKTOK_RAIL_FADE_MS)),
            exit = fadeOut(tween(TIKTOK_RAIL_FADE_MS)),
        ) {
            TikTokRailButton(
                iconRes = R.drawable.solar_bookmark_linear,
                contentDescription = stringResource(R.string.add_to_playlist),
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onAddToPlaylist()
            }
        }

        // ── Download ──
        // Removed from the rail per user request (2026-09-02: "remove the
        // download icon"). The song menu — the rail's more button, the queue
        // sheet, the library — still carries the full download/remove action
        // for whoever needs it.

        // ── Share ──
        // Hides with the other song actions while the lyrics pane is open
        // ("share ... only should hide").
        AnimatedVisibility(
            visible = !lyricsActive,
            enter = fadeIn(tween(TIKTOK_RAIL_FADE_MS)),
            exit = fadeOut(tween(TIKTOK_RAIL_FADE_MS)),
        ) {
            TikTokRailButton(
                iconRes = R.drawable.solar_share_linear,
                contentDescription = stringResource(R.string.share),
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (isLocal) {
                    val mimeType = librarySong?.format?.mimeType
                    shareLocalAudio(context, pageMetadata.id, mimeType)
                } else {
                    val url = "https://music.youtube.com/watch?v=${pageMetadata.id}"
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                            putExtra(Intent.EXTRA_TITLE, pageMetadata.title)
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                }
            }
        }

        // ── More ── While the inline lyrics pane owns the page, this opens the LYRICS overflow
        // menu — the same anchored, frosted-blur popup the Apple Music style shows from its own
        // lyrics view, opening from the top-right below the top navigation (user reports
        // 2026-09-02: "the exact same popup for lyrics overflow menu from Apple music style; also
        // it opens on the downside.
        AnimatedVisibility(
            visible = !lyricsActive,
            enter = fadeIn(tween(TIKTOK_RAIL_FADE_MS)),
            exit = fadeOut(tween(TIKTOK_RAIL_FADE_MS)),
        ) {
            TikTokRailButton(
                iconRes = R.drawable.solar_more_vert_linear,
                contentDescription = stringResource(R.string.more),
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (lyricsActive) {
                    onOpenLyricsMenu()
                } else {
                    menuState.show {
                        PlayerMenu(
                            mediaMetadata = pageMetadata,
                            navController = navController,
                            playerBottomSheetState = sheetState,
                            onShowDetailsDialog = {
                                bottomSheetPageState.show { ShowMediaInfo(pageMetadata.id) }
                            },
                            onDismiss = menuState::dismiss,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The artist's avatar at the top of the rail, TikTok-style: a circular photo
 * with a light rim, and the small follow badge at its lower-right corner.
 * The badge is backed by the app's existing subscribe feature — the same
 * ArtistEntity toggle (and YouTube channel subscription) the artist page
 * uses; the avatar itself opens the artist page.
 */
@Composable
private fun TikTokArtistAvatar(
    pageMetadata: MediaMetadata,
    sheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val haptics = LocalHapticFeedback.current

    val artist = remember(pageMetadata.id) { pageMetadata.artists.firstOrNull() }
    val artistId = artist?.id
    // The artist's own photo when the metadata carries one, else the song
    // artwork — a feed page should never show an empty circle.
    val avatarUrl = artist?.thumbnailUrl ?: pageMetadata.thumbnailUrl

    // Subscribe state from the same Room row the artist page reads.
    val artistFlow =
        remember(artistId, database) {
            if (artistId != null) {
                database.artist(artistId)
            } else {
                flowOf<moe.rukamori.archivetune.db.entities.Artist?>(null)
            }
        }
    val libraryArtist by artistFlow.collectAsStateWithLifecycle(initialValue = null)
    val isSubscribed = libraryArtist?.artist?.bookmarkedAt != null

    Box(
        modifier =
            modifier
                .size(40.dp)
                .tiktokNoRippleClickable(
                    enabled = artistId != null,
                ) {
                    if (artistId == null) return@tiktokNoRippleClickable
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    sheetState.collapseSoft()
                    navController.navigate("artist/$artistId") {
                        launchSingleTop = true
                    }
                },
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = artist?.name,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.95f), CircleShape)
                    .shadow(elevation = 4.dp, shape = CircleShape, clip = false),
        )

        // The follow badge — only while NOT subscribed, like the reference's
        // red "+" that disappears once the follow lands. Tapping it subscribes
        // through the artist page's own toggle (Room + YouTube channel).
        if (!isSubscribed) {
            val subscribeLabel = stringResource(R.string.subscribe)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(18.dp)
                        .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(TIKTOK_RED)
                        .semantics { contentDescription = subscribeLabel }
                        .tiktokNoRippleClickable(enabled = artistId != null) {
                            if (artistId == null) return@tiktokNoRippleClickable
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val existing = libraryArtist?.artist
                            if (existing != null) {
                                database.query { update(existing.toggleLike()) }
                            } else if (artist != null) {
                                database.transaction {
                                    insert(
                                        ArtistEntity(
                                            id = artistId,
                                            name = artist.name,
                                            thumbnailUrl = artist.thumbnailUrl,
                                        ).toggleLike(),
                                    )
                                }
                            }
                        },
            ) {
                // The white plus, drawn rather than tinted so the badge reads
                // as one solid TikTok-red dot.
                Canvas(modifier = Modifier.size(9.dp)) {
                    val stroke = 1.6.dp.toPx()
                    val half = stroke / 2f
                    drawLine(
                        color = Color.White,
                        start = Offset(half, size.height / 2f),
                        end = Offset(size.width - half, size.height / 2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width / 2f, half),
                        end = Offset(size.width / 2f, size.height - half),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * The page's one like action — shared by the rail's heart (a plain toggle) and the artwork's
 * double-tap (like-only: a double-tap never unlikes, the reference's rule).
 */
@Composable
internal fun rememberTikTokLikeAction(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    playerConnection: PlayerConnection,
): (Boolean) -> Unit {
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Observes the same Room row the rail renders from; the delegated read
    // inside the remembered lambda stays live, so the like-only guard sees
    // the freshest row at call time without an extra query per tap.
    val librarySong by database.song(pageMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)
    return remember(database, syncUtils, context, scope, pageMetadata, isCurrentPage, playerConnection) {
        { likeOnly: Boolean ->
            val row = librarySong?.song
            when {
                // Already liked and this is a double-tap: stay liked.
                likeOnly && row?.liked == true -> Unit

                row != null -> scope.launch(Dispatchers.IO) {
                    val requestedSong =
                        try {
                            row.toggleLike()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            reportLikeFailure(error, "Failed to prepare liked song ${pageMetadata.id}", context)
                            return@launch
                        }
                    syncUtils.likeSong(requestedSong).onFailure { error ->
                        reportLikeFailure(error, "Failed to update liked song ${pageMetadata.id}", context)
                    }
                }

                isCurrentPage -> playerConnection.toggleLike()

                else -> {
                    scope.launch(Dispatchers.IO) {
                        val requestedSong =
                            try {
                                database.withTransaction {
                                    val currentSong =
                                        getSongById(pageMetadata.id)?.song
                                            ?: pageMetadata.toSongEntity().also { insert(pageMetadata) }
                                    currentSong.toggleLike()
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                reportLikeFailure(error, "Failed to prepare liked song ${pageMetadata.id}", context)
                                return@launch
                            }
                        syncUtils.likeSong(requestedSong).onFailure { error ->
                            reportLikeFailure(error, "Failed to update liked song ${pageMetadata.id}", context)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun reportLikeFailure(
    error: Throwable,
    message: String,
    context: Context,
) {
    Timber.w(error, message)
    withContext(Dispatchers.Main) {
        Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The rail's heart, with the reference's like pop: the glyph springs in from a small scale whenever
 * the song flips to liked — whether that came from this button or a double-tap on the media, since
 * both land in the same Room row this reads — and settles with a small shrink when unliked.
 */
@Composable
private fun TikTokLikeRailButton(
    liked: Boolean,
    onLike: () -> Unit,
) {
    val likeLabel = stringResource(R.string.action_like)
    val scale = remember { Animatable(1f) }
    // Seeded with the row's current state so (re)composing an already-liked
    // page never replays the pop — only a live false -> true transition does.
    var wasLiked by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked && !wasLiked) {
            scale.snapTo(0.2f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
            )
        } else if (!liked && wasLiked) {
            scale.snapTo(0.85f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            )
        }
        wasLiked = liked
    }
    Box(
        modifier =
            Modifier
                .width(48.dp)
                .tiktokNoRippleClickable(onClick = onLike),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(30.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
            ) {
                TikTokRailGlyph(
                    iconRes = if (liked) R.drawable.solar_heart_bold else R.drawable.solar_heart_linear,
                    contentDescription = likeLabel,
                    tint = if (liked) TIKTOK_RED else Color.White,
                )
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

/**
 * The TikTok-style count label under a rail icon: small white text with the
 * same drop-shadow treatment as [TikTokRailGlyph] (a blurred, slightly
 * offset dark copy behind the crisp one), so the label stays legible over
 * light artwork exactly like the glyphs do.
 */
@Composable
private fun TikTokRailCountLabel(label: String) {
    Box {
        Text(
            text = label,
            color = Color.Black.copy(alpha = 0.35f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset(y = 1.dp)
                    .blur(2.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * One rail action: a plain glyph, no pills, no glass — the 48dp touch target
 * meets accessibility guidance.
 */
@Composable
private fun TikTokRailButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    TikTokRailActionButton(onClick = onClick) {
        TikTokRailGlyph(
            iconRes = iconRes,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

/**
 * One rail glyph with its own drop shadow: a blurred black copy of the icon offset a hair down,
 * behind the crisp one.
 */
@Composable
private fun TikTokRailGlyph(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
) {
    Box(modifier = Modifier.size(30.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.35f),
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset(y = 1.dp)
                    .blur(2.dp),
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A rail action with arbitrary content (the like heart's animated glyph). */
@Composable
private fun TikTokRailActionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .tiktokNoRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
    Spacer(Modifier.height(2.dp))
}
