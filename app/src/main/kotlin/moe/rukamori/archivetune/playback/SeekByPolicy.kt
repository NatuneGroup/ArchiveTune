/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal fun resolveSeekByTargetMs(
    currentPositionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val currentPosition = currentPositionMs.coerceAtLeast(0L)
    val targetPosition =
        (
            if (deltaMs > 0L && currentPosition > Long.MAX_VALUE - deltaMs) {
                Long.MAX_VALUE
            } else {
                currentPosition + deltaMs
            }
        ).coerceAtLeast(0L)
    return if (durationMs > 0L) targetPosition.coerceAtMost(durationMs) else targetPosition
}
