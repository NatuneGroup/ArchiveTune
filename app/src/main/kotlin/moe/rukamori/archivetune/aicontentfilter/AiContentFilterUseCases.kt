/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.aicontentfilter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import timber.log.Timber
import javax.inject.Inject

class ObserveAiContentFilterUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        operator fun invoke(): Flow<Pair<AiContentFilterSettings, AiContentFilterStatus>> =
            combine(repository.observeSettings(), repository.observeStatus(), ::Pair)
    }

class UpdateAiContentFilterSettingsUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        suspend fun setEnabled(enabled: Boolean) {
            repository.setEnabled(enabled)
        }

        suspend fun setIncludeModerateConfidence(enabled: Boolean) {
            repository.setIncludeModerateConfidence(enabled)
        }
    }

class RefreshAiContentFilterUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        suspend operator fun invoke(force: Boolean): AiContentFilterRefreshResult = repository.refreshIfStale(force)
    }

class LoadAiContentFilterPolicyUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        suspend operator fun invoke(): AiContentFilterPolicy {
            val settings = repository.getSettings()
            if (!settings.enabled) return AiContentFilterPolicy.Disabled

            repository.refreshIfStale()
            return repository.loadLists().policyFor(settings)
        }
    }

/**
 * The policy as a stream, for feeds that keep their unfiltered content and re-filter it in place
 * when the setting changes instead of fetching again.
 */
class ObserveAiContentFilterPolicyUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        @OptIn(ExperimentalCoroutinesApi::class)
        operator fun invoke(): Flow<AiContentFilterPolicy> =
            combine(repository.observeSettings(), repository.observeStatus(), ::Pair)
                .transformLatest { (settings, _) ->
                    if (!settings.enabled) {
                        emit(AiContentFilterPolicy.Disabled)
                        return@transformLatest
                    }
                    // The cached lists go out first so a stale list never holds a feed back behind a
                    // download; a refresh that lands changes the status, which comes back through here.
                    emit(repository.loadLists().policyFor(settings))
                    try {
                        repository.refreshIfStale()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "AI content filter refresh failed")
                    }
                }.distinctUntilChanged()
                .flowOn(Dispatchers.IO)
    }

private fun AiChannelLists.policyFor(settings: AiContentFilterSettings): AiContentFilterPolicy =
    AiContentFilterPolicy(
        enabled = true,
        blockedChannelKeys =
            if (settings.includeModerateConfidence) {
                blocklist + warnlist
            } else {
                blocklist
            },
    )

/**
 * The same channel lists applied to catalogues that are not YouTube's. Their items carry their own
 * ids, which mean nothing to a list of YouTube handles and channel ids, so only the creator's name
 * is compared, normalized exactly as a [YTItem] artist's name is.
 */
class FilterAiContentByCreatorNameUseCase
    @Inject
    constructor() {
        fun spotifyTracks(
            tracks: List<SpotifyTrack>,
            policy: AiContentFilterPolicy,
        ): List<SpotifyTrack> = tracks.filterByCreatorNames(policy) { track -> track.artists.asSequence().map { it.name } }

        fun spotifyFeedItems(
            items: List<SpotifyHomeFeedItem>,
            policy: AiContentFilterPolicy,
        ): List<SpotifyHomeFeedItem> =
            items.filterByCreatorNames(policy) { item ->
                when (item) {
                    is SpotifyHomeFeedItem.Album -> item.artists.asSequence().map { it.name }
                    is SpotifyHomeFeedItem.Artist -> sequenceOf(item.name)
                    is SpotifyHomeFeedItem.Playlist -> sequenceOf(item.ownerName)
                }
            }

        /**
         * QQ hands its artists over as one ", "-joined string, so each part is tried as well as
         * the whole.
         */
        fun mediaMetadata(
            items: List<MediaMetadata>,
            policy: AiContentFilterPolicy,
        ): List<MediaMetadata> =
            items.filterByCreatorNames(policy) { item ->
                item.artists.asSequence().flatMap { artist ->
                    sequenceOf(artist.name) + artist.name.split(", ").asSequence()
                }
            }

        private inline fun <T> List<T>.filterByCreatorNames(
            policy: AiContentFilterPolicy,
            crossinline creatorNames: (T) -> Sequence<String?>,
        ): List<T> {
            if (!policy.enabled || policy.blockedChannelKeys.isEmpty()) return this
            return filterNot { item ->
                creatorNames(item)
                    .filterNotNull()
                    .mapNotNull(::normalizeChannelKey)
                    .any(policy.blockedChannelKeys::contains)
            }
        }
    }

class FilterAiContentUseCase
    @Inject
    constructor() {
        operator fun <T : YTItem> invoke(
            items: List<T>,
            policy: AiContentFilterPolicy,
        ): List<T> {
            if (!policy.enabled || policy.blockedChannelKeys.isEmpty()) return items
            return items.filterNot { item -> item.matches(policy.blockedChannelKeys) }
        }

        private fun YTItem.matches(blockedChannelKeys: Set<String>): Boolean = creatorKeys().any(blockedChannelKeys::contains)

        private fun YTItem.creatorKeys(): Sequence<String> =
            when (this) {
                is SongItem -> {
                    artists.asSequence().flatMap { artist -> artist.keys() }
                }

                is AlbumItem -> {
                    artists.orEmpty().asSequence().flatMap { artist -> artist.keys() }
                }

                is PlaylistItem -> {
                    author?.keys().orEmpty()
                }

                is ArtistItem -> {
                    sequenceOf(title, id, channelId)
                        .filterNotNull()
                        .mapNotNull(::normalizeChannelKey)
                }

                is PodcastItem -> author?.keys().orEmpty()

                is EpisodeItem -> podcast?.keys().orEmpty()
            }

        private fun Artist.keys(): Sequence<String> =
            sequenceOf(name, id)
                .filterNotNull()
                .mapNotNull(::normalizeChannelKey)
    }
