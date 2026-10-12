/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import moe.rukamori.archivetune.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams this process's own logcat (no READ_LOGS needed for our pid) into rotating
 * files under filesDir/logs, so exported reports carry what Media3, libraries and
 * Timber actually logged, including the end of the previous run after a crash.
 *
 * Files are named s<sessionStartMillis>-p<part>.log. The current session keeps its
 * newest [PARTS_PER_SESSION] parts of [PART_BYTES]; earlier sessions are pruned at
 * start to [PREVIOUS_RETENTION_BYTES], always keeping the most recent one whole.
 */
object PersistentLogCapture {
    private const val TAG = "LogCapture"
    private const val DIRECTORY = "logs"
    private const val CRASH_CURSOR = "crash.cursor"
    private const val PART_BYTES = 1L shl 20
    private const val PARTS_PER_SESSION = 2
    private const val PREVIOUS_RETENTION_BYTES = 2L shl 20
    private const val EXPORT_BYTES = 1_536L * 1024
    private const val MIN_PREVIOUS_EXPORT_BYTES = 512L * 1024
    private const val VIEWER_TAIL_BYTES = 256L * 1024
    private const val WRITE_BUFFER_BYTES = 16 * 1024
    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val HEALTHY_RUN_MS = 60_000L
    private const val MAX_QUICK_FAILURES = 8
    private const val SINCE_PROBE_MS = 5_000L
    private const val CRASH_IMPORT_LINES = 400
    private const val CRASH_IMPORT_TIMEOUT_SECONDS = 5L
    private val fileName = Regex("""s(\d+)-p(\d+)\.log""")

    private class LogFile(
        val session: Long,
        val part: Int,
        val file: File,
    )

    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var output: BufferedOutputStream? = null
    private var partBytes = 0L
    private var part = 0

    @Volatile
    private var sessionStart = 0L

    @Volatile
    private var directory: File? = null

    @Volatile
    private var writeFailed = false

    @Volatile
    var isRunning = false
        private set

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val logDirectory = File(context.filesDir, DIRECTORY)
        Thread({ run(logDirectory) }, TAG)
            .apply { isDaemon = true }
            .start()
    }

    fun flush() {
        synchronized(lock) {
            try {
                output?.flush()
            } catch (_: IOException) {
            }
        }
    }

    /** Earlier sessions first, newest line last, capped at [EXPORT_BYTES]. */
    internal fun readForExport(context: Context): String {
        flush()
        val files = listLogFiles(logDirectory(context))
        val current = files.filter { it.session == sessionStart }
        val previous = files.filter { it.session != sessionStart }
        val currentBytes = current.sumOf { it.file.length() }
        val previousBudget = maxOf(MIN_PREVIOUS_EXPORT_BYTES, EXPORT_BYTES - currentBytes).coerceAtMost(EXPORT_BYTES)
        val previousTail = readTail(previous.map(LogFile::file), previousBudget)
        val currentTail = readTail(current.map(LogFile::file), EXPORT_BYTES - previousTail.size)
        return String(previousTail + currentTail, Charsets.UTF_8)
    }

    internal fun readForViewer(context: Context): String {
        flush()
        val files = listLogFiles(logDirectory(context)).map(LogFile::file)
        return String(readTail(files, VIEWER_TAIL_BYTES), Charsets.UTF_8)
    }

    /** Cheap change detector so the viewer only re-parses when something was written. */
    internal fun viewerSignature(context: Context): String =
        listLogFiles(logDirectory(context)).joinToString { "${it.file.name}:${it.file.length()}" }

    private fun logDirectory(context: Context) = directory ?: File(context.filesDir, DIRECTORY)

    private fun run(logDirectory: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            if (!logDirectory.isDirectory && !logDirectory.mkdirs()) return
            val processStart =
                System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            sessionStart = System.currentTimeMillis()
            pruneEarlierSessions(logDirectory)
            directory = logDirectory
            synchronized(lock) { openPart(logDirectory, 0) }
            writeLine(
                "--------- ArchiveTune session ${Instant.ofEpochMilli(sessionStart)} pid=${Process.myPid()} " +
                    "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) revision=${BuildConfig.FORK_REVISION}",
            )
            importCrashBuffer(logDirectory, processStart)
            flush()
            if (writeFailed) return
            isRunning = true
            readLoop(processStart)
        } catch (error: IOException) {
            Log.w(TAG, "Persistent log capture stopped", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Persistent log capture stopped", error)
        } finally {
            isRunning = false
            synchronized(lock) {
                try {
                    output?.close()
                } catch (_: IOException) {
                }
                output = null
            }
        }
    }

    private fun readLoop(processStart: Long) {
        // Lines at or before this instant were already written (or belong to an earlier
        // process that had our pid); a restarted reader replays the buffer, so the
        // boundary millisecond is deduplicated by exact line text.
        var lastMillis = processStart - MIN_BACKOFF_MS
        val linesAtLastMillis = HashSet<String>()
        var useSinceFilter = true
        var quickFailures = 0

        while (true) {
            val launchedAt = SystemClock.elapsedRealtime()
            var stampedLines = 0
            val command =
                buildList {
                    add("logcat")
                    add("--pid=${Process.myPid()}")
                    add("-v")
                    add("threadtime")
                    if (useSinceFilter) {
                        add("-T")
                        add(epochMillisToStamp(lastMillis))
                    }
                }
            try {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
                try {
                    var replaying = true
                    while (true) {
                        val line = reader.readLine() ?: break
                        val millis = threadtimeMillis(line) ?: continue
                        stampedLines++
                        if (replaying) {
                            if (millis < lastMillis) continue
                            if (millis == lastMillis && !linesAtLastMillis.add(line)) continue
                            if (millis > lastMillis) replaying = false
                        }
                        if (millis > lastMillis) {
                            lastMillis = millis
                            linesAtLastMillis.clear()
                        }
                        if (millis == lastMillis) linesAtLastMillis.add(line)
                        if (isOemNoise(line)) continue
                        writeLine(line)
                        if (writeFailed) break
                        // Flushing only when the pipe is drained batches bursts into one
                        // write, yet leaves almost nothing in memory if the process is killed.
                        if (!reader.ready()) flush()
                    }
                } finally {
                    process.destroy()
                    reader.close()
                }
            } catch (_: IOException) {
            }
            flush()
            if (writeFailed) {
                Log.w(TAG, "Log file write failed; persistent log capture disabled for this session")
                return
            }

            val ranFor = SystemClock.elapsedRealtime() - launchedAt
            if (useSinceFilter && stampedLines == 0 && ranFor < SINCE_PROBE_MS) {
                // An older logcat that rejects -T exits at once with only a usage text;
                // the replay filter above still prevents duplicates without it.
                useSinceFilter = false
            }
            quickFailures = if (ranFor >= HEALTHY_RUN_MS) 0 else quickFailures + 1
            if (quickFailures >= MAX_QUICK_FAILURES) {
                Log.w(TAG, "logcat keeps exiting; persistent log capture disabled for this session")
                return
            }
            val backoff = (MIN_BACKOFF_MS shl (quickFailures - 1).coerceIn(0, 6)).coerceAtMost(MAX_BACKOFF_MS)
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Native crash dumps land in the crash buffer under the dead process's pid, which the
     * pid-filtered reader of the next process never sees.
     */
    private fun importCrashBuffer(
        logDirectory: File,
        processStart: Long,
    ) {
        val cursorFile = File(logDirectory, CRASH_CURSOR)
        val cursor = runCatching { cursorFile.readText().trim().toLong() }.getOrDefault(0L)
        val lines =
            try {
                val process =
                    ProcessBuilder(
                        "logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", CRASH_IMPORT_LINES.toString(),
                    ).redirectErrorStream(true).start()
                try {
                    val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    process.waitFor(CRASH_IMPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    text.lines()
                } finally {
                    process.destroy()
                }
            } catch (_: IOException) {
                return
            } catch (_: InterruptedException) {
                return
            }
        val myPid = Process.myPid()
        var newest = cursor
        val imported =
            lines.filter { line ->
                val millis = threadtimeMillis(line) ?: return@filter false
                val pid = parseThreadtimeLine(line)?.pid ?: return@filter false
                val fresh = millis > cursor && millis < processStart && pid != myPid
                if (fresh) newest = maxOf(newest, millis)
                fresh
            }
        if (imported.isEmpty()) return
        writeLine("--------- crash buffer entries from earlier processes")
        imported.forEach(::writeLine)
        runCatching { cursorFile.writeText(newest.toString()) }
    }

    private fun writeLine(line: String) {
        val bytes = (DiagnosticRedaction.redact(line) + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val logDirectory = directory ?: return
            try {
                if (partBytes > 0 && partBytes + bytes.size > PART_BYTES) openPart(logDirectory, part + 1)
                val stream = output ?: return
                stream.write(bytes)
                partBytes += bytes.size
            } catch (_: IOException) {
                writeFailed = true
            }
        }
    }

    private fun openPart(
        logDirectory: File,
        index: Int,
    ) {
        output?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        val file = File(logDirectory, "s$sessionStart-p$index.log")
        output = BufferedOutputStream(FileOutputStream(file, true), WRITE_BUFFER_BYTES)
        partBytes = file.length()
        part = index
        File(logDirectory, "s$sessionStart-p${index - PARTS_PER_SESSION}.log").delete()
    }

    private fun pruneEarlierSessions(logDirectory: File) {
        logDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name != CRASH_CURSOR && fileName.matchEntire(file.name) == null) file.delete()
        }
        var retained = 0L
        listLogFiles(logDirectory)
            .groupBy(LogFile::session)
            .toSortedMap(compareByDescending { it })
            .values
            .forEachIndexed { index, sessionFiles ->
                val size = sessionFiles.sumOf { it.file.length() }
                if (index == 0 || retained + size <= PREVIOUS_RETENTION_BYTES) {
                    retained += size
                } else {
                    sessionFiles.forEach { it.file.delete() }
                }
            }
    }

    private fun listLogFiles(logDirectory: File): List<LogFile> =
        logDirectory
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                val match = fileName.matchEntire(file.name) ?: return@mapNotNull null
                val session = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val index = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                LogFile(session, index, file)
            }.sortedWith(compareBy<LogFile> { it.session }.thenBy { it.part })

    /** The last [budget] bytes across [files] in order, never starting mid-line. */
    private fun readTail(
        files: List<File>,
        budget: Long,
    ): ByteArray {
        if (budget <= 0) return ByteArray(0)
        val chunks = ArrayList<ByteArray>()
        var remaining = budget
        for (file in files.asReversed()) {
            if (remaining <= 0) break
            val length = file.length()
            if (length == 0L) continue
            try {
                RandomAccessFile(file, "r").use { access ->
                    val take = minOf(length, remaining)
                    val bytes = ByteArray(take.toInt())
                    access.seek(length - take)
                    access.readFully(bytes)
                    val chunk =
                        if (take < length) {
                            val newline = bytes.indexOf('\n'.code.toByte())
                            if (newline < 0) ByteArray(0) else bytes.copyOfRange(newline + 1, bytes.size)
                        } else {
                            bytes
                        }
                    chunks += chunk
                    remaining -= take
                }
            } catch (_: IOException) {
            }
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.asReversed().forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
