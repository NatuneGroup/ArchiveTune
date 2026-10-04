/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — one feed page.
 *
 * A page is the full-bleed treatment TikTok gives a post: a mesh-gradient
 * backdrop blended from the artwork's own palette (the Bitchord player
 * style's blend, ported — see TikTokMeshBackdrop.kt), the sharp artwork as
 * the hero in the middle (tapping it toggles playback, TikTok's
 * tap-to-pause; its edges dissolve into the backdrop so the cover never
 * meets the colours it was sampled from in a hard line), the action rail
 * pinned to the lower right of it, and the track's identity pinned along
 * the bottom. Everything is edge-to-edge; legibility comes from the scrim,
 * not from panels.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.TikTokMainLyricsEnabledKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.ui.player.rememberSeekSkip
import moe.rukamori.archivetune.utils.rememberPreference

/** The inactive gray the reference uses for everything unselected/secondary. */
internal val TIKTOK_INACTIVE_GRAY = Color(0xFFA9A9B2)

/**
 * Fixed height of the caption row (the queue chip / lyrics X + dots row) —
 * constant across every state so the info block never resizes when the
 * lyrics controls appear or disappear (which used to visibly shift the
 * artwork). See TikTokSongInfo.
 */
internal val TIKTOK_CAPTION_ROW_HEIGHT = 40.dp

/**
 * End padding for the title / secondary text so neither ever runs under the
 * action rail, which now hangs down alongside the caption block (see the
 * page-level TikTokRail in TikTokSongPage). Matches the reference's
 * constrained caption width.
 */
internal val TIKTOK_CAPTION_TEXT_CLEARANCE = 56.dp

/** One page of the feed. */
@Composable
internal fun TikTokSongPage(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    // While the stream is loading or the feed is playing an engine-initiated
    // swipe, the current page is not "paused" — buffering and auto-advance
    // must not flash the play glyph (user report 2026-09-02: "when the song
    // ends it pauses").
    suppressPauseOverlay: Boolean,
    playerConnection: PlayerConnection,
    queueTitle: String?,
    immersive: Boolean,
    lyricsOpen: Boolean,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    topChromeHeight: Dp,
    bottomChromeHeight: Dp,
    sheetState: BottomSheetState,
    onAddToPlaylist: () -> Unit,
    onToggleLyrics: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onQueueClick: () -> Unit,
    onOpenLyricsMenu: () -> Unit,
    onLyricsOverflowAnchorChange: (Rect) -> Unit,
    navController: NavController,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
) {
    val haptics = LocalHapticFeedback.current
    val seekSkip = rememberSeekSkip(playerConnection)
    val layoutDirection = LocalLayoutDirection.current
    val likeAction =
        rememberTikTokLikeAction(
            pageMetadata = pageMetadata,
            isCurrentPage = isCurrentPage,
            playerConnection = playerConnection,
        )
    // Live hearts for double-taps: each burst removes itself when its own
    // animation finishes, so rapid double-taps stack like the reference's.
    val heartBursts = remember { mutableStateListOf<TikTokHeartBurst>() }
    var nextHeartBurstId by remember { mutableStateOf(0L) }
    // In immersive mode the page keeps only the notch-safe top inset (the
    // chrome is hidden), so the artwork grows but never underlaps the notch
    // even while the status bar is hidden. The stable inset floors against
    // the display cutout, which stays non-zero regardless of bar visibility.
    val stableTopInset = LocalStableSystemBarsTopPadding.current

    // One URL for the page's artwork, pre-sized so the sharp hero decodes at
    // full resolution. The mesh backdrop pulls its palette from the same URL
    // at a thumbnail decode size (it only ever needs colours), so Coil keeps
    // the two requests as separate cache entries — the hero stays crisp, the
    // palette stays cheap, and pages returning to the feed hit the cache
    // both ways.
    val artUrl =
        remember(pageMetadata.id, pageMetadata.thumbnailUrl) {
            pageMetadata.thumbnailUrl?.resize(
                width = TIKTOK_ART_PX,
                height = TIKTOK_ART_PX,
                maxresAllowed = true,
            )
        }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Backdrop ── The mesh field is NOT drawn here. It lives once, behind the whole pager
        // (see TikTokPlayerContent), and crossfades to the settled page's palette.
        Box(modifier = Modifier.fillMaxSize().tiktokScrim())

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(if (immersive) stableTopInset else topChromeHeight))

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // The largest square the middle zone can hold. On a tall phone
                // that is the page width; on a landscape or short screen it is
                // the zone height — either way the art never clips the rail or
                // the controls, and never letterboxes.
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val artSize = minOf(maxWidth, maxHeight)
                    val cornerRadius = if (artSize < maxWidth) 16.dp else 10.dp
                    val artworkScale by animateFloatAsState(
                        targetValue = if (immersive) 1.04f else 1f,
                        animationSpec = tween(250),
                        label = "tiktokArtworkScale",
                    )
                    // The Apple Music inline lyrics pane owns the middle zone
                    // while it is open on the playing page; every other page
                    // (and closing it) shows the artwork.
                    val showInlineLyrics = isCurrentPage && lyricsOpen

                    AnimatedContent(
                        targetState = showInlineLyrics,
                        transitionSpec = {
                            fadeIn(tween(240)) togetherWith fadeOut(tween(240))
                        },
                        contentAlignment = Alignment.Center,
                        label = "tiktokCoverOrLyrics",
                    ) { showLyrics ->
                        if (showLyrics) {
                            TikTokInlineLyricsPane(
                                sliderPositionProvider = sliderPositionProvider,
                                lyricsSyncOffset = lyricsSyncOffset,
                            )
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(artSize)
                                        .graphicsLayer {
                                            scaleX = artworkScale
                                            scaleY = artworkScale
                                        }
                                        // Blend the artwork's top and bottom edges into
                                        // the blurred backdrop behind it. The sharp art
                                        // and the backdrop are the same image, so an
                                        // alpha ramp at each edge reads as focus falling
                                        // off — without it the two renderings meet in a
                                        // hard straight line (user report 2026-09-02:
                                        // "straight lines / color inconsistency").
                                        .graphicsLayer {
                                            compositingStrategy =
                                                CompositingStrategy.Offscreen
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = TIKTOK_ART_EDGE_FADE,
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }.let { m ->
                                            if (isCurrentPage) {
                                                m.pointerInput(pageMetadata.id) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            haptics.performHapticFeedback(
                                                                HapticFeedbackType.TextHandleMove,
                                                            )
                                                            onTogglePlayPause()
                                                        },
                                                        onDoubleTap = { tap ->
                                                            val edgeWidth = size.width * 0.3f
                                                            val backward =
                                                                if (layoutDirection == LayoutDirection.Ltr) {
                                                                    tap.x < edgeWidth
                                                                } else {
                                                                    tap.x > size.width - edgeWidth
                                                                }
                                                            val forward =
                                                                if (layoutDirection == LayoutDirection.Ltr) {
                                                                    tap.x > size.width - edgeWidth
                                                                } else {
                                                                    tap.x < edgeWidth
                                                                }
                                                            if (backward || forward) {
                                                                haptics.performHapticFeedback(
                                                                    HapticFeedbackType.TextHandleMove,
                                                                )
                                                                seekSkip.skip(forward)
                                                            } else {
                                                                haptics.performHapticFeedback(
                                                                    HapticFeedbackType.LongPress,
                                                                )
                                                                likeAction(true)
                                                                heartBursts +=
                                                                    TikTokHeartBurst(
                                                                        id = nextHeartBurstId++,
                                                                        x = tap.x.toDp(),
                                                                        y = tap.y.toDp(),
                                                                    )
                                                            }
                                                        },
                                                    )
                                                }
                                            } else {
                                                m
                                            }
                                        },
                            ) {
                                AsyncImage(
                                    model =
                                        ImageRequest
                                            .Builder(context)
                                            .data(artUrl)
                                            .size(TIKTOK_ART_PX)
                                            .crossfade(true)
                                            .build(),
                                    contentDescription = pageMetadata.title,
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .shadow(
                                                elevation = 18.dp,
                                                shape = RoundedCornerShape(cornerRadius),
                                                clip = true,
                                            ),
                                )

                                // Paused affordance (current page only) — TikTok's
                                // translucent play glyph while a video is paused.
                                TikTokPausedOverlay(
                                    visible = isCurrentPage && !isPlaying && !suppressPauseOverlay,
                                )

                                // Double-tap hearts, spawned at the tap point.
                                heartBursts.forEach { burst ->
                                    key(burst.id) {
                                        TikTokHeartBurstView(
                                            burst = burst,
                                            onFinished = {
                                                heartBursts.removeAll { it.id == burst.id }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // The soft dark wash the reference keeps behind its action rail: over light
                    // artwork the rail's white glyphs would otherwise wash out.
                    if (!immersive && !showInlineLyrics) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(TIKTOK_RAIL_WASH_WIDTH)
                                    .graphicsLayer {
                                        compositingStrategy =
                                            CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        drawRect(brush = TIKTOK_RAIL_WASH)
                                        drawRect(
                                            brush = TIKTOK_RAIL_WASH_VERTICAL_FADE,
                                            blendMode = BlendMode.DstIn,
                                        )
                                    },
                        )
                    }
                }
            }

            // Reserve the karaoke-caption slot whenever the feature is on — on EVERY page, and
            // whether or not the current song has synced lyrics (they also load asynchronously).
            // The artwork box above therefore keeps a constant height and the artwork never
            // shifts up or shrinks when lyrics load, appear, change between songs, or during
            // swipes.
            val mainLyricsEnabled by rememberPreference(TikTokMainLyricsEnabledKey, false)
            if (!immersive && mainLyricsEnabled && !lyricsOpen) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(TikTokMainLyricsHeight)
                            .clipToBounds(),
                ) {
                    if (isCurrentPage) {
                        TikTokMainLyrics(
                            sliderPositionProvider = sliderPositionProvider,
                            lyricsSyncOffset = lyricsSyncOffset,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    // Mirror the title/artist clearance: the right-side rail
                                    // (~58dp of buttons, bottom-anchored and tall) must never
                                    // overlap or cut the wrapped lyric rows.
                                    .padding(start = 16.dp, end = TIKTOK_CAPTION_TEXT_CLEARANCE + 16.dp)
                                    .padding(bottom = 4.dp),
                        )
                    }
                }
            }

            // ── Bottom-left: the page's identity ──
            if (!immersive) {
                TikTokSongInfo(
                    pageMetadata = pageMetadata,
                    queueTitle = queueTitle,
                    onQueueClick = onQueueClick,
                    // The caption row carries the lyrics pane's own controls
                    // while it owns the page (the rail is fully hidden then):
                    // X closes the pane, the horizontal dots open the lyrics
                    // overflow popup anchored to themselves.
                    lyricsControlsVisible = isCurrentPage && lyricsOpen,
                    onCloseLyrics = onToggleLyrics,
                    onOpenLyricsMenu = onOpenLyricsMenu,
                    onLyricsOverflowAnchorChange = onLyricsOverflowAnchorChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(if (immersive) 0.dp else bottomChromeHeight))
        }

        // ── The action rail, hanging down alongside the song info ── A SIBLING of the whole Column
        // (page level) rather than a child of the media zone, so the rail's BOTTOM sits level with
        // the caption block's last line — the artist • album text (user request 2026-09-03: "Shift
        // the like button, profile and the other control icons down and align it with the name of
        // the album of the song and the name of the artist which is just below the name of the
        // song").
        if (!immersive) {
            TikTokRail(
                pageMetadata = pageMetadata,
                isCurrentPage = isCurrentPage,
                playerConnection = playerConnection,
                sheetState = sheetState,
                lyricsActive = isCurrentPage && lyricsOpen,
                onToggleLyrics = onToggleLyrics,
                onAddToPlaylist = onAddToPlaylist,
                onOpenLyricsMenu = onOpenLyricsMenu,
                navController = navController,
                menuState = menuState,
                bottomSheetPageState = bottomSheetPageState,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = bottomChromeHeight + 8.dp),
            )
        }
    }
}

/** TikTok's paused-video affordance: a soft scrim with a play glyph. */
@Composable
private fun TikTokPausedOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(150)) +
                scaleIn(
                    initialScale = 0.55f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                ),
        exit =
            fadeOut(tween(120)) +
                scaleOut(targetScale = 0.55f, animationSpec = tween(120)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.solar_play_linear),
                contentDescription = stringResource(R.string.play),
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/**
 * The Apple Music inline lyrics pane, component and behaviour verbatim: the karaoke view in place
 * of the artwork, following the playing song, with its own tap-a-line-to-seek and per-line sync.
 */
@Composable
private fun TikTokInlineLyricsPane(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.fillMaxSize(),
    ) {
        LyricsEnhanced(
            sliderPositionProvider = sliderPositionProvider,
            lyricsSyncOffset = lyricsSyncOffset,
            textColorOverride = Color.White,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp, bottom = 4.dp)
                    .padding(horizontal = 6.dp),
        )
    }
}

/**
 * The track identity pinned at the bottom-left of the page: the queue it comes from as a small chip
 * (tap opens the queue sheet), then the big bold title and the "artist • album" secondary line —
 * the reference's username + caption treatment.
 */
@Composable
private fun TikTokSongInfo(
    pageMetadata: MediaMetadata,
    queueTitle: String?,
    onQueueClick: () -> Unit,
    lyricsControlsVisible: Boolean,
    onCloseLyrics: () -> Unit,
    onOpenLyricsMenu: () -> Unit,
    onLyricsOverflowAnchorChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val showChipRow = lyricsControlsVisible || !queueTitle.isNullOrBlank()
        if (showChipRow) {
            // FIXED-HEIGHT caption row (user report 2026-09-03: "When I open lyrics in tiktok style
            // and then close it I see artwork shifting it's position a bit for a split second").
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(TIKTOK_CAPTION_ROW_HEIGHT),
            ) {
                if (!queueTitle.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.14f))
                                .tiktokNoRippleClickable(onClick = onQueueClick)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.solar_music_note_2_linear),
                            contentDescription = null,
                            tint = TIKTOK_INACTIVE_GRAY,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = queueTitle,
                            color = TIKTOK_INACTIVE_GRAY,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // The lyrics pane's controls, in the caption row: X to close
                // ("a cross icon on the right side of recently played text"),
                // then the horizontal dots ("one more three dots horizontally
                // that opens lyrics overflow menu"). They fade in with the
                // pane and out with it.
                AnimatedVisibility(
                    visible = lyricsControlsVisible,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!queueTitle.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .tiktokNoRippleClickable(onClick = onCloseLyrics),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.player_close),
                                contentDescription = stringResource(R.string.close_dialog),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .tiktokNoRippleClickable(onClick = onOpenLyricsMenu)
                                    // The lyrics overflow popup's anchor: report
                                    // the dots' rect in root space, kept live —
                                    // the popup positions itself against it and
                                    // grows out of this exact spot.
                                    .onGloballyPositioned {
                                        onLyricsOverflowAnchorChange(it.boundsInRoot())
                                    },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_horiz),
                                contentDescription = stringResource(R.string.more),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // The title and secondary line keep their right edge clear of the
        // action rail, which now hangs down alongside this block (see the
        // page-level TikTokRail in TikTokSongPage): 56dp of end padding
        // clears the rail's 48dp buttons + 10dp end padding with room to
        // spare, exactly like the reference constrains its caption text —
        // a constant inset so the text never reflows when the rail hides.
        Text(
            text = pageMetadata.title,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = TIKTOK_CAPTION_TEXT_CLEARANCE),
        )
        Spacer(Modifier.height(3.dp))
        val artistName = pageMetadata.artists.joinToString(", ") { it.name }
        val secondary =
            if (pageMetadata.album?.title.isNullOrBlank() || artistName.isBlank()) {
                artistName.ifBlank { pageMetadata.album?.title.orEmpty() }
            } else {
                "$artistName • ${pageMetadata.album?.title}"
            }
        if (secondary.isNotBlank()) {
            Text(
                text = secondary,
                color = TIKTOK_INACTIVE_GRAY,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = TIKTOK_CAPTION_TEXT_CLEARANCE),
            )
        }
    }
}

/** One live double-tap heart, at the tap point (artwork-box coordinates). */
private data class TikTokHeartBurst(
    val id: Long,
    val x: Dp,
    val y: Dp,
)

/**
 * TikTok's double-tap heart: a big red heart pops in at the tap point with a
 * spring overshoot, then drifts up and fades out. The rotation is derived
 * from the burst id so rapid double-taps never stack perfectly aligned, and
 * the whole view removes itself from the burst list once its animation
 * finishes. The like it celebrates lands through the same Room row the
 * rail's heart uses, so the rail button pops in sync.
 */
@Composable
private fun TikTokHeartBurstView(
    burst: TikTokHeartBurst,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(burst.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(TIKTOK_HEART_BURST_MS, easing = LinearOutSlowInEasing),
        )
        onFinished()
    }
    val p = progress.value
    // Pop in fast with overshoot, settle, hold; rise and fade through the
    // second half — the reference's double-tap beat, in one progress value.
    val scale = when {
        p < 0.22f -> 0.2f + (p / 0.22f) * 1.15f
        p < 0.38f -> 1.35f - ((p - 0.22f) / 0.16f) * 0.35f
        else -> 1f
    }
    val alpha = if (p < 0.55f) 1f else 1f - ((p - 0.55f) / 0.45f)
    val rise = 160.dp * (p * p)
    val rotation = ((burst.id % 5) - 2) * 6f
    Icon(
        painter = painterResource(R.drawable.solar_heart_bold),
        contentDescription = null,
        tint = TIKTOK_RED,
        modifier =
            Modifier
                .offset(
                    x = burst.x - TIKTOK_HEART_BURST_SIZE / 2f,
                    y = burst.y - TIKTOK_HEART_BURST_SIZE / 2f - rise,
                ).size(TIKTOK_HEART_BURST_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha.coerceIn(0f, 1f)
                    rotationZ = rotation
                },
    )
}

/** Size of the double-tap heart glyph. */
private val TIKTOK_HEART_BURST_SIZE = 88.dp

/** How long a double-tap heart lives, start to fade-out end. */
private const val TIKTOK_HEART_BURST_MS = 650

/** Width of the rail's right-edge legibility wash. */
private val TIKTOK_RAIL_WASH_WIDTH = 120.dp

/**
 * The soft right-edge wash behind the action rail — clear at its left edge
 * so it blends into the cover: plain black fading in toward the rail, which
 * is what keeps the white glyphs legible over light artwork without
 * touching the rest of the media.
 */
private val TIKTOK_RAIL_WASH =
    Brush.horizontalGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
    )

/**
 * The wash's vertical mask: strongest through the lower two thirds (where
 * the rail's buttons live now that the rail is pinned to the zone's
 * bottom), fading to clear over the top third and the last sliver at the
 * bottom so the wash never begins or ends in a visible straight line.
 * Applied with BlendMode.DstIn inside an offscreen layer, in the same place
 * the wash is drawn.
 */
private val TIKTOK_RAIL_WASH_VERTICAL_FADE =
    Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.28f to Color.Black,
        0.90f to Color.Black,
        1.00f to Color.Transparent,
    )

/**
 * The artwork's edge blend, applied with BlendMode.DstIn in an offscreen layer: opaque through the
 * art, clear at the top and bottom so the sharp rendering dissolves into the mesh backdrop its
 * palette was sampled from, instead of meeting it in a hard straight edge.
 */
private val TIKTOK_ART_EDGE_FADE =
    Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.14f to Color.Black,
        0.86f to Color.Black,
        1.00f to Color.Transparent,
    )

/** TikTok's legibility scrim: dark at both edges (where the header and the
 * info zone live) and clear through the middle (where the artwork carries
 * itself). One static gradient, drawn in the draw phase.
 */
internal fun Modifier.tiktokScrim(): Modifier = drawBehind { drawRect(TIKTOK_SCRIM) }

/** Decode size for the hero artwork, in pixels. */
internal const val TIKTOK_ART_PX = 1080

internal val TIKTOK_EMPTY_BACKDROP = Color(0xFF0B0B0F)

/**
 * The single scrim gradient shared by every page — hoisted to a constant so no page ever allocates
 * a new Brush in composition or per draw.
 */
private val TIKTOK_SCRIM =
    Brush.verticalGradient(
        colorStops =
            arrayOf(
                0.00f to Color.Black.copy(alpha = 0.32f),
                0.22f to Color.Black.copy(alpha = 0.06f),
                0.55f to Color.Black.copy(alpha = 0.08f),
                1.00f to Color.Black.copy(alpha = 0.52f),
            ),
    )
