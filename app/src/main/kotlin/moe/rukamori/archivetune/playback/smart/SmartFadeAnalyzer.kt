/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis orchestrator, ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord) and adapted to ArchiveTune's
 * streaming: BitChord reads its analysis bytes out of the player's audio
 * cache (renditions, head prefetch); ArchiveTune has no rendition concept,
 * so the analyzer owns a small dedicated LRU store of analysis-only audio
 * fetched at low quality — analysis reads tempo, structure and vocals, none
 * of which need the bitrate the listener hears.
 */

package moe.rukamori.archivetune.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import moe.rukamori.archivetune.constants.AutomixPerformanceMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import okhttp3.OkHttpClient
import okhttp3.Request

@UnstableApi
class SmartFadeAnalyzer(
    context: Context,
    /** Blocking: resolves a remote mediaId to a playable stream URL, or null. */
    private val resolveAudioUrl: (mediaId: String) -> String?,
) {
    companion object {
        private const val TAG = "SmartFadeAnalyzer"

        /**
         * The decode must cover at least this fraction of the container's
         * duration or the analysis is refused outright: a confidently wrong
         * mix-out anchor (faded out minutes early) is worse than a missing one
         * (plain crossfade fallback).
         */
        private const val MIN_DECODED_FRACTION = 0.95

        /** Neutral vocal-presence value; sits below every policy threshold. */
        private const val NEUTRAL_VOCAL = 0.5

        private const val STORE_DIR = "smartfade_audio"
        private const val MAX_STORE_FILES = 24
        private const val MAX_STORE_BYTES = 512L * 1024 * 1024

        /** A remote fetch must land at least this much audio to be worth decoding. */
        private const val MIN_ANALYSIS_BYTES = 256L * 1024

        /** A refused decode is retried at most this often before being written off. */
        private const val MAX_SHORT_DECODE_STRIKES = 3

        /**
         * Long-form audio (DJ sets, concert recordings, full mixtapes) is
         * refused outright: even with the streaming low-rate fold a 30-minute
         * mix still costs a multi-minute decode plus native copies of a
         * ~26 MB envelope signal, and the models then run over that. A plain
         * crossfade is what unanalysed material gets anyway, so the cap costs
         * nothing a listener can hear.
         */
        private const val MAX_ANALYSIS_SECONDS = 600.0

        /** Structural decode may exceed the advertised duration by this
         * fraction (sync-seek overshoot, lying timestamps) before the budget
         * stops it. */
        private const val STRUCT_DECODE_HEADROOM = 1.25

        /** Absolute slack on top of the headroom, in seconds. */
        private const val STRUCT_DECODE_SLACK_SECONDS = 15.0

        /** Head/tail region decodes get the same treatment, tighter. */
        private const val REGION_DECODE_HEADROOM = 1.25
        private const val REGION_DECODE_SLACK_SECONDS = 10.0

        /**
         * The analysis pipeline's transient Java-heap footprint (structural
         * decode + region decodes + model scratch) sits in the tens of MB; a
         * process already close to its heap ceiling gets killed by the runtime
         * the moment the next big allocation lands — with the blame pinned on
         * whatever allocated next, not on automix. Below this much FREE headroom
         * the analysis defers (a retryable miss, not a strike) instead of
         * gambling the process.
         */
        private const val MIN_FREE_HEAP_BYTES = 96L * 1024 * 1024

        /**
         * Cap on in-memory analyses: each result carries downbeats, energy
         * curves and vocal masks (~10–50 KB), and an unbounded map grows into
         * precisely the sustained pressure the heap guard watches for. Older
         * entries are evicted least-recently-inserted; the on-disk store keeps
         * every usable result, so an evicted track is re-restored on demand.
         */
        private const val MAX_IN_MEMORY_RESULTS = 64

        /** A heap deferral is a retryable miss, NOT a strike — see [request]. */
        private object Deferred
    }

    private val appContext = context.applicationContext
    private val store = AnalysisStore(appContext)
    private val tracker = BeatTracker(appContext)
    private val vocals = VocalTracker(appContext)

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Insertion order for the in-memory result LRU — see [MAX_IN_MEMORY_RESULTS]. */
    private val resultOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** Set by [release]; the worker checks it between pipeline stages so a
     * teardown-time analysis stops at the next boundary instead of holding
     * the model sessions open past the drain window (the leak path when the
     * service is recreated within the same process). */
    @Volatile
    private var released = false

    private val fetchClient =
        OkHttpClient
            .Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))
            // A hard ceiling for the whole call: a stalled transfer used to
            // hold the single analysis thread hostage forever, leaving BOTH
            // the current and the next track stuck on "analysing…".
            .callTimeout(java.time.Duration.ofSeconds(120))
            .build()

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "archivetune-smartfade-analysis").apply {
                isDaemon = true
            }
        }

    // ------------------------------------------------------------------
    // Public surface (called from the playback thread, must never block)
    // ------------------------------------------------------------------

    /** What is known about [trackId] right now; empty analysis = no evidence. */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /** True once [trackId] has a result, including a failure. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * Stores a result and keeps the in-memory map bounded — see
     * [MAX_IN_MEMORY_RESULTS]. `remove(key, value)` guards the race where the
     * same track was re-analysed after an eviction: only the stale slot is
     * dropped, never the fresher put.
     */
    private fun recordResult(trackId: String, analysis: TrackAnalysis) {
        results[trackId] = analysis
        resultOrder.addLast(trackId)
        while (resultOrder.size > MAX_IN_MEMORY_RESULTS) {
            val oldestKey = resultOrder.pollFirst() ?: break
            val oldest = results[oldestKey] ?: continue
            results.remove(oldestKey, oldest)
        }
    }

    /** True while a decode and inference for [trackId] is in flight. */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * Queues [trackId] for analysis if not already done or in flight. Cheap to
     * call repeatedly — the driver re-requests every poll tick. Local tracks
     * (content://, file://) are decoded straight off the device; remote tracks
     * are fetched once into the analysis store at low quality.
     */
    fun request(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ) {
        if (trackId.isBlank()) return
        if (trackId in running) return
        if (results.containsKey(trackId)) return
        if (!running.add(trackId)) return

        // runCatching: after release() the executor is shut down, and a bare
        // RejectedExecutionException would take the poll coroutine down with it.
        runCatching {
            executor.execute {
                try {
                    if (released) return@execute
                    // A usable stored analysis short-circuits the whole pipeline:
                    // without this head-check the first request of a session would
                    // re-earn from audio a result that was already on disk.
                    val sourceFingerprint = store.sourceFingerprint(trackId, uri)
                    val stored = store.load(trackId, sourceFingerprint, durationSeconds)
                    if (stored != null && stored.isUsable) {
                        recordResult(trackId, stored)
                        Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
                        return@execute
                    }
                    // Efficient mode yields to decoding and playback rather than
                    // competing for a core.
                    Process.setThreadPriority(
                        if (SmartFadeSettings.performanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                            Process.THREAD_PRIORITY_BACKGROUND
                        } else {
                            Process.THREAD_PRIORITY_DEFAULT
                        },
                    )
                    val analysis = analyze(trackId, uri, durationSeconds, sourceFingerprint)
                    if (analysis === Deferred) {
                        // Low heap headroom: retried on a later tick with no
                        // strike recorded. Counting these toward the write-off
                        // used to permanently fail tracks after three ticks of
                        // memory pressure — a false FAILED the UI then showed
                        // for the rest of the session.
                        Log.d(TAG, "Deferring analysis of $trackId (retryable)")
                    } else if (analysis == null) {
                        val strikes = shortDecodes[trackId] ?: 0
                        if (strikes + 1 >= MAX_SHORT_DECODE_STRIKES) {
                            // Write it off rather than re-reading it every tick.
                            recordResult(trackId, empty(trackId, durationSeconds))
                            shortDecodes.remove(trackId)
                        } else {
                            shortDecodes[trackId] = strikes + 1
                        }
                    } else {
                        // The only non-null, non-Deferred outcomes are
                        // TrackAnalysis results — the worker is single-threaded
                        // so the cast just documents that contract.
                        val result = analysis as TrackAnalysis
                        recordResult(trackId, result)
                        shortDecodes.remove(trackId)
                        if (result.isUsable) {
                            store.save(trackId, sourceFingerprint, result)
                        }
                    }
                } catch (t: java.util.concurrent.CancellationException) {
                    if (!released) Log.d(TAG, "Automix analysis cancelled")
                } catch (t: Throwable) {
                    Log.w(TAG, "Analysis of $trackId failed", t)
                    recordResult(trackId, empty(trackId, durationSeconds))
                } finally {
                    running.remove(trackId)
                }
            }
        }.onFailure {
            running.remove(trackId)
        }
    }

    fun release() {
        released = true
        executor.shutdownNow()
        // The in-flight analysis can be deep inside a native ONNX Run() that an
        // interrupt cannot stop. Closing the session underneath it is a
        // use-after-free (native crash, no Java log), so the trackers are only
        // released once the worker has actually drained — on a thread of its
        // own, because release() is called from service teardown on the main
        // thread and must never block for the seconds a long inference needs.
        // The drain window has to cover a real worst case (resolve + decode
        // wall + inference can legitimately run ~3 minutes): timing out at 10s
        // used to leave BOTH model sessions allocated forever whenever the
        // service was recreated in the same process — cumulative native growth
        // that ended in a malloc abort. The worker itself bails at stage
        // boundaries once [released] is seen, so the common case drains in
        // milliseconds; this window only matters for an interrupt-immune
        // native Run().
        Thread({
            val drained = runCatching { executor.awaitTermination(150, TimeUnit.SECONDS) }
                .isSuccess && executor.isTerminated
            if (drained) {
                tracker.release()
                vocals.release()
            } else {
                Log.w(TAG, "Analysis worker did not drain in time; leaving model sessions to process teardown")
            }
        }, "archivetune-smartfade-release").apply {
            isDaemon = true
        }.start()
    }

    // ------------------------------------------------------------------
    // Analysis pipeline
    // ------------------------------------------------------------------

    /** A null result means "not now, try again" (short decode, missing bytes);
     * [Deferred] means "not now, low heap — retry without striking". */
    private fun analyze(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        sourceFingerprint: String,
    ): Any? {
        val local = LocalAudioSource.isLocal(uri)

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource(trackId, uri, local)?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: no readable duration")
            return empty(trackId, 0.0)
        }
        if (effectiveDuration > MAX_ANALYSIS_SECONDS) {
            Log.d(
                TAG,
                "Skipping $trackId: %.0fs exceeds the analysis cap".format(Locale.ROOT, effectiveDuration),
            )
            return empty(trackId, effectiveDuration)
        }

        // Memory-pressure guard: the pipeline's peak transient footprint is
        // several tens of MB on top of whatever the player, the UI and image
        // loading already hold. When free heap runs low, defer rather than
        // push the process into the OOM-kill path mid-analysis — a deferred
        // track is retried by the poll loop on a later tick.
        if (!hasHeapHeadroom()) {
            Log.d(TAG, "Deferring analysis of $trackId: low heap headroom")
            return Deferred
        }

        // Stage breadcrumbs: every crash report from here on names the exact
        // stage the pipeline died in, instead of an OOM landing on an
        // unrelated thread with no automix line anywhere in the log.
        Log.d(TAG, "stage=fetch/decode-struct track=$trackId duration=%.1fs".format(Locale.ROOT, effectiveDuration))

        // Pass 1 (DSP-only): whole track at the analyzer's low sample rate, in
        // its own frame so the decoded buffer is collectible before Pass 2.
        // A null result means the source would not open or the decode refused
        // (retry later); empty features means the decode succeeded but the DSP
        // yielded nothing usable (recorded, degrades to a plain fade).
        val structural = structure(trackId, uri, local, effectiveDuration) ?: return null
        val features = structural.features ?: return empty(trackId, effectiveDuration)

        // Stage boundary: a release that arrived mid-decode stops the pipeline
        // here rather than handing the models several more minutes of work —
        // this is what lets the drain thread close the sessions promptly.
        if (released) return null

        Log.d(TAG, "stage=dsp track=$trackId")

        // Early publish: the whole-track DSP alone already carries a tempo
        // estimate — surface it the moment Pass 1 lands so the player's status
        // line resolves within seconds. The Beat This! / vocal model passes
        // below refine the result afterwards (the next poll picks the richer
        // numbers up). Without this, "analysing…" sat on screen for the whole
        // multi-minute pipeline even when the fast answer was already known.
        runCatching {
            val early = TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = trackId,
                duration = effectiveDuration,
                contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
                bpm = features.bpm,
                beatInterval = features.beatInterval,
                beatConfidence = features.beatConfidence,
                downbeats = features.downbeats,
                finalFadeOnsetTime = features.finalFadeOnsetTime,
            )
            if (early.isUsable) {
                recordResult(trackId, early)
                store.save(trackId, sourceFingerprint, early)
                Log.d(TAG, "Early analysis for $trackId: bpm=${features.bpm} (models still refining)")
            }
        }

        // Pass 2 (models): the Beat This! grid and the open-unmix vocal mask,
        // over the head and tail only — a transition only ever reads the tail
        // of the outgoing track and the head of the incoming one.
        if (released) return null
        if (!hasHeapHeadroom()) {
            Log.d(TAG, "Deferring model passes of $trackId: low heap headroom")
            return Deferred
        }
        Log.d(TAG, "stage=decode-region track=$trackId")
        val openTrackSource: () -> MediaDataSource? = { openSource(trackId, uri, local) }
        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, effectiveDuration - window)
        val head = region(openTrackSource, 0.0, minOf(window, effectiveDuration), features)
        val tail = if (tailStart > window / 2) region(openTrackSource, tailStart, effectiveDuration, features) else null

        val headGrid = head?.grid
        val tailGrid = tail?.grid
        // The tail governs where the outgoing track is mixed out, so it takes
        // precedence; the head is what a track uses as the incoming side.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "stage=models-done track=$trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
            finalFadeOnsetTime = features.finalFadeOnsetTime,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                .ifEmpty { features.downbeats }
                .sorted(),
            firstBeat = headGrid?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = mergeMasks(features.energyCurve.size, head?.vocalMask, tail?.vocalMask)
                ?: features.vocalActivityMask,
            vocalProbability = features.vocalProbability,
        )
    }

    /** The whole-track DSP pass outcome: features, or why they are absent. */
    private class Structural(
        val features: TrackFeatures.Features?,
    )

    /**
     * Decodes the whole track and reduces it to DSP features. In a frame of
     * its own, and returning only the features, because of what it allocates:
     * the whole track decoded to mono plus the resampled copy the DSP reads.
     * Returning is what releases them before the model pass runs.
     *
     * Null = the source would not open or the decode refused (too short);
     * non-null with null features = decoded fine, DSP found no structure.
     */
    private fun structure(
        trackId: String,
        uri: Uri,
        local: Boolean,
        effectiveDuration: Double,
    ): Structural? {
        val structRate = TrackFeatures.sampleRate
        // Streaming fold + hard budget: the decode produces the analyzer's
        // low-rate signal directly (never the container-rate full track), and
        // the budget stops a container whose timestamps lie from walking the
        // file to its true end. This is the fix for the automix force-close:
        // the old path held the whole track at container rate in Java (up to
        // ~200 MB transient on a ~100 MB heap), and the OOM landed on whatever
        // thread allocated next — lyrics, Coil, the notification — with no
        // automix line anywhere in the log.
        val decoded =
            openSource(trackId, uri, local)?.use {
                AudioDecoder.decodeRegion(
                    it,
                    0.0,
                    effectiveDuration,
                    targetSampleRate = structRate,
                    maxSeconds = effectiveDuration * STRUCT_DECODE_HEADROOM + STRUCT_DECODE_SLACK_SECONDS,
                    abort = { released },
                )
            } ?: return null
        val (pcm, _) = decoded

        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s container",
            )
            return null
        }

        // Samples arrive already folded to the analyzer's rate, so the native
        // whole-track resample pass (and the full-rate native copy it made)
        // is gone entirely.
        return Structural(
            TrackFeatures.analyze(
                pcm.samples,
                effectiveDuration,
                SmartFadeSettings.performanceMode.value,
            ),
        )
    }

    /** Everything a decoded region contributes, once its audio is let go of. */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only
     * their results. Null means "no model evidence for this window" — a codec
     * that will not configure, a region too short, a missing model.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features,
    ): Region? {
        val decoded =
            openSource()?.use {
                AudioDecoder.decodeRegionStereo(
                    it,
                    startSeconds,
                    endSeconds,
                    maxSeconds = (endSeconds - startSeconds) * REGION_DECODE_HEADROOM + REGION_DECODE_SLACK_SECONDS,
                    abort = { released },
                )
            } ?: return null
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) return null

        // In a frame of its own so the full-rate mono downmix is released
        // before either model runs.
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel: FloatArray =
            if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
                MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate) ?: return null
            } else {
                mono
            }

        return Region(
            grid = tracker.track(forModel, offsetSeconds = actualStart),
            vocalMask = vocalMask(stereo, features, actualStart),
        )
    }

    /**
     * A vocal-presence value for every point on the energy curve, filled only
     * where the model actually ran; everywhere else stays [NEUTRAL_VOCAL],
     * which sits below the policy's active threshold so unmeasured material
     * can never trip vocal logic in either direction.
     */
    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        // The beat model's window is longer than the vocal model's fixed input,
        // so the region is trimmed rather than handed over whole.
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right

        val values = vocals.track(left, right, stereo.sampleRate) ?: return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    /** Overlays the head and tail masks onto one full-length curve. */
    private fun mergeMasks(
        size: Int,
        head: DoubleArray?,
        tail: DoubleArray?,
    ): List<Double>? {
        if (size <= 0 || (head == null && tail == null)) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in listOfNotNull(head, tail)) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** True when the Java heap still has the headroom the pipeline's transient
     * allocations need — see [MIN_FREE_HEAP_BYTES]. */
    private fun hasHeapHeadroom(): Boolean {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        return free >= MIN_FREE_HEAP_BYTES
    }

    /** Recorded ready-but-empty so an undecodable track is not retried forever. */
    private fun empty(
        trackId: String,
        durationSeconds: Double,
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    // ------------------------------------------------------------------
    // Analysis audio store
    // ------------------------------------------------------------------

    /**
     * Opens a [MediaDataSource] for the track: local URIs straight off the
     * device, remote ids from the LRU analysis store — fetching on first use
     * so the *incoming* track's audio exists well before its transition.
     */
    private fun openSource(
        trackId: String,
        uri: Uri,
        local: Boolean,
    ): MediaDataSource? {
        if (local) {
            return LocalAudioSource.open(appContext.contentResolver, uri)
        }
        val file = analysisFileFor(trackId)
        if (file.exists() && file.length() >= MIN_ANALYSIS_BYTES) {
            file.setLastModified(System.currentTimeMillis())
            // runCatching: the LRU prune can delete the file between the
            // exists() check and the RandomAccessFile open — an unguarded
            // FileNotFoundException used to be swallowed by the worker's
            // catch-all as a permanent write-off for the track instead of the
            // retryable miss it is.
            return runCatching { FileMediaDataSource(file) }.getOrNull()
        }
        val fetched = fetchAnalysisAudio(trackId, file) ?: return null
        return runCatching { FileMediaDataSource(fetched) }.getOrNull()
    }

    private fun analysisFileFor(trackId: String): File {
        val dir = File(appContext.filesDir, STORE_DIR)
        if (!dir.isDirectory) dir.mkdirs()
        // Hashed names, keyed by hash AND length (the same scheme as the
        // results store): a plain hashCode hex used to let two distinct ids
        // collide onto one file, silently analysing the wrong audio.
        return File(dir, "${Integer.toHexString(trackId.hashCode())}_${trackId.length}")
    }

    /** Downloads the low-quality analysis rendition for [trackId]. */
    private fun fetchAnalysisAudio(
        trackId: String,
        target: File,
    ): File? {
        val url = runCatching { resolveAudioUrl(trackId) }.getOrNull() ?: return null
        return try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            fetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Analysis fetch for $trackId failed: HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val temp = File(target.parentFile, target.name + ".part")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (temp.length() < MIN_ANALYSIS_BYTES) {
                    temp.delete()
                    return null
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
                pruneStore()
                target
            }
        } catch (e: IOException) {
            Log.d(TAG, "Analysis fetch for $trackId failed: ${e.message}")
            runCatching { File(target.parentFile, target.name + ".part").delete() }
            null
        }
    }

    /** LRU cap: oldest files go first, both by count and by bytes. */
    private fun pruneStore() {
        val dir = File(appContext.filesDir, STORE_DIR)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var totalBytes = files.sumOf { it.length() }
        val byAge = files.sortedBy { it.lastModified() }
        var count = files.size
        for (file in byAge) {
            if (count <= MAX_STORE_FILES && totalBytes <= MAX_STORE_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                count -= 1
            }
        }
    }
}

/** Random-access [MediaDataSource] over a fully-downloaded analysis file. */
private class FileMediaDataSource(
    private val file: File,
) : MediaDataSource() {
    private val handle = RandomAccessFile(file, "r")

    // Synchronized: MediaDataSource consumers are documented as needing to be
    // called from one thread, but nothing enforces it — a future multi-threaded
    // reader would otherwise interleave seek+read pairs and corrupt the stream.
    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (size <= 0) return 0
        handle.seek(position)
        var read = 0
        while (read < size) {
            val n = handle.read(buffer, offset + read, size - read)
            if (n < 0) break
            read += n
        }
        return if (read == 0) -1 else read
    }

    @Synchronized
    override fun getSize(): Long = handle.length()

    @Synchronized
    override fun close() {
        handle.close()
    }
}
