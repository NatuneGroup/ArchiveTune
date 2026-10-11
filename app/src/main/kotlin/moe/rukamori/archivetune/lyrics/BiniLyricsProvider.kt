/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/BiniLyrics.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.EnableBiniLyricsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.util.Locale

object BiniLyricsProvider : LyricsProvider {
    private const val BASE_URL = "https://lyrics-api.binimum.org/"

    override val name = "BiniLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBiniLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = getLyrics(id, title, artist, album, duration, isrc = null)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        isrc: String?,
    ): Result<String> =
        runCatching {
            fetchLyrics(title, artist, album, duration, isrc)
                ?: throw IllegalStateException("No BiniLyrics lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        isrc: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank() && artist.isBlank() && isrc.isNullOrBlank()) return@withContext null

            val url = buildString {
                append(BASE_URL)
                append('?')
                if (!isrc.isNullOrBlank()) {
                    append("isrc=")
                    append(LyricsProviderHttp.encode(isrc.trim().uppercase(Locale.ROOT)))
                } else {
                    append("track=")
                    append(LyricsProviderHttp.encode(title.forLyricsSearch()))
                    append("&artist=")
                    append(LyricsProviderHttp.encode(artist.artistForLyricsSearch()))
                    if (!album.isNullOrBlank()) {
                        append("&album=")
                        append(LyricsProviderHttp.encode(album.trim()))
                    }
                    if (duration > 0) {
                        append("&duration=")
                        append(duration)
                    }
                }
            }

            val body = LyricsProviderHttp.get(url) ?: return@withContext null
            val root = LyricsProviderHttp.parseJson(body).asObject() ?: return@withContext null
            val results = root.array("results") ?: return@withContext null
            val hit = results.firstOrNull().asObject() ?: return@withContext null

            val document = hit.string("lyricsUrl") ?: return@withContext null
            LyricsProviderHttp.get(document)?.let(LyricsPayload::toLyrics)
        }
}
