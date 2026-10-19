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

    // The unquoted value must not start with "[redacted]": redaction is applied again to feedback drafts
    // whose diagnostics were already redacted, and a second pass must leave "[redacted]" as it is.
    private val credentials = Regex(
        """(?i)(\b["']?(?:token|access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|password|passwd|secret|client[_-]?secret|app[_-]?secret|api[_-]?key|read[_-]?key|source[_-]?provider[_-]?key|pool[_-]?client[_-]?key|arl|sp_dc|sessdata|account[_-]?id|user[_-]?id|username|visitor[_-]?data|po[_-]?token|data[_-]?sync[_-]?id|session[_-]?key)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|(?!\[redacted\])[^\s,;}\]]+)""",
    )
    private val cookies = Regex(
        """\b((?:__Secure-[\w-]*?PSID|__Secure-[\w-]*?PAPISID|SAPISID|APISID|HSID|SSID|SIDCC|SID|LOGIN_INFO|VISITOR_INFO1_LIVE|YSC|PREF)=)[^\s;,"']+""",
    )
    private val urls = Regex("""(?i)(?:https?|tidal-dash|telegram|tgart|content|file)://[^\s<>"']+""")
    private val paths = Regex("""/(?:storage|sdcard|data/(?:user|data|media))/[^\s<>"',;]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    // Requires either all eight groups or a "::" so clock times such as 02:51:05 are not mistaken for addresses.
    private val ipv6 = Regex(
        """(?i)(?<![\w:])(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){7}|(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?)(?![\w:])""",
    )
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?\b""")
    private val readKeys = Regex("""\batp_[A-Za-z0-9_-]{16,}\b""")
    private val email = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    fun redact(text: String): String {
        var result = headers.replace(text) { "${it.groupValues[1]}[redacted]" }
        result = bearer.replace(result, "[authorization redacted]")
        result = credentials.replace(result) { "${it.groupValues[1]}[redacted]" }
        result = cookies.replace(result) { "${it.groupValues[1]}[redacted]" }
        result = urls.replace(result, "[url redacted]")
        result = paths.replace(result, "[path redacted]")
        result = ipv4.replace(result, "[ip redacted]")
        result = ipv6.replace(result, "[ip redacted]")
        result = jwt.replace(result, "[token redacted]")
        result = readKeys.replace(result, "[key redacted]")
        return email.replace(result, "[email redacted]")
    }
}
