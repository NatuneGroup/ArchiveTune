/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.AccountChannelHandleKey
import moe.rukamori.archivetune.constants.AccountEmailKey
import moe.rukamori.archivetune.constants.AccountImageUrlKey
import moe.rukamori.archivetune.constants.AccountNameKey
import moe.rukamori.archivetune.constants.DataSyncIdKey
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.InnerTubeOAuthExpiresAtKey
import moe.rukamori.archivetune.constants.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.constants.InnerTubeOAuthTokenKey
import moe.rukamori.archivetune.constants.PoTokenGvsKey
import moe.rukamori.archivetune.constants.PoTokenKey
import moe.rukamori.archivetune.constants.PoTokenPlayerKey
import moe.rukamori.archivetune.constants.PoTokenSourceUrlKey
import moe.rukamori.archivetune.constants.VisitorDataKey
import moe.rukamori.archivetune.constants.WebClientPoTokenEnabledKey
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.auth.YouTubeLoginRepository

fun Preferences.toPlaybackAuthState(): PlaybackAuthState =
    PlaybackAuthState(
        cookie = this[InnerTubeCookieKey],
        visitorData = this[VisitorDataKey],
        dataSyncId = this[DataSyncIdKey],
        poToken = this[PoTokenKey],
        poTokenGvs = this[PoTokenGvsKey],
        poTokenPlayer = this[PoTokenPlayerKey],
        webClientPoTokenEnabled = this[WebClientPoTokenEnabledKey] ?: false,
        // Only the access token goes to core; the refresh token stays app-side (see
        // YouTubeOAuthRepository). An expired token is passed down as null rather than as a value
        // that would 401 on every request — core has no way to refresh it.
        oauthToken =
            this[InnerTubeOAuthTokenKey]?.takeIf {
                (this[InnerTubeOAuthExpiresAtKey] ?: 0L) > System.currentTimeMillis()
            },
    ).normalized()

fun MutablePreferences.clearPlaybackAuthSession(clearAccountIdentity: Boolean = true) {
    remove(InnerTubeCookieKey)
    remove(VisitorDataKey)
    remove(DataSyncIdKey)
    remove(PoTokenKey)
    remove(PoTokenGvsKey)
    remove(PoTokenPlayerKey)
    remove(PoTokenSourceUrlKey)
    // The OAuth session is a second way to be signed in, so signing out has to drop it too —
    // otherwise "log out" leaves a working Bearer behind and playback stays authenticated.
    // Revoking it server-side is YouTubeOAuthRepository.signOut(); this only clears local state.
    remove(InnerTubeOAuthTokenKey)
    remove(InnerTubeOAuthRefreshTokenKey)
    remove(InnerTubeOAuthExpiresAtKey)
    if (clearAccountIdentity) {
        remove(AccountNameKey)
        remove(AccountImageUrlKey)
        remove(AccountEmailKey)
        remove(AccountChannelHandleKey)
    }
}

fun MutablePreferences.putLegacyPoToken(value: String?) {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    if (normalized == null) {
        remove(PoTokenKey)
    } else {
        this[PoTokenKey] = normalized
    }
    remove(PoTokenGvsKey)
    remove(PoTokenPlayerKey)
}

/** Preserve the selected context and terminal auth errors; a repair is never a default-account retry. */
suspend fun <T> Context.retryWithoutPlaybackLoginContext(block: suspend () -> Result<T>): Result<T> {
    return retryWithoutPlaybackLoginContext(App.instance.youTubeLoginRepository, { YouTube.currentPlaybackAuthState() }, block)
}

internal suspend fun <T> retryWithoutPlaybackLoginContext(
    owner: YouTubeLoginRepository,
    currentAuth: () -> PlaybackAuthState,
    block: suspend () -> Result<T>,
): Result<T> {
    owner.reconcilePersisted()
    val origin = owner.origin()
    val result = block()
    if (result.isSuccess) owner.persistRepair(origin, currentAuth())
    return result
}
