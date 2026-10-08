/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how word timings are read out of an enhanced-LRC line
 * (`[00:12.000]<00:12.000>Hello <00:12.500>world`).
 *
 * Two things were wrong at once and both were silent: the separator space that most generators put
 * after each word was trimmed away, so the verbatim word renderers (LyricsV2 / the LyricsEnhanced
 * karaoke) drew `Helloworld`; and the last word of a line kept a guessed 600 ms tail, which ran
 * past the next line and left that word highlighted while the next one was already singing.
 */
class EnhancedLrcWordSpacingTest {
    @Test
    fun keepsTheSeparatorSpaceOnTheWordThatCarriesIt() {
        val entries = LyricsUtils.parseLyrics("[00:12.000]<00:12.000>Hello <00:12.500>world")

        assertEquals(1, entries.size)
        val words = entries.single().words
        assertEquals(listOf("Hello ", "world"), words?.map { it.text })
        assertEquals("Hello world", words?.joinToString("") { it.text })
    }

    @Test
    fun collapsesADoubledSeparatorToASingleSpace() {
        val words = LyricsUtils.parseLyrics("[00:12.000]<00:12.000>Hello  <00:12.500>world").single().words

        assertEquals("Hello world", words?.joinToString("") { it.text })
    }

    @Test
    fun keepsCompactEnhancedLrcCompact() {
        // No separator in the source means no separator in the output: a CJK line must not gain
        // invented spaces between its words.
        val words = LyricsUtils.parseLyrics("[00:12.000]<00:12.000>Hello<00:12.500>world").single().words

        assertEquals("Helloworld", words?.joinToString("") { it.text })
    }

    @Test
    fun stampsAreSecondsAndMonotonic() {
        val words = LyricsUtils.parseLyrics("[00:12.000]<00:12.000>Hello <00:12.500>world").single().words!!

        assertEquals(12.0, words[0].startTime, 0.001)
        assertEquals(12.5, words[1].startTime, 0.001)
        assertTrue(words[1].endTime > words[1].startTime)
    }

    @Test
    fun clampsTheLastWordToTheNextLine() {
        val entries =
            LyricsUtils.parseLyrics(
                "[00:12.000]<00:12.000>Hello <00:12.500>world\n[00:13.000]<00:13.000>again",
            )

        val lastWordOfFirstLine = entries.first().words!!.last()
        assertEquals("world", lastWordOfFirstLine.text)
        assertEquals(13.0, lastWordOfFirstLine.endTime, 0.001)
    }

    @Test
    fun lineTextDropsTheStampsButKeepsTheSpaces() {
        assertEquals(
            "Hello world",
            LyricsUtils.parseLyrics("[00:12.000]<00:12.000>Hello <00:12.500>world").single().text,
        )
    }

    @Test
    fun plainLineSyncedLrcHasNoWords() {
        val entries = LyricsUtils.parseLyrics("[00:12.000]Hello world")

        assertEquals("Hello world", entries.single().text)
        assertNull(entries.single().words)
    }
}
