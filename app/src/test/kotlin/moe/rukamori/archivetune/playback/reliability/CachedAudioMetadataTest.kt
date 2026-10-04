/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.reliability

import androidx.media3.datasource.cache.DefaultContentMetadata
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.playback.CachedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedAudioMetadataTest {
    @Test
    fun cachedMetadataRetainsReportedRateAndLosslessClaimWithoutAStreamUrl() {
        val record = CachedAudioMetadata.from(stream(sampleRate = 88_200))
        val metadata = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(record.toMutations())

        assertEquals(record, CachedAudioMetadata.read(metadata, AudioSourceType.TIDAL))
        assertEquals(88_200, record.sampleRateHz)
        assertTrue(record.claimedLossless)
    }

    @Test
    fun aHighResolutionLabelWithoutAReportedRateRemainsUnknownAfterCaching() {
        val record = CachedAudioMetadata.from(stream(sampleRate = null))
        val metadata = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(record.toMutations())

        assertNull(CachedAudioMetadata.read(metadata, AudioSourceType.TIDAL)?.sampleRateHz)
    }

    @Test
    fun metadataCannotBeReusedForAnotherAudioSource() {
        val record = CachedAudioMetadata.from(stream(sampleRate = 96_000))
        val metadata = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(record.toMutations())

        assertNull(CachedAudioMetadata.read(metadata, AudioSourceType.QOBUZ))
    }

    @Test
    fun legacyCachedBytesWithoutSourceMetadataRemainUnknown() {
        assertNull(CachedAudioMetadata.read(DefaultContentMetadata.EMPTY, AudioSourceType.TIDAL))
    }

    private fun stream(sampleRate: Int?): DirectStream =
        DirectStream(
            uri = "https://example.test/audio",
            mimeType = "audio/flac",
            codecs = "flac",
            contentLength = 1_000_000L,
            label = "Tidal HI_RES",
            source = AudioSourceType.TIDAL,
            sampleRate = sampleRate,
        )
}
