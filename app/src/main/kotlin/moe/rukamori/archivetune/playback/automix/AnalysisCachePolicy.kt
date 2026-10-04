/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

internal data class AnalysisCacheFileMetadata(
    val fileName: String,
    val lastModifiedEpochMs: Long,
    val sizeBytes: Long,
)

internal data class AnalysisCacheEntryIdentity(
    val schemaVersion: Int,
    val trackId: String,
    val sourceFingerprint: String,
    val durationSeconds: Double,
    val savedAtEpochMs: Long,
)

internal object AnalysisCachePolicy {
    const val SCHEMA_VERSION = 2
    const val MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1000
    const val MAX_ENTRIES = 256
    const val MAX_BYTES = 32L * 1024 * 1024

    fun fileNameFor(trackId: String): String = fileNameFor(trackId, "")

    fun fileNameFor(trackId: String, sourceFingerprint: String): String =
        "${digest("$trackId\u0000$sourceFingerprint")}.json"

    fun trackIdentity(trackId: String): String = digest(trackId)

    fun sourceFingerprint(trackId: String, source: String, revision: String): String =
        digest("$trackId\u0000$source\u0000$revision")

    fun isReusable(
        entry: AnalysisCacheEntryIdentity,
        expectedTrackId: String,
        expectedSourceFingerprint: String,
        expectedDurationSeconds: Double,
        nowEpochMs: Long,
    ): Boolean {
        if (entry.schemaVersion != SCHEMA_VERSION) return false
        if (entry.trackId != expectedTrackId || entry.sourceFingerprint != expectedSourceFingerprint) return false
        if (!entry.durationSeconds.isFinite() || entry.durationSeconds <= 0.0) return false
        if (entry.savedAtEpochMs <= 0L || nowEpochMs < entry.savedAtEpochMs) return false
        if (nowEpochMs - entry.savedAtEpochMs > MAX_AGE_MILLIS) return false
        if (expectedDurationSeconds.isFinite() && expectedDurationSeconds > 0.0) {
            val toleranceSeconds = max(2.0, expectedDurationSeconds * 0.02)
            if (abs(entry.durationSeconds - expectedDurationSeconds) > toleranceSeconds) return false
        }
        return true
    }

    fun filesToEvict(files: Collection<AnalysisCacheFileMetadata>, nowEpochMs: Long): Set<String> {
        val evicted = LinkedHashSet<String>()
        val retainable = files.filter { file ->
            val validName = CACHE_FILE_NAME.matches(file.fileName)
            val validTimestamp =
                file.lastModifiedEpochMs > 0L &&
                    nowEpochMs >= file.lastModifiedEpochMs &&
                    nowEpochMs - file.lastModifiedEpochMs <= MAX_AGE_MILLIS
            val validSize = file.sizeBytes in 1..MAX_BYTES
            if (!validName || !validTimestamp || !validSize) evicted += file.fileName
            validName && validTimestamp && validSize
        }.sortedWith(
            compareByDescending<AnalysisCacheFileMetadata> { it.lastModifiedEpochMs }
                .thenBy { it.fileName },
        )

        var retainedBytes = 0L
        var retainedEntries = 0
        retainable.forEach { file ->
            val fitsEntryLimit = retainedEntries < MAX_ENTRIES
            val fitsByteLimit = file.sizeBytes <= MAX_BYTES - retainedBytes
            if (fitsEntryLimit && fitsByteLimit) {
                retainedEntries += 1
                retainedBytes += file.sizeBytes
            } else {
                evicted += file.fileName
            }
        }
        return evicted
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val CACHE_FILE_NAME_PATTERN = "[0-9a-f]{64}\\.json"
    private val CACHE_FILE_NAME = Regex(CACHE_FILE_NAME_PATTERN)
}
