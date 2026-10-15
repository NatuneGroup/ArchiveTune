/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import moe.rukamori.archivetune.ai.AiLyricsDocumentParser
import moe.rukamori.archivetune.ui.component.toLyricsWrappingUnits
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class LyricsUnicodeRegressionTest {
    private val samples = listOf(
        "café e\u0301", "日本語かな", "中文\uD840\uDC00", "한글 한",
        "می\u200Cروم اردو العربية", "Українська", "क्\u200Dष", "ไทย\u200Bเพลง",
        "👩\u200D🎤 🎤", "123 العربية Latin ٤٥",
    )

    @Test fun plainAndLrcPreserveSemanticCodePointsAndTimestamps() {
        samples.forEach { text ->
            assertEquals(text, LyricsUtils.normalizeLyricsText("\uFEFF$text"))
            assertEquals(text, LyricsUtils.displayLyricsText(text))
            val parsed = LyricsUtils.parseLyrics("[01:02.345]$text").single()
            assertEquals(text, parsed.text)
            assertEquals(62_345L, parsed.time)
            assertNull(parsed.words)
            assertEquals(text, LyricsUtils.displayLyricsText("[01:02.345]$text"))
            assertEquals(text, text.toLyricsWrappingUnits().joinToString(""))
        }
    }

    @Test fun enhancedLrcKeepsJoinersGraphemesAndSourceWordTimes() {
        val first = "ک\u200Cک क्\u200Dष e\u0301 "
        val second = "👩\u200D🎤\u200B\uD840\uDC00"
        val entry = LyricsUtils.parseLyrics("[00:01.000]<00:01.000>$first<00:02.250>$second").single()
        assertEquals(first + second, entry.text)
        val words = requireNotNull(entry.words)
        assertEquals(listOf(first, second), words.map { it.text })
        assertEquals(listOf(1.0, 2.25), words.map { it.startTime })
        assertEquals(listOf(2.25, 2.85), words.map { it.endTime })
        assertEquals(1000L, entry.time)
    }

    @Test fun millisecondYrcFramingPreservesTextAndLineTime() {
        val text = "می\u200Cروم👩\u200D🎤\u200B\uD840\uDC00"
        val entry = LyricsUtils.parseLyrics("[1200,900]${text}e\u0301").single()
        assertEquals(1200L, entry.time)
        assertEquals(text + "e\u0301", entry.text)
    }

    @Test fun ttmlDocumentParserKeepsUnicodeAndRealTimingAttributes() {
        val text = "می\u200Cروم क्\u200Dष 👩\u200D🎤\u200B\uD840\uDC00 e\u0301"
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="es"><body><div><p begin="1.25s" end="3.5s"><span begin="1.25s" end="2.5s">$text</span></p></div></body></tt>"""
        val document = AiLyricsDocumentParser.parse(raw)
        assertEquals(listOf(text), document.segments.map { it.text })
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(document.rebuild(emptyMap()))))
        val p = xml.getElementsByTagName("p").item(0) as org.w3c.dom.Element
        val span = xml.getElementsByTagName("span").item(0) as org.w3c.dom.Element
        assertEquals("1.25s", p.getAttribute("begin"))
        assertEquals("3.5s", p.getAttribute("end"))
        assertEquals("1.25s", span.getAttribute("begin"))
        assertEquals("2.5s", span.getAttribute("end"))
        assertEquals(text, span.textContent)
    }

    @Test fun qrcDocumentFramingKeepsOriginalTimedFragments() {
        val text = "ک\u200Cک👩\u200D🎤\u200B\uD840\uDC00"
        val raw = "[1000,2000]$text(1000,800)e\u0301(1800,1200)"
        val document = AiLyricsDocumentParser.parse(raw)
        assertEquals(listOf(text + "e\u0301"), document.segments.map { it.text })
        assertEquals(raw, document.rebuild(emptyMap()))
        val entry = LyricsUtils.parseLyrics(raw).single()
        assertEquals(1000L, entry.time)
        assertEquals(text + "e\u0301", entry.text)
        val words = requireNotNull(entry.words)
        assertEquals(listOf(text, "e\u0301"), words.map { it.text })
        assertEquals(listOf(1.0, 1.8), words.map { it.startTime })
        assertEquals(listOf(1.8, 3.0), words.map { it.endTime })
    }

    @Test fun payloadDecoderHandlesCompleteDecimalHexScalarsAndInvalidLiterals() {
        val values = listOf(0, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 127908, 131072, 1000000, 0x10FFFF)
        values.forEach { cp ->
            val expected = String(Character.toChars(cp))
            assertEquals(expected, LyricsPayload.decodeEntities("&#$cp;"))
            assertEquals(expected, LyricsPayload.decodeEntities("&#x${cp.toString(16)};"))
        }
        assertEquals("🎤\uD840\uDC00" + String(Character.toChars(0x10FFFF)),
            LyricsPayload.decodeEntities("&#127908;&#131072;&#x10FFFF;"))
        listOf("&#55296;", "&#57343;", "&#xD800;", "&#xDFFF;", "&#1114112;", "&#x110000;",
            "&#999999999999999999999;", "&#xFFFFFFFFFFFFFFFF;", "&#-1;", "&#xZZ;", "&#;", "&#127908").forEach {
            assertEquals(it, LyricsPayload.decodeEntities(it))
        }
    }

    @Test fun actualPayloadIngestionDecodesSupplementaryTextWithoutChangingLrcTiming() {
        val text = "🎤\uD840\uDC00👩\u200D🎤می\u200Cروم"
        val raw = "[01:02.345]&#127908;&#131072;👩\u200D🎤می\u200Cروم"
        val decoded = requireNotNull(LyricsPayload.toLyrics(raw))
        val entry = LyricsUtils.parseLyrics(decoded).single()
        assertEquals(62_345L, entry.time)
        assertEquals(text, entry.text)
        assertEquals(text, LyricsPayload.toLyrics(raw.substringAfter(']')))
        assertEquals(text, LyricsPayload.toLyrics(text))
        assertEquals(text, LyricsPayload.toLyrics("""{"lyrics":"${raw.substringAfter(']')}"}"""))
        listOf("null", "true", "false", "42", """{"error":"not found"}""").forEach {
            assertNull(LyricsPayload.toLyrics(it))
        }
    }
}
