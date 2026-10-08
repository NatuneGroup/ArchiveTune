/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * SimpMusic's lyrics view.
 *
 * The look is SimpMusic's Classic renderer (its LyricsView / LyricsLineItem / RichSyncLyricsLineItem,
 * https://github.com/maxrave-dev/SimpMusic, GPL-3.0): left-aligned, one line per row, everything a
 * dim grey except the line being sung, which steps up a type size and goes white. A word-timed line
 * lights word by word as it is sung, the rest of the line waiting behind it.
 *
 * Reached wherever LyricsMode.SIMPMUSIC is selected (the SimpMusic style's 300dp card and its
 * fullscreen sheet, the shared lyrics page, the Apple Music player), so it follows the global
 * LyricsModeKey like every other renderer.
 *
 * REWRITTEN rather than transliterated, and deliberately much smaller than either shared renderer:
 * it shows lyrics, follows the song, and seeks on tap. Romanisation, AI translation, per-word blur
 * and the karaoke fill all belong to Enhanced and V2 — reproducing them here would be a third copy
 * of machinery that already exists twice, and none of it is what makes this view look like
 * SimpMusic. The one thing it does share is the parse: LyricsUtils, the same functions both other
 * renderers call, so a format either works in all three or in none.
 */

package moe.rukamori.archivetune.ui.player.simpmusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineSpacingKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsEntry.Companion.HEAD_LYRICS_ENTRY
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.lyrics.LyricsUtils.findCurrentLineIndex
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedRomanizedTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedRomanizedWordsForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.hasTrueWordSync
import moe.rukamori.archivetune.lyrics.LyricsUtils.insertInstrumentalBreaks
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.utils.rememberPreference

/** Everything but the line being sung. SimpMusic's `Color.LightGray.copy(alpha = 0.35f)`. */
private val DimLine = Color.LightGray.copy(alpha = 0.35f)

/**
 * Words of the sung line that have not been reached yet — brighter than a whole dim line. Derived
 * from the host's inactive colour (see [pendingWordColor]) rather than a fixed grey, so a host that
 * tints its lyrics keeps the pending words on the same palette.
 */
private fun pendingWordColor(inactiveColor: Color): Color =
    inactiveColor.copy(alpha = (inactiveColor.alpha * 2f).coerceAtMost(1f))

/** Matches the lead the other two renderers apply, so all three sit on the same beat. */
private const val LRC_LEAD_MS = 300L
private const val TTML_LEAD_MS = 0L
private const val VISUAL_TUNING_OFFSET_MS = 150L

/** How long a drag suspends follow-the-song for, so scrolling back to read is not fought. */
private const val MANUAL_SCROLL_HOLD_MS = 4_000L

/**
 * The default of the shared line-spacing preference, as both other renderers declare it.
 *
 * This style's own spacing is 12dp, so the setting scales *around that* rather than replacing it —
 * at the default the screen looks exactly as it always has, and moving the slider still works.
 */
private const val DEFAULT_LINE_SPACING = 1.3f

/**
 * SimpMusic's lyrics. Signature matches [moe.rukamori.archivetune.ui.component.LyricsEnhanced] and
 * [moe.rukamori.archivetune.ui.component.LyricsV2] so a caller can swap between the three.
 */
@Composable
fun SimpMusicLyrics(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    // Non-null when this is rendered in the player's lyrics CARD rather than full screen.
    textSizeSp: Float? = null,
    // The unsung lines. Null keeps the neutral grey, which is what the card wants: it sits on a
    // surface of its own and a tint of the artwork would fight the card behind it. Full screen
    // passes a tint of the backdrop instead, so the page reads as one colour.
    inactiveColorOverride: Color? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val currentColor = textColorOverride ?: Color.White
    // The fixed grey is this style's own dark-backdrop look. Everywhere the caller supplies its own
    // foreground — the shared lyrics screen, the Apple Music player — the dim tone comes from that
    // colour instead, because a fixed light grey at 35% disappears on a light surface.
    val inactiveColor = inactiveColorOverride ?: textColorOverride?.copy(alpha = 0.45f) ?: DimLine

    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsTextSizePreference) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val (disableAnimations) = rememberPreference(DisableAnimationsKey, defaultValue = false)
    // The two controls Lyrics.kt and LyricsV2.kt have always honoured and this renderer missed:
    // without them, turning auto-scroll off (or asking for tighter lines) worked everywhere except
    // the SimpMusic style.
    val (lyricsScroll) = rememberPreference(LyricsScrollKey, defaultValue = true)
    val (lyricsLineSpacing) = rememberPreference(LyricsLineSpacingKey, defaultValue = DEFAULT_LINE_SPACING)
    val lyricsTextSize = textSizeSp ?: lyricsTextSizePreference

    // The same romanization switches Enhanced and V2 honour. Without these the style ignored
    // every lyrics-related setting beyond text size and tap-to-seek.
    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val (romanizeJapanese) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = true)
    val (romanizeKorean) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (romanizeChinese) = rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val (romanizeHindi) = rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val (romanizeOther) = rememberPreference(LyricsRomanizeOtherLanguagesKey, defaultValue = true)
    val romanizationPreferences =
        remember(romanizeJapanese, romanizeKorean, romanizeChinese, romanizeHindi, romanizeOther, aiRomanizationSettings.active) {
            LyricsRomanizationPreferences(
                romanizeJapanese = romanizeJapanese,
                romanizeKorean = romanizeKorean,
                romanizeChinese = romanizeChinese,
                romanizeHindi = romanizeHindi,
                romanizeOther = romanizeOther,
                aiHandled = aiRomanizationSettings.active,
            )
        }

    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyrics = currentLyrics?.lyrics

    val isSynced = remember(lyrics) { lyrics != null && (isLineSyncedLrc(lyrics) || isTtml(lyrics)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics) }

    // Parsed off the composition thread for the same reason the other two renderers do it: a
    // word-synced TTML file is an XML parse plus an object per syllable, and paying that in
    // composition lands the whole cost on the frame that opens the view.
    var parsed by remember(lyrics) { mutableStateOf<List<LyricsEntry>?>(null) }
    LaunchedEffect(lyrics) {
        val text = lyrics
        if (text == null || text == LYRICS_NOT_FOUND) {
            parsed = emptyList()
            return@LaunchedEffect
        }
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        parsed =
            withContext(Dispatchers.Default) {
                val lines =
                    when {
                        isTtml(text) -> parseTtml(text)
                        isLineSyncedLrc(text) -> insertInstrumentalBreaks(parseLyrics(text), durationMs)
                        else ->
                            text
                                .lines()
                                .filter { it.isNotBlank() }
                                .map { LyricsEntry(time = -1L, text = it.trim()) }
                    }
                // findCurrentLineIndex clamps to 0, so without an empty entry in front of the
                // first real one the opening line reads as "being sung" from 0:00 until the song
                // actually reaches it. Both other renderers prepend the same head entry.
                if (lines.isNotEmpty() && lines.first().time >= 0L) {
                    listOf(HEAD_LYRICS_ENTRY) + lines
                } else {
                    lines
                }
            }
    }
    val entries = parsed.orEmpty()

    // The playhead lives in explicit state read through a stable provider, so a position tick
    // invalidates only the line that reads it — not this composable and not the list.
    val positionState = remember(lyrics) { mutableLongStateOf(0L) }
    // Keyed on the state object: an unkeyed remember would keep the provider built for the first
    // track and hand every later track the previous track's clock, freezing the word sweep.
    val positionProvider: () -> Long = remember(positionState) { { positionState.longValue } }
    var currentLineIndex by remember(lyrics) { mutableIntStateOf(-1) }
    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)

    LaunchedEffect(entries, isSynced, isTtmlFormat, lyricsSyncOffset, disableAnimations) {
        if (!isSynced || entries.isEmpty()) return@LaunchedEffect
        val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS
        // A word-timed line needs a fine tick to light one word at a time; a line-synced one
        // changes state a few times a minute and a 50 ms poll is already far finer than it needs.
        val pollMs = if (isTtmlFormat) 16L else 50L
        while (isActive) {
            val raw = latestSliderPositionProvider.value() ?: player.currentPosition
            val shifted = (raw + lyricsSyncOffset.toLong()).coerceAtLeast(0L)
            positionState.longValue = (shifted + leadMs + VISUAL_TUNING_OFFSET_MS).coerceAtLeast(0L)
            currentLineIndex = findCurrentLineIndex(entries, positionState.longValue, 0L)
            delay(pollMs)
        }
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var manualUntilMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) {
        if (dragged) {
            manualUntilMs = Long.MAX_VALUE
        } else if (manualUntilMs == Long.MAX_VALUE) {
            // Only a RELEASE arms the timer. Arming it on every `dragged == false` would fire on
            // the first composition too, and hold the view off its own opening line for the first
            // four seconds after it is opened.
            manualUntilMs = System.currentTimeMillis() + MANUAL_SCROLL_HOLD_MS
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The active line is scrolled to the TOP of the list, and the list starts a third of the
        // way down the box — so "top of the list" reads as "a third of the way down the screen",
        // which is where SimpMusic anchors it. Doing it with padding rather than a scroll offset
        // keeps the first and last lines reachable.
        val topPad = maxHeight * 0.32f
        val bottomPad = maxHeight * 0.5f
        val density = LocalDensity.current

        var placed by remember(entries) { mutableStateOf(false) }
        // One long-lived collector instead of an effect per line change: animateScrollToItem's
        // default tween throws itself across the whole list and the effect restart killed it
        // mid-flight, which is what read as choppiness. A relative animateScrollBy moves one
        // line height per step; a jump too far to animate is taken instantly, so no frame ever
        // scrolls the whole song past the reader.
        LaunchedEffect(entries, lyricsScroll) {
            snapshotFlow { currentLineIndex }.collect { target ->
                // Auto-scroll off means the list stays where the reader put it, which is what the
                // setting promises in the other two renderers.
                if (!lyricsScroll) return@collect
                if (target !in entries.indices) return@collect
                if (System.currentTimeMillis() < manualUntilMs) return@collect
                if (!placed) {
                    listState.scrollToItem(target)
                    placed = true
                    return@collect
                }
                val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (visible == null || disableAnimations) {
                    listState.scrollToItem(target)
                } else {
                    // scrollToItem anchors the line at the content padding (the "a third down"
                    // position), so the relative scroll must converge on that same anchor.
                    val anchorPx = with(density) { topPad.roundToPx() }
                    val delta = visible.offset - anchorPx
                    if (delta == 0) return@collect
                    listState.animateScrollBy(
                        delta.toFloat(),
                        tween((120 + kotlin.math.abs(delta)).coerceAtMost(350), easing = FastOutSlowInEasing),
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad, start = 24.dp, end = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(entries) { index, entry ->
                SimpMusicLyricsLine(
                    entry = entry,
                    isCurrent = index == currentLineIndex,
                    baseSizeSp = lyricsTextSize,
                    currentColor = currentColor,
                    inactiveColor = inactiveColor,
                    disableAnimations = disableAnimations,
                    positionProvider = positionProvider,
                    romanizationPreferences = romanizationPreferences,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = lyricsClick && entry.time >= 0L) {
                                player.seekTo(entry.time)
                            }.padding(vertical = (lyricsLineSpacing / DEFAULT_LINE_SPACING * 12f).dp),
                )
            }
        }
    }
}

/**
 * One line. Dim and a size smaller until it is the one being sung; a word-timed line then lights
 * word by word instead of all at once.
 *
 * [positionProvider] rather than a position parameter: only a line that is actually word-timed and
 * actually current ever reads the playhead, so a tick never touches the rest of the list.
 */
@Composable
private fun SimpMusicLyricsLine(
    entry: LyricsEntry,
    isCurrent: Boolean,
    baseSizeSp: Float,
    currentColor: Color,
    inactiveColor: Color,
    disableAnimations: Boolean,
    positionProvider: () -> Long,
    romanizationPreferences: LyricsRomanizationPreferences,
    modifier: Modifier = Modifier,
) {
    val original = if (entry.isInstrumental) "♪" else entry.text
    if (original.isBlank()) return

    // Provider romanization under the same per-language switches Enhanced uses; when the line
    // has one it is displayed in place of the original, which is how SimpMusic Classic treats it.
    val romanized =
        remember(entry, romanizationPreferences) {
            providedRomanizedTextForEntry(entry, romanizationPreferences)
        }
    val displayText = romanized ?: original

    // Step-up between lines eases instead of snapping; the abrupt reflow was half of the
    // "choppy" feel, and SimpMusic animates both of these too.
    val sizeFraction by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.82f,
        animationSpec =
            if (disableAnimations) snap()
            else tween(220, easing = FastOutSlowInEasing),
        label = "simpMusicLineSize",
    )
    val lineColor by animateColorAsState(
        targetValue = if (isCurrent) currentColor else inactiveColor,
        animationSpec =
            if (disableAnimations) snap()
            else tween(220, easing = FastOutSlowInEasing),
        label = "simpMusicLineColor",
    )

    val style =
        MaterialTheme.typography.headlineMedium.copy(
            fontSize = (baseSizeSp * sizeFraction).sp,
            lineHeight = (baseSizeSp * sizeFraction).sp * 1.25f,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
        )

    val translation = if (isCurrent) providedTranslationTextForEntry(entry) else null

    val wordSynced = remember(entry) { hasTrueWordSync(entry) }
    if (!isCurrent || !wordSynced) {
        Column(modifier = modifier) {
            Text(
                text = displayText,
                style = style,
                color = lineColor,
            )
            if (translation != null) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = lineColor.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    val words = remember(entry) { entry.words.orEmpty().filter { it.text.isNotBlank() } }
    val romanizedWords =
        remember(entry, words, romanizationPreferences) {
            providedRomanizedWordsForEntry(entry, words.size, romanizationPreferences)
        }
    val displayedWords =
        romanizedWords?.mapIndexed { index, word -> word ?: words[index].text }
            ?: words.map { it.text }
    val sungThrough by remember(words) {
        derivedStateOf {
            val now = positionProvider()
            words.indexOfLast { (it.startTime * 1000.0).toLong() <= now }
        }
    }

    Column(modifier = modifier) {
        FlowRowWords(
            words = displayedWords,
            sungThrough = sungThrough,
            style = style,
            sungColor = currentColor,
            pendingColor = pendingWordColor(inactiveColor),
            modifier = Modifier.fillMaxWidth(),
        )
        if (translation != null) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyMedium,
                color = lineColor.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The words of the sung line, wrapping like a paragraph.
 *
 * [androidx.compose.foundation.layout.FlowRow] rather than one styled string: the sung/pending
 * split is per word and changes several times a second, and rebuilding an AnnotatedString for the
 * whole line on each of those was the expensive way to say the same thing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowWords(
    words: List<String>,
    sungThrough: Int,
    style: TextStyle,
    sungColor: Color,
    pendingColor: Color,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.forEachIndexed { index, word ->
            Text(
                text = word,
                style = style,
                color = if (index <= sungThrough) sungColor else pendingColor,
            )
        }
    }
}
