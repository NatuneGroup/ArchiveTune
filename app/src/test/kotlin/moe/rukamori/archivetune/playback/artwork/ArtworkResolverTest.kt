/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.playback.artwork

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArtworkResolverTest {
    private var nowMs = 0L

    private fun request(
        mediaId: String = "song-1",
        originalArtworkUrl: String? = null,
        isLocal: Boolean = false,
    ) = ArtworkRequest(
        mediaId = mediaId,
        title = "Song Title",
        artists = listOf("Artist"),
        album = "Album",
        isrc = "USABC1234567",
        durationMs = 200_000L,
        originalArtworkUrl = originalArtworkUrl,
        isLocal = isLocal,
    )

    private fun tidalMatch(confidence: Float) =
        TidalArtworkMatch(
            trackId = "tidal-42",
            releaseId = "release-7",
            artworkUrl = "https://resources.tidal.com/images/ab/cd/1080x1080.jpg",
            matchMethod = "SEARCH",
            confidence = confidence,
        )

    private fun resolver(
        settings: MutableStateFlow<ArtworkSettings>,
        fetcher: TidalArtworkFetcher,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = ArtworkResolver(
        tidalFetcher = fetcher,
        settings = settings,
        ioDispatcher = dispatcher,
        clock = { nowMs },
    )

    @Test
    fun originalMetadataAlwaysWinsAndTidalIsNeverQueried() = runTest {
        // Priority is defined by configuration, not by which request would finish first:
        // even a hypothetical instant Tidal response must not replace original artwork.
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val fetchCalls = AtomicInteger(0)
        val resolver =
            resolver(settings, { fetchCalls.incrementAndGet(); tidalMatch(1f) }, StandardTestDispatcher(testScheduler))

        val resolved = resolver.resolve(request(originalArtworkUrl = "https://i.ytimg.com/vi/x/hq.jpg"))

        assertEquals(ArtworkProvider.ORIGINAL_METADATA, resolved.provider)
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", resolved.url)
        assertEquals(0, fetchCalls.get())
    }

    @Test
    fun localEmbeddedArtworkAlwaysWins() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val resolver =
            resolver(settings, { tidalMatch(1f) }, StandardTestDispatcher(testScheduler))

        val resolved =
            resolver.resolve(request(originalArtworkUrl = "content://media/external/audio/albumart/1", isLocal = true))

        assertEquals(ArtworkProvider.LOCAL_EMBEDDED, resolved.provider)
    }

    @Test
    fun tidalFallbackIsUsedWhenNoOriginalArtwork() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val resolver =
            resolver(settings, { tidalMatch(0.9f) }, StandardTestDispatcher(testScheduler))

        val resolved = resolver.resolve(request())

        assertEquals(ArtworkProvider.TIDAL, resolved.provider)
        assertEquals("https://resources.tidal.com/images/ab/cd/1080x1080.jpg", resolved.url)
        assertEquals(0.9f, resolved.matchConfidence!!, 0.001f)
    }

    @Test
    fun lowConfidenceTidalMatchIsRejected() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val resolver =
            resolver(settings, { tidalMatch(0.10f) }, StandardTestDispatcher(testScheduler))

        val resolved = resolver.resolve(request())

        assertEquals(ArtworkProvider.ORIGINAL_METADATA, resolved.provider)
        assertNull(resolved.url)
    }

    @Test
    fun disabledProviderCannotPublishLateResult() = runTest {
        // Tidal request starts while enabled, the user disables it mid-flight, then the
        // request completes: the result must be discarded, never applied.
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val fetcher =
            TidalArtworkFetcher {
                // Simulate the preference changing while the network request is in flight.
                settings.value = ArtworkSettings(tidalArtworkEnabled = false, tidalAvailable = true)
                tidalMatch(0.95f)
            }
        val resolver = resolver(settings, fetcher, StandardTestDispatcher(testScheduler))

        val resolved = resolver.resolve(request())

        assertEquals(ArtworkProvider.ORIGINAL_METADATA, resolved.provider)
        assertNull(resolved.url)
    }

    @Test
    fun disabledProviderNeverStartsARequest() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = false, tidalAvailable = true))
        val fetchCalls = AtomicInteger(0)
        val resolver =
            resolver(settings, { fetchCalls.incrementAndGet(); tidalMatch(1f) }, StandardTestDispatcher(testScheduler))

        val resolved = resolver.resolve(request())

        assertEquals(0, fetchCalls.get())
        assertNull(resolved.url)
    }

    @Test
    fun rapidTrackChangeInvalidatesOlderGeneration() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val resolver = resolver(settings, { tidalMatch(1f) }, StandardTestDispatcher(testScheduler))

        val genA = resolver.beginTrack("track-A")
        // The user skips to track B while track A's request is still in flight.
        val genB = resolver.beginTrack("track-B")

        assertFalse("track A must never overwrite track B", resolver.isCurrent("track-A", genA))
        assertTrue(resolver.isCurrent("track-B", genB))
        assertFalse(resolver.isCurrent("track-B", genA))
    }

    @Test
    fun concurrentResolvesShareOneFetch() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val fetchCalls = AtomicInteger(0)
        val fetcher =
            TidalArtworkFetcher {
                fetchCalls.incrementAndGet()
                Thread.sleep(50)
                tidalMatch(0.9f)
            }
        val resolver = resolver(settings, fetcher, kotlinx.coroutines.Dispatchers.IO)

        val first = async { resolver.resolve(request()) }
        val second = async { resolver.resolve(request()) }
        val (firstResult, secondResult) = first.await() to second.await()

        assertEquals("single-flight: only one network fetch", 1, fetchCalls.get())
        assertEquals(firstResult, secondResult)
    }

    @Test
    fun successfulResolutionIsCached() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val fetchCalls = AtomicInteger(0)
        val resolver =
            resolver(settings, { fetchCalls.incrementAndGet(); tidalMatch(0.9f) }, StandardTestDispatcher(testScheduler))

        resolver.resolve(request())
        resolver.resolve(request())

        assertEquals(1, fetchCalls.get())
    }

    @Test
    fun transientFailureIsOnlyCachedBriefly() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val fetchCalls = AtomicInteger(0)
        val fetcher =
            TidalArtworkFetcher {
                fetchCalls.incrementAndGet()
                throw java.io.IOException("network down")
            }
        val resolver = resolver(settings, fetcher, StandardTestDispatcher(testScheduler))

        resolver.resolve(request())
        resolver.resolve(request()) // inside the short failure window
        assertEquals(1, fetchCalls.get())

        // After the short failure window the resolver must try again: transient errors must
        // never permanently poison the artwork cache.
        nowMs += ArtworkResolver.FAILURE_CACHE_MS + 1
        resolver.resolve(request())
        assertEquals(2, fetchCalls.get())
    }

    @Test
    fun cancellationExceptionIsPreserved() = runTest {
        val settings =
            MutableStateFlow(ArtworkSettings(tidalArtworkEnabled = true, tidalAvailable = true))
        val resolver =
            resolver(
                settings,
                { throw CancellationException("cancelled") },
                StandardTestDispatcher(testScheduler),
            )

        try {
            resolver.resolve(request())
            fail("CancellationException must be rethrown, not swallowed")
        } catch (error: CancellationException) {
            // expected
        }
    }
}
