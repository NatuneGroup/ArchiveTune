/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicLyricsPolicyTest {
    @Test
    fun `auto hide uses five seconds only when an overlay is open and enabled`() {
        assertEquals(5_000L, AppleMusicLyricsControlsAutoHideDelayMs)
        assertTrue(shouldAutoHideAppleMusicControls(lyricsOpen = true, queueOpen = false, enabled = true))
        assertTrue(shouldAutoHideAppleMusicControls(lyricsOpen = false, queueOpen = true, enabled = true))
        assertFalse(shouldAutoHideAppleMusicControls(lyricsOpen = true, queueOpen = false, enabled = false))
        assertFalse(shouldAutoHideAppleMusicControls(lyricsOpen = false, queueOpen = false, enabled = true))
    }

    @Test
    fun `lyrics controls count down only while playing and left alone`() {
        assertTrue(shouldAutoHideLyricsControls(isPlaying = true, userScrollingLyrics = false, scrubbing = false, menuOpen = false))
        assertFalse(shouldAutoHideLyricsControls(isPlaying = false, userScrollingLyrics = false, scrubbing = false, menuOpen = false))
        assertFalse(shouldAutoHideLyricsControls(isPlaying = true, userScrollingLyrics = true, scrubbing = false, menuOpen = false))
        assertFalse(shouldAutoHideLyricsControls(isPlaying = true, userScrollingLyrics = false, scrubbing = true, menuOpen = false))
        assertFalse(shouldAutoHideLyricsControls(isPlaying = true, userScrollingLyrics = false, scrubbing = false, menuOpen = true))
    }

    @Test
    fun `only a downward vertical drag past the threshold dismisses lyrics`() {
        assertTrue(isAppleMusicLyricsDismissDrag(deltaX = 8f, deltaY = 96f, threshold = 96f))
        assertFalse(isAppleMusicLyricsDismissDrag(deltaX = 0f, deltaY = 95f, threshold = 96f))
        assertFalse(isAppleMusicLyricsDismissDrag(deltaX = 97f, deltaY = 96f, threshold = 96f))
        assertFalse(isAppleMusicLyricsDismissDrag(deltaX = 0f, deltaY = -96f, threshold = 96f))
    }
}
