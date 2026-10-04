/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.playback.AudioAuthenticitySourceRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioAuthenticitySourceRetryPolicyTest {
    @Test
    fun `keeps the configured chain until its selected source is rejected`() {
        assertEquals(
            listOf(AudioSourceType.QOBUZ),
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = listOf(AudioSourceType.QOBUZ),
                fallbackChain = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ),
                rejectedSources = emptySet(),
            ),
        )
    }

    @Test
    fun `retries a rejected explicit choice through the remaining fallback chain`() {
        assertEquals(
            listOf(AudioSourceType.TIDAL, AudioSourceType.DEEZER),
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = listOf(AudioSourceType.QOBUZ),
                fallbackChain = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.DEEZER),
                rejectedSources = setOf(AudioSourceType.QOBUZ),
            ),
        )
    }

    @Test
    fun `empty configured chain stays empty and rejected fallback sources stay excluded`() {
        assertEquals(
            emptyList<AudioSourceType>(),
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = emptyList(),
                fallbackChain = listOf(AudioSourceType.TIDAL),
                rejectedSources = setOf(AudioSourceType.YOUTUBE),
            ),
        )
        assertEquals(
            emptyList<AudioSourceType>(),
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = listOf(AudioSourceType.QOBUZ),
                fallbackChain = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ),
                rejectedSources = setOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE),
            ),
        )
    }

    @Test
    fun `repeated rejections exhaust candidates without reinserting YouTube`() {
        val firstRetry =
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = listOf(AudioSourceType.QOBUZ),
                fallbackChain = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ),
                rejectedSources = setOf(AudioSourceType.QOBUZ),
            )
        assertEquals(listOf(AudioSourceType.TIDAL), firstRetry)

        assertEquals(
            emptyList<AudioSourceType>(),
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = firstRetry,
                fallbackChain = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ),
                rejectedSources = setOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE),
            ),
        )
    }
}
