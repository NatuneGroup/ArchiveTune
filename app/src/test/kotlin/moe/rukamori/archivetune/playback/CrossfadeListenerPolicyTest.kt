/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the reported bug: the session player's `isPlaying` turning false
 * during a blend (the outgoing player's end-of-item pause, or a buffering stall) must never
 * produce a PAUSE command for the incoming player. 4nx3b fixed this in 26b407c39; Metrolist
 * hit the same bug (PR #4389).
 */
class CrossfadeListenerPolicyTest {
    @Test
    fun isPlayingFalseWhileCrossfading_neverPausesTheIncomingPlayer() {
        for (handoffInProgress in listOf(true, false)) {
            assertEquals(
                "handoffInProgress=$handoffInProgress",
                CrossfadeSecondaryCommand.NONE,
                CrossfadeListenerPolicy.onSessionIsPlayingChanged(
                    isPlaying = false,
                    crossfading = true,
                    handoffInProgress = handoffInProgress,
                ),
            )
        }
    }

    @Test
    fun isPlayingTrueWhileCrossfading_keepsTheIncomingPlayerPlaying() {
        assertEquals(
            CrossfadeSecondaryCommand.PLAY,
            CrossfadeListenerPolicy.onSessionIsPlayingChanged(
                isPlaying = true,
                crossfading = true,
                handoffInProgress = false,
            ),
        )
    }

    @Test
    fun duringHandoff_noCommandIsIssued() {
        assertEquals(
            CrossfadeSecondaryCommand.NONE,
            CrossfadeListenerPolicy.onSessionIsPlayingChanged(
                isPlaying = true,
                crossfading = true,
                handoffInProgress = true,
            ),
        )
    }

    @Test
    fun outsideACrossfade_noCommandIsIssued() {
        for (isPlaying in listOf(true, false)) {
            assertEquals(
                "isPlaying=$isPlaying",
                CrossfadeSecondaryCommand.NONE,
                CrossfadeListenerPolicy.onSessionIsPlayingChanged(
                    isPlaying = isPlaying,
                    crossfading = false,
                    handoffInProgress = false,
                ),
            )
        }
    }
}
