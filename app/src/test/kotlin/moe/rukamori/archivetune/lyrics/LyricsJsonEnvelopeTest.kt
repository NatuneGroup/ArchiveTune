/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cached provider bodies can still be JSON envelopes around TTML; reads must unwrap them. */
class LyricsJsonEnvelopeTest {
    private val ttml =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0.0" end="1.0">Hello</p></div></body></tt>"""

    private val envelope =
        """{"lyrics":"<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"0.0\" end=\"1.0\">Hello</p></div></body></tt>"}"""

    @Test
    fun jsonEnvelope_unwrapsToTtml() {
        assertEquals(ttml, LyricsUtils.normalizeLyricsText(envelope))
    }

    @Test
    fun htmlEscapedTtmlInsideEnvelope_unwrapsThenUnescapes() {
        val escaped =
            """{"lyrics":"&lt;tt xmlns=&quot;http://www.w3.org/ns/ttml&quot;&gt;&lt;body&gt;&lt;div&gt;&lt;p begin=&quot;0.0&quot; end=&quot;1.0&quot;&gt;Hello&lt;/p&gt;&lt;/div&gt;&lt;/body&gt;&lt;/tt&gt;"}"""

        assertEquals(ttml, LyricsUtils.normalizeLyricsText(escaped))
    }

    @Test
    fun plainTtml_isUnchanged() {
        assertEquals(ttml, LyricsUtils.normalizeLyricsText(ttml))
    }

    @Test
    fun lrcStartingWithBracket_isUnchanged() {
        val lrc = "[00:12.00]First line\n[00:15.50]Second line"

        assertEquals(lrc, LyricsUtils.normalizeLyricsText(lrc))
    }

    @Test
    fun malformedJson_returnsOriginal() {
        val malformed = """{"lyrics":"""

        assertEquals(malformed, LyricsUtils.normalizeLyricsText(malformed))
    }

    @Test
    fun errorEnvelope_returnsOriginalWithoutThrowing() {
        val error = """{"error":"not found"}"""

        assertEquals(error, LyricsUtils.normalizeLyricsText(error))
    }

    @Test
    fun jsonQuotingTheTtmlNamespace_isNotATtmlDocument() {
        assertFalse(LyricsPayload.isTtmlDocument(envelope))
        assertFalse(LyricsPayload.isTtmlDocument("""{"ns":"http://www.w3.org/ns/ttml"}"""))
    }

    @Test
    fun xmlLeadingTtml_isATtmlDocument() {
        assertTrue(LyricsPayload.isTtmlDocument(ttml))
        assertTrue(
            LyricsPayload.isTtmlDocument(
                """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml"></tt>""",
            ),
        )
    }

    @Test
    fun toLyrics_returnsInnerTtmlForEnvelopeQuotingNamespace() {
        assertEquals(ttml, LyricsPayload.toLyrics(envelope))
    }
}
