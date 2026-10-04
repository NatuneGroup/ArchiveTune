/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackInfoFormattingTest {
    @Test
    fun durationFormattingHandlesMinutesHoursAndUnknownValues() {
        assertEquals("1:05", formatTrackDuration(65_000L))
        assertEquals("1:01:01", formatTrackDuration(3_661_000L))
        assertNull(formatTrackDuration(0L))
        assertNull(formatTrackDuration(-1L))
        assertNull(formatTrackDuration(null))
    }

    @Test
    fun streamFormattingUsesReportedUnitsAndRejectsInvalidValues() {
        assertEquals("320 kb/s", formatReportedBitrate(320_000))
        assertEquals("512 b/s", formatReportedBitrate(512))
        assertEquals("44.1 kHz", formatReportedSampleRate(44_100))
        assertEquals("1.5 MiB", formatReportedByteCount(1_572_864L))
        assertEquals("-14.2 dB", formatReportedLoudness(-14.2))
        assertNull(formatReportedBitrate(0))
        assertNull(formatReportedSampleRate(-1))
        assertNull(formatReportedByteCount(0L))
        assertNull(formatReportedLoudness(Double.NaN))
    }

    @Test
    fun onlyPlainYouTubeVideoIdsReceiveRemoteMetadataNavigation() {
        assertTrue(isYouTubeVideoId("dQw4w9WgXcQ"))
        assertFalse(isYouTubeVideoId("LPtg12345"))
        assertFalse(isYouTubeVideoId("https://youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
