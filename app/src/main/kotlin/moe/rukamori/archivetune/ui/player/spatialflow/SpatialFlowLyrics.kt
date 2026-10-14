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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.player.SelectedLyrics
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
    contentReady: Boolean,
    backgroundBrush: Brush,
    artUrl: String? = null,
    revealProgressProvider: () -> Float,
    revealCenterProvider: () -> Offset?,
    contentColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    // Keep the native reveal/container, but let the shared selector own renderer policy.
    val lyricsMode by rememberEnumPreference(LyricsModeKey, defaultValue = LyricsMode.ENHANCED)

    var showLyricsMenu by remember { mutableStateOf(false) }
    var moreIconBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

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
            modifier = Modifier.fillMaxSize(),
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

                    lyricsMode != LyricsMode.ENHANCED || !syncedLyrics.isNullOrEmpty() ->
                        SelectedLyrics(
                            sliderPositionProvider = { null },
                            lyricsSyncOffset = 0,
                            modifier = Modifier.fillMaxSize(),
                            textColorOverride = contentColor,
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
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = moreIconBounds,
                lyricsProvider = { currentLyricsEntity },
                mediaMetadataProvider = { currentSong },
                lyricsSyncOffset = 0,
                onLyricsSyncOffsetChange = {},
                onDismiss = { showLyricsMenu = false },
            )
        }
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
    val animationsDisabled = moe.rukamori.archivetune.LocalAnimationsDisabled.current
    val morph = remember { Animatable(if (animationsDisabled) 1f else 0f) }
    LaunchedEffect(animationsDisabled) {
        if (animationsDisabled) morph.snapTo(1f)
        else morph.animateTo(1f, animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
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
        val blurAllowed = moe.rukamori.archivetune.LocalBlurPolicy.current.any
        val blurWander = rememberBlurWanderDrift(active = blurAllowed, maxDriftDp = wanderMaxDrift)
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
            if (!blurAllowed) {
                moe.rukamori.archivetune.ui.utils.BlurFreeArtworkBackdrop(
                    mediaId = artUrl,
                    artworkUrl = artUrl,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (isPreS) {
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
