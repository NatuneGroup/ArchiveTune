/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import moe.rukamori.archivetune.logcat.DiagnosticRedaction
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val time: Long,
    val level: Int,
    val tag: String?,
    val message: String,
)

object GlobalLog {
    private const val MAX_ENTRIES = 500
    // Coalesce log emissions: even when the LogcatScreen IS visible, we cap
    // StateFlow emissions at ~10/sec. A 500-element toList() allocation on
    // every log call (lyrics prefetch can emit 100+ lines/sec) was still a
    // measurable source of GC pressure even after the ring-buffer fix.
    // 100ms is fast enough that the LogcatScreen feels live, while cutting
    // allocation rate by ~10×.
    private const val FLUSH_INTERVAL_MS = 100L

    // Backing store: ArrayDeque gives O(1) add/remove at both ends, vs the
    // previous `(_logs.value + entry).takeLast(MAX_ENTRIES)` which allocated
    // two new lists (size 501 and 500) on every single log call. During lyrics
    // prefetch the app can emit hundreds of log lines per minute, and the old
    // implementation was burning ~290k element copies + 581 StateFlow emissions
    // in a few minutes — a measurable source of GC pressure and UI jank.
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val diagnosticBuffer = ArrayDeque<LogEntry>(200)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Set to true whenever append() writes to the buffer. Cleared when we
    // actually emit a snapshot to _logs. This lets us coalesce bursts of
    // log calls into a single StateFlow emission.
    @Volatile
    private var dirty = false

    @Volatile
    private var lastFlushMs = 0L

    @Synchronized
    fun append(
        level: Int,
        tag: String?,
        message: String,
    ) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, DiagnosticRedaction.redact(message))
        if (buffer.size >= MAX_ENTRIES) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
        if (tag != null && (tag.contains("Diagnostics") || tag.contains("Crossfade") || tag.contains("Authenticity"))) {
            if (diagnosticBuffer.size >= 200) diagnosticBuffer.removeFirst()
            diagnosticBuffer.addLast(entry)
        }
        dirty = true
        // CRITICAL PERF: only allocate the snapshot list + emit the StateFlow
        // value when (a) someone is actively collecting _logs AND (b) at least
        // FLUSH_INTERVAL_MS has passed since the last emission. The LogcatScreen
        // is the only consumer and is almost never visible — during normal
        // playback this skips ~100 toList() allocations/sec (each copying up
        // to 500 LogEntry refs) and ~100 StateFlow emissions/sec that the
        // karaoke syllable fill would otherwise compete with for frame budget.
        // Even with LogcatScreen open, the 100ms coalescing window cuts the
        // allocation rate by ~10× without perceptible lag in the log view.
        if (_logs.subscriptionCount.value > 0) {
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                lastFlushMs = now
                _logs.value = buffer.toList()
                dirty = false
            }
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        diagnosticBuffer.clear()
        dirty = false
        _logs.value = emptyList()
    }

    /**
     * Force-flush any pending buffer changes to the StateFlow. Called by the
     * LogcatScreen when it becomes visible so the user sees the latest logs
     * immediately instead of waiting up to [FLUSH_INTERVAL_MS].
     */
    @Synchronized
    fun flush() {
        if (dirty) {
            _logs.value = buffer.toList()
            dirty = false
            lastFlushMs = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = buffer.toList()

    @Synchronized
    fun diagnosticSnapshot(): List<LogEntry> = diagnosticBuffer.toList()

    @Synchronized
    fun format(entry: LogEntry): String {
        val ts = timeFormat.format(Date(entry.time))
        val lvl =
            when (entry.level) {
                android.util.Log.VERBOSE -> "V"
                android.util.Log.DEBUG -> "D"
                android.util.Log.INFO -> "I"
                android.util.Log.WARN -> "W"
                android.util.Log.ERROR -> "E"
                else -> "?"
            }
        val tag = entry.tag ?: ""
        return "[$ts] $lvl/$tag: ${entry.message}"
    }
}

/** Timber Tree that forwards logs to GlobalLog */
class GlobalLogTree : Timber.DebugTree() {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        try {
            val final = if (t != null) "$message\n$t" else message
            GlobalLog.append(priority, tag, final)
        } catch (_: Exception) {
            // swallow
        }
    }
}
