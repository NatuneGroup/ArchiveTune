/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.scrobbling.LastFmScrobbleQueue
import moe.rukamori.archivetune.scrobbling.LastFmTrackMetadata
import timber.log.Timber
import kotlin.math.min

/**
 * Applies Last.fm's scrobble rule to one play at a time: a track longer than [minSongDuration]
 * scrobbles once it has actually been heard for [scrobbleDelayPercent] of its length or
 * [scrobbleDelaySeconds], whichever comes first, stamped with the second playback started.
 *
 * Listening time is accumulated from elapsed real time while the player reports it is playing, so
 * pauses and buffering do not count and seeking forward does not shortcut the threshold.
 */
class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
    context: Context? = null,
) {
    private val lock = Any()
    private var scrobbleJob: Job? = null
    private var generation = 0L
    private var songStarted = false
    private var currentMetadata: MediaMetadata? = null
    private var songStartedAt = 0L
    private var durationSeconds = 0
    private var listenedMillis = 0L
    private var playingSinceElapsed = 0L
    private var scrobbled = false
    private var lastNowPlayingElapsed = 0L
    var useNowPlaying = true

    init {
        val appContext = context?.applicationContext ?: runCatching { App.instance }.getOrNull()
        appContext?.let(LastFmScrobbleQueue::attach)
        LastFmScrobbleQueue.flushSoon()
    }

    fun destroy() {
        synchronized(lock) { resetLocked() }
    }

    fun onSongStart(
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        if (metadata == null) return
        val sendNowPlaying =
            synchronized(lock) {
                if (songStarted && currentMetadata?.id == metadata.id) {
                    updateDurationLocked(duration)
                    resumeLocked()
                    return
                }
                resetLocked()
                if (metadata.isPodcast) return
                songStarted = true
                currentMetadata = metadata
                songStartedAt = System.currentTimeMillis() / 1000
                durationSeconds = resolveDurationSeconds(metadata, duration)
                playingSinceElapsed = SystemClock.elapsedRealtime()
                scheduleLocked()
                useNowPlaying.also { if (it) lastNowPlayingElapsed = SystemClock.elapsedRealtime() }
            }
        if (sendNowPlaying) updateNowPlaying(metadata)
    }

    fun onSongResume(metadata: MediaMetadata) {
        val sendNowPlaying =
            synchronized(lock) {
                if (!songStarted || currentMetadata?.id != metadata.id) return
                resumeLocked()
            }
        if (sendNowPlaying) updateNowPlaying(metadata)
    }

    fun onSongPause() {
        synchronized(lock) { pauseLocked() }
    }

    fun onSongStop() {
        synchronized(lock) { resetLocked() }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        if (metadata == null) return
        if (isPlaying) {
            val sameSong = synchronized(lock) { songStarted && currentMetadata?.id == metadata.id }
            if (!sameSong) {
                onSongStart(metadata, duration)
            } else {
                synchronized(lock) { updateDurationLocked(duration) }
                onSongResume(metadata)
            }
        } else {
            onSongPause()
        }
    }

    private fun resolveDurationSeconds(
        metadata: MediaMetadata,
        playerDurationMs: Long?,
    ): Int {
        // Media3 reports C.TIME_UNSET (a large negative) until the source is prepared.
        val fromPlayer = playerDurationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
        return fromPlayer ?: metadata.duration.coerceAtLeast(0)
    }

    private fun updateDurationLocked(playerDurationMs: Long?) {
        val metadata = currentMetadata ?: return
        if (scrobbled) return
        val resolved = resolveDurationSeconds(metadata, playerDurationMs)
        if (resolved > 0 && resolved != durationSeconds) {
            durationSeconds = resolved
            if (playingSinceElapsed != 0L) scheduleLocked()
        }
    }

    /** Returns whether a now-playing refresh is due. */
    private fun resumeLocked(): Boolean {
        if (!songStarted) return false
        if (playingSinceElapsed == 0L) {
            playingSinceElapsed = SystemClock.elapsedRealtime()
            scheduleLocked()
        }
        // Last.fm drops a now-playing status after the track's duration; refresh it after a pause,
        // throttled so buffering stalls do not resend it on every rebuffer.
        val now = SystemClock.elapsedRealtime()
        if (!useNowPlaying || now - lastNowPlayingElapsed < NOW_PLAYING_REFRESH_MS) return false
        lastNowPlayingElapsed = now
        return true
    }

    private fun pauseLocked() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        if (playingSinceElapsed != 0L) {
            listenedMillis += SystemClock.elapsedRealtime() - playingSinceElapsed
            playingSinceElapsed = 0L
        }
    }

    private fun resetLocked() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        generation++
        songStarted = false
        currentMetadata = null
        songStartedAt = 0L
        durationSeconds = 0
        listenedMillis = 0L
        playingSinceElapsed = 0L
        scrobbled = false
        lastNowPlayingElapsed = 0L
    }

    private fun thresholdMillisLocked(): Long? {
        // Last.fm: the track must be longer than 30 seconds. An unknown length cannot be judged.
        if (durationSeconds <= 0 || durationSeconds <= minSongDuration) return null
        val byPercent = (durationSeconds * 1000L * scrobbleDelayPercent).toLong()
        return min(byPercent, scrobbleDelaySeconds * 1000L)
    }

    private fun scheduleLocked() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        if (scrobbled || playingSinceElapsed == 0L) return
        val threshold = thresholdMillisLocked() ?: return
        val heard = listenedMillis + (SystemClock.elapsedRealtime() - playingSinceElapsed)
        val remaining = (threshold - heard).coerceAtLeast(0L)
        val jobGeneration = generation
        scrobbleJob =
            scope.launch {
                delay(remaining)
                onThresholdReached(jobGeneration)
            }
    }

    private fun onThresholdReached(jobGeneration: Long) {
        val metadata: MediaMetadata
        val startedAt: Long
        val duration: Int
        synchronized(lock) {
            if (jobGeneration != generation || scrobbled) return
            metadata = currentMetadata ?: return
            scrobbled = true
            scrobbleJob = null
            startedAt = songStartedAt
            duration = durationSeconds
        }
        val track = LastFmTrackMetadata.from(metadata, duration)
        if (track == null) {
            Timber.tag(TAG).d("Not scrobbling %s: no artist or title Last.fm would accept", metadata.id)
            return
        }
        LastFmScrobbleQueue.enqueue(track, startedAt)
        Timber.tag(TAG).d("Queued scrobble: %s - %s", track.artist, track.track)
    }

    private fun updateNowPlaying(metadata: MediaMetadata) {
        val duration = synchronized(lock) { durationSeconds }
        val track = LastFmTrackMetadata.from(metadata, duration) ?: return
        val sessionKey = LastFM.sessionKey
        scope.launch {
            LastFM
                .updateNowPlaying(
                    artist = track.artist,
                    track = track.track,
                    album = track.album,
                    duration = track.durationSeconds,
                ).onSuccess {
                    Timber.tag(TAG).d("Updated now playing: %s", track.track)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // Now playing is transient by design; it is never queued or retried.
                    Timber.tag(TAG).w(throwable, "Failed to update now playing: %s", track.track)
                    LastFmScrobbleQueue.onRequestFailed(throwable, sessionKey)
                }
        }
    }

    private companion object {
        const val TAG = "ScrobbleManager"
        const val NOW_PLAYING_REFRESH_MS = 60_000L
    }
}
