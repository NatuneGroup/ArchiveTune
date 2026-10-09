/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * "Apple Music" player design: edge-to-edge artwork on top with a blurred continuation of the
 * artwork behind the lower controls (progressive-blur look), bold white title/artist with star and
 * "more" chips, a thin scrubber with elapsed/-remaining times, bare oversized transport glyphs, a
 * flat volume slider, and a bottom lyrics / output / queue icon row. Everything is tinted by the
 * artwork itself (no palette extraction needed — the blur provides the color).
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AppleMusicAnimatedArtworkKey
import moe.rukamori.archivetune.constants.AppleMusicBackdropWanderKey
import moe.rukamori.archivetune.constants.AppleMusicPauseScaleKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.ThumbnailCornerRadiusKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.isLossless
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.player.simpmusic.SimpMusicLyrics
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LyricsV2
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.ui.menu.LyricsMenu
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.menu.rememberCastPlayerMenuAction
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.ui.utils.rememberPreBlurredBitmap
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.constants.SwipeUpToOpenQueueKey
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel

private val AppleMusicContentPadding = 28.dp
private val AppleMusicChipSize = 30.dp
private val AppleMusicTransportIconSize = 48.dp
private val AppleMusicPlayPauseIconSize = 80.dp

// The loading spinner replaces the glyph visually; decoupled so the 80dp
// play/pause glyph size does not balloon the indicator.
private val AppleMusicPlayPauseSpinnerSize = 48.dp

// Vertical space the landscape title block needs under the hero artwork — a headline
// line, a subhead line and the block's own paddings. The landscape hero's height cap
// subtracts it, so the artwork cannot spill over the title on a short screen.
private val AppleMusicLandscapeTitleBlockHeight = 78.dp
private val AppleMusicLandscapeHeroMargin = 36.dp
// Bottom action row (lyrics / cast / queue). 26dp icons in 48dp boxes stay
// comfortably above the 48dp minimum touch target while looking less bulky.
private val AppleMusicBottomIconSize = 26.dp
private val AppleMusicBottomButtonSize = 48.dp
private val AppleMusicMiniArtworkSize = 56.dp

// ─── Lyrics backdrop "moving blur" wander ──────────────────────────── The drift itself lives in
// [BlurWanderDrift], shared with MovingBlurBackground in LyricsScreen.kt so both lyrics surfaces
// move identically.
private const val AmLyricsBlurDriftScale = 2.4f

// Scale of the blurred backdrop in the COVER/QUEUE states. The LYRICS state
// zooms from here to [AmLyricsBlurDriftScale] over [AmLyricsBackdropMorphMs].
private const val AmCoverBlurScale = 1.2f

/**
 * Blur radius for the live canvas backdrop. Smaller than the 72dp used on a still cover: that one
 * is blurred into a bitmap once, this one is re-evaluated per decoded frame, and the cost of a
 * RenderEffect blur climbs with the radius.
 */
private val AmCanvasBackdropBlurRadius = 32.dp

// How long the backdrop takes to travel between the COVER look (no drift, 1.2x)
// and the LYRICS look (drifting, 2.4x). Also the duration of the canvas →
// still-artwork cross-dissolve, and the delay before the canvas TextureView is
// torn down, so the video surface only disappears once the still artwork that
// replaces it is fully opaque.
private const val AmLyricsBackdropMorphMs = 650

// Blur radius of the artwork backdrop. Applied INSIDE the drift/zoom transform
// (see driftGraphicsLayer), so the on-screen radius is this times the current
// scale: ~77dp in COVER, ~154dp with lyrics open. Keeping the radius itself
// constant means the RenderEffect is built once instead of being rebuilt on
// every frame of the zoom.
private val AmBackdropBlurRadius = 64.dp

private const val AppleMusicLyricsContentDeferMs = 160L

// How far above the bottom of the artwork stage the blurred canvas starts dissolving into the
// still blurred album art beneath it. See canvasSeamFade in AppleMusicPlayerContent.
private val AmCanvasSeamFadeDp = 88.dp

/**
 * A [Shape] that interpolates the corner radius based on the element's size. At [smallSize], the
 * corner radius is [smallRadius]; at [largeSize], it's [largeRadius]. In between, it smoothly
 * interpolates.
 */
private class AdaptiveCornerShape(
    private val smallRadius: Dp,
    private val smallSize: Dp,
    private val largeRadius: Dp,
    private val largeSize: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val elementSize = minOf(size.width, size.height)
        val smallSizePx = with(density) { smallSize.toPx() }
        val largeSizePx = with(density) { largeSize.toPx() }
        val t =
            if (largeSizePx > smallSizePx) {
                ((elementSize - smallSizePx) / (largeSizePx - smallSizePx)).coerceIn(0f, 1f)
            } else {
                1f
            }
        val smallRadiusPx = with(density) { smallRadius.toPx() }
        val largeRadiusPx = with(density) { largeRadius.toPx() }
        val radius = smallRadiusPx + (largeRadiusPx - smallRadiusPx) * t
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(radius, radius),
            ),
        )
    }
}

/**
 * Internal visual state of the Apple Music player. Mirrors ViviMusic's
 * `PlayerInternalState` enum — COVER shows the full-screen artwork + title
 * row, QUEUE morphs the artwork into a mini header and reveals the in-place
 * queue sheet, LYRICS morphs the same way but reveals the inline lyrics
 * composable instead of the queue list.
 */
private enum class AppleMusicPlayerState { COVER, QUEUE, LYRICS }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppleMusicPlayerContent(
    mediaMetadata: MediaMetadata,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    // Deferred position provider — reads the 100ms-polled playback position
    // via a stable lambda so this composable does NOT recompose on every poll
    // tick. Only AppleMusicControlsColumn reads it, and only when it's actually
    // composed (visible). When lyrics is open and controls auto-hide after 3s,
    // no recomposition happens at all — eliminating the wasted frame budget
    // that caused the "smooth for first few seconds, then laggy" auto-scroll
    // symptom in the inline Enhanced lyrics view.
    positionProvider: () -> Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    currentSongLiked: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    currentFormat: FormatEntity?,
    contentBottomPadding: Dp,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    lyricsSyncOffset: Int = 0,
    onLyricsSyncOffsetChange: (Int) -> Unit = {},
    // Reports inline-lyrics visibility upward so back-stack screens
    // (playlist/album/artist) can suspend their LiquidGlass layerBackdrop +
    // CanvasArtworkPlayer GPU work during the COVER→LYRICS morph. Without this,
    // those screens keep spending GPU frame budget behind the player sheet,
    // competing with the sharedBounds morph and causing stutter. The standalone
    // MikoLyricsTransition overlay reports through this same callback.
    onLyricsVisibilityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    // In-place queue morph state. When the user taps the queue button (or
    // swipes up on the controls area), we toggle this state instead of
    // opening the separate queue BottomSheet. The artwork + title row then
    // morph into a compact mini header while the queue list fades in below —
    // matching ViviMusic's Player_v2 ↔ Queue_v2 transition exactly.
    var queueOpen by remember { mutableStateOf(false) }
    // In-place lyrics morph state. Same animation as the queue morph — the
    // artwork shrinks into the mini header and the lyrics composable fades
    // in below. Clicking the mini header artwork restores the COVER state.
    //
    // Landscape is lyrics-first (4nx3b 998edf62c): the right half opens on the lyric sheet and the
    // artwork stays beside it. Keyed on [landscape] so rotating back to portrait opens on the
    // artwork again, as portrait always has.
    var lyricsOpen by remember(landscape) { mutableStateOf(landscape) }
    // The in-place queue morph is portrait-only; landscape opens the queue as a sheet. A queue left
    // open in portrait would otherwise sit invisible under the lyric sheet after rotating, holding
    // Back and the morph state.
    LaunchedEffect(landscape) {
        if (landscape) queueOpen = false
    }

    // Low-RAM / "reduce animations" signal -- also used to gate the karaoke
    // line-blur RenderEffect (see LyricsEnhanced/LyricsV2). Reused below to drop
    // the slide component of the controls' enter/exit transition, since a slide
    // forces an extra layout pass on top of whatever the lyrics view is already
    // spending its frame budget on.
    val animationsDisabled = LocalAnimationsDisabled.current

    // Toggling one closes the other — queue and lyrics are mutually exclusive
    // (only one morph target can be active at a time).
    val toggleQueue = {
        lyricsOpen = false
        queueOpen = !queueOpen
    }
    val toggleLyrics = {
        queueOpen = false
        lyricsOpen = !lyricsOpen
    }

    // Back handler: when the in-place queue or lyrics is open, back closes
    // it first (before the outer player-collapse BackHandler in Player.kt).
    val morphOpen = queueOpen || lyricsOpen
    androidx.activity.compose.BackHandler(enabled = morphOpen) {
        if (lyricsOpen) lyricsOpen = false
        if (queueOpen) queueOpen = false
    }

    // The morph target state: COVER (default), QUEUE, or LYRICS.
    val morphState =
        when {
            queueOpen -> AppleMusicPlayerState.QUEUE
            lyricsOpen -> AppleMusicPlayerState.LYRICS
            else -> AppleMusicPlayerState.COVER
        }

    // Clicking the mini header artwork restores the COVER state (main player).
    val restoreCover = {
        queueOpen = false
        lyricsOpen = false
    }

    // Propagate inline-lyrics visibility to the parent so back-stack screens
    // suspend their GPU work during the morph.
    LaunchedEffect(lyricsOpen) {
        onLyricsVisibilityChange(lyricsOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onLyricsVisibilityChange(false) }
    }
    // The controls never auto-hide: the bar carries the scrubber, the quality badge and the
    // lyrics provider, and a control you have to poke the screen to find is worse than one that
    // is simply there.

    // Deferred canvas-visible state: when lyrics opens, the canvas TextureView teardown (visible =
    // false) + ExoPlayer pause are delayed so they don't compete with the COVER→LYRICS sharedBounds
    // morph for the main thread on the same frame.
    //
    // In landscape the artwork column stays on screen beside the lyric sheet, so the backdrop keeps
    // its canvas and its cover look instead of handing off to the lyrics backdrop (4nx3b 998edf62c).
    val lyricsTakesBackdrop = lyricsOpen && !landscape
    var canvasVisibleForLyrics by remember { mutableStateOf(true) }
    LaunchedEffect(lyricsTakesBackdrop) {
        if (lyricsTakesBackdrop) {
            // Keep canvas visible while the backdrop cross-dissolve runs.
            canvasVisibleForLyrics = true
            delay(AmLyricsBackdropMorphMs.toLong())
            canvasVisibleForLyrics = false
        } else {
            canvasVisibleForLyrics = true
        }
    }
    // True while the lyrics backdrop (zoom + drift) is on screen OR still
    // animating back out. Gates the wander frame loop so it isn't burning a
    // frame callback every 16ms while the player sits in the COVER state.
    // Only the lyrics backdrop drifts, so the frame loop stays off in landscape, where it would
    // run every frame with nothing to move (upstream left it running there).
    var lyricsBackdropActive by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsTakesBackdrop) {
        if (lyricsTakesBackdrop) {
            lyricsBackdropActive = true
        } else {
            delay(AmLyricsBackdropMorphMs.toLong())
            lyricsBackdropActive = false
        }
    }
    var lyricsContentReady by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) {
        if (!lyricsOpen) {
            lyricsContentReady = false
            return@LaunchedEffect
        }
        // Composing LyricsEnhanced/LyricsV2 for the first time is the most expensive frame in the
        // whole overlay. Let the artwork morph and the canvas handoff get a head start so that
        // burst cannot land on the same frame as the shared-bounds animation.
        //
        // A short head start is enough now that the TTML/LRC parse and buildSyncedLyrics both run
        // on Dispatchers.Default and only the composition is left on the main thread. Keeping it
        // long hurts: the AnimatedVisibility fadeIn below is 400ms, so almost all of it would be
        // spent on an empty box and the lyrics would arrive as a pop rather than a fade. The
        // renderers' own first-focus fade covers the remainder.
        lyricsContentReady = false
        delay(AppleMusicLyricsContentDeferMs)
        lyricsContentReady = true
    }
    // Nothing to re-summon any more; kept as a no-op so the call sites that report a tap stay
    // where they are, rather than being threaded out of a dozen components.
    val pokePlayerControlsVisibility = remember { {} }

    // === Deferred position reads for the lyrics overlay === `sliderPosition` is non-null ONLY
    // while the user is actively scrubbing the seekbar.
    val sliderPositionState = rememberUpdatedState(sliderPosition)
    val lyricsPosProvider = remember {
        { sliderPositionState.value }
    }

    // The moving-blur wander is remembered inside the backdrop's BoxWithConstraints below, where the
    // measured screen size is available: its amplitude is screen-proportional by design.

    // Pre-compute dp→px once (graphicsLayer.translationX is in pixels). Density
    // doesn't change per-frame so this is a one-time composition-phase read.
    val density = LocalDensity.current
    val driftDpToPx = with(density) { 1.dp.toPx() }
    val lyricsSwipeDismissThresholdPx = with(density) { 96.dp.toPx() }

    // === COVER ↔ LYRICS backdrop hand-off === The backdrop's blur, zoom and drift are all
    // driven by this one progress value, so the hand-off is a single continuous morph.
    val lyricsBackdropProgress =
        animateFloatAsState(
            targetValue = if (lyricsTakesBackdrop) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = AmLyricsBackdropMorphMs,
                    easing = FastOutSlowInEasing,
                ),
            label = "am-lyrics-backdrop-progress",
        )
    // Optional backdrop wander: while lyrics are open the blurred backdrop zooms and drifts.
    // Off keeps the COVER look (static blur at [AmCoverBlurScale]) in every state.
    val (backdropWanderEnabled) = rememberPreference(AppleMusicBackdropWanderKey, defaultValue = true)

    // Hoist the thumbnail corner radius preference so it can be used both for the COVER state's
    // artwork clip AND for the sharedBounds overlay clip during morph transitions. Read locally
    // inside AppleMusicSharpArtwork, the sharedBounds modifier in this parent could not see it, so
    // the SharedTransition overlay fell back to a RectangleShape clip and flashed sharp corners
    // for the duration of the spring bounds animation (1-2s) after expanding from the mini
    // header. See clipInOverlayDuringTransition on the sharedBounds modifiers below.
    val (thumbnailCornerRadius, _) = rememberPreference(
        ThumbnailCornerRadiusKey,
        defaultValue = 16f,
    )
    val artworkCornerRadiusDp = thumbnailCornerRadius.coerceAtMost(32f).dp

    val baseArtworkUrl = mediaMetadata.thumbnailUrl?.highRes()
    val thumbnailSwapState =
        rememberThumbnailSwapState(
            videoId = mediaMetadata.id,
            ytmUrl = baseArtworkUrl,
            lowDataMode = rememberLowDataModeActive(),
            isMusicVideo = mediaMetadata.isMusicVideo,
        )
    val artworkUrl = thumbnailSwapState.displayUrl
    val artworkRequest = rememberOfflineArtworkImageRequest(artworkUrl)
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    // The inline lyrics renderer for the selected mode — read here (rather than inside the
    // portrait overlay) because the landscape arrangement renders the same panes in its
    // right half.
    val lyricsMode by rememberEnumPreference(LyricsModeKey, LyricsMode.ENHANCED)
    val menuState = LocalMenuState.current
    val context = LocalContext.current

    // Current lyrics for the LyricsMenu (shown when lyrics is open and the
    // user taps the overflow "more" button).
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)


    // ─── Automatic AI translation ───────────────────────────────────────
    // Mirrors the same LaunchedEffect in LyricsScreen.kt. The Apple Music
    // player uses an inline LyricsV2/LyricsEnhanced view (not LyricsScreen),
    // so it needs its own trigger; these two effects are the whole of
    // auto-translation — there is no background path.
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
        // translation content. The `hasTranslation` guard allows retrying when a
        // previous attempt no-op'd — the AI returned the same text, a common
        // failure mode for CJK lyrics. Without this, those songs would be blocked
        // from retrying forever.
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

    val onPlayPauseClick = {
        if (playbackState == STATE_ENDED) {
            playerConnection.player.seekTo(0, 0)
            playerConnection.player.playWhenReady = true
        } else {
            playerConnection.player.togglePlayPause()
        }
    }
    // With lyrics open the three-dot opens 4nx3b's Apple Music popup, anchored to the dot that was
    // tapped. It is part of the player's own design, not of the Apple Music Experience switch.
    var showAnchoredLyricsMenu by remember { mutableStateOf(false) }
    var moreIconBounds by remember { mutableStateOf(Rect.Zero) }
    val onMorePositioned: (Rect) -> Unit = remember { { bounds -> moreIconBounds = bounds } }
    // A live glass backdrop records a layer every frame, so it only exists while the popup can
    // actually be shown on top of it.
    val popupBackdrop: PlatformBackdrop? =
        if (rememberLiquidGlassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
        } else {
            null
        }
    LaunchedEffect(lyricsOpen) {
        if (!lyricsOpen) showAnchoredLyricsMenu = false
    }
    val onMoreClick = {
        if (lyricsOpen) {
            showAnchoredLyricsMenu = true
        } else {
            menuState.show {
                PlayerMenu(
                    mediaMetadata = mediaMetadata,
                    navController = navController,
                    playerBottomSheetState = state,
                    onShowDetailsDialog = {
                        mediaMetadata.id.let {
                            bottomSheetPageState.show {
                                ShowMediaInfo(it)
                            }
                        }
                    },
                    onDismiss = menuState::dismiss,
                )
            }
        }
    }
    // The "AirPlay" slot opens the Cast route picker on flavors that ship Cast (gms). This also
    // renders the route-picker bottom sheet when it becomes visible. On flavors without Cast (foss)
    // rememberCastPlayerMenuAction() returns null and we fall back to the system output switcher.
    val castAction = rememberCastPlayerMenuAction()
    val onOutputClick: () -> Unit = castAction?.onClick ?: {
        // Cast-less flavors (foss): open the system media-output switcher panel.
        runCatching {
            context.startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT"))
        }
    }

    Box(modifier = modifier) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .let { base -> if (popupBackdrop != null) base.layerBackdrop(popupBackdrop) else base },
    ) {
        val sharpArtworkHeight = if (landscape) maxHeight else maxHeight * 0.55f
        // The FULL player height — used as the artwork sizing reference so the
        // artwork stays a constant size whether the system navigation bar is
        // visible or hidden. In portrait the morph area is weight(1f), so its
        // height shrinks when the nav bar inset is consumed by the controls'
        // `navigationBarsPadding()`. Using the outer maxHeight here (which is
        // fillMaxSize — the entire player area) decouples artwork sizing from
        // that inset. See `fullPlayerHeight` parameter in AppleMusicSharpArtwork.
        val fullPlayerHeightForArtwork: Dp? = if (landscape) null else maxHeight

        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(Color.Black),
        )

        // Height of the artwork stage (the weight(1f) morph area). Captured here so the backdrop —
        // which is composed before the Column and so cannot see its layout — knows where the
        // bottom controls begin. Read only from a draw-phase lambda, so a change costs a redraw
        // rather than a recomposition.
        var morphAreaHeightPx by remember { mutableIntStateOf(0) }

        // "Animated artwork" — the Spotify Canvas loop and the music video that can take the
        // cover's place. On by default, which is how this style has always behaved; the setting
        // exists because moving artwork is not what everyone wants a player to do, and there was
        // no way to stop it. Applied by emptying the canvas URLs rather than by branching at each
        // of the five places they are read: a canvas that is off should be indistinguishable from
        // a song that never had one, and that is exactly what a null URL already means here.
        val animatedArtworkEnabled by rememberPreference(AppleMusicAnimatedArtworkKey, defaultValue = true)
        val canvasPrimaryUrl = canvasPrimaryUrl.takeIf { animatedArtworkEnabled }
        val canvasFallbackUrl = canvasFallbackUrl.takeIf { animatedArtworkEnabled }

        val videoShowing =
            animatedArtworkEnabled &&
                LocalVideoArtworkState.current != null &&
                mediaMetadata.isMusicVideo &&
                !mediaMetadata.id.isLocalMediaId()
        val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val canvasActive =
            !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()
        // When a Spotify Canvas (or any canvas artwork) is playing, render the canvas video
        // itself as the blurred backdrop — "Apple Music player style". On Android 12+,
        // Modifier.blur works on the TextureView surface that CanvasArtworkPlayer uses, so
        // the backdrop mirrors the canvas video in real time. Pre-Android-12 falls back to
        // the album-art blur (RenderEffect is unavailable, so blurring a video surface
        // efficiently isn't possible).
        val useCanvasBackdrop = canvasActive && !videoShowing && !isPreS
        val context = LocalContext.current
        val preBlurredBitmap =
            if (videoShowing || useCanvasBackdrop) {
                null
            } else {
                // Pre-S has no RenderEffect, so Modifier.blur is a no-op there and the blur has to
                // be baked into a bitmap off the main thread instead. Gated on useCanvasBackdrop
                // rather than canvasActive: pre-S never uses the canvas as its backdrop (blurring a
                // video surface without RenderEffect isn't possible), so a canvas song still needs
                // the blurred artwork — without this it fell through to an unblurred AsyncImage and
                // showed a sharp backdrop.
                rememberPreBlurredBitmap(
                    imageUrl = artworkUrl,
                    radiusDp = 72.dp,
                    cacheKey = "$artworkUrl#amplayer",
                )
            }

        if (!videoShowing) {
            // Backdrop rendering — one blurred-artwork node for every state, plus (on canvas songs)
            // the live canvas video composited over it. • COVER / QUEUE state — the artwork sits at
            // [AmCoverBlurScale] with no drift.
            //
            // Same screen-scaled wander amplitude as every other player style (Apple Music
            // lyrics-page behaviour): the colour mass traverses the whole display instead of
            // orbiting near the centre.
            val wanderMaxDrift = movingBlurWanderMaxDriftDp(maxWidth, maxHeight)
            val blurWander =
                rememberBlurWanderDrift(
                    active = lyricsBackdropActive && backdropWanderEnabled,
                    maxDriftDp = wanderMaxDrift,
                )
            val driftGraphicsLayer: GraphicsLayerScope.() -> Unit = {
                // Deferred state reads: draw phase only. See the comment on
                // lyricsBackdropProgress for why this is a continuous ramp
                // rather than the `if (lyricsOpen && lyricsContentReady)` step
                // it replaced. Off keeps the COVER look: no zoom, no drift.
                val progress = if (backdropWanderEnabled) lyricsBackdropProgress.value else 0f
                // Scale [AmLyricsBlurDriftScale] (lyrics fully open) together with the
                // [backdropFootprint] the node is sized to leaves the rotated layer covering every
                // screen corner plus the ±120dp drift — see blurBackdropFootprint for the budget.
                val scale = AmCoverBlurScale + (AmLyricsBlurDriftScale - AmCoverBlurScale) * progress
                scaleX = scale
                scaleY = scale
                if (progress > 0f) {
                    // Ramping the amplitude with `progress` is what keeps the
                    // wander from snapping in: the phase advances the whole
                    // time lyrics is open, so without the multiplier the very
                    // first drifted frame would teleport the artwork by
                    // however far along the path the phase already was. The
                    // rotation is ramped for exactly the same reason.
                    translationX = blurWander.xDp.floatValue * driftDpToPx * progress
                    translationY = blurWander.yDp.floatValue * driftDpToPx * progress
                    // Rotation is what actually carries a colour from the top of
                    // the screen to the bottom — translation alone moves every
                    // colour by the same vector, so the arrangement stays rigid
                    // and the top stays the top. See BlurWanderDrift.
                    rotationZ = blurWander.rotationDeg.floatValue * progress
                }
                // Force an offscreen compositing layer so the (expensive)
                // Modifier.blur RenderEffect applied to this same node is
                // rasterized ONCE into an offscreen buffer and only the
                // cheap translation/scale transform re-runs every frame as
                // the drift values change. Without this, some GPU drivers
                // re-compute the 64dp blur on every frame because the
                // layer's transform changed — stealing GPU frame budget
                // from the 60Hz karaoke syllable fill animation in the
                // lyrics overlay (Enhanced style only, since V2 renders
                // its own syllables and is less sensitive to GPU pressure).
                compositingStrategy = CompositingStrategy.Offscreen
            }

            // ── Canvas → still-art seam ── Fades the blurred canvas layer out before the bottom
            // controls, so a BetterLyrics/Spotify canvas never shows through behind them.
            val canvasSeamFade: Modifier =
                if (landscape) {
                    Modifier
                } else {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val seam = morphAreaHeightPx.toFloat()
                            // Before the stage has been measured there is nothing to fade to.
                            if (seam <= 0f || seam >= size.height) return@drawWithContent
                            val fadeStart = ((seam - AmCanvasSeamFadeDp.toPx()) / size.height).coerceIn(0f, 1f)
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        fadeStart to Color.Black,
                                        (seam / size.height) to Color.Transparent,
                                    ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                }

            // The artwork backdrop. Composed in every state so there is no node
            // swap (and so no re-decode, no new RenderEffect, no one-frame
            // discontinuity) when lyrics opens or closes.
            //
            // Deliberately larger than the player and centred inside it: the drift
            // rotates this node, and Modifier.blur clips its result to the node's
            // own bounds, so a node the size of the player would swing its corners
            // into view. requiredSize is what lets it ignore the incoming
            // constraints; the wrapper clips the overhang back to the player so it
            // cannot bleed over anything else. See blurBackdropFootprint.
            val backdropFootprint =
                remember(maxWidth, maxHeight) {
                    blurBackdropFootprint(
                        width = maxWidth,
                        height = maxHeight,
                        restScale = AmCoverBlurScale,
                        driftScale = AmLyricsBlurDriftScale,
                        maxDriftDp = wanderMaxDrift,
                    )
                }
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                if (isPreS && preBlurredBitmap != null) {
                    // Pre-Android-12 has no RenderEffect, so the blur was baked
                    // into the bitmap on a background thread instead. It still goes
                    // through driftGraphicsLayer, rotation included, so it needs the
                    // same footprint.
                    Image(
                        bitmap = preBlurredBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .requiredSize(backdropFootprint)
                                .graphicsLayer(driftGraphicsLayer),
                    )
                } else {
                    AsyncImage(
                        model = artworkRequest ?: artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .requiredSize(backdropFootprint)
                                // graphicsLayer OUTSIDE blur: the blur is applied to
                                // the centered image (inside the layer), then the
                                // scale + translation is applied to the blurred
                                // result. Blurring after the transform would sample
                                // the translated image's edges instead.
                                .graphicsLayer(driftGraphicsLayer)
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(AmBackdropBlurRadius)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }

            if (useCanvasBackdrop) {
                // Canvas backdrop — the ExoPlayer is ALWAYS retained (never disposed across lyrics
                // open/close) so the canvas resumes instantly when lyrics closes — no multi-second
                // reload delay.
                // A 72dp blur over a live video surface is re-evaluated on every frame the decoder
                // produces, full screen, which is what makes animated artwork cost far more than a
                // still cover — that one is pre-blurred into a bitmap once. Two things keep it off
                // the frame budget: it is dropped entirely when animations are disabled (the
                // low-RAM default), and it is skipped once the lyrics ramp has faded the backdrop
                // out, since alpha is applied after the effect and a blur nobody can see still
                // costs a full pass.
                val canvasBackdropVisible by remember {
                    derivedStateOf { lyricsBackdropProgress.value < 0.99f }
                }
                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimaryUrl,
                    fallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying && canvasVisibleForLyrics,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    visible = canvasVisibleForLyrics,
                    modifier =
                        Modifier
                            .matchParentSize()
                            // Outside the blur so the mask is applied to the blurred result.
                            .then(canvasSeamFade)
                            .then(
                                if (animationsDisabled || !canvasBackdropVisible) {
                                    Modifier
                                } else {
                                    Modifier.blur(AmCanvasBackdropBlurRadius)
                                },
                            ).graphicsLayer {
                                // Fixed scale (no drift) — the canvas is on its
                                // way out by the time the drift matters.
                                scaleX = AmCoverBlurScale
                                scaleY = AmCoverBlurScale
                                alpha = 1f - lyricsBackdropProgress.value
                            },
                )
            }
            val preBlurLoading = isPreS && preBlurredBitmap == null && !canvasActive
            // Brightened scrim — matches ViviMusic's brighter aesthetic. Kept deliberately light
            // over the blurred artwork (0.15/0.28/0.50 on the canvas path, 0.28/0.42/0.60 over the
            // pre-S bitmap) so its colour shows through; only the un-blurred placeholder while the
            // bitmap loads stays dark enough to hide the bare artwork.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = if (useCanvasBackdrop || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.15f else if (preBlurLoading) 0.55f else 0.28f),
                                0.5f to Color.Black.copy(alpha = if (useCanvasBackdrop || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.28f else if (preBlurLoading) 0.65f else 0.42f),
                                1f to Color.Black.copy(alpha = if (useCanvasBackdrop || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.50f else if (preBlurLoading) 0.85f else 0.60f),
                            ),
                        ),
            )
        }

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                // Left half: the artwork with the song title and artist directly beneath it —
                // Apple Music's own landscape arrangement — sized up so the artwork reads as the
                // hero of the half (not a shrunken portrait card) and centred, with the title
                // block aligned to the very same width so name and artwork share one visual
                // column. The right half is lyrics-first: opening lyrics swaps the controls
                // column out for the lyric sheet over there, keeping the artwork on screen
                // while lyrics show.
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    // Both clamps dominate and the floor only bounds from below: the hero can
                    // never exceed the half's width (minus the side margins) or the height that
                    // is actually left once the title block and the bottom inset have taken
                    // theirs, so it cannot overflow a short landscape screen. The 280dp floor
                    // raises it back up only when that space exists — an unconditional floor
                    // after the clamps would win over them and clip the artwork instead.
                    // 36dp a side (4nx3b 998edf62c) so the hero no longer visually touches the
                    // screen edge, with a 240dp floor to match.
                    val availableWidth = (maxWidth - AppleMusicLandscapeHeroMargin * 2).coerceAtLeast(0.dp)
                    val availableHeight =
                        (maxHeight - contentBottomPadding - AppleMusicLandscapeTitleBlockHeight)
                            .coerceAtLeast(0.dp)
                    val landscapeArtworkSize =
                        availableWidth
                            .coerceAtMost(availableHeight)
                            .coerceAtLeast(minOf(240.dp, availableWidth, availableHeight))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = contentBottomPadding),
                    ) {
                        // Lyrics-first leaves the controls behind the lyric sheet, and this fork
                        // never auto-hides or tap-summons them over it (4nx3b does both), so
                        // tapping the artwork swaps the right half between lyrics and controls.
                        // The controls' lyrics button and Back keep working as before.
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .playerSeekDoubleTap(onTap = toggleLyrics)
                                    .padding(horizontal = AppleMusicContentPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppleMusicSharpArtwork(
                                artworkRequest = artworkRequest,
                                artworkUrl = artworkUrl,
                                canvasPrimaryUrl = canvasPrimaryUrl,
                                canvasFallbackUrl = canvasFallbackUrl,
                                isPlaying = isPlaying,
                                fadeBottom = false,
                                videoId = mediaMetadata.id.takeIf { !it.isLocalMediaId() },
                                isMusicVideo = mediaMetadata.isMusicVideo,
                                landscape = true,
                                landscapeArtworkSize = landscapeArtworkSize,
                                fadeRightEdge = true,
                                artworkCornerRadiusDp = artworkCornerRadiusDp,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        AppleMusicLandscapeTitleBlock(
                            mediaMetadata = mediaMetadata,
                            currentSongLiked = currentSongLiked,
                            titleActions = titleActions,
                            onToggleLike = playerConnection::toggleLike,
                            onMoreClick = onMoreClick,
                            onMorePositioned = onMorePositioned,
                            contentWidth = landscapeArtworkSize,
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    // Lyrics own the right half whenever they are open.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = lyricsOpen,
                        enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
                        modifier = Modifier.matchParentSize(),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    // Same bottom inset as the controls: the collapsed queue
                                    // sheet and the navigation bar live down there.
                                    .padding(bottom = contentBottomPadding)
                                    .padding(horizontal = AppleMusicContentPadding - 16.dp),
                        ) {
                            if (lyricsContentReady) {
                                AppleMusicInlineLyrics(
                                    lyricsMode = lyricsMode,
                                    sliderPositionProvider = lyricsPosProvider,
                                    lyricsSyncOffset = lyricsSyncOffset,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        // The controls never auto-hide in this fork, so the lyrics takeover
                        // has to hide them outright — stacked over the sheet they would leave
                        // nothing readable. Back closes the lyrics again (see the BackHandler
                        // above), which is also what the portrait arrangement relies on.
                        visible = !lyricsOpen,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(100)),
                        modifier = Modifier.matchParentSize(),
                    ) {
                        AppleMusicControlsColumn(
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            positionProvider = positionProvider,
                            duration = duration,
                            playerConnection = playerConnection,
                            currentSongLiked = currentSongLiked,
                            volume = volume,
                            onVolumeChange = onVolumeChange,
                            titleActions = titleActions,
                            onPlayPauseClick = onPlayPauseClick,
                            onMoreClick = onMoreClick,
                            onMorePositioned = onMorePositioned,
                            onOutputClick = onOutputClick,
                            onQueueClick = onQueueClick,
                            // Inline lyrics, as in portrait (and as upstream's landscape
                            // arrangement wires it): the pane above is otherwise unreachable.
                            // The queue keeps opening its own sheet here — this fork's in-place
                            // queue morph is a portrait-only layout, so toggleQueue would leave
                            // the button with nothing to show.
                            onLyricsClick = toggleLyrics,
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            currentFormat = currentFormat,
                            onQualityChipClick = {
                                bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                            },
                            // The title lives in the left half's own block in landscape; this
                            // flag and that block land together or the title renders twice.
                            showTitleRow = false,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = contentBottomPadding),
                        )
                    }
                }
            }
        } else {
            // Portrait layout with ViviMusic-style in-place queue morph. The artwork + title row
            // live inside a SharedTransitionLayout so they can morph (large → mini) when the user
            // toggles the queue.
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                BoxWithConstraints(
                    // Reports the artwork stage height to canvasSeamFade above.
                    modifier = Modifier.weight(1f).onSizeChanged { morphAreaHeightPx = it.height },
                ) {
                // Mini header height = artwork size + vertical padding (8.dp top + 8.dp bottom)
                // + top system bar inset (status bar / notch). The overlay must start
                // BELOW this height so it doesn't intercept taps on the mini header's
                // artwork (restore cover) and favourite/overflow chips.
                val topInset = LocalStableSystemBarsTopPadding.current
                val miniHeaderHeight = AppleMusicMiniArtworkSize + 16.dp + topInset
                SharedTransitionLayout(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AnimatedContent(
                        targetState = morphState,
                        transitionSpec = {
                            // Symmetric crossfade with shared-element morph.
                            fadeIn(tween(600, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(600, easing = FastOutSlowInEasing))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "AppleMusicMorph",
                    ) { targetState ->
                        if (targetState == AppleMusicPlayerState.COVER) {
                            // COVER state: large sharp artwork fills the morph area.
                            // The canvas lives inside AppleMusicSharpArtwork (default
                            // showCanvas = true). It IS disposed/recreated on morph
                            // transitions, but hoisting it outside AnimatedContent
                            // breaks touch routing (see comment above the lyrics
                            // overlay for details).
                            Box(modifier = Modifier.fillMaxSize().playerSeekDoubleTap()) {
                                AppleMusicSharpArtwork(
                                    artworkRequest = artworkRequest,
                                    artworkUrl = artworkUrl,
                                    canvasPrimaryUrl = canvasPrimaryUrl,
                                    canvasFallbackUrl = canvasFallbackUrl,
                                    isPlaying = isPlaying,
                                    fadeBottom = !videoShowing,
                                    videoId = mediaMetadata.id.takeIf { !it.isLocalMediaId() },
                                    isMusicVideo = mediaMetadata.isMusicVideo,
                                    landscape = false,
                                    // Pass the FULL player height so the artwork
                                    // size doesn't shrink when the system nav bar
                                    // eats into the morph area (weight 1f).
                                    fullPlayerHeight = fullPlayerHeightForArtwork,
                                    // Pass the preference-derived corner radius so
                                    // the artwork clip inside
                                    // AppleMusicSharpArtwork matches the overlay
                                    // clip on the sharedBounds modifier below.
                                    artworkCornerRadiusDp = artworkCornerRadiusDp,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .sharedBounds(
                                                sharedContentState =
                                                    rememberSharedContentState(key = "amCoverArt"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                // CRITICAL: explicitly set the overlay clip to a
                                                // RoundedCornerShape matching the COVER artwork's
                                                // own clip.
                                                clipInOverlayDuringTransition =
                                                    OverlayClip(
                                                        AdaptiveCornerShape(
                                                            smallRadius = 8.dp,
                                                            smallSize = AppleMusicMiniArtworkSize,
                                                            largeRadius = artworkCornerRadiusDp,
                                                            largeSize = 400.dp,
                                                        ),
                                                    ),
                                                // Snappy non-bouncy spring — restores the original
                                                // morph feel that the 600ms tween replaced.
                                                boundsTransform =
                                                    BoundsTransform { _, _ ->
                                                        spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow,
                                                        )
                                                    },
                                            ),
                                )
                            }
                        } else {
                            // QUEUE / LYRICS state: mini header + content below. The Spotify Canvas
                            // continues playing behind the queue/lyrics content so it doesn't
                            // "stop" when the user opens the queue.
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(WindowInsets(top = LocalStableSystemBarsTopPadding.current)),
                            ) {
                                // The blurred backdrop (rendered at the top level
                                // above) already contains the canvas with
                                // Modifier.blur(72.dp) when useCanvasBackdrop is
                                // true. We intentionally do NOT render a second
                                // non-blurred canvas here — the previous
                                // implementation did that, which covered the
                                // blurred backdrop and made it look like the
                                // blur "went away" when the queue opened.
                                // The queue sheet renders on a transparent
                                // background so the blurred canvas shows through.
                                Column(modifier = Modifier.fillMaxSize()) {
                                    AppleMusicMiniHeader(
                                        artworkRequest = artworkRequest,
                                        artworkUrl = artworkUrl,
                                        mediaMetadata = mediaMetadata,
                                        currentSongLiked = currentSongLiked,
                                        titleActions = titleActions,
                                        onToggleLike = playerConnection::toggleLike,
                                        onMoreClick = onMoreClick,
                                        onMorePositioned = onMorePositioned,
                                        onArtworkClick = restoreCover,
                                        onCloseClick = if (targetState == AppleMusicPlayerState.LYRICS) restoreCover else null,
                                        animatedVisibilityScope = this@AnimatedContent,
                                        // Pass the COVER artwork's corner radius so
                                        // the mini header's OverlayClip can use it
                                        // during COVER→LYRICS transitions. Without
                                        // this, the overlay uses the mini's own 8dp
                                        // radius, which looks sharp on the large
                                        // cover bounds at the start of the morph.
                                        // Using the larger cover radius keeps the
                                        // corners properly rounded from the first
                                        // frame.
                                        artworkCornerRadiusDp = artworkCornerRadiusDp,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (targetState == AppleMusicPlayerState.QUEUE) {
                                        AppleMusicQueueSheet(
                                            navController = navController,
                                            playerBottomSheetState = state,
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .animateEnterExit(
                                                        enter = slideInVertically(
                                                            animationSpec = tween(600, easing = FastOutSlowInEasing),
                                                        ) { it / 4 } + fadeIn(tween(600)),
                                                        exit = fadeOut(tween(400)) +
                                                            slideOutVertically(
                                                                animationSpec = tween(400, easing = FastOutSlowInEasing),
                                                            ) { it / 4 },
                                                    ),
                                        )
                                    }
                                    // The LyricsEnhanced/LyricsV2 composable is deliberately NOT
                                    // rendered here inside the
                                    // SharedTransitionLayout/AnimatedContent.
                                }
                            }
                        }
                    }
                }

                // === Foreground sharp canvas === The foreground canvas (sharp, inside
                // AppleMusicSharpArtwork) is NOT hoisted here.

                // Lyrics overlay — INSIDE the weighted Box, ON TOP of the SharedTransitionLayout
                // but BOUNDED to the lyrics area (below the mini header).
                androidx.compose.animation.AnimatedVisibility(
                    visible = lyricsOpen,
                    enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
                ) {
                    // Lyrics area — poke controls on touch, lyrics scroll. The Column is sized to
                    // fill the area BELOW the mini header (maxHeight - miniHeaderHeight) and offset
                    // down by miniHeaderHeight so it doesn't cover the mini header.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(maxHeight - miniHeaderHeight)
                                .offset(y = miniHeaderHeight)
                                .pointerInput(lyricsOpen, lyricsSwipeDismissThresholdPx) {
                                    if (!lyricsOpen) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        pokePlayerControlsVisibility()
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break

                                            val deltaX = change.position.x - down.position.x
                                            val deltaY = change.position.y - down.position.y
                                            if (deltaY < 0f || abs(deltaX) > abs(deltaY)) break
                                            if (deltaY > 0f) change.consume()
                                            if (isAppleMusicLyricsDismissDrag(deltaX, deltaY, lyricsSwipeDismissThresholdPx)) {
                                                restoreCover()
                                                break
                                            }
                                        }
                                    }
                                },
                    ) {
                        // HORIZONTAL PADDING — compensate for the mocharealm KaraokeLineText
                        // library's INTERNAL 16dp horizontal padding (see lyrics-ui-android
                        // sources: KaraokeLineText.kt line ~514 applies `padding(vertical = 8.dp,
                        // horizontal = 16.dp)` to its Column for non-accompaniment lines).
                        val lyricsHorizontalPadding = AppleMusicContentPadding - 16.dp
                        AppleMusicInlineLyrics(
                            lyricsMode = lyricsMode,
                            sliderPositionProvider = lyricsPosProvider,
                            lyricsSyncOffset = lyricsSyncOffset,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = lyricsHorizontalPadding),
                        )
                    }
                }
                } // end weighted BoxWithConstraints (SharedTransitionLayout + lyrics overlay)

                // Persistent playback controls — anchored at the bottom.
                // When the queue or lyrics is open, the title row is hidden (it's in the
                // mini header above) so only the seekbar + transport + volume + bottom
                // row render.
                //
                // Auto-hide follows the standalone LyricsScreen preference (5s when enabled).
                // The mini header remains visible, so the user can always return to the player.
                // Slide requires an extra layout pass on top of the fade; skip it when
                // animations are reduced so the auto-hide/show cycle doesn't compete with
                // the karaoke lyrics view for frame budget on lower-end devices.
                AnimatedVisibility(
                    // The controls never auto-hide, so this is always visible; kept as an
                    // AnimatedVisibility so the morph in and out stays the same shape.
                    visible = true,
                    enter = if (animationsDisabled) {
                        fadeIn(tween(120))
                    } else {
                        fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 }
                    },
                    exit = if (animationsDisabled) {
                        fadeOut(tween(100))
                    } else {
                        fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 8 }
                    },
                ) {
                    AppleMusicControlsColumn(
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        sliderPosition = sliderPosition,
                        positionProvider = positionProvider,
                        duration = duration,
                        playerConnection = playerConnection,
                        currentSongLiked = currentSongLiked,
                        volume = volume,
                        onVolumeChange = onVolumeChange,
                        titleActions = titleActions,
                        onPlayPauseClick = onPlayPauseClick,
                        onMoreClick = onMoreClick,
                        onMorePositioned = onMorePositioned,
                        onOutputClick = onOutputClick,
                        onQueueClick = toggleQueue,
                        onLyricsClick = toggleLyrics,
                        onSliderValueChange = onSliderValueChange,
                        onSliderValueChangeFinished = onSliderValueChangeFinished,
                        currentFormat = currentFormat,
                        onQualityChipClick = {
                            bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                        },
                        showTitleRow = !morphOpen,
                        isQueueActive = queueOpen,
                        isLyricsActive = lyricsOpen,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                // No navigationBarsPadding() here — contentBottomPadding
                                // already includes the system-bars bottom inset via
                                // collapsedBound (= dynamicQueuePeekHeight + systemBarsBottom).
                                // Adding navigationBarsPadding() on top double-counts the inset
                                // and makes the controls jump up when the nav bar appears.
                                .padding(bottom = contentBottomPadding),
                    )
                }
            } // end Column (morph area + controls)
        }
    }

        if (showAnchoredLyricsMenu) {
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = moreIconBounds,
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
private fun AppleMusicSharpArtwork(
    artworkRequest: coil3.request.ImageRequest?,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    isPlaying: Boolean,
    fadeBottom: Boolean,
    videoId: String? = null,
    isMusicVideo: Boolean = false,
    landscape: Boolean = false,
    // Landscape gets an explicit size handed down from the player (the hero-of-the-half
    // arrangement); portrait keeps the fraction-based sizing below.
    landscapeArtworkSize: Dp? = null,
    // Dissolves the canvas's inner edge (the screen's middle in horizontal mode) into the
    // blurred backdrop instead of ending in a hard rectangle.
    fadeRightEdge: Boolean = false,
    // When false, the CanvasArtworkPlayer is NOT rendered inside this composable.
    // The caller is responsible for rendering the canvas separately (hoisted
    // outside the AnimatedContent) to keep the ExoPlayer alive across morph
    // state transitions. Used by the portrait Apple Music layout.
    showCanvas: Boolean = true,
    // The FULL player height (from the outer BoxWithConstraints), used for
    // artwork sizing so the artwork stays a consistent size regardless of
    // the system navigation bar inset. When the nav bar is visible, the
    // morph area (weight 1f) shrinks, but the artwork should NOT shrink
    // with it — this parameter decouples artwork size from morph area height.
    // Null = fall back to the local maxHeight (landscape or legacy callers).
    fullPlayerHeight: Dp? = null,
    // The preference-derived corner radius for the immersiveExtendedCard
    // artwork. Hoisted from the parent (AppleMusicPlayerContent) so the
    // same value can be used for the sharedBounds overlay clip — keeping
    // the overlay's clip in sync with the artwork's own clip during morph
    // transitions. See clipInOverlayDuringTransition on the sharedBounds
    // modifier in AppleMusicPlayerContent.
    artworkCornerRadiusDp: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current
    // The landscape canvas blend direction follows the layout direction: the dissolve has to
    // face the controls (the screen's middle), which sit left of the artwork under RTL.
    val layoutDirection = LocalLayoutDirection.current
    val canvasInnerEdgeFadeBrush =
        remember(layoutDirection) {
            if (layoutDirection == LayoutDirection.Rtl) {
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Black,
                    1f to Color.Black,
                )
            } else {
                Brush.horizontalGradient(
                    0f to Color.Black,
                    0.55f to Color.Black,
                    1f to Color.Transparent,
                )
            }
        }
    Box(
        modifier =
            modifier.then(
                if (fadeBottom) {
                    // Fade the sharp artwork's lower edge into the blurred layer beneath.
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        0.62f to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        val videoArtworkState = LocalVideoArtworkState.current
        val showVideo =
            videoArtworkState != null &&
                !videoArtworkState.hasPlaybackFailed &&
                isMusicVideo &&
                !videoId.isNullOrBlank() &&
                playerConnection != null
        // "Immersive extended" — when there is no Spotify Canvas (or any animated
        // artwork) AND no music video, render the still cover as a square using
        // the SAME sizing formula as the Material Extended (V9) player, instead of
        // stretching the cover to fill the rectangular stage. When a canvas or
        // video IS available, we keep the full-bleed display so the animated
        // artwork can fill the stage.
        val hasCanvas = !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()
        val immersiveExtendedCard = !showVideo && !hasCanvas
        if (showVideo) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black),
            )
        } else if (immersiveExtendedCard) {
            // "Immersive extended" — render the still cover as a square using the SAME sizing
            // formula and thresholds as the Material Extended (V9) player (see V9PortraitContent in
            // PlayerComponents.kt), centered inside the artwork stage.
            BoxWithConstraints(modifier = Modifier.matchParentSize()) {
                val horizontalPadding = if (maxWidth < 380.dp) 16.dp else 20.dp
                val effectiveFullHeight = fullPlayerHeight ?: if (landscape) maxHeight else maxHeight / 0.55f
                val compactHeight = effectiveFullHeight < 760.dp
                val veryCompactHeight = effectiveFullHeight < 700.dp
                // artworkCornerRadiusDp is hoisted from the parent (AppleMusicPlayerContent) so
                // the same value can be used for the sharedBounds overlay clip during morph
                // transitions; read locally via rememberPreference, the sharedBounds modifier
                // could not see it, and the overlay fell back to RectangleShape — flashing sharp
                // corners during the bounds animation.
                val artworkMinSize =
                    when {
                        veryCompactHeight -> 200.dp
                        compactHeight -> 216.dp
                        else -> 236.dp
                    }
                // Two-sided cap: the full-player-height cap keeps the artwork a constant size
                // regardless of nav-bar visibility (so it doesn't visibly "shrink" when the nav bar
                // appears), while the morph-area cap (maxHeight * 0.82f) guarantees the artwork
                // ALWAYS fits inside the morph area — even when the nav bar eats into the bottom
                // and shrinks the weight(1f) area.
                val artworkHeightLimitFromFull =
                    effectiveFullHeight *
                        when {
                            veryCompactHeight -> 0.32f
                            compactHeight -> 0.35f
                            else -> 0.40f
                        }
                val artworkHeightLimitFromMorph = maxHeight * 0.82f
                val artworkHeightLimit =
                    minOf(artworkHeightLimitFromFull, artworkHeightLimitFromMorph)
                // Landscape gets an explicit size handed down from the player (the
                // hero-of-the-half arrangement); portrait keeps the fraction-based sizing.
                val artworkSize =
                    landscapeArtworkSize
                        ?: run {
                            (maxWidth - horizontalPadding * 2)
                                .coerceAtMost(artworkHeightLimit)
                                .coerceAtLeast(artworkMinSize)
                        }
                // Pause-scale animation (non-canvas songs only), gated on the pause-shrink
                // preference. When the music is paused, the artwork shrinks slightly (~8%) to
                // mirror Apple Music's behavior. When playback resumes, it restores to full
                // size. This only applies to the immersiveExtendedCard branch (static
                // artwork — no Spotify Canvas, no music video). Canvas songs continue
                // playing their loop regardless of audio play state, so shrinking them
                // would look wrong.
                val (pauseScaleEnabled) = rememberPreference(AppleMusicPauseScaleKey, defaultValue = true)
                val artworkPauseScale by animateFloatAsState(
                    targetValue = if (isPlaying || !pauseScaleEnabled) 1f else 0.92f,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    label = "artworkPauseScale",
                )
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = artworkRequest ?: artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(artworkSize)
                                .graphicsLayer {
                                    scaleX = artworkPauseScale
                                    scaleY = artworkPauseScale
                                    // Apply shadow elevation + clip in a single graphicsLayer
                                    // instead of separate .shadow() + .clip() modifiers.
                                    shadowElevation = 8f
                                    clip = true
                                    shape = RoundedCornerShape(artworkCornerRadiusDp)
                                },
                    )
                }
            }
        } else {
            AsyncImage(
                model = artworkRequest ?: artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (showCanvas && !showVideo &&
            (!canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank())
        ) {
            // Landscape canvas blend: the canvas's inner edge (the screen's middle in
            // horizontal mode) dissolves into the blurred backdrop with a gradient mask
            // instead of ending in a hard rectangle — matching the edge-less look
            // non-canvas songs get from the full-bleed backdrop.
            val canvasModifier =
                if (fadeRightEdge) {
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = canvasInnerEdgeFadeBrush,
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier.matchParentSize()
                }
            CanvasArtworkPlayer(
                primaryUrl = canvasPrimaryUrl,
                fallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                modifier = canvasModifier,
            )
        }

        if (showVideo) {
            InlineVideoPlayer(
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun AppleMusicControlsColumn(
    mediaMetadata: MediaMetadata,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    positionProvider: () -> Long,
    duration: Long,
    playerConnection: PlayerConnection,
    currentSongLiked: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    titleActions: PlayerTitleActions,
    onPlayPauseClick: () -> Unit,
    onMoreClick: () -> Unit,
    onOutputClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    // Stream format for the quality chip. Null = no chip rendered.
    currentFormat: FormatEntity?,
    // Clicked when the user taps the quality chip — opens the song-detail
    // bottom sheet (ShowMediaInfo), mirroring how tapping the title/artist
    // in Apple Music's stock UI opens the song info page.
    onQualityChipClick: () -> Unit,
    // When false, the title/artist row is hidden — used in QUEUE/LYRICS state
    // where the title lives in the mini header above.
    showTitleRow: Boolean = true,
    // Whether the in-place queue is currently open. Highlights the queue button.
    isQueueActive: Boolean = false,
    // Whether the in-place lyrics view is currently open. Highlights the lyrics button.
    isLyricsActive: Boolean = false,
    onMorePositioned: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var swipeUpAccumulated by remember { mutableFloatStateOf(0f) }
    val swipeUpThreshold = 120f
    val swipeActivationThreshold = 72f
    val (swipeUpToOpenQueue) = rememberPreference(SwipeUpToOpenQueueKey, defaultValue = true)
    val resetSwipeUp = remember {
        {
            if (swipeUpAccumulated != 0f) swipeUpAccumulated = 0f
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); resetSwipeUp() }

    // The controls cluster (title → bottom action row) is bottom-anchored by the caller. Gaps
    // between rows are controlled by explicit Spacers below (not by verticalArrangement) so they
    // stay predictable and compact.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val compactHeight = screenHeight < 720.dp
    val veryCompactHeight = screenHeight < 620.dp
    val titleToScrubberGap = if (veryCompactHeight) 8.dp else if (compactHeight) 14.dp else 20.dp
    val scrubberToTransportGap = if (veryCompactHeight) 12.dp else if (compactHeight) 14.dp else 18.dp
    val transportToVolumeGap = if (veryCompactHeight) 8.dp else if (compactHeight) 12.dp else 16.dp
    val volumeToActionsGap = if (veryCompactHeight) 12.dp else if (compactHeight) 16.dp else 22.dp

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = AppleMusicContentPadding)
                .pointerInput(swipeUpToOpenQueue) {
                    // Off: no swipe tracking at all, so seeks and taps are never taken over (#171).
                    if (!swipeUpToOpenQueue) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var accumulated = 0f
                        var swipeActivated = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp()) break

                            val dragDelta = change.positionChange().y

                            if (!swipeActivated) {
                                // Track upward movement but don't consume yet — let child taps win.
                                if (dragDelta < 0f) {
                                    accumulated += dragDelta
                                }
                                if (abs(accumulated) > swipeActivationThreshold) {
                                    swipeActivated = true
                                    swipeUpAccumulated = accumulated
                                    change.consume()
                                }
                            } else {
                                // Swipe is confirmed — consume to prevent child handling.
                                if (dragDelta < 0f) {
                                    swipeUpAccumulated =
                                        (swipeUpAccumulated + dragDelta).coerceAtLeast(-swipeUpThreshold * 1.5f)
                                }
                                change.consume()
                            }
                        }

                        if (swipeActivated && swipeUpAccumulated < -swipeUpThreshold) {
                            onQueueClick()
                        }
                        swipeUpAccumulated = 0f
                    }
            },
        // Bottom-aligned — gaps between rows are controlled by explicit
        // Spacers below (titleToScrubberGap, scrubberToTransportGap, etc.).
        // Do NOT use spacedBy here — it would stack on top of the Spacers and
        // double the gaps.
        verticalArrangement = Arrangement.Bottom,
    ) {
    // Title / artist row with star + more chips.
    // Hidden when showTitleRow = false (queue is open — the title lives in
    // the mini header above the queue list).
    if (showTitleRow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerTextBackdrop(
                textColor = Color.White,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppleMusicMarqueeLine(
                        text = mediaMetadata.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        onClick = titleActions.onTitleClick,
                    )
                    AppleMusicMarqueeLine(
                        text = mediaMetadata.artists.joinToString { it.name },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.64f),
                        onClick = { mediaMetadata.artists.firstOrNull()?.id?.let(titleActions.onArtistClick) },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            AppleMusicChip(
                iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
                tint = Color.White,
                contentDescription = null,
                onClick = playerConnection::toggleLike,
            )
            Spacer(Modifier.width(10.dp))
            AppleMusicChip(
                iconRes = R.drawable.player_more_horiz,
                tint = Color.White,
                contentDescription = null,
                onClick = onMoreClick,
                onPositioned = onMorePositioned,
            )
        }
    }

    Spacer(Modifier.height(titleToScrubberGap))

    // Read the polled playback position through the deferred provider. This is
    // the ONLY place in AppleMusicControlsColumn that reads the 100ms-polled
    // position — by reading it here (inside the controls column that is only
    // composed when visible), we ensure the parent AppleMusicPlayerContent
    // does not recompose on every poll tick. When lyrics is open and controls
    // are auto-hidden, this composable is not composed at all, so the state
    // read never fires and no recomposition happens.
    val currentPosition = positionProvider()

    // Thin scrubber + elapsed / -remaining.
    Column {
        AppleMusicSeekBar(
            position = sliderPosition ?: currentPosition,
            duration = duration,
            onScrub = onSliderValueChange,
            onScrubFinished = onSliderValueChangeFinished,
        )
        Spacer(Modifier.height(6.dp))
        // Mirror the Immersive V8 layout: elapsed time on the left, quality
        // chip (Lossless / AAC / OPUS) centered, -remaining on the right.
        // The chip is tappable and opens the song-detail bottom sheet.
        Box(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(
                    text = makeTimeString(sliderPosition ?: currentPosition),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            if (currentFormat != null) {
                AppleMusicQualityChip(
                    currentFormat = currentFormat,
                    onClick = onQualityChipClick,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(
                    text = "-" + makeTimeString((duration - (sliderPosition ?: currentPosition)).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
    }

    Spacer(Modifier.height(scrubberToTransportGap))

    // The ported glyphs fill very different fractions of their 960-unit viewports (the
    // chevrons ~85% of the width, the play/pause glyph ~42%), so the dp sizes are what
    // carry the reference's proportions: 48dp skips put ~41dp of chevron next to an 80dp
    // play/pause whose centre ink is ~34x41dp — the centre reads dominant, as in the
    // reference. At canary's previous 52/64 the same glyphs would render ~44dp chevrons
    // beside a 27dp centre, which is what makes play/pause look smaller than the skips.
    // Scaled proportionally on short screens so the row keeps its footprint there.
    val transportIconSize =
        if (veryCompactHeight) 40.dp else if (compactHeight) 44.dp else AppleMusicTransportIconSize
    val playPauseIconSize =
        if (veryCompactHeight) 67.dp else if (compactHeight) 73.dp else AppleMusicPlayPauseIconSize

    // Bare transport glyphs.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleMusicTransportButton(
            iconRes = R.drawable.apple_skip_previous,
            enabled = canSkipPrevious,
            mirrored = false,
            iconSize = transportIconSize,
            onClick = playerConnection::seekToPrevious,
        )
        // Center slot MUST keep the same outer Box size (iconSize + 20.dp) in both
        // the loading and playing states — otherwise SpaceEvenly redistributes the
        // 20dp gap across the row and prev/next visually slide outward when the
        // spinner replaces the play button.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(playPauseIconSize + 20.dp)
                    .clip(CircleShape),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(AppleMusicPlayPauseSpinnerSize),
                    strokeWidth = 3.dp,
                )
            } else {
                AppleMusicTransportButton(
                    iconRes = if (isPlaying) R.drawable.pause_applemusic else R.drawable.play_applemusic,
                    enabled = true,
                    mirrored = false,
                    iconSize = playPauseIconSize,
                    onClick = onPlayPauseClick,
                )
            }
        }
        AppleMusicTransportButton(
            iconRes = R.drawable.apple_skip_next,
            enabled = canSkipNext,
            mirrored = false,
            iconSize = transportIconSize,
            onClick = playerConnection::seekToNext,
        )
    }

    Spacer(Modifier.height(transportToVolumeGap))

    // Flat volume slider with speaker glyphs. Uses the shared AppleMusicVolumeRow
    // which has proper drag tracking (dragging state + rememberUpdatedState) so the
    // fill follows the finger during a drag instead of lagging behind the rounded
    // device-volume step.
    AppleMusicVolumeRow(
        volume = volume,
        onVolumeChange = onVolumeChange,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(volumeToActionsGap))

    // Bottom action row: lyrics / media output / queue.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleMusicBottomButton(
            iconRes = R.drawable.player_lyrics,
            contentDescription = stringResource(R.string.lyrics),
            onClick = onLyricsClick,
            tint = if (isLyricsActive) Color.White else Color.White.copy(alpha = 0.85f),
        )
        AppleMusicBottomButton(
            iconRes = R.drawable.cast,
            contentDescription = null,
            onClick = onOutputClick,
        )
        AppleMusicBottomButton(
            iconRes = R.drawable.player_queue_music,
            contentDescription = stringResource(R.string.queue),
            onClick = onQueueClick,
            tint = if (isQueueActive) Color.White else Color.White.copy(alpha = 0.85f),
        )
    }
    }
}

/**
 * The inline lyrics renderer for the selected [LyricsMode], shared by the portrait overlay and
 * the landscape right half so the mode→renderer mapping (and its white text override) exists
 * once. Both surfaces are white-on-scrim, so the SimpMusic pane keeps the forced white.
 */
@Composable
private fun AppleMusicInlineLyrics(
    lyricsMode: LyricsMode,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    when (lyricsMode) {
        LyricsMode.V2 ->
            LyricsV2(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
            )
        LyricsMode.ENHANCED ->
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
            )
        LyricsMode.SPOTIFY ->
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                spotifyStyle = true,
            )
        LyricsMode.SIMPMUSIC ->
            SimpMusicLyrics(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                modifier = modifier,
                // This pane is white-on-scrim, so the renderer's default light-grey dim
                // tone would sit almost invisible on it.
                textColorOverride = Color.White,
            )
    }
}

/**
 * The landscape player's title block: song title and artist directly under the artwork on the
 * left half — Apple Music's own landscape arrangement — carrying the like and overflow chips
 * the portrait title row owns, so nothing is lost when the right column switches to
 * controls-only (showTitleRow = false). The two land together: the block without the controls'
 * title row hidden would render the song title twice.
 */
@Composable
private fun AppleMusicLandscapeTitleBlock(
    mediaMetadata: MediaMetadata,
    currentSongLiked: Boolean,
    titleActions: PlayerTitleActions,
    onToggleLike: () -> Unit,
    onMoreClick: () -> Unit,
    contentWidth: Dp,
    onMorePositioned: ((Rect) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                // Aligned to the artwork: the block spans exactly the artwork's width, so
                // title, chips and artwork share one centred visual column (the caller
                // centres the column).
                .width(contentWidth)
                .padding(top = 12.dp, bottom = 10.dp),
    ) {
        PlayerTextBackdrop(
            textColor = Color.White,
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppleMusicMarqueeLine(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    onClick = titleActions.onTitleClick,
                )
                AppleMusicMarqueeLine(
                    text = mediaMetadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.64f),
                    onClick = { mediaMetadata.artists.firstOrNull()?.id?.let(titleActions.onArtistClick) },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        AppleMusicChip(
            iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
            tint = Color.White,
            contentDescription = null,
            onClick = onToggleLike,
        )
        Spacer(Modifier.width(10.dp))
        AppleMusicChip(
            iconRes = R.drawable.player_more_horiz,
            tint = Color.White,
            contentDescription = null,
            onClick = onMoreClick,
            onPositioned = onMorePositioned,
        )
    }
}

@Composable
private fun AppleMusicChip(
    iconRes: Int,
    tint: Color,
    contentDescription: String?,
    onClick: () -> Unit,
    onPositioned: ((Rect) -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(AppleMusicChipSize)
                .let { base ->
                    if (onPositioned != null) {
                        base.onGloballyPositioned { coords -> onPositioned(coords.boundsInRoot()) }
                    } else {
                        base
                    }
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AppleMusicTransportButton(
    iconRes: Int,
    enabled: Boolean,
    mirrored: Boolean,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(iconSize + 20.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = iconSize / 2 + 10.dp),
                    enabled = enabled,
                    onClick = onClick,
                ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier =
                Modifier
                    .size(iconSize)
                    .graphicsLayer { if (mirrored) scaleX = -1f },
        )
    }
}

@Composable
private fun AppleMusicBottomButton(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = Color.White.copy(alpha = 0.85f),
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(AppleMusicBottomButtonSize)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = AppleMusicBottomButtonSize / 2),
                    onClick = onClick,
                ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(AppleMusicBottomIconSize),
        )
    }
}

/**
 * Single-line marquee text with an edge fade that only appears once the laid-out text is wider
 * than the space it's given, plus a tap target. Shared by the full title/artist row and the mini
 * header's compact one — both used the same measure-and-fade shape before this was pulled out.
 */
@Composable
private fun AppleMusicMarqueeLine(
    text: String,
    style: TextStyle,
    color: Color,
    onClick: () -> Unit,
    fontWeight: FontWeight? = null,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val viewport = remember { mutableStateOf(0) }
    val hasOverflow = viewport.value > 0 && (layout.value?.size?.width ?: 0) > viewport.value
    Box(
        modifier = (if (hasOverflow) Modifier.fillMaxWidth().viewportEdgeFade() else Modifier.fillMaxWidth())
            .clipToBounds()
            .onSizeChanged { viewport.value = it.width }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = text,
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout.value = it },
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
        )
    }
}

/**
 * Mini header shown at the top of the QUEUE / LYRICS state. Contains a small
 * artwork (shared element with the large COVER artwork), compact title/artist,
 * and like + more buttons. Mirrors ViviMusic's Player_v2 mini header exactly.
 *
 * @param onArtworkClick Called when the mini artwork is tapped. Restores the
 *   COVER state (morphs back to the full main player).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.AppleMusicMiniHeader(
    artworkRequest: coil3.request.ImageRequest?,
    artworkUrl: String?,
    mediaMetadata: MediaMetadata,
    currentSongLiked: Boolean,
    titleActions: PlayerTitleActions,
    onToggleLike: () -> Unit,
    onMoreClick: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onMorePositioned: ((Rect) -> Unit)? = null,
    onArtworkClick: () -> Unit = {},
    onCloseClick: (() -> Unit)? = null,
    // The COVER artwork's corner radius, used for the OverlayClip during
    // COVER→LYRICS transitions. The mini header's own clip stays at 8dp
    // (its visual style), but the SharedTransition overlay uses this larger
    // radius so corners look properly rounded on the large cover bounds at
    // the start of the morph. See the call site for the full rationale.
    artworkCornerRadiusDp: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .padding(horizontal = AppleMusicContentPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini artwork — shared element with the large COVER artwork. Tapping it restores the COVER
        // state (morphs back to the main player).
        Box(
            modifier =
                Modifier
                    .size(AppleMusicMiniArtworkSize)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = AppleMusicMiniArtworkSize / 2),
                        onClick = onArtworkClick,
                    )
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "amCoverArt"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        clipInOverlayDuringTransition =
                            OverlayClip(
                                AdaptiveCornerShape(
                                    smallRadius = 8.dp,
                                    smallSize = AppleMusicMiniArtworkSize,
                                    largeRadius = artworkCornerRadiusDp,
                                    largeSize = 400.dp,
                                ),
                            ),
                        // Match the COVER state's boundsTransform (non-bouncy
                        // spring at default StiffnessMediumLow) so the morph
                        // duration and feel are identical in both directions
                        // and the overlay clip doesn't stutter on oscillation.
                        // See the COVER state's sharedBounds modifier for the
                        // full rationale (including pink-flash safety).
                        boundsTransform =
                            BoundsTransform { _, _ ->
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                            },
                    ),
        ) {
            AsyncImage(
                model = artworkRequest ?: artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        PlayerTextBackdrop(
            textColor = Color.White,
            modifier = Modifier.weight(1f),
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppleMusicMarqueeLine(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                onClick = titleActions.onTitleClick,
            )
            AppleMusicMarqueeLine(
                text = mediaMetadata.artists.joinToString { it.name },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f),
                onClick = { mediaMetadata.artists.firstOrNull()?.id?.let(titleActions.onArtistClick) },
            )
        }
        }
        AppleMusicChip(
            iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
            tint = Color.White,
            contentDescription = null,
            onClick = onToggleLike,
        )
        Spacer(Modifier.width(8.dp))
        AppleMusicChip(
            iconRes = R.drawable.player_more_horiz,
            tint = Color.White,
            contentDescription = null,
            onClick = onMoreClick,
            onPositioned = onMorePositioned,
        )
        onCloseClick?.let { closeClick ->
            Spacer(Modifier.width(8.dp))
            AppleMusicChip(
                iconRes = R.drawable.close,
                tint = Color.White,
                contentDescription = stringResource(R.string.close),
                onClick = closeClick,
            )
        }
    }
}

/** Thin Apple-Music-style scrubber: rounded 6dp track, no thumb, tap + drag to seek. */
@Composable
private fun AppleMusicSeekBar(
    position: Long,
    duration: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val enabled = duration > 0L
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val playedFraction =
        if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownFraction = if (dragging) dragFraction else playedFraction

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub((fraction * duration).toLong())
                        onScrubFinished()
                    }
                }.pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrub((dragFraction * duration).toLong())
                        },
                        onDragEnd = {
                            dragging = false
                            onScrubFinished()
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            onScrub((dragFraction * duration).toLong())
                        },
                    )
                }.drawWithContent {
                    val trackHeight = if (dragging) 10.dp.toPx() else 7.dp.toPx()
                    val top = (size.height - trackHeight) / 2f
                    val radius = CornerRadius(trackHeight / 2f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, trackHeight),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (dragging) 1f else 0.85f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width * shownFraction, trackHeight),
                        cornerRadius = radius,
                    )
                },
    )
}

/**
 * Quality chip rendered between the elapsed and -remaining timestamps on the Apple Music player's
 * seek-bar row.
 */
@Composable
private fun AppleMusicQualityChip(
    currentFormat: FormatEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = remember(currentFormat.mimeType, currentFormat.codecs) {
        currentFormat.codecLabel()
    }
    val lossless = remember(currentFormat.codecs, currentFormat.mimeType) {
        currentFormat.isLossless()
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.White.copy(alpha = 0.1f),
        border = BorderStroke(width = 1.dp, color = Color.White.copy(alpha = 0.13f)),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (lossless) R.drawable.ic_mqa else R.drawable.player_graphic_eq,
                ),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(if (lossless) 18.dp else 15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}
