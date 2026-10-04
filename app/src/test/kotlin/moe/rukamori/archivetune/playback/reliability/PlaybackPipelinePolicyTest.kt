/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.reliability

import moe.rukamori.archivetune.playback.AudioAuthenticityProbeAvailability
import moe.rukamori.archivetune.playback.isClaimedLosslessFormat
import moe.rukamori.archivetune.playback.resolveAudioAuthenticityProbeAvailability
import moe.rukamori.archivetune.playback.resolveAudioAuthenticityRetryPositionMs
import moe.rukamori.archivetune.playback.resolveAudioAuthenticitySampleRateHz
import moe.rukamori.archivetune.playback.resolveAudioOffloadEnabled
import moe.rukamori.archivetune.playback.resolveAudioSourceAttemptTimeoutMs
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptDeadline
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPipelinePolicyTest {
    @Test
    fun `lossless claim requires a lossless encoding or explicit lossless quality label`() {
        assertTrue(isClaimedLosslessFormat("audio/flac", "flac", "Qobuz FLAC"))
        assertTrue(isClaimedLosslessFormat("audio/mp4", "alac", "Apple Music"))
        assertTrue(isClaimedLosslessFormat("audio/mp4", "", "Tidal HI-RES_LOSSLESS"))
        assertTrue(isClaimedLosslessFormat("audio/flac", "flac", "Qobuz MAX"))
        assertFalse(isClaimedLosslessFormat("audio/mp4", "mp4a.40.2", "JioSaavn 320"))
        assertFalse(isClaimedLosslessFormat("audio/webm", "opus", "YouTube Music"))
    }

    @Test
    fun `authenticity sample rate uses actual metadata or a quality tier for category selection`() {
        assertEquals(48_000, resolveAudioAuthenticitySampleRateHz(48_000, "audio/flac", "flac", "Qobuz HI_RES"))
        assertEquals(96_000, resolveAudioAuthenticitySampleRateHz(null, "audio/flac", "flac", "Qobuz HI_RES"))
        assertEquals(96_000, resolveAudioAuthenticitySampleRateHz(null, "audio/flac", "flac", "Qobuz MAX"))
        assertEquals(44_100, resolveAudioAuthenticitySampleRateHz(null, "audio/flac", "flac", "Qobuz FLAC"))
        assertEquals(null, resolveAudioAuthenticitySampleRateHz(null, "audio/mp4", "mp4a.40.2", "AAC 320"))
    }

    @Test
    fun `PCM authenticity opt in disables offload without changing the requested setting`() {
        assertFalse(
            resolveAudioOffloadEnabled(
                requested = true,
                crossfadeEnabled = false,
                automixEnabled = false,
                authenticityRejectionEnabled = true,
            ),
        )
        assertTrue(
            resolveAudioOffloadEnabled(
                requested = true,
                crossfadeEnabled = false,
                automixEnabled = false,
                authenticityRejectionEnabled = false,
            ),
        )
    }

    @Test
    fun `a processor-bypassing output is reported unknown and cannot reject`() {
        assertEquals(
            AudioAuthenticityProbeAvailability.UNKNOWN,
            resolveAudioAuthenticityProbeAvailability(rejectionEnabled = true, pcmTapAvailable = false),
        )
        assertEquals(
            AudioAuthenticityProbeAvailability.DISABLED,
            resolveAudioAuthenticityProbeAvailability(rejectionEnabled = false, pcmTapAvailable = false),
        )
        assertEquals(
            AudioAuthenticityProbeAvailability.AVAILABLE,
            resolveAudioAuthenticityProbeAvailability(rejectionEnabled = true, pcmTapAvailable = true),
        )
    }

    @Test
    fun `authenticity source retries retain the captured playback position`() {
        assertEquals(14_321L, resolveAudioAuthenticityRetryPositionMs(14_321L))
        assertEquals(0L, resolveAudioAuthenticityRetryPositionMs(-20L))
    }

    @Test
    fun `Amazon uses its transfer budget while remaining capped by the fallback chain`() {
        assertEquals(
            AudioSourceAttemptTimeouts.AMAZON_STREAM_TRANSFER_MS,
            resolveAudioSourceAttemptTimeoutMs(AudioSourceType.AMAZON),
        )
        assertEquals(
            AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS,
            resolveAudioSourceAttemptTimeoutMs(AudioSourceType.TIDAL),
        )
        val chainDeadline = AudioSourceAttemptDeadline.start(AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS)
        val amazonDeadline = chainDeadline.child(resolveAudioSourceAttemptTimeoutMs(AudioSourceType.AMAZON))
        assertTrue(amazonDeadline.remainingMillis() <= AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS)
        assertTrue(amazonDeadline.remainingMillis() > 0L)
    }
}
