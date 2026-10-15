/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

import android.util.Log
import moe.rukamori.archivetune.utils.LogEntry
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Framework tags that OEM ROMs log into every app process. They say nothing about
 * playback and used to fill a third of an exported report.
 */
internal val oemNoiseTags =
    setOf(
        "PowerHalMgrImpl",
        "IJankManager",
        "ScrollIdentify",
        "ViewRootImplExtImpl",
        "QualityInfo",
        "WindowOnBackDispatcher",
    )

private const val STAMP_LENGTH = 18
private const val DAY_MILLIS = 86_400_000L
private const val REPEAT_KEY_MAX_LENGTH = 500

private val threadtimeLine =
    Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s(.*?)\s*: ?(.*)$""")
private val repeatDigits = Regex("""\d+""")
private val stampFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

/** One logical log call: a threadtime header plus every continuation line logcat printed for it. */
internal data class CapturedRecord(
    val stamp: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
) {
    val isMarker: Boolean get() = level == MARKER_LEVEL

    fun sameCall(other: CapturedRecord): Boolean =
        !isMarker && stamp == other.stamp && pid == other.pid && tid == other.tid &&
            level == other.level && tag == other.tag

    companion object {
        const val MARKER_LEVEL = '-'

        fun marker(line: String) = CapturedRecord("", 0, 0, MARKER_LEVEL, "", line)
    }
}

internal fun parseThreadtimeLine(line: String): CapturedRecord? {
    val match = threadtimeLine.matchEntire(line) ?: return null
    val (stamp, pid, tid, level, tag, message) = match.destructured
    return CapturedRecord(stamp, pid.toInt(), tid.toInt(), level[0], tag.trim(), message)
}

internal fun threadtimeMillis(line: String): Long? =
    if (line.length < STAMP_LENGTH) null else stampToEpochMillis(line.substring(0, STAMP_LENGTH))

internal fun isOemNoise(line: String): Boolean {
    val tag = parseThreadtimeLine(line)?.tag ?: return false
    return tag in oemNoiseTags
}

/**
 * logcat prints no year. The newest plausible year is assumed, so lines captured on
 * 31 December still sort before lines from 1 January.
 */
internal fun stampToEpochMillis(
    stamp: String,
    now: Long = System.currentTimeMillis(),
): Long? {
    if (stamp.length != STAMP_LENGTH || stamp[2] != '-' || stamp[5] != ' ' || stamp[14] != '.') return null
    return try {
        val month = stamp.substring(0, 2).toInt()
        val day = stamp.substring(3, 5).toInt()
        val hour = stamp.substring(6, 8).toInt()
        val minute = stamp.substring(9, 11).toInt()
        val second = stamp.substring(12, 14).toInt()
        val millis = stamp.substring(15, 18).toInt()
        val zone = ZoneId.systemDefault()
        val year = Instant.ofEpochMilli(now).atZone(zone).year
        fun at(y: Int) =
            LocalDateTime.of(y, month, day, hour, minute, second, millis * 1_000_000)
                .atZone(zone).toInstant().toEpochMilli()
        val candidate = runCatching { at(year) }.getOrNull()
        if (candidate == null || candidate > now + DAY_MILLIS) at(year - 1) else candidate
    } catch (_: NumberFormatException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}

internal fun epochMillisToStamp(epochMillis: Long): String =
    stampFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/**
 * logcat repeats the full header on every line of a multi-line message, so a Timber
 * call with a throwable arrives as one line per stack frame. Lines that share
 * timestamp, pid, tid, level and tag are folded back into the call that produced them.
 * Two distinct calls on one thread in the same millisecond with the same tag are
 * indistinguishable and end up as one block, which is harmless for reading.
 */
internal fun parseCapturedLog(text: String): List<CapturedRecord> {
    val records = ArrayList<CapturedRecord>()
    var pending: CapturedRecord? = null
    val message = StringBuilder()

    fun flushPending() {
        pending?.let { records += it.copy(message = message.toString()) }
        pending = null
        message.setLength(0)
    }

    text.lineSequence().forEach { line ->
        if (line.isBlank()) return@forEach
        val parsed = parseThreadtimeLine(line)
        if (parsed == null) {
            flushPending()
            records += CapturedRecord.marker(line)
            return@forEach
        }
        val current = pending
        if (current != null && current.sameCall(parsed)) {
            message.append('\n').append(parsed.message)
        } else {
            flushPending()
            pending = parsed
            message.append(parsed.message)
        }
    }
    flushPending()
    return records
}

internal fun CapturedRecord.toLogEntry(): LogEntry? {
    if (isMarker) return null
    val time = stampToEpochMillis(stamp) ?: return null
    val priority =
        when (level) {
            'V' -> Log.VERBOSE
            'D' -> Log.DEBUG
            'I' -> Log.INFO
            'W' -> Log.WARN
            else -> Log.ERROR
        }
    return LogEntry(time = time, level = priority, tag = tag, message = message)
}

private fun CapturedRecord.repeatKey(): String? {
    if (isMarker) return null
    val normalized =
        if (message.length <= REPEAT_KEY_MAX_LENGTH) repeatDigits.replace(message, "#") else message
    return "$pid|$level|$tag|$normalized"
}

/**
 * Renders records in threadtime order, newest last. Consecutive calls that differ only
 * in their numbers (retry counters, durations, positions) collapse into one line plus a
 * repeat count, which keeps a retry storm from pushing the useful context out of the cap.
 */
internal fun formatCapturedLog(records: List<CapturedRecord>): String =
    buildString {
        var index = 0
        while (index < records.size) {
            val record = records[index]
            val key = record.repeatKey()
            var end = index + 1
            if (key != null) {
                while (end < records.size && records[end].repeatKey() == key) end++
            }
            if (record.isMarker) {
                appendLine(record.message)
            } else {
                append(record.stamp).append(' ')
                append(record.pid).append(' ').append(record.tid).append(' ')
                append(record.level).append('/').append(record.tag).append(": ")
                appendLine(record.message)
            }
            val repeats = end - index - 1
            if (repeats > 0) {
                appendLine("    … repeated $repeats× more, last at ${records[end - 1].stamp}")
            }
            index = end
        }
    }
