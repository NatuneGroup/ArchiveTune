/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.*
import org.junit.Test

class TimedLyricsPresentationTest {
    @Test fun rtlCjkLatinAndSplitGraphemesRetainOneLogicalSourceStringAndRanges() {
        val fixtures = listOf(
            listOf("می\u200C", "روم"), listOf("الع", "ربية"), listOf("中", "文"),
            listOf("東", "京"), listOf("Hello ", "world"), listOf("e", "\u0301"),
            listOf("👩\u200D", "🎤"), listOf("\uD840\uDC00", "かな"),
        )
        fixtures.forEach { fragments ->
            val source = fragments.joinToString("")
            val words = fragments.mapIndexed { index, fragment -> WordTimestamp(fragment, index + 1.0, index + 2.0) }
            val entry = LyricsEntry(1000, source, words)
            val result = timedLyricsPresentation(entry, LyricsPhonetics())
            assertEquals(source, result.text)
            assertEquals(fragments, result.ranges.map { result.text.substring(requireNotNull(it)) })
            assertEquals(0, result.ranges.first()!!.first)
            assertEquals(source.lastIndex, result.ranges.last()!!.last)
            assertEquals(words, entry.words)
            assertEquals(1000L, entry.time)
        }
    }

    @Test fun lineOnlyPhoneticsHaveNoInventedWordRangesAndTogglesKeepSeekIdentity() {
        val words = listOf(WordTimestamp("東", 4.5, 5.0), WordTimestamp("京", 5.0, 5.5))
        val entry = LyricsEntry(4500, "東京", words)
        val original = timedLyricsPresentation(entry, LyricsPhonetics())
        val lineOnly = timedLyricsPresentation(entry, LyricsPhonetics("toukyou"))
        val aligned = timedLyricsPresentation(entry, LyricsPhonetics("toukyou", listOf("tou", "kyou")))
        assertEquals("東京", original.text)
        assertEquals(listOf(0..0, 1..1), original.ranges)
        assertEquals("toukyou", lineOnly.text)
        assertEquals(listOf<IntRange?>(null, null), lineOnly.ranges)
        assertEquals("tou kyou", aligned.text)
        assertEquals(listOf(0..2, 4..7), aligned.ranges)
        assertEquals(listOf(4.5 to 5.0, 5.0 to 5.5), entry.words!!.map { it.startTime to it.endTime })
        assertEquals(4500L, entry.time)
        assertEquals(original, timedLyricsPresentation(entry, LyricsPhonetics()))
    }

    @Test fun repeatedWordsMissingAlignmentAndBackgroundDoNotShiftSourceIndices() {
        val entry = LyricsEntry(1000, "la la", listOf(
            WordTimestamp("la ", 1.0, 2.0), WordTimestamp("aside", 1.5, 2.5, true), WordTimestamp("la", 2.0, 3.0)))
        assertEquals(listOf(0..2, 3..4), timedLyricsPresentation(entry, LyricsPhonetics()).ranges)
        assertEquals("la la", timedLyricsPresentation(entry, LyricsPhonetics(words = listOf("wrong-count"))).text)
        val absent = entry.copy(words = listOf(WordTimestamp("missing", 1.0, 2.0)))
        assertEquals(listOf<IntRange?>(null), timedLyricsPresentation(absent, LyricsPhonetics()).ranges)
    }
}
