/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.LyricsProviderOrderKey
import moe.rukamori.archivetune.constants.PreferredLyricsProvider
import moe.rukamori.archivetune.constants.PrioritizeWordSyncedLyricsKey
import moe.rukamori.archivetune.constants.deserializeLyricsProviderOrder
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.getAsync
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject

class LyricsHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val networkConnectivity: NetworkConnectivityObserver,
    ) {
        private val baseProviders =
            listOf(
                LrcRedLyricsProvider,
                BiniLyricsProvider,
                BetterLyricsProvider,
                BetterLyricsPortatoProvider,
                YouLyPlusLyricsProvider,
                LrcLibLyricsProvider,
                KuGouLyricsProvider,
                MegalobizLyricsProvider,
                SimpMusicLyricsProvider,
                UnisonLyricsProvider,
                PaxsenixAppleMusicLyricsProvider,
                AppleMusicAccountLyricsProvider,
                PaxsenixNeteaseLyricsProvider,
                PaxsenixSpotifyLyricsProvider,
                PaxsenixMusixmatchLyricsProvider,
                PaxsenixYouTubeLyricsProvider,
                TidalLyricsProvider,
                DeezerLyricsProvider,
                YouTubeSubtitleLyricsProvider,
                YouTubeLyricsProvider,

                MusixmatchExperimentalLyricsProvider,
                GeniusLyricsProvider,
            )

        private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
        private val singleLyricsCache = LruCache<String, LyricsResult>(MAX_CACHE_SIZE)

        /**
         * The stored row text an upgrade attempt has already been made against, keyed by media id.
         *
         * Both fetch gates ask [shouldAttemptWordSyncedUpgrade] before sweeping the providers, and
         * without this memo the sweep would repeat on every play and every open of the lyrics panel
         * for a track the providers simply have no word-synced copy of — the same expensive miss,
         * over and over, on providers that rate-limit. Keyed by the stored text rather than the id
         * alone so a row that changes underneath us (a manual refetch, a provider backfill) is
         * eligible again.
         */
        private val wordSyncedUpgradeAttempts =
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                    size > MAX_WORD_SYNCED_UPGRADE_ATTEMPTS
            }

        /**
         * True when the caller should refetch a track's lyrics purely because the word-synced
         * toggle can still improve the stored row, and this process has not already settled an
         * attempt for this exact row.
         *
         * The gates own this decision rather than [getLyricsWithProvider] because they short-circuit
         * on a stored row before the helper is ever asked; see [LyricsUtils.needsWordSyncedUpgrade].
         * The caller must use the returned value to decide *what to write* as well: only a result
         * that actually carries word-level timing may replace the stored row, and only the row that
         * was inspected — see [noteWordSyncedUpgradeSettled].
         *
         * Asking does not consume the attempt. See [noteWordSyncedUpgradeSettled] for why the
         * verdict, not the question, is what gets remembered.
         */
        suspend fun shouldAttemptWordSyncedUpgrade(
            mediaId: String,
            stored: LyricsEntity?,
        ): Boolean {
            // getAsync, not the blocking get operator: that one answers with the fallback when the
            // first DataStore snapshot has not landed and the caller is on the main thread, and this
            // is reachable from the lyrics panel's composition effect.
            val prioritizeWordSynced = context.dataStore.getAsync(PrioritizeWordSyncedLyricsKey) ?: false
            if (!LyricsUtils.needsWordSyncedUpgrade(prioritizeWordSynced, stored)) return false
            val storedLyrics = stored?.lyrics ?: return false
            return synchronized(wordSyncedUpgradeAttempts) { wordSyncedUpgradeAttempts[mediaId] != storedLyrics }
        }

        /**
         * Records that the upgrade sweep for [mediaId] reached a verdict, so the next gate pass
         * does not sweep the providers again for the same stored row.
         *
         * Deliberately called *after* the fetch rather than when the attempt is granted. A sweep
         * that never returned — no network, so the helper handed back the sentinel; or the track
         * was skipped, so the coroutine was cancelled — has learned nothing, and the row is still
         * worth upgrading. Consuming the attempt there would drop the upgrade for the rest of the
         * process, and it is MusicService's gate that usually asks first, at track start, which is
         * exactly when the network and the metadata are least settled. This mirrors the existing
         * rule that a sentinel row is retried on every play.
         */
        fun noteWordSyncedUpgradeSettled(
            mediaId: String,
            storedLyrics: String,
        ) {
            synchronized(wordSyncedUpgradeAttempts) { wordSyncedUpgradeAttempts[mediaId] = storedLyrics }
        }

        suspend fun getLyrics(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): String = getLyricsWithProvider(
            mediaMetadata = mediaMetadata,
            preferredProviderOnly = preferredProviderOnly,
            forceRefresh = forceRefresh,
        ).lyrics

        suspend fun getLyricsWithProvider(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): LyricsResult {
            val cacheKey = mediaMetadata.lyricsCacheKey

            // Read the "Prioritize Word Synced Lyrics" toggle once up-front. We need
            // it during the cache check below because when the toggle is ON, cached
            // non-word-synced lyrics must be treated as stale — otherwise turning the
            // toggle on and replaying a song that was already cached would keep
            // returning the old line-synced/plain result and the word-synced lookup
            // would never run.
            val prioritizeWordSynced =
                !preferredProviderOnly && (context.dataStore.getAsync(PrioritizeWordSyncedLyricsKey) ?: false)

            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                singleLyricsCache.get(cacheKey)?.let { cached ->
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)
                    // When prioritizing word-synced lyrics, only honor the cache if the
                    // cached lyrics are themselves word-synced. Otherwise skip the cache
                    // so the word-synced lookup gets a chance to find better lyrics.
                    if (!prioritizeWordSynced || cachedIsWordSynced) {
                        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                        return cached
                    }
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        "Skipping cache for ${mediaMetadata.title}: prioritizeWordSynced=true, cached lyrics not word-synced",
                    )
                }

                val cached = cache.get(cacheKey)?.firstOrNull()
                if (cached != null) {
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)
                    if (!prioritizeWordSynced || cachedIsWordSynced) {
                        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                        return cached
                    }
                }
            }

            GlobalLog.append(
                Log.DEBUG,
                "LyricsHelper",
                "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString {
                    it.name
                }}, Album: ${mediaMetadata.album?.title})",
            )

            val isNetworkAvailable =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
                return LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)
            }

            // When "Prioritize Word Synced Lyrics" is ON (and the caller isn't asking for the
            // preferred provider only), first try to obtain word-synced lyrics from the three
            // word-sync-capable providers (BetterLyrics, YouLyPlus, Unison).
            if (prioritizeWordSynced) {
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "PrioritizeWordSynced=on: querying BetterLyrics/YouLyPlus/Unison for word-synced lyrics",
                )
                val wordSyncedResult = tryFetchWordSyncedFromPriorityProviders(mediaMetadata)
                if (wordSyncedResult != null && isMeaningfulLyrics(wordSyncedResult.lyrics)) {
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        "Word-synced lyrics found via ${wordSyncedResult.providerName}",
                    )
                    singleLyricsCache.put(cacheKey, wordSyncedResult)
                    return wordSyncedResult
                }
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "No word-synced lyrics from priority providers, falling back to normal priority flow",
                )
            }

            val ordered =
                orderedProviders()
                    .filter { it.isEnabled(context) }
                    .filter { supportsMediaId(it, mediaMetadata.id) }
            val providers = if (preferredProviderOnly) ordered.take(1) else ordered

            val result = fetchPriorityLyricsResult(providers, mediaMetadata)
            if (isMeaningfulLyrics(result.lyrics)) {
                singleLyricsCache.put(cacheKey, result)
            }

            return result
        }

        /**
         * Queries the three word-sync-capable providers (BetterLyrics, YouLyPlus, Unison) IN
         * PARALLEL and returns the first one whose response is actually word-synced (QRC/YRC/TTML
         * with word-level timings).
         */
        private suspend fun tryFetchWordSyncedFromPriorityProviders(
            mediaMetadata: MediaMetadata,
        ): LyricsResult? {
            // Fixed canonical order. This is independent of the user's provider
            // priority order so the behaviour is predictable when the toggle is ON.
            val wordSyncCapable: List<LyricsProvider> =
                listOf(
                    BetterLyricsProvider,
                    YouLyPlusLyricsProvider,
                    UnisonLyricsProvider,
                )

            val artist = mediaMetadata.artists.joinToString { it.name }
            val results =
                supervisorScope {
                    wordSyncCapable
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val lyrics =
                                    providerPermits.withPermit {
                                        withTimeoutOrNull(WORD_SYNC_PROVIDER_TIMEOUT_MS) {
                                            fetchProviderLyrics(provider, mediaMetadata, artist)
                                        }
                                    }
                                if (lyrics == null) {
                                    GlobalLog.append(
                                        Log.DEBUG,
                                        "LyricsHelper",
                                        "${provider.name} returned no lyrics (timeout or error)",
                                    )
                                    null
                                } else {
                                    val isWordSynced = LyricsUtils.hasWordSyncedLyrics(lyrics)
                                    GlobalLog.append(
                                        Log.DEBUG,
                                        "LyricsHelper",
                                        "${provider.name} returned lyrics (word-synced=$isWordSynced, length=${lyrics.length})",
                                    )
                                    if (isWordSynced) provider.name to lyrics else null
                                }
                            }
                        }.mapNotNull { it.await() }
                }

            if (results.isEmpty()) return null

            // Walk results in canonical provider order (because `wordSyncCapable`
            // is ordered) and return the first one. We already filtered out
            // non-word-synced responses above, so every entry here is word-synced.
            val first = results.first()
            return LyricsResult(providerName = first.first, lyrics = first.second)
        }

        suspend fun getAllLyrics(
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
            forceRefresh: Boolean = false,
            callback: (LyricsResult) -> Unit,
        ) {
            val cacheKey = lyricsCacheKey(songTitle, songArtists)
            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                cache.get(cacheKey)?.let { results ->
                    results.forEach(callback)
                    return
                }
            }

            val isNetworkAvailable =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                return
            }

            val allResult = mutableListOf<LyricsResult>()
            val providers = orderedProviders().filter { it.isEnabled(context) }

            // Fan out all enabled providers in parallel. The previous implementation
            // iterated providers sequentially with `forEach`, which meant the search
            // dialog stayed on "Searching providers…" until every provider returned in
            // order — a single slow provider (Musixmatch can take 10–15s) held back
            // results from faster ones (LRCLIB ~100ms). Running them concurrently lets
            // results stream into the UI as each provider finishes.
            //
            // Each provider call is wrapped in a per-provider timeout so a hung
            // provider can't pin the search dialog indefinitely. Failures and timeouts
            // are reported but never propagated — the dialog just shows fewer results.
            withContext(Dispatchers.IO) {
                supervisorScope {
                    providers.map { provider ->
                        async {
                            try {
                                providerPermits.withPermit {
                                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                        provider.getAllLyrics(mediaId, songTitle, songArtists, songAlbum, duration) lyricsCallback@{ lyrics ->
                                            val normalizedLyrics = LyricsUtils.lyricsOrNotFound(lyrics)
                                            if (normalizedLyrics == LYRICS_NOT_FOUND) return@lyricsCallback
                                            val result = LyricsResult(provider.name, normalizedLyrics)
                                            synchronized(allResult) {
                                                allResult += result
                                            }
                                            callback(result)
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                reportException(e)
                            }
                        }
                    }.forEach { it.await() }
                }
            }
            cache.put(cacheKey, allResult.toList())
        }

        /**
         * Resolves lyrics from all providers in parallel and returns the best result by (sync tier:
         * word > line > plain) then by provider priority (lower index wins).
         */
        private suspend fun fetchPriorityLyricsResult(
            providers: List<LyricsProvider>,
            mediaMetadata: MediaMetadata,
        ): LyricsResult {
            if (providers.isEmpty()) return LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)

            val artist = mediaMetadata.artists.joinToString { it.name }
            val results =
                supervisorScope {
                    providers
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val lyrics =
                                    providerPermits.withPermit {
                                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                            fetchProviderLyrics(provider, mediaMetadata, artist)
                                        }
                                    }
                                if (lyrics == null) null else provider.name to lyrics
                            }
                        }.mapNotNull { it.await() }
                }

            if (results.isEmpty()) return LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)

            // Ranking: word-synced > line-synced > plain. `firstOrNull` walks the
            // results in provider-priority order (because `providers` is ordered), so
            // when multiple providers return the same tier the higher-priority one
            // wins — this is what restores the priority order the streaming
            // implementation broke.
            val wordSynced = results.firstOrNull { LyricsUtils.hasWordSyncedLyrics(it.second) }
            if (wordSynced != null) return LyricsResult(providerName = wordSynced.first, lyrics = wordSynced.second)

            val lineSynced = results.firstOrNull { LyricsUtils.isLineSyncedLrc(it.second) }
            if (lineSynced != null) return LyricsResult(providerName = lineSynced.first, lyrics = lineSynced.second)

            val first = results.first()
            return LyricsResult(providerName = first.first, lyrics = first.second)
        }

        private suspend fun fetchProviderLyrics(
            provider: LyricsProvider,
            mediaMetadata: MediaMetadata,
            artist: String,
        ): String? =
            try {
                provider
                    .getLyrics(
                        mediaMetadata.id,
                        mediaMetadata.title,
                        artist,
                        mediaMetadata.album?.title,
                        mediaMetadata.duration,
                        mediaMetadata.isrc,
                    ).fold(
                        onSuccess = { lyrics ->
                            LyricsUtils.lyricsOrNotFound(lyrics).takeIf { it != LYRICS_NOT_FOUND }
                        },
                        onFailure = {
                            reportException(it)
                            null
                        },
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                null
            }

        private suspend fun orderedProviders(): List<LyricsProvider> {
            val orderStr = context.dataStore.data.first()[LyricsProviderOrderKey]
            val orderedEnums = deserializeLyricsProviderOrder(orderStr)
            val providerMap: Map<PreferredLyricsProvider, LyricsProvider> =
                mapOf(
                    PreferredLyricsProvider.LRCLIB to LrcLibLyricsProvider,
                    PreferredLyricsProvider.KUGOU to KuGouLyricsProvider,
                    PreferredLyricsProvider.MEGALOBIZ to MegalobizLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS to BetterLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS_PORTATO to BetterLyricsPortatoProvider,
                    PreferredLyricsProvider.YOULY_PLUS to YouLyPlusLyricsProvider,
                    PreferredLyricsProvider.SIMPMUSIC to SimpMusicLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC to PaxsenixAppleMusicLyricsProvider,
                    PreferredLyricsProvider.APPLE_MUSIC to AppleMusicAccountLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_NETEASE to PaxsenixNeteaseLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_SPOTIFY to PaxsenixSpotifyLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_MUSIXMATCH to PaxsenixMusixmatchLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_YOUTUBE to PaxsenixYouTubeLyricsProvider,
                    PreferredLyricsProvider.TIDAL to TidalLyricsProvider,
                    PreferredLyricsProvider.DEEZER to DeezerLyricsProvider,
                    PreferredLyricsProvider.UNISON to UnisonLyricsProvider,
                    PreferredLyricsProvider.MUSIXMATCH_EXPERIMENTAL to MusixmatchExperimentalLyricsProvider,
                )
            val userOrdered = orderedEnums.mapNotNull { providerMap[it] }
            val rest = baseProviders.filterNot { it in userOrdered }
            return userOrdered + rest
        }

        private fun isMeaningfulLyrics(lyrics: String): Boolean = LyricsUtils.hasMeaningfulLyricsContent(lyrics)

        private fun supportsMediaId(
            provider: LyricsProvider,
            mediaId: String,
        ): Boolean {
            val isNonYouTubeId = mediaId.isTelegramMediaId() || mediaId.isLocalMediaId()
            if (!isNonYouTubeId) return true
            return provider !is SimpMusicLyricsProvider &&
                provider !is YouTubeLyricsProvider &&
                provider !is YouTubeSubtitleLyricsProvider
        }

        fun clearCache() {
            cache.evictAll()
            singleLyricsCache.evictAll()
        }

        private fun invalidateCache(cacheKey: String) {
            cache.remove(cacheKey)
            singleLyricsCache.remove(cacheKey)
        }

        private val MediaMetadata.lyricsCacheKey: String
            get() =
                lyricsCacheKey(
                    title = title,
                    artists = artists.joinToString { it.name },
                )

        private fun lyricsCacheKey(
            title: String,
            artists: String,
        ): String = "$artists-$title".replace(" ", "")
        // Bounds provider fan-out across the three parallel fetch paths so a burst of
        // provider HTTP calls cannot exhaust sockets/threads on low-end devices.

        companion object {
            // Process-wide cap on concurrent provider fetches, shared by the three
            // parallel fetch paths AND every LyricsHelper instance (MusicService,
            // menu, preload all inject their own). Per-instance caps summed past
            // the socket budget on low-end devices; one shared semaphore holds.
            private val providerPermits = Semaphore(MAX_CONCURRENT_PROVIDERS)

            // Cap on concurrent provider fetches, shared by all three parallel fetch
            // paths via [providerPermits].
            private const val MAX_CONCURRENT_PROVIDERS = 6
            private const val MAX_CACHE_SIZE = 16

            // One entry per track whose stored lyrics a word-synced upgrade has been attempted
            // against, so a session that plays through a large library cannot grow this map
            // without bound.
            private const val MAX_WORD_SYNCED_UPGRADE_ATTEMPTS = 64

            // Per-provider hard timeout for the normal priority flow. Provider calls
            // that exceed this are cancelled and dropped from ranking. Tuned to be
            // long enough for typical provider latency (~3–5s for Musixmatch under
            // good conditions) but short enough that a hung provider can't pin the
            // lyrics panel.
            private const val PROVIDER_TIMEOUT_MS = 8_000L

            // Longer timeout for the "Prioritize Word Synced Lyrics" path. YouLyPlus
            // in particular fans out across 5 mirrors × 2 endpoints (up to 10 HTTP
            // requests in sequence) and can legitimately take 10–15s, so the regular
            // 8s timeout silently skipped it even when it had word-synced lyrics
            // available. Since this path only runs once per song when the toggle is
            // ON (and the user has explicitly opted in for higher-quality lyrics),
            // the extra latency is acceptable.
            private const val WORD_SYNC_PROVIDER_TIMEOUT_MS = 15_000L
        }
    }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)
