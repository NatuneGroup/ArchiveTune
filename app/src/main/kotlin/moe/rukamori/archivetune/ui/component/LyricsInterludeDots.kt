/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Technique adapted from amll-dev/applemusic-like-lyrics (AGPL-3.0-only),
 * technique-source: packages/core/src/lyric-player/base/interlude-dots.ts
 * (`InterludeDotsBase` — a three-dot interlude performance: container
 * breathes on a ~4s cycle while the dots light up in staggered sequence with
 * an eased enter, then the whole thing exits with a quick swell-and-fade).
 * IDEA PORT ONLY — no upstream code is copied here; the mapping below is our
 * own Kotlin/Compose implementation of the idea: a breathing container scale
 * plus three dots pulsing in staggered sequence next to the instrumental note.
 *
 * 60fps design: one shared `rememberInfiniteTransition` drives all four
 * values; every value is read ONLY inside `graphicsLayer` lambdas, so frames
 * update render-node properties without recomposition, and no object is
 * allocated per frame (dot size, spacing and colors are fixed modifiers).
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Upstream breathes on a ~4s cycle; faster here so short breaks still read. */
private const val BREATH_MS = 2400

/** Per-dot pulse length with a stagger so the dots light up in sequence. */
private const val DOT_PULSE_MS = 900
private const val DOT_STAGGER_MS = 220

private const val DOT_INACTIVE_ALPHA = 0.25f
private const val DOT_ACTIVE_ALPHA = 1f
private const val BREATH_MIN_SCALE = 0.92f
private const val BREATH_MAX_SCALE = 1.08f

/**
 * Three breathing dots for instrumental breaks. Draw-phase animation only:
 * safe to keep composed for the whole interlude with no per-frame cost beyond
 * layer-property updates.
 */
@Composable
fun InterludeDots(
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "v2InterludeDots")
    val breath by transition.animateFloat(
        initialValue = BREATH_MIN_SCALE,
        targetValue = BREATH_MAX_SCALE,
        animationSpec =
            infiniteRepeatable(
                animation = tween(BREATH_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "v2InterludeBreath",
    )
    Row(
        modifier =
            modifier.graphicsLayer {
                scaleX = breath
                scaleY = breath
            },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val dotAlpha by transition.animateFloat(
                initialValue = DOT_INACTIVE_ALPHA,
                targetValue = DOT_ACTIVE_ALPHA,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(DOT_PULSE_MS, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * DOT_STAGGER_MS),
                    ),
                label = "v2InterludeDot$index",
            )
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = dotAlpha }
                        .background(color = textColor, shape = CircleShape),
            )
        }
    }
}
