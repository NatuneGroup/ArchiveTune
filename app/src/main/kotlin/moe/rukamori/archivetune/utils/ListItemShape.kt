/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Ported from vivi-music (beta branch) utils/Utils.kt listItemShape (GPL-3.0).
 */



package moe.rukamori.archivetune.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Per-position shape for grouped list items: the first item curves its top corners, the last
 * item curves its bottom corners, single items are fully rounded, middle items stay flat.
 *
 * vivi builds these from `racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape`
 * (squircle "smooth" corners at 60% smoothness). ArchiveTune does not depend on that library,
 * so the identical corner layout is expressed with plain [RoundedCornerShape]; the radius
 * parameter and the first/middle/last positional logic match vivi 1:1.
 */
fun listItemShape(index: Int, count: Int, radius: Dp = 16.dp): Shape {
    return when {
        count == 1 -> RoundedCornerShape(radius)
        index == 0 -> RoundedCornerShape(
            topStart = radius,
            topEnd = radius,
        )
        index == count - 1 -> RoundedCornerShape(
            bottomStart = radius,
            bottomEnd = radius,
        )
        else -> RectangleShape
    }
}
