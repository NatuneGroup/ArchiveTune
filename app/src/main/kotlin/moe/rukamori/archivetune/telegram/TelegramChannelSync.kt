/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Materialises a Telegram channel into a real local playlist so it reuses the normal playlist UI
 * (list tile, rich playlist screen, search / radio / download / menus) instead of a bespoke screen.
 * Opening a channel creates (or reuses) a playlist with a deterministic id, then pages through the
 * channel's audio files in the background, inserting each as a song + playlist membership and
 * seeding its format row. The playlist screen updates reactively as songs arrive.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.telegram

import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.time.LocalDateTime

object TelegramChannelSync {
    private const val TAG = "TelegramChannelSync"
    private const val PLAYLIST_ID_PREFIX = "LPtg"
    private const val PAGE_FETCH_LIMIT = 100

    // How long to wait for the TDLib client to reach the Ready auth state
    // before giving up on a sync. The client might still be connecting when
    // the user opens a channel from the browse screen — without this wait,
    // the first fetch fails silently and the playlist shows "0 songs" until
    // the user manually hits "Refresh from Telegram".
    private const val READY_TIMEOUT_MS = 15_000L
    private const val READY_POLL_INTERVAL_MS = 500L

    // Retry config for individual page fetches. TDLib can transiently fail
    // on the first SearchChatMessages call for a private channel (chat history
    // not yet loaded), but succeed on a subsequent call a few hundred ms later.
    // This applies BOTH to exception failures AND to "empty first page" results
    // — TDLib frequently returns an empty page (no exception) for a private
    // channel whose history hasn't been loaded yet, which is why the playlist
    // showed "0 songs" until the user manually hit Refresh.
    private const val FETCH_RETRY_COUNT = 5
    private const val FETCH_RETRY_DELAY_MS = 600L

    // Settling delay after openChat. For private channels, TDLib needs time to
    // load the chat history into its local index before SearchChatMessages can
    // return results. Waiting here BEFORE the first fetch dramatically reduces
    // the chance of getting an empty first page.
    private const val OPEN_CHAT_SETTLE_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards against two concurrent syncs of the same channel racing on membership positions.
    private val inFlight = mutableSetOf<Long>()
    private val inFlightLock = Mutex()

    fun playlistId(chatId: Long): String = "$PLAYLIST_ID_PREFIX$chatId"

    /**
     * Creates the playlist row for [chatId] if missing (so it exists before navigation) and returns
     * its id. Safe to call repeatedly — an existing playlist keeps its songs and is just retitled.
     */
    suspend fun ensurePlaylist(
        database: MusicDatabase,
        chatId: Long,
        title: String,
    ): String {
        val id = playlistId(chatId)
        // insert() ignores on conflict, so an existing playlist (and any user tweaks to it) is left
        // untouched; only a brand-new channel creates a row.
        database.withTransaction {
            insert(
                PlaylistEntity(
                    id = id,
                    name = title.ifBlank { "Telegram channel" },
                    browseId = null,
                    isEditable = true,
                    bookmarkedAt = LocalDateTime.now(),
                ),
            )
        }
        return id
    }

    /** Kicks off a background sync of [chatId]'s audio files into its playlist. */
    fun syncAsync(
        database: MusicDatabase,
        chatId: Long,
        title: String,
        losslessOnly: Boolean,
    ) {
        scope.launch {
            val started =
                inFlightLock.withLock {
                    if (chatId in inFlight) false else inFlight.add(chatId)
                }
            if (!started) return@launch
            try {
                sync(database, chatId, title, losslessOnly)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Sync failed for chat %d", chatId)
            } finally {
                inFlightLock.withLock { inFlight.remove(chatId) }
            }
        }
    }

    private suspend fun sync(
        database: MusicDatabase,
        chatId: Long,
        title: String,
        losslessOnly: Boolean,
    ) {
        // Wait for the TDLib client to reach the Ready auth state before
        // attempting any fetch. The user might open a channel immediately
        // after the browse screen loads, before TDLib has finished its
        // initial connection. Without this wait, the first SearchChatMessages
        // call fails with "auth not ready" and the playlist stays empty.
        val ready =
            withTimeoutOrNull(READY_TIMEOUT_MS) {
                while (!TelegramClient.isReady) {
                    delay(READY_POLL_INTERVAL_MS)
                }
                true
            } ?: false
        if (!ready) {
            Timber.tag(TAG).w("TDLib client not ready after %dms, aborting sync for chat %d", READY_TIMEOUT_MS, chatId)
            return
        }

        // For private channels, TDLib may need to "open" the chat (load its
        // metadata + recent history) before SearchChatMessages returns results.
        // The first call can return an empty page or a transient error. Opening
        // the chat here ensures the message index is warm before we page.
        runCatching { TelegramClient.openChat(chatId) }
            .onFailure { Timber.tag(TAG).w(it, "openChat(%d) failed (non-fatal)", chatId) }
        // OpenChat only marks the chat as viewed; it does not guarantee TDLib pulled any history
        // from the server. SearchChatMessages is answered out of TDLib's *local* message database,
        // so a freshly added private channel has nothing to search and returns an empty page with
        // no error.
        //
        // primeChatHistory forces the fetch with GetChatHistory and waits until messages actually
        // land, so the search below has an index to hit. A false result is not fatal (the channel
        // may genuinely be empty), so we still fall through to the paging loop.
        runCatching { TelegramClient.primeChatHistory(chatId) }
            .onFailure { Timber.tag(TAG).w(it, "primeChatHistory(%d) failed (non-fatal)", chatId) }
        // Small settle delay so the messages primeChatHistory pulled are fully
        // committed to the local index before the first search.
        delay(OPEN_CHAT_SETTLE_MS)

        val playlistId = playlistId(chatId)
        val filters = listOf(TdApi.SearchMessagesFilterAudio(), TdApi.SearchMessagesFilterDocument())
        var inserted = 0
        val seen = mutableSetOf<Long>()

        // No MAX_TRACKS cap — page through the entire channel. The `page.nextFromMessageId == 0L`
        // check terminates the loop once TDLib reports there are no more audio messages to
        // return, so this is bounded by the actual size of the channel.
        for (filter in filters) {
            var fromMessageId = 0L
            var isFirstPage = true
            while (true) {
                val page =
                    fetchPageWithRetry(chatId, fromMessageId, filter, isFirstPage)

                if (page == null) {
                    // All retries exhausted — stop this filter and move on to
                    // the next one (a different filter might still work).
                    Timber.tag(TAG).w("fetchPageWithRetry exhausted for chat %d filter %s", chatId, filter::class.simpleName)
                    break
                }

                for (track in page.tracks) {
                    if (losslessOnly && !track.isLossless) continue
                    if (!seen.add(track.messageId)) continue
                    if (insertTrack(database, playlistId, track, title)) inserted++
                }

                isFirstPage = false
                if (page.nextFromMessageId == 0L) break
                fromMessageId = page.nextFromMessageId
            }
        }
        Timber.tag(TAG).d("Materialised %d tracks for chat %d", inserted, chatId)
    }

    /**
     * Fetches one page with retry. TDLib can transiently fail on the first SearchChatMessages call
     * for a private channel (chat history not yet loaded), but succeed on a subsequent call a few
     * hundred ms later. Returns null only if ALL retries fail.
     */
    private suspend fun fetchPageWithRetry(
        chatId: Long,
        fromMessageId: Long,
        filter: TdApi.SearchMessagesFilter,
        isFirstPage: Boolean = false,
    ): TelegramAudioPage? {
        repeat(FETCH_RETRY_COUNT) { attempt ->
            val result =
                runCatching {
                    TelegramClient.fetchAudioPage(chatId, fromMessageId, PAGE_FETCH_LIMIT, filter)
                }
            result.onSuccess { page ->
                // On the first page, treat "empty + no next cursor" as a
                // transient failure and retry — TDLib hasn't indexed the
                // channel history yet. On subsequent pages, this is the
                // legitimate end-of-channel signal.
                val isEmptyFirstPage =
                    isFirstPage &&
                        page.tracks.isEmpty() &&
                        page.nextFromMessageId == 0L
                if (isEmptyFirstPage && attempt < FETCH_RETRY_COUNT - 1) {
                    Timber.tag(TAG).w(
                        "fetchAudioPage attempt %d/%d returned empty first page for chat %d filter %s (will retry — TDLib history not indexed yet)",
                        attempt + 1,
                        FETCH_RETRY_COUNT,
                        chatId,
                        filter::class.simpleName,
                    )
                } else {
                    return page
                }
            }
            result.onFailure { e ->
                Timber.tag(TAG).w(
                    e,
                    "fetchAudioPage attempt %d/%d failed for chat %d (will retry)",
                    attempt + 1,
                    FETCH_RETRY_COUNT,
                    chatId,
                )
            }
            delay(FETCH_RETRY_DELAY_MS)
        }
        return null
    }

    /** Inserts one track as a song + playlist membership + format row. Skips duplicates. */
    private suspend fun insertTrack(
        database: MusicDatabase,
        playlistId: String,
        track: TelegramTrack,
        channelTitle: String,
    ): Boolean =
        database.withTransaction {
            if (checkInPlaylist(playlistId, track.mediaId) > 0) return@withTransaction false
            insert(track.toMediaMetadata(channelTitle))
            upsert(track.toFormatEntity(getFormatsByIds(listOf(track.mediaId)).firstOrNull()))
            val position = (maxPlaylistSongPosition(playlistId) ?: -1) + 1
            insert(
                PlaylistSongMap(
                    songId = track.mediaId,
                    playlistId = playlistId,
                    position = position,
                ),
            )
            true
        }
}
