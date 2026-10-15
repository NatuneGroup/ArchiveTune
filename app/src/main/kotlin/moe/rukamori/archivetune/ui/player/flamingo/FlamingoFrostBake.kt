/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player.flamingo

import kotlin.math.roundToInt

/**
 * The geometry of one frost bake: how far to shrink the captured canvas still before blurring it
 * so the bake, drawn stretched over the frost band, reads as the same blur the old runtime-blurred
 * video copy applied. A bake pixel spans `frostWidth / bakeWidth` display pixels, so a radius of
 * [FlamingoFrostBakePlan.blurRadiusPx] baked pixels displays as
 * `blurRadiusPx * frostWidth / bakeWidth` — solving that for the bake width keeps the displayed
 * radius at the display intent even though the CPU helper clamps its radius.
 */
data class FlamingoFrostBakePlan(
    val blurRadiusPx: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bakeWidth: Int,
    val bakeHeight: Int,
) {
    val needsDownscale: Boolean
        get() = bakeWidth < sourceWidth || bakeHeight < sourceHeight
}

/**
 * Works out the bake for a captured still of [sourceWidth] x [sourceHeight] pixels that must read,
 * stretched over a frost band [frostWidthPx] pixels wide, as a [displayRadiusPx] blur. The blur
 * radius is capped at [maxBlurRadiusPx] (the CPU blur helper's own ceiling), so the bake shrinks
 * proportionally to compensate. Unknown frost width keeps the source as-is rather than guessing;
 * invalid inputs yield no bake at all.
 */
fun flamingoFrostBakePlan(
    sourceWidth: Int,
    sourceHeight: Int,
    displayRadiusPx: Float,
    frostWidthPx: Int,
    maxBlurRadiusPx: Float = 48f,
): FlamingoFrostBakePlan? {
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    if (displayRadiusPx <= 0f) return null

    val blurRadiusPx = displayRadiusPx.coerceAtMost(maxBlurRadiusPx)
    val bakeWidth =
        if (frostWidthPx > 0) {
            (frostWidthPx * blurRadiusPx / displayRadiusPx).roundToInt().coerceAtLeast(1)
        } else {
            sourceWidth
        }
    // Never upscale: a bake wider than the source would only spend memory on detail the
    // blur is about to destroy.
    val clampedBakeWidth = bakeWidth.coerceAtMost(sourceWidth)
    val bakeHeight =
        (sourceHeight.toFloat() * clampedBakeWidth.toFloat() / sourceWidth.toFloat())
            .roundToInt()
            .coerceAtLeast(1)
    return FlamingoFrostBakePlan(
        blurRadiusPx = blurRadiusPx,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        bakeWidth = clampedBakeWidth,
        bakeHeight = bakeHeight,
    )
}
