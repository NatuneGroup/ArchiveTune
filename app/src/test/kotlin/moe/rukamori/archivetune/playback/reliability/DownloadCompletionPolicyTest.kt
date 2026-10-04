/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.isDownloadedContentTruncated
import moe.rukamori.archivetune.playback.resolveExpectedDownloadedLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCompletionPolicyTest {
    @Test
    fun `range response recovers total length when HEAD has no length`() {
        val expectedLength =
            resolveExpectedDownloadedLength(
                explicitExpectedLength = null,
                headContentLength = null,
                rangeResponseCode = 206,
                rangeContentRange = "bytes 0-0/8192",
                rangeContentLength = "1",
            )

        assertEquals(8192L, expectedLength)
        assertTrue(isDownloadedContentTruncated(actualLength = 4096L, expectedLength = expectedLength))
    }

    @Test
    fun `explicit and HEAD lengths take priority over range response`() {
        assertEquals(4096L, resolveExpectedDownloadedLength("4096", "2048", 206, "bytes 0-0/8192", "1"))
        assertEquals(2048L, resolveExpectedDownloadedLength(null, "2048", 206, "bytes 0-0/8192", "1"))
    }

    @Test
    fun `range body length is not mistaken for total length`() {
        assertEquals(0L, resolveExpectedDownloadedLength(null, null, 206, null, "1"))
        assertEquals(0L, resolveExpectedDownloadedLength(null, null, 206, "bytes 0-0/*", "1"))
        assertEquals(8192L, resolveExpectedDownloadedLength(null, null, 200, null, "8192"))
    }

    @Test
    fun `complete or unknown length responses are not rejected`() {
        assertFalse(isDownloadedContentTruncated(actualLength = 8192L, expectedLength = 8192L))
        assertFalse(isDownloadedContentTruncated(actualLength = 4096L, expectedLength = 0L))
    }
}
