/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/*
 * Preference keys for the Deezer playback source, kept OUT of PreferenceKeys.kt on purpose so
 * they cannot collide with upstream's declarations there. The four Deezer account keys
 * (DeezerEnabledKey, DeezerArlKey, DeezerAccountNameKey, DeezerAccountPremiumKey) are already
 * declared in PreferenceKeys.kt and are deliberately not repeated here.
 *
 * The string passed to stringPreferencesKey is the on-disk DataStore name. Renaming it orphans
 * the stored setting on every existing install, so treat it as permanent.
 */

// Deezer serves three tiers. FLAC needs a lossless plan, so the provider walks down from the
// requested tier and a free account silently lands on MP3 rather than failing the track.
enum class DeezerAudioQuality {
    FLAC,
    MP3_320,
    MP3_128,
}

val DeezerAudioQualityKey = stringPreferencesKey("deezerAudioQuality")

/** The `format` string Deezer's media endpoint expects for each tier. */
fun DeezerAudioQuality.toFormatName(): String =
    when (this) {
        DeezerAudioQuality.FLAC -> "FLAC"
        DeezerAudioQuality.MP3_320 -> "MP3_320"
        DeezerAudioQuality.MP3_128 -> "MP3_128"
    }

// Listen Together (ported from vivi-music beta — same key names and types as
// vivi's constants/PreferenceKeys.kt so persisted values carry over semantics 1:1).
val ListenTogetherServerUrlKey = stringPreferencesKey("listenTogetherServerUrl")
val ListenTogetherUserIdKey = stringPreferencesKey("listenTogetherUserId")
val ListenTogetherRoomCodeKey = stringPreferencesKey("listenTogetherRoomCode")
val ListenTogetherSessionTokenKey = stringPreferencesKey("listenTogetherSessionToken")
val ListenTogetherSessionTimestampKey = longPreferencesKey("listenTogetherSessionTimestamp")
val ListenTogetherIsHostKey = booleanPreferencesKey("listenTogetherIsHost")
val ListenTogetherAvatarIndexKey = intPreferencesKey("listenTogetherAvatarIndex")
val ListenTogetherAutoApprovalKey = booleanPreferencesKey("listenTogetherAutoApproval")
val ListenTogetherSuggestionAutoApproveKey = booleanPreferencesKey("listenTogetherSuggestionAutoApprove")
val ListenTogetherCustomAvatarUriKey = stringPreferencesKey("listenTogetherCustomAvatarUri")
val ListenTogetherSmartResyncKey = booleanPreferencesKey("listenTogetherSmartResync")
val ListenTogetherSyncVolumeKey = booleanPreferencesKey("listenTogetherSyncVolume")
val ListenTogetherBlockedUsersKey = stringPreferencesKey("listenTogetherBlockedUsers")
// UI-side keys (T1-C): same names/types as vivi's PreferenceKeys.kt
// (stringPreferencesKey "listenTogetherUsername", booleanPreferencesKey "listenTogetherInTopBar").
val ListenTogetherChatNotificationsKey = booleanPreferencesKey("listenTogetherChatNotifications")
val ListenTogetherChatHistoryKey = stringPreferencesKey("listenTogetherChatHistory")

val ListenTogetherUsernameKey = stringPreferencesKey("listenTogetherUsername")
val ListenTogetherInTopBarKey = booleanPreferencesKey("listenTogetherInTopBar")

// TikTok player: the karaoke caption strip under the artwork (ported from
// 4nx3b/ArchiveTune). Off by default — it reserves 168dp of the page for the strip.
val TikTokMainLyricsEnabledKey = booleanPreferencesKey("tiktokMainLyricsEnabled")

// Manual ordering for the Library's section chips and its playlist-tag filter row.
// Both store a comma-separated list of names/ids; see constants/LibraryFilter.kt and
// constants/PlaylistTagOrder.kt for the readers. LibraryChipOrderKey defaults to the
// built-in order; PlaylistTagOrderKey defaults empty = unknown/never arranged.
val LibraryChipOrderKey = stringPreferencesKey("libraryChipOrder")
val PlaylistTagOrderKey = stringPreferencesKey("playlistTagOrder")
