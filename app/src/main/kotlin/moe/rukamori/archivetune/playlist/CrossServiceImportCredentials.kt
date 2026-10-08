/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.playlist

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.QobuzTokensKey
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import moe.rukamori.archivetune.constants.TidalCountryCodeKey
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.spotify.SpotifyLibraryRepository
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.dataStore

/**
 * Collects the credentials [CrossServicePlaylistImporter] needs for the services whose playlist
 * APIs reject anonymous reads (Tidal and Qobuz).
 */
object CrossServiceImportCredentials {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SpotifyRepositoryEntryPoint {
        fun spotifyLibraryRepository(): SpotifyLibraryRepository
    }

    suspend fun load(context: Context): CrossServicePlaylistImporter.Credentials =
        withContext(Dispatchers.IO) {
            // Warm the pool cache from disk so a cold start still has accounts.
            runCatching { PoolAccountManager.loadCached(context) }

            // Restore the Spotify session so playlist imports use the authenticated
            // paged API (full playlist) instead of the public embed page, whose
            // __NEXT_DATA__ blob is server-capped at ~100 tracks. On a fresh launch
            // the in-memory token is null even when the user is signed in, which
            // silently truncated every Spotify transfer at 100 songs.
            runCatching {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    SpotifyRepositoryEntryPoint::class.java,
                ).spotifyLibraryRepository().restoreSession()
            }

            val prefs = runCatching { context.dataStore.data.first() }.getOrNull()

            val userTidalToken = prefs?.get(TidalAccessTokenKey)?.takeIf { it.isNotBlank() }
            val poolTidal = PoolAccountManager.tidalAccounts().firstOrNull()
            val tidalToken = userTidalToken ?: poolTidal?.token?.takeIf { it.isNotBlank() }
            val tidalCountry = prefs?.get(TidalCountryCodeKey)?.takeIf { it.isNotBlank() }
                ?: poolTidal?.countryCode?.takeIf { it.isNotBlank() }
                ?: "US"

            // Qobuz needs the app_id alongside the auth token; a token without
            // one can't sign requests, so only complete pairs are used.
            val qobuz = QobuzToken.listFromJson(prefs?.get(QobuzTokensKey))
                .firstOrNull { it.token.isNotBlank() && it.appId.isNotBlank() }
                ?.let { it.appId to it.token }
                ?: PoolAccountManager.qobuzAccounts()
                    .firstOrNull { it.token.isNotBlank() && it.appId.isNotBlank() }
                    ?.let { it.appId to it.token }

            CrossServicePlaylistImporter.Credentials(
                tidalAccessToken = tidalToken,
                tidalCountryCode = tidalCountry,
                qobuzAppId = qobuz?.first,
                qobuzAuthToken = qobuz?.second,
            )
        }
}
