/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Echo Music resolve method for Spotify, ported from echo-spotify-extension
 * (SpotifyExtension.loadStreamableMedia / oggStream): desktop token ->
 * extended-metadata audio file list -> storage-resolve CDN urls -> PlayPlay
 * audio key. The caller hands the returned stream to EchoDecryptingDataSource,
 * which AES-CTR decrypts the CDN bytes so the extractor sees plain Ogg Vorbis.
 */

package moe.rukamori.archivetune.spotify.echo

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.utils.withAppProxy

object SpotifyEchoResolver {
    private const val TAG = "SpotifyEchoResolver"

    const val SCHEME = "echo-spotify"

    private const val EXTENDED_METADATA_URL = "https://spclient.wg.spotify.com/extended-metadata/v0/extended-metadata"
    private const val STORAGE_RESOLVE_URL = "https://spclient.wg.spotify.com/storage-resolve/v2/files/audio/interactive"
    private const val PLAYPLAY_KEY_URL = "https://spclient.wg.spotify.com/playplay/v1/key"

    private val PROTOBUF_MEDIA_TYPE = "application/x-protobuf".toMediaType()

    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var cachedDeviceId: String? = null

    private val trackCache = java.util.concurrent.ConcurrentHashMap<String, ResolvedEchoStream>()

    private const val CACHE_TTL_MS = 10L * 60 * 1000

    class EchoFile(
        val fileIdHex: String,
        val format: Int,
        val formatName: String,
    )

    class ResolvedEchoStream(
        val trackUri: String,
        val files: List<EchoFile>,
        val chosenFile: EchoFile,
        val cdnUrls: List<String>,
        val keyHex: String,
        val durationMs: Long?,
        val resolvedAtMs: Long,
    )

    fun deviceId(): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val id = bytes.joinToString("") { "%02x".format(it) }
            cachedDeviceId = id
            return id
        }
    }

    fun streamUriFor(stream: ResolvedEchoStream): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority("audio")
            .appendPath(stream.chosenFile.fileIdHex)
            .appendQueryParameter("url", stream.cdnUrls.joinToString("|"))
            .appendQueryParameter("key", stream.keyHex)
            .appendQueryParameter("format", stream.chosenFile.format.toString())
            .build()

    fun parseStreamUri(uri: Uri): EchoStreamRef? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val urls = uri.getQueryParameter("url")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        if (urls.isEmpty()) return null
        val keyHex = uri.getQueryParameter("key")?.takeIf { it.length == 32 } ?: return null
        val format = uri.getQueryParameter("format")?.toIntOrNull() ?: return null
        val fileId = uri.lastPathSegment ?: return null
        return EchoStreamRef(urls, keyHex, format, fileId)
    }

    class EchoStreamRef(
        val cdnUrls: List<String>,
        val keyHex: String,
        val format: Int,
        val fileIdHex: String,
    )

    fun invalidateTrack(trackUri: String) {
        trackCache.remove(trackUri)
    }

    fun clearCache() {
        trackCache.clear()
    }

    suspend fun resolve(
        spDc: String,
        trackId: String,
        durationMs: Long?,
    ): ResolvedEchoStream? {
        val trackUri = if (trackId.startsWith("spotify:track:")) trackId else "spotify:track:$trackId"
        trackCache[trackUri]?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAtMs < CACHE_TTL_MS) return cached
            trackCache.remove(trackUri)
        }
        val token = SpotifyDesktopAuth.desktopToken(spDc) ?: run {
            Timber.tag(TAG).w("Echo resolve: no desktop token (device authorization failed)")
            return null
        }
        val clientToken = SpotifyDesktopAuth.ensureClientToken(spDc)
        val files = fetchAudioFiles(token, clientToken, trackUri) ?: return null
        if (files.isEmpty()) {
            Timber.tag(TAG).w("Echo resolve: track %s exposed no audio files", trackUri)
            return null
        }
        val chosen = pickFile(files)
        val cdnUrls = resolveStorageCdn(token, clientToken, chosen) ?: return null
        val keyHex = fetchPlayPlayKey(token, clientToken, chosen.fileIdHex) ?: return null
        val stream = ResolvedEchoStream(trackUri, files, chosen, cdnUrls, keyHex, durationMs, System.currentTimeMillis())
        trackCache[trackUri] = stream
        return stream
    }

    fun pickFile(files: List<EchoFile>): EchoFile {
        val preferredOrder =
            listOf(
                SpotifyEchoProto.FORMAT_OGG_VORBIS_320,
                SpotifyEchoProto.FORMAT_FLAC_FLAC,
                SpotifyEchoProto.FORMAT_OGG_VORBIS_160,
                SpotifyEchoProto.FORMAT_FLAC_FLAC_24BIT,
                SpotifyEchoProto.FORMAT_OGG_VORBIS_96,
            )
        for (format in preferredOrder) {
            files.firstOrNull { it.format == format }?.let { return it }
        }
        return files.first()
    }

    fun formatLabel(format: Int): String =
        when (format) {
            SpotifyEchoProto.FORMAT_OGG_VORBIS_96 -> "Vorbis 96"
            SpotifyEchoProto.FORMAT_OGG_VORBIS_160 -> "Vorbis 160"
            SpotifyEchoProto.FORMAT_OGG_VORBIS_320 -> "Vorbis 320"
            SpotifyEchoProto.FORMAT_FLAC_FLAC -> "FLAC"
            SpotifyEchoProto.FORMAT_FLAC_FLAC_24BIT -> "FLAC 24-bit"
            else -> "Format $format"
        }

    fun isFlac(format: Int): Boolean =
        format == SpotifyEchoProto.FORMAT_FLAC_FLAC || format == SpotifyEchoProto.FORMAT_FLAC_FLAC_24BIT

    fun bitrateFor(format: Int): Int? =
        when (format) {
            SpotifyEchoProto.FORMAT_OGG_VORBIS_96 -> 96_000
            SpotifyEchoProto.FORMAT_OGG_VORBIS_160 -> 160_000
            SpotifyEchoProto.FORMAT_OGG_VORBIS_320 -> 320_000
            else -> null
        }

    private suspend fun fetchAudioFiles(
        token: String,
        clientToken: String?,
        trackUri: String,
    ): List<EchoFile>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestBytes = SpotifyEchoProto.buildExtendedMetadataRequest(
                    trackUri,
                    listOf(SpotifyEchoProto.EXTENSION_KIND_TRACK_V4, SpotifyEchoProto.EXTENSION_KIND_AUDIO_FILES),
                )
                val request =
                    Request
                        .Builder()
                        .url(EXTENDED_METADATA_URL)
                        .header("Authorization", "Bearer $token")
                        .apply { clientToken?.let { header("client-token", it) } }
                        .header("Accept", "application/x-protobuf")
                        .header("Content-Type", "application/x-protobuf")
                        .header("User-Agent", SpotifyDesktopAuth.userAgent())
                        .header("App-Platform", "Win32_x86_64")
                        .header("spotify-app-version", SpotifyDesktopAuth.appVersion())
                        .header("Accept-Language", "en")
                        .post(requestBytes.toRequestBody(PROTOBUF_MEDIA_TYPE))
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w("Extended metadata failed: HTTP %d", response.code)
                        return@use null
                    }
                    val payloads = SpotifyEchoProto.parseBatchedExtensionResponse(response.body?.bytes() ?: return@use null)
                    var trackData: SpotifyEchoProto.TrackData? = null
                    var audioFiles: List<SpotifyEchoProto.AudioFileEntry> = emptyList()
                    for (payload in payloads) {
                        when (payload.kind) {
                            SpotifyEchoProto.EXTENSION_KIND_TRACK_V4 -> trackData = SpotifyEchoProto.parseTrack(payload.data)
                            SpotifyEchoProto.EXTENSION_KIND_AUDIO_FILES -> audioFiles = SpotifyEchoProto.parseAudioFilesExtension(payload.data)
                        }
                    }
                    if (trackData == null) return@use null
                    val needsRelinking = trackData.restrictionCount > 0 || trackData.files.isEmpty()
                    val entries =
                        when {
                            needsRelinking && trackData.alternativeFiles.isNotEmpty() -> trackData.alternativeFiles
                            audioFiles.isNotEmpty() -> audioFiles
                            trackData.files.isNotEmpty() -> trackData.files
                            else -> trackData.alternativeFiles
                        }
                    entries
                        .filter { it.fileIdHex.length == 40 && it.format >= 0 }
                        .map { EchoFile(it.fileIdHex, it.format, formatName(it.format)) }
                }
            }.getOrNull()
        }

    private fun formatName(format: Int): String =
        when (format) {
            SpotifyEchoProto.FORMAT_OGG_VORBIS_96 -> "OGG_VORBIS_96"
            SpotifyEchoProto.FORMAT_OGG_VORBIS_160 -> "OGG_VORBIS_160"
            SpotifyEchoProto.FORMAT_OGG_VORBIS_320 -> "OGG_VORBIS_320"
            SpotifyEchoProto.FORMAT_FLAC_FLAC -> "FLAC_FLAC"
            SpotifyEchoProto.FORMAT_FLAC_FLAC_24BIT -> "FLAC_FLAC_24BIT"
            else -> "FORMAT_$format"
        }

    private suspend fun resolveStorageCdn(
        token: String,
        clientToken: String?,
        file: EchoFile,
    ): List<String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$STORAGE_RESOLVE_URL/${file.format}/${file.fileIdHex}?version=10000000&product=9&platform=39&alt=json"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .apply { clientToken?.let { header("client-token", it) } }
                        .header("Accept", "application/json")
                        .header("User-Agent", SpotifyDesktopAuth.userAgent())
                        .header("App-Platform", "Win32_x86_64")
                        .header("spotify-app-version", SpotifyDesktopAuth.appVersion())
                        .header("Accept-Language", "en")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w("Storage resolve failed: HTTP %d for %s", response.code, file.fileIdHex)
                        return@use null
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val urls = json.optJSONArray("cdnurl") ?: return@use null
                    val out = mutableListOf<String>()
                    for (i in 0 until urls.length()) {
                        val cdnUrl = urls.optString(i)
                        if (cdnUrlIsSafe(cdnUrl)) out += cdnUrl
                    }
                    out.distinct()
                }
            }.getOrNull()
        }

    private fun cdnUrlIsSafe(url: String): Boolean =
        runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)

    private suspend fun fetchPlayPlayKey(
        token: String,
        clientToken: String?,
        fileIdHex: String,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url("$PLAYPLAY_KEY_URL/$fileIdHex")
                        .header("Authorization", "Bearer $token")
                        .apply { clientToken?.let { header("client-token", it) } }
                        .header("Accept", "application/x-protobuf")
                        .header("User-Agent", SpotifyDesktopAuth.userAgent())
                        .header("App-Platform", "Win32_x86_64")
                        .header("spotify-app-version", SpotifyDesktopAuth.appVersion())
                        .header("Accept-Language", "en")
                        .post(SpotifyPlayPlayLicenseRequest.encode().toRequestBody(PROTOBUF_MEDIA_TYPE))
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w("PlayPlay key request failed: HTTP %d for %s", response.code, fileIdHex)
                        return@use null
                    }
                    val body = response.body?.bytes() ?: return@use null
                    val obfuscated = SpotifyPlayPlayRecordDecoder.parseObfuscatedKey(body)
                    val key = SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(obfuscated)
                    key.joinToString("") { "%02x".format(it) }
                }
            }.getOrNull()
        }
}
