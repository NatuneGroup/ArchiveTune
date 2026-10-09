/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Technique adapted from amll-dev/applemusic-like-lyrics (AGPL-3.0-only),
 * technique-source files:
 *   packages/core/src/lyric-player/dom/animation/mask/animator-web.ts
 *   (`WebMaskAnimator` — a static fade gradient whose *position* slides), and
 *   packages/core/src/lyric-player/dom/animation/mask/utils.ts
 *   (`generateFadeGradient` — the bright/dark two-stop fade window).
 * IDEA PORT ONLY — no upstream code is copied here; the mapping below is our
 * own Kotlin/Compose implementation of the idea: one static gradient window
 * plus a sliding reveal position, so partially-sung words render
 * partially-lit instead of flipping all-or-nothing.
 *
 * 60fps design: the gradient brush is canonical (0..1) and remembered once —
 * only float geometry moves per frame. Progress is read inside the draw scope
 * (redraw, not recomposition), and the whole mask is three `drawRect`s
 * (opaque / gradient / erase) with `BlendMode.DstIn`: zero per-frame
 * allocation, zero per-frame recomposition from this modifier.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp

/**
 * Sliding soft-reveal mask for the sung-text overlay.
 *
 * Must sit inside a `graphicsLayer { compositingStrategy = Offscreen }` —
 * `DstIn` composites against what is already drawn in the layer.
 *
 * @param progressProvider sung fraction 0..1. Read in the draw phase so the
 * animating word invalidates draw only, never recomposition.
 * @param edgeWidth full fade width is `2 * edgeWidth`, centred on the reveal
 * position — the Compose analogue of upstream's `fadeWidth` window.
 */
@Composable
fun Modifier.slidingSoftReveal(
    progressProvider: () -> Float,
    edgeWidth: Dp,
    isRtl: Boolean,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    // Canonical 1px-wide fade window; mapped onto the live edge position with
    // withTransform below so this brush is built exactly once per direction.
    val fadeBrush =
        remember(isRtl) {
            Brush.horizontalGradient(
                colors =
                    if (isRtl) {
                        listOf(Color.Transparent, Color.Black)
                    } else {
                        listOf(Color.Black, Color.Transparent)
                    },
                startX = 0f,
                endX = 1f,
            )
        }
    return this.drawWithContent {
        drawContent()
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@drawWithContent
        val edgePx = edgeWidth.toPx().coerceAtLeast(0f)
        val progress = progressProvider().coerceIn(0f, 1f)
        val center = if (isRtl) width * (1f - progress) else width * progress
        if (edgePx <= 0f) {
            // Degenerate edge: binary split, still allocation-free.
            if (isRtl) {
                if (center > 0f) {
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset.Zero,
                        size = Size(center, height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            } else {
                if (center < width) {
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(center, 0f),
                        size = Size(width - center, height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            return@drawWithContent
        }
        if (!isRtl) {
            // Sung side: keep fully lit.
            val litRight = center - edgePx
            if (litRight > 0f) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset.Zero,
                    size = Size(litRight.coerceAtMost(width), height),
                    blendMode = BlendMode.DstIn,
                )
            }
            // Fade window [center - edge, center + edge].
            withTransform(
                transformBlock = {
                    translate(left = center - edgePx, top = 0f)
                    scale(scaleX = edgePx * 2f, scaleY = 1f, pivot = Offset.Zero)
                },
                drawBlock = {
                    drawRect(
                        brush = fadeBrush,
                        topLeft = Offset.Zero,
                        size = Size(1f, height),
                        blendMode = BlendMode.DstIn,
                    )
                },
            )
            // Unsung side: erase.
            val clearLeft = center + edgePx
            if (clearLeft < width) {
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(clearLeft.coerceAtLeast(0f), 0f),
                    size = Size(width - clearLeft.coerceAtLeast(0f), height),
                    blendMode = BlendMode.DstIn,
                )
            }
        } else {
            // Mirrored: the sung side grows from the right.
            val clearRight = center - edgePx
            if (clearRight > 0f) {
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset.Zero,
                    size = Size(clearRight.coerceAtMost(width), height),
                    blendMode = BlendMode.DstIn,
                )
            }
            withTransform(
                transformBlock = {
                    translate(left = center - edgePx, top = 0f)
                    scale(scaleX = edgePx * 2f, scaleY = 1f, pivot = Offset.Zero)
                },
                drawBlock = {
                    drawRect(
                        brush = fadeBrush,
                        topLeft = Offset.Zero,
                        size = Size(1f, height),
                        blendMode = BlendMode.DstIn,
                    )
                },
            )
            val litLeft = center + edgePx
            if (litLeft < width) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(litLeft.coerceAtLeast(0f), 0f),
                    size = Size(width - litLeft.coerceAtLeast(0f), height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}
