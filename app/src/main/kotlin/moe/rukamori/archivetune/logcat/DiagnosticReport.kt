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
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.dataStore
import java.io.IOException
import java.time.Instant

internal suspend fun diagnosticReport(context: Context, capturedText: String): String {
    val values = try {
        context.dataStore.data.first().asMap().mapKeys { it.key.name }
    } catch (_: IOException) {
        emptyMap()
    }
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
        GlobalLog.diagnosticSnapshot().forEach { appendLine(GlobalLog.format(it)) }
        appendLine()
        appendLine("Application logs:")
        GlobalLog.snapshot().forEach { appendLine(GlobalLog.format(it)) }
        appendLine()
        appendLine("Selected system/application log view:")
        appendLine(DiagnosticRedaction.redact(capturedText))
    }.let(DiagnosticRedaction::redact)
}
