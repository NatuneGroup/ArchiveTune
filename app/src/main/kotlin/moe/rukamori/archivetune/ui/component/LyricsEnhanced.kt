/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.ui.component

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.player.LocalLyricsScrollListener
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineBlurKey
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.lyrics.LyricsUtils.hasTrueWordSync
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedRomanizedTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedRomanizedWordsForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.romanizeLyricsLine
import moe.rukamori.archivetune.lyrics.LyricsUtils.romanizeWordsForLine
import moe.rukamori.archivetune.lyrics.LyricsUtils.shouldRomanizeLyricsLine
import moe.rukamori.archivetune.lyrics.WordTimestamp
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.theme.rememberArchiveTuneLyricsFontFamily
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val LRC_LEAD_MS = 300L
private const val TTML_LEAD_MS = 0L
private const val LYRIC_VISUAL_TUNING_OFFSET_MS = 150L
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L
private const val MANUAL_SCROLL_DEBOUNCE_MS = 50L
private const val LYRIC_FOCUS_TOP_ANCHOR_RATIO = 0.08f
// Guards define the "close enough" zone inside which the custom scroll is skipped.
// The top guard MUST be smaller than LYRIC_FOCUS_TOP_ANCHOR_RATIO so the resting
// position (8%) is inside the zone — otherwise the custom scroll would re-fire on
// every line change to "fix" a position that's already correct.
private const val LYRIC_FOCUS_TOP_GUARD_RATIO = 0.04f
private const val LYRIC_FOCUS_BOTTOM_GUARD_RATIO = 0.30f
private const val LYRIC_FOCUS_MIN_SCROLL_PX = 6
// Auto-scroll deltas up to this fraction of the viewport height snap instantly
// (no tween). A typical line-advance scroll moves the focus point from ~30%
// (bottom guard) to ~8% (anchor) = 22% of viewport. Setting the threshold to
// 40% covers all normal-playback line advances AND small-to-medium seeks,
// while larger jumps (return from manual scroll, large seeks) still animate.
// This eliminates the per-frame LazyColumn re-layout cost of the 280ms tween
// for the common case — which was the single biggest source of "auto-scroll
// lag" because the tween was competing with the 60Hz karaoke syllable sweep
// for frame budget on every line change.
private const val LYRIC_FOCUS_INSTANT_SCROLL_RATIO = 0.40f
private const val LYRIC_FOCUS_ANIMATED_DISTANCE = 4
private const val SMOOTH_PLAYBACK_MAX_FORWARD_DRIFT_MS = 80L
private const val SMOOTH_PLAYBACK_MAX_BACKWARD_DRIFT_MS = 180L
private const val SMOOTH_PLAYBACK_DRIFT_CORRECTION = 0.55f
// Reduced from 520ms to 280ms. The previous 520ms tween was long enough that
// it was STILL running when the next line change fired (especially on tracks
// with short lines), causing collectLatest to cancel + restart the animation
// repeatedly — visible as stuttery, non-monotonic scroll motion. 280ms is
// short enough to settle before the next line change in almost all songs,
// while still looking smooth rather than instant.
private const val LYRIC_FOCUS_SCROLL_DURATION_MS = 280
// The lines stay transparent until the active one has been placed, then fade in
// over this long. See `awaitingFirstFocus`.
private const val LYRIC_FIRST_FOCUS_FADE_MS = 200
// Hard ceiling on how long the lines may stay hidden waiting for that placement.
private const val LYRIC_FIRST_FOCUS_TIMEOUT_MS = 400L
// A line-synced "syllable" is only a peg to hang a romanisation on, so it gets the shortest window
// the library will accept rather than a share of the line's duration — see
// buildWrappingKaraokeSyllables.
private const val MIN_KARAOKE_SYLLABLE_DURATION_MS = 1

// KaraokeLyricsView resolves the focused line as "the line whose [start, end) contains the
// position", and falls back to the *next* line whenever the position lands between two windows.
// These constants exist to make sure that fallback only ever fires on a genuine instrumental
// break; see the long comment in buildSyncedLyrics.
//
// How long a line may keep focus after its own timestamp before it is allowed to hand over to a
// breathing-dots interlude. Below this the line simply holds until the next one starts.
private const val LINE_SYNCED_MAX_FOCUS_HOLD_MS = 5_000L
// Mirrors the library's own interlude threshold: it draws the breathing dots only when the silence
// between two lines is strictly longer than this.
private const val LINE_SYNCED_INTERLUDE_MIN_GAP_MS = 5_000L
// The last line has no successor to butt against, so give it a fixed window.
private const val LINE_SYNCED_TRAILING_LINE_DURATION_MS = 4_000L
// Minimum backward position jump (in ms) that we treat as a "reset" trigger —
// large enough to ride through ExoPlayer's normal position jitter (which is
// well under 200ms even on a stuttering device) and minor playback corrections,
// small enough to catch any real seek-backward or REPEAT_MODE_ONE wrap (which
// is typically a jump from near the end of the song back to 0, i.e. minutes).
private const val POSITION_RESET_BACKWARD_THRESHOLD_MS = 1000L

// How long the first build waits for the built-in romanisation pass before giving up and publishing
// without it. Romanising every line is local work (ICU tables, or one Kuromoji tokenize per line)
// and normally lands well inside this, so the very first build the karaoke view ever sees already
// carries its phonetics — which is what keeps KaraokeLyricsView from freezing the on-screen lines
// on a layout that has none. See [KaraokeBuild] for why that matters, and note the cost of missing
// the window is only that the shimmer holds a moment longer.
private const val ROMANIZATION_FIRST_BUILD_GRACE_MS = 700L

/** One published [SyncedLyrics] plus the identity of the romanisation baked into it. */
private data class KaraokeBuild(
    val lyrics: SyncedLyrics,
    val romanization: Map<Int, List<String?>>,
    val generation: Int,
)

/**
 * The part of a romanisation map that changes what is drawn: entries whose every value is
 * null/blank produce no phonetics at all, so a map full of them is indistinguishable from an empty
 * one and must not count as a change (otherwise a song with nothing romanisable would re-key the
 * karaoke view for a build that looks identical).
 */
private fun Map<Int, List<String?>>.renderedRomanization(): Map<Int, List<String?>> =
    filterValues { values -> values.any { !it.isNullOrBlank() } }

/**
 * Enhanced karaoke lyrics renderer.
 *
 * @param singleActiveLine when true, renders ONLY the active line cluster — the
 *   main karaoke line (word-timed sweep), its per-word phonetic (romanisation)
 *   and its translation — cross-fading between lines as playback advances.
 *   Designed for compact surfaces like the TikTok player strip, where previous
 *   and upcoming lines must not be visible and no line-list scrolling applies.
 */
@Composable
fun LyricsEnhanced(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    lyricsLineBlurOverride: Boolean? = null,
    spotifyStyle: Boolean = false,
    // Non-null when the caller is not a full screen. The SimpMusic style embeds this in a 300dp
    // card, where the user's full-screen size fits about four words in the box.
    textSizeOverride: Float? = null,
    singleActiveLine: Boolean = false,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val animationsDisabled = LocalAnimationsDisabled.current

    // collectAsStateWithLifecycle: pauses StateFlow collection when the
    // composable's lifecycle drops below STARTED (e.g. user navigates away
    // from the player, or the app goes to background). Without this, these
    // flows continue pushing values into state during background playback,
    // causing recompositions that compete with the karaoke frame loop for
    // main-thread time when the user returns. This is especially important
    // for `currentLyrics` below, which can emit during background lyrics
    // prefetch / AI translation completion.
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val playbackParameters by playerConnection.playbackParameters.collectAsStateWithLifecycle()

    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsTextSizePreference) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val lyricsTextSize = textSizeOverride ?: lyricsTextSizePreference
    // Per-line RenderEffect blur (see useBlurEffect below) is the single heaviest
    // per-frame cost in the karaoke view -- default it OFF so word-synced lyrics
    // are smooth out of the box; users who want the effect can re-enable it in
    // Settings > Lyrics.
    val (lyricsLineBlurPreference) = rememberPreference(LyricsLineBlurKey, defaultValue = false)
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

    // Apple Music style and all lyrics backgrounds always use white text so lyrics stay
    // readable on the dark blurred backdrop regardless of system theme.
    val textColor = textColorOverride ?: Color.White
    val lyricsLineBlur = lyricsLineBlurOverride ?: lyricsLineBlurPreference

    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedLineKeys = remember { mutableStateListOf<String>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var showShareImageDialog by remember { mutableStateOf(false) }

    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyrics =
        remember(currentLyrics, mediaMetadata?.id) {
            currentLyrics
                ?.takeIf { lyricsEntity -> lyricsEntity.id == mediaMetadata?.id }
                ?.lyrics
        }
    val showTranslations =
        remember(currentLyrics?.source) {
            currentLyrics?.source in setOf(LyricsEntity.Source.AI_TRANSLATION.value, LyricsEntity.Source.TRANSLATION.value)
        }

    // Restart tick: bumps when the SAME song restarts (auto-repeat, manual replay, seek-to-zero)
    // so the karaoke subtree tears down and re-animates from the beginning. Without this,
    // mediaId + lyrics text are unchanged on restart, so lyricsSessionKey stays the same and
    // all per-session Animatable instances retain their settled values — no intro animation replays.
    val playbackState by playerConnection.playbackState.collectAsState()
    var restartTick by remember { mutableIntStateOf(0) }
    var lastPlaybackState by remember { mutableStateOf(Player.STATE_READY) }
    LaunchedEffect(mediaMetadata?.id, playbackState) {
        if (playbackState == Player.STATE_READY &&
            lastPlaybackState == Player.STATE_ENDED &&
            player.currentPosition < 1_000L
        ) {
            restartTick++
        } else if (playbackState == Player.STATE_READY &&
            lastPlaybackState == Player.STATE_BUFFERING &&
            player.currentPosition < 500L &&
            restartTick > 0
        ) {
            // Manual replay from the queue / notification while the song was already playing.
            // Only count it as a restart if we've already started once (restartTick > 0)
            // to avoid firing on the very first load.
            restartTick++
        }
        lastPlaybackState = playbackState
    }
    val lyricsSessionKey =
        remember(mediaMetadata?.id, lyrics, restartTick) {
            Triple(mediaMetadata?.id.orEmpty(), lyrics, restartTick)
        }

    val isSynced = remember(lyrics) { lyrics != null && (isLineSyncedLrc(lyrics!!) || isTtml(lyrics!!)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics!!) }

    // Parsed off the composition thread. `null` means "not parsed yet" — the render `when` below
    // keeps the shimmer up on that state instead of falling through to "lyrics not found".
    //
    // The parse must stay off the composition thread: for word-synced TTML it is an XML parse
    // plus one object per syllable, twice (buildSyncedLyrics walks the result again). On the
    // composition thread that lands on the exact frame the Apple Music COVER->LYRICS morph starts
    // and stutters the switch to the lyrics page. Both run on Dispatchers.Default, the dispatcher
    // the romanization pass below already uses.
    var parsedEntries by remember(lyrics) { mutableStateOf<List<LyricsEntry>?>(null) }
    LaunchedEffect(lyrics) {
        val text = lyrics
        if (text == null || text == LYRICS_NOT_FOUND) {
            parsedEntries = emptyList()
            return@LaunchedEffect
        }
        parsedEntries =
            withContext(Dispatchers.Default) {
                when {
                    isTtml(text) -> parseTtml(text)
                    isLineSyncedLrc(text) -> parseLyrics(text)
                    else ->
                        text
                            .lines()
                            .filter { it.isNotBlank() }
                            .map { line -> LyricsEntry(time = -1L, text = line.trim()) }
                }
            }
    }
    val lyricsEntries: List<LyricsEntry> = parsedEntries.orEmpty()

    // Keyed on the raw lyrics text, not on lyricsEntries: the entries now arrive a beat after the
    // first composition, and re-keying on them would put a synchronous buildSyncedLyrics straight
    // back on the main thread the moment the parse lands. The effect below publishes the real
    // build from Default, so this only ever supplies the empty starting value.
    var karaokeBuild by remember(lyrics, isTtmlFormat) {
        mutableStateOf(KaraokeBuild(SyncedLyrics(emptyList()), emptyMap(), generation = 0))
    }
    val syncedLyrics = karaokeBuild.lyrics
    // Bumped alongside the lyrics object whenever a build replaces an already-visible one with
    // different romanisation; see KaraokeBuild for why the karaoke view has to be re-keyed on it.
    val karaokeGeneration = karaokeBuild.generation

    // Runs once per track instead of once per line (network + billed), so it can't hang off the
    // per-line pass below. Results arrive asynchronously through AiLyricsRomanization.results and are
    // folded into the same romanizationMap the built-in engines feed, which means everything
    // downstream — the wrapping-unit distribution, the karaoke phonetics, `showPhonetic` — is shared.
    val aiRomanizationSessionKey =
        remember(mediaMetadata?.id, lyrics) { AiLyricsRomanization.sessionKey(mediaMetadata?.id, lyrics) }
    val aiRomanizationResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    // Resolved by line text, not by index — this renderer parses without the head entry and without
    // instrumental breaks, LyricsV2 parses with both, and both derive the same session key. See
    // AiLyricsRomanization.Result.
    val aiRomanizedLines: List<String?> =
        remember(aiRomanizationResult, aiRomanizationSessionKey, aiRomanizationSettings.active, lyricsEntries) {
            if (!aiRomanizationSettings.active) {
                emptyList()
            } else {
                AiLyricsRomanization.linesFor(aiRomanizationSessionKey, lyricsEntries.map { it.text })
            }
        }
    LaunchedEffect(aiRomanizationSessionKey, lyricsEntries, aiRomanizationSettings) {
        if (!aiRomanizationSettings.active || !aiRomanizationSettings.auto) return@LaunchedEffect
        if (lyricsEntries.isEmpty()) return@LaunchedEffect
        AiLyricsRomanization.request(
            sessionKey = aiRomanizationSessionKey,
            lines = lyricsEntries.map { it.text },
            settings = aiRomanizationSettings,
        )
    }

    LaunchedEffect(lyricsEntries, romanizationPreferences, aiRomanizedLines) {
        // Everything below (scanning + romanizing every line, often one job per word for TTML)
        // must not inherit the main dispatcher — it stutters the karaoke animation on track
        // change; run the whole batch on Default and only publish the results back.
        withContext(Dispatchers.Default) {
        // Publishing new lyrics as state (instead of bumping a key that tears the whole karaoke
        // subtree down mid-playback) lets the view pick up a *translation* without a re-layout
        // hitch. Romanisation is not so lucky — the library pins each line's glyph layout to
        // whatever it measured the first time that line was composed — so a build that changes the
        // romanisation of lines already on screen has to carry a new generation with it. See
        // KaraokeBuild.
        fun publish(romanization: Map<Int, List<String?>>) {
            val previous = karaokeBuild
            val changesVisibleLines =
                previous.lyrics.lines.isNotEmpty() &&
                    romanization.renderedRomanization() != previous.romanization.renderedRomanization()
            karaokeBuild =
                KaraokeBuild(
                    lyrics = buildSyncedLyrics(lyricsEntries, romanization),
                    romanization = romanization,
                    generation = if (changesVisibleLines) previous.generation + 1 else previous.generation,
                )
        }

        val aiMap = aiRomanizationMap(lyricsEntries, aiRomanizedLines)

        val toRomanize: List<Pair<Int, LyricsEntry>> =
            if (!romanizationPreferences.isEnabled) {
                // Romanisation is off, or the AI engine owns it — `aiMap` is already everything
                // this build will ever have.
                emptyList()
            } else {
                lyricsEntries.mapIndexedNotNull { index, entry ->
                    val hasProviderRomanization =
                        providedRomanizedTextForEntry(entry, romanizationPreferences) != null
                    if (hasProviderRomanization || shouldRomanizeLyricsLine(entry.text, romanizationPreferences)) {
                        index to entry
                    } else {
                        null
                    }
                }
            }
        if (toRomanize.isEmpty()) {
            publish(aiMap)
            return@withContext
        }

        val romanization =
            async {
                val jobs =
                    toRomanize.map { (index, entry) ->
                        async {
                            val romanized: List<String?> =
                                try {
                                    if (entry.words != null) {
                                        val mainWordCount = entry.words!!.count { !it.isBackground }
                                        providedRomanizedWordsForEntry(entry, mainWordCount, romanizationPreferences)
                                            ?: romanizeWordsForLine(
                                                // Japanese: single-pass line tokenization (6x fewer
                                                // Kuromoji calls than per-word). Other languages:
                                                // per-word character-by-character (already cheap).
                                                words = entry.words!!.filter { !it.isBackground }.map { it.text },
                                                lineText = entry.text,
                                                preferences = romanizationPreferences,
                                            )
                                    } else {
                                        listOf(
                                            providedRomanizedTextForEntry(entry, romanizationPreferences)
                                                ?: romanizeLyricsLine(entry.text, romanizationPreferences),
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    reportException(e)
                                    if (entry.words != null) {
                                        List(entry.words!!.count { !it.isBackground }) { null }
                                    } else {
                                        listOf(null)
                                    }
                                }
                            index to romanized
                        }
                    }
                jobs.awaitAll().toMap()
            }

        // Why the un-romanised build is not published up front: KaraokeBuild describes the case of
        // a second build landing while lines are already on screen — those lines then keep their
        // phonetic-less layout for good. Waiting a beat for the local pass means the first build
        // the view ever sees already has the phonetics, so nothing has to be replaced at all —
        // and the shimmer is already up, so the wait costs no visible state.
        //
        // The timeout is what keeps that from becoming a stall: it does not cancel the pass (the
        // deferred belongs to the enclosing scope, not to withTimeoutOrNull), it only stops waiting
        // on it, and the two-build path below is then taken with the re-key that makes it correct.
        val quickRomanization = withTimeoutOrNull(ROMANIZATION_FIRST_BUILD_GRACE_MS) { romanization.await() }
        if (quickRomanization != null) {
            publish(quickRomanization)
            return@withContext
        }
        publish(aiMap)
        publish(romanization.await())
        }
    }

    val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS

    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)
    val latestLyricsSyncOffset = rememberUpdatedState(lyricsSyncOffset)
    val latestLeadMs = rememberUpdatedState(leadMs)
    val latestPlaybackSpeed = rememberUpdatedState(playbackParameters.speed)
    val playbackPositionMs =
        remember(player) {
            mutableLongStateOf(player.currentPosition.coerceAtLeast(0L))
        }
    val playbackSyncPosition: () -> Int =
        remember {
            {
                (
                    playbackPositionMs.longValue +
                        latestLyricsSyncOffset.value.toLong() +
                        latestLeadMs.value +
                        LYRIC_VISUAL_TUNING_OFFSET_MS
                ).coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
            }
        }
    val currentLineIndexState = remember { mutableIntStateOf(-1) }

    // Single-active-line mode (TikTok strip): pick the currently-sung line out of the
    // fully-built karaoke model. Metadata lines (header at start<0, footer at start>=1h) are
    // skipped so the strip only ever shows real lyrics; before the first line and after the
    // last one it renders nothing.
    val activeKaraokeLine =
        remember(syncedLyrics, currentLineIndexState.intValue, singleActiveLine) {
            if (!singleActiveLine) {
                null
            } else {
                val index = currentLineIndexState.intValue
                val line = syncedLyrics.lines.getOrNull(index)
                if (line != null && line.start >= 0 && line.start < 86_400_000) line else null
            }
        }

    // rememberUpdatedState so the playback loop sees the latest syncedLyrics (which can be
    // rebuilt when romanization finishes) without re-launching the loop and stuttering the
    // 60 Hz position interpolation.
    val latestSyncedLyrics = rememberUpdatedState(syncedLyrics)
    // Incremented every time we detect a backward position discontinuity larger
    // than 1 second — this happens when REPEAT_MODE_ONE wraps the song back to
    // the start (or when the user seeks backward by more than a second). The
    // KaraokeLyricsView from com.mocharealm.accompanist:lyrics-ui doesn't reset
    // its internal karaoke-progress / current-line state when the player
    // position jumps backward, so the highlight gets stuck at whatever line
    // was last active before the wrap. Forcing a re-key here disposes the old
    // view instance and creates a fresh one, which restarts the karaoke
    // animation from the current line.
    var positionResetCounter by remember { mutableIntStateOf(0) }
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }
    // The scroll state is recreated together with the subtree that owns it.
    val listState = key(lyricsSessionKey, positionResetCounter, karaokeGeneration) { rememberLazyListState() }

    // ── First-frame placement ── A fresh LazyListState starts at line 0 and the auto-scroll
    // collector below walks it to the active line.
    var awaitingFirstFocus by
        remember(lyricsSessionKey, positionResetCounter, karaokeGeneration) {
            mutableStateOf(isSynced)
        }
    // Safety net: nothing may keep the lyrics hidden. If the placement hasn't
    // happened by the time this fires (no viewport, auto-scroll preference off,
    // an index that never lands in range) the lines are shown as they are.
    LaunchedEffect(awaitingFirstFocus) {
        if (!awaitingFirstFocus) return@LaunchedEffect
        delay(LYRIC_FIRST_FOCUS_TIMEOUT_MS)
        awaitingFirstFocus = false
    }
    // Read only from the draw phase (graphicsLayer), so the fade never
    // recomposes the karaoke view.
    val firstFocusAlpha =
        animateFloatAsState(
            targetValue = if (awaitingFirstFocus) 0f else 1f,
            animationSpec =
                tween(
                    durationMillis = if (awaitingFirstFocus) 0 else LYRIC_FIRST_FOCUS_FADE_MS,
                    easing = LinearEasing,
                ),
            label = "lyrics-first-focus-alpha",
        )

    LaunchedEffect(lyricsSessionKey) {
        playbackPositionMs.longValue = player.currentPosition.coerceAtLeast(0L)
        isManualScrolling = false
        lastManualScrollTime = 0L
        isSelectionModeActive = false
        selectedLineKeys.clear()
        // Reset the cached line index so the auto-scroll effect force-scrolls to the
        // new track's first active line instead of inheriting the previous track's index.
        currentLineIndexState.intValue = -1
    }

    LaunchedEffect(player, lyricsSessionKey, animationsDisabled, playbackParameters.speed) {
        var wasSliderActive = false
        var anchorPlayerPositionMs = player.currentPosition.coerceAtLeast(0L)
        var anchorFrameNanos = 0L
        var lastRawPositionMs = player.currentPosition.coerceAtLeast(0L)
        // Cache the current line index + boundary timestamps to avoid calling
        // findLastStartedLineIndex (binary search) every frame.
        var lastLyricsRef: SyncedLyrics? = null
        var cachedLineIdx = -1
        var cachedCurrentLineStart = Int.MIN_VALUE
        var cachedNextLineStart = Int.MAX_VALUE
        while (isActive) {
            val sliderPosition = latestSliderPositionProvider.value()
            val isSliderActive = sliderPosition != null
            if (isSliderActive && !wasSliderActive) {
                isManualScrolling = false
            }
            wasSliderActive = isSliderActive

            val rawPosition = (sliderPosition ?: player.currentPosition).coerceAtLeast(0L)
            // Detect a backward position discontinuity, measured against the
            // PLAYER's clock rather than the slider-provided value. This catches
            // both REPEAT_MODE_ONE wraps (duration -> 0) and explicit backward
            // seeks, while the 1s threshold excludes playback jitter and minor
            // ExoPlayer position corrections.
            //
            // Do NOT gate this on `sliderPosition == null`: the Apple Music player always installs
            // a slider provider, so the gate would disable restart detection there and the lyrics
            // would snap back to the first line without re-animating until the overlay was
            // reopened.
            val rawPlayerPosition = player.currentPosition.coerceAtLeast(0L)
            if (lastRawPositionMs - rawPlayerPosition > POSITION_RESET_BACKWARD_THRESHOLD_MS) {
                positionResetCounter += 1
            }
            lastRawPositionMs = rawPlayerPosition
            // effectivePositionMs is the synced position (offset + lead +
            // tuning) that we'd otherwise compute via playbackSyncPosition().
            // Computing it here inline avoids a redundant State read of
            // playbackPositionMs.longValue (we already have the value in
            // `rawPosition` / `nextPosition`).
            val effectivePositionMs: Long
            if (sliderPosition != null || !player.isPlaying || animationsDisabled) {
                anchorPlayerPositionMs = rawPosition
                anchorFrameNanos = 0L
                if (playbackPositionMs.longValue != rawPosition) {
                    playbackPositionMs.longValue = rawPosition
                }
                effectivePositionMs =
                    (rawPosition + latestLyricsSyncOffset.value.toLong() +
                        latestLeadMs.value + LYRIC_VISUAL_TUNING_OFFSET_MS)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                if (sliderPosition == null) {
                    delay(100L)
                } else {
                    withFrameNanos { }
                }
            } else {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                if (anchorFrameNanos == 0L) {
                    anchorFrameNanos = frameNanos
                    anchorPlayerPositionMs = rawPosition
                }

                val elapsedMs = ((frameNanos - anchorFrameNanos) / 1_000_000f) * latestPlaybackSpeed.value
                val projectedPosition = anchorPlayerPositionMs + elapsedMs.roundToLong()
                val driftMs = rawPosition - projectedPosition
                val nextPosition =
                    when {
                        driftMs > SMOOTH_PLAYBACK_MAX_FORWARD_DRIFT_MS ||
                            driftMs < -SMOOTH_PLAYBACK_MAX_BACKWARD_DRIFT_MS -> {
                            anchorPlayerPositionMs = rawPosition
                            anchorFrameNanos = frameNanos
                            rawPosition
                        }

                        driftMs != 0L -> {
                            projectedPosition + (driftMs * SMOOTH_PLAYBACK_DRIFT_CORRECTION).roundToLong()
                        }

                        else -> {
                            projectedPosition
                        }
                    }.coerceAtLeast(0L)

                if (playbackPositionMs.longValue != nextPosition) {
                    playbackPositionMs.longValue = nextPosition
                }
                effectivePositionMs =
                    (nextPosition + latestLyricsSyncOffset.value.toLong() +
                        latestLeadMs.value + LYRIC_VISUAL_TUNING_OFFSET_MS)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
            }

            // Line-index tracking: only re-search when the position crosses a
            // line boundary OR the lyrics object identity changed (romanization
            // finished). This is the key per-frame optimization — the binary
            // search is skipped entirely on the vast majority of frames, and
            // we reuse `effectivePositionMs` instead of calling the
            // `playbackSyncPosition()` lambda (which would do 3 more State
            // reads + arithmetic on every frame).
            val syncedLyricsNow = latestSyncedLyrics.value
            if (syncedLyricsNow.lines.isNotEmpty()) {
                val lyricsChanged = syncedLyricsNow !== lastLyricsRef
                if (lyricsChanged) {
                    lastLyricsRef = syncedLyricsNow
                    cachedLineIdx = -1
                }
                val pos = effectivePositionMs.toInt()
                val needsResearch =
                    cachedLineIdx == -1 ||
                        pos >= cachedNextLineStart ||
                        pos < cachedCurrentLineStart
                if (needsResearch) {
                    val newLineIdx = syncedLyricsNow.findLastStartedLineIndex(pos)
                    cachedLineIdx = newLineIdx
                    cachedCurrentLineStart =
                        if (newLineIdx >= 0) syncedLyricsNow.lines[newLineIdx].start else Int.MIN_VALUE
                    cachedNextLineStart =
                        syncedLyricsNow.lines.getOrNull(newLineIdx + 1)?.start ?: Int.MAX_VALUE
                    if (newLineIdx != currentLineIndexState.intValue) {
                        currentLineIndexState.intValue = newLineIdx
                    }
                }
            }
        }
    }

    val nestedScrollConnection =
        remember {
            var lastUserScrollEventMs = 0L
            object : NestedScrollConnection {
                private fun markManualScroll() {
                    val now = System.currentTimeMillis()
                    if (now - lastUserScrollEventMs >= MANUAL_SCROLL_DEBOUNCE_MS) {
                        isManualScrolling = true
                        lastManualScrollTime = now
                        lastUserScrollEventMs = now
                    }
                }

                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                        markManualScroll()
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (!isSelectionModeActive && isManualScrolling) {
                        lastManualScrollTime = System.currentTimeMillis()
                    }
                    return Velocity.Zero
                }
            }
        }

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

    // Deliberately not a key on `syncedLyrics` — the value is read through rememberUpdatedState
    // below instead, so a lyrics update does not restart the collector.
    val latestSyncedLyricsForScroll = rememberUpdatedState(syncedLyrics)
    // Restart this collector after a repeat, or after a romanisation re-key. The karaoke view is
    // re-keyed at the same time, and the fresh collector resets its focus state before it
    // observes the first new active line. Keeping listState stable means the collector
    // always targets the list currently on screen.
    LaunchedEffect(
        lyricsSessionKey,
        isSynced,
        positionResetCounter,
        karaokeGeneration,
        animationsDisabled,
    ) {
        if (!isSynced || singleActiveLine) {
            // Single-line mode never scrolls a lyric list, so there is no first focus to wait
            // for — show the strip straight away.
            awaitingFirstFocus = false
            return@LaunchedEffect
        }
        // An empty line list here means "the off-thread build hasn't published yet", not "this
        // track has no synced lyrics" — buildSyncedLyrics runs on Dispatchers.Default and lands a
        // frame or two after the first composition. Bailing out on it (which is what this used to
        // do) disarmed the first-focus gate before there was anything to place, so the lines were
        // drawn at line 0 and then visibly walked to the active one. Wait for the content instead;
        // the LYRIC_FIRST_FOCUS_TIMEOUT_MS safety net above covers the case where it never arrives.
        snapshotFlow { latestSyncedLyricsForScroll.value.lines.isNotEmpty() }.first { it }
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset > listState.layoutInfo.viewportStartOffset
        }.first { it }

        var forceNextScroll = true
        snapshotFlow {
            if (isManualScrolling || isSelectionModeActive) {
                null
            } else {
                currentLineIndexState.intValue
                    .takeIf { index -> index in latestSyncedLyricsForScroll.value.lines.indices }
            }
        }.distinctUntilChanged()
            .collectLatest { index ->
                if (index == null) {
                    forceNextScroll = true
                    // Nothing to place: either no line has started yet (song intro) or the user
                    // took over the scroll. The list is already where it belongs in both cases, so
                    // release the first-focus gate instead of letting it time out — otherwise the
                    // lines would sit invisible for LYRIC_FIRST_FOCUS_TIMEOUT_MS on every track
                    // whose first lyric starts a beat after playback does.
                    awaitingFirstFocus = false
                    return@collectLatest
                }

                // The first placement of a session is a snap: the list is still
                // transparent, so animating it would only spend frames on a
                // motion nobody can see — and it is the animation that used to
                // read as the lyrics repositioning themselves.
                val isFirstFocus = awaitingFirstFocus
                listState.scrollLyricIntoFocus(
                    index = index,
                    animateToNearbyItem = !forceNextScroll,
                    force = forceNextScroll,
                    snap = isFirstFocus,
                    animationsDisabled = animationsDisabled,
                )
                forceNextScroll = false
                if (isFirstFocus) awaitingFirstFocus = false
            }
    }

    // Clear the tracked active line after a backward discontinuity so the re-keyed
    // auto-scroll collector above force-scrolls to the first line of the new
    // play-through instead of treating the previous line index as still current.
    //
    // No explicit scroll here: [listState] is recreated alongside the karaoke subtree on
    // the same counter, so the fresh list already starts at the top. Scrolling the old
    // state was both redundant and a way to touch a state whose layout was being
    // disposed.
    LaunchedEffect(positionResetCounter) {
        if (positionResetCounter > 0) {
            currentLineIndexState.intValue = -1
        }
    }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedLineKeys.clear()
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

    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Remembered: fresh TextStyle identities every recomposition forced the karaoke view to
    // re-measure all lines on unrelated state changes (scroll flags, selection, etc.).
    val typography = MaterialTheme.typography
    val normalTextStyle =
        remember(typography, lyricsTextSize, lyricsFontFamily) {
            typography.headlineMedium.copy(
                fontSize = lyricsTextSize.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = lyricsFontFamily ?: typography.headlineMedium.fontFamily,
            )
        }
    val accompanimentTextStyle =
        remember(typography, lyricsTextSize, lyricsFontFamily) {
            typography.titleLarge.copy(
                fontSize = (lyricsTextSize * 0.82f).sp,
                fontFamily = lyricsFontFamily ?: typography.titleLarge.fontFamily,
            )
        }
    val phoneticTextStyle =
        remember(typography, lyricsTextSize) {
            typography.bodyMedium.copy(
                fontSize = (lyricsTextSize * 0.55f).sp,
                fontWeight = FontWeight.Normal,
            )
        }
    val plainLyrics =
        remember(lyricsEntries, isSynced) {
            PlainLyrics(
                items =
                    if (isSynced) {
                        emptyList()
                    } else {
                        lyricsEntries.mapIndexedNotNull { index, entry ->
                            val text = entry.text.trim()
                            if (text.isBlank()) {
                                null
                            } else {
                                val selectionId = "plain:$index:${text.hashCode()}"
                                PlainLyricLine(
                                    itemId = "$selectionId#$index",
                                    selectionId = selectionId,
                                    text = text,
                                )
                            }
                        }
                    },
            )
        }
    val selectionLines =
        remember(isSynced, syncedLyrics, plainLyrics) {
            if (isSynced) {
                syncedLyrics.lines.mapIndexedNotNull { index, line ->
                    val text = line.lineText()
                    if (text.isBlank()) {
                        null
                    } else {
                        val selectionId = line.selectionKey(text)
                        LyricSelectionLine(
                            itemId = "$selectionId#$index",
                            selectionId = selectionId,
                            text = text,
                        )
                    }
                }
            } else {
                plainLyrics.items.map { line ->
                    LyricSelectionLine(
                        itemId = line.itemId,
                        selectionId = line.selectionId,
                        text = line.text,
                    )
                }
            }
        }
    val selectedLineKeySnapshot = selectedLineKeys.toList()
    val selectedLineKeySet = remember(selectedLineKeySnapshot) { selectedLineKeySnapshot.toSet() }
    val dismissSelection = {
        isSelectionModeActive = false
        selectedLineKeys.clear()
    }
    val toggleSelectedLine: (String) -> Unit = { lineKey ->
        if (selectedLineKeys.contains(lineKey)) {
            selectedLineKeys.remove(lineKey)
            if (selectedLineKeys.isEmpty()) isSelectionModeActive = false
        } else if (selectedLineKeys.size < maxSelectionLimit) {
            selectedLineKeys.add(lineKey)
        } else {
            showMaxSelectionToast = true
        }
    }
    val shareSelectedLyrics: () -> Unit = {
        val metadata = mediaMetadata
        if (metadata != null) {
            val selectedLyricsText =
                selectionLines
                    .filter { line -> line.selectionId in selectedLineKeySet }
                    .joinToString("\n") { line -> line.text }
            if (selectedLyricsText.isNotBlank()) {
                shareDialogData =
                    Triple(
                        selectedLyricsText,
                        metadata.title,
                        metadata.artists.joinToString { it.name },
                    )
                showShareDialog = true
            }
        }
        dismissSelection()
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = 12.dp)
                // last of those lets the first two flash at full opacity and vanish when the lines
                // take over. When there is nothing to place (plain lyrics, no lyrics, not-found) the
                // gate is never armed and this is a no-op.
                .graphicsLayer { alpha = firstFocusAlpha.value },
    ) {
        when {
            lyrics == LYRICS_NOT_FOUND -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // parsedEntries == null means the off-thread parse above hasn't published yet.
            // Shimmer on that state — without it the two "lyrics not found" branches below
            // would flash for the frames the parse takes.
            lyrics == null || parsedEntries == null -> {
                ShimmerHost {
                    repeat(6) { TextPlaceholder() }
                }
            }

            // Same window, one hop later: the parse has landed but buildSyncedLyrics (also on
            // Dispatchers.Default) hasn't published its result, so `lines` is empty even though
            // this track definitely has synced lyrics. Keep the shimmer up rather than claiming
            // they don't exist.
            isSynced && syncedLyrics.lines.isEmpty() && lyricsEntries.isNotEmpty() -> {
                ShimmerHost {
                    repeat(6) { TextPlaceholder() }
                }
            }

            isSynced && syncedLyrics.lines.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced && plainLyrics.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val lyricsContentWidth =
                        minOf(maxWidth, if (spotifyStyle) 760.dp else 860.dp)
                    PlainLyricsView(
                        lines = plainLyrics,
                        listState = listState,
                        selectedLineKeys = selectedLineKeySet,
                        textColor = textColor,
                        textStyle = normalTextStyle,
                        onLineClicked = { lineKey ->
                            if (isSelectionModeActive) toggleSelectedLine(lineKey)
                        },
                        onLinePressed = { lineKey ->
                            if (!isSelectionModeActive) {
                                isSelectionModeActive = true
                                if (!selectedLineKeys.contains(lineKey)) {
                                    selectedLineKeys.add(lineKey)
                                }
                            } else if (!selectedLineKeys.contains(lineKey)) {
                                toggleSelectedLine(lineKey)
                            }
                        },
                        modifier =
                            Modifier
                                .widthIn(max = lyricsContentWidth)
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .align(Alignment.TopCenter),
                    )
                }
            }

            // TikTok-style strip: only the active line cluster is ever composed — no line list,
            // no scrolling, no previous/upcoming lines.
            singleActiveLine && isSynced -> {
                key(lyricsSessionKey, positionResetCounter, karaokeGeneration) {
                    CompositionLocalProvider(LocalTextStyle provides phoneticTextStyle) {
                        SingleActiveKaraokeLine(
                            activeLine = activeKaraokeLine,
                            currentPosition = playbackSyncPosition,
                            textColor = textColor,
                            normalTextStyle = normalTextStyle,
                            accompanimentTextStyle = accompanimentTextStyle,
                            phoneticTextStyle = phoneticTextStyle,
                            showTranslation = showTranslations,
                            onLineClick = { line ->
                                if (lyricsClick && line.start > 0) {
                                    player.seekTo(line.start.toLong())
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            else -> {
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection),
                ) {
                    val lyricsViewportOffset = remember(maxHeight) { maxHeight * 0.08f }
                    val lyricsContentWidth =
                        minOf(maxWidth, if (spotifyStyle) 760.dp else 860.dp)

                    // Keyed on the session + a position-reset counter so that when the song repeats
                    // (REPEAT_MODE_ONE wraps position to 0) or the user seeks backward by more than
                    // 1 second, the KaraokeLyricsView instance is disposed and recreated — the
                    // library's internal highlight state doesn't reset on backward position jumps,
                    // so without this re-key the karaoke animation freezes at the line that was
                    // active just before the repeat.
                    key(lyricsSessionKey, positionResetCounter, karaokeGeneration) {
                        KaraokeLyricsView(
                            listState = listState,
                            lyrics = syncedLyrics,
                            currentPosition = playbackSyncPosition,
                            onLineClicked = { line ->
                                if (isSelectionModeActive) {
                                    toggleSelectedLine(line.selectionKey())
                                } else if (lyricsClick && isSynced && line.start > 0) {
                                    player.seekTo(line.start.toLong())
                                }
                            },
                            onLinePressed = { line ->
                                val lineKey = line.selectionKey()
                                if (!isSelectionModeActive) {
                                    isSelectionModeActive = true
                                    if (!selectedLineKeys.contains(lineKey)) {
                                        selectedLineKeys.add(lineKey)
                                    }
                                } else if (!selectedLineKeys.contains(lineKey)) {
                                    toggleSelectedLine(lineKey)
                                }
                            },
                            textColor = textColor,
                            normalLineTextStyle = normalTextStyle,
                            accompanimentLineTextStyle = accompanimentTextStyle,
                            phoneticTextStyle = phoneticTextStyle,
                            blendMode = BlendMode.SrcOver,
                            // Per-line RenderEffect blur is the single heaviest per-frame cost in
                            // this view; drop it when animations are disabled (low-RAM default).
                            useBlurEffect = lyricsLineBlur && !animationsDisabled,
                            showTranslation = showTranslations,
                            showPhonetic = romanizationPreferences.showsRomanization,
                            offset = lyricsViewportOffset,
                            // Reduced from 36.dp → 20.dp → 8.dp.
                            keepAliveZone = 8.dp,
                            modifier =
                                Modifier
                                    .widthIn(max = lyricsContentWidth)
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    if (isSelectionModeActive && selectionLines.isNotEmpty()) {
        LyricsSelectionBottomSheet(
            lines = selectionLines,
            selectedLineKeys = selectedLineKeySet,
            onToggleLine = toggleSelectedLine,
            onDismissRequest = dismissSelection,
            onShareSelected = shareSelectedLyrics,
        )
    }

    if (showShareDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        BasicAlertDialog(onDismissRequest = { showShareDialog = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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

@Immutable
private data class PlainLyrics(
    val items: List<PlainLyricLine>,
)

@Immutable
private data class PlainLyricLine(
    val itemId: String,
    val selectionId: String,
    val text: String,
)

@Immutable
private data class LyricSelectionLine(
    val itemId: String,
    val selectionId: String,
    val text: String,
)

@Composable
private fun PlainLyricsView(
    lines: PlainLyrics,
    listState: LazyListState,
    selectedLineKeys: Set<String>,
    textColor: Color,
    textStyle: TextStyle,
    onLineClicked: (String) -> Unit,
    onLinePressed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentPadding =
        remember {
            PaddingValues(
                start = 12.dp,
                top = 120.dp,
                end = 12.dp,
                bottom = 96.dp,
            )
        }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = lines.items,
            key = { line -> line.itemId },
            contentType = { "plain_lyric_line" },
        ) { line ->
            PlainLyricLineItem(
                line = line,
                selected = line.selectionId in selectedLineKeys,
                textColor = textColor,
                textStyle = textStyle,
                onLineClicked = onLineClicked,
                onLinePressed = onLinePressed,
            )
        }
    }
}

@Composable
private fun PlainLyricLineItem(
    line: PlainLyricLine,
    selected: Boolean,
    textColor: Color,
    textStyle: TextStyle,
    onLineClicked: (String) -> Unit,
    onLinePressed: (String) -> Unit,
) {
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            textColor
        }

    Text(
        text = line.text,
        style = textStyle,
        color = contentColor,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .combinedClickable(
                    onClick = { onLineClicked(line.selectionId) },
                    onLongClick = { onLinePressed(line.selectionId) },
                ).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun LyricsSelectionBottomSheet(
    lines: List<LyricSelectionLine>,
    selectedLineKeys: Set<String>,
    onToggleLine: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onShareSelected: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstSelectedIndex =
        remember(lines, selectedLineKeys) {
            lines
                .indexOfFirst { line -> line.selectionId in selectedLineKeys }
                .coerceAtLeast(0)
        }
    val sheetListState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = firstSelectedIndex,
        )
    val selectedCount = selectedLineKeys.size

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_selected),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.n_element, selectedCount, selectedCount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.close))
                }
            }

            LazyColumn(
                state = sheetListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = lines,
                    key = { line -> line.itemId },
                    contentType = { "lyric_selection_line" },
                ) { line ->
                    LyricsSelectionLineItem(
                        line = line,
                        selected = line.selectionId in selectedLineKeys,
                        onClick = { onToggleLine(line.selectionId) },
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.cancel))
                }
                Button(
                    onClick = onShareSelected,
                    enabled = selectedCount > 0,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.share),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = stringResource(R.string.share_selected))
                }
            }
        }
    }
}

@Composable
private fun LyricsSelectionLineItem(
    line: LyricSelectionLine,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineSmall,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

private suspend fun LazyListState.scrollLyricIntoFocus(
    index: Int,
    animateToNearbyItem: Boolean,
    force: Boolean,
    // Placement for a view that isn't visible yet: skip both animations so the
    // active line is already in place on the frame the lyrics fade in.
    snap: Boolean = false,
    animationsDisabled: Boolean,
) {
    val itemCount = layoutInfo.totalItemsCount
    if (itemCount == 0) return

    val targetIndex = index.coerceIn(0, itemCount - 1)
    var itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
    if (itemInfo == null) {
        val distance = abs(targetIndex - firstVisibleItemIndex)
        if (!snap && animateToNearbyItem && !animationsDisabled && distance <= LYRIC_FOCUS_ANIMATED_DISTANCE) {
            animateScrollToItem(targetIndex)
        } else {
            scrollToItem(targetIndex)
        }
        withFrameNanos { }
        itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
    }

    itemInfo ?: return

    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportHeight = viewportEnd - viewportStart
    if (viewportHeight <= 0) return

    val itemFocusPoint = itemInfo.offset
    val topGuard = viewportStart + (viewportHeight * LYRIC_FOCUS_TOP_GUARD_RATIO).roundToInt()
    val bottomGuard = viewportEnd - (viewportHeight * LYRIC_FOCUS_BOTTOM_GUARD_RATIO).roundToInt()
    if (!force && itemFocusPoint in topGuard..bottomGuard) return

    val targetFocusPoint = viewportStart + (viewportHeight * LYRIC_FOCUS_TOP_ANCHOR_RATIO).roundToInt()
    val scrollDelta = itemFocusPoint - targetFocusPoint
    if (abs(scrollDelta) > LYRIC_FOCUS_MIN_SCROLL_PX) {
        // For deltas up to 40% of the viewport (which covers ALL normal
        // line-advance scrolls — they're typically ~22% of viewport), snap
        // instantly instead of running a 280ms tween. The tween triggers a
        // LazyColumn re-layout every frame for its entire duration, which
        // steals frame budget from the 60Hz karaoke syllable sweep. The snap
        // is imperceptible because the karaoke fill animation already provides
        // visual continuity. Larger deltas (return from manual scroll, large
        // seeks) still animate so the motion stays smooth over long distances.
        val instantThreshold = (viewportHeight * LYRIC_FOCUS_INSTANT_SCROLL_RATIO).roundToInt()
        if (snap || animationsDisabled || (abs(scrollDelta) <= instantThreshold && !force)) {
            scrollBy(scrollDelta.toFloat())
        } else {
            animateScrollBy(
                value = scrollDelta.toFloat(),
                animationSpec =
                    tween(
                        durationMillis = LYRIC_FOCUS_SCROLL_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
            )
        }
    }
}

private fun ISyncedLine.lineText(): String =
    when (this) {
        is KaraokeLine -> syllables.joinToString("") { it.content }
        is SyncedLine -> content
        else -> ""
    }

private fun ISyncedLine.selectionKey(text: String = lineText()): String = "$start:$end:${text.hashCode()}"

private fun SyncedLyrics.findLastStartedLineIndex(time: Int): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1

    while (low <= high) {
        val mid = low + (high - low) / 2
        if (lines[mid].start <= time) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }

    return result
}

private fun List<WordTimestamp>.toKaraokeSyllables(phonetics: List<String?>): List<KaraokeSyllable> =
    mapIndexed { index, word ->
        val start = word.startTime.toMilliseconds()
        val nextStart = getOrNull(index + 1)?.startTime?.toMilliseconds()
        val rawEnd = word.endTime.toMilliseconds()
        val end =
            nextStart
                ?.let { minOf(rawEnd, it) }
                ?: rawEnd

        KaraokeSyllable(
            content = word.text,
            start = start,
            end = end.coerceAtLeast(start + MIN_KARAOKE_SYLLABLE_DURATION_MS),
            phonetic = phonetics.getOrNull(index),
        )
    }

private fun Double.toMilliseconds(): Int = (this * 1000.0).roundToInt().coerceAtLeast(0)

/**
 * Reshapes AI romanisation — one string per lyric line — into the per-word map [buildSyncedLyrics]
 * expects.
 */
private fun aiRomanizationMap(
    entries: List<LyricsEntry>,
    aiLines: List<String?>,
): Map<Int, List<String?>> {
    if (aiLines.isEmpty() || entries.isEmpty()) return emptyMap()
    val map = mutableMapOf<Int, List<String?>>()
    entries.forEachIndexed { index, entry ->
        val romanized = aiLines.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
        val words = entry.words?.filter { !it.isBackground }
        map[index] =
            if (!words.isNullOrEmpty()) {
                distributePhonetics(words.map { it.text }, romanized)
            } else {
                listOf(romanized)
            }
    }
    return map
}

/**
 * Spreads the whitespace-separated pieces of [romanized] across [words], keeping order.
 *
 * Proportional rather than one-to-one because the two sides rarely have the same count: "君の名は"
 * arrives as four TTML words and comes back as "kimi no na wa", but "ありがとう" is one word and one
 * token. Anchoring by relative position keeps a syllable's phonetic under roughly the right glyphs
 * even when the counts differ, and merging surplus tokens onto the last anchor means none are lost.
 */
private fun distributePhonetics(
    words: List<String>,
    romanized: String,
): List<String?> {
    if (words.isEmpty()) return emptyList()
    val tokens = romanized.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return List(words.size) { null }
    if (words.size == 1) return listOf(tokens.joinToString(" "))

    val out = MutableList<String?>(words.size) { null }
    tokens.forEachIndexed { tokenIndex, token ->
        val anchor = (tokenIndex * words.size / tokens.size).coerceIn(0, words.size - 1)
        out[anchor] = listOfNotNull(out[anchor], token).joinToString(" ")
    }
    return out
}

private fun buildSyncedLyrics(
    entries: List<LyricsEntry>,
    romanizationMap: Map<Int, List<String?>>,
): SyncedLyrics {
    if (entries.isEmpty()) return SyncedLyrics(emptyList())
    val lines = mutableListOf<ISyncedLine>()

    entries.forEachIndexed { index, entry ->
        if (entry.time < 0L) return@forEachIndexed
        if (entry.isInstrumental) return@forEachIndexed
        if (entry.text.isBlank() && entry.words.isNullOrEmpty()) return@forEachIndexed

        if (entry.words != null && hasTrueWordSync(entry)) {
            val translation = providedTranslationTextForEntry(entry)
            val mainWords = entry.words!!.filter { !it.isBackground }
            val bgWords = entry.words!!.filter { it.isBackground }
            val alignment =
                when (entry.agent?.lowercase()) {
                    "v2" -> KaraokeAlignment.End
                    else -> KaraokeAlignment.Start
                }

            val wordsForMain = if (mainWords.isNotEmpty()) mainWords else entry.words!!
            val wordPhonetics = romanizationMap[index] ?: emptyList()
            val mainSyllables = wordsForMain.toKaraokeSyllables(wordPhonetics)

            val lineStart = mainSyllables.first().start
            val lineEnd = mainSyllables.last().end
            if (lineEnd <= lineStart) return@forEachIndexed

            val accompanimentLines =
                if (mainWords.isNotEmpty() && bgWords.isNotEmpty()) {
                    val bgSyllables = bgWords.toKaraokeSyllables(emptyList())
                    val bgStart = bgSyllables.first().start
                    val bgEnd = bgSyllables.last().end
                    if (bgEnd > bgStart) {
                        listOf(
                            KaraokeLine.AccompanimentKaraokeLine(
                                syllables = bgSyllables,
                                translation = null,
                                alignment = alignment,
                                start = bgStart,
                                end = bgEnd,
                                phonetic = null,
                            ),
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }

            lines.add(
                KaraokeLine.MainKaraokeLine(
                    syllables = mainSyllables,
                    translation = translation,
                    alignment = alignment,
                    start = lineStart,
                    end = lineEnd,
                    phonetic = null,
                    accompanimentLines = accompanimentLines,
                ),
            )
        } else {
            val nextEntry = entries.getOrNull(index + 1)
            // ── Why `end` must be exactly the next line's start ── KaraokeLyricsView picks the
            // focused line with lines.indices.firstOrNull { pos >= it.start && pos <
            // effectiveEnd(it) } ?: lines.indexOfFirst { it.start > pos } // ← the *upcoming* line
            // ?: lines.lastIndex so any instant that falls in a gap between one line's end and the
            // next line's start resolves to the line that has NOT started yet.
            val lineEnd =
                if (nextEntry != null && nextEntry.time > entry.time) {
                    val handOver = entry.time + LINE_SYNCED_MAX_FOCUS_HOLD_MS
                    if (nextEntry.time - handOver > LINE_SYNCED_INTERLUDE_MIN_GAP_MS) {
                        // A real instrumental break. Hand focus over so the library's breathing-dots
                        // interlude — which only triggers when `next.start - previous.end` exceeds
                        // its own 5s threshold — still gets to run through the silence.
                        handOver.toInt()
                    } else {
                        nextEntry.time.toInt()
                    }
                } else {
                    (entry.time + LINE_SYNCED_TRAILING_LINE_DURATION_MS).toInt()
                }
            lines.add(
                buildLineSyncedLrcLine(
                    entry = entry,
                    romanizedText = romanizationMap[index]?.firstOrNull(),
                    start = entry.time.toInt(),
                    end = lineEnd,
                ),
            )
        }
    }

    return SyncedLyrics(lines = lines)
}

private fun buildLineSyncedLrcLine(
    entry: LyricsEntry,
    romanizedText: String?,
    start: Int,
    end: Int,
): ISyncedLine {
    val translation = providedTranslationTextForEntry(entry)
    val normalizedRomanizedText = romanizedText?.trim()?.takeIf { it.isNotEmpty() }

    if (normalizedRomanizedText == null) {
        return SyncedLine(
            content = entry.text,
            translation = translation,
            start = start,
            end = end,
        )
    }

    val syllables =
        buildWrappingKaraokeSyllables(
            content = entry.text,
            romanizedText = normalizedRomanizedText,
            start = start,
        )

    return KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = translation,
        alignment = KaraokeAlignment.Start,
        start = start,
        end = end,
    )
}

/**
 * Wraps a line-synced lyric into karaoke syllables purely so a romanisation can sit above it.
 *
 * Takes no `end`, deliberately: a line-synced LRC file has no word timing, so there is nothing to
 * spread across the line's duration and no reason for the syllables to know how long the line lasts.
 */
private fun buildWrappingKaraokeSyllables(
    content: String,
    romanizedText: String,
    start: Int,
): List<KaraokeSyllable> {
    val contentUnits = content.toLyricsWrappingUnits().ifEmpty { listOf(content) }
    val phoneticWords = romanizedText.split(Regex("\\s+")).filter(String::isNotEmpty)
    val phoneticAnchorIndices =
        contentUnits.indices.filter { index ->
            contentUnits[index].any(Char::isLetterOrDigit)
        }
    val phoneticsByUnit = MutableList<String?>(contentUnits.size) { null }

    if (phoneticAnchorIndices.isNotEmpty()) {
        phoneticWords.forEachIndexed { wordIndex, word ->
            val anchorIndex = wordIndex * phoneticAnchorIndices.size / phoneticWords.size
            val unitIndex = phoneticAnchorIndices[anchorIndex]
            phoneticsByUnit[unitIndex] = listOfNotNull(phoneticsByUnit[unitIndex], word).joinToString(" ")
        }
    }

    // Every unit shares one instant, deliberately: these syllables exist only to hang a
    // romanisation over the right glyphs, so per-word timing derived from the line's *length*
    // would be pure fabrication, and `KaraokeLineText` would faithfully animate it — making plain
    // LRC lyrics fill word by word whenever romanisation is on, while the very same lines without
    // romanisation highlight whole through `SyncedLine`.
    //
    // Collapsing every unit onto `[start, start + 1)` makes `KaraokeSyllable.progress` a step
    // function: 0 before the line, 1 from its first millisecond. The whole line lights up at once,
    // matching the `SyncedLine` path exactly, and each romanisation still sits above its own unit.
    val syllableEnd = start + MIN_KARAOKE_SYLLABLE_DURATION_MS
    return contentUnits.mapIndexed { index, unit ->
        KaraokeSyllable(
            content = unit,
            start = start,
            end = syllableEnd,
            phonetic = phoneticsByUnit[index],
        )
    }
}

/**
 * Renders exactly one karaoke line cluster — the joined phonetic (romanisation) row above, the
 * main line with its per-word colour sweep and the translation row(s) below — as a compact custom
 * renderer.
 *
 * Deliberately NOT the library's [KaraokeLyricsView]: that view draws a permanent vertical
 * fading-edge mask over its whole viewport (a 20dp fade-in at the top and a 100dp fade-out at the
 * bottom, applied with DstIn over everything). In a ~168dp strip that mask dominates — a long line
 * that wraps onto a second row pushes that row (and the translation) into the ramp and visibly
 * fades "the more line there is". This renderer clips nothing and fades nothing, so wrapped lines
 * stay fully opaque.
 *
 * [AnimatedContent] handles the line-change cross-fade; its content is bottom-aligned so the
 * cluster sits directly above the song-info block.
 */
@Composable
private fun SingleActiveKaraokeLine(
    activeLine: ISyncedLine?,
    currentPosition: () -> Int,
    textColor: Color,
    normalTextStyle: TextStyle,
    accompanimentTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    onLineClick: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = activeLine,
        transitionSpec = {
            (
                fadeIn(tween(durationMillis = 240, easing = LinearEasing)) +
                    slideInVertically(
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 5 },
                    )
            ).togetherWith(fadeOut(tween(durationMillis = 160, easing = LinearEasing)))
        },
        contentAlignment = Alignment.BottomStart,
        label = "single-active-lyric-line",
        modifier = modifier,
    ) { line ->
        if (line != null) {
            SingleActiveLineCluster(
                line = line,
                currentPosition = currentPosition,
                textColor = textColor,
                normalTextStyle = normalTextStyle,
                accompanimentTextStyle = accompanimentTextStyle,
                phoneticTextStyle = phoneticTextStyle,
                showTranslation = showTranslation,
                onLineClick = onLineClick,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

/** The one-line cluster itself; the karaoke sweep recomposes only this Text. */
@Composable
private fun SingleActiveLineCluster(
    line: ISyncedLine,
    currentPosition: () -> Int,
    textColor: Color,
    normalTextStyle: TextStyle,
    accompanimentTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    onLineClick: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(line.start) { scrollState.scrollTo(0) }
    Column(
        modifier =
            modifier
                .verticalScroll(scrollState)
                .clickable(enabled = line.start > 0) { onLineClick(line) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (line) {
            is KaraokeLine -> {
                (line as? KaraokeLine.MainKaraokeLine)?.accompanimentLines?.forEach { accompaniment ->
                    val accompanimentText =
                        (accompaniment as? KaraokeLine)?.syllables?.joinSyllableContents().orEmpty()
                    if (accompanimentText.isNotBlank()) {
                        Text(
                            text = accompanimentText,
                            style = accompanimentTextStyle,
                            color = textColor.copy(alpha = 0.45f),
                        )
                    }
                }

                val phonetic =
                    line.syllables
                        .mapNotNull { it.phonetic?.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                if (phonetic.isNotEmpty()) {
                    Text(
                        text = phonetic,
                        style = phoneticTextStyle,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }

                KaraokeSweepText(
                    syllables = line.syllables,
                    currentPosition = currentPosition,
                    textColor = textColor,
                    textStyle = normalTextStyle,
                )

                if (showTranslation) {
                    TranslationStack(
                        translation = line.translation,
                        textColor = textColor,
                        phoneticTextStyle = phoneticTextStyle,
                    )
                }
            }

            else -> {
                // Plain line-synced lyrics: no word timing — the whole active line renders bright,
                // with romanisation/translation stacked underneath it.
                Text(
                    text = line.lineText(),
                    style = normalTextStyle,
                    color = textColor,
                )
                if (showTranslation) {
                    TranslationStack(
                        translation = (line as? SyncedLine)?.translation,
                        textColor = textColor,
                        phoneticTextStyle = phoneticTextStyle,
                    )
                }
            }
        }
    }
}

/**
 * Word-timed karaoke sweep: each syllable's colour interpolates from the dimmed rest colour to the
 * full text colour across its own [start, end].
 */
@Composable
private fun KaraokeSweepText(
    syllables: List<KaraokeSyllable>,
    currentPosition: () -> Int,
    textColor: Color,
    textStyle: TextStyle,
) {
    val position = currentPosition()
    val unsungColor = textColor.copy(alpha = 0.45f)
    val annotated =
        buildAnnotatedString {
            syllables.forEachIndexed { index, syllable ->
                val span = (syllable.end - syllable.start).coerceAtLeast(1)
                val progress = ((position - syllable.start).toFloat() / span).coerceIn(0f, 1f)
                val color = lerp(unsungColor, textColor, progress)
                withStyle(SpanStyle(color = color)) {
                    append(syllable.content)
                    val next = syllables.getOrNull(index + 1)
                    if (next != null && needsLatinWordGap(syllable.content, next.content)) {
                        append(" ")
                    }
                }
            }
        }
    Text(text = annotated, style = textStyle)
}

/** The phonetic (romanisation) and translation rows stacked under the main line. */
@Composable
private fun TranslationStack(
    translation: String?,
    textColor: Color,
    phoneticTextStyle: TextStyle,
) {
    if (translation.isNullOrBlank()) return
    translation
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { row ->
            Text(
                text = row,
                style = phoneticTextStyle,
                color = textColor.copy(alpha = 0.85f),
            )
        }
}

/** A separating space only between Latin-script words — CJK syllables join seamlessly. */
private fun needsLatinWordGap(previous: String, next: String): Boolean =
    isLatinWordChar(previous.lastOrNull()) && isLatinWordChar(next.firstOrNull())

private fun isLatinWordChar(ch: Char?): Boolean {
    if (ch == null) return false
    return ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '\'' || ch == ','
}

private fun List<KaraokeSyllable>.joinSyllableContents(): String {
    val builder = StringBuilder()
    forEachIndexed { index, syllable ->
        builder.append(syllable.content)
        val next = getOrNull(index + 1)
        if (next != null && needsLatinWordGap(syllable.content, next.content)) {
            builder.append(' ')
        }
    }
    return builder.toString()
}
