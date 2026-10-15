/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.reliability

import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
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

    @Test
    fun reportedBitDepthAndObservedChannelCountSurviveCaching() {
        val record = CachedAudioMetadata.from(stream(96_000).copy(bitDepth = 24)).copy(channelCount = 2)
        val metadata = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(record.toMutations())
        val restored = CachedAudioMetadata.read(metadata, AudioSourceType.TIDAL)

        assertEquals(24, restored?.bitDepth)
        assertEquals(2, restored?.channelCount)
    }

    @Test
    fun olderVersionOneMetadataDoesNotInventNewFormatFields() {
        val mutations = ContentMetadataMutations()
            .set("archivetune:audio:version", 1L)
            .set("archivetune:audio:source", AudioSourceType.TIDAL.name)
            .set("archivetune:audio:sample_rate", 96_000L)
            .set("archivetune:audio:claimed_lossless", 1L)
        val metadata = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations)
        val restored = CachedAudioMetadata.read(metadata, AudioSourceType.TIDAL)

        assertEquals(96_000, restored?.sampleRateHz)
        assertNull(restored?.bitDepth)
        assertNull(restored?.channelCount)
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
