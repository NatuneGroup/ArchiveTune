/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import android.content.Context
import android.util.Log
import moe.rukamori.archivetune.logcat.DiagnosticRedaction
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Crash capture for next-launch feedback.
 *
 * The dying process shows no UI and starts no upload. It only writes a small,
 * redacted text file under filesDir/feedback; if the app launches cleanly the
 * next time, the crash prompt in the feedback UI offers to review and send it,
 * and only after the user explicitly asks. Everything is skipped silently when
 * the feedback service is not configured for the build.
 */
object CrashFeedback {
    private const val TAG = "CrashFeedback"
    private const val FILE_NAME = "crash_report.txt"
    private const val STACK_CHARS = 12_000

    /** Straight-line, never coroutine-based: called by the uncaught-exception handler. */
    fun reportFile(context: Context): File = File(context.filesDir, "feedback/$FILE_NAME")

    /** Overwrites any earlier unsent report: only the most recent crash is ever offered. */
    fun capture(context: Context, stackTrace: String) {
        if (!FeedbackApi.isAvailable()) return
        try {
            val file = reportFile(context)
            file.parentFile?.mkdirs()
            file.writeText(captureBody(stackTrace))
        } catch (e: Exception) {
            Log.e(TAG, "crash capture failed", e)
        }
    }

    fun storedReport(context: Context): String? =
        try {
            reportFile(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        } catch (_: IOException) {
            null
        }

    /** Deletes the stored report. False while it is still present, so it stays offered next launch. */
    fun clear(context: Context): Boolean = reportFile(context).let { !it.exists() || it.delete() }

    private fun captureBody(stackTrace: String): String =
        buildString {
            appendLine(FeedbackDrafts.header())
            appendLine()
            appendLine("Kind: crash")
            appendLine("Captured: ${Instant.now()}")
            appendLine()
            appendLine("Stack trace (redacted):")
            // Redacted before the cut: cutting first could leave a partial secret or address past the patterns.
            append(DiagnosticRedaction.redact(stackTrace.trim()).take(STACK_CHARS))
        }
}
