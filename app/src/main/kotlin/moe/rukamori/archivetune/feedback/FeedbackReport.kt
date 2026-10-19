/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import moe.rukamori.archivetune.logcat.DiagnosticRedaction

/**
 * Pure report assembly for the opt-in feedback service. Plain Kotlin only (no
 * Android types), so the envelope shape, JSON escaping and the size bound are
 * covered by the JVM tests in app/src/test.
 *
 * Nothing in this file sends anything: transport lives in [FeedbackApi], and
 * every send starts from an explicit user action that has already shown this
 * exact text on screen.
 */
object FeedbackReport {
    const val SCHEMA = "natune-feedback/1"
    const val KIND_PLAYBACK = "playback"
    const val KIND_CRASH = "crash"

    /**
     * Largest report, in JSON-escaped UTF-8 bytes, that a reviewed draft may carry.
     * The server accepts 256 KiB bodies; this leaves room for a maximal note and the
     * envelope fields, so a reviewed report is never refused for its size.
     */
    const val MAX_REPORT_BYTES = 200 * 1024
    const val MAX_NOTE_CHARS = 2_000

    /** Opens the diagnostic section of every draft; [finish] only trims what follows it. */
    const val LOG_HEADING = "Diagnostic log (redacted, newest last):"

    /** Marks where older diagnostic lines were dropped to meet [MAX_REPORT_BYTES]. */
    const val TRUNCATED_MARKER = "[truncated]"

    /**
     * The one redaction and size pass every draft takes before review. Redacting the
     * whole assembled text, then bounding it, makes the text the user reviews exactly
     * the report [json] sends.
     */
    fun finish(draft: String): String = bounded(DiagnosticRedaction.redact(draft))

    /** Header shared by playback drafts and crash capture files. */
    fun header(
        app: AppIdentity,
        device: DeviceIdentity,
        forkDirty: Boolean,
    ): String =
        buildString {
            appendLine("ArchiveTune feedback report")
            appendLine("Fork: NatuneGroup/ArchiveTune")
            appendLine("Revision: ${app.forkRevision} (${app.forkBranch})" + if (forkDirty) " (modified)" else "")
            appendLine("Version: ${app.versionName} (${app.versionCode})")
            appendLine("Variant: ${app.variant}")
            appendLine("Android: ${device.osRelease} (API ${device.sdkInt})")
            append("Device: ${device.manufacturer} ${device.model}")
        }

    /** The JSON envelope posted to /api/report. Text is sent exactly as given; [finish] has bounded it. */
    fun json(
        kind: String,
        sentAt: String,
        app: AppIdentity,
        device: DeviceIdentity,
        note: String,
        report: String,
    ): String =
        buildString {
            append("{")
            append("\"schema\":").append(escape(SCHEMA)).append(',')
            append("\"kind\":").append(escape(kind)).append(',')
            append("\"sentAt\":").append(escape(sentAt)).append(',')
            append("\"app\":{")
            append("\"version\":").append(escape(app.versionName)).append(',')
            append("\"code\":").append(app.versionCode).append(',')
            append("\"revision\":").append(escape(app.forkRevision)).append(',')
            append("\"branch\":").append(escape(app.forkBranch)).append(',')
            append("\"variant\":").append(escape(app.variant))
            append("},")
            append("\"device\":{")
            append("\"manufacturer\":").append(escape(device.manufacturer)).append(',')
            append("\"model\":").append(escape(device.model)).append(',')
            append("\"os\":").append(escape(device.osRelease)).append(',')
            append("\"sdk\":").append(device.sdkInt)
            append("},")
            append("\"note\":").append(escape(note)).append(',')
            append("\"report\":").append(escape(report))
            append("}")
        }

    /** Minimal JSON string escaping: control characters are always escaped; other text goes out as UTF-8. */
    internal fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        out.append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (char < ' ') {
                        out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * Keeps [draft] within [MAX_REPORT_BYTES]. Everything before [LOG_HEADING] (header,
     * error or crash details) stays whole; the diagnostic log loses its oldest lines and
     * the cut is marked with [TRUNCATED_MARKER].
     */
    private fun bounded(draft: String): String {
        if (escapedUtf8Bytes(draft) <= MAX_REPORT_BYTES) return draft
        val logStart = draft.indexOf("$LOG_HEADING\n").let { if (it < 0) draft.length else it + LOG_HEADING.length + 1 }
        val head = draft.substring(0, logStart)
        val headCap = MAX_REPORT_BYTES / 2
        if (escapedUtf8Bytes(head) > headCap) {
            // Only an oversized header or error details get here; the start is kept and the log dropped.
            return prefixWithin(head, headCap) + "\n" + TRUNCATED_MARKER + "\n"
        }
        val marker = TRUNCATED_MARKER + "\n"
        val logCap = MAX_REPORT_BYTES - escapedUtf8Bytes(head) - escapedUtf8Bytes(marker)
        return head + marker + newestLinesWithin(draft.substring(logStart), logCap)
    }

    /** The newest whole lines of [log] whose escaped size fits in [maxBytes]. */
    private fun newestLinesWithin(
        log: String,
        maxBytes: Int,
    ): String {
        val kept = suffixWithin(log, maxBytes)
        return if (kept.length == log.length) kept else kept.substringAfter('\n', missingDelimiterValue = "")
    }

    private fun suffixWithin(
        text: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var start = text.length
        while (start > 0) {
            val codePoint = text.codePointBefore(start)
            bytes += escapedBytesOf(codePoint)
            if (bytes > maxBytes) break
            start -= Character.charCount(codePoint)
        }
        return text.substring(start)
    }

    private fun prefixWithin(
        text: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            bytes += escapedBytesOf(codePoint)
            if (bytes > maxBytes) break
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    /** UTF-8 bytes [value] takes inside the envelope once escaped; the surrounding quotes are not counted. */
    internal fun escapedUtf8Bytes(value: String): Int {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            bytes += escapedBytesOf(codePoint)
            index += Character.charCount(codePoint)
        }
        return bytes
    }

    /** Must mirror [escape]: a lone surrogate is counted as three bytes, which only overestimates. */
    private fun escapedBytesOf(codePoint: Int): Int =
        when {
            codePoint == '"'.code || codePoint == '\\'.code -> 2
            codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code -> 2
            codePoint == '\b'.code || codePoint == '\u000C'.code -> 2
            codePoint < ' '.code -> 6
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

    /** Build-time identity. Values come from the app's BuildConfig. */
    data class AppIdentity(
        val versionName: String,
        val versionCode: Int,
        val forkRevision: String,
        val forkBranch: String,
        val variant: String,
    )

    data class DeviceIdentity(
        val manufacturer: String,
        val model: String,
        val osRelease: String,
        val sdkInt: Int,
    )
}
