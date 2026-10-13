/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.DownloadSourceConfig

/**
 * One immutable read of the user's source settings: the order they arranged in "Preferred sources"
 * plus the set of sources currently enabled. Every playback path (normal play, prefetch, the
 * crossfade/automix secondary player, Cast, per-song pins, the cache shortcut) builds exactly one
 * of these per resolution and derives its chain from it, so the UI and the resolver cannot
 * disagree about order or toggles.
 *
 * Pure data — no DataStore, no Android types. The caller reads the raw preference values.
 */
data class PlaybackSourceSnapshot(
    val order: List<AudioSourceType>,
    val enabled: Set<AudioSourceType>,
)

/**
 * Pure derivation of the ordered, gated source lists a resolution needs, from one
 * [PlaybackSourceSnapshot]. MusicService reads the preference values once and hands them here;
 * nothing else in the app may build its own source order.
 */
object PlaybackSourceChain {
    /**
     * The enabled sources in the user's priority order, cut at YouTube: everything from YouTube
     * onward is unreachable (the terminal YouTube fallback handles that position), and disabled
     * sources are skipped. Empty when only YouTube is enabled.
     */
    fun baseChain(snapshot: PlaybackSourceSnapshot): List<AudioSourceType> =
        snapshot.order.takeWhile { it != AudioSourceType.YOUTUBE }.filter { it in snapshot.enabled }

    /**
     * The chain a single resolution actually attempts: the per-song pin's chain if one applies,
     * otherwise [baseChain]. A pin — plain "play from" override or an exact Qobuz/Qobuz-backup
     * pick — whose source the user has since turned off yields to the base chain like a stale
     * pin; an enabled YouTube pin empties the chain so the terminal fallback serves the song.
     * Authenticity rejections fall through the AudioAuthenticitySourceRetryPolicy at the caller.
     */
    fun configuredChain(
        snapshot: PlaybackSourceSnapshot,
        override: AudioSourceType?,
        rejected: Set<AudioSourceType>,
    ): List<AudioSourceType> {
        val base = baseChain(snapshot)
        if (override == null) return base
        if (override !in snapshot.enabled) return base
        if (override == AudioSourceType.YOUTUBE) {
            return if (override in rejected) base else emptyList()
        }
        return listOf(override)
    }

    /**
     * Whether the ephemeral YouTube player cache may short-circuit a resolution. Any enabled
     * source above YouTube — JioSaavn included — must bypass it, or toggling that source on
     * would keep replaying previously cached YouTube bytes instead of taking effect.
     */
    fun cacheShortCircuitAllowed(snapshot: PlaybackSourceSnapshot): Boolean = baseChain(snapshot).isEmpty()

    /**
     * The cache keys to probe for [mediaId], in preference order: enabled sources in the user's
     * order, YouTube's bare key last and only while YouTube is enabled. Sources without a cache
     * key (Apple, Amazon, QQ, Spotify) contribute nothing. A disabled source's key is left out
     * entirely — its player cache and its persistent downloads alike are not served, the same
     * rule the download chain follows; re-enabling the source plays them again.
     */
    fun cacheKeyOrder(
        snapshot: PlaybackSourceSnapshot,
        mediaId: String,
    ): List<String> =
        snapshot.order
            .filter { it in snapshot.enabled }
            .mapNotNull { source -> cacheProbeKey(source, mediaId) }

    /**
     * The cache key [source]'s bytes are probed under, or null when the source has none. The
     * prefixes must stay identical to [DownloadSourceConfig.CACHE_KEY_PREFIXES], which is what
     * downloads write under — a drift-guard test pins the two together.
     */
    fun cacheProbeKey(
        source: AudioSourceType,
        mediaId: String,
    ): String? =
        when (source) {
            AudioSourceType.TIDAL -> "tidal:$mediaId"
            AudioSourceType.QOBUZ -> "qobuz:$mediaId"
            AudioSourceType.QOBUZ_BACKUP -> "qobuz_backup:$mediaId"
            AudioSourceType.DEEZER -> "deezer:$mediaId"
            AudioSourceType.JIOSAAVN -> "jiosaavn:$mediaId"
            AudioSourceType.YOUTUBE -> mediaId
            AudioSourceType.APPLE, AudioSourceType.AMAZON, AudioSourceType.QQ, AudioSourceType.SPOTIFY -> null
        }

    /**
     * The enabled set from the per-source switches. Spotify counts only when its switch is on
     * AND a streaming account is signed in — the switch alone does nothing, so neither the
     * resolver nor the order picker may treat the source as active.
     */
    fun enabledSet(
        flags: Map<AudioSourceType, Boolean>,
        spotifySignedIn: Boolean,
    ): Set<AudioSourceType> =
        flags
            .filter { (source, enabled) -> source != AudioSourceType.SPOTIFY && enabled }
            .keys +
            (if (flags[AudioSourceType.SPOTIFY] == true && spotifySignedIn) {
                setOf(AudioSourceType.SPOTIFY)
            } else {
                emptySet()
            })
}
