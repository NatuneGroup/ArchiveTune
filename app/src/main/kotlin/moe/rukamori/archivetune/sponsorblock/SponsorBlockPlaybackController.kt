/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.sponsorblock

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import moe.rukamori.archivetune.extensions.metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeks past the stretches SponsorBlock marks on the currently playing track.
 *
 * Only ever seeks forward, and only ever out of a segment it is currently inside, so a user who
 * deliberately scrubs back into one is carried out of it again rather than fought frame by frame --
 * the segment is remembered as skipped and left alone until the track changes.
 */
@Singleton
class SponsorBlockPlaybackController
    @Inject
    constructor(
        private val repository: SponsorBlockRepository,
    ) {
        private var player: Player? = null
        private var watcher: Job? = null
        private var scope: CoroutineScope? = null
        @Volatile private var segments: List<SponsorBlockSegment> = emptyList()
        private val skippedEndsMs = mutableSetOf<Long>()

        private val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    reload(mediaItem)
                }
            }

        fun attach(player: Player, scope: CoroutineScope) {
            detach()
            this.player = player
            this.scope = scope
            player.addListener(listener)
            reload(player.currentMediaItem)
            watcher =
                scope.launch {
                    while (isActive) {
                        val current = this@SponsorBlockPlaybackController.player
                        if (current == null) {
                            delay(IDLE_POLL_MS)
                            continue
                        }
                        val playing = withContext(Dispatchers.Main) { current.isPlaying }
                        if (!playing || segments.isEmpty()) {
                            delay(IDLE_POLL_MS)
                            continue
                        }
                        withContext(Dispatchers.Main) { skipIfInsideSegment(current) }
                        delay(ACTIVE_POLL_MS)
                    }
                }
        }

        fun detach() {
            watcher?.cancel()
            watcher = null
            player?.removeListener(listener)
            player = null
            scope = null
            segments = emptyList()
            skippedEndsMs.clear()
        }

        private fun skipIfInsideSegment(player: Player) {
            val position = player.currentPosition
            val segment =
                segments.firstOrNull { position >= it.startMs && position < it.endMs - SKIP_TAIL_MS } ?: return
            if (!skippedEndsMs.add(segment.endMs)) return
            val duration = player.duration
            // Landing past the end of a track would end it early; a segment that runs to the finish
            // is left to play out instead.
            if (duration > 0 && segment.endMs >= duration - SKIP_TAIL_MS) return
            Timber.tag(TAG).d("Skipping %s: %dms -> %dms", segment.category.apiName, position, segment.endMs)
            player.seekTo(segment.endMs)
        }

        private fun reload(mediaItem: MediaItem?) {
            segments = emptyList()
            skippedEndsMs.clear()
            // Podcast episodes have no segments, on attach as much as on a transition.
            if (mediaItem?.metadata?.isPodcast == true) return
            val id = mediaItem?.mediaId ?: return
            val scope = scope ?: return
            scope.launch {
                val loaded = repository.segments(id)
                // The track may have moved on while the lookup was in flight.
                if (withContext(Dispatchers.Main) { player?.currentMediaItem?.mediaId } == id) {
                    segments = loaded
                }
            }
        }

        private companion object {
            const val TAG = "SponsorBlock"
            const val ACTIVE_POLL_MS = 200L
            const val IDLE_POLL_MS = 750L

            /** Too close to the end to be worth a seek, and close enough that seeking may overshoot. */
            const val SKIP_TAIL_MS = 300L
        }
    }
