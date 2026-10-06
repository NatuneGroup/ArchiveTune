/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.qobuz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class QobuzBundleSecretsTest {
    private val secret = "a".repeat(32)
    private val decoy = "b".repeat(32)

    @Test
    fun `a keyed secret is tried before an anonymous hex string`() {
        val candidates =
            QobuzBundleSecrets.candidates(
                """
                hex:$decoy
                keyed:$secret
                """.trimIndent(),
            )

        assertEquals(listOf(secret, decoy), candidates)
    }

    @Test
    fun `duplicates across buckets are reported once`() {
        val candidates =
            QobuzBundleSecrets.candidates(
                """
                keyed:$secret
                hex:$secret
                hex:$decoy
                """.trimIndent(),
            )

        assertEquals(listOf(secret, decoy), candidates)
    }

    @Test
    fun `the limit caps how many candidates the caller has to verify`() {
        val payload = (1..20).joinToString("\n") { "hex:%032x".format(it) }

        assertEquals(3, QobuzBundleSecrets.candidates(payload, limit = 3).size)
    }

    @Test
    fun `strings that are not 32 hex characters are dropped`() {
        val candidates =
            QobuzBundleSecrets.candidates(
                """
                keyed:tooshort
                hex:E79F8B9BE485692B0E5F9DD895826368
                hex:$secret
                garbage
                """.trimIndent(),
            )

        assertEquals(listOf(secret), candidates)
    }

    @Test
    fun `an older bundle's split secret is reassembled`() {
        val encoded = Base64.getEncoder().encodeToString(secret.toByteArray())
        val padded = encoded + "x".repeat(44)
        val seed = padded.take(10)
        val info = padded.substring(10, 30)
        val extras = padded.substring(30)

        assertEquals(
            listOf(secret),
            QobuzBundleSecrets.candidates("legacy:$seed:$info:$extras"),
        )
    }

    @Test
    fun `a legacy triple shorter than its own padding decodes to nothing`() {
        assertNull(QobuzBundleSecrets.decodeLegacy(seed = "abc", info = "def", extras = "ghi"))
    }

    @Test
    fun `a legacy triple that decodes to something other than a secret is dropped`() {
        val padded = Base64.getEncoder().encodeToString("not a secret".toByteArray()) + "x".repeat(44)

        assertNull(QobuzBundleSecrets.decodeLegacy(seed = padded, info = "", extras = ""))
    }
}
