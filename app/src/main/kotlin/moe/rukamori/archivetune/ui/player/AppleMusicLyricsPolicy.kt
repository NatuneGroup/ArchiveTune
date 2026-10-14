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

/**
 * Whether the lyrics page may start its idle countdown to tuck the controls away. Paused playback
 * keeps them up (there is nothing to read along to, and play is the next thing wanted), as does
 * anything the user is in the middle of: scrolling the lyrics, scrubbing, or an open menu.
 */
internal fun shouldAutoHideLyricsControls(
    isPlaying: Boolean,
    userScrollingLyrics: Boolean,
    scrubbing: Boolean,
    menuOpen: Boolean,
): Boolean = isPlaying && !userScrollingLyrics && !scrubbing && !menuOpen

internal fun isAppleMusicLyricsDismissDrag(
    deltaX: Float,
    deltaY: Float,
    threshold: Float,
): Boolean = deltaY >= threshold && deltaY >= abs(deltaX)
