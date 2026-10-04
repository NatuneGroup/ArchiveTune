/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

object DiagnosticRedaction {
    private val headers = Regex(
        """(?im)(["']?(?:authorization|proxy-authorization|cookie|set-cookie)["']?\s*[:=]\s*)[^\r\n]+""",
    )
    private val bearer = Regex("""(?i)\b(?:Bearer|Basic)\s+[^\s,"'<>]+""")
    private val credentials = Regex(
        """(?i)(\b["']?(?:token|access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|password|passwd|secret|client[_-]?secret|app[_-]?secret|api[_-]?key|read[_-]?key|source[_-]?provider[_-]?key|pool[_-]?client[_-]?key|arl|sp_dc|sessdata|account[_-]?id|user[_-]?id|username)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}\]]+)""",
    )
    private val urls = Regex("""(?i)(?:https?|tidal-dash|telegram|tgart)://[^\s<>"']+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?\b""")
    private val readKeys = Regex("""\batp_[A-Za-z0-9_-]{16,}\b""")
    private val email = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    fun redact(text: String): String {
        var result = headers.replace(text) { "${it.groupValues[1]}[redacted]" }
        result = bearer.replace(result, "[authorization redacted]")
        result = credentials.replace(result) { "${it.groupValues[1]}[redacted]" }
        result = urls.replace(result, "[url redacted]")
        result = jwt.replace(result, "[token redacted]")
        result = readKeys.replace(result, "[key redacted]")
        return email.replace(result, "[email redacted]")
    }
}
