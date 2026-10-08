/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import android.content.Context
import moe.rukamori.archivetune.constants.EnableTidalLyricsKey
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import moe.rukamori.archivetune.constants.TidalCountryCodeKey
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get

object TidalLyricsProvider : LyricsProvider {
    override val name = "Tidal"

    private fun firstToken(): String? {
        PreferenceStore.get(TidalAccessTokenKey)?.takeIf { it.isNotBlank() }?.let { return it }
        PoolAccountManager.tidalAccounts().firstOrNull()?.token?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore[EnableTidalLyricsKey] ?: true) && firstToken() != null

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        val token = firstToken() ?: return Result.failure(java.io.IOException("no Tidal token"))
        return TidalAccountManager.getLyrics(
            accessToken = token,
            title = title,
            artists = listOfNotNull(artist),
            durationMs = duration.takeIf { it > 0 }?.times(1000L),
            countryCode = PreferenceStore.get(TidalCountryCodeKey)?.ifBlank { null } ?: "US",
        )
    }
}
