/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import android.os.Bundle
import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import java.io.Serializable

enum class FinitePlaylistPhase { INITIALIZING, UNKNOWN, PAGEABLE, EXHAUSTED, FAILED }

data class PlaylistLoadState(
    val loading: Boolean = false,
    val incomplete: Boolean = false,
    val wholeAction: Boolean = false,
    val showDialog: Boolean = false,
)

data class FinitePlaylistEntry(
    val metadata: MediaMetadata,
    val key: String,
    val originalIndex: Int?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

data class FinitePlaylistSnapshot(
    val title: String? = null,
    val entries: List<FinitePlaylistEntry> = emptyList(),
    val continuation: String? = null,
    val phase: FinitePlaylistPhase = FinitePlaylistPhase.INITIALIZING,
    val consumedTokens: Set<String> = emptySet(),
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Network work produces a proposal. Only MusicService's current generation can accept it,
 * on the player thread in the same non-suspending section as timeline insertion.
 */
class YouTubePlaylistQueue(
    val endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
    snapshot: FinitePlaylistSnapshot = FinitePlaylistSnapshot(),
    internal val legacyItems: List<MediaMetadata>? = null,
    internal val restoredTimelineKeys: List<String?>? = null,
    internal val shuffled: Boolean = false,
    internal val shuffleAfterCompletion: Boolean = false,
) : Queue {
    var snapshot: FinitePlaylistSnapshot = snapshot
        private set
    val playlistId = requireNotNull(endpoint.playlistId).normalizedPlaylistId()
    override val nextPageRepeatsCurrentItem = false
    val confirmedExhausted get() = snapshot.phase == FinitePlaylistPhase.EXHAUSTED
    internal var preloadedItem: MediaItem? = null
    internal var preloadedKey: String? = null
    internal var timelineInstalled = false
    internal var initialTimelineCleared = false
    private val reconciledOccurrences = java.util.IdentityHashMap<MediaItem, String>()
    internal fun rememberOccurrence(item: MediaItem, key: String) { reconciledOccurrences[item] = key }
    internal fun occurrenceOf(item: MediaItem): String? =
        item.finiteOccurrence() ?: reconciledOccurrences[item] ?: preloadedKey.takeIf { item === preloadedItem }

    internal fun beginPlayback() {
        preloadedItem = null
        preloadedKey = null
        reconciledOccurrences.clear()
        timelineInstalled = false
        initialTimelineCleared = false
    }

    internal data class Proposal(
        val snapshot: FinitePlaylistSnapshot,
        val entries: List<FinitePlaylistEntry>,
        val selectedIndex: Int = 0,
    )

    internal fun accept(proposal: Proposal) { snapshot = proposal.snapshot }
    internal fun fail() { snapshot = snapshot.copy(phase = FinitePlaylistPhase.FAILED) }
    internal fun restoreMediaItems(items: List<MediaMetadata>): List<MediaItem> =
        items.mapIndexed { index, metadata ->
            metadata.toMediaItem().withFiniteOccurrence(restoredTimelineKeys?.getOrNull(index))
        }

    override fun hasNextPage() = snapshot.phase == FinitePlaylistPhase.PAGEABLE

    // These methods remain usable by generic Queue clients, but never consume/commit a token.
    // MusicService uses proposal/accept; no IO thread can advance the live queue independently.
    override suspend fun getInitialStatus(): Queue.Status = status(proposeInitial())
    override suspend fun nextPage(): List<MediaItem> = proposeNext().entries.map { it.toMediaItem() }

    internal fun status(proposal: Proposal): Queue.Status {
        val entries = if (shuffled) proposal.entries.shuffled() else proposal.entries
        return Queue.Status(proposal.snapshot.title, entries.map { it.toMediaItem() }, if (shuffled) 0 else proposal.selectedIndex)
    }

    internal suspend fun proposeInitial(
        source: FinitePlaylistSource = YouTubeFinitePlaylistSource,
    ): Proposal = FinitePlaylistLoader.bounded {
        if (snapshot.phase == FinitePlaylistPhase.EXHAUSTED || snapshot.phase == FinitePlaylistPhase.PAGEABLE) {
            return@bounded Proposal(snapshot, snapshot.entries, selectedIndex(snapshot.entries) ?: 0)
        }
        val first = source.initial(playlistId)
        FinitePlaylistLoader.requireConfirmedInitial(first)
        var token = first.songsContinuation ?: first.continuation
        FinitePlaylistLoader.validateNext(token, emptySet(), first.songs, initial = true)
        var entries = appendEntries(emptyList(), first.songs)
        val consumed = mutableSetOf<String>()
        var requests = 1
        var selected = selectedIndex(entries)
        val selectedVideoId = preloadItem?.id ?: endpoint.videoId
        while (token != null && (legacyItems != null || (selectedVideoId != null && selected == null))) {
            if (++requests > FinitePlaylistLoader.MAX_REQUESTS || !consumed.add(token)) throw IncompleteYouTubePlaylistException()
            val page = source.continuation(token, playlistId)
            FinitePlaylistLoader.validateNext(page.continuation, consumed, page.songs)
            entries = appendEntries(entries, page.songs)
            token = page.continuation
            selected = selectedIndex(entries)
        }
        if (selectedVideoId != null && selected == null) {
            // Without a validated locator, only a unique video in a *complete* source is safe.
            val matches = entries.indices.filter { entries[it].metadata.id == selectedVideoId }
            if (token != null || matches.size != 1) throw IncompleteYouTubePlaylistException()
            selected = matches.single()
        }
        Proposal(
            FinitePlaylistSnapshot(
                first.playlist.title, entries, token,
                if (token == null) FinitePlaylistPhase.EXHAUSTED else FinitePlaylistPhase.PAGEABLE,
                consumed,
            ),
            entries,
            selected ?: 0,
        )
    }

    internal suspend fun proposeNext(
        source: FinitePlaylistSource = YouTubeFinitePlaylistSource,
    ): Proposal = FinitePlaylistLoader.bounded {
        val token = snapshot.continuation ?: throw IncompleteYouTubePlaylistException()
        val consumed = snapshot.consumedTokens + token
        val page = source.continuation(token, playlistId)
        FinitePlaylistLoader.validateNext(page.continuation, consumed, page.songs)
        val allEntries = appendEntries(snapshot.entries, page.songs)
        Proposal(
            snapshot.copy(
                entries = allEntries,
                continuation = page.continuation,
                phase = if (page.continuation == null) FinitePlaylistPhase.EXHAUSTED else FinitePlaylistPhase.PAGEABLE,
                consumedTokens = consumed,
            ),
            allEntries.drop(snapshot.entries.size),
        )
    }

    private fun selectedIndex(entries: List<FinitePlaylistEntry>): Int? {
        val videoId = preloadItem?.id ?: endpoint.videoId ?: return 0
        val setId = endpoint.playlistSetVideoId?.takeIf(String::isNotBlank) ?: preloadItem?.setVideoId?.takeIf(String::isNotBlank)
        val matches = entries.indices.filter { index ->
            val entry = entries[index]
            entry.metadata.id == videoId &&
                if (setId != null) entry.metadata.setVideoId == setId
                else endpoint.index != null && entry.originalIndex == endpoint.index
        }
        return matches.singleOrNull()
    }

    private fun appendEntries(existing: List<FinitePlaylistEntry>, songs: List<SongItem>): List<FinitePlaylistEntry> {
        val keys = existing.mapTo(mutableSetOf()) { it.key }
        val result = existing.toMutableList()
        songs.forEach { song ->
            val setId = song.setVideoId?.takeIf(String::isNotBlank)
            val originalIndex = song.endpoint?.takeIf { it.playlistId?.normalizedPlaylistId() == playlistId }?.index
            // Ordinal keys distinguish anonymous occurrences; they are NOT source-index locators.
            // Only setVideoId proves overlap; repeated videos/indices alone never deduplicate.
            val key = setId?.let { "$playlistId:set:$it" } ?: "$playlistId:ordinal:${result.size}"
            if (setId == null || keys.add(key)) result += FinitePlaylistEntry(song.toMediaMetadata(), key, originalIndex)
        }
        return result
    }
}

internal const val FINITE_OCCURRENCE = "archivetune.finiteOccurrence"
internal fun MediaItem.withFiniteOccurrence(key: String?): MediaItem {
    if (key == null) return this
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply { putString(FINITE_OCCURRENCE, key) }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}
internal fun FinitePlaylistEntry.toMediaItem(): MediaItem = metadata.toMediaItem().withFiniteOccurrence(key)
internal fun MediaItem.finiteOccurrence(): String? = mediaMetadata.extras?.getString(FINITE_OCCURRENCE)

/**
 * Only a unique increasing alignment is recoverable. Anonymous duplicates and source edits
 * leave the saved timeline untouched. Only marked manual items may be unmatched.
 */
internal fun alignLegacyFinitePlaylist(
    saved: List<MediaMetadata>,
    source: List<FinitePlaylistEntry>,
    manualIndices: Set<Int> = emptySet(),
): LegacyFiniteAlignment? {
    val positions = saved.mapIndexed { savedIndex, item ->
        if (savedIndex in manualIndices) return@mapIndexed null
        val matches = source.indices.filter { index ->
            val remote = source[index].metadata
            remote.id == item.id && (item.setVideoId.isNullOrBlank() || remote.setVideoId == item.setVideoId)
        }
        if (matches.size != 1) return null
        matches.singleOrNull()
    }
    val known = positions.filterNotNull()
    if (known.isEmpty() || known.zipWithNext().any { (a, b) -> a >= b }) return null
    val merged = mutableListOf<FinitePlaylistEntry>()
    val savedIndices = mutableListOf<Int>()
    var nextSource = 0
    saved.forEachIndexed { index, metadata ->
        val sourceIndex = positions[index]
        if (sourceIndex != null) {
            merged += source.subList(nextSource, sourceIndex)
            savedIndices += merged.size
            merged += source[sourceIndex].copy(metadata = metadata)
            nextSource = sourceIndex + 1
        } else {
            savedIndices += merged.size
            merged += FinitePlaylistEntry(metadata, "manual:$index", null)
        }
    }
    merged += source.drop(nextSource)
    return LegacyFiniteAlignment(merged, savedIndices)
}

internal data class LegacyFiniteAlignment(val entries: List<FinitePlaylistEntry>, val savedIndices: List<Int>)
