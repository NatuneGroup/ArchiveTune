/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.discord

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordSocialPresenceClientTest {
    private val activity =
        DiscordPresenceActivity(
            applicationId = 1L,
            name = "ArchiveTune",
            type = DiscordActivityType.Playing,
            details = null,
            state = null,
        )

    @Test
    fun updatePresenceDistinguishesMissingAuthorizationFromUnavailableSdk() = runTest {
        val missingAuthorization = DiscordSocialPresenceClient.updatePresence("", activity)
        val unsupported = DiscordSocialPresenceClient.updatePresence("not-a-real-oauth-token", activity)

        assertTrue(missingAuthorization.exceptionOrNull() is DiscordAuthorizationRequiredException)
        assertTrue(unsupported.exceptionOrNull() is DiscordSocialSdkUnavailableException)
        assertFalse(DiscordSocialPresenceClient.isStarted)
    }

    @Test
    fun clearPresenceReportsUnavailableSdkWithoutOpeningAGateway() = runTest {
        val result = DiscordSocialPresenceClient.clearPresence("not-a-real-oauth-token")

        assertTrue(result.exceptionOrNull() is DiscordSocialSdkUnavailableException)
        assertFalse(DiscordSocialPresenceClient.isStarted)
    }

    @Test
    fun oauthHttpErrorsIdentifyRejectedAuthorizationWithoutResponseBody() {
        val rejected = DiscordOAuthHttpException(401)
        val unavailable = DiscordOAuthHttpException(503)

        assertTrue(rejected.isAuthorizationRejected)
        assertFalse(unavailable.isAuthorizationRejected)
        assertEquals("Discord OAuth request failed with HTTP 401", rejected.message)
    }
}
