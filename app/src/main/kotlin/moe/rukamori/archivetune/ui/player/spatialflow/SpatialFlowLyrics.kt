/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * SpatialFlow player style — the full-screen lyrics overlay.
 *
 * A port of SpatialFlow's FullScreenLyricsOverlay + the circular-reveal
 * modifier (github.com/MythicalSHUB/SpatialFlow, GPL-3.0,
 * ui/player/FullScreenLyricsOverlay.kt): the overlay reveals with a circular
 * clip expanding from the Lyrics chip, carries the centred "LYRICS • Synced
 * Lyrics" header with the song title, the auto-scrolling synced lines (active
 * 38sp Bold, inactive 20sp dimmed, tap to seek), the plain-lyrics fallback and
 * the metadata footer. Dimensions, colors and reveal timing are SpatialFlow's
 * own. The lyric DATA comes from ArchiveTune's own lyrics store
 * (playerConnection.currentLyrics parsed by LyricsUtils) — real providers, no
 * second lyrics implementation.
 */

package moe.rukamori.archivetune.ui.player.spatialflow

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.lyrics.WordTimestamp
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.player.blurBackdropFootprint
import moe.rukamori.archivetune.ui.player.movingBlurWanderMaxDriftDp
import moe.rukamori.archivetune.ui.player.rememberBlurWanderDrift
import moe.rukamori.archivetune.ui.player.rememberOfflineArtworkImageRequest
import moe.rukamori.archivetune.ui.utils.rememberPreBlurredBitmap
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel

private fun Modifier.circularRevealFrom(
    progressProvider: () -> Float,
    centerProvider: () -> Offset?,
): Modifier =
    this.drawWithCachePathClip(progressProvider, centerProvider)

private fun Modifier.drawWithCachePathClip(
    progressProvider: () -> Float,
    centerProvider: () -> Offset?,
): Modifier =
    this.drawWithCache {
            val revealPath = Path()
            var lastCenter: Offset? = null
            var lastRadius = -1f
            onDrawWithContent {
                val progress = progressProvider()
                if (progress >= 1f) {
                    drawContent()
                    return@onDrawWithContent
                }
                if (progress <= 0f) {
                    return@onDrawWithContent
                }
                val revealCenter = centerProvider() ?: Offset(size.width / 2f, size.height / 3f)
                if (revealCenter != lastCenter || lastRadius == -1f) {
                    lastCenter = revealCenter
                    lastRadius =
                        maxOf(
                            kotlin.math.hypot(revealCenter.x.toDouble(), revealCenter.y.toDouble()).toFloat(),
                            kotlin.math.hypot((size.width - revealCenter.x).toDouble(), revealCenter.y.toDouble()).toFloat(),
                            kotlin.math.hypot(revealCenter.x.toDouble(), (size.height - revealCenter.y).toDouble()).toFloat(),
                            kotlin.math.hypot((size.width - revealCenter.x).toDouble(), (size.height - revealCenter.y).toDouble()).toFloat(),
                        )
                }
                val radius = lastRadius * progress
                revealPath.reset()
                revealPath.addOval(
                    androidx.compose.ui.geometry.Rect(
                        left = revealCenter.x - radius,
                        top = revealCenter.y - radius,
                        right = revealCenter.x + radius,
                        bottom = revealCenter.y + radius,
                    ),
                )

                clipPath(revealPath, ClipOp.Intersect) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }

@Composable
internal fun SpatialFlowLyricsOverlay(
    currentSong: MediaMetadata,
    syncedLyrics: List<LyricsEntry>?,
    plainLyrics: String?,
    lyricsProvider: String?,
    currentPositionProvider: () -> Long,
    contentReady: Boolean,
    backgroundBrush: Brush,
    artUrl: String? = null,
    revealProgressProvider: () -> Float,
    revealCenterProvider: () -> Offset?,
    contentColor: Color,
    contentSecondary: Color,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    // Respect the global lyrics mode: Enhanced (the default) renders the
    // shared word-synced karaoke view so enhanced lyrics are used in the
    // SpatialFlow style too; every other mode keeps this style's own
    // char-fill renderer below.
    val lyricsMode by rememberEnumPreference(LyricsModeKey, defaultValue = LyricsMode.ENHANCED)

    var showLyricsMenu by remember { mutableStateOf(false) }
    var moreIconBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    // The lyrics overflow popup only gets a live liquid-glass backdrop when
    // the liquid glass preference is enabled; otherwise it renders with the
    // regular opaque surface so no glass remains with the toggle off.
    val popupBackdrop: PlatformBackdrop? =
        if (rememberLiquidGlassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
        } else {
            null
        }

    val (autoTranslateLyrics) = rememberPreference(AutoTranslateLyricsKey, defaultValue = false)
    val (translatorTargetLang) = rememberPreference(TranslatorTargetLangKey, defaultValue = "")
    val (autoTranslateExcludedLanguages) =
        rememberPreference(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
    val lyricsMenuViewModel: LyricsMenuViewModel = hiltViewModel()
    val translationDismissedMediaIds by lyricsMenuViewModel.translationDismissedMediaIds
        .collectAsStateWithLifecycle()
    LaunchedEffect(
        currentSong.id,
        currentLyricsEntity?.lyrics,
        currentLyricsEntity?.source,
        autoTranslateLyrics,
        translatorTargetLang,
        autoTranslateExcludedLanguages,
        translationDismissedMediaIds,
    ) {
        if (!autoTranslateLyrics) return@LaunchedEffect
        val snapshot = currentLyricsEntity ?: return@LaunchedEffect
        val text = snapshot.lyrics ?: return@LaunchedEffect
        if (text.isBlank() || text == LYRICS_NOT_FOUND) return@LaunchedEffect
        if (snapshot.source in setOf(LyricsEntity.Source.AI_TRANSLATION.value, LyricsEntity.Source.TRANSLATION.value) &&
            LyricsUtils.hasTranslation(text)
        ) return@LaunchedEffect
        if (currentSong.id in translationDismissedMediaIds) return@LaunchedEffect
        if (!LyricsUtils.shouldAutoTranslate(
                lyrics = text,
                targetLanguage = translatorTargetLang,
                excludedLanguageCodes = autoTranslateExcludedLanguages,
            )
        ) {
            return@LaunchedEffect
        }
        lyricsMenuViewModel.translateLyricsWithAi(
            mediaMetadata = currentSong,
            lyrics = text,
            targetLanguage = translatorTargetLang,
        )
    }

    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val aiRomanizationSessionKey =
        remember(currentLyricsEntity?.lyrics) {
            AiLyricsRomanization.sessionKey(currentSong.id, currentLyricsEntity?.lyrics)
        }
    val aiRomanizationResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    val romanizedLines: List<String?> =
        remember(
            aiRomanizationResult,
            aiRomanizationSessionKey,
            aiRomanizationSettings.active,
            syncedLyrics,
        ) {
            if (!aiRomanizationSettings.active || syncedLyrics == null) {
                emptyList()
            } else {
                AiLyricsRomanization.linesFor(
                    aiRomanizationSessionKey,
                    syncedLyrics.map { it.text },
                )
            }
        }
    LaunchedEffect(aiRomanizationSessionKey, syncedLyrics, aiRomanizationSettings) {
        if (!aiRomanizationSettings.active || !aiRomanizationSettings.auto) return@LaunchedEffect
        if (syncedLyrics.isNullOrEmpty()) return@LaunchedEffect
        AiLyricsRomanization.request(
            sessionKey = aiRomanizationSessionKey,
            lines = syncedLyrics.map { it.text },
            settings = aiRomanizationSettings,
        )
    }

    val consumeClicks = remember { MutableInteractionSource() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    Box(
        modifier =
            modifier
                .circularRevealFrom(
                    progressProvider = revealProgressProvider,
                    centerProvider = revealCenterProvider,
                ).background(backgroundBrush)
                .clickable(
                    interactionSource = consumeClicks,
                    indication = null,
                    onClick = {},
                ),
    ) {
        // The backdrop layer wraps EVERY visual in the overlay — the moving-
        // blur background AND the content — so the lyrics overflow popup's
        // drawBackdrop() samples the actual on-screen pixels behind it
        // (gradient + moving blur + text). Sampling just the text column over a
        // transparent base would yield an empty texture, i.e. no liquid glass.
        // The popup itself stays OUTSIDE this box, as a later sibling, so it
        // never feeds back into its own sample. (Same pattern as AppleMusicPlayer.)
        //
        // The padding lives on the content Column inside, NOT on this box: padding
        // here would inset MovingBlurBackground and leave the strip above the song
        // title showing only the dark scrim/base brush. The blur background fills
        // edge to edge.
        Box(
            modifier =
                Modifier.fillMaxSize().let { base ->
                    if (popupBackdrop != null) {
                        base.layerBackdrop(popupBackdrop)
                    } else {
                        base
                    }
                },
        ) {
        // The lyrics backdrop is Apple Music's exact moving-blur recipe: the
        // artwork at 64dp blur, slowly drifting and scaling up as the overlay
        // settles in, with AM's scrim colors (0.25/0.40/0.65 black) over it.
        // The previous palette-gradient + vibrancy renderer read as "the
        // liquid blur is too bright". The solid backgroundBrush beneath stays
        // the reveal/crop base colour and the no-artwork fallback.
        SpatialFlowLyricsMovingBlur(
            artUrl = artUrl,
            modifier = Modifier.matchParentSize(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = LocalStableSystemBarsTopPadding.current)
                    .navigationBarsPadding()
                    .padding(vertical = 12.dp),
        ) {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {

                IconButton(
                    onClick = { showLyricsMenu = true },
                    modifier =
                        Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            val sz = coords.size
                            moreIconBounds =
                                androidx.compose.ui.geometry.Rect(
                                    offset = pos,
                                    size =
                                        androidx.compose.ui.geometry.Size(
                                            width = sz.width.toFloat(),
                                            height = sz.height.toFloat(),
                                        ),
                                )
                        },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = "Lyrics menu",
                        tint = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.basicMarqueeWithFadedEdges(edgeWidth = 8.dp),
                    ) {
                        Text(
                            text = "LYRICS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor.copy(alpha = 0.5f),
                            letterSpacing = 1.sp,
                        )
                        AnimatedVisibility(
                            visible = !syncedLyrics.isNullOrEmpty(),
                            enter =
                                fadeIn(
                                    animationSpec =
                                        androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                                        ),
                                ) + slideInHorizontally(initialOffsetX = { it / 2 }),
                            exit = fadeOut() + slideOutHorizontally(),
                        ) {
                            Text(
                                text = " • Synced Lyrics",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.4f),
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                    Text(
                        text = currentSong.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier.basicMarqueeWithFadedEdges(edgeWidth = 8.dp),
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = "Close Lyrics",
                        tint = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !contentReady -> Unit

                    lyricsMode == LyricsMode.ENHANCED && !syncedLyrics.isNullOrEmpty() ->
                        LyricsEnhanced(
                            sliderPositionProvider = { null },
                            lyricsSyncOffset = 0,
                            modifier = Modifier.fillMaxSize(),
                            textColorOverride = contentColor,
                        )

                    !syncedLyrics.isNullOrEmpty() ->
                        SpatialFlowSyncedLyrics(
                            lyrics = syncedLyrics,
                            romanizedLines = romanizedLines,
                            currentPositionProvider = currentPositionProvider,
                            contentColor = contentColor,
                            onSeekTo = onSeekTo,
                            modifier = Modifier.fillMaxSize(),
                        )

                    !plainLyrics.isNullOrBlank() ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 28.dp),
                        ) {
                            Text(
                                text = plainLyrics,
                                style = MaterialTheme.typography.titleLarge,
                                color = contentColor.copy(alpha = 0.9f),
                            )
                            LyricsMetadataFooter(
                                currentSong = currentSong,
                                selectedProvider = lyricsProvider,
                                contentColor = contentColor,
                            )
                        }

                    else ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 32.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "No lyrics found",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 64.dp, bottom = 12.dp),
                            )
                            Text(
                                text = "Lyrics for this song are not available yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                            )
                            LyricsMetadataFooter(
                                currentSong = currentSong,
                                selectedProvider = lyricsProvider,
                                contentColor = contentColor,
                            )
                        }
                }
            }
        }
        }

        if (showLyricsMenu) {
            // Dim in the lyrics surface's own hue instead of flashing pure
            // black over the moving-blur backdrop (task report: "a black
            // overlay appears as background").
            val scrimBase = (backgroundBrush as? SolidColor)?.value ?: Color.Black
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = moreIconBounds,
                lyricsProvider = { currentLyricsEntity },
                mediaMetadataProvider = { currentSong },
                lyricsSyncOffset = 0,
                onLyricsSyncOffsetChange = {},
                onDismiss = { showLyricsMenu = false },
                backdrop = popupBackdrop,
            )
        }
    }
}

@Composable
private fun SpatialFlowSyncedLyrics(
    lyrics: List<LyricsEntry>,
    romanizedLines: List<String?>,
    currentPositionProvider: () -> Long,
    contentColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val dimColor = contentColor.copy(alpha = 0.35f)

    val isKaraokeMode =
        remember(lyrics) {
            lyrics.any { !it.isInstrumental && LyricsUtils.hasTrueWordSync(it) }
        }

    val displayItems =
        remember(lyrics, isKaraokeMode) {
            lyrics.mapIndexedNotNull { index, line ->
                if (isKaraokeMode && line.isInstrumental) null else index
            }
        }

    val activeIndex by remember(displayItems) {
        derivedStateOf {
            val position = currentPositionProvider()
            var index = -1
            for (i in displayItems.indices) {
                if (lyrics[displayItems[i]].time <= position) index = i else break
            }
            index
        }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex < 0 || listState.isScrollInProgress) return@LaunchedEffect
        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (itemInfo != null) {
            val viewportHeight =
                listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            val center = listState.layoutInfo.viewportStartOffset + (viewportHeight / 2)
            val itemCenter = itemInfo.offset + itemInfo.size / 2
            val offset = itemCenter - center
            if (abs(offset) > 5) {
                listState.animateScrollBy(
                    value = offset.toFloat(),
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                )
            }
        } else {
            listState.scrollToItem(activeIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier =
            modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.08f to Color.Black,
                                0.86f to Color.Black,
                                1f to Color.Transparent,
                            ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                start = 32.dp,
                end = 32.dp,
                top = 48.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            items = displayItems,
            key = { displayIndex, lyricsIndex -> "$lyricsIndex-${lyrics[lyricsIndex].time}-$displayIndex" },
        ) { displayIndex, lyricsIndex ->
            val line = lyrics[lyricsIndex]
            val isActive = displayIndex == activeIndex
            if (line.isInstrumental) {
                SpatialFlowInterludeItem(
                    isActive = isActive,
                    currentPositionProvider = currentPositionProvider,
                    line = line,
                    nextLineStartMs = lyrics.getOrNull(lyricsIndex + 1)?.time ?: (line.time + 5000L),
                    accentColor = contentColor,
                )
            } else {
                SpatialFlowLyricLineItem(
                    line = line,
                    isActive = isActive,
                    currentPositionProvider = currentPositionProvider,
                    contentColor = contentColor,
                    romanizedText = romanizedLines.getOrNull(lyricsIndex)?.takeIf { it.isNotBlank() },
                    onClick = { onSeekTo(line.time) },
                )
            }
        }
    }
}

private data class WordCharSpan(
    val start: Int,
    val endExclusive: Int,
    val word: WordTimestamp,
)

private fun wordSpansFor(
    text: String,
    words: List<WordTimestamp>,
): List<WordCharSpan> {
    val spans = mutableListOf<WordCharSpan>()
    var cursor = 0
    for (word in words) {
        // Word text may carry a trailing separator space (the parsers preserve it so verbatim
        // renderers keep inter-word gaps) while the line text is trimmed at its end — aligning on
        // the raw token would stop matching at the final word of a line. Match the trimmed core
        // instead; the separator itself stays outside the karaoke span.
        val core = word.text.trim()
        if (core.isEmpty()) continue
        val idx = text.indexOf(core, cursor)
        if (idx >= 0) {
            spans += WordCharSpan(idx, idx + core.length, word)
            cursor = idx + core.length
        }
    }
    return spans
}

@Composable
private fun SpatialFlowLyricLineItem(
    line: LyricsEntry,
    isActive: Boolean,
    currentPositionProvider: () -> Long,
    contentColor: Color,
    romanizedText: String? = null,
    onClick: () -> Unit,
) {
    val rawWords = line.words.orEmpty().filter { it.text.isNotBlank() }
    val spans = remember(line.text, rawWords) { wordSpansFor(line.text, rawWords) }
    val isKaraoke = LyricsUtils.hasTrueWordSync(line) && spans.isNotEmpty()

    val dimColor = contentColor.copy(alpha = 0.35f)
    val litColor = contentColor

    val rawPos = if (isKaraoke && isActive) currentPositionProvider() else line.time
    val smoothedPos by animateFloatAsState(
        targetValue = rawPos.toFloat(),
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "SmoothKaraokePos",
    )

    val mainTextStyle =
        MaterialTheme.typography.headlineMedium.copy(
            fontFamily = SpatialFlowGoogleSansFlexNonRounded,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 50.sp,
        )
    val baseTextLayout = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    Box(
        modifier =
            Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {

            Box(modifier = Modifier.fillMaxWidth()) {

                Text(
                    text = line.text,
                    style = mainTextStyle,
                    color = if (isKaraoke || !isActive) dimColor else litColor,
                    textAlign = TextAlign.Center,
                    onTextLayout = { baseTextLayout.value = it },
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isKaraoke && isActive) {
                    Text(
                        text = line.text,
                        style = mainTextStyle,
                        color = litColor,
                        textAlign = TextAlign.Center,
                        maxLines = Int.MAX_VALUE,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithCache {
                                    onDrawWithContent {
                                        val layout = baseTextLayout.value
                                        drawContent()
                                        if (layout != null) {
                                            eraseFutureText(layout, spans, smoothedPos.toLong())
                                        }
                                    }
                                },
                    )
                }
            }

            romanizedText
                ?.takeIf { it.isNotBlank() && it != line.text }
                ?.let { romanized ->
                    Text(
                        text = romanized,
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = SpatialFlowGoogleSansFlexNonRounded,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        color = if (isActive) litColor.copy(alpha = 0.65f) else dimColor.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
        }
    }
}

private fun DrawScope.eraseFutureText(
    layout: androidx.compose.ui.text.TextLayoutResult,
    spans: List<WordCharSpan>,
    pos: Long,
) {
    val textLength = layout.layoutInput.text.length
    for (charIndex in 0 until textLength) {
        val controllingSpan = findControllingSpan(charIndex, spans)
        val charProgress =
            if (controllingSpan != null) {
                calculateCharProgress(charIndex, controllingSpan, pos)
            } else {
                0f
            }

        if (charProgress >= 0.99f) {

        } else if (charProgress < 0.01f) {

            val path = layout.getPathForRange(charIndex, charIndex + 1)
            drawPath(path, color = Color.Black, blendMode = BlendMode.DstOut)
        } else {

            val path = layout.getPathForRange(charIndex, charIndex + 1)
            val box = layout.getBoundingBox(charIndex)

            val gradientWidth = box.width * 1.5f
            val sweepCenter = box.left + (box.width * charProgress)

            val brush =
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    1.0f to Color.Black,
                    startX = sweepCenter - (gradientWidth / 2f),
                    endX = sweepCenter + (gradientWidth / 2f),
                )

            drawPath(path, brush = brush, blendMode = BlendMode.DstOut)
        }
    }
}

private fun findControllingSpan(
    charIndex: Int,
    spans: List<WordCharSpan>,
): WordCharSpan? {
    if (spans.isEmpty()) return null

    val exactSpan = spans.find { charIndex >= it.start && charIndex < it.endExclusive }
    if (exactSpan != null) return exactSpan

    if (charIndex < spans.first().start) return spans.first()
    if (charIndex >= spans.last().endExclusive) return spans.last()

    return spans.lastOrNull { it.endExclusive <= charIndex } ?: spans.first()
}

private fun calculateCharProgress(
    charIndex: Int,
    span: WordCharSpan,
    pos: Long,
): Float {

    val wordStartMs = (span.word.startTime * 1000.0).toLong()
    val wordEndMs = (span.word.endTime * 1000.0).toLong().coerceAtLeast(wordStartMs + 120L)

    val wordProgress =
        when {
            pos < wordStartMs -> 0f
            pos >= wordEndMs -> 1f
            else -> {
                val duration = (wordEndMs - wordStartMs).toFloat().coerceAtLeast(1f)
                ((pos - wordStartMs).toFloat() / duration).coerceIn(0f, 1f)
            }
        }

    val easedWordProgress = easeOutCubic(wordProgress)

    val wStart = span.start
    val wEnd = span.endExclusive
    val wordLength = (wEnd - wStart).toFloat().coerceAtLeast(1f)

    val sweepWidth = 0.35f
    val sweepPosition = easedWordProgress * (1f + sweepWidth)

    val charOffsetInWord = (charIndex - wStart).toFloat()
    val charRelativePosition = charOffsetInWord / wordLength

    return when {
        sweepPosition < charRelativePosition -> 0f
        sweepPosition >= (charRelativePosition + sweepWidth) -> 1f
        else -> (sweepPosition - charRelativePosition) / sweepWidth
    }
}

private fun easeOutCubic(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

@Composable
private fun SpatialFlowInterludeItem(
    isActive: Boolean,
    currentPositionProvider: () -> Long,
    line: LyricsEntry,
    nextLineStartMs: Long,
    accentColor: Color,
) {
    val duration = (nextLineStartMs - line.time).coerceAtLeast(1)
    val rawProgress =
        if (isActive) {
            ((currentPositionProvider() - line.time).toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "InterludeProgress",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "InterludeBreathing")
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "BreathScale",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.85f else 0.25f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "InterludeAlpha",
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.spatialflow_ic_music_note),
            contentDescription = "Interlude",
            tint = accentColor.copy(alpha = iconAlpha),
            modifier =
                Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        scaleX = if (isActive) breatheScale else 1f
                        scaleY = if (isActive) breatheScale else 1f
                    },
        )

        LinearWavyProgressIndicator(
            progress = { animatedProgress },
            modifier =
                Modifier
                    .weight(1f)
                    .height(11.dp),
            color = accentColor.copy(alpha = if (isActive) 0.75f else 0.18f),
            trackColor = accentColor.copy(alpha = 0.06f),
            amplitude = { p -> (0.6f + p) },
        )
    }
}

// The AM player's lyrics backdrop: the artwork image sized to the drift
// footprint, blurred 64dp (AmBackdropBlurRadius), slowly wandering
// (blurWander — the "moving" part) and scaling from the rest scale (1.2,
// AmCoverBlurScale) up to the lyrics drift scale (2.4, AmLyricsBlurDriftScale)
// as the overlay settles in, with AM's canvas scrim colors over it. The
// previous renderer (MovingBlurBackground) boosted saturation 1.6x and laid a
// bright palette gradient over the artwork — the reported "liquid blur is too
// bright". Pre-S devices render a pre-blurred bitmap instead (Modifier.blur is
// a no-op below S), same as AM's preBlurredBitmap path.
private const val SfLyricsBlurRestScale = 1.2f
private const val SfLyricsBlurDriftScale = 2.4f
private val SfLyricsBlurRadius = 64.dp

@Composable
private fun SpatialFlowLyricsMovingBlur(
    artUrl: String?,
    modifier: Modifier = Modifier,
) {
    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val driftDpToPx = with(LocalDensity.current) { 1.dp.toPx() }

    // AM's lyricsBackdropProgress equivalent: 0 → 1 as the overlay appears,
    // driving the rest → drift scale morph and ramping the wander in.
    // animateFloatAsState would snap straight to 1f on the first composition
    // (its initial value IS the first target), so this uses an Animatable
    // launched on appearance instead.
    val morph = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        morph.animateTo(1f, animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds(),
    ) {
        // Same screen-scaled wander amplitude as every other player style, so the moving blur
        // feels identical everywhere: the colour mass traverses the whole display instead of
        // orbiting near the centre.
        val wanderMaxDrift = movingBlurWanderMaxDriftDp(maxWidth, maxHeight)
        val blurWander = rememberBlurWanderDrift(active = true, maxDriftDp = wanderMaxDrift)
        val driftFootprint =
            remember(maxWidth, maxHeight) {
                blurBackdropFootprint(
                    width = maxWidth,
                    height = maxHeight,
                    restScale = SfLyricsBlurRestScale,
                    driftScale = SfLyricsBlurDriftScale,
                    maxDriftDp = wanderMaxDrift,
                )
            }

        if (artUrl != null) {
            if (isPreS) {
                val preBlurredBitmap =
                    rememberPreBlurredBitmap(
                        imageUrl = artUrl,
                        radiusDp = SfLyricsBlurRadius,
                        cacheKey = "$artUrl#sflyricsblur",
                    )
                preBlurredBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .requiredSize(driftFootprint)
                                    .graphicsLayer {
                                        val scale =
                                            SfLyricsBlurRestScale +
                                                (SfLyricsBlurDriftScale - SfLyricsBlurRestScale) * morph.value
                                        scaleX = scale
                                        scaleY = scale
                                    },
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        // Offline-artwork request builder for parity with the
                        // player's other backdrops (raw strings render empty
                        // for offline-only tracks).
                        model = rememberOfflineArtworkImageRequest(artUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .requiredSize(driftFootprint)
                                .graphicsLayer {
                                    val scale =
                                        SfLyricsBlurRestScale +
                                            (SfLyricsBlurDriftScale - SfLyricsBlurRestScale) * morph.value
                                    scaleX = scale
                                    scaleY = scale
                                    translationX =
                                        blurWander.xDp.floatValue * driftDpToPx * morph.value
                                    translationY =
                                        blurWander.yDp.floatValue * driftDpToPx * morph.value
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .blur(SfLyricsBlurRadius),
                    )
                }
            }
        }

        // Apple Music's exact scrim colors over the blurred artwork.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SfCanvasScrimBrush),
        )
    }
}
