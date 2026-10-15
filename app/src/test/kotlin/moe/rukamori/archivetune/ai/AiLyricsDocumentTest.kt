/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what a translation pass sees, and what it writes back, when a document puts two rows on one
 * timestamp.
 *
 * A repeated timestamp is the translated row the rebuild writes under its original, so it must not
 * be translated again. It is only that when the row above it carried text — LRCLIB emits an empty
 * spacer row (`[00:21.50]`) directly above a sung line with the same time, and treating that sung
 * line as a translation row dropped it from the rebuilt document altogether (issue #1333).
 */
class AiLyricsDocumentTest {
    private val spacerAndSungLineAtOneTimestamp =
        "[00:16.50] E ho bisogno di amici che mi facciano stare bene\n" +
            "[00:21.50]\n" +
            "[00:21.50] Respiravo una cosa\n" +
            "[00:24.30] Una cosa pericolosa"

    @Test
    fun rebuildKeepsASungLineThatSharesItsTimeWithASpacerRow() {
        val document = AiLyricsDocumentParser.parse(spacerAndSungLineAtOneTimestamp)

        assertEquals(spacerAndSungLineAtOneTimestamp, document.rebuild(emptyMap()))
    }

    @Test
    fun offersEverySungLineForTranslation() {
        val document = AiLyricsDocumentParser.parse(spacerAndSungLineAtOneTimestamp)

        assertEquals(
            listOf(
                "E ho bisogno di amici che mi facciano stare bene",
                "Respiravo una cosa",
                "Una cosa pericolosa",
            ),
            document.segments.map { it.text },
        )
    }

    @Test
    fun doesNotOfferTheTranslatedRowForTranslationAgain() {
        val document = AiLyricsDocumentParser.parse("[00:16.50] Hello there\n[00:16.50] Hola")

        assertEquals(listOf("Hello there"), document.segments.map { it.text })
    }
}
