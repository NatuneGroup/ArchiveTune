/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import android.content.Context
import moe.rukamori.archivetune.constants.EnableDeezerLyricsKey
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get

object DeezerLyricsProvider : LyricsProvider {
    override val name = "Deezer"

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore[EnableDeezerLyricsKey] ?: true) && DeezerAudioProvider.hasAccounts()

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        DeezerAudioProvider.getLyrics(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration.takeIf { it > 0 }?.times(1000L),
        )
}
