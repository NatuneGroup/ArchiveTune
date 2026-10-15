/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Offline-first Spotify home: restores the last good lists so the home renders
 * instantly, then replaces them when the network answers. Ported from
 * YumaPlayer's SpotifyProfileCache, adapted to the fork's models.
 */

package moe.rukamori.archivetune.spotify

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.utils.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded offline snapshot of the Spotify home. Three small capped lists only —
 * the recent panel (≤8 rows), the top-tracks shelf and the artist strip — so the
 * stored JSON stays a few kilobytes and never grows with the library.
 *
 * DataStore is the write path for all of this because the lists replace
 * wholesale on every successful load; the fork's SQL layer is kept for the
 * library itself, which is query-shaped data.
 */
data class SpotifyProfileCachedData(
    val recentItems: List<SpotifyRecentItem> = emptyList(),
    val topTracks: List<SpotifyTrack> = emptyList(),
    val frequentArtists: List<SpotifyArtist> = emptyList(),
    val timestamp: Long = 0L,
)

@Singleton
class SpotifyProfileCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        suspend fun restoreFromDataStore(): SpotifyProfileCachedData {
            val prefs = context.dataStore.data.firstOrNull() ?: return SpotifyProfileCachedData()

            // Each list decodes independently: one corrupt blob must not take the
            // other two down with it, so a bad write degrades to a partial home,
            // never a stuck Loading spinner. The sealed recent-items interface
            // carries its own generated polymorphic serializer, so the stored
            // JSON round-trips without a hand-written adapter.
            val recentItems =
                prefs[SpotifyProfileRecentItemsKey]?.let { raw ->
                    runCatching {
                        json.decodeFromString(
                            ListSerializer(SpotifyRecentItem.serializer()),
                            raw,
                        )
                    }.getOrNull()
                }.orEmpty()

            val topTracks =
                prefs[SpotifyProfileTopTracksKey]?.let { raw ->
                    runCatching {
                        json.decodeFromString(ListSerializer(SpotifyTrack.serializer()), raw)
                    }.getOrNull()
                }.orEmpty()

            val artists =
                prefs[SpotifyProfileArtistsKey]?.let { raw ->
                    runCatching {
                        json.decodeFromString(ListSerializer(SpotifyArtist.serializer()), raw)
                    }.getOrNull()
                }.orEmpty()

            return SpotifyProfileCachedData(
                recentItems = recentItems,
                topTracks = topTracks,
                frequentArtists = artists,
                timestamp = prefs[SpotifyProfileCacheTsKey] ?: 0L,
            )
        }

        suspend fun persistToDataStore(
            recentItems: List<SpotifyRecentItem>,
            topTracks: List<SpotifyTrack>,
            frequentArtists: List<SpotifyArtist>,
        ) {
            context.dataStore.edit { prefs ->
                prefs[SpotifyProfileCacheTsKey] = System.currentTimeMillis()
                // Persist non-empty only: an empty successful section (user cleared
                // their recents) must not wipe the last good snapshot until a
                // populated load replaces it.
                if (recentItems.isNotEmpty()) {
                    runCatching {
                        prefs[SpotifyProfileRecentItemsKey] =
                            json.encodeToString(
                                ListSerializer(SpotifyRecentItem.serializer()),
                                recentItems,
                            )
                    }
                }
                if (topTracks.isNotEmpty()) {
                    runCatching {
                        prefs[SpotifyProfileTopTracksKey] =
                            json.encodeToString(ListSerializer(SpotifyTrack.serializer()), topTracks)
                    }
                }
                if (frequentArtists.isNotEmpty()) {
                    runCatching {
                        prefs[SpotifyProfileArtistsKey] =
                            json.encodeToString(ListSerializer(SpotifyArtist.serializer()), frequentArtists)
                    }
                }
            }
        }

        suspend fun clearCache() {
            context.dataStore.edit { prefs -> clearSnapshot(prefs) }
        }

        companion object {
            internal fun clearSnapshot(prefs: MutablePreferences) {
                prefs.remove(SpotifyProfileRecentItemsKey)
                prefs.remove(SpotifyProfileTopTracksKey)
                prefs.remove(SpotifyProfileArtistsKey)
                prefs.remove(SpotifyProfileCacheTsKey)
            }

            private val SpotifyProfileRecentItemsKey =
                stringPreferencesKey("spotify_profile_recent_items")
            private val SpotifyProfileTopTracksKey =
                stringPreferencesKey("spotify_profile_top_tracks")
            private val SpotifyProfileArtistsKey =
                stringPreferencesKey("spotify_profile_artists")
            private val SpotifyProfileCacheTsKey =
                longPreferencesKey("spotify_profile_cache_ts")
        }
    }
