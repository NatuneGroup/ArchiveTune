/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExceptionSummaryTest {
    private fun error(
        type: String = "java.lang.IllegalStateException",
        method: String = "resolve",
        line: Int = 12,
        detail: String = "unavailable",
    ): String =
        """
        $type: $detail
            at moe.rukamori.archivetune.playback.MusicService.$method(MusicService.kt:$line)
    """
            .trimIndent()

    @Test
    fun messagesAndSourceLineNumbersDoNotChangeTheSignature() {
        val first = error(detail = "token=private-one user@example.test")
        val second = error(line = 98, detail = "https://example.test/private-two")
        val summary = diagnosticExceptionSummary(listOf(first, second))
        assertEquals(1, summary.lines().size)
        assertTrue(summary.contains("records=2"))
        assertTrue(
            summary.contains("origin=moe.rukamori.archivetune.playback.MusicService.resolve"),
        )
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("example.test"))
        assertFalse(summary.contains("MusicService.kt"))
        assertEquals(
            diagnosticExceptionSummary(listOf(first)).substringBefore(" records="),
            diagnosticExceptionSummary(listOf(second)).substringBefore(" records="),
        )
        assertTrue(
            summary.substringAfter("id=").substringBefore(' ').matches(Regex("[0-9a-f]{12}")),
        )
    }

    @Test
    fun differentTypesAndCallSitesRemainSeparate() {
        val summary =
            diagnosticExceptionSummary(
                listOf(error(), error(type = "java.io.IOException"), error(method = "prefetch")),
            )
        assertEquals(3, summary.lines().size)
        assertEquals(3, summary.lines().map { it.substringBefore(' ') }.distinct().size)
    }

    @Test
    fun unrecognizedMessagesDoNotBecomeExceptionSummaries() {
        assertEquals(
            "",
            diagnosticExceptionSummary(
                listOf("private provider response", "customer.account.SecretException: private"),
            ),
        )
    }

    @Test
    fun genericExceptionWithoutAnApplicationFrameDoesNotInventAnOrigin() {
        val summary = diagnosticExceptionSummary(listOf("java.lang.Exception: private details"))
        assertTrue(summary.contains("exception=java.lang.Exception"))
        assertTrue(summary.endsWith("origin=unavailable"))
        assertFalse(summary.contains("private"))
    }

    @Test
    fun constructorFramesKeepTheirApplicationOrigin() {
        val summary =
            diagnosticExceptionSummary(listOf(error(method = "<init>"), error(method = "<clinit>")))
        assertEquals(2, summary.lines().size)
        assertTrue(summary.contains("origin=moe.rukamori.archivetune.playback.MusicService.<init>"))
        assertTrue(
            summary.contains("origin=moe.rukamori.archivetune.playback.MusicService.<clinit>"),
        )
    }

    @Test
    fun recognizedCausesDoNotCopyTheSurroundingProviderMessage() {
        val cause = error(type = "java.io.IOException", detail = "private")
        val summary =
            diagnosticExceptionSummary(
                listOf("Provider failed with private context\nCaused by: $cause"),
            )
        assertTrue(summary.contains("exception=java.io.IOException"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("Provider failed"))
    }

    @Test
    fun exceptionHeadersAndFramesBeyondTheRecordLimitAreNotScanned() {
        val outsideWindow = "x".repeat(32_768) + "\n" + error()
        assertEquals("", diagnosticExceptionSummary(listOf(outsideWindow)))
        val missingFrame =
            "java.lang.Exception: " +
                "x".repeat(32_768) +
                "\n at moe.rukamori.archivetune.playback.MusicService.resolve(MusicService.kt:12)"
        assertTrue(diagnosticExceptionSummary(listOf(missingFrame)).endsWith("origin=unavailable"))
    }

    @Test
    fun onlyTheMostRecentFiveHundredErrorCallsAreCounted() {
        val summary =
            diagnosticExceptionSummary(
                listOf(error()) + List(500) { error(type = "java.io.IOException") },
            )
        assertTrue(summary.contains("records=500"))
        assertFalse(summary.contains("IllegalStateException"))
    }

    @Test
    fun theMostFrequentGroupsWinTheBoundedSummary() {
        val messages =
            (0 until 20).map { error(method = "source$it") } + List(3) { error(method = "latest") }
        val summary = diagnosticExceptionSummary(messages)
        assertEquals(8, summary.lines().size)
        assertTrue(summary.lineSequence().first().contains("records=3"))
        assertTrue(summary.lineSequence().first().endsWith("MusicService.latest"))
    }
}
