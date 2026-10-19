/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test
    fun credentialsAndSignedStreamUrlsAreRemoved() {
        val secrets = listOf("private-token", "private-secret", "private-password", "private-cookie", "private-header")
        val text = """
            token=private-token app_secret="private-secret" password='private-password'
            Cookie: session=private-cookie; another=private-cookie
            Authorization: Bearer private-header
            source=https://example.test/stream/private-token?signature=private-secret
            status=BUFFERING durationMs=154000
        """.trimIndent()
        val result = DiagnosticRedaction.redact(text)
        secrets.forEach { assertFalse(result.contains(it)) }
        assertTrue(result.contains("status=BUFFERING durationMs=154000"))
    }

    @Test
    fun embeddedJsonAndStandaloneTokensAreRemoved() {
        val text = listOf(
            """{"accessToken":"secret-one","appSecret":"secret-two","arl":"secret-three"}""",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
            "atp_" + "0123456789abcdef".repeat(3),
        ).joinToString(" ")
        val result = DiagnosticRedaction.redact(text)
        listOf("secret-one", "secret-two", "secret-three", "eyJhbGci", "atp_0123").forEach {
            assertFalse(result.contains(it))
        }
    }

    @Test
    fun escapedQuotesInPasswordsDoNotLeaveTheRestOfTheCredential() {
        val result = DiagnosticRedaction.redact("""password="secret\"tail" status=READY""")
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("tail"))
        assertTrue(result.contains("status=READY"))
    }

    @Test
    fun privateTelegramPathsAndEmailsAreRemoved() {
        val result = DiagnosticRedaction.redact("telegram://private-chat/private-file tgart://private-chat/name user@example.test")
        assertFalse(result.contains("private-chat"))
        assertFalse(result.contains("user@example.test"))
    }

    @Test
    fun settingsExportUsesAnExplicitNonCredentialAllowlist() {
        val result = diagnosticPreferences(
            mapOf("crossfadeEnabled" to true, "SpotifySpDc" to "secret-cookie", "sourceProviderKey" to "secret-key"),
        )
        assertTrue(result.contains("crossfadeEnabled=true"))
        assertFalse(result.contains("secret-cookie"))
        assertFalse(result.contains("secret-key"))
    }

    @Test
    fun localPathsContentUrisAddressesAndCookieNamesAreRemoved() {
        val text = """
            content://media/external/audio/42 file:///sdcard/Music/a.flac /storage/emulated/0/Music/b.mp3
            /data/user/0/moe.rukamori.archivetune/files/c.m4a ip=192.168.1.20 peer=2001:db8::1
            SAPISID=sap-secret __Secure-3PSID=psid-secret visitorData=visitor-secret poToken=po-secret
            dataSyncId=sync-secret session_key=session-secret status=READY
        """.trimIndent()
        val result = DiagnosticRedaction.redact(text)
        listOf(
            "audio/42",
            "a.flac",
            "b.mp3",
            "c.m4a",
            "192.168.1.20",
            "2001:db8",
            "sap-secret",
            "psid-secret",
            "visitor-secret",
            "po-secret",
            "sync-secret",
            "session-secret",
        ).forEach { assertFalse(it, result.contains(it)) }
        assertTrue(result.contains("status=READY"))
    }

    @Test
    fun redactionIsIdempotent() {
        // Feedback drafts redact text whose diagnostics were already redacted; a second pass must change nothing.
        val text = "token=abc SAPISID=x /storage/emulated/0/a.mp3 10.0.0.1 visitorData=v file:///x content://y status=READY"
        val once = DiagnosticRedaction.redact(text)
        assertEquals(once, DiagnosticRedaction.redact(once))
    }
}
