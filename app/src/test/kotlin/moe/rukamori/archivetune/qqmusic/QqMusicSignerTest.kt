/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Pins QQ's request sign.
 * A signing mistake here does not look like a mistake: the request goes out, the server answers 200
 * with an empty result, and the source quietly falls through to the next one with nothing to show
 * for it. Everything else in the QQ path fails loudly by comparison, so the hash — the same one used
 * for the QR poll token and for the `g_tk` the RPC modules carry — is the piece that has to be
 * pinned against fixed inputs.
 */



package moe.rukamori.archivetune.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QqMusicSignerTest {
    /**
     * The QR poll token, for a fixed session id. The digest is the number the published algorithm
     * produces for this input; it is written out rather than recomputed so that a change to the
     * accumulator, the character handling or the mask is caught instead of being mirrored.
     */
    @Test
    fun pollTokenMatchesThePinnedDigest() {
        assertEquals(582540342, QqMusicSign.ptqrToken("@abcDEF123xyz"))
    }

    /**
     * `g_tk`, which is the same hash seeded differently. Both numbers matter: the poll token is
     * seeded at zero and `g_tk` at 5381, and using one for the other is the mistake this catches.
     */
    @Test
    fun gTkMatchesThePinnedDigest() {
        assertEquals(1821381531, QqMusicSign.gTk("Q_H_L_63k3NeU0aXqRz8n1cJ7mZ4bY2vK9wP5tS6dG0fH"))
        assertEquals(486107167, QqMusicSign.gTk("@V6kN8h2LmQp4Rt7Yw9Zx1Cd3Ef5Gh0Ji"))
    }

    /** An empty ticket still hashes: `g_tk` is the seed itself, and the poll token is zero. */
    @Test
    fun emptyInputHashesToTheSeed() {
        assertEquals(0, QqMusicSign.hash33(""))
        assertEquals(5381, QqMusicSign.gTk(""))
    }

    /**
     * The accumulator is `h * 33 + c` held inside 31 bits. Written the long way round (`(h << 5) + h`)
     * it is the same number, so a plausible refactor to one or the other must not change any digest.
     */
    @Test
    fun shiftingAndMultiplyingAgree() {
        val inputs = listOf("qrsig-value", "@abcDEF123xyz", "Q_H_L_ticket", "0123456789", "")
        for (input in inputs) {
            for (seed in listOf(0, 5381)) {
                var multiply = seed
                for (character in input) {
                    multiply = (multiply * 33 + character.code) and 0x7FFFFFFF
                }
                assertEquals(multiply, QqMusicSign.hash33(input, seed))
            }
        }
    }

    /** A different ticket must not produce the same `g_tk`, or the CSRF check proves nothing. */
    @Test
    fun gTkDependsOnTheTicket() {
        assertNotEquals(QqMusicSign.gTk("Q_H_L_one"), QqMusicSign.gTk("Q_H_L_two"))
    }
}
