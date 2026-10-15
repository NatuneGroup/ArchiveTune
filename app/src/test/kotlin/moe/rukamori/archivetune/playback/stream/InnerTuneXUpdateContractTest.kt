/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import com.metrolist.innertubex.extraction.AudioQuality as LibraryQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedMediaMetadata
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InnerTuneXUpdateContractTest {
    @Test
    fun mapsTargetDirectAudioAndMetadataWithoutChangingRequestIdentity() = runTest {
        val target = directStream()
        // These library-owned enrichment setters are internal in Kotlin, public in the saved
        // v0.9.2 binary contract. Reflection seeds the real model, not an alternate adapter.
        val metadata = ExtractedMediaMetadata(
            title = "Fixture title", author = "Fixture author", channelId = "fixture-channel",
            durationSeconds = 187L, musicVideoType = null, viewCount = null,
            thumbnails = emptyList(), isLive = false,
        )
        target.javaClass.getMethod(
            "setMediaMetadata\$com_github_MetrolistGroup_innertubex", ExtractedMediaMetadata::class.java,
        ).invoke(target, metadata)
        target.javaClass.getMethod(
            "setPerceptualLoudnessDb\$com_github_MetrolistGroup_innertubex", java.lang.Double::class.java,
        ).invoke(target, -9.25)
        val extractor = RecordingExtractor { target }
        val request = request()
        val result = InnerTuneXStreamRepository(extractor).resolve(request)

        assertEquals(target.audioUrl, result.url)
        assertEquals(target.headers, result.requestHeaders)
        assertEquals(140, result.formatId)
        assertEquals("audio/mp4", result.mimeType)
        assertEquals("mp4a.40.2", result.codecs)
        assertEquals(128_000, result.bitrate)
        assertEquals(44_100, result.sampleRate)
        assertEquals(3_000_000L, result.contentLength)
        assertEquals(4_000_000_000_000L, result.expiresAtMs)
        assertEquals("Fixture title", result.title)
        assertEquals(187, result.durationSeconds)
        assertEquals(-7.5, result.loudnessDb!!, 0.0)
        assertEquals(-9.25, result.perceptualLoudnessDb!!, 0.0)
        assertEquals(StreamSource.INNERTUBE_X, result.source)
        assertEquals(request.authState.streamCacheFingerprint, result.authFingerprint)
        assertEquals(listOf(request.mediaId), extractor.videoIds)
        assertEquals("fixture-playlist", request.playlistId)
        assertNull(result.thumbnailUrl)
        assertNull(result.playbackTrackingUrl)
    }

    @Test
    fun requestsAnonymousAudioOnlyWithEveryUnsupportedCapabilityDisabled() = runTest {
        val extractor = RecordingExtractor { directStream() }
        InnerTuneXStreamRepository(extractor).resolve(request())
        val hints = extractor.hints.single()
        assertFalse(hints.wantVideo)
        assertFalse(hints.allowHls)
        assertFalse(hints.allowSabr)
        assertFalse(hints.allowBoundedRange)
        assertFalse(hints.premium)
        assertNull(hints.playbackClientOverrideId)
        assertEquals(listOf(emptySet<String>()), extractor.excludedClients)
    }

    @Test
    fun rejectsHlsUrlsIndependently() = runTest {
        for (url in listOf("https://media.example/manifest/hls/fixture", "https://media.example/audio.m3u8")) {
            assertRejected(directStream().copy(audioUrl = url), "HLS manifest")
        }
    }

    @Test
    fun rejectsSabrBootstrapEvenWithDirectAudioUrl() = runTest {
        val sabr = SabrBootstrap(
            videoId = "fixture-video", serverAbrStreamingUrl = "https://media.example/sabr",
            videoPlaybackUstreamerConfig = byteArrayOf(1), clientName = 1, clientVersion = "fixture",
            audioFormat = SabrFormatId(140), discardVideoFormat = SabrFormatId(160),
            discardVideoHeight = 144, durationMs = 187_000L, contentLengthBytes = 3_000_000L,
            mimeType = "audio/mp4",
        )
        assertRejected(directStream().copy(sabrBootstrap = sabr), "segmented SABR")
    }

    @Test
    fun rejectsMandatoryBoundedRangeIndependently() = runTest {
        assertRejected(directStream().copy(requireBoundedRange = true), "byte-range-only")
    }

    @Test
    fun mapsEveryPlaybackQualityAndLowDownloadWithoutMp4Retry() = runTest {
        for ((quality, expected) in listOf(
            AudioQuality.AUTO to LibraryQuality.AUTO,
            AudioQuality.LOW to LibraryQuality.LOW,
            AudioQuality.HIGH to LibraryQuality.HIGH,
            AudioQuality.HIGHEST to LibraryQuality.HIGH,
        )) {
            val extractor = RecordingExtractor { directStream() }
            InnerTuneXStreamRepository(extractor).resolve(request(quality))
            assertEquals(listOf(expected), extractor.qualities)
        }
        val lowDownload = RecordingExtractor { directStream() }
        InnerTuneXStreamRepository(lowDownload).resolve(request(AudioQuality.LOW, StreamPurpose.DOWNLOAD))
        assertEquals(listOf(LibraryQuality.LOW), lowDownload.qualities)
    }

    @Test
    fun downloadMp4SuccessDoesNotRequestAnotherQuality() = runTest {
        val extractor = RecordingExtractor { directStream() }
        InnerTuneXStreamRepository(extractor).resolve(request(AudioQuality.HIGHEST, StreamPurpose.DOWNLOAD))
        assertEquals(listOf(LibraryQuality.MP4), extractor.qualities)
    }

    @Test
    fun downloadMp4MissOrFailureRetriesRequestedQualityExactlyOnce() = runTest {
        for (quality in listOf(AudioQuality.AUTO, AudioQuality.HIGH, AudioQuality.HIGHEST)) {
            for (throws in listOf(false, true)) {
                val extractor = RecordingExtractor { selected ->
                    if (selected == LibraryQuality.MP4) {
                        if (throws) throw IOException("fixture network")
                        null
                    } else directStream()
                }
                InnerTuneXStreamRepository(extractor).resolve(request(quality, StreamPurpose.DOWNLOAD))
                val expected = if (quality == AudioQuality.AUTO) LibraryQuality.AUTO else LibraryQuality.HIGH
                assertEquals(listOf(LibraryQuality.MP4, expected), extractor.qualities)
            }
        }
        val missing = RecordingExtractor { null }
        val failure = failureOf {
            InnerTuneXStreamRepository(missing).resolve(request(AudioQuality.HIGH, StreamPurpose.DOWNLOAD))
        }
        assertTrue(failure is InnerTuneXExtractionException)
        assertEquals(listOf(LibraryQuality.MP4, LibraryQuality.HIGH), missing.qualities)
    }

    @Test
    fun cancelledMp4ExtractionNeverRetriesRequestedQuality() = runTest {
        val cancellation = CancellationException("fixture extraction cancelled")
        val extractor = RecordingExtractor { throw cancellation }
        assertSame(cancellation, failureOf {
            InnerTuneXStreamRepository(extractor).resolve(request(AudioQuality.HIGH, StreamPurpose.DOWNLOAD))
        })
        assertEquals(listOf(LibraryQuality.MP4), extractor.qualities)
    }

    @Test
    fun callerCancellationDuringMp4ExtractionStopsWithoutRetry() = runTest {
        var released = false
        val extractor = RecordingExtractor {
            try { awaitCancellation() } finally { released = true }
        }
        val repository = InnerTuneXStreamRepository(extractor)
        val job = launch { repository.resolve(request(AudioQuality.HIGH, StreamPurpose.DOWNLOAD)) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(released)
        assertEquals(listOf(LibraryQuality.MP4), extractor.qualities)
    }

    @Test
    fun successfulPrewarmRunsOnlyOnce() = runTest {
        val extractor = RecordingExtractor { directStream() }
        val repository = InnerTuneXStreamRepository(extractor)
        repository.resolve(request())
        repository.resolve(request())
        assertEquals(1, extractor.prewarmCalls)
        assertEquals(2, extractor.qualities.size)
    }

    @Test
    fun failedPrewarmStillExtractsAndRetriesWarmOnNextResolve() = runTest {
        val extractor = RecordingExtractor { directStream() }
        extractor.warm = { if (extractor.prewarmCalls == 1) throw IOException("fixture warm failure") }
        val repository = InnerTuneXStreamRepository(extractor)
        repository.resolve(request())
        repository.resolve(request())
        repository.resolve(request())
        assertEquals(2, extractor.prewarmCalls)
        assertEquals(3, extractor.qualities.size)
    }

    @Test
    fun cancelledPrewarmDoesNotExtractAndCanRetry() = runTest {
        val extractor = RecordingExtractor { directStream() }
        extractor.warm = { awaitCancellation() }
        val repository = InnerTuneXStreamRepository(extractor)
        val job = launch { repository.resolve(request()) }
        runCurrent()
        assertEquals(1, extractor.prewarmCalls)
        assertTrue(extractor.qualities.isEmpty())
        job.cancel()
        job.join()
        extractor.warm = {}
        repository.resolve(request())
        repository.resolve(request())
        assertEquals(2, extractor.prewarmCalls)
        assertEquals(2, extractor.qualities.size)
    }

    @Test
    fun missingOptionalFieldsUseExistingDefaultsAndSixHourExpiry() = runTest {
        val target = directStream().copy(
            expiresAt = null, contentLengthBytes = null, mimeType = null, codecs = null,
            bitrate = null, sampleRate = null, loudnessDb = null,
        )
        val before = System.currentTimeMillis()
        val result = InnerTuneXStreamRepository(RecordingExtractor { target }).resolve(request())
        val after = System.currentTimeMillis()
        assertTrue(result.expiresAtMs in (before + 21_600_000L)..(after + 21_600_000L))
        assertEquals("", result.mimeType)
        assertEquals("", result.codecs)
        assertEquals(0, result.bitrate)
        assertEquals(0L, result.contentLength)
        assertNull(result.sampleRate)
        assertNull(result.title)
        assertNull(result.durationSeconds)
        assertNull(result.loudnessDb)
        assertNull(result.perceptualLoudnessDb)
    }

    private suspend fun assertRejected(stream: ExtractedStream, message: String) {
        val failure = failureOf { InnerTuneXStreamRepository(RecordingExtractor { stream }).resolve(request()) }
        assertTrue(failure is InnerTuneXExtractionException)
        assertTrue(failure.message.orEmpty().contains(message))
    }

    private suspend fun failureOf(block: suspend () -> Any?): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected failure")
    }

    private fun request(
        quality: AudioQuality = AudioQuality.HIGH,
        purpose: StreamPurpose = StreamPurpose.PLAYBACK,
    ) = AudioStreamRequest(
        mediaId = "fixture-video", playlistId = "fixture-playlist", quality = quality,
        networkMetered = false, purpose = purpose, authState = PlaybackAuthState.EMPTY,
    )

    private fun directStream() = ExtractedStream(
        videoId = "fixture-video", audioUrl = "https://media.example/audio?itag=140",
        headers = mapOf("User-Agent" to "fixture-agent", "Referer" to "https://www.youtube.com/"),
        loudnessDb = -7.5, expiresAt = Instant.fromEpochMilliseconds(4_000_000_000_000L),
        contentLengthBytes = 3_000_000L, itag = 140, mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        codecs = "mp4a.40.2", bitrate = 128_000, sampleRate = 44_100,
        clientName = "fixture-client", profileId = "fixture-profile",
        requireBoundedRange = false, rangeChunkSizeBytes = 0L,
    )

    private class RecordingExtractor(
        val result: suspend (LibraryQuality) -> ExtractedStream?,
    ) : StreamExtractor {
        var prewarmCalls = 0
        var warm: suspend () -> Unit = {}
        val qualities = mutableListOf<LibraryQuality>()
        val videoIds = mutableListOf<String>()
        val hints = mutableListOf<ContentHints>()
        val excludedClients = mutableListOf<Set<String>>()

        override suspend fun prewarm() {
            prewarmCalls++
            warm()
        }

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: LibraryQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            videoIds += videoId
            this.hints += hints
            this.excludedClients += excludedClients
            qualities += audioQuality
            return result(audioQuality)
        }
    }
}
