/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Playing a track from the signed-in user's own QQ Music account.
 * The shape is the same as every other source here: the playback layer hands over the metadata it
 * knows, this turns it into a playable stream, and the shared
 * [moe.rukamori.archivetune.audiosource.TitleMatch] gate decides whether what came back really is
 * the right track. What is unusual is that QQ Music is addressed by resource name rather than by
 * track, so the work is: find the catalogue entry, learn its resource id, ask for that resource at
 * the quality the user chose, and — only when the service answers with a protected container rather
 * than a plain file — decrypt it on device.
 * The account is the one the user signed in with. Nothing here reaches for another account's
 * entitlements, borrows a credential, or asks for a tier the account does not have: an unentitled
 * tier comes back empty from the service and this returns null so the resolver carries on down the
 * chain, which is how every other source behaves.
 * Failure is always null. A missing session, an empty catalogue result, a rejected metadata match,
 * an unentitled quality, a container whose key cannot be recovered — all of them mean "not this
 * source, try the next", never a partial stream.
 */



package moe.rukamori.archivetune.qqmusic

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
import moe.rukamori.archivetune.audiosource.TitleMatch
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.QqAudioQuality
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

internal object QqMusicAudioProvider {
    private const val TAG = "QqMusicAudio"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** How many catalogue hits to consider before giving up on a match. */
    private const val CANDIDATE_LIMIT = 6

    /**
     * Decrypted containers are cached and pruned oldest-first past this much, the way the Apple
     * source prunes its own: a lossless track is tens of megabytes, and replaying one should not
     * download and decrypt it again.
     */
    private const val CACHE_LIMIT_BYTES = 300L * 1024 * 1024

    /**
     * The budget for the whole attempt when a protected container has to be downloaded before it
     * can be decrypted. The catalogue and url-minter calls keep the ordinary provider budget inside
     * it; this only matters when the caller's own deadline allows a transfer that long, because a
     * child deadline never outlives its parent.
     */
    private const val CONTAINER_TRANSFER_MS = 120_000L

    /**
     * Generous read timeout, no overall cap: a lossless container is a large download and the
     * decrypt only starts once all of it has arrived.
     */
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

    /**
     * Resolves [title]/[artists] to a playable QQ Music stream.
     *
     * [trusted] is the per-song override: when the user has pinned this song to QQ Music by hand the
     * metadata gate is skipped for the catalogue hit, because their intent outranks it. Otherwise
     * every candidate is matched against [title]/[artists]/[album]/[durationMs], so the first hit
     * whose metadata actually agrees is the one that plays.
     */
    suspend fun resolveByMetadata(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        quality: QqAudioQuality,
        session: QqMusicSession,
        cacheDir: File,
        trusted: Boolean,
    ): DirectStream? =
        AudioSourceAttemptScope.withinSuspending(CONTAINER_TRANSFER_MS) {
            withContext(Dispatchers.IO) {
                if (title.isBlank()) return@withContext null
                val resolved =
                    AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
                        findSource(title, artists, album, durationMs, quality, session, trusted)
                    } ?: return@withContext null
                val (matched, source) = resolved
                val stream = playable(source, matched, cacheDir, trusted) ?: return@withContext null
                Timber.tag(TAG).i("QQ Music resolved \"%s\" as %s (%s)", title, matched.mid, stream.label)
                stream
            }
        }

    /**
     * The first catalogue hit that clears the metadata gate and that this account is served, with
     * the resource the url-minter named for it.
     *
     * A search hit already carries the title, artists, album, duration and resource id the gate and
     * the url-minter need, so the detail call is only spent on a hit that lacks one of them.
     */
    private suspend fun findSource(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        quality: QqAudioQuality,
        session: QqMusicSession,
        trusted: Boolean,
    ): Pair<QqTrack, QqStreamSource>? {
        val searchQuery =
            listOfNotNull(title, artists.firstOrNull())
                .joinToString(" ")
                .trim()
        val candidates = QqMusicApi.search(searchQuery, CANDIDATE_LIMIT, session)
        if (candidates.isEmpty()) {
            Timber.tag(TAG).d("QQ Music catalogue had no hit for \"%s\"", searchQuery)
            return null
        }
        for (candidate in candidates) {
            val complete =
                candidate.title != null && candidate.artist != null && candidate.durationMs != null &&
                    !candidate.mediaMid.isNullOrBlank()
            val detail = if (complete) null else QqMusicApi.detail(candidate.mid, session)
            val matched =
                candidate.copy(
                    title = candidate.title ?: detail?.title,
                    artist = candidate.artist ?: detail?.artist,
                    album = candidate.album ?: detail?.album,
                    durationMs = detail?.durationMs ?: candidate.durationMs,
                    mediaMid = detail?.mediaMid ?: candidate.mediaMid,
                )
            // The gate runs before the stream call because that call is the expensive one. It
            // reads only the matched* fields, so this probe never reaches the player.
            if (!accepted(title, artists, album, durationMs, matched, trusted)) continue
            val source =
                QqMusicApi.streamSource(session, matched.mid, matched.mediaMid, matched.songType, quality)
                    ?: continue
            return matched to source
        }
        return null
    }

    /** The shared metadata gate, with the override bypass the playback layer uses. */
    private fun accepted(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        candidate: QqTrack,
        trusted: Boolean,
    ): Boolean {
        if (trusted) return true
        val probe =
            DirectStream(
                uri = "",
                mimeType = "",
                codecs = "",
                contentLength = null,
                label = "",
                source = AudioSourceType.QQ,
                matchedTitle = candidate.title,
                matchedArtist = candidate.artist,
                matchedAlbum = candidate.album,
                matchedDurationMs = candidate.durationMs,
            )
        val match =
            TitleMatch.evaluate(
                wantedTitle = title,
                wantedArtists = artists,
                wantedAlbum = album,
                wantedDurationMs = durationMs,
                stream = probe,
            )
        if (!match.accepted) {
            Timber
                .tag(TAG)
                .i(
                    "QQ Music candidate %s rejected for \"%s\": %s (matched=\"%s\")",
                    candidate.mid,
                    title,
                    match.reason,
                    candidate.title ?: "?",
                )
        }
        return match.accepted
    }

    /**
     * Either hands the remote path straight to the player, or downloads a protected container,
     * decrypts it, and hands over the local file. A container already decrypted for the same
     * resource is reused without being fetched again.
     */
    private fun playable(
        source: QqStreamSource,
        matched: QqTrack,
        cacheDir: File,
        trusted: Boolean,
    ): DirectStream? {
        val extension = source.url.substringBefore('?').substringAfterLast('.', "")
        if (!source.encrypted) {
            val (mimeType, codecs) = plainMimeType(extension)
            return stream(source.url, mimeType, codecs, null, source, matched, trusted)
        }

        val directory = File(cacheDir, "qqmusic").apply { mkdirs() }
        val stem = cacheStem(source.url)
        cachedContainer(directory, stem)?.let { (file, container) ->
            file.setLastModified(System.currentTimeMillis())
            return stream(
                android.net.Uri.fromFile(file).toString(),
                container.mimeType,
                codec(container, readHead(file)),
                file.length(),
                source,
                matched,
                trusted,
            )
        }

        val downloaded = download(source.url) ?: return null
        val decrypted = QmcDecryptor.decrypt(downloaded, extension, source.ekey) ?: return null
        val file = cacheContainer(directory, stem, decrypted) ?: return null
        return stream(
            android.net.Uri.fromFile(file).toString(),
            decrypted.container.mimeType,
            codec(decrypted.container, decrypted.bytes),
            file.length(),
            source,
            matched,
            trusted,
        )
    }

    private fun stream(
        uri: String,
        mimeType: String,
        codecs: String,
        contentLength: Long?,
        source: QqStreamSource,
        matched: QqTrack,
        trusted: Boolean,
    ): DirectStream =
        DirectStream(
            uri = uri,
            mimeType = mimeType,
            codecs = codecs,
            contentLength = contentLength,
            label = "QQ Music ${source.tier}",
            source = AudioSourceType.QQ,
            matchedTitle = matched.title,
            matchedArtist = matched.artist,
            matchedAlbum = matched.album,
            matchedDurationMs = matched.durationMs,
            trustedDirectId = trusted,
        )

    private fun download(url: String): ByteArray? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("QQ Music container download failed: HTTP %d", response.code)
                    null
                } else {
                    response.body?.bytes()
                }
            }
        }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse { error ->
            Timber.tag(TAG).w(error, "QQ Music container download error")
            null
        }

    /**
     * The cache name for a resource. It comes from the resource name in the path — which already
     * names the track and the tier — and not from the whole URL, whose `vkey` changes on every
     * mint and would make every replay a miss.
     */
    private fun cacheStem(url: String): String {
        val resource = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return "qq_" + resource.filter { it.isLetterOrDigit() }.ifEmpty { url.hashCode().toString() }
    }

    private fun cachedContainer(
        directory: File,
        stem: String,
    ): Pair<File, QmcContainer>? {
        for (container in QmcContainer.entries) {
            val file = File(directory, "$stem.${container.extension}")
            if (file.isFile && file.length() > 0) return file to container
        }
        return null
    }

    private fun readHead(file: File): ByteArray =
        runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(256)
                val read = input.read(head).coerceAtLeast(0)
                head.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))

    /**
     * Writes the decrypted payload under [stem], so a replay of the same resource reuses it, and
     * prunes the directory oldest-first past [CACHE_LIMIT_BYTES]. The bytes go to a temporary file
     * first, so an interrupted write never leaves a truncated file that a replay would take as whole.
     */
    private fun cacheContainer(
        directory: File,
        stem: String,
        decrypted: QmcDecrypted,
    ): File? {
        val existing = directory.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        var total = existing.sumOf { it.length() } + decrypted.bytes.size
        for (stale in existing) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= stale.length()
            stale.delete()
        }
        val file = File(directory, "$stem.${decrypted.container.extension}")
        val partial = File(directory, "$stem.part")
        return runCatching {
            partial.writeBytes(decrypted.bytes)
            if (!partial.renameTo(file)) error("could not move the decrypted file into place")
            file
        }.getOrElse { error ->
            partial.delete()
            Timber.tag(TAG).w(error, "could not write the decrypted QQ Music file")
            null
        }
    }

    private fun codec(
        container: QmcContainer,
        bytes: ByteArray,
    ): String =
        when (container) {
            QmcContainer.FLAC -> "flac"
            QmcContainer.OGG -> oggCodec(bytes)
            QmcContainer.MP3 -> "mp3"
            QmcContainer.M4A -> "aac"
            QmcContainer.WAV -> "pcm"
        }

    /**
     * MIME type and codec hint for a plain (unencrypted) resource, taken from the extension the
     * url-minter named. Anything unexpected is served as MPEG audio, which is what the low tiers are.
     */
    private fun plainMimeType(extension: String): Pair<String, String> =
        when (extension.lowercase()) {
            "flac" -> "audio/flac" to "flac"
            "ogg" -> "audio/ogg" to "vorbis"
            "m4a", "mp4" -> "audio/mp4" to "aac"
            "wav" -> "audio/wav" to "pcm"
            else -> "audio/mpeg" to "mp3"
        }
}
