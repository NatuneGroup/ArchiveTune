/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.SearchResult
import moe.rukamori.archivetune.innertube.utils.completed
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import moe.rukamori.archivetune.utils.withAppProxy

/**
 * Resolves a playlist URL from a foreign music service (Apple Music, Amazon Music, Tidal, Deezer)
 * into a list of `(title, artist)` pairs, which are then matched against YouTube Music via
 * [YouTube.search] to produce local YouTube Music song ids.
 */
object CrossServicePlaylistImporter {

    /** The track-tuple extracted from the foreign service. */
    data class ForeignTrack(
        val title: String,
        val artist: String?,
        val album: String? = null,
        val durationMs: Long? = null,
    )

    /** The resolved import — playlist name + foreign tracks to look up. */
    data class ResolvedImport(
        val source: ImportSource,
        val sourcePlaylistId: String,
        val title: String,
        val thumbnailUrl: String?,
        val tracks: List<ForeignTrack>,
    )

    enum class ImportSource(val displayName: String) {
        YOUTUBE_MUSIC("YouTube Music"),
        SPOTIFY("Spotify"),
        APPLE_MUSIC("Apple Music"),
        AMAZON_MUSIC("Amazon Music"),
        TIDAL("Tidal"),
        QOBUZ("Qobuz"),
        DEEZER("Deezer"),
        UNKNOWN("Unknown"),
    }

    /**
     * Credentials for the services whose playlist APIs are not publicly readable. Tidal and Qobuz
     * both reject anonymous playlist reads, so the caller supplies whatever the user already has
     * configured (own account or a community Source Pool account) — see
     * `CrossServiceImportCredentials.load`.
     */
    data class Credentials(
        val tidalAccessToken: String? = null,
        val tidalCountryCode: String = "US",
        val qobuzAppId: String? = null,
        val qobuzAuthToken: String? = null,
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().withAppProxy()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Detects the source service from a URL. Returns [ImportSource.UNKNOWN]
     * for unrecognized URLs so the caller can show a friendly error.
     */
    fun detectSource(url: String): ImportSource = when {
        url.contains("music.youtube.com") || url.contains("youtube.com/playlist") -> ImportSource.YOUTUBE_MUSIC
        url.contains("spotify.com") || url.startsWith("spotify:") -> ImportSource.SPOTIFY
        url.contains("music.apple.com") -> ImportSource.APPLE_MUSIC
        url.contains("music.amazon.com") -> ImportSource.AMAZON_MUSIC
        url.contains("tidal.com") -> ImportSource.TIDAL
        url.contains("qobuz.com") -> ImportSource.QOBUZ
        url.contains("deezer.com") -> ImportSource.DEEZER
        else -> ImportSource.UNKNOWN
    }

    /**
     * Fetches the foreign playlist metadata + tracklist. For YouTube Music
     * this delegates to the existing [YouTube.playlist] API and returns
     * tracks whose ids are already YouTube Music song ids (so callers can
     * skip the [resolveToYouTubeMusic] step). For all other services the
     * tracks are `(title, artist)` tuples that need to be looked up.
     */
    suspend fun fetchPlaylist(
        url: String,
        credentials: Credentials = Credentials(),
    ): Result<ResolvedImport> = withContext(Dispatchers.IO) {
        runCatching {
            val source = detectSource(url)
            when (source) {
                ImportSource.YOUTUBE_MUSIC -> {
                    val playlistId = extractQuery(url, "list")
                        ?: error("Missing 'list' query parameter in YouTube URL")
                    val page = YouTube.playlist(playlistId).getOrNull()
                        ?: error("Could not fetch YouTube playlist (it may be private)")
                    ResolvedImport(
                        source = source,
                        sourcePlaylistId = playlistId,
                        title = page.playlist.title,
                        thumbnailUrl = page.playlist.thumbnail,
                        tracks = page.songs.map {
                            ForeignTrack(
                                title = it.title,
                                artist = it.artists.firstOrNull()?.name,
                                album = it.album?.name,
                                durationMs = it.duration?.toLong()?.times(1000L),
                            )
                        },
                    )
                }
                ImportSource.SPOTIFY -> fetchSpotifyPlaylist(url)
                ImportSource.APPLE_MUSIC -> fetchAppleMusicPlaylist(url)
                ImportSource.AMAZON_MUSIC -> fetchAmazonMusicPlaylist(url)
                ImportSource.TIDAL -> fetchTidalPlaylist(url, credentials)
                ImportSource.QOBUZ -> fetchQobuzPlaylist(url, credentials)
                ImportSource.DEEZER -> fetchDeezerPlaylist(url)
                ImportSource.UNKNOWN -> error(
                    "Unrecognized URL — supported: YouTube Music, Spotify, Apple Music, " +
                        "Amazon Music, Tidal, Qobuz, Deezer",
                )
            }
        }
    }

    /**
     * Minimum SpotifyMapper score for a YouTube Music hit to be accepted as the wanted track at
     * import time.
     */
    private const val IMPORT_MATCH_THRESHOLD = 0.6

    /**
     * Picks the best-scoring YouTube Music hit for [track], or null when none of
     * the results actually is the track. See [IMPORT_MATCH_THRESHOLD].
     */
    private fun bestYouTubeMatch(
        track: ForeignTrack,
        search: SearchResult?,
    ): SongItem? {
        val candidates = search?.items.orEmpty().filterIsInstance<SongItem>()
        if (candidates.isEmpty()) return null
        val precomputed =
            SpotifyMapper.precompute(
                title = track.title,
                artist = track.artist.orEmpty(),
                durationMs = track.durationMs?.toInt() ?: 0,
            )
        return candidates
            .map { candidate ->
                candidate to
                    SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artists.joinToString(" ") { it.name },
                        candidateDurationSec = candidate.duration,
                    )
            }.filter { (_, score) -> score >= IMPORT_MATCH_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /**
     * Resolves a list of [ForeignTrack]s to YouTube Music song ids via
     * [YouTube.search]. Returns the ids (in the same order as the input
     * where possible) — tracks that can't be matched are skipped.
     *
     * @param onProgress optional callback invoked with (resolved, total)
     *        after each track resolves. Lets the UI show a live counter.
     */
    suspend fun resolveToYouTubeMusic(
        tracks: List<ForeignTrack>,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<String> = coroutineScope {
        val total = tracks.size
        if (total == 0) return@coroutineScope emptyList()
        val results = mutableListOf<String>()
        var completed = 0

        // Process in bounded-concurrency batches so we don't hammer
        // InnerTube with 100+ parallel searches for a 100-track playlist.
        tracks.chunked(MAX_PARALLEL_SEARCHES).forEach { batch ->
            val resolved = batch.map { track ->
                async {
                    val term = listOfNotNull(track.artist?.takeIf(String::isNotBlank), track.title)
                        .joinToString(" ")
                        .ifBlank { return@async null }
                    val search = YouTube.search(term, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    bestYouTubeMatch(track, search)?.id
                }
            }.awaitAll()
            resolved.filterNotNull().forEach { results.add(it) }
            completed += batch.size
            onProgress?.invoke(completed, total)
        }
        results
    }

    /**
     * Same as [resolveToYouTubeMusic] but returns the fully-resolved [MediaMetadata] for each
     * matched track (instead of just the song id).
     */
    suspend fun resolveToYouTubeMusicMetadata(
        tracks: List<ForeignTrack>,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<MediaMetadata> = coroutineScope {
        val total = tracks.size
        if (total == 0) return@coroutineScope emptyList()
        val results = mutableListOf<MediaMetadata>()
        var completed = 0

        // Process in bounded-concurrency batches so we don't hammer
        // InnerTube with 100+ parallel searches for a 100-track playlist.
        tracks.chunked(MAX_PARALLEL_SEARCHES).forEach { batch ->
            val resolved = batch.map { track ->
                async {
                    val term = listOfNotNull(track.artist?.takeIf(String::isNotBlank), track.title)
                        .joinToString(" ")
                        .ifBlank { return@async null }
                    val search = YouTube.search(term, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    bestYouTubeMatch(track, search)?.toMediaMetadata()
                }
            }.awaitAll()
            resolved.filterNotNull().forEach { results.add(it) }
            completed += batch.size
            onProgress?.invoke(completed, total)
        }
        results
    }

    /**
     * Fetches a YouTube Music playlist (following continuation pages) and returns the
     * fully-resolved [MediaMetadata] for every song.
     */
    suspend fun fetchYouTubePlaylistSongs(playlistId: String): List<MediaMetadata> =
        withContext(Dispatchers.IO) {
            val page = YouTube.playlist(playlistId).completed().getOrNull() ?: return@withContext emptyList()
            page.songs.map { it.toMediaMetadata() }
        }

    // ─── Spotify ────────────────────────────────────────────────────────── URL pattern:
    // https://open.spotify.com/playlist/{base62-id} Two paths, in order of preference: 1.
    private suspend fun fetchSpotifyPlaylist(url: String): ResolvedImport {
        val id = extractSpotifyPlaylistId(url)
            ?: error("Couldn't extract Spotify playlist id from URL")

        if (Spotify.isAuthenticated()) {
            val viaApi = runCatching { fetchSpotifyPlaylistViaApi(id) }.getOrNull()
            if (viaApi != null && viaApi.tracks.isNotEmpty()) return viaApi
        }
        return fetchSpotifyPlaylistViaEmbed(id)
    }

    /** Pages through the authenticated GQL endpoint until every track is collected. */
    private suspend fun fetchSpotifyPlaylistViaApi(id: String): ResolvedImport {
        val meta = Spotify.playlist(id).getOrNull()
        val tracks = mutableListOf<ForeignTrack>()
        var offset = 0
        while (true) {
            val page = Spotify.playlistTracks(id, limit = SPOTIFY_PAGE_SIZE, offset = offset)
                .getOrNull() ?: break
            val items = page.items.mapNotNull { item ->
                val track = item.track ?: return@mapNotNull null
                val title = track.name.ifBlank { return@mapNotNull null }
                ForeignTrack(
                    title = title,
                    artist = track.artists.firstOrNull()?.name,
                    album = track.album?.name,
                    durationMs = track.durationMs.toLong().takeIf { it > 0 },
                )
            }
            tracks.addAll(items)
            offset += SPOTIFY_PAGE_SIZE
            // Stop when we've drained the playlist or the API stopped
            // returning rows (guards against an off-by-one `total`).
            if (page.items.isEmpty() || offset >= page.total || tracks.size >= SPOTIFY_MAX_TRACKS) break
        }
        return ResolvedImport(
            source = ImportSource.SPOTIFY,
            sourcePlaylistId = id,
            title = meta?.name?.takeIf { it.isNotBlank() } ?: "Spotify Playlist",
            thumbnailUrl = meta?.images?.firstOrNull()?.url?.takeIf { it.isNotBlank() },
            tracks = tracks,
        )
    }

    /**
     * Reads the public `open.spotify.com/embed/playlist/{id}` page. The page
     * embeds a `__NEXT_DATA__` script whose
     * `props.pageProps.state.data.entity` holds `name`, `coverArt.sources`
     * and a `trackList` of `{title, subtitle, duration}` — `subtitle` is the
     * artist name.
     */
    private suspend fun fetchSpotifyPlaylistViaEmbed(id: String): ResolvedImport {
        val html = fetchText("https://open.spotify.com/embed/playlist/$id")
        val nextData = extractNextData(html)
            ?: error("Spotify playlist is private or unavailable")
        val entity = JSONObject(nextData)
            .optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("state")
            ?.optJSONObject("data")
            ?.optJSONObject("entity")
            ?: error("Spotify playlist is private or unavailable")

        val trackList = entity.optJSONArray("trackList")
        val tracks = (0 until (trackList?.length() ?: 0)).mapNotNull { i ->
            val obj = trackList?.optJSONObject(i) ?: return@mapNotNull null
            val title = obj.optString("title").ifBlank { return@mapNotNull null }
            ForeignTrack(
                title = title,
                artist = obj.optString("subtitle").ifBlank { null },
                durationMs = obj.optLong("duration").takeIf { it > 0 },
            )
        }
        val cover = entity.optJSONObject("coverArt")
            ?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.ifBlank { null }

        return ResolvedImport(
            source = ImportSource.SPOTIFY,
            sourcePlaylistId = id,
            title = entity.optString("name").ifBlank { "Spotify Playlist" },
            thumbnailUrl = cover,
            tracks = tracks,
        )
    }

    /** Accepts web URLs, `spotify:playlist:` URIs, and embed links. */
    internal fun extractSpotifyPlaylistId(url: String): String? {
        val uri = Pattern.compile("spotify:playlist:([A-Za-z0-9]+)").matcher(url)
        if (uri.find()) return uri.group(1)
        val web = Pattern.compile("playlist[/:]([A-Za-z0-9]{16,})").matcher(url)
        return if (web.find()) web.group(1) else null
    }

    // ─── Apple Music ──────────────────────────────────────────────────────
    // URL pattern: https://music.apple.com/{cc}/playlist/{slug}/pl.{u}-{id}
    // Apple Music's public page embeds a `<script name="schema:music-playlist"
    // type="application/json">` block containing the full track list with
    // name + artistName. We extract from that. If the schema block isn't
    // present (older layouts), we fall back to scraping the JSON-LD
    // MusicRecording entries.
    private suspend fun fetchAppleMusicPlaylist(url: String): ResolvedImport {
        val html = fetchText(url)
        val id = extractAppleMusicPlaylistId(url) ?: url
        val title = extractAppleMusicTitle(html)
        val tracks = parseAppleMusicTracks(html)
        return ResolvedImport(
            source = ImportSource.APPLE_MUSIC,
            sourcePlaylistId = id,
            title = title,
            thumbnailUrl = null,
            tracks = tracks,
        )
    }

    private fun extractAppleMusicPlaylistId(url: String): String? {
        val m = Pattern.compile("playlist/[^/]+/pl\\.([a-zA-Z0-9.\\-]+)").matcher(url)
        return if (m.find()) "pl.${m.group(1)}" else null
    }

    private fun extractAppleMusicTitle(html: String): String {
        // Try the Open Graph og:title first, then the schema:name field,
        // then the JSON-LD name field.
        val og = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").matcher(html)
        if (og.find()) return unescapeJson(og.group(1))
        val schema = Pattern.compile("\"@type\":\"MusicPlaylist\"[^}]*?\"name\":\"([^\"]+)\"").matcher(html)
        if (schema.find()) return unescapeJson(schema.group(1))
        val ld = Pattern.compile("\\{\"@type\":\"MusicPlaylist\",\"name\":\"([^\"]+)\"").matcher(html)
        if (ld.find()) return unescapeJson(ld.group(1))
        return "Apple Music Playlist"
    }

    private fun parseAppleMusicTracks(html: String): List<ForeignTrack> {
        val tracks = mutableListOf<ForeignTrack>()

        // Strategy 1: the `serialized-server-data` script block — this is what
        // current Apple Music pages server-render. Track rows appear nested
        // several levels deep (data[].data.sections[].items[]) and each carries
        // a `title` + `artistName`, so we walk the whole tree rather than
        // hardcoding indices that Apple reshuffles between redesigns.
        extractScriptJson(html, "serialized-server-data")?.let { raw ->
            runCatching {
                collectAppleMusicTracks(JSONTokener(raw).nextValue(), tracks)
            }
        }
        if (tracks.isNotEmpty()) {
            return tracks.distinctBy { it.title to it.artist }
        }

        // Strategy 2: the older schema:music-playlist JSON blob, where each
        // track looks like {"name":"...","artistName":"..."}.
        val schemaRegex = Pattern.compile("\\{\"name\":\"([^\"]{2,200})\",\"artistName\":\"([^\"]{2,200})\"")
        val sm = schemaRegex.matcher(html)
        while (sm.find()) {
            tracks.add(ForeignTrack(
                title = unescapeJson(sm.group(1)),
                artist = unescapeJson(sm.group(2)),
            ))
        }
        if (tracks.isNotEmpty()) {
            return tracks.distinctBy { it.title to it.artist }
        }

        // Strategy 3: JSON-LD MusicRecording entries.
        val itemRegex = Pattern.compile("\\{\"@type\":\"MusicRecording\",\"name\":\"([^\"]+)\"[^}]*?(?:\"byArtist\":\\{\"@type\":\"MusicGroup\",\"name\":\"([^\"]+)\"\\})?")
        val m = itemRegex.matcher(html)
        while (m.find()) {
            val title = unescapeJson(m.group(1) ?: continue)
            val artist = m.group(2)?.let { unescapeJson(it) }
            tracks.add(ForeignTrack(title = title, artist = artist))
        }
        // Fallback: scrape from the simpler "track-list" serialization
        // Apple Music sometimes uses for short playlists.
        if (tracks.isEmpty()) {
            val fallback = Pattern.compile("\"name\":\"([^\"]{2,80})\"[^}]{0,400}?\"artistName\":\"([^\"]+)\"")
            val fm = fallback.matcher(html)
            while (fm.find()) {
                tracks.add(ForeignTrack(
                    title = unescapeJson(fm.group(1)),
                    artist = unescapeJson(fm.group(2)),
                ))
            }
        }
        return tracks.distinctBy { it.title to it.artist }
    }

    /**
     * Depth-first walk over the Apple Music server-data tree, collecting any
     * object that carries both a `title` and an `artistName`. Order is
     * preserved because Apple serialises the track rows in playlist order.
     */
    private fun collectAppleMusicTracks(node: Any?, into: MutableList<ForeignTrack>) {
        when (node) {
            is JSONObject -> {
                val title = node.optString("title").takeIf { it.isNotBlank() }
                val artist = node.optString("artistName").takeIf { it.isNotBlank() }
                if (title != null && artist != null) {
                    into.add(
                        ForeignTrack(
                            title = title,
                            artist = artist,
                            durationMs = node.optLong("durationInMillis").takeIf { it > 0 },
                        ),
                    )
                }
                node.keys().forEach { key -> collectAppleMusicTracks(node.opt(key), into) }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectAppleMusicTracks(node.opt(i), into)
            }
        }
    }

    // ─── Amazon Music ─────────────────────────────────────────────────────
    // URL pattern: https://music.amazon.com/{cc}/playlists/{id}
    // Amazon Music's page is JS-rendered, but the initial HTML includes a
    // `<script>` with a `musicPluginProps` JSON blob containing the track
    // list. We extract that.
    private suspend fun fetchAmazonMusicPlaylist(url: String): ResolvedImport {
        val html = fetchText(url)
        val id = extractAmazonPlaylistId(url) ?: url
        val title = extractAmazonMusicTitle(html)
        val tracks = parseAmazonMusicTracks(html)
        return ResolvedImport(
            source = ImportSource.AMAZON_MUSIC,
            sourcePlaylistId = id,
            title = title,
            thumbnailUrl = null,
            tracks = tracks,
        )
    }

    private fun extractAmazonPlaylistId(url: String): String? {
        val m = Pattern.compile("playlists/([A-Z0-9]+)").matcher(url)
        return if (m.find()) m.group(1) else null
    }

    private fun extractAmazonMusicTitle(html: String): String {
        val og = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").matcher(html)
        if (og.find()) return unescapeJson(og.group(1))
        val title = Pattern.compile("<title>([^<]+)</title>").matcher(html)
        if (title.find()) {
            val raw = title.group(1).trim()
            // Amazon Music titles look like "Playlist Name | Amazon Music"
            return raw.substringBefore(" |").ifBlank { raw }
        }
        return "Amazon Music Playlist"
    }

    private fun parseAmazonMusicTracks(html: String): List<ForeignTrack> {
        val tracks = mutableListOf<ForeignTrack>()
        // Amazon Music embeds track data as `{"title":"...","artist":"..."}`
        // inside the musicPluginProps blob. The exact key names have varied
        // across redesigns so we keep the regex tolerant.
        val regex = Pattern.compile("\\{\"title\":\"([^\"]{2,120})\",\"artist\":\"([^\"]+)\"")
        val m = regex.matcher(html)
        while (m.find()) {
            tracks.add(ForeignTrack(
                title = unescapeJson(m.group(1)),
                artist = unescapeJson(m.group(2)),
            ))
        }
        // Fallback: `{"trackName":"...","artistName":"..."}`
        if (tracks.isEmpty()) {
            val alt = Pattern.compile("\\{\"trackName\":\"([^\"]{2,120})\",\"artistName\":\"([^\"]+)\"")
            val am = alt.matcher(html)
            while (am.find()) {
                tracks.add(ForeignTrack(
                    title = unescapeJson(am.group(1)),
                    artist = unescapeJson(am.group(2)),
                ))
            }
        }
        return tracks.distinctBy { it.title to it.artist }
    }

    // ─── Tidal ────────────────────────────────────────────────────────────
    // URL pattern: https://tidal.com/browse/playlist/{uuid}
    //
    // tidal.com is a client-rendered SPA — the served HTML contains no track
    // data at all, so scraping it can't work. Instead we call the same
    // `api.tidal.com/v1` endpoints the app's playback path already uses, with
    // the user's own OAuth token or a community Source Pool account. Playlist
    // reads are paginated 100 items at a time.
    private suspend fun fetchTidalPlaylist(url: String, credentials: Credentials): ResolvedImport {
        val id = extractTidalPlaylistId(url) ?: error("Couldn't extract Tidal playlist id from URL")
        val accessToken = credentials.tidalAccessToken?.takeIf { it.isNotBlank() }
            ?: error("Tidal import needs a signed-in Tidal account (Settings → Integration → Tidal)")
        val country = credentials.tidalCountryCode.ifBlank { "US" }

        val meta = runCatching {
            JSONObject(
                fetchText(
                    url = "$TIDAL_API_BASE/playlists/$id?countryCode=$country",
                    headers = mapOf("Authorization" to "Bearer $accessToken"),
                ),
            )
        }.getOrNull()

        val tracks = mutableListOf<ForeignTrack>()
        var offset = 0
        while (tracks.size < TIDAL_MAX_TRACKS) {
            val body = fetchText(
                url = "$TIDAL_API_BASE/playlists/$id/items" +
                    "?countryCode=$country&limit=$TIDAL_PAGE_SIZE&offset=$offset",
                headers = mapOf("Authorization" to "Bearer $accessToken"),
            )
            val root = JSONObject(body)
            val items = root.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                // Each row wraps the payload as {"item": {...}, "type": "track"}.
                val wrapper = items.optJSONObject(i) ?: continue
                if (wrapper.optString("type").let { it.isNotBlank() && it != "track" }) continue
                val item = wrapper.optJSONObject("item") ?: wrapper
                val title = item.optString("title")
                if (title.isBlank()) continue
                tracks.add(
                    ForeignTrack(
                        title = title,
                        artist = item.optJSONObject("artist")?.optString("name")?.ifBlank { null }
                            ?: item.optJSONArray("artists")?.optJSONObject(0)?.optString("name")?.ifBlank { null },
                        album = item.optJSONObject("album")?.optString("title")?.ifBlank { null },
                        durationMs = item.optLong("duration").takeIf { it > 0 }?.times(1000L),
                    ),
                )
            }
            offset += TIDAL_PAGE_SIZE
            if (offset >= root.optInt("totalNumberOfItems", offset)) break
        }

        return ResolvedImport(
            source = ImportSource.TIDAL,
            sourcePlaylistId = id,
            title = meta?.optString("title")?.ifBlank { null } ?: "Tidal Playlist",
            thumbnailUrl = meta?.optString("squareImage")?.ifBlank { null }
                ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" },
            tracks = tracks,
        )
    }

    // ─── Qobuz ────────────────────────────────────────────────────────────
    // URL patterns: https://open.qobuz.com/playlist/{id}
    //               https://www.qobuz.com/{cc}/playlists/{slug}/{id}
    //
    // Qobuz rejects anonymous playlist reads (401), so we sign the request
    // with an app_id + user auth token — the user's own pasted Qobuz token or
    // a community Source Pool account, exactly like the playback path does.
    private suspend fun fetchQobuzPlaylist(url: String, credentials: Credentials): ResolvedImport {
        val id = extractQobuzPlaylistId(url) ?: error("Couldn't extract Qobuz playlist id from URL")
        val appId = credentials.qobuzAppId?.takeIf { it.isNotBlank() }
        val authToken = credentials.qobuzAuthToken?.takeIf { it.isNotBlank() }
        if (appId == null || authToken == null) {
            error("Qobuz import needs a Qobuz token (Settings → Integration → Qobuz)")
        }

        val tracks = mutableListOf<ForeignTrack>()
        var title = "Qobuz Playlist"
        var thumbnail: String? = null
        var offset = 0
        while (tracks.size < QOBUZ_MAX_TRACKS) {
            val body = fetchText(
                url = "$QOBUZ_API_BASE/playlist/get?playlist_id=$id&extra=tracks" +
                    "&limit=$QOBUZ_PAGE_SIZE&offset=$offset&app_id=$appId",
                headers = mapOf(
                    "X-App-Id" to appId,
                    "X-User-Auth-Token" to authToken,
                ),
            )
            val root = JSONObject(body)
            if (offset == 0) {
                title = root.optString("name").ifBlank { title }
                thumbnail = root.optJSONObject("image")?.optString("large")?.ifBlank { null }
            }
            val trackObj = root.optJSONObject("tracks")
            val items = trackObj?.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val trackTitle = item.optString("title")
                if (trackTitle.isBlank()) continue
                tracks.add(
                    ForeignTrack(
                        title = trackTitle,
                        artist = item.optJSONObject("performer")?.optString("name")?.ifBlank { null }
                            ?: item.optJSONObject("album")?.optJSONObject("artist")
                                ?.optString("name")?.ifBlank { null },
                        album = item.optJSONObject("album")?.optString("title")?.ifBlank { null },
                        durationMs = item.optLong("duration").takeIf { it > 0 }?.times(1000L),
                    ),
                )
            }
            offset += QOBUZ_PAGE_SIZE
            if (offset >= trackObj.optInt("total", offset)) break
        }

        return ResolvedImport(
            source = ImportSource.QOBUZ,
            sourcePlaylistId = id,
            title = title,
            thumbnailUrl = thumbnail,
            tracks = tracks,
        )
    }

    /** Qobuz playlist ids are numeric and always the last path segment. */
    internal fun extractQobuzPlaylistId(url: String): String? {
        val m = Pattern.compile("playlists?/(?:[^/?#]+/)*?(\\d{3,})").matcher(url)
        return if (m.find()) m.group(1) else null
    }

    private fun extractTidalPlaylistId(url: String): String? {
        // Tidal ids are UUIDs.
        val m = Pattern.compile("playlist/([a-f0-9\\-]{20,40})").matcher(url)
        return if (m.find()) m.group(1) else null
    }

    // ─── Deezer ───────────────────────────────────────────────────────────
    // URL pattern: https://www.deezer.com/{cc}/playlist/{numeric-id}
    // Deezer exposes a public JSON API: `https://api.deezer.com/playlist/{id}`.
    // Returns `{title, picture_xl, tracks: {data: [{title, artist: {name}}]}}`.
    private suspend fun fetchDeezerPlaylist(url: String): ResolvedImport {
        val id = extractDeezerPlaylistId(url) ?: error("Couldn't extract Deezer playlist id from URL")
        val json = fetchText("https://api.deezer.com/playlist/$id")
        val root = JSONObject(json)
        val title = root.optString("title").ifBlank { "Deezer Playlist" }
        val thumb = root.optString("picture_xl").ifBlank { null }
        val tracksArr = root.optJSONObject("tracks")?.optJSONArray("data") ?: return ResolvedImport(
            source = ImportSource.DEEZER,
            sourcePlaylistId = id,
            title = title,
            thumbnailUrl = thumb,
            tracks = emptyList(),
        )
        val tracks = (0 until tracksArr.length()).mapNotNull { i ->
            val obj = tracksArr.optJSONObject(i) ?: return@mapNotNull null
            val t = obj.optString("title").ifBlank { return@mapNotNull null }
            val a = obj.optJSONObject("artist")?.optString("name")?.ifBlank { null }
            ForeignTrack(title = t, artist = a)
        }
        return ResolvedImport(
            source = ImportSource.DEEZER,
            sourcePlaylistId = id,
            title = title,
            thumbnailUrl = thumb,
            tracks = tracks,
        )
    }

    private fun extractDeezerPlaylistId(url: String): String? {
        val m = Pattern.compile("playlist/(\\d+)").matcher(url)
        return if (m.find()) m.group(1) else null
    }

    // ─── Shared helpers ───────────────────────────────────────────────────
    private suspend fun fetchText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            // Spotify's embed page varies its markup by client; a desktop UA
            // reliably returns the __NEXT_DATA__ payload we parse.
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/json,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                // Surface auth problems in words the user can act on — a bare
                // "HTTP 401" in a toast tells them nothing.
                when (res.code) {
                    401, 403 -> error("Not authorised (HTTP ${res.code}) — the account or token may have expired")
                    404 -> error("Playlist not found (HTTP 404) — it may be private or the URL is wrong")
                    429 -> error("Rate limited by the service (HTTP 429) — try again in a minute")
                    else -> error("HTTP ${res.code} fetching $url")
                }
            }
            res.body?.string() ?: error("Empty response from $url")
        }
    }

    /** Pulls the raw JSON body out of a `<script id="...">…</script>` block. */
    internal fun extractScriptJson(html: String, scriptId: String): String? {
        val m = Pattern
            .compile("<script[^>]*id=\"$scriptId\"[^>]*>(.*?)</script>", Pattern.DOTALL)
            .matcher(html)
        return if (m.find()) m.group(1)?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    /** Convenience for Next.js pages (Spotify's embed). */
    private fun extractNextData(html: String): String? = extractScriptJson(html, "__NEXT_DATA__")

    private fun extractQuery(url: String, key: String): String? {
        val m = Pattern.compile("[?&]$key=([^&]+)").matcher(url)
        return if (m.find()) java.net.URLDecoder.decode(m.group(1), "UTF-8") else null
    }

    private fun unescapeJson(s: String): String =
        s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", " ")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&#x2F;", "/")

    private const val MAX_PARALLEL_SEARCHES = 6

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private const val TIDAL_API_BASE = "https://api.tidal.com/v1"
    private const val QOBUZ_API_BASE = "https://www.qobuz.com/api.json/0.2"

    private const val SPOTIFY_PAGE_SIZE = 100
    private const val TIDAL_PAGE_SIZE = 100
    private const val QOBUZ_PAGE_SIZE = 500

    // Hard ceilings so a pathological playlist can't spin the importer forever
    // (each track also costs one YouTube Music search downstream).
    private const val SPOTIFY_MAX_TRACKS = 2000
    private const val TIDAL_MAX_TRACKS = 2000
    private const val QOBUZ_MAX_TRACKS = 2000
}
