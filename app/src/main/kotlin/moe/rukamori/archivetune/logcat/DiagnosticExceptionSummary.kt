/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.logcat

import java.security.MessageDigest

private const val MAX_ERROR_RECORDS = 500
private const val MAX_RECORD_CHARACTERS = 32_768
private const val MAX_EXCEPTION_GROUPS = 8

private val exceptionName = Regex(
    """^((?:java|javax|android|androidx|kotlin|kotlinx|moe\.rukamori\.archivetune)\.(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*)(?::|$)""",
)
private val projectFrame = Regex(
    """^at\s+(moe\.rukamori\.archivetune\.[A-Za-z0-9_$.\-]+(?:\.<(?:cl)?init>)?)\(""",
)

private data class ExceptionSignature(val type: String, val origin: String?)

private fun exceptionSignature(message: String): ExceptionSignature? {
    val lines = message.take(MAX_RECORD_CHARACTERS).lineSequence().map(String::trim)
    val type = lines.mapNotNull { line ->
        exceptionName.find(line.removePrefix("Caused by: ").trim())?.groupValues?.get(1)
    }.firstOrNull { it.endsWith("Exception") || it.endsWith("Error") } ?: return null
    val origin = lines.mapNotNull { projectFrame.find(it)?.groupValues?.get(1) }.firstOrNull()
    return ExceptionSignature(type, origin)
}

private fun fingerprint(signature: ExceptionSignature): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${signature.type}|${signature.origin.orEmpty()}".toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Group only existing error calls during explicit export; messages are never identity inputs. */
internal fun diagnosticExceptionSummary(messages: List<String>): String {
    val counts = linkedMapOf<ExceptionSignature, Int>()
    for (message in messages.takeLast(MAX_ERROR_RECORDS)) {
        val signature = exceptionSignature(message) ?: continue
        counts[signature] = (counts[signature] ?: 0) + 1
    }
    return counts.entries.sortedByDescending { it.value }.take(MAX_EXCEPTION_GROUPS)
        .joinToString("\n") { (signature, count) ->
            "id=${fingerprint(signature)} records=$count exception=${signature.type} " +
                "origin=${signature.origin ?: "unavailable"}"
        }
}
