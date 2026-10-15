/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Technique adapted from amll-dev/applemusic-like-lyrics (AGPL-3.0-only),
 * technique-source: packages/core/src/lyric-player/dom/animation/emphasize/index.ts
 * (`createEmphasizeAnimation` — per-character staggered glow + float, with a
 * stronger longer variant for the LAST word of a line, built from 32 WAAPI
 * keyframes driven by a bump easing that peaks mid-animation).
 *
 * PROVENANCE: this is a PORT OF THE PARAMETERS, not an idea-only port. The
 * 1000 ms floor, the piecewise /2000 and /3000 cubic-vs-sqrt growth, the 0.6
 * and 0.5 base weights, the 1.6/1.5/1.2 last-word boosts, the 1.2/0.8 caps and
 * the /2.5 stagger below are all taken from upstream's `calculateEmphasizeParams`.
 * Only the Compose translation (one continuous Animatable replacing 32 WAAPI
 * keyframes) is ours. Upstream is AGPL-3.0-only — see docs/CREDITS.md for the
 * attribution and the open license question the owner must decide.
 *
 * 60fps design: the word is laid out ONCE with the same TextStyle as the dim
 * base Text underneath, and each grapheme cluster is drawn as a clipped column
 * of that single layout, so kerning, ligatures and the overall width match the
 * base exactly. Every per-frame value (bump alpha, scale, rise, push-apart) is
 * read from an Animatable ONLY inside the draw lambda — draw-phase
 * invalidation, never recomposition — and no object is allocated per frame.
 * Only the last word of the active line pays for this; every other word
 * renders exactly as before.
 */

package moe.rukamori.archivetune.ui.component

import android.icu.text.BreakIterator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.lyrics.WordTimestamp
import java.util.EnumSet
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Last-word boost factors (upstream amplifies the final word of a line). */
private const val LAST_WORD_AMOUNT_BOOST = 1.6f
private const val LAST_WORD_BLUR_BOOST = 1.5f
private const val LAST_WORD_DURATION_BOOST = 1.2f
private const val MIN_EMPHASIZE_DURATION_MS = 1000L
private const val STAGGER_DIVISOR = 2.5f

/** Precomputed per-word emphasize parameters. Pure; O(1). */
private data class EmphasizeParams(
    val amount: Float,
    val blur: Float,
    val charDurationMs: Int,
    val staggerMs: Long,
)

/**
 * Port of the upstream parameter mapping: longer words get a stronger effect
 * (sub-linearly), clamped so the boost never blows out the line.
 */
private fun emphasizeParams(wordDurationMs: Long, charCount: Int): EmphasizeParams {
    val du = maxOf(MIN_EMPHASIZE_DURATION_MS, wordDurationMs)
    val anchors = charCount.coerceAtLeast(1)
    var amount = (du / 2000f).let { if (it > 1f) sqrt(it) else it * it * it }
    var blur = (du / 3000f).let { if (it > 1f) sqrt(it) else it * it * it }
    amount = (amount * 0.6f * LAST_WORD_AMOUNT_BOOST).coerceAtMost(1.2f)
    blur = (blur * 0.5f * LAST_WORD_BLUR_BOOST).coerceAtMost(0.8f)
    val boosted = (du * LAST_WORD_DURATION_BOOST).toInt().coerceAtLeast(1)
    return EmphasizeParams(
        amount = amount,
        blur = blur,
        charDurationMs = boosted,
        staggerMs = (boosted / STAGGER_DIVISOR / anchors).toLong().coerceAtLeast(0L),
    )
}

// Scripts whose characters render the same whether or not their neighbours are
// drawn with them. Everything else (Arabic joining, Hebrew, Indic conjuncts and
// matras, Thai/Lao/Khmer stacking, ...) is shaped across characters, so cutting
// the word into separately moving pieces would tear the glyphs apart.
private val GRAPHEME_SAFE_SCRIPTS: Set<Character.UnicodeScript> =
    EnumSet.of(
        Character.UnicodeScript.COMMON,
        Character.UnicodeScript.INHERITED,
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.ARMENIAN,
        Character.UnicodeScript.GEORGIAN,
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
    )

/** A bump below this is drawn as the resting (plain lit) word. */
private const val BUMP_REST_EPSILON = 0.001f

/**
 * Whether [text] can take the per-grapheme emphasize overlay. Right-to-left
 * text and complex-shaping scripts must keep the whole-word lit overlay.
 */
fun supportsGraphemeEmphasis(text: String): Boolean {
    if (text.isEmpty()) return false
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            -> return false
        }
        if (Character.UnicodeScript.of(codePoint) !in GRAPHEME_SAFE_SCRIPTS) return false
        i += Character.charCount(codePoint)
    }
    return true
}

/** Start offsets of each user-perceived character (emoji sequences, combining marks). */
private fun graphemeStarts(text: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val starts = ArrayList<Int>()
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE && boundary < text.length) {
        starts.add(boundary)
        boundary = iterator.next()
    }
    return starts.toIntArray()
}

private fun bumpCurve(value: Float): Float = sin(value * PI).toFloat()

private class EmphasizeLayout(
    val maxWidth: Int,
    val word: TextLayoutResult,
    // Null when the word wrapped: grapheme columns are only meaningful on one line.
    val glyphs: Array<TextLayoutResult>?,
    val edges: FloatArray,
    val glyphTops: FloatArray,
)

private fun measureEmphasizeLayout(
    measurer: TextMeasurer,
    text: String,
    starts: IntArray,
    style: TextStyle,
    glowStyle: TextStyle,
    maxWidth: Int,
    layoutDirection: LayoutDirection,
    density: Density,
): EmphasizeLayout {
    val word =
        measurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = maxWidth),
            layoutDirection = layoutDirection,
            density = density,
        )
    if (word.lineCount != 1) {
        return EmphasizeLayout(maxWidth, word, null, FloatArray(0), FloatArray(0))
    }
    val count = starts.size
    val edges =
        FloatArray(count + 1) { i ->
            word.getHorizontalPosition(
                offset = if (i < count) starts[i] else text.length,
                usePrimaryDirection = true,
            )
        }
    val glyphTops = FloatArray(count)
    val glyphs =
        Array(count) { i ->
            val end = if (i + 1 < count) starts[i + 1] else text.length
            val glyph =
                measurer.measure(
                    text = text.substring(starts[i], end),
                    style = glowStyle,
                    layoutDirection = layoutDirection,
                    density = density,
                )
            glyphTops[i] = word.firstBaseline - glyph.firstBaseline
            glyph
        }
    return EmphasizeLayout(maxWidth, word, glyphs, edges, glyphTops)
}

/**
 * Draws the word as one clipped column of the shared layout per grapheme, so a
 * resting word is exactly the whole-word layout the dim base Text draws. The
 * glow copy is a standalone layout of that grapheme, because clipping the
 * shared layout would also clip the blur at the column edges.
 */
private fun DrawScope.drawEmphasizedWord(
    layout: EmphasizeLayout,
    bumps: List<Animatable<Float, AnimationVector1D>>,
    params: EmphasizeParams,
    litColor: Color,
    fontPx: Float,
    floatAmpPx: Float,
    bounceFactor: Float,
) {
    val glyphs = layout.glyphs
    if (glyphs == null) {
        drawText(layout.word, color = litColor)
        return
    }
    val count = glyphs.size
    var resting = true
    for (i in 0 until count) {
        if (bumpCurve(bumps[i].value) > BUMP_REST_EPSILON) {
            resting = false
            break
        }
    }
    if (resting) {
        drawText(layout.word, color = litColor)
        return
    }
    val height = size.height
    // Glyph ink can extend past the advance (italics, swashes); the outer
    // columns must not crop it.
    val overhang = size.width + height
    for (i in 0 until count) {
        val t = bumpCurve(bumps[i].value)
        val left = layout.edges[i]
        val right = layout.edges[i + 1]
        val clipLeft = if (i == 0) left - overhang else left
        val clipRight = if (i == count - 1) right + overhang else right
        if (t <= BUMP_REST_EPSILON) {
            clipRect(clipLeft, -height, clipRight, height * 2f) {
                drawText(layout.word, color = litColor)
            }
            continue
        }
        val grow = 1f + t * 0.1f * params.amount * bounceFactor
        withTransform({
            // Gentle push-apart from the word centre, like upstream.
            translate(
                left = -t * 0.03f * fontPx * params.amount * (count / 2f - i) * bounceFactor,
                top = -t * floatAmpPx * bounceFactor,
            )
            scale(scaleX = grow, scaleY = grow, pivot = Offset((left + right) / 2f, height / 2f))
        }) {
            clipRect(clipLeft, -height, clipRight, height * 2f) {
                drawText(layout.word, color = litColor)
            }
            val glowAlpha = (t * 255f).roundToInt()
            if (glowAlpha > 0) {
                val canvas = drawContext.canvas.nativeCanvas
                val saveCount =
                    canvas.saveLayerAlpha(
                        left - overhang,
                        -height,
                        right + overhang,
                        height * 2f,
                        glowAlpha,
                    )
                drawText(glyphs[i], color = litColor, topLeft = Offset(left, layout.glyphTops[i]))
                canvas.restoreToCount(saveCount)
            }
        }
    }
}

/**
 * Per-grapheme glow + float overlay for the last word of the active line.
 *
 * Replaces the single whole-word lit overlay while the word is sung: each
 * grapheme swells, glows and rises in sequence, then settles back to the
 * plain lit look (bump returns to 0, so finished words never keep glowing).
 * When the word is complete/past, every bump rests at 1 -> sin(pi) = 0 and
 * the overlay is pixel-equivalent to the normal lit text.
 *
 * Callers must check [supportsGraphemeEmphasis] first and use the whole-word
 * overlay when it is false.
 */
@Composable
fun EmphasizedWordOverlay(
    word: WordTimestamp,
    baseTextStyle: TextStyle,
    textColor: Color,
    litAlpha: Float,
    fontSizeSp: Float,
    isBackground: Boolean,
    isWordActive: Boolean,
    isWordComplete: Boolean,
    bounceFactor: Float,
    glowFactor: Float,
    modifier: Modifier = Modifier,
) {
    val text = word.text
    val starts = remember(text) { graphemeStarts(text) }
    val count = starts.size
    if (count == 0) return
    val wordDurationMs =
        ((word.endTime - word.startTime) * 1000.0).toLong().coerceAtLeast(1L)
    val params = remember(wordDurationMs, count) {
        emphasizeParams(wordDurationMs, count)
    }
    val density = LocalDensity.current
    val fontPx = remember(density, fontSizeSp) {
        with(density) { fontSizeSp.sp.toPx() }
    }
    val floatAmpPx = 0.05f * fontPx * (if (isBackground) 2f else 1f)
    val glowAlphaMax = (0.55f * glowFactor).coerceIn(0f, 1f)
    val glowBlurPx = (fontPx * 0.3f * params.blur).coerceAtLeast(1f)
    val glowStyle =
        remember(baseTextStyle, glowAlphaMax, glowBlurPx) {
            baseTextStyle.copy(
                shadow =
                    Shadow(
                        color = Color.White.copy(alpha = glowAlphaMax),
                        offset = Offset.Zero,
                        blurRadius = glowBlurPx,
                    ),
            )
        }
    // One bump driver per grapheme; values are read ONLY in the draw lambda.
    val bumps = remember(text) { List(count) { Animatable(0f) } }
    LaunchedEffect(isWordActive, isWordComplete, text) {
        if (isWordActive) {
            // Staggered one-shot bumps, earliest grapheme first.
            for (i in 0 until count) {
                launch {
                    val wait = params.staggerMs * i
                    if (wait > 0) delay(wait)
                    bumps[i].animateTo(
                        1f,
                        tween(durationMillis = params.charDurationMs, easing = LinearEasing),
                    )
                }
            }
        } else if (isWordComplete) {
            bumps.forEach { it.snapTo(1f) }
        } else {
            bumps.forEach { it.snapTo(0f) }
        }
    }
    val litColor = textColor.copy(alpha = litAlpha)
    // The measurer's own cache would only duplicate the holder below.
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val layoutHolder = remember(text, baseTextStyle, glowStyle) { arrayOfNulls<EmphasizeLayout>(1) }
    Spacer(
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val cached = layoutHolder[0]
                    val measured =
                        if (cached != null && cached.maxWidth == constraints.maxWidth) {
                            cached
                        } else {
                            measureEmphasizeLayout(
                                measurer = textMeasurer,
                                text = text,
                                starts = starts,
                                style = baseTextStyle,
                                glowStyle = glowStyle,
                                maxWidth = constraints.maxWidth,
                                layoutDirection = layoutDirection,
                                density = this,
                            ).also { layoutHolder[0] = it }
                        }
                    val width = constraints.constrainWidth(measured.word.size.width)
                    val height = constraints.constrainHeight(measured.word.size.height)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }.drawBehind {
                    val measured = layoutHolder[0] ?: return@drawBehind
                    drawEmphasizedWord(
                        layout = measured,
                        bumps = bumps,
                        params = params,
                        litColor = litColor,
                        fontPx = fontPx,
                        floatAmpPx = floatAmpPx,
                        bounceFactor = bounceFactor,
                    )
                },
    )
}
