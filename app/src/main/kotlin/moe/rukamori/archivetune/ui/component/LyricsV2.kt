/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.player.LocalLyricsScrollListener
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineBlurKey
import moe.rukamori.archivetune.constants.LyricsLineSpacingKey
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.constants.LyricsV2BounceFactorKey
import moe.rukamori.archivetune.constants.LyricsV2EmphasizeLastWordKey
import moe.rukamori.archivetune.constants.LyricsV2SoftWordRevealKey
import moe.rukamori.archivetune.constants.LyricsV2InterludeDotsKey
import moe.rukamori.archivetune.constants.LyricsV2AdaptiveScrollSpringKey
import moe.rukamori.archivetune.constants.LyricsV2FillTransitionWidthKey
import moe.rukamori.archivetune.constants.LyricsV2GlowFactorKey
import moe.rukamori.archivetune.constants.LyricsV2LrcBounceEnabledKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.lyrics.LyricsUtils.findCurrentLineIndex
import moe.rukamori.archivetune.lyrics.LyricsUtils.hasTrueWordSync
import moe.rukamori.archivetune.lyrics.LyricsUtils.insertInstrumentalBreaks
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isRtlText
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedRomanizedTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.romanizeLyricsLine
import moe.rukamori.archivetune.lyrics.LyricsUtils.shouldRomanizeLyricsLine
import moe.rukamori.archivetune.lyrics.WordTimestamp
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.theme.rememberArchiveTuneLyricsFontFamily
import moe.rukamori.archivetune.ui.utils.smoothFadingEdge
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** Lead time offset for LRC-style line-synced lyrics (ms). */
private const val LRC_LEAD_MS = 300L

/** Lead time offset for TTML word-synced lyrics (ms). */
private const val TTML_LEAD_MS = 0L

private const val LYRIC_VISUAL_TUNING_OFFSET_MS = 150L

/**
 * Minimum duration (ms) for the per-word letter-by-letter sweep animation.
 * Without this floor, very short words (< ~50ms) can be skipped entirely
 * between 16ms position polls, causing the word to jump from 0% to 100%
 * with no visible sweep. 180ms is long enough to be clearly visible while
 * short enough not to lag noticeably behind the audio for typical words.
 */
private const val MIN_SWEEP_MS = 180L

/**
 * Backward jump in the player's clock that counts as a restart (REPEAT_MODE_ONE
 * wrapping from duration back to 0, or an explicit backward seek) rather than
 * playback jitter or an ExoPlayer position correction.
 */
private const val V2_POSITION_RESET_BACKWARD_THRESHOLD_MS = 1_000L

/** Seconds to wait before auto-scroll resumes after manual scroll. */
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L
// The lines stay transparent until the active one has been placed, then fade
// in over this long. See `awaitingFirstFocus`.
private const val V2_FIRST_FOCUS_FADE_MS = 200
// Hard ceiling on how long the lines may stay hidden waiting for that placement.
private const val V2_FIRST_FOCUS_TIMEOUT_MS = 400L

/** Sentinel entry prepended so auto-scroll has headroom above the first line. */
private val HEAD_LYRICS_ENTRY = LyricsEntry(time = 0L, text = "")

/**
 * Per-Composable cache for [LyricsUtils.hasTrueWordSync] results. The check allocates several Lists
 * (filter, map, distinct) per call and is pure — same entry → same result for the lifetime of the
 * lyrics.
 */
private class WordSyncCache {
    private val cache = HashMap<LyricsEntry, Boolean>()

    fun getOrCompute(entry: LyricsEntry): Boolean =
        cache.getOrPut(entry) { hasTrueWordSync(entry) }
}

@Composable
private fun rememberWordSyncCache(): WordSyncCache = remember { WordSyncCache() }

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LyricsV2(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    lyricsLineBlurOverride: Boolean? = null,
    spotifyStyle: Boolean = false,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsScroll) = rememberPreference(LyricsScrollKey, defaultValue = true)
    val (lyricsTextSize) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val (lyricsLineSpacing) = rememberPreference(LyricsLineSpacingKey, defaultValue = 1.3f)
    // Modifier.blur() is applied to every visible line continuously while lyrics is
    // open (see the .blur() call below) -- it is the heaviest per-frame cost in this
    // view. Default OFF so word-synced lyrics are smooth out of the box.
    val (lyricsLineBlurPreference) = rememberPreference(LyricsLineBlurKey, defaultValue = false)
    val (bounceFactorPreference) = rememberPreference(LyricsV2BounceFactorKey, defaultValue = 1f)
    val (glowFactorPreference) = rememberPreference(LyricsV2GlowFactorKey, defaultValue = 1f)
    val (fillTransitionWidth) = rememberPreference(LyricsV2FillTransitionWidthKey, defaultValue = 8f)
    val (lrcBounceEnabledPreference) = rememberPreference(LyricsV2LrcBounceEnabledKey, defaultValue = true)
    val (softWordReveal) = rememberPreference(LyricsV2SoftWordRevealKey, defaultValue = true)
    val (emphasizeLastWordPreference) = rememberPreference(LyricsV2EmphasizeLastWordKey, defaultValue = false)
    val (interludeDotsPreference) = rememberPreference(LyricsV2InterludeDotsKey, defaultValue = true)
    val (adaptiveScrollSpring) = rememberPreference(LyricsV2AdaptiveScrollSpringKey, defaultValue = true)
    // The V2 renderer never honored the reduce-animations setting: on low-RAM devices (where it
    // defaults on) the per-word glow shadows, bounce springs and line blur are the difference
    // between smooth and stuttering karaoke, so gate them all here.
    val v2AnimationsDisabled = LocalAnimationsDisabled.current
    val bounceFactor = if (v2AnimationsDisabled) 0f else bounceFactorPreference
    val glowFactor = if (v2AnimationsDisabled) 0f else glowFactorPreference
    val interludeDotsEnabled = interludeDotsPreference && !v2AnimationsDisabled
    val emphasizeLastWord = emphasizeLastWordPreference && !v2AnimationsDisabled
    val lrcBounceEnabled = lrcBounceEnabledPreference && !v2AnimationsDisabled
    val (romanizeChinese) = rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val (romanizeHindi) = rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val (romanizeJapanese) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = true)
    val (romanizeKorean) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (romanizeOtherLanguages) = rememberPreference(LyricsRomanizeOtherLanguagesKey, defaultValue = true)
    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val romanizationPreferences =
        remember(
            romanizeJapanese,
            romanizeKorean,
            romanizeChinese,
            romanizeHindi,
            romanizeOtherLanguages,
            aiRomanizationSettings.active,
        ) {
            LyricsRomanizationPreferences(
                romanizeJapanese = romanizeJapanese,
                romanizeKorean = romanizeKorean,
                romanizeChinese = romanizeChinese,
                romanizeHindi = romanizeHindi,
                romanizeOther = romanizeOtherLanguages,
                aiHandled = aiRomanizationSettings.active,
            )
        }
    val lyricsFontFamily = rememberArchiveTuneLyricsFontFamily()

    // Apple Music style and all lyrics backgrounds always use white text so that
    // lyrics remain readable on the dark blurred backdrop regardless of system theme.
    val textColor = textColorOverride ?: Color.White
    val lyricsLineBlur = (lyricsLineBlurOverride ?: lyricsLineBlurPreference) && !v2AnimationsDisabled

    val inactiveAlpha = 0.35f

    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var showShareImageDialog by remember { mutableStateOf(false) }

    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyrics = currentLyrics?.lyrics
    val showTranslations =
        remember(currentLyrics?.source) {
            currentLyrics?.source in setOf(LyricsEntity.Source.AI_TRANSLATION.value, LyricsEntity.Source.TRANSLATION.value)
        }

    val isSynced = remember(lyrics) { lyrics != null && (isLineSyncedLrc(lyrics!!) || isTtml(lyrics!!)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics!!) }

    // Parsed off the composition thread — same reason as LyricsEnhanced: a word-synced TTML file
    // is an XML parse plus one object per syllable, and running it in composition put the whole
    // cost on the frame the Apple Music COVER->LYRICS morph starts. `null` means "not parsed yet",
    // which the render path below shows as the shimmer rather than "lyrics not found".
    var parsedEntries by remember(lyrics) { mutableStateOf<List<LyricsEntry>?>(null) }
    LaunchedEffect(lyrics) {
        val text = lyrics
        if (text == null || text == LYRICS_NOT_FOUND) {
            parsedEntries = emptyList()
            return@LaunchedEffect
        }
        val dur = player.duration.takeIf { it > 0L } ?: 0L
        parsedEntries =
            withContext(Dispatchers.Default) {
                val parsed =
                    when {
                        isTtml(text) -> parseTtml(text)
                        isLineSyncedLrc(text) -> insertInstrumentalBreaks(parseLyrics(text), dur)
                        else ->
                            text
                                .lines()
                                .filter { it.isNotBlank() }
                                .map { line -> LyricsEntry(time = -1L, text = line.trim()) }
                    }
                if (parsed.isNotEmpty() && parsed.first().time >= 0) {
                    listOf(HEAD_LYRICS_ENTRY) + parsed
                } else {
                    parsed
                }
            }
    }
    val lyricsEntries: List<LyricsEntry> = parsedEntries.orEmpty()

    val entriesWithWords: List<LyricsEntry> = lyricsEntries

    // Per-entry cache for hasTrueWordSync() — avoids re-running the (allocating) word-sync
    // detection on every 16 ms position tick for every visible line.
    val wordSyncCache = rememberWordSyncCache()

    // One batched request per track (network + billed), so it cannot hang off the per-line pass
    // below. Results are pushed into the same `romanizedTextFlow` the built-in engines write to, so
    // the render path and the fade-in behaviour are identical whichever engine produced them.
    val aiRomanizationSessionKey =
        remember(mediaMetadata?.id, lyrics) { AiLyricsRomanization.sessionKey(mediaMetadata?.id, lyrics) }
    val aiRomanizationResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    // Resolved by line text, not by index — this renderer's entry list carries a head entry and
    // instrumental breaks that LyricsEnhanced's does not, and both derive the same session key. See
    // AiLyricsRomanization.Result.
    val aiRomanizedLines: List<String?> =
        remember(aiRomanizationResult, aiRomanizationSessionKey, aiRomanizationSettings.active, entriesWithWords) {
            if (!aiRomanizationSettings.active) {
                emptyList()
            } else {
                AiLyricsRomanization.linesFor(aiRomanizationSessionKey, entriesWithWords.map { it.text })
            }
        }
    LaunchedEffect(aiRomanizationSessionKey, entriesWithWords, aiRomanizationSettings) {
        if (!aiRomanizationSettings.active || !aiRomanizationSettings.auto) return@LaunchedEffect
        if (entriesWithWords.isEmpty()) return@LaunchedEffect
        AiLyricsRomanization.request(
            sessionKey = aiRomanizationSessionKey,
            lines = entriesWithWords.map { it.text },
            settings = aiRomanizationSettings,
        )
    }
    LaunchedEffect(entriesWithWords, aiRomanizedLines, aiRomanizationSettings.active) {
        if (!aiRomanizationSettings.active) return@LaunchedEffect
        entriesWithWords.forEachIndexed { index, entry ->
            val romanized = aiRomanizedLines.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
            if (entry.romanizedTextFlow.value != romanized) {
                entry.romanizedTextFlow.value = romanized
            }
        }
    }

    LaunchedEffect(entriesWithWords, romanizationPreferences) {
        if (!romanizationPreferences.isEnabled) {
            // Guard against clobbering AI results: when the AI engine owns romanisation the effect
            // above is the writer, and blanking the flows here would erase it on every recomposition
            // that changed `entriesWithWords`.
            if (aiRomanizationSettings.active) return@LaunchedEffect
            entriesWithWords.forEach { entry ->
                if (entry.romanizedTextFlow.value != null) {
                    entry.romanizedTextFlow.value = null
                }
            }
            return@LaunchedEffect
        }

        entriesWithWords.forEach { entry ->
            val providerRomanized = providedRomanizedTextForEntry(entry, romanizationPreferences)
            if (providerRomanized != null) {
                if (entry.romanizedTextFlow.value != providerRomanized) {
                    entry.romanizedTextFlow.value = providerRomanized
                }
                return@forEach
            }

            if (!shouldRomanizeLyricsLine(entry.text, romanizationPreferences)) {
                if (entry.romanizedTextFlow.value != null) {
                    entry.romanizedTextFlow.value = null
                }
                return@forEach
            }

            // Off the UI thread: one romanization per line on Main would queue dozens of
            // dispatcher hops right as the karaoke animation starts.
            launch(Dispatchers.Default) {
                val romanized =
                    try {
                        romanizeLyricsLine(entry.text, romanizationPreferences)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                        null
                    }
                entry.romanizedTextFlow.value = romanized
            }
        }
    }

    val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS
    // Hold the position in an explicit MutableState so we can expose a stable provider lambda
    // that reads the current value. The lambda identity is stable across position updates, so
    // passing it to child composables never by itself triggers their recomposition — only the
    // composables that actually invoke the lambda (active / near-active lines) re-read it.
    // Key position-owned state by the lyrics payload. A repeat keeps the same
    // payload, but the position loop below explicitly resets the values when
    // it observes the playback clock wrap back to the start.
    val currentPositionMsState = remember(lyrics) { mutableLongStateOf(0L) }
    var currentPositionMs by currentPositionMsState
    var playbackPositionMs by remember { mutableLongStateOf(0L) }
    var currentLineIndex by remember { mutableIntStateOf(0) }

    val currentPositionProvider: () -> Long =
        remember { { currentPositionMsState.longValue } }

    // rememberUpdatedState so the playback loop always sees the latest
    // sliderPositionProvider lambda. Without this, if the caller's lambda
    // identity changes between recompositions (e.g. in the Apple Music
    // player where the lyrics overlay sits inside an AnimatedVisibility
    // whose parent recomposes on every position tick), the LaunchedEffect
    // would keep capturing the old lambda and never see new positions,
    // freezing the V2 lyrics in place.
    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)

    var lastRawPositionMs by remember(lyrics) { mutableLongStateOf(0L) }
    var playbackResetTick by remember(lyrics) { mutableIntStateOf(0) }

    LaunchedEffect(entriesWithWords, isSynced, leadMs, lyricsSyncOffset) {
        if (!isSynced || entriesWithWords.isEmpty()) return@LaunchedEffect
        // 16 ms recomposes every visible word ~60×/s; with animations disabled a coarser tick is
        // indistinguishable (fills snap anyway) and much cheaper.
        val pollIntervalMs =
            when {
                v2AnimationsDisabled -> 100L
                isTtmlFormat -> 16L
                else -> 50L
            }
        // For the high-frequency TTML path, drive the poll from the choreographer frame clock
        // instead of a fixed `delay(16)`. When the device is under GPU pressure (most commonly
        // because a Spotify Canvas video is compositing on a TextureView alongside the lyrics),
        // the frame clock naturally drops to 30/24 fps; `delay(16)` would keep firing at 60 Hz
        // and pile up recompositions on top of the slow frames, which is what makes the karaoke
        // fill animation lag visibly behind the audio. `withFrameNanos` ties the poll cadence to
        // the actual frame rate so the two stay in lock-step.
        val useFrameClock = !v2AnimationsDisabled && isTtmlFormat
        while (isActive) {
            val sliderPos = latestSliderPositionProvider.value()
            val pos = sliderPos ?: player.currentPosition

            // REPEAT_MODE_ONE remains in STATE_READY, so lifecycle/state based restart detection
            // never fires.
            val rawPlayerPositionMs = player.currentPosition.coerceAtLeast(0L)
            if (lastRawPositionMs - rawPlayerPositionMs > V2_POSITION_RESET_BACKWARD_THRESHOLD_MS) {
                playbackResetTick++
            }
            lastRawPositionMs = rawPlayerPositionMs

            playbackPositionMs = (pos + lyricsSyncOffset.toLong()).coerceAtLeast(0L)
            currentPositionMs = (playbackPositionMs + leadMs + LYRIC_VISUAL_TUNING_OFFSET_MS).coerceAtLeast(0L)

            currentLineIndex = findCurrentLineIndex(entriesWithWords, currentPositionMs, 0L)
            if (useFrameClock) {
                withFrameNanos { }
            } else {
                delay(pollIntervalMs)
            }
        }
    }

    // Recreated on a repeat/backward-seek for the same reason the item keys below include
    // [playbackResetTick]: that tick changes EVERY item key at once, so the LazyColumn's
    // bookkeeping (its remembered first-visible key, its item animations, the pending
    // animateScrollToItem target) all refer to items that no longer exist, and carrying the
    // old state across that swap anchors the list to a vanished key. A fresh state starts
    // cleanly at the top, and the auto-scroll effect takes over from the first active line.
    val listState = key(playbackResetTick) { rememberLazyListState() }
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }

    // ── First-frame placement ── A fresh LazyListState starts at line 0 and the auto-scroll effect
    // below animates it to the active line, so opening the view showed the first verse for a moment
    // and then scrolled itself to wherever the song actually is.
    var awaitingFirstFocus by
        remember(playbackResetTick) {
            mutableStateOf(isSynced)
        }
    // Safety net: never leave the lyrics hidden because a placement never came.
    LaunchedEffect(awaitingFirstFocus) {
        if (!awaitingFirstFocus) return@LaunchedEffect
        delay(V2_FIRST_FOCUS_TIMEOUT_MS)
        awaitingFirstFocus = false
    }
    val firstFocusAlpha =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (awaitingFirstFocus) 0f else 1f,
            animationSpec =
                tween(
                    durationMillis = if (awaitingFirstFocus) 0 else V2_FIRST_FOCUS_FADE_MS,
                    easing = LinearEasing,
                ),
            label = "lyrics-v2-first-focus-alpha",
        )

    // Detect manual scrolling
    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                        isManualScrolling = true
                        lastManualScrollTime = System.currentTimeMillis()
                    }
                    return Offset.Zero
                }
            }
        }

    // Resume auto-scroll after timeout
    LaunchedEffect(isManualScrolling, lastManualScrollTime) {
        if (isManualScrolling) {
            delay(MANUAL_SCROLL_TIMEOUT_MS)
            isManualScrolling = false
        }
    }

    // Forward the user-scroll signal up to LyricsScreen via LocalLyricsScrollListener so the
    // Apple Music-style bottom controls can slide in when the user scrolls lyrics.
    val onLyricsScroll = LocalLyricsScrollListener.current
    LaunchedEffect(isManualScrolling) {
        onLyricsScroll(isManualScrolling)
    }

    // Auto-scroll to active line.
    //
    // `entriesWithWords.size` is part of the key so the first-focus placement below still runs when
    // the off-thread parse publishes without changing `currentLineIndex` (the common case: the view
    // opens mid-song, the index loop resolves the same line the list already thinks is current).
    // Re-running this effect on content changes is cheap — unlike re-running the `remember` that
    // owns `awaitingFirstFocus`, which is what caused the flicker.
    LaunchedEffect(currentLineIndex, isManualScrolling, lyricsScroll, entriesWithWords.size) {
        if (!lyricsScroll || isManualScrolling || !isSynced) {
            awaitingFirstFocus = false
            return@LaunchedEffect
        }
        if (currentLineIndex < 0 || currentLineIndex >= entriesWithWords.size) {
            // Nothing to place yet: either the parse hasn't published (empty list) or playback
            // hasn't reached the first line. Only release the gate in the second case — while the
            // list is still empty the effect will re-run as soon as an index becomes valid, and
            // releasing here would let line 0 draw and then visibly walk to the active line.
            if (entriesWithWords.isNotEmpty()) awaitingFirstFocus = false
            return@LaunchedEffect
        }

        if (awaitingFirstFocus) {
            // Wait for the first measure so the 35% anchor is computed against a
            // real viewport, then jump straight there while the list is still
            // transparent.
            val viewportHeight =
                listState.layoutInfo.viewportSize.height.takeIf { it > 0 }
                    ?: snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            listState.scrollToItem(
                index = currentLineIndex,
                scrollOffset = -(viewportHeight * 0.35f).toInt(),
            )
            awaitingFirstFocus = false
            return@LaunchedEffect
        }

        val visibleInfo = listState.layoutInfo
        val viewportHeight = visibleInfo.viewportSize.height
        val targetOffset = (viewportHeight * 0.35f).toInt() // Center bias at 35% from top

        val distance = abs(currentLineIndex - (listState.firstVisibleItemIndex))
        if (distance > 15) {
            // Far jump — snap first, then settle
            listState.scrollToItem(
                (currentLineIndex - 2).coerceAtLeast(0),
                0,
            )
        }
        if (adaptiveScrollSpring) {
            listState.intervalAdaptiveScrollTo(
                index = currentLineIndex,
                scrollOffset = -targetOffset,
                intervalMs = lineIntervalMs(entriesWithWords, currentLineIndex),
                isSeeking = false,
                isInterludeActive = entriesWithWords.getOrNull(currentLineIndex)?.isInstrumental == true,
            )
        } else {
            listState.animateScrollToItem(
                index = currentLineIndex,
                scrollOffset = -targetOffset,
            )
        }
    }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.max_selection_limit, maxSelectionLimit),
                    Toast.LENGTH_SHORT,
                ).show()
            showMaxSelectionToast = false
        }
    }

    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = 12.dp)
                // Draw-phase read — holds everything back until the active line has been placed
                // (see awaitingFirstFocus), then fades it in.
                //
                // The gate covers the whole subtree rather than just the LazyColumn because the
                // branches below swap while it is armed: shimmer first, then the lines once the
                // off-thread parse publishes. Gating only the list meant the shimmer drew at full
                // opacity and then vanished the instant the lines took over — the "lyrics appear,
                // blink out, come back" symptom. When nothing needs placing (no lyrics, plain
                // lyrics, not-found) the gate is never armed and this is a no-op.
                .graphicsLayer { alpha = firstFocusAlpha.value },
    ) {
        if (lyrics == LYRICS_NOT_FOUND) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        // parsedEntries == null: the off-thread parse hasn't published yet. Shimmer on that
        // state so the "lyrics not found" branch below doesn't flash while it runs.
        if (lyrics == null || parsedEntries == null) {
            ShimmerHost {
                repeat(6) {
                    TextPlaceholder()
                }
            }
            return@BoxWithConstraints
        }

        if (entriesWithWords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .smoothFadingEdge(vertical = 80.dp)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = entriesWithWords,
                // Include repeat resets so a repeated word receives a new
                // Animatable instead of retaining its completed sweep.
                key = { index, entry -> "${playbackResetTick}_${index}_${entry.time}" },
                contentType = { _, entry ->
                    when {
                        entry == HEAD_LYRICS_ENTRY -> "head"
                        entry.isInstrumental -> "instrumental"
                        entry.words != null && isSynced -> "wordSynced"
                        else -> "lineSynced"
                    }
                },
            ) { index, item ->
                if (item == HEAD_LYRICS_ENTRY) {
                    Spacer(modifier = Modifier.height(120.dp))
                    return@itemsIndexed
                }

                if (item.isInstrumental && isSynced) {
                    InstrumentalBreakRow(
                        item = item,
                        index = index,
                        hasHeadEntry = entriesWithWords.isNotEmpty() && entriesWithWords[0] === HEAD_LYRICS_ENTRY,
                        currentLineIndex = currentLineIndex,
                        isManualScrolling = isManualScrolling,
                        lyricsLineSpacing = lyricsLineSpacing,
                        lyricsLineBlur = lyricsLineBlur,
                        lyricsClick = lyricsClick,
                        textColor = textColor,
                        inactiveAlpha = inactiveAlpha,
                        playbackPositionProvider = { playbackPositionMs },
                        onSeek = { time -> player.seekTo(time) },
                        interludeDots = interludeDotsEnabled,
                    )
                    return@itemsIndexed
                }

                // v1 or null -> Start, v2 -> End, others -> Center
                val textAlign =
                    when (item.agent?.lowercase()) {
                        "v1", null -> TextAlign.Start
                        "v2" -> TextAlign.End
                        else -> TextAlign.Center
                    }
                val horizontalAlignment =
                    when (item.agent?.lowercase()) {
                        "v1", null -> Alignment.Start
                        "v2" -> Alignment.End
                        else -> Alignment.CenterHorizontally
                    }

                val isActive = isSynced && index == currentLineIndex
                val isPast = isSynced && index < currentLineIndex
                val isSelected = selectedIndices.contains(index)

                // Distance-based alpha for non-active lines
                val distanceFromActive = if (isSynced) abs(index - currentLineIndex) else 0
                val lineAlpha =
                    when {
                        !isSynced -> {
                            0.92f
                        }

                        isActive -> {
                            1f
                        }

                        isManualScrolling -> {
                            when {
                                distanceFromActive == 1 -> 0.72f
                                distanceFromActive == 2 -> 0.56f
                                distanceFromActive == 3 -> 0.40f
                                else -> 0.28f
                            }
                        }

                        distanceFromActive == 1 -> {
                            0.52f
                        }

                        distanceFromActive == 2 -> {
                            0.30f
                        }

                        distanceFromActive == 3 -> {
                            0.18f
                        }

                        else -> {
                            0.10f
                        }
                    }
                val targetBlur =
                    when {
                        !isSynced || isActive || (isSelectionModeActive && isSelected) || isManualScrolling -> 0f
                        distanceFromActive == 1 -> 2f
                        distanceFromActive == 2 -> 5f
                        else -> 12f
                    }
                val animatedBlur by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = targetBlur,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 300,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    label = "v2LyricBlur",
                )
                val animatedLineScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.95f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 166,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    label = "v2LineScale",
                )
                val animatedLineAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = lineAlpha,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = if (isActive) 330 else 500,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    label = "v2LineAlpha",
                )
                // Spotify mode: the incoming line rises into place while the
                // outgoing one keeps its fade — together with the list glide
                // this reads as "lines drift up smoothly" instead of a snap.
                val spotifyRise = remember { Animatable(0f) }
                LaunchedEffect(isActive, spotifyStyle) {
                    if (!spotifyStyle) return@LaunchedEffect
                    if (isActive) {
                        spotifyRise.snapTo(1f)
                        spotifyRise.animateTo(
                            0f,
                            tween(durationMillis = 380, easing = FastOutSlowInEasing),
                        )
                    } else {
                        spotifyRise.snapTo(0f)
                    }
                }
                val lineTransformOrigin =
                    remember(item.agent) {
                        when (item.agent?.lowercase()) {
                            "v2" -> {
                                androidx.compose.ui.graphics
                                    .TransformOrigin(1f, 0.5f)
                            }

                            "v1", null -> {
                                androidx.compose.ui.graphics
                                    .TransformOrigin(0f, 0.5f)
                            }

                            else -> {
                                androidx.compose.ui.graphics
                                    .TransformOrigin(0.5f, 0.5f)
                            }
                        }
                    }

                val isAllBackground = item.words?.all { it.isBackground || it.text.isBlank() } == true
                val baseLayoutDirection = LocalLayoutDirection.current
                val lineText =
                    remember(item.text, item.words) {
                        item.words
                            ?.joinToString(separator = "") { it.text }
                            ?.takeIf { it.isNotBlank() }
                            ?: item.text
                    }
                val lineIsRtl = remember(lineText) { isRtlText(lineText) }
                val lineLayoutDirection =
                    remember(lineIsRtl, baseLayoutDirection) {
                        if (lineIsRtl) LayoutDirection.Rtl else baseLayoutDirection
                    }

                CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    color =
                                        if (isSelected && isSelectionModeActive) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        } else {
                                            Color.Transparent
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                ).padding(
                                    start = if (isAllBackground) 24.dp else 12.dp,
                                    end = 12.dp,
                                    top =
                                        if (index == 0 ||
                                            (index == 1 && entriesWithWords[0] == HEAD_LYRICS_ENTRY)
                                        ) {
                                            0.dp
                                        } else {
                                            (lyricsLineSpacing * 8).dp
                                        },
                                    bottom = (lyricsLineSpacing * 8).dp,
                                ).then(
                                    if (lyricsLineBlur) {
                                        Modifier.blur(
                                            radiusX = animatedBlur.dp,
                                            radiusY = animatedBlur.dp,
                                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).graphicsLayer {
                                    scaleX = animatedLineScale
                                    scaleY = animatedLineScale
                                    alpha = animatedLineAlpha
                                    transformOrigin = lineTransformOrigin
                                    if (spotifyStyle) {
                                        translationY = spotifyRise.value * 14.dp.toPx()
                                    }
                                }.combinedClickable(
                                    enabled = true,
                                    onClick = {
                                        if (isSelectionModeActive) {
                                            if (isSelected) {
                                                selectedIndices.remove(index)
                                                if (selectedIndices.isEmpty()) {
                                                    isSelectionModeActive = false
                                                }
                                            } else {
                                                if (selectedIndices.size < maxSelectionLimit) {
                                                    selectedIndices.add(index)
                                                } else {
                                                    showMaxSelectionToast = true
                                                }
                                            }
                                        } else if (lyricsClick && isSynced && item.time > 0) {
                                            player.seekTo(item.time)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionModeActive) {
                                            isSelectionModeActive = true
                                            selectedIndices.add(index)
                                        } else if (!isSelected && selectedIndices.size < maxSelectionLimit) {
                                            selectedIndices.add(index)
                                        } else if (!isSelected) {
                                            showMaxSelectionToast = true
                                        }
                                    },
                                ),
                        horizontalAlignment = horizontalAlignment,
                    ) {
                        val romanizedText =
                            // `showsRomanization`, not `isEnabled`: the latter is false whenever the
                            // AI engine owns romanisation (that is what tells the built-in pass to
                            // stand down), so gating on it meant the AI results pushed into
                            // `romanizedTextFlow` above were never composed at all — romanisation
                            // simply never appeared in this renderer with AI romanisation on.
                            if (romanizationPreferences.showsRomanization) {
                                val value by item.romanizedTextFlow.collectAsStateWithLifecycle()
                                value
                            } else {
                                null
                            }
                        val translationText =
                            remember(showTranslations, item.providerTranslationText, item.text) {
                                if (showTranslations) providedTranslationTextForEntry(item) else null
                            }
                        val supplementaryBaseTextStyle = MaterialTheme.typography.bodyMedium
                        val supplementaryTextStyle =
                            remember(supplementaryBaseTextStyle, lyricsTextSize, lyricsFontFamily, isAllBackground) {
                                supplementaryBaseTextStyle.copy(
                                    fontSize = (lyricsTextSize * 0.55f).sp,
                                    lineHeight = (lyricsTextSize * 0.75f).sp,
                                    fontWeight = FontWeight.Normal,
                                    fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal,
                                    fontFamily = lyricsFontFamily ?: supplementaryBaseTextStyle.fontFamily,
                                )
                            }

                        if (romanizedText != null) {
                            Text(
                                text = romanizedText,
                                style = supplementaryTextStyle,
                                color = textColor.copy(alpha = if (isActive) 0.76f else 0.42f),
                                textAlign = textAlign,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = (lyricsTextSize * 0.18f).dp),
                            )
                        }

                        if (item.words != null && isSynced && wordSyncCache.getOrCompute(item)) {
                            if (spotifyStyle) {
                                LyricsLineSpotify(
                                    words = item.words!!,
                                    isActive = isActive,
                                    isPast = isPast,
                                    distanceFromActive = distanceFromActive,
                                    currentPositionProvider = currentPositionProvider,
                                    textColor = textColor,
                                    inactiveAlpha = inactiveAlpha,
                                    baseFontSize = lyricsTextSize,
                                    isLineAllBackground = isAllBackground,
                                    textAlign = textAlign,
                                    lyricsFontFamily = lyricsFontFamily,
                                    isRtl = lineIsRtl,
                                    softReveal = softWordReveal,
                                )
                            } else {
                                LyricsLineV2(
                                    words = item.words!!,
                                    isActive = isActive,
                                    isPast = isPast,
                                    distanceFromActive = distanceFromActive,
                                    currentPositionProvider = currentPositionProvider,
                                    textColor = textColor,
                                    inactiveAlpha = inactiveAlpha,
                                    baseFontSize = lyricsTextSize,
                                    isLineAllBackground = isAllBackground,
                                    textAlign = textAlign,
                                    lyricsFontFamily = lyricsFontFamily,
                                    isRtl = lineIsRtl,
                                    bounceFactor = bounceFactor,
                                    glowFactor = glowFactor,
                                    fillTransitionWidth = fillTransitionWidth,
                                    softReveal = softWordReveal,
                                    emphasizeEnabled = emphasizeLastWord,
                                )
                            }
                        } else if (isSynced) {
                            LyricsLineLrcBounce(
                                text = item.text,
                                isActive = isActive,
                                textColor = textColor.copy(alpha = if (isActive) 1f else 0.52f),
                                fontSize = lyricsTextSize,
                                lineSpacing = lyricsLineSpacing,
                                isAllBackground = isAllBackground,
                                lyricsFontFamily = lyricsFontFamily,
                                textAlign = textAlign,
                                bounceFactor = if (lrcBounceEnabled) bounceFactor else 0f,
                            )
                        } else {
                            Text(
                                text = item.text,
                                style =
                                    MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = if (isAllBackground) (lyricsTextSize * 0.82f).sp else lyricsTextSize.sp,
                                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal,
                                        lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                        fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                                    ),
                                color = textColor.copy(alpha = if (isActive) 1f else 0.52f),
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (translationText != null) {
                            Text(
                                text = translationText,
                                style = supplementaryTextStyle,
                                color = textColor.copy(alpha = if (isActive) 0.76f else 0.42f),
                                textAlign = textAlign,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = (lyricsTextSize * 0.3f).dp),
                            )
                        }
                    }
                }
            }

            // Bottom spacer for overscroll
            item {
                Spacer(modifier = Modifier.height(300.dp))
            }
        }

        if (isManualScrolling && isSynced) {
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    isManualScrolling = false
                    scope.launch {
                        val viewportHeight = listState.layoutInfo.viewportSize.height
                        listState.animateScrollToItem(
                            index = currentLineIndex,
                            scrollOffset = -(viewportHeight * 0.35f).toInt(),
                        )
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = "Resume",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (isSelectionModeActive) {
            mediaMetadata?.let { metadata ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.3f),
                                        shape = CircleShape,
                                    ).clickable {
                                        isSelectionModeActive = false
                                        selectedIndices.clear()
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.close),
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Row(
                            modifier =
                                Modifier
                                    .background(
                                        color =
                                            if (selectedIndices.isNotEmpty()) {
                                                Color.White.copy(alpha = 0.9f)
                                            } else {
                                                Color.White.copy(alpha = 0.5f)
                                            },
                                        shape = RoundedCornerShape(24.dp),
                                    ).clickable(enabled = selectedIndices.isNotEmpty()) {
                                        if (selectedIndices.isNotEmpty()) {
                                            val sortedIndices = selectedIndices.sorted()
                                            val selectedLyricsText =
                                                sortedIndices
                                                    .mapNotNull { entriesWithWords.getOrNull(it)?.text }
                                                    .joinToString("\n")

                                            if (selectedLyricsText.isNotBlank()) {
                                                shareDialogData =
                                                    Triple(
                                                        selectedLyricsText,
                                                        metadata.title ?: "",
                                                        metadata.artists.joinToString { it.name },
                                                    )
                                                showShareDialog = true
                                            }
                                            isSelectionModeActive = false
                                            selectedIndices.clear()
                                        }
                                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.share),
                                contentDescription = stringResource(R.string.share_selected),
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.share),
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        BasicAlertDialog(onDismissRequest = { showShareDialog = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                modifier =
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth(0.85f),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.share_lyrics),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    shareLyricsAsText(
                                        context = context,
                                        payload = LyricsSharePayload(lyricsText, songTitle, artists),
                                        songId = mediaMetadata?.id,
                                    )
                                    showShareDialog = false
                                }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_text),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    shareDialogData = Triple(lyricsText, songTitle, artists)
                                    showShareImageDialog = true
                                    showShareDialog = false
                                }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_image),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier =
                                Modifier
                                    .clickable { showShareDialog = false }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (showShareImageDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        LyricsShareImageDialog(
            mediaMetadata = mediaMetadata,
            payload = LyricsSharePayload(lyricsText, songTitle, artists),
            onDismissRequest = { showShareImageDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineV2(
    words: List<WordTimestamp>,
    isActive: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    currentPositionProvider: () -> Long,
    textColor: Color,
    inactiveAlpha: Float,
    baseFontSize: Float,
    isLineAllBackground: Boolean,
    textAlign: TextAlign,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
    bounceFactor: Float,
    glowFactor: Float,
    fillTransitionWidth: Float,
    softReveal: Boolean = true,
    emphasizeEnabled: Boolean = false,
) {
    val arrangement =
        when (textAlign) {
            TextAlign.Center -> Arrangement.Center
            TextAlign.End -> Arrangement.End
            else -> Arrangement.Start
        }

    // Split words into main and background
    val mainWords = words.filter { !it.isBackground }
    val bgWords = words.filter { it.isBackground }

    val isNearActive = isActive || distanceFromActive <= 1
    val effectivePositionMs =
        if (isNearActive) {
            currentPositionProvider()
        } else if (isPast) {
            Long.MAX_VALUE
        } else {
            0L
        }

    // 1. Render main words First (if any)
    if (mainWords.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = arrangement,
        ) {
            mainWords.forEachIndexed { wordIndex, word ->
                if (word.text == " ") {
                    Text(
                        text = " ",
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontSize = if (isLineAllBackground) (baseFontSize * 0.82f).sp else baseFontSize.sp,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                            ),
                        color = Color.Transparent,
                    )
                    return@forEachIndexed
                }
                if (word.text == "\n") {
                    Spacer(modifier = Modifier.fillMaxWidth())
                    return@forEachIndexed
                }

                AnimatedWordV2(
                    word = word,
                    wordIndex = wordIndex,
                    isLineActive = isActive,
                    isLinePast = isPast,
                    currentPositionMs = effectivePositionMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                    fontSize = if (isLineAllBackground) baseFontSize * 0.82f else baseFontSize,
                    isBackground = isLineAllBackground,
                    lyricsFontFamily = lyricsFontFamily,
                    isRtl = isRtl,
                    bounceFactor = bounceFactor,
                    glowFactor = glowFactor,
                    fillTransitionWidth = fillTransitionWidth,
                    softReveal = softReveal,
                    emphasizeLastWord = emphasizeEnabled && isActive && wordIndex == mainWords.lastIndex && mainWords.size > 1,
                )
            }
        }
    }

    // 2. Render background words explicitly on a NEW line, noticeably smaller
    if (bgWords.isNotEmpty()) {
        val spacerHeight = if (mainWords.isNotEmpty()) 4.dp else 0.dp
        if (mainWords.isNotEmpty()) Spacer(modifier = Modifier.height(spacerHeight))

        FlowRow(
            modifier = Modifier.fillMaxWidth().alpha(0.85f), // Slightly dimmer overall
            horizontalArrangement = arrangement,
        ) {
            bgWords.forEachIndexed { wordIndex, word ->
                if (word.text == " ") {
                    Text(
                        text = " ",
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontSize = (baseFontSize * 0.65f).sp,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                            ),
                        color = Color.Transparent,
                    )
                    return@forEachIndexed
                }

                AnimatedWordV2(
                    word = word,
                    wordIndex = wordIndex + mainWords.size,
                    isLineActive = isActive,
                    isLinePast = isPast,
                    currentPositionMs = effectivePositionMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                    fontSize = baseFontSize * 0.65f, // ~65% size of main text
                    isBackground = true, // Force dimmer styling inside AnimatedWordV2
                    lyricsFontFamily = lyricsFontFamily,
                    isRtl = isRtl,
                    bounceFactor = bounceFactor,
                    glowFactor = glowFactor,
                    fillTransitionWidth = fillTransitionWidth,
                    softReveal = softReveal,
                )
            }
        }
    }
}

@Composable
private fun AnimatedWordV2(
    word: WordTimestamp,
    wordIndex: Int,
    isLineActive: Boolean,
    isLinePast: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    fontSize: Float,
    isBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
    bounceFactor: Float,
    glowFactor: Float,
    fillTransitionWidth: Float,
    softReveal: Boolean = true,
    emphasizeLastWord: Boolean = false,
) {
    val wordStartMs = (word.startTime * 1000).toLong()
    val wordEndMs = (word.endTime * 1000).toLong()
    val wordDuration = (wordEndMs - wordStartMs).coerceAtLeast(1L)

    val isWordComplete = currentPositionMs >= wordEndMs
    val isWordActive = currentPositionMs in wordStartMs until wordEndMs

    // ── Sweep progress: Animatable-driven for robustness ── Deriving progress directly from
    // currentPositionMs breaks for very short words (< ~50ms): the 16ms position poll can skip
    // the entire isWordActive window, making the word jump from 0% to 100% with no
    // letter-by-letter sweep.
    val sweepAnimatable = remember(word) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(isWordActive, isWordComplete, wordStartMs, wordEndMs) {
        when {
            isWordComplete && sweepAnimatable.value < 1f -> {
                // Snap to 1 if we missed the active window entirely (very
                // short word skipped between polls). Use a quick tween
                // instead of snapTo so there's at least a flicker of motion.
                sweepAnimatable.animateTo(
                    1f,
                    androidx.compose.animation.core.tween(
                        durationMillis = 80,
                        easing = androidx.compose.animation.core.LinearEasing,
                    ),
                )
            }
            isWordActive -> {
                // Animate from current value to 1 over the remaining word
                // duration, but at least MIN_SWEEP_MS so short words still
                // get a visible sweep.
                val remainingMs = (wordEndMs - currentPositionMs).coerceAtLeast(1L)
                val animDurationMs = maxOf(remainingMs, MIN_SWEEP_MS)
                sweepAnimatable.animateTo(
                    1f,
                    androidx.compose.animation.core.tween(
                        durationMillis = animDurationMs.toInt().coerceAtLeast(1),
                        easing = androidx.compose.animation.core.LinearEasing,
                    ),
                )
            }
            else -> {
                // Word not yet started — reset to 0 so the next active
                // window begins fresh.
                sweepAnimatable.snapTo(0f)
            }
        }
    }
    val progress = if (isWordComplete) 1f else sweepAnimatable.value

    // Subtle scale up peaking halfway through the word. Exact timing sync!
    val sinProgress = kotlin.math.sin(progress * kotlin.math.PI).toFloat()
    val wordScale = 1f + (0.015f * bounceFactor * sinProgress)

    // Float is only applied when the word is actively sung, making it pop from the line.
    // We use animateFloatAsState so that when it finishes (and drops to 0f),
    // it smoothly decays back into place rather than a harsh mathematical snap.
    val targetFloat = if (isWordActive) -4f * bounceFactor * sinProgress else 0f
    val floatOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetFloat,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = if (isWordActive) 50 else 350,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        label = "v2FloatOffset",
    )

    // "lines and words that are done animating shouldnt continue to glow"
    // Make glow build up faster: reach max intensity at 50% progress
    val glowProgress = (progress * 2f).coerceAtMost(1f)
    val glowAlpha = if (isWordActive) glowProgress * 0.45f * glowFactor else 0f
    val glowRadius = if (isWordActive) glowProgress * 12f * glowFactor else 0f

    val actualFontSize = if (isBackground) fontSize * 0.85f else fontSize
    val fontWeight = if (isLineActive || isLinePast) FontWeight.ExtraBold else FontWeight.SemiBold
    val glowPadding = 10.dp

    val baseHeadlineStyle = MaterialTheme.typography.headlineMedium
    val baseTextStyle =
        remember(baseHeadlineStyle, actualFontSize, fontWeight, lyricsFontFamily) {
            baseHeadlineStyle.copy(
                fontSize = actualFontSize.sp,
                fontWeight = fontWeight,
                fontStyle = FontStyle.Normal,
                lineHeight = (actualFontSize * 1.35f).sp,
                fontFamily = lyricsFontFamily ?: baseHeadlineStyle.fontFamily,
            )
        }

    // listOf(Color.Transparent, Color.Black) was being allocated every frame for every active
    // word inside drawWithContent. Precompute the two immutable color lists (LTR + RTL) once.
    val sweepColors =
        if (isRtl) {
            remember { listOf(Color.Transparent, Color.Black) }
        } else {
            remember { listOf(Color.Black, Color.Transparent) }
        }

    Box(
        modifier =
            Modifier
                .layout { measurable, constraints ->
                    val glowPaddingPx = glowPadding.roundToPx()
                    val looseConstraints =
                        constraints.copy(
                            minWidth = 0,
                            maxWidth = constraints.maxWidth,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                    val placeable = measurable.measure(looseConstraints)

                    val coreWidth = (placeable.width - glowPaddingPx * 2).coerceAtLeast(0)
                    val coreHeight = (placeable.height - glowPaddingPx * 2).coerceAtLeast(0)

                    layout(coreWidth, coreHeight) {
                        placeable.place(-glowPaddingPx, -glowPaddingPx)
                    }
                }.graphicsLayer {
                    clip = false
                    translationY = floatOffset * density
                    scaleX = wordScale
                    scaleY = wordScale
                },
    ) {
        // Layer 1: Base text (always dimmed)
        Text(
            text = word.text,
            style = baseTextStyle,
            color = textColor.copy(alpha = if (isBackground) inactiveAlpha * 0.7f else inactiveAlpha),
            modifier = Modifier.padding(glowPadding),
        )

        // Layer 2: Filled overlay with liquid sweep mask + glow
        if (isWordComplete || isWordActive || isLinePast) {
            // Only rebuild the overlay style (with shadow) when glow is actually visible —
            // avoids allocating a Shadow + copying the style on every frame for completed/past words.
            val overlayTextStyle =
                if (glowAlpha > 0f) {
                    remember(baseTextStyle, glowAlpha, glowRadius, textColor) {
                        baseTextStyle.copy(
                            shadow =
                                Shadow(
                                    color = textColor.copy(alpha = glowAlpha),
                                    offset = Offset.Zero,
                                    blurRadius = glowRadius.coerceAtLeast(1f),
                                ),
                        )
                    }
                } else {
                    baseTextStyle
                }
            if (emphasizeLastWord && (isWordActive || isWordComplete) && !isLinePast) {
                // Per-character emphasize overlay (see LyricsEmphasize.kt): same
                // dim base + lit overlay structure and the same sliding reveal
                // mask, but the sung overlay is split char-by-char with a
                // staggered glow+float bump. OFF (default) renders today's
                // single whole-word overlay below, untouched.
                EmphasizedWordOverlay(
                    word = word,
                    baseTextStyle = baseTextStyle,
                    textColor = textColor,
                    litAlpha = if (isBackground) 0.75f else 1f,
                    fontSizeSp = actualFontSize,
                    isBackground = isBackground,
                    isWordActive = isWordActive,
                    isWordComplete = isWordComplete,
                    bounceFactor = bounceFactor,
                    glowFactor = glowFactor,
                    modifier =
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .then(
                                if (softReveal && isWordActive && !isWordComplete) {
                                    val sweepProgress = progress
                                    Modifier.slidingSoftReveal(
                                        progressProvider = { sweepProgress },
                                        edgeWidth = fillTransitionWidth.dp,
                                        isRtl = isRtl,
                                        enabled = true,
                                    )
                                } else {
                                    Modifier
                                },
                            ).padding(glowPadding),
                )
            } else {
            Text(
                text = word.text,
                style = overlayTextStyle,
                color =
                    textColor.copy(
                        alpha = if (isBackground) 0.75f else 1f,
                    ),
                modifier =
                    if (isWordActive && !isWordComplete) {
                        // Soft reveal ON: upstream-style sliding fade window (see
                        // LyricsWordReveal.kt). OFF: today's exact hard-edge mask.
                        val sweepProgress = progress
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .then(
                                if (softReveal) {
                                    Modifier.slidingSoftReveal(
                                        progressProvider = { sweepProgress },
                                        edgeWidth = fillTransitionWidth.dp,
                                        isRtl = isRtl,
                                        enabled = true,
                                    )
                                } else {
                                    Modifier.drawWithContent {
                                        drawContent()
                                        val edgeWidth = fillTransitionWidth.dp.toPx()
                                        val center =
                                            if (isRtl) {
                                                size.width - ((size.width + edgeWidth * 2) * sweepProgress - edgeWidth)
                                            } else {
                                                (size.width + edgeWidth * 2) * sweepProgress - edgeWidth
                                            }
                                        drawRect(
                                            brush =
                                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                    colors = sweepColors,
                                                    startX = center - edgeWidth,
                                                    endX = center + edgeWidth,
                                                ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                },
                            ).padding(glowPadding)
                    } else {
                        Modifier.padding(glowPadding)
                    },
            )
            }
        }
    }
}

// Spotify style: no glow/bounce — motion lives in the pill fill sweep and the
// line rise/fade transitions instead.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineSpotify(
    words: List<WordTimestamp>,
    isActive: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    currentPositionProvider: () -> Long,
    textColor: Color,
    inactiveAlpha: Float,
    baseFontSize: Float,
    isLineAllBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
    softReveal: Boolean = true,
) {
    val arrangement =
        when (textAlign) {
            TextAlign.Center -> Arrangement.Center
            TextAlign.End -> Arrangement.End
            else -> Arrangement.Start
        }

    val mainWords = words.filter { !it.isBackground }
    val bgWords = words.filter { it.isBackground }

    val isNearActive = isActive || distanceFromActive <= 1
    val effectivePositionMs =
        if (isNearActive) {
            currentPositionProvider()
        } else if (isPast) {
            Long.MAX_VALUE
        } else {
            0L
        }

    // Pills only on the active line; non-active lines keep the same word
    // geometry (padding included) so nothing reflows when a line activates.
    val pillVisible = isActive && !isPast

    if (mainWords.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = arrangement,
        ) {
            mainWords.forEachIndexed { _, word ->
                if (word.text == " ") {
                    Text(
                        text = " ",
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontSize = if (isLineAllBackground) (baseFontSize * 0.82f).sp else baseFontSize.sp,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                            ),
                        color = Color.Transparent,
                    )
                    return@forEachIndexed
                }
                if (word.text == "\n") {
                    Spacer(modifier = Modifier.fillMaxWidth())
                    return@forEachIndexed
                }
                SpotifyWord(
                    word = word,
                    isLineActive = isActive,
                    pillVisible = pillVisible,
                    currentPositionMs = effectivePositionMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                    fontSize = if (isLineAllBackground) baseFontSize * 0.82f else baseFontSize,
                    lyricsFontFamily = lyricsFontFamily,
                    isRtl = isRtl,
                    softReveal = softReveal,
                )
            }
        }
    }

    // Background vocals on their own line, noticeably smaller (mirrors LyricsLineV2).
    if (bgWords.isNotEmpty()) {
        if (mainWords.isNotEmpty()) Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth().alpha(0.85f),
            horizontalArrangement = arrangement,
        ) {
            bgWords.forEachIndexed { _, word ->
                if (word.text == " ") {
                    Text(
                        text = " ",
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontSize = (baseFontSize * 0.65f).sp,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                            ),
                        color = Color.Transparent,
                    )
                    return@forEachIndexed
                }
                if (word.text == "\n") {
                    Spacer(modifier = Modifier.fillMaxWidth())
                    return@forEachIndexed
                }
                SpotifyWord(
                    word = word,
                    isLineActive = isActive,
                    pillVisible = pillVisible,
                    currentPositionMs = effectivePositionMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                    fontSize = baseFontSize * 0.65f,
                    isBackground = true,
                    lyricsFontFamily = lyricsFontFamily,
                    isRtl = isRtl,
                    softReveal = softReveal,
                )
            }
        }
    }
}

@Composable
internal fun SpotifyWord(
    word: WordTimestamp,
    isLineActive: Boolean,
    pillVisible: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    fontSize: Float,
    isBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
    softReveal: Boolean = true,
) {
    val wordStartMs = (word.startTime * 1000).toLong()
    val wordEndMs = (word.endTime * 1000).toLong()
    val isWordComplete = currentPositionMs >= wordEndMs
    val isWordActive = currentPositionMs in wordStartMs until wordEndMs

    // Same Animatable-driven sweep as AnimatedWordV2: guarantees a visible
    // fill even for words shorter than the position-poll interval.
    val sweepAnimatable = remember(word) { Animatable(0f) }
    LaunchedEffect(isWordActive, isWordComplete, wordStartMs, wordEndMs) {
        when {
            isWordComplete && sweepAnimatable.value < 1f -> {
                sweepAnimatable.animateTo(
                    1f,
                    tween(durationMillis = 80, easing = LinearEasing),
                )
            }

            isWordActive -> {
                val remainingMs = (wordEndMs - currentPositionMs).coerceAtLeast(1L)
                sweepAnimatable.animateTo(
                    1f,
                    tween(
                        durationMillis = maxOf(remainingMs, MIN_SWEEP_MS).toInt().coerceAtLeast(1),
                        easing = LinearEasing,
                    ),
                )
            }

            else -> {
                sweepAnimatable.snapTo(0f)
            }
        }
    }
    val progress = if (isWordComplete) 1f else sweepAnimatable.value

    val pillRadius = 6.dp
    val pillPaddingHorizontal = 7.dp
    val pillPaddingVertical = 1.dp
    val interWordGap = 2.dp

    val baseHeadlineStyle = MaterialTheme.typography.headlineMedium
    val textStyle =
        remember(baseHeadlineStyle, fontSize, lyricsFontFamily) {
            baseHeadlineStyle.copy(
                fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Normal,
                lineHeight = (fontSize * 1.35f).sp,
                fontFamily = lyricsFontFamily ?: baseHeadlineStyle.fontFamily,
            )
        }

    Box(
        modifier =
            Modifier
                .padding(horizontal = interWordGap)
                .drawBehind {
                    if (!pillVisible) return@drawBehind
                    val r = pillRadius.toPx()
                    // resting slot behind the whole word
                    drawRoundRect(
                        color = textColor.copy(alpha = 0.15f),
                        cornerRadius = CornerRadius(r),
                    )
                    // sung fill, sweeps LTR (or RTL) with the word timing
                    val pillWidth = size.width
                    val fillPx = pillWidth * progress
                    if (fillPx > 0f) {
                        val left = if (isRtl) pillWidth - fillPx else 0f
                        clipRect(left, 0f, left + fillPx, size.height) {
                            drawRoundRect(
                                color = textColor.copy(alpha = 0.32f),
                                cornerRadius = CornerRadius(r),
                            )
                        }
                    }
                }
                .padding(horizontal = pillPaddingHorizontal, vertical = pillPaddingVertical),
    ) {
        // Layer 1: dim unsung text (the resting look for the whole line)
        Text(
            text = word.text,
            style = textStyle,
            color = textColor.copy(alpha = if (isBackground) inactiveAlpha * 0.75f else (inactiveAlpha + 0.1f).coerceAtMost(1f)),
        )

        // Layer 2: bright sung text, clipped to the same sweep as the pill.
        // Only composed while the word is animating or done on the active line.
        if (pillVisible && (isWordComplete || isWordActive) && isLineActive) {
            Text(
                text = word.text,
                style = textStyle,
                color = textColor.copy(alpha = if (isBackground) 0.75f else 1f),
                modifier =
                    if (isWordActive && !isWordComplete) {
                        // Same upstream sliding fade idea as AnimatedWordV2 (see
                        // LyricsWordReveal.kt): keep the pill edge position, but
                        // soften the alpha step into a 4dp fade band. The pill
                        // fill itself stays hard-edged, matching upstream where
                        // the pill sweeps solid and only the text mask feathers.
                        val spotifyProgress = progress
                        val spotifyReveal = softReveal
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .then(
                                if (spotifyReveal) {
                                    Modifier.slidingSoftReveal(
                                        progressProvider = { spotifyProgress },
                                        edgeWidth = 4.dp,
                                        isRtl = isRtl,
                                        enabled = true,
                                    )
                                } else {
                                    Modifier.drawWithContent {
                                        val padHpx = pillPaddingHorizontal.toPx()
                                        val pillWidth = size.width + padHpx * 2f
                                        val fillPx = pillWidth * spotifyProgress
                                        val pillLeft = if (isRtl) pillWidth - fillPx else 0f
                                        val rawLeft = pillLeft - padHpx
                                        val solidFraction = (rawLeft / size.width).coerceIn(0f, 1f)
                                        drawContent()
                                        drawRect(
                                            brush =
                                                if (isRtl) {
                                                    Brush.horizontalGradient(
                                                        0f to Color.Transparent,
                                                        solidFraction.coerceAtMost(1f) to Color.Transparent,
                                                        solidFraction.coerceAtLeast(0f) to Color.Black,
                                                        1f to Color.Black,
                                                    )
                                                } else {
                                                    Brush.horizontalGradient(
                                                        0f to Color.Black,
                                                        solidFraction.coerceIn(0f, 1f) to Color.Black,
                                                        solidFraction.coerceAtLeast(0f) to Color.Transparent,
                                                        1f to Color.Transparent,
                                                    )
                                                },
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                },
                            )
                    } else {
                        Modifier
                    },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineLrcBounce(
    text: String,
    isActive: Boolean,
    textColor: Color,
    fontSize: Float,
    lineSpacing: Float,
    isAllBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    textAlign: TextAlign,
    bounceFactor: Float,
) {
    val words = remember(text) { text.toLyricsWrappingUnits() }
    val effectiveFontSize = if (isAllBackground) fontSize * 0.82f else fontSize
    val fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold
    val fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal
    val scaleAnimatables = remember(words.size) { List(words.size) { Animatable(1f) } }
    val floatAnimatables = remember(words.size) { List(words.size) { Animatable(0f) } }

    LaunchedEffect(isActive) {
        if (!isActive || bounceFactor == 0f) return@LaunchedEffect
        words.indices.forEach { i ->
            launch {
                delay(i * 40L)
                try {
                    scaleAnimatables[i].animateTo(
                        targetValue = 1f + 0.045f * bounceFactor,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessHigh,
                            ),
                    )
                    scaleAnimatables[i].animateTo(
                        targetValue = 1f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    )
                } finally {
                    withContext(NonCancellable) { scaleAnimatables[i].snapTo(1f) }
                }
            }
            launch {
                delay(i * 40L)
                try {
                    floatAnimatables[i].animateTo(
                        targetValue = -5f * bounceFactor,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessHigh,
                            ),
                    )
                    floatAnimatables[i].animateTo(
                        targetValue = 0f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    )
                } finally {
                    withContext(NonCancellable) { floatAnimatables[i].snapTo(0f) }
                }
            }
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            when (textAlign) {
                TextAlign.Center -> Arrangement.Center
                TextAlign.End -> Arrangement.End
                else -> Arrangement.Start
            },
    ) {
        words.forEachIndexed { i, word ->
            LrcBouncingWord(
                text = word,
                scaleAnim = scaleAnimatables[i],
                floatAnim = floatAnimatables[i],
                color = textColor,
                fontSize = effectiveFontSize,
                lineSpacing = lineSpacing,
                fontWeight = fontWeight,
                fontStyle = fontStyle,
                lyricsFontFamily = lyricsFontFamily,
            )
        }
    }
}

@Composable
private fun LrcBouncingWord(
    text: String,
    scaleAnim: Animatable<Float, AnimationVector1D>,
    floatAnim: Animatable<Float, AnimationVector1D>,
    color: Color,
    fontSize: Float,
    lineSpacing: Float,
    fontWeight: FontWeight,
    fontStyle: FontStyle,
    lyricsFontFamily: FontFamily?,
) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.headlineMedium.copy(
                fontSize = fontSize.sp,
                fontWeight = fontWeight,
                fontStyle = fontStyle,
                lineHeight = (fontSize * lineSpacing).sp,
                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
            ),
        color = color,
        modifier =
            Modifier.graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                translationY = floatAnim.value
            },
    )
}

/**
 * Extracted wrapper for the instrumental-break row.
 *
 * Reads [playbackPositionProvider] internally so the parent `itemsIndexed` lambda doesn't
 * subscribe to the 16 ms position tick. Without this extraction, every visible item (word-synced
 * lines included) would recompose on every position update because Compose tracks snapshot-state
 * reads at the composable-call scope, not per-control-flow branch.
 */
@Composable
private fun InstrumentalBreakRow(
    item: LyricsEntry,
    index: Int,
    hasHeadEntry: Boolean,
    currentLineIndex: Int,
    isManualScrolling: Boolean,
    lyricsLineSpacing: Float,
    lyricsLineBlur: Boolean,
    lyricsClick: Boolean,
    textColor: Color,
    inactiveAlpha: Float,
    playbackPositionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    interludeDots: Boolean = true,
) {
    val startTimeMs = item.time
    val endTimeMs = item.time + item.durationMs
    val playbackPositionMs = playbackPositionProvider()
    val isActive = playbackPositionMs in startTimeMs until endTimeMs
    val distanceFromActive = abs(index - currentLineIndex)
    val instrAlpha =
        when {
            isActive -> 1f
            isManualScrolling -> {
                when {
                    distanceFromActive == 1 -> 0.72f
                    distanceFromActive == 2 -> 0.56f
                    distanceFromActive == 3 -> 0.40f
                    else -> 0.28f
                }
            }
            distanceFromActive == 1 -> 0.52f
            distanceFromActive == 2 -> 0.30f
            distanceFromActive == 3 -> 0.18f
            else -> inactiveAlpha
        }
    val animatedInstrAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = instrAlpha,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = if (isActive) 330 else 500,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        label = "v2InstrumentalAlpha",
    )
    val animatedInstrScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isActive) 1f else 0.95f,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = 166,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        label = "v2InstrumentalScale",
    )
    val targetInstrBlur =
        when {
            isActive || isManualScrolling -> 0f
            distanceFromActive == 1 -> 2f
            distanceFromActive == 2 -> 5f
            else -> 12f
        }
    val animatedInstrBlur by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetInstrBlur,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = 300,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        label = "v2InstrumentalBlur",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top =
                        if (index == 0 || (index == 1 && hasHeadEntry)) {
                            0.dp
                        } else {
                            (lyricsLineSpacing * 8).dp
                        },
                    bottom = (lyricsLineSpacing * 8).dp,
                ).then(
                    if (lyricsLineBlur) {
                        Modifier.blur(
                            radiusX = animatedInstrBlur.dp,
                            radiusY = animatedInstrBlur.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        )
                    } else {
                        Modifier
                    },
                ).graphicsLayer {
                    scaleX = animatedInstrScale
                    scaleY = animatedInstrScale
                    alpha = animatedInstrAlpha
                }.then(
                    if (lyricsClick && item.time > 0) {
                        Modifier.clickable { onSeek(item.time) }
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (interludeDots && isActive) {
            // Breathing dots beside the note while the break plays (see
            // LyricsInterludeDots.kt). OFF renders today's note-only row.
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                InstrumentalBreakItem(
                    durationMs = item.durationMs,
                    currentPositionMs = playbackPositionMs,
                    startTimeMs = startTimeMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                )
                InterludeDots(textColor = textColor)
            }
        } else {
        InstrumentalBreakItem(
            durationMs = item.durationMs,
            currentPositionMs = playbackPositionMs,
            startTimeMs = startTimeMs,
            textColor = textColor,
            inactiveAlpha = inactiveAlpha,
        )
        }
    }
}

@Composable
private fun InstrumentalBreakItem(
    durationMs: Long,
    currentPositionMs: Long,
    startTimeMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
) {
    val musicNotePath =
        remember {
            androidx.compose.ui.graphics.vector
                .PathParser()
                .parsePathString(
                    "M10 21q-1.65 0-2.825-1.175T6 17t1.175-2.825T10 13q.575 0 1.063.138t.937.412V4" +
                        "q0-.425.288-.712T13 3h4q.425 0 .713.288T18 4v2q0 .425-.288.713T17 7h-3v10" +
                        "q0 1.65-1.175 2.825T10 21",
                ).toPath()
        }

    val targetFillFraction =
        when {
            durationMs <= 0L -> {
                0f
            }

            currentPositionMs <= startTimeMs -> {
                0f
            }

            currentPositionMs >= startTimeMs + durationMs -> {
                1f
            }

            else -> {
                ((currentPositionMs - startTimeMs).toDouble() / durationMs.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
            }
        }
    val fillFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetFillFraction,
        animationSpec =
            spring(
                stiffness = Spring.StiffnessHigh,
                dampingRatio = Spring.DampingRatioNoBouncy,
            ),
        label = "instrumentalFill",
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.size(48.dp)) {
        val scaleX = size.width / 24f
        val scaleY = size.height / 24f
        val pivot = androidx.compose.ui.geometry.Offset.Zero

        withTransform(
            transformBlock = { scale(scaleX, scaleY, pivot) },
        ) {
            drawPath(path = musicNotePath, color = textColor.copy(alpha = inactiveAlpha))
        }

        if (fillFraction > 0f) {
            val clipTop = size.height * (1f - fillFraction)
            clipRect(
                left = 0f,
                top = clipTop,
                right = size.width,
                bottom = size.height,
            ) {
                withTransform(
                    transformBlock = { scale(scaleX, scaleY, pivot) },
                ) {
                    drawPath(path = musicNotePath, color = textColor)
                }
            }
        }
    }
}
