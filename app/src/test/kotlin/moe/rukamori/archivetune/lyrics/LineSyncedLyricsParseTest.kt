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
import org.junit.Test

/**
 * Pins how a line-synced document is read when several rows share one timestamp.
 *
 * A repeated timestamp means two different things: a translation-annotated document (our own AI
 * translation writes the translated row directly under the original with the same prefix, and some
 * providers ship bilingual lyrics that way), or a plain LRC that happens to put two rows on one
 * timestamp — LRCLIB emits an empty spacer row (`[00:21.50]` with no text) immediately before a
 * sung line that carries the same time. Collapsing the second kind made both rows disappear:
 * the empty spacer stayed as the entry and held the only real text as its "translation", which is
 * issue #1333.
 */
class LineSyncedLyricsParseTest {
    private val spacerAndSungLineAtOneTimestamp =
        """
        [00:16.50] E ho bisogno di amici che mi facciano stare bene
        [00:21.50]
        [00:21.50] Respiravo una cosa
        [00:24.30] Una cosa pericolosa
        """.trimIndent()

    @Test
    fun keepsBothRowsWhenAnEmptySpacerSharesTheTimestamp() {
        val entries = LyricsUtils.parseLyrics(spacerAndSungLineAtOneTimestamp)

        assertEquals(
            listOf(
                "E ho bisogno di amici che mi facciano stare bene",
                "",
                "Respiravo una cosa",
                "Una cosa pericolosa",
            ),
            entries.map { it.text },
        )
        assertEquals(
            listOf(16_500L, 21_500L, 21_500L, 24_300L),
            entries.map { it.time },
        )
    }

    @Test
    fun showsEverySungLineOfADocumentThatRepeatsATimestamp() {
        // What the user actually reads: the spacer row drops out of the text, but the line that
        // shared its timestamp must not.
        assertEquals(
            "E ho bisogno di amici che mi facciano stare bene\n" +
                "Respiravo una cosa\n" +
                "Una cosa pericolosa",
            LyricsUtils.displayLyricsText(spacerAndSungLineAtOneTimestamp),
        )
    }

    @Test
    fun foldsTheTranslatedRowUnderItsOriginal() {
        val entries = LyricsUtils.parseLyrics("[00:16.50] Hello there\n[00:16.50] Hola")

        assertEquals(1, entries.size)
        assertEquals("Hello there", entries.single().text)
        assertEquals("Hola", entries.single().providerTranslationText)
    }

    @Test
    fun keepsRowsThatFollowASingleTranslationRow() {
        // Two translation rows at one timestamp is not a shape the AI pass produces; the second one
        // is a real line and must not be silently dropped.
        val entries = LyricsUtils.parseLyrics("[00:16.50] Hello there\n[00:16.50] Hola\n[00:16.50] Salut")

        assertEquals(listOf("Hello there", "Salut"), entries.map { it.text })
        assertEquals("Hola", entries.first().providerTranslationText)
        assertNull(entries.last().providerTranslationText)
    }
}
