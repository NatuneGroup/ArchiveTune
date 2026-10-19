/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.dataStore
import java.io.IOException
import java.time.Instant

internal suspend fun diagnosticReport(context: Context, capturedText: String): String =
    DiagnosticRedaction.redact(unredactedDiagnosticReport(context, capturedText))

/**
 * The report sections before redaction. A caller that publishes the text must redact it
 * once as a whole: [diagnosticReport] does so for the log export, and feedback drafts do
 * so together with their own sections.
 */
internal suspend fun unredactedDiagnosticReport(context: Context, capturedText: String): String {
    val values = try {
        context.dataStore.data.first().asMap().mapKeys { it.key.name }
    } catch (_: IOException) {
        emptyMap()
    }
    val persisted = parseCapturedLog(PersistentLogCapture.readForExport(context))
    return buildString {
        appendLine("ArchiveTune diagnostic report v2")
        appendLine("Fork: NatuneGroup/ArchiveTune")
        appendLine("Revision: ${BuildConfig.FORK_REVISION} (${BuildConfig.FORK_BRANCH})")
        appendLine("Local modifications: ${BuildConfig.FORK_DIRTY}")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Variant: ${BuildConfig.DISTRIBUTION}/${BuildConfig.DEVICE}/${BuildConfig.ARCHITECTURE}/${BuildConfig.BUILD_TYPE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Captured: ${Instant.now()}")
        appendLine()
        appendLine("Stored playback and presentation settings (unset values use the app's defaults):")
        appendLine(diagnosticPreferences(values))
        appendLine()
        appendLine("Retained playback diagnostics:")
        // The in-memory ring keeps this process's diagnostics even when a retry storm has
        // pushed them out of the capped file window; earlier processes only exist on disk.
        val myPid = Process.myPid()
        val diagnostics = persisted.filter { !it.isMarker && GlobalLog.isDiagnosticTag(it.tag) }
        val currentRing = GlobalLog.diagnosticSnapshot()
        val fromFiles = if (currentRing.isEmpty()) diagnostics else diagnostics.filter { it.pid != myPid }
        append(formatCapturedLog(fromFiles.takeLast(RETAINED_DIAGNOSTICS_LIMIT)))
        currentRing.forEach { appendLine(GlobalLog.format(it)) }
        appendLine()
        if (persisted.any { !it.isMarker }) {
            appendLine("Process log (logcat for this app; earlier sessions first, newest last):")
            append(formatCapturedLog(persisted))
        } else {
            // Persistent capture unavailable (logcat missing or never started): fall back
            // to the in-memory ring and the on-screen selection so the report is not empty.
            appendLine("Application logs:")
            GlobalLog.snapshot().forEach { appendLine(GlobalLog.format(it)) }
            appendLine()
            appendLine("Selected system/application log view:")
            appendLine(capturedText)
        }
    }
}

private const val RETAINED_DIAGNOSTICS_LIMIT = 200
