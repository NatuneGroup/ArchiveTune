/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.CanvasRequestPolicy
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.canvas.models.looselyMatchesSongIdentity
import moe.rukamori.archivetune.canvas.models.matchesSongIdentity
import moe.rukamori.archivetune.customartwork.CustomArtworkStore
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.isLocalMediaId
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

internal suspend fun resolveCanvasArtworkForPlayback(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    allowNetwork: Boolean,
    albumTitle: String? = null,
    trySpotifyCanvas: Boolean = false,
): CanvasArtwork? {
    // A user-picked canvas outranks every provider and is never written into the playback cache,
    // so removing it falls straight back to whatever the providers resolve.
    withContext(Dispatchers.IO) { CustomArtworkStore.customCanvasArtwork(mediaId) }?.let { return it }
    // Telegram/local files have tag-derived (often noisy) metadata — use fuzzy identity matching
    // so a real canvas isn't discarded over a "(2019)" suffix or a channel-name artist.
    val strictIdentity = !(mediaId.isTelegramMediaId() || mediaId.isLocalMediaId())
    // Fast path: try the cache with preferCachedOnly=true via getCachedOnlyFast,
    // which skips the expensive MediaExtractor probe (~50-200ms per file).
    // The probe was the dominant contributor to canvas startup latency on
    // cache hits — see CanvasArtworkPlaybackCache.getCachedOnlyFast for details.
    val cachedArtwork =
        withContext(Dispatchers.IO) {
            CanvasArtworkPlaybackCache.getCachedOnlyFast(mediaId)
                ?: CanvasArtworkPlaybackCache.get(
                    mediaId = mediaId,
                    preferCachedOnly = true,
                )
        }
    if (cachedArtwork != null) {
        val isValid =
            cachedArtwork.hasRequiredCanvasVariant(requireVertical) &&
                cachedArtwork.matchesIdentity(songTitleRaw, artistNameRaw, strictIdentity)
        if (isValid) return cachedArtwork
        withContext(Dispatchers.IO) {
            CanvasArtworkPlaybackCache.remove(mediaId)
        }
    }

    if (!allowNetwork || mediaId.isBlank()) {
        Timber.tag(CanvasArtworkLogTag).d("Skipping canvas network lookup for %s", mediaId)
        return null
    }

    // Both providers deny at the request policy when their source is switched off (or the
    // network is blocked), and that denial is indistinguishable from "this song has no
    // canvas". So the negative is keyed on the sources this lookup may actually ask:
    // switching a source on changes the key instead of being masked by the older negative.
    // The probe reads live connectivity, so it runs off the caller's thread.
    val queriedSources =
        withContext(Dispatchers.IO) { queriedCanvasSources(strictIdentity, trySpotifyCanvas) }

    if (queriedSources.isNotEmpty() &&
        CanvasResolutionMissCache.isRecentlyMissed(mediaId, requireVertical, queriedSources)
    ) {
        Timber.tag(CanvasArtworkLogTag).d("Skipping canvas lookup for %s — negative result is still fresh", mediaId)
        return null
    }

    return withContext(Dispatchers.IO) {
        // Spotify Canvas: when enabled and the current media is a YouTube Music video
        // (i.e. mediaId is the video ID), look up the official Spotify Canvas via the
        // mlc.kouzu.in resolver. This is a direct video-ID → canvas-URL lookup and
        // doesn't depend on tag metadata, so it's both faster and more reliable than
        // the song-title-based ArchiveTune Canvas lookup. Try it first when enabled.
        if (trySpotifyCanvas && strictIdentity) {
            val spotifyCanvas =
                runCatching {
                    SpotifyCanvasProvider.getByVideoId(
                        videoId = mediaId,
                        songTitle = songTitleRaw,
                        artistName = artistNameRaw,
                    )
                }.onFailure { throwable ->
                    Timber.tag(CanvasArtworkLogTag).w(throwable, "Spotify Canvas lookup failed for %s", mediaId)
                }.getOrNull()
            if (spotifyCanvas != null && spotifyCanvas.hasRequiredCanvasVariant(requireVertical)) {
                Timber.tag(CanvasArtworkLogTag).d("Spotify Canvas resolved for %s", mediaId)
                return@withContext CanvasArtworkPlaybackCache
                    .put(mediaId, spotifyCanvas)
                    .also { CanvasResolutionMissCache.clear(mediaId) }
            }
        }

        val fetched =
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = requireVertical,
                strictIdentity = strictIdentity,
                albumTitle = albumTitle,
            )

        if (fetched == null) {
            Timber.tag(CanvasArtworkLogTag).d("No playable canvas resolved for %s", mediaId)
            // Only a lookup that actually asked a provider may be remembered as a miss.
            if (queriedSources.isNotEmpty()) {
                CanvasResolutionMissCache.markMissed(mediaId, requireVertical, queriedSources)
            }
            return@withContext null
        }

        CanvasArtworkPlaybackCache
            .put(mediaId, fetched)
            .also { CanvasResolutionMissCache.clear(mediaId) }
    }
}

internal suspend fun fetchCanvasArtworkForPlayback(
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    forceRefresh: Boolean = false,
    strictIdentity: Boolean = true,
    albumTitle: String? = null,
): CanvasArtwork? {
    val songTitle = normalizeCanvasSongTitle(songTitleRaw)
    val artistName = normalizeCanvasArtistName(artistNameRaw)
    val candidates =
        linkedSetOf(
            songTitle to artistName,
            songTitleRaw to artistName,
            songTitle to artistNameRaw,
            songTitleRaw to artistNameRaw,
        ).filter { (song, artist) ->
            song.isNotBlank() && artist.isNotBlank()
        }

    return candidates.firstNotNullOfOrNull { (song, artist) ->
        // A policy denial (source deselected, wifi-only/low-data block) throws;
        // resolve it to null like the Spotify branch so the chain continues.
        runCatching {
            AppleMusicProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    storefront = storefront,
                    forceRefresh = forceRefresh,
                    album = albumTitle,
                )
        }.getOrNull()?.takeIf { artwork ->
            artwork.matchesIdentity(songTitleRaw, artistNameRaw, strictIdentity) &&
                artwork.hasRequiredCanvasVariant(requireVertical)
        }
    }
}

internal suspend fun refetchCanvasArtworkForPlayback(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    albumTitle: String? = null,
): CanvasArtwork? {
    if (mediaId.isBlank()) return null

    return withContext(Dispatchers.IO) {
        val fetched =
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = requireVertical,
                forceRefresh = true,
                strictIdentity = !(mediaId.isTelegramMediaId() || mediaId.isLocalMediaId()),
                albumTitle = albumTitle,
            ) ?: return@withContext null

        CanvasArtworkPlaybackCache
            .replace(mediaId, fetched)
            .also { CanvasResolutionMissCache.clear(mediaId) }
    }
}

private fun CanvasArtwork.matchesIdentity(
    songTitleRaw: String,
    artistNameRaw: String,
    strict: Boolean,
): Boolean =
    if (strict) {
        matchesSongIdentity(songTitleRaw, artistNameRaw)
    } else {
        // Album-level motion artwork carries the album name in `name`, so an exact/fuzzy song
        // match may legitimately fail; accept it when the song lookup already vouched for it.
        looselyMatchesSongIdentity(songTitleRaw, artistNameRaw) || !albumName.isNullOrBlank()
    }

private fun CanvasArtwork.hasRequiredCanvasVariant(requireVertical: Boolean): Boolean =
    if (requireVertical) {
        !preferredVerticalAnimationUrl.isNullOrBlank()
    } else {
        !preferredAnimationUrl.isNullOrBlank()
    }

private const val CanvasArtworkLogTag = "CanvasArtwork"

/**
 * A single canvas source result for the "Save Canvas" feature.
 * Each result has a human-readable source name and the resolved CanvasArtwork.
 */
data class CanvasSourceResult(
    val sourceName: String,
    val artwork: CanvasArtwork,
)

/** Fetch ALL canvas sources for a song (used by the "Save Canvas" overflow- menu action). */
internal suspend fun fetchAllCanvasSourcesForSong(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    albumTitle: String? = null,
): List<CanvasSourceResult> = coroutineScope {
    val strictIdentity = !(mediaId.isTelegramMediaId() || mediaId.isLocalMediaId())
    val songTitle = normalizeCanvasSongTitle(songTitleRaw)
    val artistName = normalizeCanvasArtistName(artistNameRaw)

    // Spotify Canvas lookup (by video ID) — only for YouTube media (not local/Telegram).
    val spotifyDeferred = async {
        if (strictIdentity && mediaId.isNotBlank()) {
            runCatching {
                SpotifyCanvasProvider.getByVideoId(
                    videoId = mediaId,
                    songTitle = songTitleRaw,
                    artistName = artistNameRaw,
                )
            }.getOrNull()
                ?.takeIf { it.hasRequiredCanvasVariant(requireVertical = false) }
        } else {
            null
        }
    }

    // Apple Music lookup (by song title + artist) — try normalized + raw candidates.
    val appleMusicDeferred = async {
        val candidates =
            linkedSetOf(
                songTitle to artistName,
                songTitleRaw to artistName,
                songTitle to artistNameRaw,
                songTitleRaw to artistNameRaw,
            ).filter { (song, artist) ->
                song.isNotBlank() && artist.isNotBlank()
            }
        candidates.firstNotNullOfOrNull { (song, artist) ->
            runCatching {
                AppleMusicProvider.getBySongArtist(
                    song = song,
                    artist = artist,
                    storefront = storefront,
                    forceRefresh = false,
                    album = albumTitle,
                )
            }.getOrNull()?.takeIf { it.hasRequiredCanvasVariant(requireVertical = false) }
        }
    }

    val results = mutableListOf<CanvasSourceResult>()
    spotifyDeferred.await()?.let { results.add(CanvasSourceResult("Spotify Canvas", it)) }
    appleMusicDeferred.await()?.let { results.add(CanvasSourceResult("Apple Music", it)) }
    results
}

private fun normalizeCanvasSongTitle(raw: String): String {
    val stripped =
        raw
            // Leading track numbers ("01. ", "12 - ") common in files shared on Telegram.
            .replace(Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*"), "")
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(
                Regex(
                    "\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(
                Regex(
                    "\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(
                Regex(
                    "\\s*-\\s*(?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)\\b.*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(Regex("\\s+"), " ")
            .trim()

    return stripped
        .trim('-')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeCanvasArtistName(raw: String): String {
    val first =
        raw
            .split(
                Regex(
                    "(?:\\s*,\\s*|\\s*&\\s*|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
                    RegexOption.IGNORE_CASE,
                ),
                limit = 2,
            ).firstOrNull()
            .orEmpty()

    return first.replace(Regex("\\s+"), " ").trim()
}

/**
 * The canvas sources a lookup is actually allowed to query, as a compact cache key.
 *
 * [CanvasRequestPolicy] is the gate every provider checks before it goes out, so a source
 * that is switched off — or a network the policy blocks — produces exactly the same null as
 * a song that genuinely has no canvas. A lookup that could not ask a source therefore must
 * not record a negative against the ones that did, or switching that source on later would
 * be masked by the stale answer.
 */
private fun queriedCanvasSources(
    strictIdentity: Boolean,
    trySpotifyCanvas: Boolean,
): String {
    fun allowed(source: CanvasSource): Boolean = runCatching { CanvasRequestPolicy.check(source) }.isSuccess

    return buildString {
        if (allowed(CanvasSource.APPLE_MUSIC)) append('A')
        // Spotify Canvas is keyed on the YouTube video id, so a local/Telegram id can never
        // match it — that branch is not attempted and must not count as queried.
        if (trySpotifyCanvas && strictIdentity && allowed(CanvasSource.SPOTIFY)) append('S')
    }
}

/**
 * Short-lived negative-result cache for canvas resolution. Without it a song that resolves
 * to no canvas is re-queried against Apple Music + Spotify every time the UI re-requests
 * artwork (observed every ~2-4 minutes while playing), which burns the Spotify REST quota
 * with 429s and keeps the lookup busy for nothing.
 *
 * [sources] is the source set the lookup was allowed to query (see [queriedCanvasSources]),
 * so a negative recorded while a source was off cannot mask that source once it is on.
 */
internal object CanvasResolutionMissCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private val misses = ConcurrentHashMap<String, Long>()

    private fun key(
        mediaId: String,
        requireVertical: Boolean,
        sources: String,
    ): String = "$mediaId|v$requireVertical|$sources"

    fun isRecentlyMissed(
        mediaId: String,
        requireVertical: Boolean,
        sources: String,
    ): Boolean {
        val cacheKey = key(mediaId, requireVertical, sources)
        val markedAtMs = misses[cacheKey] ?: return false
        if (System.currentTimeMillis() - markedAtMs < TTL_MS) return true
        // Drop it rather than leaving it: the map is only ever read through this TTL check,
        // so expired entries would otherwise accumulate for the life of the process.
        misses.remove(cacheKey)
        return false
    }

    fun markMissed(
        mediaId: String,
        requireVertical: Boolean,
        sources: String,
    ) {
        misses[key(mediaId, requireVertical, sources)] = System.currentTimeMillis()
    }

    /** Forgets every negative for [mediaId], whatever source set or variant it was keyed on. */
    fun clear(mediaId: String) {
        val prefix = "$mediaId|"
        misses.keys.removeAll { it.startsWith(prefix) }
    }
}
