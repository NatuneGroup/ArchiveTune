/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

@Composable
internal fun rememberMeasuredInlineLyricPages(
    text: String,
    words: List<InlineLyricTimedWord>,
    linesPerPage: Int,
    cueStartMs: Long,
    cueEndMs: Long,
    style: TextStyle,
    maxWidth: Dp,
): InlineLyricPages {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val maxWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
    val visualLineStarts =
        remember(textMeasurer, text, style, maxWidthPx, density, layoutDirection) {
            val layout =
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = Int.MAX_VALUE,
                    constraints = Constraints(maxWidth = maxWidthPx),
                    layoutDirection = layoutDirection,
                )
            List(layout.lineCount) { layout.getLineStart(it) }
        }
    return remember(text, visualLineStarts, words, linesPerPage, cueStartMs, cueEndMs) {
        createInlineLyricPages(
            text = text,
            visualLineStarts = visualLineStarts,
            words = words,
            linesPerPage = linesPerPage,
            cueStartMs = cueStartMs,
            cueEndMs = cueEndMs,
        )
    }
}
