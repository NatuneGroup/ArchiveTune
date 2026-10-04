/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextLayoutResult
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.ui.player.PlayerFadeConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialShapes
import kotlin.math.abs
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import me.saket.squiggles.SquigglySlider
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerHorizontalPadding
import moe.rukamori.archivetune.constants.SliderStyle
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.extensions.toggleRepeatMode
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.component.PlayerSliderTrack
import moe.rukamori.archivetune.ui.component.ResizableIconButton
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.theme.PlayerBackgroundColorUtils
import moe.rukamori.archivetune.ui.theme.PlayerSliderColors
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.constants.SwipeUpToOpenQueueKey

@Composable
fun AutoResizeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var fontSizeValue by remember(text) { mutableStateOf(style.fontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = style.copy(fontSize = fontSizeValue),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.hasVisualOverflow && fontSizeValue.value > 10f) {
                fontSizeValue = (fontSizeValue.value * 0.9f).sp
            } else {
                readyToDraw = true
            }
        },
        modifier = modifier.drawWithContent {
            if (readyToDraw) {
                drawContent()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun V10PlayerContent(
    mediaMetadata: MediaMetadata,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    textBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    onCollapseClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onSleepTimerClick: () -> Unit,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onMenuClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val baseArtworkUrl = mediaMetadata.thumbnailUrl?.highRes()
    val thumbnailSwapState =
        rememberThumbnailSwapState(
            videoId = mediaMetadata.id,
            ytmUrl = baseArtworkUrl,
            lowDataMode = rememberLowDataModeActive(),
            isMusicVideo = mediaMetadata.isMusicVideo,
        )
    val artworkUrl = thumbnailSwapState.displayUrl
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val onTitleClick = titleActions.onTitleClick
    val onArtistClick = titleActions.onArtistClick
    val onPlayPauseClick = {
        if (playbackState == STATE_ENDED) {
            playerConnection.player.seekTo(0, 0)
            playerConnection.player.playWhenReady = true
        } else {
            playerConnection.player.togglePlayPause()
        }
    }

    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val liked = currentSong?.song?.liked == true
    val onToggleLike = playerConnection::toggleLike

    // The two-tone contract: field + accent, nothing else.
    // textBackgroundColor = accent (text/icon color), textButtonColor = field (fill color)
    val accent = textBackgroundColor
    val field = textButtonColor

    Column(modifier = modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorialCircleButton(
                onClick = onCollapseClick,
                accent = accent,
                field = field,
                size = 44.dp
            ) {
                Icon(painter = painterResource(R.drawable.expand_more), contentDescription = "Collapse", modifier = Modifier.size(26.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sleepTimerEnabled) {
                    val countdownText = makeTimeString(sleepTimerTimeLeft.coerceAtLeast(0L))
                    Surface(
                        onClick = onSleepTimerClick,
                        shape = CircleShape,
                        color = accent,
                        contentColor = field,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = countdownText,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                painter = painterResource(R.drawable.bedtime),
                                contentDescription = "Sleep timer",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    EditorialCircleButton(
                        onClick = onSleepTimerClick,
                        accent = field,
                        field = accent,
                        size = 44.dp
                    ) {
                        Icon(painter = painterResource(R.drawable.bedtime), contentDescription = "Sleep timer", modifier = Modifier.size(22.dp))
                    }
                }

                EditorialCircleButton(
                    onClick = onLyricsClick,
                    accent = accent,
                    field = field,
                    size = 44.dp
                ) {
                    Icon(painter = painterResource(R.drawable.lyrics), contentDescription = "Lyrics", modifier = Modifier.size(22.dp))
                }
                EditorialCircleButton(
                    onClick = onMenuClick,
                    accent = accent,
                    field = field,
                    size = 44.dp
                ) {
                    Icon(painter = painterResource(R.drawable.more_vert), contentDescription = "More", modifier = Modifier.size(22.dp))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f),
            contentAlignment = Alignment.Center
        ) {
            EditorialDieCutArt(
                artworkUrl = artworkUrl,
                mediaMetadataId = mediaMetadata.id,
                isPlaying = isPlaying,
                onTap = onPlayPauseClick,
                accent = accent,
                field = field,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                onSkipPrevious = { playerConnection.player.seekToPrevious() },
                onSkipNext = { playerConnection.player.seekToNext() }
            )
        }

        val title = mediaMetadata.title
        val headlineBase = when {
            title.length <= 12 -> MaterialTheme.typography.displayLarge
            title.length <= 24 -> MaterialTheme.typography.displayMedium
            else -> MaterialTheme.typography.displaySmall
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = title,
                style = headlineBase.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold
                ),
                color = accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            val artistName = remember(mediaMetadata.artists) {
                mediaMetadata.artists.joinToString(", ") { it.name }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        mediaMetadata.artists.firstOrNull()?.id?.let(onArtistClick)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                if (mediaMetadata.explicit) {
                    Icon(
                        painter = painterResource(R.drawable.explicit),
                        contentDescription = "Explicit",
                        tint = accent.copy(alpha = 0.8f),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp)
                    )
                }
                Text(
                    text = artistName.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                    color = accent.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 2000
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            // Row 1: word pill + next circle
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    onClick = onPlayPauseClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(50),
                    color = accent,
                    contentColor = field
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading && !isPlaying) {
                            LoadingIndicator(
                                modifier = Modifier.size(36.dp),
                                color = field,
                                polygons = listOf(
                                    MaterialShapes.SoftBurst,
                                    MaterialShapes.Cookie9Sided,
                                    MaterialShapes.Pill,
                                    MaterialShapes.Sunny
                                )
                            )
                        } else {
                            AnimatedContent(
                                targetState = isPlaying,
                                label = "EditorialPlayWord"
                            ) { playing ->
                                Text(
                                    text = if (playing) "PAUSE" else "PLAY",
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 3.sp
                                    )
                                )
                            }
                        }
                    }
                }
                EditorialCircleButton(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        playerConnection.seekToNext()
                    },
                    accent = accent,
                    field = field,
                    size = 80.dp
                ) {
                    Icon(painter = painterResource(R.drawable.skip_next), contentDescription = "Next", modifier = Modifier.size(34.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: previous circle + progress line with times
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                EditorialCircleButton(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        playerConnection.seekToPrevious()
                    },
                    accent = accent,
                    field = field,
                    size = 80.dp
                ) {
                    Icon(painter = painterResource(R.drawable.skip_previous), contentDescription = "Previous", modifier = Modifier.size(34.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    var scrubPosition by remember { mutableStateOf<Float?>(null) }
                    val displayedProgress = scrubPosition?.toLong() ?: (sliderPosition ?: position)
                    val progressFraction =
                        if (duration > 0) displayedProgress.toFloat() / duration.toFloat() else 0f
                    val animatedProgress by animateFloatAsState(
                        targetValue = progressFraction,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "EditorialProgress"
                    )
                    val lineStroke = Stroke(
                        width = with(LocalDensity.current) { 4.dp.toPx() },
                        cap = StrokeCap.Round
                    )

                    Box(contentAlignment = Alignment.Center) {
                        LinearWavyProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                            color = accent,
                            trackColor = accent.copy(alpha = 0.35f),
                            stroke = lineStroke,
                            trackStroke = lineStroke,
                            amplitude = { if (isPlaying) 1f else 0f }
                        )
                        Slider(
                            value = scrubPosition ?: (sliderPosition ?: position).toFloat(),
                            onValueChange = { scrubPosition = it },
                            onValueChangeFinished = {
                                scrubPosition?.let { onSliderValueChange(it.toLong()) }
                                onSliderValueChangeFinished()
                                scrubPosition = null
                            },
                            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Transparent,
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatEditorialTime(displayedProgress.coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent.copy(alpha = 0.8f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatEditorialTime(duration.coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            val (swipeUpToOpenQueue) = rememberPreference(SwipeUpToOpenQueueKey, defaultValue = true)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .pointerInput(swipeUpToOpenQueue) {
                        if (!swipeUpToOpenQueue) return@pointerInput
                        detectVerticalDragGestures { change, dragAmount ->
                            if (dragAmount < -15) {
                                change.consume()
                                onQueueClick()
                            }
                        }
                    },
                horizontalArrangement = Arrangement.SpaceAround // Spread the buttons wider apart
            ) {
                EditorialChip(
                    checked = liked,
                    onClick = { onToggleLike() },
                    accent = accent,
                    field = field
                ) {
                    Icon(
                        painter = painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                        contentDescription = "Like",
                        modifier = Modifier.size(20.dp)
                    )
                }
                EditorialChip(
                    checked = shuffleModeEnabled,
                    onClick = {
                        try {
                            playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                        } catch (_: Exception) {}
                    },
                    accent = accent,
                    field = field
                ) {
                    Icon(painter = painterResource(R.drawable.shuffle), contentDescription = "Shuffle", modifier = Modifier.size(20.dp))
                }
                EditorialChip(
                    checked = repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = {
                        try {
                            playerConnection.player.toggleRepeatMode()
                        } catch (_: Exception) {}
                    },
                    accent = accent,
                    field = field
                ) {
                    Icon(
                        painter = painterResource(if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat),
                        contentDescription = "Repeat",
                        modifier = Modifier.size(20.dp)
                    )
                }
                EditorialChip(
                    checked = false,
                    onClick = onAddToPlaylistClick,
                    accent = accent,
                    field = field
                ) {
                    Icon(painter = painterResource(R.drawable.library_add), contentDescription = "Add to playlist", modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(
            modifier = Modifier
                .navigationBarsPadding()
                .height(6.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorialDieCutArt(
    artworkUrl: String?,
    mediaMetadataId: String,
    isPlaying: Boolean,
    onTap: () -> Unit,
    accent: Color,
    field: Color,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit
) {
    val dieCuts = remember {
        listOf(
            MaterialShapes.Flower,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Puffy,
            MaterialShapes.Cookie12Sided,
            MaterialShapes.SoftBurst
        )
    }
    val targetPolygon = remember(mediaMetadataId) {
        dieCuts[abs(mediaMetadataId.hashCode()) % dieCuts.size]
    }

    var morphFrom by remember { mutableStateOf(targetPolygon) }
    var morphTo by remember { mutableStateOf(targetPolygon) }
    val morphProgress = remember { Animatable(1f) }
    LaunchedEffect(targetPolygon) {
        if (targetPolygon !== morphTo) {
            morphFrom = morphTo
            morphTo = targetPolygon
            morphProgress.snapTo(0f)
            morphProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }
    val morph = remember(morphFrom, morphTo) { Morph(morphFrom, morphTo) }
    val dieCutShape = remember(morph, morphProgress.value) {
        EditorialMorphShape(morph, morphProgress.value)
    }

    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "EditorialArtScale"
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (maxWidth < 50.dp || maxHeight < 50.dp) return@BoxWithConstraints
        val artSize = minOf(maxWidth, maxHeight) * 0.95f

        val view = androidx.compose.ui.platform.LocalView.current
        val (enableHapticFeedback) = rememberPreference(moe.rukamori.archivetune.constants.EnableHapticFeedbackKey, true)
        val coroutineScope = rememberCoroutineScope()

        // Visual feedback variables
        var skipIndicator by remember { mutableStateOf<String?>(null) } // "prev", "next", or "play_pause"
        val skipIndicatorAlpha = remember { Animatable(0f) }

        Box(
            modifier = Modifier
                .size(artSize)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                }
                .clip(dieCutShape)
                .background(accent)
                .playerSeekDoubleTap(),
            contentAlignment = Alignment.Center
        ) {
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = field,
                    modifier = Modifier.size(artSize * 0.3f)
                )
            }
        }
    }
}

@Composable
internal fun EditorialCircleButton(
    onClick: () -> Unit,
    accent: Color,
    field: Color,
    size: Dp,
    content: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = accent,
            contentColor = field
        ),
        modifier = Modifier.size(size)
    ) {
        content()
    }
}

@Composable
internal fun EditorialChip(
    checked: Boolean,
    onClick: () -> Unit,
    accent: Color,
    field: Color,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (checked) accent else Color.Transparent,
        contentColor = if (checked) field else accent,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

internal class EditorialMorphShape(
    private val morph: Morph,
    private val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = morph.toPath(progress).asComposePath()
        val matrix = Matrix()
        val bounds = morph.calculateBounds()
        val boundsWidth = bounds[2] - bounds[0]
        val boundsHeight = bounds[3] - bounds[1]

        matrix.scale(size.width / boundsWidth, size.height / boundsHeight)
        matrix.translate(-bounds[0], -bounds[1])
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

internal class EditorialPolygonShape(
    private val polygon: RoundedPolygon
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = polygon.toPath().asComposePath()
        val matrix = Matrix()
        val bounds = polygon.calculateBounds()
        val boundsWidth = bounds[2] - bounds[0]
        val boundsHeight = bounds[3] - bounds[1]

        matrix.scale(size.width / boundsWidth, size.height / boundsHeight)
        matrix.translate(-bounds[0], -bounds[1])
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

internal fun formatEditorialTime(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
