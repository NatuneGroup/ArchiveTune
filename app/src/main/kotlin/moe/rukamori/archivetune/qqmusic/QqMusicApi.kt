/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The QQ Music RPC surface this source needs: catalogue search, track detail, the Home page's
 * charts, and the call that mints a playable URL for the signed-in account.
 *
 * Every call is a single POST to `https://u.y.qq.com/cgi-bin/musicu.fcg` carrying a
 * `{comm, req_N: {module, method, param}}` envelope. `comm` identifies the caller; the module name
 * identifies the operation. This is the protocol QQ Music's own clients speak, not a published
 * API: Tencent's OpenAPI is enterprise-only, so a personal account is reached the same way the
 * official desktop and web clients reach it, with the account's own credentials.
 *
 * The url-minter is a `filename`-based call: the client does not name a track, it names the
 * *resource* — `<prefix><media_mid><extension>` — and gets back a relative path plus a CDN list.
 * The prefix selects the quality tier, so the tier the user picks in settings is what gets asked
 * for, and a tier the account is not entitled to comes back empty rather than being worked around.
 *
 * No request signature is needed here. The `sign=` parameter belongs to the separate `musics.fcg`
 * endpoint, whose signer is a JavaScript virtual machine; `musicu.fcg` authenticates with the
 * account ticket alone. What it does need is `g_tk`, which is derived from that ticket below.
 */

package moe.rukamori.archivetune.qqmusic

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.constants.QqAudioQuality
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * QQ's request sign: a 33-multiplier string hash held inside 31 bits.
 *
 * The published implementations differ in where they truncate — `(h << 5) + h + c` masked on
 * return, or the same expression masked every round — which is the same number either way, because
 * multiplication and addition commute with the modulus. Two initial values are in use on this
 * protocol and both are load-bearing: `0` for the QR poll token, and `5381` for `g_tk`.
 */
internal object QqMusicSign {
    private const val MASK = 0x7FFFFFFF

    /** The `ptqrlogin` poll token is this hash of the `qrsig` cookie. */
    fun ptqrToken(qrsig: String): Int = hash33(qrsig)

    /** The `g_tk` CSRF parameter is this hash of the account ticket. */
    fun gTk(musicKey: String): Int = hash33(musicKey, 5381)

    fun hash33(
        value: String,
        initial: Int = 0,
    ): Int {
        var hash = initial
        for (character in value) {
            hash = (hash shl 5) + hash + character.code
            hash = hash and MASK
        }
        return hash
    }
}

/**
 * One quality tier of one track, as the url-minter addresses it.
 *
 * [prefix] and [extension] together are the resource name; [tier] is only a label for the media
 * info panel.
 */
internal data class QqAudioFormat(
    val prefix: String,
    val extension: String,
    val tier: String,
)

/**
 * The quality tiers this source can ask for, in both the plain and the encrypted container.
 *
 * QQ Music names the same tier twice: the plain form (`F000`/`.flac`) and an encrypted form whose
 * prefix inserts an `M` (`F0M0`/`.mflac`). The plain form is requested first, because a stream the
 * account is served unencrypted needs no local work at all; the encrypted form is the fallback for
 * the tracks — mostly lossless ones — where the service will only hand out a protected container.
 */
internal object QqAudioQualityFormats {
    /** Every extension that names a container which must be decrypted before it can be played. */
    private val encryptedExtensions =
        setOf(
            ".mflac", ".mflac0", ".mflac1", ".mflach",
            ".mgg", ".mgg0", ".mgg1", ".mggl",
            ".mmp4", ".mnac",
        )

    fun plain(quality: QqAudioQuality): QqAudioFormat =
        when (quality) {
            QqAudioQuality.LOSSLESS -> QqAudioFormat("F000", ".flac", "FLAC")
            QqAudioQuality.HIGH -> QqAudioFormat("M800", ".mp3", "320 kbps")
            QqAudioQuality.STANDARD -> QqAudioFormat("M500", ".mp3", "128 kbps")
        }

    fun encrypted(quality: QqAudioQuality): QqAudioFormat =
        when (quality) {
            QqAudioQuality.LOSSLESS -> QqAudioFormat("F0M0", ".mflac", "FLAC")
            QqAudioQuality.HIGH -> QqAudioFormat("O8M0", ".mgg", "320 kbps")
            QqAudioQuality.STANDARD -> QqAudioFormat("O4M0", ".mgg", "128 kbps")
        }

    /** True when [url]'s path names an encrypted container. */
    fun isEncryptedUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return encryptedExtensions.any { path.endsWith(it) }
    }
}

/** A catalogue hit, with whatever metadata the catalogue supplied for it. */
internal data class QqTrack(
    val mid: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mediaMid: String? = null,
    val coverUrl: String? = null,
)

/**
 * One of the charts the Home page shows: its title, the group it is published under, and the songs
 * it currently ranks.
 *
 * [tracks] starts as the three songs the chart listing itself carries and is replaced by the
 * chart's own ranked list when that can be fetched, so a chart is never dropped for a failed second
 * request when it already arrived with something to show.
 */
internal data class QqHomeChart(
    val id: Int,
    val title: String,
    val label: String?,
    val tracks: List<QqTrack>,
)

/** A playable resource: the CDN path, and whether its bytes are a protected container. */
internal data class QqStreamSource(
    val url: String,
    val encrypted: Boolean,
    val ekey: String?,
    val tier: String,
)

internal object QqMusicApi {
    private const val TAG = "QqMusicApi"

    private const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val REFERER = "https://y.qq.com/"

    /**
     * The key the single call in an envelope is sent under. The server echoes the request's key
     * back, so every reply is looked up under this and under the other spelling clients use.
     */
    private const val ENVELOPE_KEY = "req_0"

    /** `search_type: 0` is a song search. */
    private const val SEARCH_TYPE_SONG = 0

    /** How many charts the Home page shows. Each one costs a second request for its songs. */
    private const val HOME_SECTION_LIMIT = 6

    /** How many songs of a chart its section shows. */
    private const val HOME_SECTION_TRACKS = 10

    /**
     * The album art naming rule QQ Music's own clients use: `T002` is an album, `R300x300` the size
     * segment, and the media id is the file name.
     */
    private const val ALBUM_COVER_URL_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
    private const val ALBUM_COVER_URL_SUFFIX = ".jpg"

    /**
     * The one CDN the web client falls back to when the response's `sip` list is empty. It is a
     * Tencent host, not a third-party mirror.
     */
    private const val FALLBACK_CDN = "https://isure.stream.qqmusic.qq.com/"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Catalogue search. Returns every song-shaped object in the response, in document order,
     * deduplicated by id.
     *
     * The reply is read from the documented path (`data.body.song.list`) first, and only if the
     * envelope is a shape this file has not seen does it fall back to walking the tree for
     * song-shaped objects (`songmid`, or `mid` beside a `singer`/`interval`). Album and artist
     * objects carry a bare `mid`, so they are skipped either way. The playback layer's own metadata
     * gate is what decides whether a hit is the wanted track.
     */
    suspend fun search(
        query: String,
        limit: Int,
        session: QqMusicSession?,
    ): List<QqTrack> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            val response = post(searchBody(query, limit, session), session) ?: return@withContext emptyList()
            val listed =
                businessData(response)
                    ?.get("body")
                    ?.jsonObjectOrNull()
                    ?.get("song")
                    ?.jsonObjectOrNull()
                    ?.get("list")
                    ?.jsonArrayOrNull()
                    ?.mapNotNull { it.jsonObjectOrNull() }
                    ?.mapNotNull { song ->
                        val mid = song["mid"]?.stringOrNull() ?: song["songmid"]?.stringOrNull()
                        mid?.let { parseTrack(song, it) }
                    }
                    .orEmpty()
            val tracks = listed.ifEmpty { collectSongs(response) }
            tracks.filter { it.title != null }.take(limit)
        }

    /**
     * Whether the account can still reach the catalogue.
     *
     * The reply's own status is what is inspected, not how many hits came back, so a query that
     * matches nothing is not mistaken for a dead ticket — only a refused or unparsable call is. The
     * account's expiry is reported by the catalogue as a non-zero status, which is exactly what this
     * surfaces.
     */
    suspend fun reachable(
        session: QqMusicSession,
        query: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext false
            val response = post(searchBody(query, 1, session), session)
            val sub = response?.let(::subResponse)
            sub?.get("code")?.stringOrNull()?.toIntOrNull() == 0
        }

    private fun searchBody(
        query: String,
        limit: Int,
        session: QqMusicSession?,
    ): JsonObject =
        buildJsonObject {
            put("comm", catalogComm(session))
            putJsonObject(ENVELOPE_KEY) {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                putJsonObject("param") {
                    put("grp", 1)
                    put("num_per_page", limit.coerceIn(1, 50))
                    put("page_num", 1)
                    put("query", query)
                    put("search_type", SEARCH_TYPE_SONG)
                }
            }
        }

    /**
     * The chart listing. `GetAll` takes no parameters and answers with every chart the service
     * publishes, grouped into families.
     */
    private fun toplistBody(session: QqMusicSession?): JsonObject =
        buildJsonObject {
            put("comm", catalogComm(session))
            putJsonObject(ENVELOPE_KEY) {
                put("module", "music.musicToplist.Toplist")
                put("method", "GetAll")
                putJsonObject("param") {}
            }
        }

    /**
     * One chart's songs. `GetDetail` is addressed by `topId` and pages by `offset`/`num`; tags are
     * not asked for, because nothing on the page shows them.
     */
    private fun toplistDetailBody(
        session: QqMusicSession?,
        topId: Int,
    ): JsonObject =
        buildJsonObject {
            put("comm", catalogComm(session))
            putJsonObject(ENVELOPE_KEY) {
                put("module", "music.musicToplist.Toplist")
                put("method", "GetDetail")
                putJsonObject("param") {
                    put("topId", topId)
                    put("offset", 0)
                    put("num", HOME_SECTION_TRACKS)
                    put("withTags", false)
                }
            }
        }

    /**
     * The Home page's sections: the charts QQ Music publishes, each with the songs it currently
     * ranks.
     *
     * Two calls for the page, not two per section. The chart listing (`music.musicToplist.Toplist`
     * / `GetAll`) names the charts, groups them, and carries their top three songs; each chart's
     * `GetDetail` call then fills its section out to [HOME_SECTION_TRACKS] songs. The detail calls
     * run together rather than in turn, so the page costs one round trip plus the slowest chart
     * instead of the sum of them.
     *
     * Null means the page could not be fetched — no answer, or an answer with no chart listing in
     * it — which the caller reports as a failure worth retrying. An empty list means it was fetched
     * and held nothing readable, which is a different thing to say. A chart whose own songs cannot
     * be fetched is neither: it keeps the three the listing carried. Nothing here throws.
     */
    suspend fun home(session: QqMusicSession): List<QqHomeChart>? =
        withContext(Dispatchers.IO) {
            val listing = post(toplistBody(session), session) ?: return@withContext null
            val charts = parseHomeCharts(listing)?.take(HOME_SECTION_LIMIT) ?: return@withContext null
            if (charts.isEmpty()) return@withContext emptyList()
            coroutineScope {
                charts
                    .map { chart -> async { chart.withSongs(session) } }
                    .awaitAll()
                    .filter { it.tracks.isNotEmpty() }
            }
        }

    /**
     * [this] chart with its songs: the chart's own ranked list when the detail call answers, and the
     * previews the listing already carried when it does not.
     */
    private suspend fun QqHomeChart.withSongs(session: QqMusicSession): QqHomeChart {
        val songs =
            post(toplistDetailBody(session, id), session)
                ?.let(::parseChartSongs)
                .orEmpty()
        return copy(tracks = songs.ifEmpty { tracks })
    }

    /**
     * The charts a `GetAll` reply lists, in the order the service publishes them.
     *
     * Null means the reply carries no chart listing at all — a refused call, an error envelope, or
     * a shape this build does not know — which is a failed fetch rather than an empty one. An empty
     * list means the listing was there and nothing in it could be read. Below the listing every
     * level is optional: a group with no charts, a chart with no id or title, and a song with no id
     * or title are each skipped, so one unfamiliar entry never costs the rest of the page.
     */
    internal fun parseHomeCharts(response: JsonObject): List<QqHomeChart>? {
        val groups = businessData(response)?.get("group")?.jsonArrayOrNull() ?: return null
        return groups
            .mapNotNull { it.jsonObjectOrNull() }
            .flatMap { group ->
                val groupName = group["groupName"]?.stringOrNull()
                group["toplist"]
                    ?.jsonArrayOrNull()
                    .orEmpty()
                    .mapNotNull { chart -> chart.jsonObjectOrNull()?.let { parseChart(it, groupName) } }
            }
    }

    /** One listed chart, or null when the entry names no id or title to address it by. */
    private fun parseChart(
        chart: JsonObject,
        groupName: String?,
    ): QqHomeChart? {
        val id = chart["topId"]?.stringOrNull()?.toIntOrNull() ?: return null
        val title = chart["title"]?.stringOrNull() ?: return null
        val previews =
            chart["song"]
                ?.jsonArrayOrNull()
                .orEmpty()
                .mapNotNull { preview ->
                    val song = preview.jsonObjectOrNull() ?: return@mapNotNull null
                    val songId = song["songId"]?.stringOrNull() ?: return@mapNotNull null
                    parseTrack(song, songId).takeIf { it.title != null }
                }
        return QqHomeChart(id = id, title = title, label = groupName, tracks = previews)
    }

    /**
     * The songs a `GetDetail` reply ranks, in chart order. They are the catalogue's own song
     * objects, so they are read by the same parser a search hit is.
     */
    internal fun parseChartSongs(response: JsonObject): List<QqTrack> =
        businessData(response)
            ?.get("songInfoList")
            ?.jsonArrayOrNull()
            .orEmpty()
            .mapNotNull { song ->
                val entry = song.jsonObjectOrNull() ?: return@mapNotNull null
                val mid = entry["mid"]?.stringOrNull() ?: return@mapNotNull null
                parseTrack(entry, mid).takeIf { it.title != null }
            }

    /** Track detail: the authoritative title/artists/album/duration and the resource id. */
    suspend fun detail(
        mid: String,
        session: QqMusicSession?,
    ): QqTrack? =
        withContext(Dispatchers.IO) {
            if (mid.isBlank()) return@withContext null
            val body =
                buildJsonObject {
                    put("comm", catalogComm(session))
                    putJsonObject(ENVELOPE_KEY) {
                        put("module", "music.pf_song_detail_svr")
                        put("method", "get_song_detail_yqq")
                        putJsonObject("param") { put("song_mid", mid) }
                    }
                }
            val response = post(body, session) ?: return@withContext null
            val trackInfo =
                businessData(response)
                    ?.get("track_info")
                    ?.jsonObjectOrNull()
            if (trackInfo != null) parseTrack(trackInfo, mid) else collectSongs(response).firstOrNull()
        }

    /**
     * The playable resource for [mid], or null when this account is not served one.
     *
     * The plain container is asked for first. Only if the service refuses that does the encrypted
     * call happen, with the encrypted prefix and `songtype: 1` — the combination that makes the
     * service disclose the `ekey` alongside the path. A refusal at both tiers is reported as "not
     * available to this account" and the resolver moves on.
     */
    suspend fun streamSource(
        session: QqMusicSession,
        mid: String,
        mediaMid: String?,
        quality: QqAudioQuality,
    ): QqStreamSource? =
        withContext(Dispatchers.IO) {
            mintUrl(session, mid, mediaMid, QqAudioQualityFormats.plain(quality), encrypted = false)
                ?: mintUrl(session, mid, mediaMid, QqAudioQualityFormats.encrypted(quality), encrypted = true)
        }

    /**
     * Mints a URL for one resource name.
     *
     * Tries the current module (`music.vkey.GetVkey` / `UrlGetVkey`) and, if the service answers
     * without a path, the older but still working `vkey.GetVkeyServer` / `CgiGetVkey`. The
     * encrypted tier uses `music.vkey.GetEVkey` / `CgiGetEVkey`, which is the only call that
     * returns the `ekey`; asking it for anything but an encrypted name, or with `songtype: 0`,
     * yields an error instead of a key.
     */
    private fun mintUrl(
        session: QqMusicSession,
        mid: String,
        mediaMid: String?,
        format: QqAudioFormat,
        encrypted: Boolean,
    ): QqStreamSource? {
        // The resource name doubles the mid when the catalogue exposes no separate resource id.
        val resourceId = mediaMid?.takeIf { it.isNotBlank() } ?: "$mid$mid"
        val filename = "${format.prefix}$resourceId${format.extension}"

        val attempts =
            if (encrypted) {
                listOf("music.vkey.GetEVkey" to "CgiGetEVkey")
            } else {
                listOf(
                    "music.vkey.GetVkey" to "UrlGetVkey",
                    "vkey.GetVkeyServer" to "CgiGetVkey",
                )
            }
        for ((module, method) in attempts) {
            val body =
                buildJsonObject {
                    put("comm", authComm(session))
                    putJsonObject(ENVELOPE_KEY) {
                        put("module", module)
                        put("method", method)
                        putJsonObject("param") {
                            put("uin", session.uin)
                            put("guid", guid())
                            putJsonArray("songmid") { add(mid) }
                            putJsonArray("filename") { add(filename) }
                            if (encrypted) {
                                putJsonArray("songtype") { add(1) }
                                put("ctx", 1)
                            } else {
                                putJsonArray("songtype") { add(0) }
                                put("ctx", 0)
                            }
                            // The legacy minter names these explicitly; the current one ignores
                            // them, and sending them either way keeps one param builder for both.
                            put("loginflag", 1)
                            put("platform", "20")
                        }
                    }
                }
            val data = post(body, session)?.let(::businessData) ?: continue
            val entry =
                data["midurlinfo"]
                    ?.jsonArrayOrNull()
                    ?.firstOrNull()
                    ?.jsonObjectOrNull()
                    ?: continue
            val purl = entry["purl"]?.stringOrNull().orEmpty()
            if (!purl.startsWith("http")) {
                Timber
                    .tag(TAG)
                    .i(
                        "%s gave no path for %s (result=%s); this account is not served that tier",
                        module,
                        filename,
                        entry["result"]?.stringOrNull() ?: "?",
                    )
                continue
            }
            return QqStreamSource(
                url = absoluteUrl(data, purl),
                encrypted = encrypted || QqAudioQualityFormats.isEncryptedUrl(purl),
                ekey = entry["ekey"]?.stringOrNull(),
                tier = format.tier,
            )
        }
        return null
    }

    /**
     * The CDN prefix plus the response's relative path.
     *
     * A `purl` that is already absolute is used as it stands. Otherwise the CDN list is searched for
     * an `https` entry, skipping the `ws` hosts the streaming clients avoid. The list is usually
     * plain `http`, and this app's network security config refuses cleartext, so when nothing
     * usable is left the request goes to Tencent's own `isure` host — the same fallback the clients
     * use when the list is empty, and the one that is reachable over TLS. The `vkey` in the path is
     * what authorises the fetch, not the host, so the substitution does not change what is served.
     */
    private fun absoluteUrl(
        data: JsonObject,
        purl: String,
    ): String {
        if (purl.startsWith("http")) return purl
        val sips =
            data["sip"]
                ?.jsonArrayOrNull()
                ?.mapNotNull { it.stringOrNull() }
                .orEmpty()
        val secure = sips.firstOrNull { it.startsWith("https://") && !it.startsWith("https://ws") }
        val prefix = (secure ?: FALLBACK_CDN).let { if (it.endsWith("/")) it else "$it/" }
        return prefix + purl.removePrefix("/")
    }

    /** The sub-response, under whichever envelope key the server echoed back. */
    private fun subResponse(response: JsonObject): JsonObject? =
        response[ENVELOPE_KEY]?.jsonObjectOrNull() ?: response["req"]?.jsonObjectOrNull()

    /** The business payload of a call: the sub-response's `data`. */
    private fun businessData(response: JsonObject): JsonObject? =
        subResponse(response)?.get("data")?.jsonObjectOrNull()

    /**
     * The desktop identity block, which is the one documented profile that carries the account
     * without also needing an Android device session or the web client's page context.
     */
    private fun catalogComm(session: QqMusicSession?): JsonObject =
        buildJsonObject {
            put("ct", 19)
            put("cv", 2201)
            put("chid", "0")
            session?.let {
                put("uin", it.uin)
                put("g_tk", it.gTk)
                put("guid", guid().uppercase())
            }
        }

    /**
     * The reduced block the url-minter is called with in practice: the ticket travels as `authst`,
     * which is how the song-URL call is documented by the maintained clients.
     */
    private fun authComm(session: QqMusicSession): JsonObject =
        buildJsonObject {
            put("uin", session.uin)
            put("format", "json")
            put("ct", 19)
            put("cv", 0)
            put("authst", session.musicKey)
        }

    private fun post(
        body: JsonObject,
        session: QqMusicSession?,
    ): JsonObject? {
        val request =
            Request
                .Builder()
                .url(ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .apply { session?.let { header("Cookie", it.cookieHeader) } }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> {
                        Timber.tag(TAG).w("musicu.fcg answered HTTP %d", response.code)
                        null
                    }

                    text.isBlank() -> {
                        Timber.tag(TAG).w("musicu.fcg answered %d with an empty body", response.code)
                        null
                    }

                    else ->
                        runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { error ->
                            Timber.tag(TAG).w(error, "musicu.fcg body was not a JSON object")
                            null
                        }
                }
            }
        } catch (error: IOException) {
            error.rethrowIfAudioSourceCancelled()
            Timber.tag(TAG).w(error, "musicu.fcg request failed")
            null
        }
    }

    private fun guid(): String = UUID.randomUUID().toString().replace("-", "")

    /** Every song-shaped object in [root], in document order, deduplicated by id. */
    private fun collectSongs(root: JsonElement): List<QqTrack> {
        val found = LinkedHashMap<String, QqTrack>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val mid = element["songmid"]?.stringOrNull() ?: songMidOf(element)
                    if (mid != null && !found.containsKey(mid)) {
                        found[mid] = parseTrack(element, mid)
                    }
                    for (child in element.values) walk(child)
                }

                is JsonArray -> element.forEach { child -> walk(child) }

                else -> Unit
            }
        }
        walk(root)
        return found.values.filter { it.title != null }.toList()
    }

    /**
     * A bare `mid` only counts as a song when the object also carries a field no album or artist
     * object has, so album and singer entries beside a track are not mistaken for tracks.
     */
    private fun songMidOf(element: JsonObject): String? {
        val songOnly = element["singer"] != null || element["interval"] != null
        return if (songOnly) element["mid"]?.stringOrNull() else null
    }

    private fun parseTrack(
        element: JsonObject,
        mid: String,
    ): QqTrack {
        val file = element["file"]?.jsonObjectOrNull()
        val seconds = element["interval"]?.stringOrNull()?.toLongOrNull()
        return QqTrack(
            mid = element["mid"]?.stringOrNull() ?: element["songmid"]?.stringOrNull() ?: mid,
            title =
                element["title"]?.stringOrNull()
                    ?: element["songname"]?.stringOrNull()
                    ?: element["name"]?.stringOrNull(),
            // A search hit spells the artist as a `singer` list; a chart's preview song spells it
            // as one already-joined `singerName` string.
            artist =
                element["singer"]?.artistsOrNull()
                    ?: element["singerName"]?.stringOrNull()
                    ?: element["singername"]?.stringOrNull(),
            album =
                element["album"]?.jsonObjectOrNull()?.get("name")?.stringOrNull()
                    ?: element["albumname"]?.stringOrNull(),
            durationMs = seconds?.takeIf { it > 0 }?.times(1000L),
            mediaMid =
                file?.get("media_mid")?.stringOrNull()
                    ?: element["media_mid"]?.stringOrNull(),
            coverUrl = coverUrl(element),
        )
    }

    /**
     * A track's album art, when the response names one.
     *
     * A chart preview carries a ready URL in `cover`; the catalogue's own song objects carry an
     * album media id instead, which QQ Music's clients turn into an image URL by the `photo_new`
     * naming rule. Neither is required: a missing id, or a `cover` that is not a URL, leaves the
     * track without artwork rather than with a broken address.
     */
    private fun coverUrl(element: JsonObject): String? {
        element["cover"]?.stringOrNull()?.takeIf { it.startsWith("http") }?.let { return it }
        val album = element["album"]?.jsonObjectOrNull()
        val albumId =
            album?.get("mid")?.stringOrNull()
                ?: album?.get("pmid")?.stringOrNull()
                ?: element["albummid"]?.stringOrNull()
                ?: element["albumMid"]?.stringOrNull()
                ?: return null
        return "$ALBUM_COVER_URL_PREFIX$albumId$ALBUM_COVER_URL_SUFFIX"
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    /** `singer: [{name: "..."}]` joined for the metadata gate, which compares one artist string. */
    private fun JsonElement.artistsOrNull(): String? =
        jsonArrayOrNull()
            ?.mapNotNull { it.jsonObjectOrNull()?.get("name")?.stringOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
}
