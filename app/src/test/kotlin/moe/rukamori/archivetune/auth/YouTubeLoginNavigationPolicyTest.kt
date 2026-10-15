/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import org.junit.Assert.*
import org.junit.Test

class YouTubeLoginNavigationPolicyTest {
    private val product = "com.google.android.apps.youtube.music"
    private val listing = "https://play.google.com/store/apps/details?id=$product"
    private fun intent(hostPath: String, pkg: String = product, extra: String = "") =
        "intent://$hostPath#Intent;scheme=https;package=$pkg$extra;end"

    @Test
    fun canonicalMusicInstallFormsReturnOnlyToFixedMusicHome() {
        val targets = listOf(
            intent("play.google.com/store/apps/details?id=$product", "com.android.vending"),
            intent("play.google.com/store/apps/details?id=$product"),
            intent("music.youtube.com/"),
            intent("music.youtube.com/", extra = ";S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3D$product"),
            "market://details?id=$product", listing,
        )
        targets.forEach { target ->
            assertTrue(target, YouTubeLoginNavigationPolicy.isMusicInstall(target))
            assertEquals(target, YouTubeLoginNavigationPolicy.Decision.ReturnToMusic,
                YouTubeLoginNavigationPolicy.decide(target, true, "GET", true))
        }
        assertEquals("https://music.youtube.com/", YouTubeLoginNavigationPolicy.MUSIC_HOME)
    }

    @Test
    fun authorityPathProductAndPackageAttacksNeverAuthorizeReturn() {
        val targets = listOf(
            intent("play.google.com.evil.invalid/store/apps/details?id=$product"),
            intent("evil.invalid/store/apps/details?id=$product"),
            intent("user@play.google.com/store/apps/details?id=$product"),
            intent("play.google.com:443/store/apps/details?id=$product"),
            intent("play.google.com/store/apps/other?id=$product"),
            intent("play.google.com/store/apps/details?id=another.product"),
            intent("play.google.com/store/apps/details?id=$product&id=$product"),
            intent("play.google.com/store/apps/details?id=$product", "evil.package"),
            intent("music.youtube.com/", "com.android.vending"),
            intent("music.youtube.com.evil.invalid/"), intent("music.youtube.com:8443/"),
            intent("music.youtube.com/watch?v=synthetic"), intent("music.youtube.com/other"),
            "market://user@details?id=$product", "market://details:443?id=$product",
            "market://details/other?id=$product", "$listing#fragment",
            "https://play.google.com.evil.invalid/store/apps/details?id=$product",
            "https://user@play.google.com/store/apps/details?id=$product",
            "https://play.google.com:443/store/apps/details?id=$product",
        )
        targets.forEach { target ->
            assertFalse(target, YouTubeLoginNavigationPolicy.isMusicInstall(target))
            assertNotEquals(target, YouTubeLoginNavigationPolicy.Decision.ReturnToMusic,
                YouTubeLoginNavigationPolicy.decide(target, true, "GET", true))
        }
    }

    @Test
    fun malformedDuplicateAndUntrustedIntentExtrasAreDenied() {
        val targets = listOf(
            intent("music.youtube.com/", extra = ";package=$product"),
            intent("music.youtube.com/", extra = ";scheme=https"),
            intent("music.youtube.com/", extra = ";S.arbitrary=synthetic"),
            intent("music.youtube.com/", extra = ";action=android.intent.action.SEND"),
            intent("music.youtube.com/", extra = ";category=untrusted"),
            intent("music.youtube.com/", extra = ";S.browser_fallback_url=https%3A%2F%2Fevil.invalid%2F"),
            intent("music.youtube.com/", extra = ";S.browser_fallback_url=https%3A%2F%2Fmusic.youtube.com%3A443%2F"),
            intent("music.youtube.com/", extra = ";S.browser_fallback_url=%ZZ"),
            "intent://music.youtube.com/#Intent;scheme=https;end",
            "intent://music.youtube.com/#Intent;scheme=http;package=$product;end",
            "intent://music.youtube.com/#Intent;scheme=https;package=$product",
            "intent://[malformed#Intent;scheme=https;package=$product;end",
            "intent://music.youtube.com/#Intent;scheme=https;#Intent;package=$product;end",
        )
        targets.forEach { assertEquals(it, YouTubeLoginNavigationPolicy.Decision.Block,
            YouTubeLoginNavigationPolicy.decide(it, true, "GET", true)) }
    }

    @Test
    fun subframePostLegacyAndUntrustedSourceCannotTriggerTopLevelGet() {
        for (target in listOf(intent("music.youtube.com/"), listing, "market://details?id=$product")) {
            for ((frame, method, trusted) in listOf(Triple(false, "GET", true), Triple(true, "POST", true),
                Triple(false, "UNKNOWN", true), Triple(true, "GET", false), Triple(true, "HEAD", true))) {
                assertEquals(YouTubeLoginNavigationPolicy.Decision.Block,
                    YouTubeLoginNavigationPolicy.decide(target, frame, method, trusted))
            }
        }
    }

    @Test
    fun googleConsentAndChallengeHttpsRetainTheirOriginalNavigation() {
        for (target in listOf("https://accounts.google.com/signin/v2/challenge", "https://accounts.google.com/o/oauth2/consent")) {
            assertTrue(YouTubeLoginNavigationPolicy.trustedLoginPage(target))
            for (method in listOf("GET", "POST")) assertEquals(YouTubeLoginNavigationPolicy.Decision.Allow,
                YouTubeLoginNavigationPolicy.decide(target, true, method, true))
        }
        assertEquals(YouTubeLoginNavigationPolicy.Decision.Block,
            YouTubeLoginNavigationPolicy.decide("arbitrary://music.youtube.com/", true, "GET", true))
    }

    @Test
    fun extractionTrustRequiresExactOfficialHttpsOrigin() {
        assertEquals("https://music.youtube.com", YouTubeLoginNavigationPolicy.trustedYouTubeOrigin("https://music.youtube.com/watch?v=synthetic"))
        listOf("http://music.youtube.com/", "https://music.youtube.com.evil.invalid/", "https://user@music.youtube.com/",
            "https://music.youtube.com:443/", "https://accounts.google.com/", "malformed").forEach {
            assertNull(it, YouTubeLoginNavigationPolicy.trustedYouTubeOrigin(it))
        }
    }

    @Test
    fun pendingDuplicatesCoalesceAndRepeatedInstallStopsInsteadOfLooping() {
        val guard = YouTubeLoginReturnGuard()
        assertTrue(guard.requestReturn())
        assertTrue(guard.isPending())
        assertFalse(guard.requestReturn())
        assertTrue(guard.consumePending())
        assertFalse(guard.consumePending())
        assertFalse(guard.requestReturn())
        assertFalse(guard.isPending())
        assertTrue(YouTubeLoginReturnGuard().requestReturn()) // Explicit Retry owns a fresh view.
    }

    @Test
    fun releasedPendingReturnCannotLoadLater() {
        val guard = YouTubeLoginReturnGuard()
        assertTrue(guard.requestReturn())
        guard.release()
        assertFalse(guard.isPending())
        assertFalse(guard.consumePending())
        assertFalse(guard.requestReturn())
    }
}
