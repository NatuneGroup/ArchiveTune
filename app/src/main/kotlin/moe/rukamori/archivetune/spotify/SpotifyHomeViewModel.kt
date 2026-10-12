/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.AiContentFilterPolicy
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentByCreatorNameUseCase
import moe.rukamori.archivetune.aicontentfilter.ObserveAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedSection
import moe.rukamori.archivetune.spotify.models.SpotifyImage
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistOwner
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef
import javax.inject.Inject

@Serializable
sealed interface SpotifyRecentItem {
    val id: String
    val name: String
    val imageUrl: String?

    @Serializable
    data class Playlist(
        override val id: String,
        override val name: String,
        override val imageUrl: String?
    ) : SpotifyRecentItem

    @Serializable
    data class Album(
        override val id: String,
        override val name: String,
        override val imageUrl: String?,
        val artists: List<SpotifyArtist>
    ) : SpotifyRecentItem
}

sealed interface SpotifyHomeScreenState {
    data object Loading : SpotifyHomeScreenState
    data class Success(
        val sections: List<SpotifyHomeSection>,
        val recentItems: List<SpotifyRecentItem> = emptyList(),
        val frequentArtists: List<SpotifyArtist> = emptyList()
    ) : SpotifyHomeScreenState
    data object Empty : SpotifyHomeScreenState
    data class Error(val messageResId: Int, val notAuthenticated: Boolean = false) : SpotifyHomeScreenState
}

sealed interface SpotifyHomeNavigationEvent {
    data class OpenAlbum(val browseId: String) : SpotifyHomeNavigationEvent
    data class OpenArtist(val id: String) : SpotifyHomeNavigationEvent
    data class PlayTracks(val queue: SpotifyTracksQueue) : SpotifyHomeNavigationEvent

    /** A one-song release with no album page: start it as a radio (#160). */
    data class PlaySong(val song: SongItem) : SpotifyHomeNavigationEvent
    data class ShowMessage(val messageResId: Int) : SpotifyHomeNavigationEvent
}

sealed interface SpotifyHomeAction {
    data object Refresh : SpotifyHomeAction
    data class TrackClick(
        val track: SpotifyTrack,
        val tracks: List<SpotifyTrack>,
        val title: String,
    ) : SpotifyHomeAction
    // Identity plus the words the catalogue search needs, rather than a whole Spotify model. The
    // callers hold four different shapes for the same album (feed item, recent item, search
    // result), and every one of them was rebuilding a SpotifyAlbum just to be taken apart again.
    data class AlbumClick(val id: String, val name: String, val artist: String?) : SpotifyHomeAction
    data class ArtistClick(val id: String, val name: String) : SpotifyHomeAction
}

@HiltViewModel
class SpotifyHomeViewModel @Inject constructor(
    private val repository: SpotifyLibraryRepository,
    private val profileCache: SpotifyProfileCache,
    observeAiContentFilterPolicy: ObserveAiContentFilterPolicyUseCase,
    private val filterAiContent: FilterAiContentByCreatorNameUseCase,
) : ViewModel() {

    // Holds the feed as Spotify sent it, so toggling the AI filter re-filters without a refetch.
    private val _screenState = MutableStateFlow<SpotifyHomeScreenState>(SpotifyHomeScreenState.Loading)
    val screenState: StateFlow<SpotifyHomeScreenState> =
        combine(_screenState, observeAiContentFilterPolicy()) { state, policy ->
            state.withoutAiContent(policy)
        }.flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SpotifyHomeScreenState.Loading,
            )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<SpotifyHomeNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<SpotifyHomeNavigationEvent> = _navigationEvents.asSharedFlow()

    private val _resolvingItemKey = MutableStateFlow<String?>(null)
    val resolvingItemKey = _resolvingItemKey.asStateFlow()
    private var selectionJob: Job? = null
    @Volatile
    private var loadJob: Job? = null

    init {
        // accountChanges replays the current spDc on collect: the first emission
        // is "same account as launch", not a change. Wiping the snapshot there
        // deletes the very cache instant-home needs, so skip emission one and
        // only wipe on genuine changes; the initial load() paints from cache.
        var firstEmission = true
        viewModelScope.launch {
            repository.accountChanges.collect {
                if (firstEmission) {
                    firstEmission = false
                    load()
                } else {
                    // A changed account must never see the previous account's home:
                    // wipe the snapshot, then load fresh.
                    profileCache.clearCache()
                    load()
                }
            }
        }
    }


    fun onAction(action: SpotifyHomeAction) {
        when (action) {
            SpotifyHomeAction.Refresh -> load(force = true)
            is SpotifyHomeAction.TrackClick -> resolveSelection(
                key = "track:${action.track.id}",
                unavailableMessageResId = R.string.spotify_track_unavailable,
            ) {
                SpotifyPlaybackResolver.resolveToMetadata(action.track)?.let { metadata ->
                    SpotifyHomeNavigationEvent.PlayTracks(
                        SpotifyTracksQueue(
                            title = action.title,
                            initialTracks = action.tracks,
                            startIndex = action.tracks.indexOf(action.track).coerceAtLeast(0),
                            preloadItem = metadata,
                        ),
                    )
                }
            }
            is SpotifyHomeAction.AlbumClick -> resolveSelection("album:${action.id}") {
                when (val target = resolveSpotifyAlbumTarget(action.id, action.name, action.artist)) {
                    is SpotifyReleaseTarget.AlbumPage -> SpotifyHomeNavigationEvent.OpenAlbum(target.browseId)
                    is SpotifyReleaseTarget.Song -> SpotifyHomeNavigationEvent.PlaySong(target.song)
                    is SpotifyReleaseTarget.SpotifyTracks ->
                        SpotifyHomeNavigationEvent.PlayTracks(
                            SpotifyTracksQueue(title = action.name, initialTracks = target.tracks),
                        )
                    null -> null
                }
            }
            is SpotifyHomeAction.ArtistClick -> resolveSelection("artist:${action.id}") {
                resolveSpotifyArtistId(action.name)?.let { SpotifyHomeNavigationEvent.OpenArtist(it) }
            }
        }
    }

    fun cancelSelection() {
        selectionJob?.cancel()
        selectionJob = null
        _resolvingItemKey.value = null
    }

    private fun resolveSelection(
        key: String,
        unavailableMessageResId: Int = R.string.no_results_found,
        resolve: suspend () -> SpotifyHomeNavigationEvent?,
    ) {
        if (_resolvingItemKey.value == key && selectionJob?.isActive == true) return
        cancelSelection()
        _resolvingItemKey.value = key
        selectionJob = viewModelScope.launch {
            try {
                val event = withTimeoutOrNull(SPOTIFY_CATALOG_RESOLVE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { resolve() }
                }
                currentCoroutineContext().ensureActive()
                _navigationEvents.emit(event ?: SpotifyHomeNavigationEvent.ShowMessage(unavailableMessageResId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportException(error)
                _navigationEvents.emit(SpotifyHomeNavigationEvent.ShowMessage(unavailableMessageResId))
            } finally {
                // Unconditional: the buffered navigation flow can't strand the
                // emit, so a cancelled tap must not leave the tile spinning.
                _resolvingItemKey.value = null
            }
        }
    }

    private fun load(force: Boolean = false) {
        loadJob?.cancel()
        // Cleared before the spinner is re-armed: the cancelled job's catch can run on IO at any
        // moment from here on, and must not find itself still current and reset the new flag.
        loadJob = null
        cancelSelection()
        // Sync the spinner to this load's intent first: a cancelled forced load
        // never reaches its reset, so without this a stale true would stick.
        _isRefreshing.value = force
        if (!force) {
            _screenState.value = SpotifyHomeScreenState.Loading
        }
        // Started lazily so loadJob is assigned before the body can compare against it.
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val thisJob = coroutineContext[Job]
            // Instant home: paint the last good snapshot first so a cold start or
            // an account switch never flashes a bare spinner, then replace it
            // when the network answers below. Bounded to three small lists, so
            // restore is one DataStore read and no image work.
            val cached = profileCache.restoreFromDataStore()
            if (cached.recentItems.isNotEmpty() ||
                cached.topTracks.isNotEmpty() ||
                cached.frequentArtists.isNotEmpty()
            ) {
                _screenState.update {
                    SpotifyHomeScreenState.Success(
                        sections = cached.topTracks.takeIf { it.isNotEmpty() }?.let { tracks ->
                            listOf(
                                SpotifyHomeSection.Tracks(
                                    title = "spotify_top_tracks",
                                    tracks = tracks,
                                ),
                            )
                        }.orEmpty(),
                        recentItems = cached.recentItems,
                        frequentArtists = cached.frequentArtists,
                    )
                }
            }

            try {
                val session = repository.restoreSession()
                if (!session.isAuthenticated) {
                    _screenState.update { SpotifyHomeScreenState.Error(R.string.spotify_not_connected, notAuthenticated = true) }
                    _isRefreshing.value = false
                    return@launch
                }

                val sections = mutableListOf<SpotifyHomeSection>()
                var frequentArtists = emptyList<SpotifyArtist>()

                val topTracksDeferred = async { Spotify.topTracks(limit = 20) }
                val newReleasesDeferred = async { Spotify.newReleases(limit = 20) }
                val homeDeferred = async { Spotify.home(sectionItemsLimit = 10) }
                val topArtistsDeferred = async { Spotify.topArtists(limit = 20) }

                val topTracksResult = topTracksDeferred.await()
                val newReleasesResult = newReleasesDeferred.await()
                val homeResult = homeDeferred.await()
                val topArtistsResult = topArtistsDeferred.await()
                currentCoroutineContext().ensureActive()

                var topTracksList = emptyList<SpotifyTrack>()
                topTracksResult.onSuccess { topTracks ->
                    if (topTracks.items.isNotEmpty()) {
                        topTracksList = topTracks.items
                        sections.add(
                            SpotifyHomeSection.Tracks(
                                title = "spotify_top_tracks",
                                tracks = topTracks.items,
                            )
                        )
                    }
                }
                // Network top-tracks missing but a snapshot exists: keep the shelf
                // populated rather than dropping it for one failed call.
                if (topTracksList.isEmpty() && cached.topTracks.isNotEmpty()) {
                    sections.add(
                        SpotifyHomeSection.Tracks(
                            title = "spotify_top_tracks",
                            tracks = cached.topTracks,
                        )
                    )
                }

                newReleasesResult.onSuccess { newReleases ->
                    val albums = newReleases.albums?.items.orEmpty()
                    if (albums.isNotEmpty()) {
                        sections.add(
                            SpotifyHomeSection.Cards(
                                title = "spotify_new_releases",
                                items = albums.map { album ->
                                    SpotifyHomeFeedItem.Album(
                                        uri = album.uri.orEmpty(),
                                        id = album.id,
                                        name = album.name,
                                        albumType = album.albumType,
                                        artists = album.artists,
                                        imageUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
                                    )
                                },
                            )
                        )
                    }
                }

                topArtistsResult.onSuccess { topArtists ->
                    frequentArtists = topArtists.items
                }
                if (frequentArtists.isEmpty()) {
                    frequentArtists = cached.frequentArtists
                }

                var recentItems = emptyList<SpotifyRecentItem>()

                homeResult.onSuccess { feed ->
                    feed.sections.forEach { raw ->
                        // Recognised by the section URI alone, never the title: Spotify returns
                        // section titles in the account's language, so a title match only ever
                        // worked in the languages that were hard-coded. The URI is the same string
                        // whatever the user reads.
                        if (raw.sectionUri.contains("recent", ignoreCase = true)) {
                            recentItems = raw.items.mapNotNull { item ->
                                when (item) {
                                    is SpotifyHomeFeedItem.Album -> SpotifyRecentItem.Album(
                                        id = item.id,
                                        name = item.name,
                                        imageUrl = item.imageUrl,
                                        artists = item.artists.map { SpotifyArtist(id = it.id.orEmpty(), name = it.name, uri = it.uri) }
                                    )
                                    is SpotifyHomeFeedItem.Playlist -> SpotifyRecentItem.Playlist(
                                        id = item.id,
                                        name = item.name,
                                        imageUrl = item.imageUrl
                                    )
                                    else -> null
                                }
                            }
                        } else {
                            val converted = convertHomeSection(raw)
                            if (converted != null) {
                                sections.add(converted)
                            }
                        }
                    }
                }

                currentCoroutineContext().ensureActive()
                if (sections.isEmpty() && recentItems.isEmpty() && frequentArtists.isEmpty()) {
                    _screenState.update { SpotifyHomeScreenState.Empty }
                } else {
                    _screenState.update {
                        SpotifyHomeScreenState.Success(
                            sections = sections,
                            recentItems = recentItems,
                            frequentArtists = frequentArtists,
                        )
                    }
                    // Snapshot the network win so the next cold start paints
                    // instantly; cheap single DataStore edit off the UI thread.
                    profileCache.persistToDataStore(
                        recentItems = recentItems,
                        topTracks = topTracksList,
                        frequentArtists = frequentArtists,
                    )
                }
                if (loadJob === thisJob) _isRefreshing.value = false

            } catch (error: CancellationException) {
                // A superseding load already set the flag for itself; only clear
                // when this cancelled job is still the current one.
                if (loadJob === thisJob) _isRefreshing.value = false
                throw error
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e is Spotify.SpotifyException && e.statusCode == 401) {
                    _screenState.update { SpotifyHomeScreenState.Error(R.string.spotify_not_connected, notAuthenticated = true) }
                } else {
                    _screenState.update { SpotifyHomeScreenState.Error(R.string.error_unknown) }
                }
                if (loadJob === thisJob) _isRefreshing.value = false
            }
        }
        loadJob = job
        job.start()
    }

    /**
     * A feed shelf, kept whole. Every item stays, in the order Spotify sent it, carrying its own
     * kind — so a shelf mixing albums with playlists renders both and each tile opens its own
     * thing. The previous version kept only the majority kind and dropped the rest.
     */
    private fun convertHomeSection(feedSection: SpotifyHomeFeedSection): SpotifyHomeSection? {
        val title = feedSection.title ?: return null
        if (feedSection.items.isEmpty()) return null
        return SpotifyHomeSection.Cards(title = title, items = feedSection.items)
    }

    /**
     * Recents and frequent artists are left alone: they are the account's own listening, which the
     * YouTube home does not filter either.
     */
    private fun SpotifyHomeScreenState.withoutAiContent(policy: AiContentFilterPolicy): SpotifyHomeScreenState {
        if (this !is SpotifyHomeScreenState.Success || !policy.enabled) return this
        val filtered = sections.mapNotNull { section ->
            when (section) {
                is SpotifyHomeSection.Tracks ->
                    filterAiContent.spotifyTracks(section.tracks, policy)
                        .takeIf { it.isNotEmpty() }
                        ?.let { section.copy(tracks = it) }
                is SpotifyHomeSection.Cards ->
                    filterAiContent.spotifyFeedItems(section.items, policy)
                        .takeIf { it.isNotEmpty() }
                        ?.let { section.copy(items = it) }
            }
        }
        return if (filtered.isEmpty() && recentItems.isEmpty() && frequentArtists.isEmpty()) {
            SpotifyHomeScreenState.Empty
        } else {
            copy(sections = filtered)
        }
    }
}

/**
 * The YouTube Music page a tapped Spotify release opens.
 *
 * YouTube Music's albums index holds no page for many one-track releases — asked for one, the album
 * filter answers with no results at all, which is what the album-only lookup turned into a "no
 * result found" dead end. The release itself is still indexed as a song there, and that song carries
 * the album page it came from, so the song index resolves exactly the releases the album index
 * cannot.
 */
internal suspend fun resolveSpotifyReleaseAlbumId(
    query: String,
    searchAlbum: suspend (String) -> AlbumItem?,
    searchSong: suspend (String) -> SongItem?,
): String? =
    (resolveSpotifyRelease(query, searchAlbum, searchSong) as? SpotifyReleaseTarget.AlbumPage)?.browseId

/** Where a tapped Spotify release lands in the app. */
internal sealed interface SpotifyReleaseTarget {
    /** The release's YouTube Music album page. */
    data class AlbumPage(val browseId: String) : SpotifyReleaseTarget

    /**
     * The release has no album page at all, but its song is indexed: play that song directly.
     * Singles pushed as "New releases" often land here, and a page-only lookup told users
     * "no result found" for a track that plays fine everywhere else (#160).
     */
    data class Song(val song: SongItem) : SpotifyReleaseTarget

    /**
     * YouTube Music indexes neither the album nor its song under this name, but Spotify's own
     * track list for the release is known: play those through the Spotify-to-YouTube resolver.
     */
    data class SpotifyTracks(val tracks: List<SpotifyTrack>) : SpotifyReleaseTarget
}

/**
 * Resolves a tapped Spotify release: its album page when YouTube Music has one (found through the
 * album index, or through the song index's album link), otherwise the song itself. The song index
 * is asked at most once; [spotifyTracks] is the last resort when it finds nothing either.
 */
internal suspend fun resolveSpotifyRelease(
    query: String,
    searchAlbum: suspend (String) -> AlbumItem?,
    searchSong: suspend (String) -> SongItem?,
    spotifyTracks: suspend () -> List<SpotifyTrack> = { emptyList() },
): SpotifyReleaseTarget? {
    searchAlbum(query)?.browseId?.takeIf(String::isNotBlank)?.let { return SpotifyReleaseTarget.AlbumPage(it) }
    val song = searchSong(query)
    if (song != null) {
        song.album?.id?.takeIf(String::isNotBlank)?.let { return SpotifyReleaseTarget.AlbumPage(it) }
        return SpotifyReleaseTarget.Song(song)
    }
    return spotifyTracks().takeIf { it.isNotEmpty() }?.let { SpotifyReleaseTarget.SpotifyTracks(it) }
}

/**
 * The first [T] YouTube Music returns for [query] under [filter], searched without the account
 * context first and with it as the fallback. Shared by the Spotify home and Spotify search, which
 * both open a tapped Spotify album or artist on its YouTube Music page.
 */
internal suspend inline fun <reified T : YTItem> searchYouTubeCatalogItem(
    query: String,
    filter: YouTube.SearchFilter,
): T? {
    val anonymous = YouTube.search(query, filter, useAccountContext = false)
    currentCoroutineContext().ensureActive()
    anonymous.getOrNull()?.items?.filterIsInstance<T>()?.firstOrNull()?.let { return it }
    val fallback = YouTube.search(query, filter)
    currentCoroutineContext().ensureActive()
    return fallback.getOrThrow().items.filterIsInstance<T>().firstOrNull()
}
