/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.LogEntry
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val LOGCAT_POLL_INTERVAL_MILLIS = 2_000L
private const val PERSISTED_POLL_INTERVAL_MILLIS = 1_000L
private const val LOGCAT_ENTRY_LIMIT = 1_000
private const val DUPLICATE_WINDOW_MILLIS = 2_000L

@Singleton
class LogcatRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val clearTimestamp = MutableStateFlow(0L)

        fun observeEntries(): Flow<List<LogEntry>> =
            combine(
                GlobalLog.logs,
                observeLogcatSnapshots(),
                clearTimestamp,
            ) { capturedEntries, logcatEntries, clearedAt ->
                mergeCapturedAndLogcatEntries(
                    capturedEntries = capturedEntries,
                    logcatEntries = logcatEntries,
                ).asSequence()
                    .filter { entry -> entry.time >= clearedAt }
                    .sortedBy(LogEntry::time)
                    .toList()
            }.flowOn(Dispatchers.Default)

        fun clear() {
            clearTimestamp.value = System.currentTimeMillis()
            GlobalLog.clear()
        }

        suspend fun exportText(content: String): Uri =
            withContext(Dispatchers.IO) {
                val report = diagnosticReport(context, content)

                val exportDirectory = File(context.cacheDir, "shared_logs")
                if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
                    throw IOException("Unable to create log export directory")
                }

                val exportFile =
                    File(
                        exportDirectory,
                        "archivetune-log-${System.currentTimeMillis()}.txt",
                    )
                exportFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(report)
                    writer.newLine()
                }

                exportDirectory
                    .listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }
                    ?.sortedByDescending(File::lastModified)
                    ?.drop(5)
                    ?.forEach { file -> file.delete() }

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    exportFile,
                )
            }

        private fun observeLogcatSnapshots(): Flow<List<LogEntry>> =
            flow {
                var signature: String? = null
                while (currentCoroutineContext().isActive) {
                    if (PersistentLogCapture.isRunning) {
                        val current = PersistentLogCapture.viewerSignature(context)
                        if (current != signature) {
                            signature = current
                            emit(PersistentLogCapture.readForViewer(context).toLogEntries())
                        }
                        delay(PERSISTED_POLL_INTERVAL_MILLIS)
                    } else {
                        signature = null
                        emit(readLogcatEntries())
                        delay(LOGCAT_POLL_INTERVAL_MILLIS)
                    }
                }
            }.flowOn(Dispatchers.IO)

        private suspend fun readLogcatEntries(): List<LogEntry> {
            val logcatProcess =
                ProcessBuilder(
                    "logcat",
                    "--pid=${Process.myPid()}",
                    "-v",
                    "threadtime",
                    "-t",
                    LOGCAT_ENTRY_LIMIT.toString(),
                ).redirectErrorStream(true)
                    .start()

            return try {
                val text = logcatProcess.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val exitCode = logcatProcess.waitFor()
                currentCoroutineContext().ensureActive()
                if (exitCode != 0) {
                    throw IOException("logcat exited with code $exitCode")
                }
                text.lineSequence()
                    .filterNot(::isOemNoise)
                    .joinToString("\n")
                    .let(DiagnosticRedaction::redact)
                    .toLogEntries()
            } finally {
                logcatProcess.destroy()
            }
        }
    }

private fun String.toLogEntries(): List<LogEntry> = parseCapturedLog(this).mapNotNull { it.toLogEntry() }

// Both sides are already redacted (GlobalLog on append, the capture per written line).
private fun LogEntry.dedupeKey(): String = "$level|$tag|${message.substringBefore('\n')}"

/**
 * Logcat entries are the complete record (a Timber throwable arrives with its whole
 * stack trace), so they win. In-memory entries only survive while logcat has not
 * delivered the same call yet, which keeps the viewer instant without doubling lines.
 * Matching uses the first line because GlobalLog keeps only the throwable's summary.
 */
private fun mergeCapturedAndLogcatEntries(
    capturedEntries: List<LogEntry>,
    logcatEntries: List<LogEntry>,
): List<LogEntry> {
    val logcatTimes = HashMap<String, MutableList<Long>>()
    logcatEntries.forEach { entry ->
        logcatTimes.getOrPut(entry.dedupeKey()) { ArrayList(1) } += entry.time
    }
    val pendingCaptured =
        capturedEntries.filter { captured ->
            val times = logcatTimes[captured.dedupeKey()] ?: return@filter true
            times.none { time -> abs(time - captured.time) <= DUPLICATE_WINDOW_MILLIS }
        }
    return logcatEntries + pendingCaptured
}
