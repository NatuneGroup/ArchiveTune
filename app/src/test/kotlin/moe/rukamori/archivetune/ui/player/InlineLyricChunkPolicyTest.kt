/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineLyricChunkPolicyTest {
    @Test
    fun `chunks preserve all text and group measured visual lines`() {
        val text = "first line\nsecond line\nthird line\nfourth line"
        val starts = listOf(0, 11, 23, 34)
        val pages =
            createInlineLyricPages(
                text = text,
                visualLineStarts = starts,
                words = emptyList(),
                linesPerPage = 2,
                cueStartMs = 1_000L,
                cueEndMs = 5_000L,
            )

        assertEquals(listOf(2, 2), pages.chunks.map { it.lineCount })
        assertEquals(text, pages.chunks.joinToString("") { text.substring(it.startOffset, it.endOffset) })
        assertEquals(listOf(1_000L, 3_000L), pages.chunks.map { it.startMs })
    }

    @Test
    fun `timed pages begin at the words assigned to their measured lines`() {
        val text = "one two three four"
        val words =
            listOf(
                InlineLyricTimedWord("one", 1_000L, 1_400L),
                InlineLyricTimedWord("two", 1_500L, 1_900L),
                InlineLyricTimedWord("three", 2_300L, 2_800L),
                InlineLyricTimedWord("four", 3_700L, 4_200L),
            )
        val pages =
            createInlineLyricPages(
                text = text,
                visualLineStarts = listOf(0, 8, 14),
                words = words,
                linesPerPage = 1,
                cueStartMs = 1_000L,
                cueEndMs = 5_000L,
            )

        assertEquals(listOf(1_000L, 2_300L, 3_700L), pages.chunks.map { it.startMs })
        assertEquals(listOf(0, 4, 8, 14), pages.wordRanges.map { it.startOffset })
    }

    @Test
    fun `Unicode line boundaries preserve CJK RTL and grapheme sequences`() {
        val text = "日本語の歌詞 مرحبا بالعالم 👩🏽‍🎤 e\u0301"
        val graphemeAndWordStarts =
            listOf(
                0,
                text.indexOf("の"),
                text.indexOf("مرحبا"),
                text.indexOf("بالعالم"),
                text.indexOf("👩🏽‍🎤"),
                text.indexOf("e\u0301"),
            )
        val words =
            listOf(
                InlineLyricTimedWord("日本語", 0L, 300L),
                InlineLyricTimedWord("の歌詞", 300L, 700L),
                InlineLyricTimedWord("مرحبا", 700L, 1_100L),
                InlineLyricTimedWord("بالعالم", 1_100L, 1_600L),
                InlineLyricTimedWord("👩🏽‍🎤", 1_600L, 2_000L),
                InlineLyricTimedWord("e\u0301", 2_000L, 2_400L),
            )
        val pages =
            createInlineLyricPages(
                text = text,
                visualLineStarts = graphemeAndWordStarts,
                words = words,
                linesPerPage = 1,
                cueStartMs = 0L,
                cueEndMs = 3_000L,
            )

        assertEquals(text, pages.chunks.joinToString("") { text.substring(it.startOffset, it.endOffset) })
        assertEquals(listOf("日本語", "の歌詞", "مرحبا", "بالعالم", "👩🏽‍🎤", "e\u0301"), pages.wordRanges.map { word -> text.substring(word.startOffset, word.endOffset) })
        assertTrue(pages.chunks.zipWithNext().all { (first, second) -> first.startOffset < second.startOffset })
    }

    @Test
    fun `line synced pages divide the cue and playback selects the active page`() {
        val text = "one two three four"
        val pages =
            createInlineLyricPages(
                text = text,
                visualLineStarts = listOf(0, 8, 14),
                words = emptyList(),
                linesPerPage = 1,
                cueStartMs = 1_000L,
                cueEndMs = 5_000L,
            )

        assertEquals(listOf(1_000L, 2_333L, 3_666L), pages.chunks.map { it.startMs })
        assertEquals(0, inlineLyricChunkIndexAt(pages.chunks, 1_999L))
        assertEquals(1, inlineLyricChunkIndexAt(pages.chunks, 2_333L))
        assertEquals(2, inlineLyricChunkIndexAt(pages.chunks, 4_000L))
    }
}
