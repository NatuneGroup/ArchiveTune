/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Technique adapted from amll-dev/applemusic-like-lyrics (AGPL-3.0-only),
 * technique-source: packages/core/src/lyric-player/base/spring.ts
 * (`getPosYSpringPolicy`).
 *
 * PROVENANCE: this is a PORT OF THE PARAMETERS, not an idea-only port. The
 * interval clamp window, the stiffness range, the 5th-root bias and the
 * damping multiplier below are taken from upstream's policy; only the Compose
 * translation (damping ratio derived from unit mass) is ours. Upstream is
 * AGPL-3.0-only — see docs/CREDITS.md for the attribution and the open
 * license question the owner must decide.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import moe.rukamori.archivetune.lyrics.LyricsEntry
import kotlin.math.pow
import kotlin.math.sqrt

// Upstream slow mode (seek / interlude / boundary lines). Upstream expresses
// damping absolutely; Compose takes a damping *ratio*, so the ratio is derived
// once here with unit mass: ratio = damping / (2 * sqrt(stiffness)).
private const val SLOW_STIFFNESS = 90f
private const val SLOW_DAMPING = 15f
private const val MEDIUM_STIFFNESS = 140f
private const val MEDIUM_DAMPING = 22f

// Upstream normal-playback window: intervals are clamped to [100, 800] ms and
// mapped to stiffness [170, 220] with a 5th-root bias toward faster springs,
// damping tracking sqrt(stiffness) * 2.2 (i.e. a constant ratio of ~1.1).
private const val MIN_INTERVAL_MS = 100f
private const val MAX_INTERVAL_MS = 800f
private const val MIN_STIFFNESS = 170f
private const val MAX_STIFFNESS = 220f
private const val DAMPING_MULTIPLIER = 2.2f
private const val INTERVAL_EXPONENT = 0.2f

/** Interval-derived scroll spring. See file banner for the technique source. */
data class ScrollSpringPolicy(
    val stiffness: Float,
    val dampingRatio: Float,
)

private fun ratioOf(stiffness: Float, damping: Float): Float =
    (damping / (2f * sqrt(stiffness))).coerceIn(0.1f, 2f)

/**
 * Port of the upstream `getPosYSpringPolicy` idea: short line intervals get a
 * stiffer/faster spring, long intervals a softer one; seeks, interludes,
 * missing intervals and end-of-song each get their dedicated policy.
 */
fun getScrollSpringPolicy(
    intervalMs: Long?,
    isSeeking: Boolean,
    isInterludeActive: Boolean,
    isEndOfSong: Boolean = false,
): ScrollSpringPolicy {
    if (isSeeking || isInterludeActive) {
        return ScrollSpringPolicy(SLOW_STIFFNESS, ratioOf(SLOW_STIFFNESS, SLOW_DAMPING))
    }
    if (isEndOfSong) {
        return ScrollSpringPolicy(MEDIUM_STIFFNESS, ratioOf(MEDIUM_STIFFNESS, MEDIUM_DAMPING))
    }
    if (intervalMs == null) {
        return ScrollSpringPolicy(SLOW_STIFFNESS, ratioOf(SLOW_STIFFNESS, SLOW_DAMPING))
    }
    val clamped = intervalMs.toFloat().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    var ratio = 1f - (clamped - MIN_INTERVAL_MS) / (MAX_INTERVAL_MS - MIN_INTERVAL_MS)
    ratio = ratio.toDouble().pow(INTERVAL_EXPONENT.toDouble()).toFloat()
    val stiffness = MIN_STIFFNESS + ratio * (MAX_STIFFNESS - MIN_STIFFNESS)
    // sqrt(stiffness) * 2.2 with unit mass folds to a constant ratio of 1.1.
    return ScrollSpringPolicy(stiffness, (DAMPING_MULTIPLIER / 2f).coerceIn(0.1f, 2f))
}

/**
 * Milliseconds between the target line and its predecessor, or null when the
 * predecessor is unusable (first line, head sentinel, unsynced). O(1).
 */
fun lineIntervalMs(entries: List<LyricsEntry>, index: Int): Long? {
    if (index <= 0 || index >= entries.size) return null
    val current = entries[index]
    val previous = entries[index - 1]
    if (current.time < 0L || previous.time < 0L) return null
    if (previous.text.isBlank() && previous.words == null) return null
    return (current.time - previous.time).takeIf { it >= 0L }
}

/**
 * Scroll to [index] with the interval-derived spring. When the target line is
 * already laid out, the exact pixel delta is animated with the policy spring;
 * far jumps fall back to the stock [LazyListState.animateScrollToItem] (same
 * as today). One spring-spec object per scroll; no per-frame allocation and
 * no state reads in draw — the framework scroll runner owns the frames.
 */
suspend fun LazyListState.intervalAdaptiveScrollTo(
    index: Int,
    scrollOffset: Int,
    intervalMs: Long?,
    isSeeking: Boolean,
    isInterludeActive: Boolean,
    isEndOfSong: Boolean = false,
) {
    val policy = getScrollSpringPolicy(intervalMs, isSeeking, isInterludeActive, isEndOfSong)
    val spec = spring<Float>(stiffness = policy.stiffness, dampingRatio = policy.dampingRatio)
    val target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (target != null) {
        // animateScrollToItem(index, scrollOffset) settles the item at
        // `offset == scrollOffset`, so the remaining distance is exact.
        val delta = (target.offset - scrollOffset).toFloat()
        if (delta == 0f) return
        animateScrollBy(delta, spec)
    } else {
        animateScrollToItem(index, scrollOffset)
    }
}

/** Framework default kept for reference; used when the toggle is OFF. */
@Suppress("unused")
val DefaultScrollStiffness: Float = Spring.StiffnessMediumLow
