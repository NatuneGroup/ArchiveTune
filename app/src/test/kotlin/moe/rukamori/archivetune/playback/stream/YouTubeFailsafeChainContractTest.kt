/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.utils.YTPlayerUtils
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YouTubeFailsafeChainContractTest {
    @Test
    fun invalidLoginContextIsTerminalWithOriginalErrorAndZeroFallbackCalls() = runTest {
        val error = YTPlayerUtils.InvalidPlaybackLoginContextException(
            "fixture-video", "https://music.youtube.com/watch?v=fixture-video", IOException("fixture cause"),
        )
        assertTerminal(error)
    }

    @Test
    fun loginRequiredIsTerminalWithOriginalErrorAndZeroFallbackCalls() = runTest {
        val error = YTPlayerUtils.LoginRequiredForPlaybackException(
            "fixture-video", "https://music.youtube.com/watch?v=fixture-video", "Fixture login required",
        )
        assertTerminal(error)
    }

    @Test
    fun primaryCancellationIsTerminalBeforeAnyFallback() = runTest {
        assertTerminal(CancellationException("fixture primary cancelled"))
    }

    @Test
    fun innerTubeXSuccessSkipsNewPipeEchoAndExternalPlugin() = runTest {
        val calls = mutableListOf<String>()
        val result = resolved()
        val resolver = resolver(
            innerTubeX = repository { calls += "innertubex"; result },
            newPipe = repository { calls += "newpipe"; error("Must not run") },
        )
        val actual = recover(resolver) { calls += "echo"; error("Must not run") }
        assertSame(result, actual)
        assertEquals(listOf("innertubex"), calls)
    }

    @Test
    fun networkFailureFallsThroughToNewPipeWithoutEcho() = runTest {
        val calls = mutableListOf<String>()
        val request = request()
        val result = resolved(StreamSource.NEWPIPE)
        val resolver = resolver(
            innerTubeX = repository {
                assertSame(request, it)
                calls += "innertubex"
                throw IOException("fixture network")
            },
            newPipe = repository {
                assertSame(request, it)
                calls += "newpipe"
                result
            },
        )
        val actual = recover(resolver, request) { calls += "echo"; error("Must not run") }
        assertSame(result, actual)
        assertEquals(listOf("innertubex", "newpipe"), calls)
    }

    @Test
    fun echoIsLastAfterNetworkAndUnavailableFailures() = runTest {
        val calls = mutableListOf<String>()
        val result = resolved()
        val resolver = resolver(
            innerTubeX = repository { calls += "innertubex"; throw IOException("fixture network") },
            newPipe = repository { calls += "newpipe"; throw IllegalStateException("fixture unavailable") },
        )
        assertSame(result, recover(resolver) { calls += "echo"; result })
        assertEquals(listOf("innertubex", "newpipe", "echo"), calls)
    }

    @Test
    fun allFallbackFailuresRethrowExactOriginalPrimaryError() = runTest {
        for (status in listOf(403, 404, 410, 416)) {
            val primary = IOException("Fixture primary HTTP $status")
            val resolver = resolver(
                innerTubeX = repository { throw IOException("fixture network") },
                newPipe = repository { throw IllegalStateException("fixture unavailable") },
            )
            assertSame(primary, failureOf {
                recover(resolver, primary = primary) { throw IOException("fixture echo failure") }
            })
            assertTrue(primary.suppressed.isEmpty())
        }
    }

    @Test
    fun nullEchoResultAlsoPreservesOriginalPrimaryError() = runTest {
        val primary = IOException("fixture primary")
        val resolver = resolver(
            innerTubeX = repository { throw IOException("fixture network") },
            newPipe = repository { throw IllegalStateException("fixture unavailable") },
        )
        assertSame(primary, failureOf { recover(resolver, primary = primary) { null } })
    }

    @Test
    fun eachExtractorHasTwelveSecondsAndEchoHasTwentySeconds() = runTest {
        val calls = mutableListOf<Pair<String, Long>>()
        val primary = IOException("fixture primary")
        val resolver = resolver(
            innerTubeX = repository {
                calls += "innertubex" to testScheduler.currentTime
                awaitCancellation()
            },
            newPipe = repository {
                calls += "newpipe" to testScheduler.currentTime
                awaitCancellation()
            },
        )
        var failure: Throwable? = null
        val job = launch {
            failure = failureOf {
                recover(resolver, primary = primary) {
                    calls += "echo" to testScheduler.currentTime
                    awaitCancellation()
                }
            }
        }
        runCurrent()
        assertEquals(listOf("innertubex" to 0L), calls)
        advanceTimeBy(11_999L)
        runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("innertubex" to 0L, "newpipe" to 12_000L), calls)
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals("echo" to 24_000L, calls.last())
        advanceTimeBy(19_999L)
        runCurrent()
        assertTrue(job.isActive)
        advanceTimeBy(1L)
        runCurrent()
        job.join()
        assertSame(primary, failure)
        assertEquals(44_000L, testScheduler.currentTime)
    }

    @Test
    fun callerCancellationDuringExtractorStopsBeforeLaterWork() = runTest {
        val calls = mutableListOf<String>()
        var released = false
        val resolver = resolver(
            innerTubeX = repository {
                calls += "innertubex"
                try { awaitCancellation() } finally { released = true }
            },
            newPipe = repository { calls += "newpipe"; resolved() },
        )
        val job = launch { recover(resolver) { calls += "echo"; resolved() } }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(released)
        assertEquals(listOf("innertubex"), calls)
    }

    @Test
    fun callerCancellationDuringEchoPropagatesAndReleasesWork() = runTest {
        var released = false
        val resolver = resolver(
            innerTubeX = repository { throw IOException("fixture network") },
            newPipe = repository { throw IllegalStateException("fixture unavailable") },
        )
        val job = launch {
            recover(resolver) {
                try { awaitCancellation() } finally { released = true }
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(released)
    }

    @Test
    fun tierOwnedTimeoutRemainsRecoverableWhenCallerIsActive() = runTest {
        val result = resolved(StreamSource.NEWPIPE)
        var echoCalls = 0
        val resolver = resolver(
            innerTubeX = repository { withTimeout(100L) { awaitCancellation() } },
            newPipe = repository { result },
        )
        assertSame(result, recover(resolver) { echoCalls++; null })
        assertEquals(100L, testScheduler.currentTime)
        assertEquals(0, echoCalls)
    }

    @Test
    fun actualResolverCacheSeparatesAuthPurposeQualityMeteringAndPinnedFormat() = runTest {
        val calls = mutableListOf<AudioStreamRequest>()
        val resolver = ResolveAudioStreamUseCase(
            nativeRepository = repository { request ->
                calls += request
                resolved().copy(authFingerprint = request.authState.streamCacheFingerprint)
            },
            innerTuneXRepository = repository { error("Native success must stop fallback") },
            newPipeRepository = repository { error("Native success must stop fallback") },
            ytdlnisRepository = repository { error("Native success must stop fallback") },
            elapsedRealtime = { testScheduler.currentTime },
        )
        try {
            val base = request()
            val first = resolver(base)
            assertSame(first, resolver(base))
            val variants = listOf(
                base.copy(authState = PlaybackAuthState(visitorData = "fixture-other-session")),
                base.copy(purpose = StreamPurpose.DOWNLOAD),
                base.copy(quality = AudioQuality.LOW),
                base.copy(networkMetered = true),
                base.copy(pinnedFormatId = 140),
            )
            for (variant in variants) {
                assertNull(resolver.peek(variant))
                val result = resolver(variant)
                assertNotSame(first, result)
                assertSame(result, resolver.peek(variant))
            }
            assertEquals(listOf(base) + variants, calls)
        } finally {
            resolver.clear()
        }
    }

    private suspend fun assertTerminal(primary: Throwable) {
        var extractorCalls = 0
        var echoCalls = 0
        val message = primary.message
        assertSame(primary, failureOf {
            YouTubeFailsafeChain.recoverAfterPrimary<ResolvedAudioStream>(
                primaryFailure = primary,
                extractors = { extractorCalls++; null },
                echo = { echoCalls++; null },
            )
        })
        assertEquals(message, primary.message)
        assertEquals(0, extractorCalls)
        assertEquals(0, echoCalls)
    }

    private suspend fun recover(
        resolver: ResolveAudioStreamUseCase,
        request: AudioStreamRequest = request(),
        primary: Throwable = IOException("fixture shipped primary"),
        echo: suspend () -> ResolvedAudioStream?,
    ): ResolvedAudioStream = YouTubeFailsafeChain.recoverAfterPrimary(
        primaryFailure = primary,
        extractors = { timeout -> resolver.resolveShippedFallback(request, timeout) },
        echo = echo,
    )

    private fun resolver(
        innerTubeX: AudioStreamRepository,
        newPipe: AudioStreamRepository,
    ) = ResolveAudioStreamUseCase(
        nativeRepository = repository { error("Shipped recovery must not repeat the primary") },
        innerTuneXRepository = innerTubeX,
        newPipeRepository = newPipe,
        ytdlnisRepository = repository { error("External plugin must stay out of shipped recovery") },
        elapsedRealtime = { 0L },
    )

    private fun repository(block: suspend (AudioStreamRequest) -> ResolvedAudioStream) =
        object : AudioStreamRepository {
            override suspend fun resolve(request: AudioStreamRequest) = block(request)
        }

    private fun request() = AudioStreamRequest(
        mediaId = "fixture-video", playlistId = "fixture-playlist", quality = AudioQuality.HIGH,
        networkMetered = false, purpose = StreamPurpose.PLAYBACK, authState = PlaybackAuthState.EMPTY,
    )

    private fun resolved(source: StreamSource = StreamSource.INNERTUBE_X) = ResolvedAudioStream(
        url = "https://media.example/audio", requestHeaders = emptyMap(), formatId = 140,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128_000, sampleRate = 44_100,
        contentLength = 3_000_000L, expiresAtMs = 4_000_000_000_000L,
        authFingerprint = PlaybackAuthState.EMPTY.streamCacheFingerprint, source = source,
    )

    private suspend fun failureOf(block: suspend () -> Any?): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected failure")
    }
}
