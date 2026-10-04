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
 * Bitchord player style — lyrics model + collapsed-player lyric strip.
 *
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord):
 *  - data/lyrics/LyricLine.kt (model, verbatim)
 *  - the lyric composables from ui/player/NowPlayingScreen.kt
 *    (rememberLyricClock, SweptLyricLine, glowAt, sweepTo, CurrentLyricLine,
 *    LyricsUnavailableLine, LyricsLoadingLine, keepScrollInList,
 *    bleedHorizontally, fadingEdges)
 *
 * Only the short wrapped lyric strip on the collapsed player lives here: it has no list around it,
 * which is what BitChord's character sweep was for. The full-screen lyrics window opens the app's
 * own LyricsV2 / LyricsEnhanced, so it follows the Lyrics settings like every other surface.
 *
 * Adaptations for ArchiveTune (documented inline):
 *  - a mapper from ArchiveTune's parsed lyrics ([LyricsEntry] with
 *    [WordTimestamp] word timings, seconds) into BitChord's [LyricLine]
 *    (milliseconds), including the background-vocal split.
 *  - `rememberIsForeground` (BitChord) is replaced by a Lifecycle RESUMED
 *    observation, same semantics.
 *  - AppSettings reads (reduceAnimation / reduceDynamicBlur) replaced with
 *    parameters/LocalAnimationsDisabled.
 *  - BitChord's per-lyric lyric-clock follows the player position; the entry
 *    composable passes position + isPlaying in.
 *
 * Belongs to the Bitchord player style, with one deliberate exception to the self-containment rule:
 * [SweptLyricLine], [rememberLyricClock] and [toBitChordLyrics]
 * are also drawn by SimpMusic's current-line band. That band highlighted whole lines while this one
 * swept word by word off the same timings, and a second copy of the sweep is a second thing to fix
 * every time a provider changes shape. Everything else here stays Bitchord's.
 */

package moe.rukamori.archivetune.ui.player.bitchord

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.normalizeLyricsText
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.romanizeLyricsLine
import moe.rukamori.archivetune.lyrics.LyricsUtils.shouldRomanizeLyricsLine
import moe.rukamori.archivetune.lyrics.LyricsUtils.shouldUseProvidedRomanization
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.ui.player.InlineLyricLinesPerPage
import moe.rukamori.archivetune.ui.player.InlineLyricTimedWord
import moe.rukamori.archivetune.ui.player.inlineLyricChunkIndexAt
import moe.rukamori.archivetune.ui.player.inlineLyricChunkText
import moe.rukamori.archivetune.ui.player.inlineLyricChunkWordText
import moe.rukamori.archivetune.ui.player.rememberMeasuredInlineLyricPages
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.abs

// ── Model (verbatim from BitChord's data/lyrics/LyricLine.kt) ─────────────────

/**
 * One word of a line, with the stretch of the song it is sung over.
 *
 * Apple's TTML splits long words into syllables; those are merged back into
 * whole words on the way in, so [startMs] is the first syllable's start and
 * [endMs] the last one's end. Whole words are what the sweep needs — a
 * highlight that ran across "e" and "nough" separately reads as a stutter.
 */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One synced line. [timeMs] is when it starts; a blank [text] is an instrumental stretch — LRC
 * files mark those with a bare timestamp.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: LyricLine? = null,
    // ArchiveTune additions (not in BitChord): the translation a provider or the
    // AI translator attached to this line, and the provider's own romanisation
    // of it. Drawn as small dim voices under the lead, mirroring how the other
    // lyrics surfaces in the app show them. Null for pure BitChord-style lyrics.
    val translation: String? = null,
    val providerRomanizedText: String? = null,
    val providerRomanizedLanguage: String? = null,
) {
    val isGap: Boolean get() = text.isEmpty()

    val isWordSynced: Boolean get() = words.isNotEmpty()

    /**
     * Whether anything actually told us when the singing stops, rather than
     * only when it starts. Word timings carry it, and so does a provider that
     * stamps the line's own end ([sungUntilMs]).
     */
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null

    /**
     * When the last word finishes — or the line's own end where the provider
     * gave one, or [timeMs] when nothing did.
     *
     * The answering vocal counts: it is still this line being sung, and it
     * regularly holds a note past the lead's last word.
     */
    val endMs: Long
        get() {
            val lead = words.lastOrNull()?.endMs ?: sungUntilMs ?: timeMs
            return maxOf(lead, background?.endMs ?: lead)
        }

    /**
     * How far through the line the singing has got, 0..1, as a fractional index into [text]. The
     * sweep reveals up to this character.
     */
    fun revealedChars(positionMs: Long): Float {
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f
        var offset = 0
        words.forEachIndexed { index, word ->
            // Where this word sits in [text]. Built by walking rather than
            // searching, so a word repeated in the line still lines up.
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = start + word.text.length
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) {
                val span = (word.endMs - word.startMs).coerceAtLeast(1L)
                val through = (positionMs - word.startMs).toFloat() / span
                return start + through * word.text.length
            }
            // Past this word: the trailing space fills over the pause before
            // the next one, so the highlight keeps creeping instead of resting
            // on the word's last letter.
            val next = words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val gapStart = text.indexOf(next.text, end).takeIf { it >= 0 } ?: end
                val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                val through = (positionMs - word.endMs).toFloat() / pause
                return end + through * (gapStart - end)
            }
            offset = end
        }
        return text.length.toFloat()
    }

    /** How much bloom the word being sung has earned, 0..1. */
    fun glowIntensity(positionMs: Long): Float {
        val word = words.firstOrNull { positionMs < it.endMs } ?: return 0f
        if (positionMs < word.startMs) return 0f

        val held = (word.endMs - word.startMs).coerceAtLeast(1L)
        val through = ((positionMs - word.startMs).toFloat() / held).coerceIn(0f, 1f)
        val envelope = when {
            through < GLOW_ATTACK -> through / GLOW_ATTACK
            through > 1f - GLOW_RELEASE -> (1f - through) / GLOW_RELEASE
            else -> 1f
        }
        val pace = ((held - GLOW_FAST_MS).toFloat() / (GLOW_SLOW_MS - GLOW_FAST_MS))
            .coerceIn(0f, 1f)
        return (GLOW_FLOOR + (1f - GLOW_FLOOR) * pace) * envelope.coerceIn(0f, 1f)
    }
}

/** A word this short is patter; it gets [GLOW_FLOOR] and no more. */
private const val GLOW_FAST_MS = 130L

/** A word held this long gets the full bloom. */
private const val GLOW_SLOW_MS = 800L

/** What the quickest words still get, so patter doesn't go completely flat. */
private const val GLOW_FLOOR = 0.22f

/** Share of a word's span spent coming up, and going back down. */
private const val GLOW_ATTACK = 0.18f
private const val GLOW_RELEASE = 0.38f

private const val STRIP_LINES = InlineLyricLinesPerPage

// ── Mapper from ArchiveTune's parsed lyrics ───────────────────────────────────

/**
 * Converts ArchiveTune's [LyricsEntry] list (times in ms, word timings in seconds) into BitChord's
 * [LyricLine] list (everything in ms), splitting the background-vocal words out into their own
 * answering line the way BitChord's TTML parser does.
 */
internal fun List<LyricsEntry>.toBitChordLyrics(): List<LyricLine> =
    map { entry ->
        val lead = mutableListOf<LyricWord>()
        val backing = mutableListOf<LyricWord>()
        entry.words.orEmpty().forEach { word ->
            // BitChord aligns each word against the (space-carrying) line text via indexOf and
            // derives its own gaps from that text, so drop the edge separator space the parsers now
            // preserve on word text — otherwise the last word of a line stops matching the trimmed
            // line text.
            val w =
                LyricWord(
                    startMs = (word.startTime * 1000.0).toLong(),
                    endMs = (word.endTime * 1000.0).toLong(),
                    text = word.text.trim(),
                )
            if (word.isBackground) backing += w else lead += w
        }
        val text = entry.text
        LyricLine(
            timeMs = entry.time,
            text = text,
            words = lead,
            sungUntilMs = entry.durationMs.takeIf { it > 0L }?.let { entry.time + it },
            background =
                backing
                    .takeIf { it.isNotEmpty() }
                    ?.let { words ->
                        // The answering line draws under the lead, sharing its
                        // bracket-stripped text composed from its own words.
                        LyricLine(
                            timeMs = words.first().startMs,
                            text = words.joinToString(" ") { w -> w.text },
                            words = words,
                        )
                    },
            translation = providedTranslationTextForEntry(entry),
            providerRomanizedText = entry.providerRomanizedText,
            providerRomanizedLanguage = entry.providerRomanizedLanguage,
        )
    }

// ── Full-format parsing (ArchiveTune addition) ────────────────────────────────

/**
 * The parsed lyrics for one track: BitChord's [LyricLine]s plus whether the source carried any
 * timing at all.
 */
internal class BitChordParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
)

/** Parses whatever lyrics string the lyrics table holds into [BitChordParsedLyrics]. */
internal fun parseBitChordLyrics(raw: String, durationSeconds: Int?): BitChordParsedLyrics? {
    val normalized = normalizeLyricsText(raw)
    if (normalized.isEmpty() || normalized == LyricsEntity.LYRICS_NOT_FOUND) return null

    val syncedEntries =
        when {
            isLineSyncedLrc(normalized) -> parseLyrics(normalized).takeIf { it.isNotEmpty() }
            isTtml(normalized) -> parseTtml(normalized, durationSeconds).takeIf { it.isNotEmpty() }
            else -> null
        }
    if (syncedEntries != null) return BitChordParsedLyrics(syncedEntries.toBitChordLyrics(), isSynced = true)

    // Plain, untimestamped text. Kept verbatim (blank lines dropped so the list
    // doesn't render a run of music-note gaps) with timeMs pinned at 0 — the
    // panel keys every timed behaviour off [isSynced] instead.
    val plainLines =
        normalized
            .lines()
            .map { line -> line.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .map { line -> LyricLine(timeMs = 0L, text = line) }
    if (plainLines.isEmpty()) return null
    return BitChordParsedLyrics(plainLines, isSynced = false)
}

private val WHITESPACE = Regex("\\s+")

// ── The lyric clock (verbatim from BitChord's NowPlayingScreen.kt) ────────────

/** The song position, ticking every frame. */
@Composable
internal fun rememberLyricClock(positionMs: Long, isPlaying: Boolean): MutableLongState {
    val clock = remember { mutableLongStateOf(positionMs) }
    // Gated on the app being on screen. The loop asks for a frame, writes a
    // value that invalidates a drawing, and is handed the next frame for it —
    // which is a request to render continuously for as long as it runs. That is
    // the right trade for a lyric being read and the wrong one for a phone in a
    // pocket, and the composition alone cannot tell the two apart.
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(positionMs, isPlaying, foreground) {
        clock.longValue = positionMs
        if (!isPlaying || !foreground) return@LaunchedEffect
        var previousFrame = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame ->
                clock.longValue += frame - previousFrame
                previousFrame = frame
            }
        }
    }
    return clock
}

// ── SweptLyricLine (verbatim) ─────────────────────────────────────────────────

/**
 * A lyric line with the sung part of it lit, the rest dimmed, and the boundary travelling across
 * the words in time with the vocal.
 */
@Composable
internal fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    // Carried by every copy: identical insets keep them laying out identically,
    // and the inset is what gives the blurred copy's layer somewhere to put the
    // halo. Sits inside the blur and outside the draw lambdas, so text-layout
    // coordinates and draw coordinates still agree.
    //
    // Off unless asked for. Only the full panel can afford it — it takes the
    // space back off its own row spacing and content padding.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    val sweep = Modifier.drawWithContent {
        val position = clock.longValue
        when {
            // Sung and done with: all of it is lit. Checked first so the lines
            // above and below the playing one — which are in this same state
            // for minutes at a time — cost a comparison per frame rather than
            // a walk of their words.
            position >= line.endMs -> drawContent()
            // Not started: nothing lit, the dim copy is the whole of it.
            position <= line.timeMs -> Unit
            else -> layout?.let { sweepTo(it, line.revealedChars(position)) }
        }
    }

    Box(modifier) {
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            minLines = minLines,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = room,
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = Color.White,
                minLines = minLines,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    // Read in the layer block rather than in composition: the
                    // intensity changes every frame, and this way only the
                    // layer's alpha is recomputed, not the line.
                    .graphicsLayer { alpha = glowAlpha * line.glowIntensity(clock.longValue) }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    // The band is masked with a DstIn gradient, which needs a
                    // layer of its own to erase into — against the backdrop it
                    // would take the artwork with it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Deliberately not the shared sweep: that lights
                        // everything sung so far, and this is only the front of
                        // it. No short-circuit either — the glow layer only
                        // exists for the line being sung, so it is one line's
                        // worth of arithmetic, not the whole panel's.
                        val measured = layout ?: return@drawWithContent
                        val position = clock.longValue
                        glowAt(
                            layout = measured,
                            revealedChars = line.revealedChars(position),
                            intensity = line.glowIntensity(position),
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            minLines = minLines,
            maxLines = maxLines,
            overflow = overflow,
            modifier = room.then(sweep),
        )
    }
}

/**
 * Draws this text clipped to a band trailing the sweep's leading edge — the word being sung,
 * roughly, rather than the whole of what has been.
 */
private fun ContentDrawScope.glowAt(
    layout: TextLayoutResult,
    revealedChars: Float,
    intensity: Float,
) {
    val length = layout.layoutInput.text.length
    if (length == 0 || revealedChars <= 0f || intensity <= 0f) return

    val edge = revealedChars.coerceIn(0f, length.toFloat())
    val visualLine = layout.getLineForOffset(edge.toInt().coerceIn(0, length - 1))
    val lineStart = layout.getLineStart(visualLine)
    val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)

    val right = horizontalAt(layout, edge.coerceIn(lineStart.toFloat(), lineEnd.toFloat()), lineStart, lineEnd)
    val trail = GLOW_TRAIL.toPx() * (GLOW_TRAIL_FLOOR + (1f - GLOW_TRAIL_FLOOR) * intensity)
    val left = (right - trail).coerceAtLeast(layout.getLineLeft(visualLine))
    if (right <= left) return

    // The band, cut out of the line. This is only the vertical and trailing
    // bounds; how it fades across is the mask below.
    clipRect(
        left = left,
        top = layout.getLineTop(visualLine),
        right = right,
        bottom = layout.getLineBottom(visualLine),
    ) {
        this@glowAt.drawContent()
    }

    // Full strength at the leading edge, ebbing away behind it. Without this
    // the band has a hard back edge, and a hard edge travelling along at a
    // constant distance behind the sweep is exactly what reads as a fixed-width
    // block of light being dragged across the words.
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.45f to Color.White.copy(alpha = 0.22f),
            1f to Color.White,
            startX = left,
            endX = right,
        ),
        blendMode = BlendMode.DstIn,
    )
}

/** Where a fractional character index sits across a visual line, in pixels. */
private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    lineStart: Int,
    lineEnd: Int,
): Float {
    val index = chars.toInt().coerceIn(lineStart, lineEnd)
    val here = layout.getHorizontalPosition(index, usePrimaryDirection = true)
    val next = layout.getHorizontalPosition(
        (index + 1).coerceAtMost(lineEnd),
        usePrimaryDirection = true,
    )
    return here + (next - here) * (chars - index)
}

/** Draws this text clipped to its first [revealedChars] characters. */
private fun ContentDrawScope.sweepTo(layout: TextLayoutResult, revealedChars: Float) {
    if (revealedChars <= 0f) return
    if (revealedChars >= layout.layoutInput.text.length) {
        drawContent()
        return
    }
    for (visualLine in 0 until layout.lineCount) {
        val start = layout.getLineStart(visualLine)
        // Lines beyond the boundary have nothing lit on them, and neither has
        // anything after them.
        if (revealedChars <= start) return
        val end = layout.getLineEnd(visualLine, visibleEnd = true)
        val isRtl = layout.getParagraphDirection(start) == ResolvedTextDirection.Rtl
        val edge = if (revealedChars >= end) {
            if (isRtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        } else {
            horizontalAt(layout, revealedChars, start, end)
        }
        clipRect(
            left = if (isRtl) edge else layout.getLineLeft(visualLine),
            top = layout.getLineTop(visualLine),
            right = if (isRtl) layout.getLineRight(visualLine) else edge,
            bottom = layout.getLineBottom(visualLine),
        ) {
            this@sweepTo.drawContent()
        }
    }
}

/** The single lyric line above the scrubber. */
@Composable
internal fun CurrentLyricLine(
    lines: List<LyricLine>,
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    synced: Boolean = true,
) {
    val clock = rememberLyricClock(positionMs, isPlaying)

    val index by remember(lines, synced) {
        derivedStateOf {
            if (!synced) 0 else lines.indexOfLast { it.timeMs <= clock.longValue }
        }
    }
    val current = lines.getOrNull(index)
    // Before the first line, and through instrumental breaks, show the note.
    val instrumental = synced && (current == null || current.isGap)
    // Everything ahead of the first sung line is the intro — LRC files open on a
    // bare [00:00.00] gap, so that stretch is gap lines rather than nothing.
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    val intro = instrumental && firstSung >= 0 && index < firstSung
    // The intro gets one of the slang lines; mid-song breaks stay plain.
    val introLine = remember(trackKey) { INTRO_LINES.random() }
    // The strip is a couple of wrapped lines and switches the moment the next one is due, so
    // the answering vocal — where there is one — has nowhere to go: showing
    // it would mean either cutting it short when the next line arrives or
    // holding the strip back and leaving a gap before the next line's own
    // words appear. [LyricsPanel] has the room to draw it properly; here it
    // is simply left off, same as before this line had a bracket in it.
    val text = when {
        intro -> introLine
        instrumental -> INSTRUMENTAL_MARK
        else -> current!!.text
    }
    val lyricStyle = MaterialTheme.typography.titleMedium
    val swept = current?.takeIf { !instrumental && it.isWordSynced }
    val timedWords =
        remember(swept?.words) {
            swept?.words.orEmpty().map { word ->
                InlineLyricTimedWord(
                    text = word.text,
                    startMs = word.startMs,
                    endMs = word.endMs,
                )
            }
        }
    val cueStartMs = current?.timeMs ?: 0L
    val cueEndMs =
        lines.getOrNull(index + 1)?.timeMs
            ?: current?.sungUntilMs?.takeIf { it > cueStartMs }
            ?: current?.words?.lastOrNull()?.endMs?.takeIf { it > cueStartMs }
            ?: durationMs.takeIf { it > cueStartMs }
            ?: cueStartMs + 4_000L

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .graphicsLayer {
                if (!synced || instrumental) {
                    // Nothing is being sung (or nothing is timed); hold it
                    // steady rather than fading.
                    alpha = 0.5f
                    return@graphicsLayer
                }
                val start = lines.getOrNull(index)?.timeMs ?: 0L
                val end = lines.getOrNull(index + 1)?.timeMs
                    ?: durationMs.takeIf { it > start }
                    ?: (start + 4_000L)
                val fade = ((end - start) * LYRIC_FADE_FRACTION)
                    .coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                val remaining = (end - clock.longValue).toFloat()
                alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
            },
    ) {
        if (instrumental) {
            Icon(
                imageVector = BitChordIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val pages =
                rememberMeasuredInlineLyricPages(
                    text = text,
                    words = timedWords,
                    linesPerPage = InlineLyricLinesPerPage,
                    cueStartMs = cueStartMs,
                    cueEndMs = cueEndMs,
                    style = lyricStyle,
                    maxWidth = maxWidth,
                )
            val chunk = pages.chunks.getOrNull(inlineLyricChunkIndexAt(pages.chunks, clock.longValue))
            if (chunk != null) {
                val chunkText = inlineLyricChunkText(text, chunk)
                if (swept != null) {
                    val pageWords =
                        pages.wordRanges.mapNotNull { wordRange ->
                            val word = swept.words.getOrNull(wordRange.wordIndex) ?: return@mapNotNull null
                            val pageWordText = inlineLyricChunkWordText(text, chunk, wordRange) ?: return@mapNotNull null
                            word.copy(text = pageWordText)
                        }
                    SweptLyricLine(
                        line = swept.copy(
                            timeMs = chunk.startMs,
                            text = chunkText,
                            words = pageWords,
                        ),
                        clock = clock,
                        style = lyricStyle,
                        dimAlpha = UNSUNG_ALPHA_STRIP,
                        minLines = STRIP_LINES,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = chunkText,
                        style = lyricStyle,
                        color = Color.White,
                        minLines = STRIP_LINES,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        // Disclosure hint: this strip opens the full lyrics screen.
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Stands in for [CurrentLyricLine] once a lookup has come back empty — shown for a few seconds so
 * it registers, then left to fade rather than snapping out or lingering for the rest of the track.
 */
@Composable
internal fun LyricsUnavailableLine(
    trackKey: Any,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var visible by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        delay(LYRICS_UNAVAILABLE_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.55f else 0f,
        animationSpec = tween(durationMillis = LYRICS_UNAVAILABLE_FADE_MS),
        label = "lyricsUnavailableAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "Lyrics not available",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            minLines = STRIP_LINES,
            maxLines = STRIP_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .graphicsLayer { this.alpha = alpha },
        )
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            // Same disclosure hint the loaded strip carries.
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Stands in for [CurrentLyricLine] while a lookup is still in flight. */
@Composable
internal fun LyricsLoadingLine(
    trackKey: Any,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val text = remember(trackKey) { LYRICS_LOADING_LINES.random() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.55f),
            minLines = STRIP_LINES,
            maxLines = STRIP_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            // Same disclosure hint the loaded strip carries.
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ── List plumbing (verbatim) ──────────────────────────────────────────────────

/**
 * Swallows whatever scroll the queue list itself didn't use. The player sits in a bottom sheet, and
 * the sheet's own nested-scroll handler reads that leftover as "drag me down" — so scrolling the
 * list would slide the player away. Consuming it here keeps the gesture inside the list.
 */
internal fun keepScrollInList(listState: LazyListState) = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = available

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (available.y > 0f && !listState.canScrollBackward) available else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * Measure a child wider than its slot by [gutter] on each side and place it back over that margin,
 * still reporting the original width to the parent.
 */
internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) {
        placeable.place(-(placeable.width - width) / 2, 0)
    }
}

/** Softens the list where it meets the header and the scrubber. */
internal fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 28.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = fade,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
