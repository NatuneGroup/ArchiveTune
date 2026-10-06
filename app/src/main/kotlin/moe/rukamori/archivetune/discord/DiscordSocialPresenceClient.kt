/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.discord

object DiscordSocialPresenceClient {
    const val isAvailable = false

    val isStarted: Boolean
        get() = false

    fun setOnTransportInvalidated(listener: ((String) -> Unit)?) = Unit

    suspend fun updatePresence(
        accessToken: String,
        activity: DiscordPresenceActivity,
    ): Result<Unit> =
        if (accessToken.isBlank()) {
            Result.failure(DiscordAuthorizationRequiredException())
        } else {
            Result.failure(DiscordSocialSdkUnavailableException())
        }

    suspend fun clearPresence(accessToken: String? = null): Result<Unit> =
        Result.failure(DiscordSocialSdkUnavailableException())

    suspend fun close(): Result<Unit> = Result.success(Unit)
}

class DiscordAuthorizationRequiredException : IllegalStateException("Link and authorize a Discord account first")

class DiscordSocialSdkUnavailableException :
    IllegalStateException("Discord Rich Presence is unavailable because this build does not include the official Social SDK Android integration")
