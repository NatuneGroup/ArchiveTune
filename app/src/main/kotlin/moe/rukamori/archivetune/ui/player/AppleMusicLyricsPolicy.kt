/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.player

import kotlin.math.abs

internal const val AppleMusicLyricsControlsAutoHideDelayMs = 5_000L

internal fun shouldAutoHideAppleMusicControls(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    enabled: Boolean,
): Boolean = enabled && (lyricsOpen || queueOpen)

internal fun isAppleMusicLyricsDismissDrag(
    deltaX: Float,
    deltaY: Float,
    threshold: Float,
): Boolean = deltaY >= threshold && deltaY >= abs(deltaX)
