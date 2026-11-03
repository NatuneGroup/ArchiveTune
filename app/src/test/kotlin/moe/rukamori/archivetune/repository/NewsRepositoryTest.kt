/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The news host answers a missing content path with HTTP 200 + the site HTML shell. Those answers
 * must surface as errors (the screen shows retry), never as article content or a cached success —
 * and API/content paths are excluded from any site-shell treatment.
 */
class NewsRepositoryTest {
    @Test
    fun non2xxIsRejected() {
        assertEquals("HTTP 404", NewsRepository.invalidReason(404, "application/json", "{}"))
        assertEquals("HTTP 500", NewsRepository.invalidReason(500, null, "body"))
    }

    @Test
    fun emptyBodyIsRejected() {
        assertEquals("empty body", NewsRepository.invalidReason(200, "application/json", ""))
        assertEquals("empty body", NewsRepository.invalidReason(200, "application/json", "   "))
        assertEquals("empty body", NewsRepository.invalidReason(200, "application/json", null))
    }

    @Test
    fun htmlContentTypeIsRejected() {
        assertEquals(
            "HTML content-type",
            NewsRepository.invalidReason(200, "text/html; charset=utf-8", "# Title\n\nBody"),
        )
    }

    @Test
    fun htmlShellBodyIsRejected() {
        assertEquals(
            "HTML shell, not article content",
            NewsRepository.invalidReason(200, "text/html", "<!doctype html><html>site</html>"),
        )
        assertEquals(
            "HTML shell, not article content",
            NewsRepository.invalidReason(200, "text/plain", "   <html><body>site</body></html>"),
        )
        assertEquals(
            "HTML shell, not article content",
            NewsRepository.invalidReason(200, null, "<!DOCTYPE html>"),
        )
    }

    @Test
    fun realPayloadsAreAccepted() {
        assertNull(NewsRepository.invalidReason(200, "application/json", "[{\"id\":\"a\"}]"))
        assertNull(NewsRepository.invalidReason(200, "text/markdown", "# Announcement\n\nText"))
        assertNull(NewsRepository.invalidReason(200, "text/plain", "plain article"))
        assertNull(NewsRepository.invalidReason(200, null, "# markdown without content-type"))
        // A mid-document fragment that is not a page shell is still article content.
        assertNull(NewsRepository.invalidReason(200, "text/markdown", "<p>embedded</p>"))
    }
}
