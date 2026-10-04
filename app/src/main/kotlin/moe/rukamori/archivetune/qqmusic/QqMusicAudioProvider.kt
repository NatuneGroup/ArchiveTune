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
 * Playing a track from the signed-in user's own QQ Music account.
 *
 * The shape is the same as every other source here: the playback layer hands over the metadata it
 * knows, this turns it into a playable stream, and the shared
 * [moe.rukamori.archivetune.audiosource.TitleMatch] gate decides whether what came back really is
 * the right track. What is unusual is that QQ Music is addressed by resource name rather than by
 * track, so the work is: find the catalogue entry, learn its resource id, ask for that resource at
 * the quality the user chose, and — only when the service answers with a protected container rather
 * than a plain file — decrypt it on device.
 *
 * The account is the one the user signed in with. Nothing here reaches for another account's
 * entitlements, borrows a credential, or asks for a tier the account does not have: an unentitled
 * tier comes back empty from the service and this returns null so the resolver carries on down the
 * chain, which is how every other source behaves.
 *
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
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val searchQuery =
                listOfNotNull(title, artists.firstOrNull())
                    .joinToString(" ")
                    .trim()
            val candidates = QqMusicApi.search(searchQuery, CANDIDATE_LIMIT, session)
            if (candidates.isEmpty()) {
                Timber.tag(TAG).d("QQ Music catalogue had no hit for \"%s\"", searchQuery)
                return@withContext null
            }
            for (candidate in candidates) {
                val detail = QqMusicApi.detail(candidate.mid, session)
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
                val source = QqMusicApi.streamSource(session, matched.mid, matched.mediaMid, quality) ?: continue
                val stream = playable(source, matched, quality, cacheDir, trusted) ?: continue
                Timber.tag(TAG).i("QQ Music resolved \"%s\" as %s (%s)", title, matched.mid, stream.label)
                return@withContext stream
            }
            null
            }
        }
            ?: null

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
     * decrypts it, and hands over the local file.
     */
    private fun playable(
        source: QqStreamSource,
        matched: QqTrack,
        quality: QqAudioQuality,
        cacheDir: File,
        trusted: Boolean,
    ): DirectStream? {
        val extension = source.url.substringBefore('?').substringAfterLast('.', "")
        val protected = source.encrypted || source.ekey != null
        if (!protected) {
            val (mimeType, codecs) = plainMimeType(extension)
            return DirectStream(
                uri = source.url,
                mimeType = mimeType,
                codecs = codecs,
                contentLength = null,
                label = "QQ Music ${source.tier}",
                source = AudioSourceType.QQ,
                matchedTitle = matched.title,
                matchedArtist = matched.artist,
                matchedAlbum = matched.album,
                matchedDurationMs = matched.durationMs,
                trustedDirectId = trusted,
            )
        }

        val downloaded = download(source.url) ?: return null
        val decrypted = QmcDecryptor.decrypt(downloaded, extension, source.ekey) ?: return null
        val file = cacheContainer(cacheDir, source.url, quality, decrypted) ?: return null
        return DirectStream(
            uri = android.net.Uri.fromFile(file).toString(),
            mimeType = decrypted.container.mimeType,
            codecs = codec(decrypted.container, decrypted.bytes),
            contentLength = file.length(),
            label = "QQ Music ${source.tier}",
            source = AudioSourceType.QQ,
            matchedTitle = matched.title,
            matchedArtist = matched.artist,
            matchedAlbum = matched.album,
            matchedDurationMs = matched.durationMs,
            trustedDirectId = trusted,
        )
    }

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
     * Writes the decrypted payload under a name derived from the resource path, so a replay of the
     * same track and tier reuses the file, and prunes the directory oldest-first past
     * [CACHE_LIMIT_BYTES].
     */
    private fun cacheContainer(
        cacheDir: File,
        url: String,
        quality: QqAudioQuality,
        decrypted: QmcDecrypted,
    ): File? {
        val directory = File(cacheDir, "qqmusic").apply { mkdirs() }
        val existing = directory.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        var total = existing.sumOf { it.length() }
        for (stale in existing) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= stale.length()
            stale.delete()
        }
        val name = "qq_${url.hashCode()}_${quality.name.lowercase()}.${decrypted.container.extension}"
        val file = File(directory, name)
        return runCatching { file.writeBytes(decrypted.bytes) }
            .getOrElse { error ->
                Timber.tag(TAG).w(error, "could not write the decrypted QQ Music file")
                null
            }.let { file }
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
