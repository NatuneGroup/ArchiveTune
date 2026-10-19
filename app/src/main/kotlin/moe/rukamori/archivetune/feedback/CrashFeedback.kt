/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import android.content.Context
import android.os.Build
import android.util.Log
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.logcat.DiagnosticRedaction
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Crash capture for next-launch feedback.
 *
 * The dying process shows no UI and starts no upload. It only appends a small,
 * redacted text file under filesDir/feedback; if the app launches cleanly the
 * next time, the crash prompt in the feedback UI offers to review and send it —
 * and only after the user explicitly asks. Everything is skipped silently when
 * the feedback service is not configured for the build.
 */
object CrashFeedback {
    private const val TAG = "CrashFeedback"
    private const val FILE_NAME = "crash_report.txt"
    private const val STACK_CHARS = 12_000

    /** Straight-line, never coroutine-based: called by the uncaught-exception handler. */
    fun reportFile(context: Context): File = File(context.filesDir, "feedback/$FILE_NAME")

    fun capture(context: Context, stackTrace: String) {
        if (!FeedbackApi.isAvailable()) return
        try {
            val file = reportFile(context)
            file.parentFile?.let { dir ->
                if (!dir.isDirectory) dir.mkdirs()
            }
            file.writeText(captureBody(stackTrace))
        } catch (e: Exception) {
            Log.e(TAG, "crash capture failed", e)
        }
    }

    fun storedReport(context: Context): String? =
        try {
            reportFile(context).let { file ->
                file.takeIf { it.isFile }?.bufferedReader()?.use { reader ->
                    val text = reader.readText()
                    text.takeIf { it.isNotBlank() }
                }
            }
        } catch (_: IOException) {
            null
        }

    /** Removes the stored crash report (after a send, or when the user declines). */
    fun clear(context: Context) {
        try {
            reportFile(context).delete()
        } catch (_: SecurityException) {
        }
    }

    private fun captureBody(stackTrace: String): String =
        buildString {
            appendLine("ArchiveTune crash report (captured at ${Instant.now()})")
            appendLine("Kind: crash")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "Revision: ${BuildConfig.FORK_REVISION} (${BuildConfig.FORK_BRANCH})" +
                    if (BuildConfig.FORK_DIRTY) " (modified)" else "",
            )
            appendLine("Variant: ${BuildConfig.DISTRIBUTION}/${BuildConfig.DEVICE}/${BuildConfig.ARCHITECTURE}/${BuildConfig.BUILD_TYPE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Stack trace (redacted):")
            append(stackLine(stackTrace))
        }

    private fun stackLine(stackTrace: String): String =
        DiagnosticRedaction.redact(stackTrace.trim()).take(STACK_CHARS)
}
