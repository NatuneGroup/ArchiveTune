/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.player

import android.content.res.Configuration
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_READY
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.toBitmap
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.imageLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.BlurRadiusKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.LyricsBackgroundStyle
import moe.rukamori.archivetune.constants.LyricsBackgroundStyleKey
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.PlayerCustomBlurKey
import moe.rukamori.archivetune.constants.PlayerCustomBrightnessKey
import moe.rukamori.archivetune.constants.PlayerCustomContrastKey
import moe.rukamori.archivetune.constants.PlayerCustomImageUriKey
import moe.rukamori.archivetune.constants.PrioritizeWordSyncedLyricsKey
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.player.simpmusic.SimpMusicLyrics
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LyricsV2
import moe.rukamori.archivetune.ui.component.PlayerSliderTrack
import moe.rukamori.archivetune.ui.menu.LyricsMenu
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.ui.utils.rememberPreBlurredBitmap
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel
import moe.rukamori.archivetune.db.entities.FormatEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val AppleMusicFallbackGradient =
    listOf(
        Color(0xFF202020),
        Color(0xFF141414),
        Color(0xFF050505),
    )

private val LyricsSwipeStartRegion = 144.dp

// Scale of the blurred backdrop. Must cover the screen-proportional wander amplitude
// (movingBlurWanderMaxDriftDp — 0.85 of the half-diagonal, so the drift grows with the screen)
// plus the 64dp blur, and, because the walk rotates, must do so out to the container's furthest
// corner rather than its nearest edge. Kept in sync with AmLyricsBlurDriftScale in
// AppleMusicPlayer.kt.
//
// Scaling alone does NOT make the rotation safe: the layer Modifier.blur produces is clipped to the
// composable's bounds, so a rotated *screen-shaped* rectangle only covers a circle of
// 2.4 * min(W, H) / 2. blurBackdropFootprint is what closes that gap; see its docs.
private const val MovingBlurDriftScale = 2.4f
private val LyricsSwipeDismissThreshold = 96.dp

/**
 * Plumbs the lyrics-scroll signal up from [LyricsEnhanced] / [LyricsV2] (which own the
 * LazyListState internally) to [LyricsScreen] without changing every signature along the way.
 */
val LocalLyricsScrollListener = compositionLocalOf<(Boolean) -> Unit> { {} }

@Suppress("UNUSED_PARAMETER")
@Composable
fun LyricsScreen(
    mediaMetadata: MediaMetadata,
    onBackClick: () -> Unit,
    navController: NavController,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    onQueueClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    backHandlerEnabled: Boolean = true,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val view = LocalView.current

    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val deviceMusicVolumeController = rememberDeviceMusicVolumeController()
    val onVolumeChange =
        remember(deviceMusicVolumeController) {
            { volume: Float ->
                deviceMusicVolumeController.setVolumeFraction(volume)
            }
        }
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentSongLiked = currentSong?.song?.liked == true

    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val lyricsMode by rememberEnumPreference(LyricsModeKey, LyricsMode.ENHANCED)
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val configuredLyricsBackground by rememberEnumPreference(LyricsBackgroundStyleKey, LyricsBackgroundStyle.DEFAULT)
    val lyricsBackground = configuredLyricsBackground.resolveFor(playerBackground)
    val disableBlur by rememberPreference(DisableBlurKey, false)
    val blurRadius by rememberPreference(BlurRadiusKey, 48f)
    val playerCustomImageUri by rememberPreference(PlayerCustomImageUriKey, "")
    val playerCustomBlur by rememberPreference(PlayerCustomBlurKey, 0f)
    val playerCustomContrast by rememberPreference(PlayerCustomContrastKey, 1f)
    val playerCustomBrightness by rememberPreference(PlayerCustomBrightnessKey, 1f)
    val foregroundColor = Color.White
    val density = LocalDensity.current
    val swipeStartRegionPx = with(density) { LyricsSwipeStartRegion.toPx() }
    val swipeDismissThresholdPx = with(density) { LyricsSwipeDismissThreshold.toPx() }
    // The lyrics controls never hide: the bar carries the scrubber, the quality badge and the
    // lyrics provider, none of which is worth playing hide-and-seek with, and a control you have
    // to poke the screen to see is worse than one that is simply there.
    // isUserScrollingLyrics is still collected because LyricsEnhanced / LyricsV2 report it through
    // LocalLyricsScrollListener; nothing downstream needs it now.
    var isUserScrollingLyrics by remember { mutableStateOf(false) }

    val hapticClick =
        remember(enableHapticFeedback, view) {
            {
                if (enableHapticFeedback) {
                    view.performHapticFeedback(
                        HapticFeedbackConstants.CONTEXT_CLICK,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                    )
                }
            }
        }
    val lyricsHelper =
        remember(context) {
            EntryPointAccessors
                .fromApplication(
                    context.applicationContext,
                    moe.rukamori.archivetune.di.LyricsHelperEntryPoint::class.java,
                ).lyricsHelper()
        }

    // Read through the preference rather than inside the effect so that flipping the toggle while
    // the panel is open re-runs the fetch instead of leaving the stored lyrics untouched.
    val (prioritizeWordSynced) = rememberPreference(PrioritizeWordSyncedLyricsKey, defaultValue = false)

    LaunchedEffect(
        mediaMetadata.id,
        currentLyrics?.lyrics,
        currentLyrics?.providerName,
        prioritizeWordSynced,
    ) {
        val snapshot = currentLyrics
        // A stored row that the word-synced toggle can still improve is a reason to fetch, not only
        // a missing one. Without this the gate returns before LyricsHelper is asked and turning the
        // toggle on changed nothing for any song that already had line-synced lyrics stored.
        val needsFetch =
            snapshot == null ||
                snapshot.lyrics == LyricsEntity.LYRICS_NOT_FOUND ||
                snapshot.providerName.isBlank() ||
                LyricsUtils.needsWordSyncedUpgrade(prioritizeWordSynced, snapshot)
        if (!needsFetch) return@LaunchedEffect
        try {
            val existingLyrics =
                withContext(Dispatchers.IO) {
                    database.lyrics(mediaMetadata.id).first()
                }

            val hasValidLyrics =
                existingLyrics != null &&
                    existingLyrics.lyrics != LyricsEntity.LYRICS_NOT_FOUND
            // Turning "Prioritise word synced lyrics" on has to be able to *replace* a row stored
            // before it was on, not merely fill an empty one; see LyricsUtils.needsWordSyncedUpgrade
            // for which rows are eligible and LyricsHelper for why only a settled sweep consumes
            // the attempt, so a song no provider has word timings for is not re-swept on every visit.
            val upgradeToWordSynced =
                lyricsHelper.shouldAttemptWordSyncedUpgrade(
                    mediaId = mediaMetadata.id,
                    stored = existingLyrics,
                )
            if (!upgradeToWordSynced &&
                existingLyrics != null &&
                hasValidLyrics &&
                existingLyrics.providerName.isNotBlank()
            ) {
                return@LaunchedEffect
            }

            val lyricsResult =
                withContext(Dispatchers.IO) {
                    lyricsHelper.getLyricsWithProvider(mediaMetadata)
                }
            withContext(Dispatchers.IO) {
                database.query {
                    if (upgradeToWordSynced &&
                        existingLyrics != null &&
                        LyricsUtils.hasWordSyncedLyrics(lyricsResult.lyrics)
                    ) {
                        // Replace only the row that was inspected: a better result written while
                        // the providers were being queried must survive.
                        upgradeLyricsIfUnchanged(
                            id = mediaMetadata.id,
                            expectedLyrics = existingLyrics.lyrics,
                            expectedSource = existingLyrics.source,
                            lyrics = lyricsResult.lyrics,
                            providerName = lyricsResult.providerName,
                        )
                    } else if (hasValidLyrics) {
                        backfillLyricsProviderName(
                            id = mediaMetadata.id,
                            providerName = lyricsResult.providerName,
                        )
                    } else {
                        replaceLyricsIfAbsentOrNotFound(
                            id = mediaMetadata.id,
                            lyrics = lyricsResult.lyrics,
                            providerName = lyricsResult.providerName,
                        )
                    }
                }
            }
            if (upgradeToWordSynced &&
                existingLyrics != null &&
                lyricsResult.lyrics != LyricsEntity.LYRICS_NOT_FOUND
            ) {
                lyricsHelper.noteWordSyncedUpgradeSettled(mediaMetadata.id, existingLyrics.lyrics)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ─── Automatic AI translation ───────────────────────────────────────
    // When the user has enabled "Automatic translation" in AI Integration
    // settings, we kick off a background AI translation as soon as lyrics
    // arrive in a foreign script. Detection is conservative: only lyrics
    // with a meaningful run of non-Latin characters (CJK, Cyrillic, Arabic,
    // Devanagari, etc.) trigger auto-translation, so Spanish-to-English
    // and other Latin-to-Latin cases don't waste API calls. Lyrics that
    // are already an AI translation are skipped to avoid re-translating
    // the same song on every visit. The success toast is suppressed
    // inside the ViewModel when this pref is on (see LyricsMenuViewModel).
    val (autoTranslateLyrics) = rememberPreference(AutoTranslateLyricsKey, defaultValue = false)
    val (translatorTargetLang) = rememberPreference(TranslatorTargetLangKey, defaultValue = "")
    // "Don't auto translate these languages". Read here and passed explicitly below — leaving it to
    // shouldAutoTranslate's old default was exactly how this setting came to do nothing.
    val (autoTranslateExcludedLanguages) =
        rememberPreference(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
    val lyricsMenuViewModel: LyricsMenuViewModel = hiltViewModel()
    // Observe the set of media IDs the user has dismissed translation for.
    // When a user clicks "Undo Translation", the mediaId is added to this set;
    // auto-translate is suppressed for dismissed songs until the user manually
    // triggers translation again (which clears the dismissal in the ViewModel).
    val translationDismissedMediaIds by lyricsMenuViewModel.translationDismissedMediaIds
        .collectAsStateWithLifecycle()
    LaunchedEffect(
        mediaMetadata.id,
        currentLyrics?.lyrics,
        currentLyrics?.source,
        autoTranslateLyrics,
        translatorTargetLang,
        // In the key list so unticking a language re-evaluates the current track instead of waiting
        // for the next one.
        autoTranslateExcludedLanguages,
        translationDismissedMediaIds,
    ) {
        if (!autoTranslateLyrics) return@LaunchedEffect
        val snapshot = currentLyrics ?: return@LaunchedEffect
        val text = snapshot.lyrics ?: return@LaunchedEffect
        if (text.isBlank() || text == LyricsEntity.LYRICS_NOT_FOUND) return@LaunchedEffect
        // Skip if these lyrics were already AI-translated AND actually contain
        // translation content. The `hasTranslation` guard is important: a previous
        // translation attempt may have no-op'd — the AI returned the same text, a
        // common failure mode for CJK lyrics. Without this check, those songs
        // would be blocked from retrying forever.
        if (snapshot.source in setOf(LyricsEntity.Source.AI_TRANSLATION.value, LyricsEntity.Source.TRANSLATION.value) &&
            LyricsUtils.hasTranslation(text)
        ) return@LaunchedEffect

        // Skip auto-translate if the user has dismissed translation for this
        // song. The user clicked "Undo Translation" — they explicitly do not
        // want the translation back. Auto-translate will resume only after the
        // user manually triggers translation (which clears the dismissal).
        if (mediaMetadata.id in translationDismissedMediaIds) return@LaunchedEffect

        if (!LyricsUtils.shouldAutoTranslate(
                lyrics = text,
                targetLanguage = translatorTargetLang,
                excludedLanguageCodes = autoTranslateExcludedLanguages,
            )
        ) {
            return@LaunchedEffect
        }

        lyricsMenuViewModel.translateLyricsWithAi(
            mediaMetadata = mediaMetadata,
            lyrics = text,
            targetLanguage = translatorTargetLang,
            automatic = true,
        )
    }

    val positionState = remember(mediaMetadata.id) { mutableLongStateOf(0L) }
    val durationState = remember(mediaMetadata.id) { mutableLongStateOf(C.TIME_UNSET) }
    var sliderPosition by remember(mediaMetadata.id) { mutableStateOf<Long?>(null) }

    var gradientColors by remember { mutableStateOf(AppleMusicFallbackGradient) }
    var hasValidPalette by remember { mutableStateOf(false) }

    val fallbackColor = remember { Color.Black.toArgb() }
    val darkTheme = isSystemInDarkTheme()

    LaunchedEffect(mediaMetadata.id, mediaMetadata.thumbnailUrl, lyricsBackground, darkTheme) {

        

        

        
        
        kotlinx.coroutines.delay(120)
        if (lyricsBackground != LyricsBackgroundStyle.DEFAULT &&
            lyricsBackground != LyricsBackgroundStyle.COLORING &&
            lyricsBackground != LyricsBackgroundStyle.MOVING_BLUR
        ) {
            gradientColors = AppleMusicFallbackGradient
            hasValidPalette = false
            return@LaunchedEffect
        }
        val thumbnailUrl = mediaMetadata.thumbnailUrl
        if (thumbnailUrl == null) {
            if (!hasValidPalette) gradientColors = AppleMusicFallbackGradient
            return@LaunchedEffect
        }

        val cacheKey =
            PlayerPaletteCacheKey(
                mediaId = mediaMetadata.id,
                provider = guessArtworkProvider(thumbnailUrl),
                artworkIdentity = thumbnailUrl,
                backgroundMode = lyricsBackground.name,
                darkTheme = darkTheme,
            )
        PlayerPaletteCache.get(cacheKey)?.let {
            gradientColors = it
            hasValidPalette = true
            return@LaunchedEffect
        }

        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(Size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE))
                .allowHardware(false)
                .build()

        val extractedColors =
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }
                if (result !is SuccessResult) {
                    null
                } else {
                    val bitmap = result.image?.toBitmap()
                    if (bitmap == null) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            val palette =
                                Palette
                                    .from(bitmap)
                                    .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                    .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                    .generate()
                            PlayerColorExtractor.extractGradientColors(
                                palette = palette,
                                fallbackColor = fallbackColor,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        
        if (extractedColors != null) {
            val stillCurrent =
                mediaMetadata.thumbnailUrl == thumbnailUrl
            if (stillCurrent) {
                PlayerPaletteCache.put(cacheKey, extractedColors)
                gradientColors = extractedColors
                hasValidPalette = true
            }
        } else if (!hasValidPalette) {
            gradientColors = AppleMusicFallbackGradient
        }
    }

    LaunchedEffect(player, playbackState, mediaMetadata.id) {
        if (playbackState != STATE_READY && playbackState != STATE_BUFFERING) return@LaunchedEffect
        while (isActive) {
            positionState.longValue = player.currentPosition.coerceAtLeast(0L)
            durationState.longValue = player.duration
            delay(250)
        }
    }

    // The lyric actions open as 4nx3b's Apple Music popup, anchored to whichever three-dot was
    // tapped. It belongs to this screen's design, not to the Apple Music Experience switch.
    var showAnchoredLyricsMenu by remember { mutableStateOf(false) }
    var lyricsMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var headerMoreBounds by remember { mutableStateOf(Rect.Zero) }
    var controlsMoreBounds by remember { mutableStateOf(Rect.Zero) }
    val popupBackdrop: PlatformBackdrop? =
        if (rememberLiquidGlassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
        } else {
            null
        }
    val openLyricsMenu: (Rect) -> Unit = { anchor ->
        lyricsMenuAnchor = anchor
        showAnchoredLyricsMenu = true
    }
    val openLyricsMenuFromHeader = { openLyricsMenu(headerMoreBounds) }
    val openLyricsMenuFromControls = { openLyricsMenu(controlsMoreBounds) }

    // Feeds the quality badge in the controls bar, the same source the players read.
    val currentFormat by playerConnection.currentFormat.collectAsStateWithLifecycle(initialValue = null)

    val isLoading = playbackState == STATE_BUFFERING || sliderPosition != null
    val orientation = LocalConfiguration.current.orientation
    // Always shown, always expanded — see the note on the controls bar above.
    val controlsVisible = true
    val controlsExpanded = true
    val onControlsPositionChange: (Long) -> Unit = {
        sliderPosition = it
    }
    val onControlsPositionChangeFinished: () -> Unit = {
        sliderPosition?.let { targetPosition ->
            player.seekTo(targetPosition)
            positionState.longValue = targetPosition
        }
        sliderPosition = null
    }
    val onControlsVolumeChange: (Float) -> Unit = {
        onVolumeChange(it)
    }
    val onControlsPreviousClick = {
        hapticClick()
        playerConnection.seekToPrevious()
    }
    val onControlsPlayPauseClick = {
        hapticClick()
        player.togglePlayPause()
    }
    val onControlsNextClick = {
        hapticClick()
        playerConnection.seekToNext()
    }

    BackHandler(enabled = backHandlerEnabled, onBack = onBackClick)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                // Root-level pointer handler for the edge-swipe dismiss. Sitting on the ROOT
                // container makes it an ancestor of every hit path: Compose delivers pointer
                // events to the hit node and its ancestors, so taps land here even when the
                // lyrics LazyColumn consumes them for scrolling.
                .pointerInput(
                    swipeStartRegionPx,
                    swipeDismissThresholdPx,
                    onBackClick,
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.y <= swipeStartRegionPx) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break

                                val deltaX = change.position.x - down.position.x
                                val deltaY = change.position.y - down.position.y
                                if (deltaY < 0f || abs(deltaX) > abs(deltaY)) break
                                // Claim a downward gesture before the player sheet can drag
                                // underneath the lyrics overlay. Taps remain unconsumed.
                                if (deltaY > 0f) change.consume()
                                if (isAppleMusicLyricsDismissDrag(deltaX, deltaY, swipeDismissThresholdPx)) {
                                    onBackClick()
                                    break
                                }
                            }
                        }
                    }
                },
    ) {
        LyricsScreenBackground(
            modifier = if (popupBackdrop != null) Modifier.layerBackdrop(popupBackdrop) else Modifier,
            style = lyricsBackground,
            mediaMetadata = mediaMetadata,
            gradientColors = gradientColors,
            disableBlur = disableBlur,
            blurRadius = blurRadius,
            playerCustomImageUri = playerCustomImageUri,
            playerCustomBlur = playerCustomBlur,
            playerCustomContrast = playerCustomContrast,
            playerCustomBrightness = playerCustomBrightness,
        )

        // Keeps unconsumed taps from falling through to whatever is layered below the
        // lyrics screen (e.g. the player behind it).
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeUnhandledPointerInput(),
        )

        CompositionLocalProvider(LocalLyricsScrollListener provides { scrolling ->
            if (isUserScrollingLyrics != scrolling) isUserScrollingLyrics = scrolling
        }) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                AppleMusicGrabber(onClick = onBackClick)
                AppleMusicTrackHeader(
                    mediaMetadata = mediaMetadata,
                    foregroundColor = foregroundColor,
                    onMoreClick = openLyricsMenuFromHeader,
                    onMorePositioned = { headerMoreBounds = it },
                    onDismissClick = onBackClick,
                    isLiked = currentSongLiked,
                    onToggleLike = playerConnection::toggleLike,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                )

                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    AnimatedContent(
                    targetState = controlsVisible,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                    },
                    label = "lyrics-landscape-controls",
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) { controlsVisible ->
                    if (controlsVisible) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 36.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppleMusicLyricsPane(
                                lyricsMode = lyricsMode,
                                foregroundColor = foregroundColor,
                                sliderPositionProvider = { sliderPosition },
                                lyricsSyncOffset = lyricsSyncOffset,
                                modifier =
                                    Modifier
                                        .weight(1.15f)
                                        .fillMaxHeight()
                                        .padding(end = 32.dp),
                            )

                            Column(
                                modifier =
                                    Modifier
                                        .weight(0.85f)
                                        .widthIn(max = 420.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                AppleMusicControls(
                                    positionProvider = { positionState.longValue },
                                    durationProvider = { durationState.longValue },
                                    sliderPosition = sliderPosition,
                                    controlsExpanded = controlsExpanded,
                                    isPlaying = isPlaying,
                                    isLoading = isLoading,
                                    volume = deviceMusicVolumeController.volumeFraction,
                                    onPositionChange = onControlsPositionChange,
                                    onPositionChangeFinished = onControlsPositionChangeFinished,
                                    onVolumeChange = onControlsVolumeChange,
                                    onPreviousClick = onControlsPreviousClick,
                                    onPlayPauseClick = onControlsPlayPauseClick,
                                    onNextClick = onControlsNextClick,
                                    onControlsInteraction = {},
                                    foregroundColor = foregroundColor,
                                    currentFormat = currentFormat,
                                    lyricsProviderName = currentLyrics?.providerName.orEmpty(),
                                    hasLyrics = currentLyrics != null,
                                    onOverflowClick = openLyricsMenuFromControls,
                                    onOverflowPositioned = { controlsMoreBounds = it },
                                    onCloseClick = onBackClick,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        AppleMusicLyricsPane(
                            lyricsMode = lyricsMode,
                            foregroundColor = foregroundColor,
                            sliderPositionProvider = { sliderPosition },
                            lyricsSyncOffset = lyricsSyncOffset,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 36.dp, vertical = 8.dp),
                        )
                    }
                }
            } else {
                AppleMusicLyricsPane(
                    lyricsMode = lyricsMode,
                    foregroundColor = foregroundColor,
                    sliderPositionProvider = { sliderPosition },
                    lyricsSyncOffset = lyricsSyncOffset,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )

                AnimatedVisibility(
                    visible = controlsVisible,
                    enter =
                        fadeIn(tween(120)) +
                            slideInVertically(tween(180)) { fullHeight -> fullHeight / 6 },
                    exit =
                        fadeOut(tween(90)) +
                            slideOutVertically(tween(140)) { fullHeight -> fullHeight / 8 },
                    label = "lyrics-player-controls",
                ) {
                    AppleMusicControls(
                        positionProvider = { positionState.longValue },
                        durationProvider = { durationState.longValue },
                        sliderPosition = sliderPosition,
                        controlsExpanded = controlsExpanded,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        volume = deviceMusicVolumeController.volumeFraction,
                        onPositionChange = onControlsPositionChange,
                        onPositionChangeFinished = onControlsPositionChangeFinished,
                        onVolumeChange = onControlsVolumeChange,
                        onPreviousClick = onControlsPreviousClick,
                        onPlayPauseClick = onControlsPlayPauseClick,
                        onNextClick = onControlsNextClick,
                        onControlsInteraction = {},
                        foregroundColor = foregroundColor,
                        currentFormat = currentFormat,
                        lyricsProviderName = currentLyrics?.providerName.orEmpty(),
                        hasLyrics = currentLyrics != null,
                        onOverflowClick = openLyricsMenuFromControls,
                        onOverflowPositioned = { controlsMoreBounds = it },
                        onCloseClick = onBackClick,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp),
                    )
                }
            }
            }
        }

        if (showAnchoredLyricsMenu) {
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = lyricsMenuAnchor,
                lyricsProvider = { currentLyrics },
                mediaMetadataProvider = { mediaMetadata },
                lyricsSyncOffset = lyricsSyncOffset,
                onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
                onDismiss = { showAnchoredLyricsMenu = false },
                backdrop = popupBackdrop,
            )
        }
    }
}

@Composable
private fun LyricsScreenBackground(
    style: LyricsBackgroundStyle,
    mediaMetadata: MediaMetadata,
    gradientColors: List<Color>,
    disableBlur: Boolean,
    blurRadius: Float,
    playerCustomImageUri: String,
    playerCustomBlur: Float,
    playerCustomContrast: Float,
    playerCustomBrightness: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    if (style == LyricsBackgroundStyle.FOLLOW_THEME) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Black
                    },
                ),
    ) {
        when (style) {
            LyricsBackgroundStyle.DEFAULT -> {
                AppleMusicBackground(
                    mediaMetadata = mediaMetadata,
                    gradientColors = gradientColors,
                )
            }

            LyricsBackgroundStyle.MOVING_BLUR -> {
                MovingBlurBackground(
                    mediaMetadata = mediaMetadata,
                    gradientColors = gradientColors,
                )
            }

            LyricsBackgroundStyle.FOLLOW_THEME -> Unit

            LyricsBackgroundStyle.COLORING,
            LyricsBackgroundStyle.CUSTOM,
            -> {
                PlayerBackground(
                    playerBackground =
                        if (style == LyricsBackgroundStyle.CUSTOM) {
                            PlayerBackgroundStyle.CUSTOM
                        } else {
                            PlayerBackgroundStyle.COLORING
                        },
                    mediaMetadata = mediaMetadata,
                    gradientColors = gradientColors,
                    disableBlur = disableBlur,
                    blurRadius = blurRadius,
                    playerCustomImageUri = playerCustomImageUri,
                    playerCustomBlur = playerCustomBlur,
                    playerCustomContrast = playerCustomContrast,
                    playerCustomBrightness = playerCustomBrightness,
                )
            }
        }
    }
}

@Composable
private fun MovingBlurBackground(
    mediaMetadata: MediaMetadata,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val colors = if (gradientColors.isNotEmpty()) gradientColors else AppleMusicFallbackGradient

    val backgroundBrush =
        remember(colors) {
            Brush.verticalGradient(
                listOf(
                    // Veil over the blurred artwork, kept well below the static
                    // AppleMusicBackground's alphas (0.88 / 0.76 / 0.96): at that strength the
                    // colour mass behind these lyrics flattened into a near-solid block instead of
                    // reading as artwork, so the page looked dimmer than the player it sits in.
                    colors.getOrElse(0) { AppleMusicFallbackGradient[0] }.copy(alpha = 0.55f),
                    colors.getOrElse(1) { AppleMusicFallbackGradient[1] }.copy(alpha = 0.42f),
                    colors.getOrElse(2) { AppleMusicFallbackGradient[2] }.copy(alpha = 0.62f),
                ),
            )
        }
    val bottomScrim =
        remember {
            Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.10f),
                ),
            )
        }

    // Vibrancy ColorFilter applied ONLY to the moving-blur lyrics background — does not touch
    // the shared PlayerColorExtractor palette (which other screens consume). 1.6× saturation
    // gives Apple-Music-style vivid artwork colors that punch through the 64-dp blur.
    // ColorMatrix is built manually because androidx.compose.ui.graphics.ColorMatrix doesn't
    // expose setSaturation() (unlike android.graphics.ColorMatrix). The matrix below is the
    // standard saturation matrix: R' = αR + βG + βB, G' = βR + αG + βB, B' = βR + βG + αB,
    // where α = 0.213 + 0.787*sat and β = 0.715 - 0.715*sat (Rec. 709 luma coefficients),
    // and the existing gamma is preserved (sat=1 → identity).
    val vibrancyColorFilter = remember {
        val sat = 1.85f
        val alpha = 0.213f + 0.787f * sat
        val beta = 0.715f - 0.715f * sat
        val gamma = 0.072f - 0.072f * sat
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    alpha, beta, gamma, 0f, 0f,
                    alpha, beta, gamma, 0f, 0f,
                    alpha, beta, gamma, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    // Pre-Android S can't use Modifier.blur (it requires RenderEffect, API 31+). We use sang's
    // pure-Kotlin stack-blur fallback (rukamori/ArchiveTune#924): load the thumbnail, blur it once
    // with ImageBlurUtils, render via Image.
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .background(AppleMusicFallbackGradient.last()),
    ) {
        // Screen-proportional wander amplitude, shared with every other player style so the moving
        // blur feels identical everywhere: the colour mass traverses the whole display instead of
        // orbiting a narrow ring around the centre. Hoisted here because the amplitude needs the
        // measured size.
        val wanderMaxDrift = movingBlurWanderMaxDriftDp(maxWidth, maxHeight)
        val blurWander = rememberBlurWanderDrift(active = true, maxDriftDp = wanderMaxDrift)
        val preSDriftScale =
            if (isPreS) {
                val safetyMargin = 48.dp
                val requiredScaleX = 1f + 2f * (wanderMaxDrift + safetyMargin.value) / maxWidth.value
                val requiredScaleY = 1f + 2f * (wanderMaxDrift + safetyMargin.value) / maxHeight.value
                maxOf(requiredScaleX, requiredScaleY, 1.4f)
            } else {
                MovingBlurDriftScale
            }

        // Footprint of the drifting layer. NOT the screen: the layer Modifier.blur creates is
        // clipped to its own bounds, so a screen-shaped rectangle rotated by the walk only covers a
        // circle of MovingBlurDriftScale * min(W, H) / 2 = 432dp on a 360x800 phone, against the
        // 559dp the furthest corner plus the drift needs — the missing wedge is what read as black
        // artefacts rotating through the corners. Widening the short side fixes it without changing
        // the look, because max(W, H) — and so ContentScale.Crop's scale factor — is untouched.
        // See blurBackdropFootprint.
        val driftFootprint =
            remember(maxWidth, maxHeight) {
                blurBackdropFootprint(
                    width = maxWidth,
                    height = maxHeight,
                    // No ramp here: this backdrop is only ever composed with lyrics open, so it
                    // sits at the drifting scale the whole time.
                    restScale = MovingBlurDriftScale,
                    driftScale = MovingBlurDriftScale,
                    maxDriftDp = wanderMaxDrift,
                )
            }

        AnimatedContent(
            targetState = mediaMetadata.thumbnailUrl,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
            label = "lyrics-moving-blur-bg",
        ) { thumbnailUrl ->
            if (thumbnailUrl != null) {
                if (isPreS) {
                    // Bare-URL cache key: this blur deliberately shares the decoded artwork with
                    // the other requests for the song instead of caching a second copy of it.
                    val blurredBitmap =
                        rememberPreBlurredBitmap(
                            imageUrl = thumbnailUrl,
                            radiusDp = 64.dp,
                            cacheKey = thumbnailUrl,
                        )
                    blurredBitmap?.let { bm ->
                        Image(
                            bitmap = bm.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = vibrancyColorFilter,
                            modifier = Modifier
                                .fillMaxSize()
                                .playerSeekDoubleTap()
                                .graphicsLayer {
                                    // Translation only: the pre-S bitmap is screen-shaped, so
                                    // rotating it would uncover the corners (the post-S path
                                    // rotates a square footprint safely).
                                    scaleX = preSDriftScale
                                    scaleY = preSDriftScale
                                    translationX = blurWander.xDp.floatValue.dp.toPx()
                                    translationY = blurWander.yDp.floatValue.dp.toPx()
                                }
                                // No offset: the pre-S fallback pins its single pre-blurred
                                // bitmap (see above), so there is nothing to animate here.
                                .alpha(0.95f),
                        )
                    }
                } else {
                    // Centred inside a full-size box rather than filling it: the image is
                    // deliberately larger than the screen (see driftFootprint) and requiredSize is
                    // what lets it ignore the incoming constraints. The overflow is clipped by the
                    // clipToBounds on the BoxWithConstraints above.
                    Box(
                        modifier = Modifier.fillMaxSize().playerSeekDoubleTap(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = vibrancyColorFilter,
                            modifier = Modifier
                                .requiredSize(driftFootprint)
                                // graphicsLayer OUTSIDE blur: the blur is applied to the
                                // centered image (inside graphicsLayer), then the scale +
                                // translation is applied to the blurred result, so the blur
                                // never samples transparent areas at the translated image's
                                // trailing edge. The flip side is that the blur clips its
                                // result to this element's bounds, which is why those bounds
                                // are the driftFootprint and not the screen.
                                .graphicsLayer {
                                    scaleX = MovingBlurDriftScale
                                    scaleY = MovingBlurDriftScale
                                    // Deferred read — see blurWander above.
                                    translationX = blurWander.xDp.floatValue.dp.toPx()
                                    translationY = blurWander.yDp.floatValue.dp.toPx()
                                    // Rotation is the only part of the walk that can
                                    // carry a colour across the whole surface;
                                    // translation moves every colour by the same
                                    // vector, so on its own it leaves the top the top.
                                    // See BlurWanderDrift.
                                    rotationZ = blurWander.rotationDeg.floatValue
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .blur(64.dp)
                                .alpha(0.95f),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bottomScrim),
        )
    }
}

@Composable
private fun AppleMusicBackground(
    mediaMetadata: MediaMetadata,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val colors = if (gradientColors.isNotEmpty()) gradientColors else AppleMusicFallbackGradient
    val backgroundBrush =
        remember(colors) {
            Brush.verticalGradient(
                listOf(
                    colors.getOrElse(0) { AppleMusicFallbackGradient[0] }.copy(alpha = 0.88f),
                    colors.getOrElse(1) { AppleMusicFallbackGradient[1] }.copy(alpha = 0.76f),
                    colors.getOrElse(2) { AppleMusicFallbackGradient[2] }.copy(alpha = 0.96f),
                ),
            )
        }
    val bottomScrim =
        remember {
            Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.28f),
                ),
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppleMusicFallbackGradient.last()),
    ) {
        val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        AnimatedContent(
            targetState = mediaMetadata.thumbnailUrl,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
            label = "lyrics-apple-background",
        ) { thumbnailUrl ->
            if (thumbnailUrl != null) {
                if (isPreS) {
                    val blurredBitmap =
                        rememberPreBlurredBitmap(
                            imageUrl = thumbnailUrl,
                            radiusDp = 46.dp,
                            cacheKey = "$thumbnailUrl#lyricsbg",
                        )
                    blurredBitmap?.let { bm ->
                        Image(
                            bitmap = bm.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .playerSeekDoubleTap()
                                    .alpha(0.62f),
                        )
                    }
                } else {
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .playerSeekDoubleTap()
                                .blur(46.dp)
                                .alpha(0.62f),
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(backgroundBrush),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(bottomScrim),
        )
    }
}

@Composable
private fun AppleMusicGrabber(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeDescription = stringResource(R.string.close)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = closeDescription }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
    )
}

@Composable
private fun AppleMusicTrackHeader(
    mediaMetadata: MediaMetadata,
    foregroundColor: Color,
    onMoreClick: () -> Unit,
    onDismissClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onMorePositioned: ((Rect) -> Unit)? = null,
) {
    val artistText =
        remember(mediaMetadata.id, mediaMetadata.artists) {
            mediaMetadata.artists.joinToString { it.name }
        }

    Row(
        modifier = modifier.heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(foregroundColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = mediaMetadata.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (mediaMetadata.thumbnailUrl == null) {
                Icon(
                    painter = painterResource(R.drawable.player_music_note),
                    contentDescription = null,
                    tint = foregroundColor.copy(alpha = 0.72f),
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = foregroundColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artistText,
                style = MaterialTheme.typography.bodyLarge,
                color = foregroundColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Close (cross) button — required so users can dismiss the lyrics sheet
        // without relying on the system back gesture. Sits to the left of the
        // overflow menu icon (and to the left of the heart button). Matches
        // upstream rukamori/ArchiveTune's lyrics top bar layout.
        AppleMusicHeaderIconButton(
            iconRes = R.drawable.close,
            contentDescription = stringResource(R.string.close),
            foregroundColor = foregroundColor,
            onClick = onDismissClick,
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Favourite (heart) button — matches Apple Music's lyrics page where the
        // heart icon sits to the right of the song title/artist. Tapping toggles
        // the like state on the current song.
        AppleMusicHeaderIconButton(
            iconRes = if (isLiked) R.drawable.player_favorite else R.drawable.player_favorite_border,
            contentDescription = stringResource(
                if (isLiked) R.string.action_remove_like else R.string.action_like,
            ),
            foregroundColor = foregroundColor,
            onClick = onToggleLike,
        )

        Spacer(modifier = Modifier.width(4.dp))

        AppleMusicHeaderIconButton(
            iconRes = R.drawable.player_more_horiz,
            contentDescription = stringResource(R.string.more_options),
            foregroundColor = foregroundColor,
            onClick = onMoreClick,
            onPositioned = onMorePositioned,
        )
    }
}

@Composable
private fun AppleMusicHeaderIconButton(
    iconRes: Int,
    contentDescription: String,
    foregroundColor: Color,
    onClick: () -> Unit,
    onPositioned: ((Rect) -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .let { base ->
                    if (onPositioned != null) {
                        base.onGloballyPositioned { coords -> onPositioned(coords.boundsInRoot()) }
                    } else {
                        base
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 24.dp),
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(foregroundColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = foregroundColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun AppleMusicLyricsPane(
    lyricsMode: LyricsMode,
    foregroundColor: Color,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    LyricsContent(
        lyricsMode = lyricsMode,
        sliderPositionProvider = sliderPositionProvider,
        lyricsSyncOffset = lyricsSyncOffset,
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        textColor = foregroundColor,
    )
}

@Composable
private fun AppleMusicControls(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    sliderPosition: Long?,
    controlsExpanded: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    volume: Float,
    onPositionChange: (Long) -> Unit,
    onPositionChangeFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onControlsInteraction: () -> Unit,
    foregroundColor: Color,
    currentFormat: FormatEntity?,
    lyricsProviderName: String,
    hasLyrics: Boolean,
    onOverflowClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOverflowPositioned: ((Rect) -> Unit)? = null,
) {
    val position = positionProvider()
    val duration = durationProvider()
    val hasDuration = duration != C.TIME_UNSET && duration > 0L
    val safeDuration = if (hasDuration) duration else 1L
    val currentPosition = (sliderPosition ?: position).coerceIn(0L, safeDuration)
    val remainingPosition = (safeDuration - currentPosition).coerceAtLeast(0L)

    Column(
        modifier =
            modifier
                .offset(y = (-6).dp)
                .pointerInput(controlsExpanded) {
                    if (controlsExpanded) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onControlsInteraction()
                    }
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppleMusicSlider(
            value = currentPosition.toFloat(),
            valueRange = 0f..safeDuration.toFloat(),
            activeColor = foregroundColor.copy(alpha = 0.94f),
            inactiveColor = foregroundColor.copy(alpha = 0.28f),
            trackHeight = 8.dp,
            onValueChange = { onPositionChange(it.toLong()) },
            onValueChangeFinished = onPositionChangeFinished,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = makeTimeString(currentPosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = foregroundColor.copy(alpha = 0.54f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hasDuration) "-${makeTimeString(remainingPosition)}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = foregroundColor.copy(alpha = 0.54f),
                    )
                }
            }
            // Centred on the row rather than placed between the two timestamps: that gap changes
            // width by a digit every time a minute rolls over, and a badge that shifts with the
            // clock reads as a glitch.
            LosslessOrStats(
                isLoading = isLoading,
                format = currentFormat,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = controlsExpanded,
            enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { fullHeight -> fullHeight / 8 },
            exit = fadeOut(tween(90)) + slideOutVertically(tween(120)) { fullHeight -> fullHeight / 10 },
            label = "lyrics-expanded-player-controls",
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 26.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppleMusicTransportButton(
                        iconRes = R.drawable.player_fast_forward,
                        contentDescription = stringResource(R.string.widget_previous),
                        iconSize = 44.dp,
                        touchSize = 68.dp,
                        foregroundColor = foregroundColor,
                        mirrored = true,
                        onClick = onPreviousClick,
                    )
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier.size(74.dp),
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(42.dp),
                                color = foregroundColor,
                            )
                        } else {
                            Icon(
                                painter = painterResource(if (isPlaying) R.drawable.player_pause else R.drawable.player_play),
                                contentDescription =
                                    if (isPlaying) {
                                        stringResource(R.string.widget_pause)
                                    } else {
                                        stringResource(R.string.play)
                                    },
                                tint = foregroundColor,
                                modifier = Modifier.size(54.dp),
                            )
                        }
                    }
                    AppleMusicTransportButton(
                        iconRes = R.drawable.player_fast_forward,
                        contentDescription = stringResource(R.string.next),
                        iconSize = 44.dp,
                        touchSize = 68.dp,
                        foregroundColor = foregroundColor,
                        mirrored = false,
                        onClick = onNextClick,
                    )
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.player_volume_min),
                        contentDescription = stringResource(R.string.minimum_volume),
                        tint = foregroundColor.copy(alpha = 0.66f),
                        modifier = Modifier.size(17.dp),
                    )
                    AppleMusicSlider(
                        value = volume.coerceIn(0f, 1f),
                        valueRange = 0f..1f,
                        activeColor = foregroundColor.copy(alpha = 0.88f),
                        inactiveColor = foregroundColor.copy(alpha = 0.24f),
                        trackHeight = 8.dp,
                        onValueChange = onVolumeChange,
                        onValueChangeFinished = {},
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.player_volume_up),
                        contentDescription = stringResource(R.string.maximum_volume),
                        tint = foregroundColor.copy(alpha = 0.66f),
                        modifier = Modifier.size(19.dp),
                    )
                }

                // The credit, and beside it the way out. Ported from the BitChord style, where
                // this row already sat under the scrubber, so every lyrics style now names its
                // provider and offers the lyric actions in the same place.
                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(foregroundColor.copy(alpha = 0.10f))
                                .clickable(onClick = onOverflowClick)
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text =
                                when {
                                    lyricsProviderName.isNotBlank() ->
                                        stringResource(R.string.lyrics_from_source, lyricsProviderName)
                                    hasLyrics -> stringResource(R.string.lyrics)
                                    else -> stringResource(R.string.lyrics_not_found)
                                },
                            style = MaterialTheme.typography.labelLarge,
                            color = foregroundColor.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AppleMusicTransportButton(
                        iconRes = R.drawable.more_horiz,
                        contentDescription = stringResource(R.string.more_options),
                        iconSize = 20.dp,
                        touchSize = 40.dp,
                        foregroundColor = foregroundColor.copy(alpha = 0.75f),
                        onClick = onOverflowClick,
                        modifier =
                            if (onOverflowPositioned != null) {
                                Modifier.onGloballyPositioned { coords -> onOverflowPositioned(coords.boundsInRoot()) }
                            } else {
                                Modifier
                            },
                    )
                    AppleMusicTransportButton(
                        iconRes = R.drawable.close,
                        contentDescription = stringResource(R.string.close),
                        iconSize = 20.dp,
                        touchSize = 40.dp,
                        foregroundColor = foregroundColor.copy(alpha = 0.75f),
                        onClick = onCloseClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppleMusicTransportButton(
    iconRes: Int,
    contentDescription: String?,
    iconSize: Dp,
    touchSize: Dp,
    foregroundColor: Color,
    mirrored: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(touchSize),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = foregroundColor,
            modifier =
                Modifier
                    .size(iconSize)
                    .graphicsLayer { if (mirrored) scaleX = -1f },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppleMusicSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    activeColor: Color,
    inactiveColor: Color,
    trackHeight: Dp,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeStart = valueRange.start
    val safeEnd = valueRange.endInclusive.coerceAtLeast(safeStart + 1f)
    val safeRange = safeStart..safeEnd
    val sliderColors =
        SliderDefaults.colors(
            activeTrackColor = activeColor,
            activeTickColor = activeColor,
            thumbColor = Color.Transparent,
            inactiveTrackColor = inactiveColor,
        )

    Slider(
        value = value.coerceIn(safeRange),
        valueRange = safeRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = sliderColors,
        thumb = { Spacer(modifier = Modifier.size(0.dp)) },
        track = { sliderState ->
            PlayerSliderTrack(
                sliderState = sliderState,
                colors = sliderColors,
                trackHeight = trackHeight,
            )
        },
        // Do NOT constrain the Slider's height. The Material3 Slider's internal
        // touch target is 48dp tall; forcing a smaller height clips the touch
        // area and makes the slider impossible to drag.
        modifier = modifier,
    )
}

@Composable
private fun LyricsContent(
    lyricsMode: LyricsMode,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    when (lyricsMode) {
        LyricsMode.V2 -> {
            LyricsV2(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColor,
            )
        }

        LyricsMode.ENHANCED -> {
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColor,
            )
        }

        LyricsMode.SPOTIFY -> {
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColor,
                spotifyStyle = true,
            )
        }

        LyricsMode.SIMPMUSIC -> {
            SimpMusicLyrics(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                textColorOverride = textColor,
            )
        }
    }
}
