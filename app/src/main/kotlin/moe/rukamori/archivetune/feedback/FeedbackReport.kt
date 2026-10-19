/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

/**
 * Pure report assembly for the opt-in feedback service. Everything here is plain
 * Kotlin (no Android types) so the envelope shape and JSON escaping are covered
 * by the JVM tests in app/src/test.
 *
 * Nothing in this file sends anything: transport lives in [FeedbackApi], and
 * every send starts from an explicit user action that has already shown this
 * exact text on screen.
 */
object FeedbackReport {
    const val SCHEMA = "natune-feedback/1"
    const val KIND_PLAYBACK = "playback"
    const val KIND_CRASH = "crash"

    /** Server-side bound mirrors this; keep the two in sync. */
    const val MAX_REPORT_CHARS = 40_000
    const val MAX_NOTE_CHARS = 2_000

    /**
     * The self-describing header written at the top of every report body. Values
     * are supplied by the caller so tests can exercise the assembly without
     * touching Android classes.
     */
    fun bodyHeader(
        versionName: String,
        versionCode: Int,
        forkRevision: String,
        forkBranch: String,
        forkDirty: Boolean,
        distribution: String,
        device: String,
        architecture: String,
        buildType: String,
        osRelease: String,
        sdkInt: Int,
        manufacturer: String,
        model: String,
    ): String = buildString {
        appendLine("ArchiveTune feedback report")
        appendLine("Fork: NatuneGroup/ArchiveTune")
        appendLine("Revision: $forkRevision ($forkBranch)" + if (forkDirty) " (modified)" else "")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Variant: $distribution/$device/$architecture/$buildType")
        appendLine("Android: $osRelease (API $sdkInt)")
        append("Device: $manufacturer $model")
    }

    /** The JSON envelope posted to /api/report. Report text is already redacted upstream. */
    fun json(
        kind: String,
        sentAt: String,
        app: AppIdentity,
        device: DeviceIdentity,
        note: String,
        report: String,
    ): String {
        val trimmedNote = note.take(MAX_NOTE_CHARS)
        val trimmedReport = report.take(MAX_REPORT_CHARS)
        return buildString {
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
            append("\"note\":").append(escape(trimmedNote)).append(',')
            append("\"report\":").append(escape(trimmedReport))
            append("}")
        }
    }

    /** Minimal JSON string escaping: ASCII only, control characters never pass through raw. */
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
