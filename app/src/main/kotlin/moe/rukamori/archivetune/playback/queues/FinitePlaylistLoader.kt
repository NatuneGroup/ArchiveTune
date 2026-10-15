/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import kotlin.coroutines.coroutineContext

/**
 * Browse transport, deliberately separate from watch-next. Tests can supply pages at the
 * same boundary used by playback, complete-list actions and database synchronization.
 */
internal interface FinitePlaylistSource {
    suspend fun initial(playlistId: String): PlaylistPage
    suspend fun continuation(token: String, playlistId: String): FinitePlaylistChunk
}

internal data class FinitePlaylistChunk(val songs: List<SongItem>, val continuation: String?)

internal object YouTubeFinitePlaylistSource : FinitePlaylistSource {
    override suspend fun initial(playlistId: String) = YouTube.playlist(playlistId).getOrThrow()

    override suspend fun continuation(token: String, playlistId: String): FinitePlaylistChunk {
        val page = YouTube.playlistContinuation(token, playlistId).getOrThrow()
        return FinitePlaylistChunk(page.songs, page.continuation)
    }
}

class IncompleteYouTubePlaylistException : Exception("YouTube playlist loading is incomplete")

internal fun String.normalizedPlaylistId(): String = removePrefix("VL").removePrefix("VL")

fun isFiniteYouTubePlaylist(playlistId: String?): Boolean =
    !playlistId.isNullOrBlank() && !playlistId.normalizedPlaylistId().startsWith("RD")

internal object FinitePlaylistLoader {
    const val MAX_REQUESTS = 200
    const val TIMEOUT_MS = 120_000L

    suspend fun <T> bounded(block: suspend () -> T): T =
        try {
            withTimeout(TIMEOUT_MS) { block() }
        } catch (error: TimeoutCancellationException) {
            // An enclosing cancellation is not a recoverable transport timeout.
            coroutineContext.ensureActive()
            throw IncompleteYouTubePlaylistException()
        }

    fun validateNext(token: String?, consumed: Set<String>, songs: List<SongItem>, initial: Boolean = false) {
        if (token != null && (token.isBlank() || token in consumed)) throw IncompleteYouTubePlaylistException()
        // The pinned parser maps an unrecognized continuation envelope to empty/null. There
        // is no public envelope-validity bit: fail closed instead of declaring it exhausted.
        if (!initial && token == null && songs.isEmpty()) throw IncompleteYouTubePlaylistException()
    }

    suspend fun complete(
        playlistId: String,
        source: FinitePlaylistSource = YouTubeFinitePlaylistSource,
    ): PlaylistPage = bounded {
        val first = source.initial(playlistId)
        var token = first.songsContinuation ?: first.continuation
        validateNext(token, emptySet(), first.songs, initial = true)
        requireConfirmedInitial(first)
        val setIds = mutableSetOf<String>()
        val songs = first.songs.filter { song ->
            song.setVideoId?.takeIf(String::isNotBlank)?.let(setIds::add) ?: true
        }.toMutableList()
        val consumed = mutableSetOf<String>()
        var requests = 1
        while (token != null) {
            coroutineContext.ensureActive()
            if (++requests > MAX_REQUESTS || !consumed.add(token)) throw IncompleteYouTubePlaylistException()
            val page = source.continuation(token, playlistId)
            validateNext(page.continuation, consumed, page.songs)
            songs += page.songs.filter { song ->
                val setId = song.setVideoId?.takeIf(String::isNotBlank)
                setId == null || setIds.add(setId)
            }
            token = page.continuation
        }
        first.copy(songs = songs, songsContinuation = null, continuation = null)
    }

    fun requireConfirmedInitial(page: PlaylistPage) {
        if (page.songs.isNotEmpty() || page.songsContinuation != null || page.continuation != null) return
        // A real, parsed playlist header with an explicit zero count is evidence of empty.
        // Unknown/localized counts we cannot establish remain incomplete, never false success.
        val count = page.playlist.songCountText?.let { Regex("""\d+""").findAll(it).map { match -> match.value }.toList() }
        if (count != listOf("0")) throw IncompleteYouTubePlaylistException()
    }
}

/** Strict completion is all-or-nothing; callers must commit only after this returns. */
suspend fun loadCompleteYouTubePlaylist(playlistId: String): PlaylistPage =
    FinitePlaylistLoader.complete(playlistId)

/** The store/player edge cannot observe a partially fetched playlist. */
internal suspend fun <T> withCompleteYouTubePlaylist(
    playlistId: String,
    source: FinitePlaylistSource = YouTubeFinitePlaylistSource,
    fetch: suspend () -> PlaylistPage = { FinitePlaylistLoader.complete(playlistId, source) },
    commit: suspend (PlaylistPage) -> T,
): T {
    val page = fetch()
    coroutineContext.ensureActive()
    return commit(page)
}
