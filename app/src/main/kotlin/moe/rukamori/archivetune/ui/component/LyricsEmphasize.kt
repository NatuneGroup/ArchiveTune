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
 * IDEA PORT ONLY — no upstream code is copied here; the mapping below is our
 * own Kotlin/Compose implementation of the idea:
 *  - the 32 discrete keyframes become ONE continuous Animatable per character
 *    (0 -> 1, linear), and the bump easing becomes `sin(pi * x)` evaluated in
 *    the draw phase — same rise-peak-settle shape, zero keyframe objects;
 *  - the staggered per-character delays become tween delays scaled by
 *    character position (`du / 2.5 / charCount * index`);
 *  - the last-word boost (stronger glow, longer run) is kept as a multiplier.
 *
 * 60fps design: the overlay is per-character Texts with a FIXED remembered
 * glow style (built once per word). Every per-frame value (bump alpha, scale,
 * rise, push-apart) is read from an Animatable ONLY inside `graphicsLayer`
 * lambdas — draw-phase invalidation, never recomposition — and no object is
 * allocated per frame. Only the last word of the active line pays for this
 * (a handful of small Texts); every other word renders exactly as before.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.lyrics.WordTimestamp
import kotlin.math.PI
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

/**
 * Per-character glow + float overlay for the last word of the active line.
 *
 * Replaces the single whole-word lit overlay while the word is sung: each
 * character swells, glows and rises in sequence, then settles back to the
 * plain lit look (bump returns to 0, so finished words never keep glowing).
 * When the word is complete/past, every bump rests at 1 -> sin(pi) = 0 and
 * the overlay is pixel-equivalent to the normal lit text.
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
    val chars = remember(word.text) { word.text.toList().map { it.toString() } }
    if (chars.isEmpty()) return
    val wordDurationMs =
        ((word.endTime - word.startTime) * 1000.0).toLong().coerceAtLeast(1L)
    val params = remember(wordDurationMs, chars.size) {
        emphasizeParams(wordDurationMs, chars.size)
    }
    val density = LocalDensity.current
    val fontPx = remember(density, fontSizeSp) {
        with(density) { fontSizeSp.sp.toPx() }
    }
    val floatAmpPx = 0.05f * fontPx * (if (isBackground) 2f else 1f)
    val glowAlphaMax = (0.55f * glowFactor).coerceIn(0f, 1f)
    val glowBlurPx = (fontPx * 0.3f * params.blur).coerceAtLeast(1f)
    val glowStyle =
        remember(baseTextStyle, textColor, glowAlphaMax, glowBlurPx) {
            baseTextStyle.copy(
                shadow =
                    Shadow(
                        color = Color.White.copy(alpha = glowAlphaMax),
                        offset = Offset.Zero,
                        blurRadius = glowBlurPx,
                    ),
            )
        }
    // One bump driver per character; values are read ONLY in graphicsLayer.
    val bumps = remember(word.text) { List(chars.size) { Animatable(0f) } }
    LaunchedEffect(isWordActive, isWordComplete, word.text) {
        if (isWordActive) {
            // Staggered one-shot bumps, earliest char first.
            chars.indices.forEach { i ->
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
    Row(modifier = modifier) {
        chars.forEachIndexed { i, ch ->
            val bump = bumps[i]
            androidx.compose.foundation.layout.Box(
                modifier =
                    Modifier.graphicsLayer {
                        // Bump curve: 0 -> 1 -> 0 across the char animation.
                        val t = sin(bump.value * PI).toFloat()
                        val s = 1f + t * 0.1f * params.amount * bounceFactor
                        scaleX = s
                        scaleY = s
                        translationY = -t * floatAmpPx * bounceFactor
                        // Gentle push-apart from the word centre, like upstream.
                        translationX =
                            -t * 0.03f * fontPx * params.amount *
                                (chars.size / 2f - i) * bounceFactor
                    },
            ) {
                Text(text = ch, style = baseTextStyle, color = litColor)
                Text(
                    text = ch,
                    style = glowStyle,
                    color = litColor,
                    modifier =
                        Modifier.graphicsLayer {
                            val t = sin(bump.value * PI).toFloat()
                            alpha = t
                        },
                )
            }
        }
    }
}
