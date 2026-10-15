/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadReadBoundsTest {
    @Test
    fun nonzeroPositionAndExplicitRangeCannotReadPastRequestedLength() {
        assertEquals(40L, downloadedBytesRemaining(40, -1, 1_000, 200))
        assertEquals(40L, downloadedBytesRemaining(40, 800, 1_000, 200))
        assertEquals(10L, downloadedBytesRemaining(40, 10, 1_000, 200))
        assertEquals(10, downloadedReadLength(64, 10))
        assertEquals(4, downloadedReadLength(4, 10))
    }

    @Test
    fun unknownLengthUsesActualFileRemainderRatherThanWholeFileOrCapacity() {
        assertEquals(800L, downloadedBytesRemaining(-1, -1, 1_000, 200))
        assertEquals(120L, downloadedBytesRemaining(-1, 120, 1_000, 200))
        assertEquals(0L, downloadedBytesRemaining(-1, -1, 1_000, 1_000))
        assertEquals(0L, downloadedBytesRemaining(0, 800, 1_000, 200))
        assertEquals(0, downloadedReadLength(64, 0))
        assertEquals(0, downloadedReadLength(0, 800))
        assertEquals(Int.MAX_VALUE, downloadedReadLength(Int.MAX_VALUE, Long.MAX_VALUE))
    }
}
