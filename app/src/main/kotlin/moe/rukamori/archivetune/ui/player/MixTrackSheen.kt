/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * The crossfade/automix morph shared by the BitChord and Flamingo seek bars: while
 * MusicService blends two tracks, the played fill collapses and an accent-coloured sheen
 * sweeps the bar, and both return when the mix hands over. Written for the BitChord
 * slider and now shared with Flamingo's track, so it lives in ui.player rather than
 * inside one style's package — the same reason FormatBadge does.
 *
 * Every duration here is a Compose animation, so the system animator duration scale
 * applies on its own; with animations off (0x) the collapse snaps and the sheen never
 * composes.
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import moe.rukamori.archivetune.LocalAnimationsDisabled

/** How long the played fill takes to collapse, and to grow back, at 1x. */
private const val MixMorphMs = 450

/**
 * The seek-bar fill while a mix blends: 1 while playing normally, easing to 0 over
 * [MixMorphMs] so the played track collapses instead of vanishing on the frame the mix
 * starts, and growing back when it hands over. Compose scales the tween with the system
 * animator duration scale and snaps it at 0x.
 */
@Composable
internal fun rememberMixFillFactor(
    mixing: Boolean,
    label: String,
): Float =
    animateFloatAsState(
        targetValue = if (mixing) 0f else 1f,
        animationSpec = tween(durationMillis = MixMorphMs, easing = FastOutSlowInEasing),
        label = label,
    ).value

/**
 * A soft accent-coloured highlight travelling the length of the bar, over and over, for
 * as long as [visible] holds — the "two tracks are being mixed" signal next to
 * [rememberMixFillFactor]'s collapsed fill.
 *
 * Composed only while visible: the sheen's infinite transition keeps requesting frames
 * for as long as it exists, so the cheap way to stop it costing anything while idle is
 * for it not to exist. [AnimatedVisibility] keeps it alive through the exit fade, so it
 * dies away with the mix instead of vanishing on the frame the mix ends. With animations
 * off it never composes at all — a frozen sweep would be neither seen nor free.
 */
@Composable
internal fun MixTrackSheen(
    visible: Boolean,
    color: Color,
    height: Dp,
) {
    if (LocalAnimationsDisabled.current) return
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = MixMorphMs, easing = FastOutSlowInEasing)) +
            scaleIn(
                animationSpec = tween(durationMillis = MixMorphMs, easing = FastOutSlowInEasing),
                initialScale = 0f,
                transformOrigin = TransformOrigin(0f, 0.5f),
            ),
        exit = fadeOut(tween(durationMillis = MixMorphMs, easing = FastOutSlowInEasing)),
    ) {
        val transition = rememberInfiniteTransition(label = "mixTrackSheen")
        val phase by transition.animateFloat(
            initialValue = -SheenBandFraction,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // Long enough to read as a sweep rather than a flicker, and slow enough
                // not to compete with the music for attention.
                animation = tween(durationMillis = SheenPeriodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mixTrackSheenPhase",
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val band = size.width * SheenBandFraction
            val x = phase * size.width
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.5f to color,
                        1f to Color.Transparent,
                    ),
                    startX = x,
                    endX = x + band,
                ),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
    }
}

/** Width of the travelling highlight, as a fraction of the bar. */
private const val SheenBandFraction = 0.34f

/** How long one sweep across the bar — and back — takes, at 1x. */
private const val SheenPeriodMs = 1000
