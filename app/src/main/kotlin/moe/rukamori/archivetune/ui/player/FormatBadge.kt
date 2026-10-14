/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * The stream-quality badge — "Lossless", "Hi-Res Lossless", or the codec of a lossy stream.
 *
 * Written for the BitChord player, and now shared: the lyrics screen shows the same badge between
 * its two timestamps for every player style, so it lives in ui.player rather than inside one
 * style's package.
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.FormatEntity

// ── The codec / quality badge (adapted to ArchiveTune's FormatEntity) ─────────

/**
 * The gap between the two timestamps under the seek bar. The text comes from [streamQuality], the
 * same classification the other player styles' chips read, so the lyrics page and the player page
 * behind it always name the stream the same way.
 */
@Composable
fun LosslessOrStats(
    format: FormatEntity?,
    modifier: Modifier = Modifier,
) {
    val quality = format?.streamQuality() ?: return
    LosslessLabel(
        text = quality.label(),
        // Shimmer is reserved for the thing that was asked for and confirmed. It is what makes
        // the badge read as an achievement rather than a label.
        animated = quality.isLossless,
        modifier = modifier,
    )
}

/** A lossless-wave glyph ahead of the quality tag. */
@Composable
private fun LosslessLabel(text: String, animated: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lossless_wave),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (animated) 0.78f else 0.68f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        if (animated) {
            ShimmerText(text = text)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
                ),
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "Lossless", with a highlight band sweeping left to right across it every
 * three seconds — confirmed, not just claimed, so it's worth the shine.
 *
 * The band's width is measured off the text itself via [onSizeChanged]
 * rather than assumed, so the sweep always clears the word fully at both
 * ends instead of being sized for whatever length happened to be typical.
 */
@Composable
private fun ShimmerText(text: String) {
    var widthPx by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "lossless-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lossless-shimmer-progress",
    )
    val baseColor = Color.White.copy(alpha = 0.68f)
    val brush = if (widthPx <= 0) {
        Brush.linearGradient(listOf(baseColor, baseColor))
    } else {
        val band = widthPx * 0.6f
        val center = -band + progress * (widthPx + 2 * band)
        Brush.linearGradient(
            colorStops = arrayOf(0f to baseColor, 0.5f to Color.White, 1f to baseColor),
            start = Offset(center - band, 0f),
            end = Offset(center + band, 0f),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            brush = brush,
            fontWeight = FontWeight.SemiBold,
            fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.onSizeChanged { widthPx = it.width },
    )
}
