/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis pipeline ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord), which derives it from
 * Orchard (https://github.com/SFG5453/Orchard). Orchard's original source
 * is licensed AGPL-3.0-or-later; per AGPLv3 section 13 this file is
 * combined into ArchiveTune -- a GPL-3.0-or-later work -- and remains
 * itself governed by the AGPLv3 as part of that combination.
 */

package moe.rukamori.archivetune.playback.smart
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.playback.automix.AnalysisCacheEntryIdentity
import moe.rukamori.archivetune.playback.automix.AnalysisCacheFileMetadata
import moe.rukamori.archivetune.playback.automix.AnalysisCachePolicy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Analysis results kept on disk, so a track is measured once and stays measured.
 *
 * ## Why this exists
 *
 * Analysis was in memory only, which meant every app start threw away
 * everything and every track had to earn its result again — from audio that may
 * no longer be on disk to earn it from. Two things conspire against re-earning
 * it:
 *
 *  - The analyzer needs a track's opening, contiguously, and the cache evicts
 *    openings first because they are read once and never touched again. See
 *    the analysis store, which now holds them
 *    back — but only within a budget, and only for tracks played recently.
 *  - Even with the bytes present, analysis costs a decode plus two model
 *    inferences, several seconds, and it has to finish *before* the transition
 *    that wants it. Losing that work to a restart means the next few
 *    transitions after every launch are plain crossfades.
 *
 * Neither applies to a result already computed. The audio is a means to the
 * numbers, and the numbers are small.
 *
 * ## Shape
 *
 * Only fields the planner reads are stored, and the curves are rounded to
 * milliseconds: they are the bulk of the payload and nothing downstream can
 * tell the difference.
 */
class AnalysisStore(private val context: Context) {

    /**
     * Resolved on first use, not at construction. [TrackAnalyzer] is a field
     * initializer on the playback service, which runs before the service has a
     * base context attached — asking for [Context.getFilesDir] there returns
     * null and takes the whole process down before it can start.
     */
    private val directory by lazy { File(context.filesDir, DIRECTORY) }

    /**
     * Track ids known to have no file, so a track analysed in neither this
     * session nor a previous one does not hit the filesystem on every tick.
     */
    private val known = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var lastPrunedAtEpochMs = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun sourceFingerprint(trackId: String, uri: Uri): String {
        val scheme = uri.scheme.orEmpty().lowercase()
        val authority = uri.authority.orEmpty()
        val stableUri =
            if (scheme == "content") {
                uri.toString()
            } else {
                uri.buildUpon().clearQuery().fragment(null).build().toString()
            }
        val revision = when (scheme) {
            "file" -> {
                val file = uri.path?.let { File(it) }
                "$stableUri\u0000${file?.length() ?: -1L}\u0000${file?.lastModified() ?: -1L}"
            }
            "content" -> "$stableUri\u0000${contentRevision(uri)}"
            else -> stableUri
        }
        return AnalysisCachePolicy.sourceFingerprint(trackId, "$scheme\u0000$authority", revision)
    }

    /** Reads [trackId]'s stored analysis, or null when there isn't one. */
    fun load(trackId: String): TrackAnalysis? =
        load(trackId, sourceFingerprint(trackId, Uri.EMPTY), Double.NaN)

    fun load(trackId: String, sourceFingerprint: String, expectedDurationSeconds: Double): TrackAnalysis? {
        if (trackId.isBlank()) return null
        pruneIfDue()
        val cacheFileName = AnalysisCachePolicy.fileNameFor(trackId, sourceFingerprint)
        if (known[cacheFileName] == false) return null
        val file = File(directory, cacheFileName)
        if (!file.exists()) {
            rememberMissing(cacheFileName)
            return null
        }
        if (file.length() !in 1L..AnalysisCachePolicy.MAX_BYTES) {
            file.delete()
            rememberMissing(cacheFileName)
            return null
        }
        return runCatching {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            val identity = AnalysisCacheEntryIdentity(
                schemaVersion = stored.version,
                trackId = stored.trackIdentity,
                sourceFingerprint = stored.sourceFingerprint,
                durationSeconds = stored.duration,
                savedAtEpochMs = stored.savedAtEpochMs,
            )
            require(
                AnalysisCachePolicy.isReusable(
                    entry = identity,
                    expectedTrackId = AnalysisCachePolicy.trackIdentity(trackId),
                    expectedSourceFingerprint = sourceFingerprint,
                    expectedDurationSeconds = expectedDurationSeconds,
                    nowEpochMs = System.currentTimeMillis(),
                ),
            ) { "cache identity or lifetime mismatch" }
            stored.toAnalysis(trackId)
        }
            .onFailure {
                // A half-written or outdated file is worth exactly nothing and
                // costs a re-analysis to replace, so it goes rather than being
                // returned as a partly-filled result.
                Log.w(TAG, "Discarding unreadable analysis for $trackId", it)
                file.delete()
                rememberMissing(cacheFileName)
            }
            .getOrNull()
    }

    /**
     * Stores [analysis]. Silently does nothing for a result with no usable
     * tempo: a failure is cheap to rediscover and worth rediscovering, since
     * the reason for it is usually missing bytes rather than the track itself.
     */
    fun save(trackId: String, analysis: TrackAnalysis) =
        save(trackId, sourceFingerprint(trackId, Uri.EMPTY), analysis)

    fun save(trackId: String, sourceFingerprint: String, analysis: TrackAnalysis) {
        if (trackId.isBlank() || !analysis.isUsable) return
        runCatching {
            directory.mkdirs()
            val cacheFileName = AnalysisCachePolicy.fileNameFor(trackId, sourceFingerprint)
            val file = File(directory, cacheFileName)
            // Written aside and renamed, so a kill mid-write leaves the old
            // entry rather than a truncated one.
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(
                json.encodeToString(
                    Stored.serializer(),
                    Stored.of(
                        AnalysisCachePolicy.trackIdentity(trackId),
                        sourceFingerprint,
                        analysis,
                        System.currentTimeMillis(),
                    ),
                ),
            )
            if (!temporary.renameTo(file)) temporary.delete()
            known.remove(cacheFileName)
        }.onFailure { Log.w(TAG, "Could not store analysis for $trackId", it) }
        prune()
    }

    private fun prune() {
        prune(System.currentTimeMillis())
    }

    private fun pruneIfDue() {
        val now = System.currentTimeMillis()
        if (now >= lastPrunedAtEpochMs && now - lastPrunedAtEpochMs < PRUNE_INTERVAL_MILLIS) return
        synchronized(this) {
            if (now >= lastPrunedAtEpochMs && now - lastPrunedAtEpochMs < PRUNE_INTERVAL_MILLIS) return
            prune(now)
            lastPrunedAtEpochMs = now
        }
    }

    private fun prune(nowEpochMs: Long) {
        val files = directory.listFiles()?.filter(File::isFile) ?: return
        val evicted = AnalysisCachePolicy.filesToEvict(
            files.map { file ->
                AnalysisCacheFileMetadata(
                    fileName = file.name,
                    lastModifiedEpochMs = file.lastModified(),
                    sizeBytes = file.length(),
                )
            },
            nowEpochMs,
        )
        files.filter { it.name in evicted }.forEach { it.delete() }
    }

    private fun rememberMissing(cacheFileName: String) {
        if (known.size >= MAX_KNOWN_MISSES) {
            known.keys.firstOrNull()?.let(known::remove)
        }
        known[cacheFileName] = false
    }

    private fun contentRevision(uri: Uri): String {
        val lastModified = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1L
            }
        }.getOrNull() ?: -1L
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1L
            }
        }.getOrNull() ?: -1L
        return "$size\u0000$lastModified"
    }

    /**
     * The persisted subset, kept separate from [TrackAnalysis] so that adding a
     * field to the in-memory type is not silently a schema change.
     *
     * [version] is checked on read through [ignoreUnknownKeys] plus an explicit
     * comparison: an entry written by an older build may hold numbers computed a
     * different way, and a wrong beat grid is worse than no beat grid.
     */
    @Serializable
    private data class Stored(
        val version: Int = SCHEMA_VERSION,
        val trackIdentity: String = "",
        val sourceFingerprint: String = "",
        val savedAtEpochMs: Long = 0L,
        val duration: Double = 0.0,
        val bpm: Double = 0.0,
        val beatInterval: Double = 0.0,
        val beatConfidence: Double = 0.0,
        val firstBeat: Double = 0.0,
        val downbeats: List<Double> = emptyList(),
        val phraseBoundaries: List<Double> = emptyList(),
        val key: String = "",
        val keyConfidence: Double = 0.0,
        val audibleStartTime: Double? = null,
        val pickupTime: Double? = null,
        val introEndTime: Double = 0.0,
        val outroStartTime: Double = 0.0,
        val contentEndTime: Double = 0.0,
        val finalFadeOnsetTime: Double? = null,
        val mixInTime: Double = 0.0,
        val mixOutTime: Double = 0.0,
        val mixInCandidates: List<StoredCue> = emptyList(),
        val mixOutCandidates: List<StoredCue> = emptyList(),
        val energyCurve: List<StoredEnergy> = emptyList(),
        val lowEnergyCurve: List<StoredEnergy> = emptyList(),
        val vocalActivityMask: List<Double> = emptyList(),
        val vocalProbability: Double = 0.0,
    ) {
        fun toAnalysis(trackId: String) = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = duration,
            bpm = bpm,
            beatInterval = beatInterval,
            beatConfidence = beatConfidence,
            firstBeat = firstBeat,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            key = key,
            keyConfidence = keyConfidence,
            audibleStartTime = audibleStartTime,
            pickupTime = pickupTime,
            introEndTime = introEndTime,
            outroStartTime = outroStartTime,
            contentEndTime = contentEndTime,
            finalFadeOnsetTime = finalFadeOnsetTime,
            mixInTime = mixInTime,
            mixOutTime = mixOutTime,
            mixInCandidates = mixInCandidates.map { it.toCue() },
            mixOutCandidates = mixOutCandidates.map { it.toCue() },
            energyCurve = energyCurve.map { it.toSample() },
            lowEnergyCurve = lowEnergyCurve.map { it.toSample() },
            vocalActivityMask = vocalActivityMask,
            vocalProbability = vocalProbability,
        )

        companion object {
            fun of(trackIdentity: String, sourceFingerprint: String, analysis: TrackAnalysis, savedAtEpochMs: Long) = Stored(
                trackIdentity = trackIdentity,
                sourceFingerprint = sourceFingerprint,
                savedAtEpochMs = savedAtEpochMs,
                duration = analysis.duration,
                bpm = analysis.bpm,
                beatInterval = analysis.beatInterval,
                beatConfidence = analysis.beatConfidence,
                firstBeat = analysis.firstBeat,
                downbeats = analysis.downbeats.map(::round),
                phraseBoundaries = analysis.phraseBoundaries.map(::round),
                key = analysis.key,
                keyConfidence = analysis.keyConfidence,
                audibleStartTime = analysis.audibleStartTime,
                pickupTime = analysis.pickupTime,
                introEndTime = analysis.introEndTime,
                outroStartTime = analysis.outroStartTime,
                contentEndTime = analysis.contentEndTime,
                finalFadeOnsetTime = analysis.finalFadeOnsetTime,
                mixInTime = analysis.mixInTime,
                mixOutTime = analysis.mixOutTime,
                mixInCandidates = analysis.mixInCandidates.map(StoredCue::of),
                mixOutCandidates = analysis.mixOutCandidates.map(StoredCue::of),
                energyCurve = analysis.energyCurve.map(StoredEnergy::of),
                lowEnergyCurve = analysis.lowEnergyCurve.map(StoredEnergy::of),
                vocalActivityMask = analysis.vocalActivityMask.map(::round),
                vocalProbability = analysis.vocalProbability,
            )
        }
    }

    @Serializable
    private data class StoredCue(val time: Double, val score: Double, val type: String) {
        fun toCue() = MixCandidate(time = time, score = score, type = type)

        companion object {
            fun of(cue: MixCandidate) = StoredCue(round(cue.time), round(cue.score), cue.type)
        }
    }

    @Serializable
    private data class StoredEnergy(val time: Double, val energy: Double) {
        fun toSample() = EnergySample(time = time, energy = energy)

        companion object {
            fun of(sample: EnergySample) = StoredEnergy(round(sample.time), round(sample.energy))
        }
    }

    private companion object {
        const val TAG = "BitChordAnalysisStore"
        const val DIRECTORY = "smart_analysis"

        /**
         * Bump whenever a stored number starts being computed differently.
         * Entries from an older schema are ignored rather than migrated: a
         * re-analysis costs seconds, and a beat grid interpreted under the wrong
         * assumptions is silently wrong for the life of the file.
         */
        const val SCHEMA_VERSION = AnalysisCachePolicy.SCHEMA_VERSION
        const val PRUNE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_KNOWN_MISSES = AnalysisCachePolicy.MAX_ENTRIES

        /** Milliseconds is finer than anything downstream distinguishes. */
        fun round(value: Double): Double =
            if (value.isFinite()) Math.round(value * 1000.0) / 1000.0 else 0.0
    }
}
