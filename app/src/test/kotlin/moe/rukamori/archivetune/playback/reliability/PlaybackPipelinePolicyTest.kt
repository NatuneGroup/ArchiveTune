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
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.constants.AudioSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaybackPipelinePolicyTest {
    @Test
    fun `lossless claim requires a lossless encoding marker, never a quality label`() {
        assertTrue(isClaimedLosslessFormat("audio/flac", "flac"))
        assertTrue(isClaimedLosslessFormat("audio/mp4", "alac"))
        assertTrue(isClaimedLosslessFormat("audio/raw", ""))
        assertFalse(isClaimedLosslessFormat("audio/mp4", ""))
        assertFalse(isClaimedLosslessFormat("audio/mp4", "mp4a.40.2"))
        assertFalse(isClaimedLosslessFormat("audio/webm", "opus"))
    }

    @Test
    fun `authenticity sample rate requires reported metadata and never invents a quality tier rate`() {
        assertEquals(48_000, resolveAudioAuthenticitySampleRateHz(48_000))
        assertEquals(96_000, resolveAudioAuthenticitySampleRateHz(96_000))
        assertEquals(44_100, resolveAudioAuthenticitySampleRateHz(44_100))
        assertEquals(null, resolveAudioAuthenticitySampleRateHz(null))
        assertEquals(null, resolveAudioAuthenticitySampleRateHz(0))
        assertEquals(null, resolveAudioAuthenticitySampleRateHz(-1))
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
    fun `Amazon search uses its budget while remaining capped by the fallback chain`() {
        assertEquals(
            AudioSourceAttemptTimeouts.WHOLE_FILE_SOURCE_SEARCH_MS,
            resolveAudioSourceAttemptTimeoutMs(AudioSourceType.AMAZON),
        )
        assertEquals(
            AudioSourceAttemptTimeouts.WHOLE_FILE_SOURCE_SEARCH_MS,
            resolveAudioSourceAttemptTimeoutMs(AudioSourceType.QQ),
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

    @Test
    fun `a matched whole-file transfer is not cut by an expired source chain`() {
        var nowNanos = 0L
        val chainDeadline = AudioSourceAttemptDeadline.start(AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS) { nowNanos }
        nowNanos += TimeUnit.MILLISECONDS.toNanos(AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS + 1L)
        assertTrue(chainDeadline.isExpired())

        val transferRemainingMs =
            AudioSourceAttemptScope.withDeadline(chainDeadline) {
                runBlocking {
                    AudioSourceAttemptScope.withinTransfer {
                        AudioSourceAttemptScope.current()?.remainingMillis()
                    }
                }
            }
        assertTrue((transferRemainingMs ?: 0L) > AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS)
        assertTrue((transferRemainingMs ?: 0L) <= AudioSourceAttemptTimeouts.WHOLE_FILE_TRANSFER_MS)
    }
}
