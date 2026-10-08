/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.applemusic

import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object AppleMusicCatalog {
    private const val SEARCH_ENDPOINT = "https://itunes.apple.com/search"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    data class CatalogPage(
        val items: List<AppleMusicSearchItem>,
        val anyEndpointFull: Boolean,
    )

    suspend fun searchPage(
        query: String,
        limit: Int,
        offset: Int,
    ): CatalogPage =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext CatalogPage(emptyList(), false)

            coroutineScope {
                val entities = listOf("song", "album", "musicArtist")
                val responses =
                    entities
                        .map { entity ->
                            async {
                                runCatching { request(trimmed, entity, limit, offset) }
                                    .onFailure { it.rethrowIfAudioSourceCancelled() }
                                    .getOrNull()
                            }
                        }.awaitAll()

                val tracks = responses.getOrNull(0)?.toTracks().orEmpty()
                val albums = responses.getOrNull(1)?.toAlbums().orEmpty()
                val artists = responses.getOrNull(2)?.toArtists().orEmpty()

                val items =
                    (tracks + albums + artists).distinctBy { it.key }
                val anyFull = responses.any { (it?.resultCount ?: 0) >= limit }
                CatalogPage(items = items, anyEndpointFull = anyFull)
            }
        }

    suspend fun searchTrackSuggestions(
        query: String,
        limit: Int,
    ): List<AppleMusicSearchItem> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            runCatching { request(trimmed, "song", limit, 0) }
                .onFailure { it.rethrowIfAudioSourceCancelled() }
                .getOrNull()
                ?.toTracks()
                .orEmpty()
        }

    private fun request(
        query: String,
        entity: String,
        limit: Int,
        offset: Int,
    ): ITunesSearchResponse {
        val url =
            SEARCH_ENDPOINT.toHttpUrl().newBuilder().apply {
                addQueryParameter("term", query)
                addQueryParameter("media", "music")
                addQueryParameter("entity", entity)
                addQueryParameter("limit", limit.toString())
                if (offset > 0) addQueryParameter("offset", offset.toString())
            }.build()

        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

        client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
            if (!response.isSuccessful) error("Apple Music catalog search failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("Apple Music catalog search returned an empty body")
            return json.decodeFromString(ITunesSearchResponse.serializer(), body)
        }
    }

    internal fun upscaleArtwork(url: String?): String? =
        url?.replace("/100x100bb.jpg", "/200x200bb.jpg")
}

private fun ITunesSearchResponse.toTracks(): List<AppleMusicSearchItem.Track> =
    results.mapNotNull { result ->
        val id = result.trackId ?: return@mapNotNull null
        val name = result.trackName ?: return@mapNotNull null
        AppleMusicSearchItem.Track(
            id = id.toString(),
            title = name,
            artist = result.artistName.orEmpty(),
            album = result.collectionName,
            artworkUrl = AppleMusicCatalog.upscaleArtwork(result.artworkUrl100),
            durationMs = result.trackTimeMillis ?: 0L,
            viewUrl = result.trackViewUrl,
            explicit = result.trackExplicitness == "explicit",
        )
    }

private fun ITunesSearchResponse.toAlbums(): List<AppleMusicSearchItem.Album> =
    results.mapNotNull { result ->
        val id = result.collectionId ?: return@mapNotNull null
        val name = result.collectionName ?: return@mapNotNull null
        AppleMusicSearchItem.Album(
            id = id.toString(),
            title = name,
            artist = result.collectionArtistName ?: result.artistName.orEmpty(),
            artworkUrl = AppleMusicCatalog.upscaleArtwork(result.artworkUrl100),
            trackCount = result.trackCount ?: 0,
            releaseYear = result.releaseDate?.take(4),
            viewUrl = result.collectionViewUrl,
        )
    }

private fun ITunesSearchResponse.toArtists(): List<AppleMusicSearchItem.Artist> =
    results.mapNotNull { result ->
        val id = result.artistId ?: return@mapNotNull null
        val name = result.artistName ?: return@mapNotNull null
        AppleMusicSearchItem.Artist(
            id = id.toString(),
            title = name,
            genre = result.primaryGenreName,
            viewUrl = result.artistViewUrl,
        )
    }
