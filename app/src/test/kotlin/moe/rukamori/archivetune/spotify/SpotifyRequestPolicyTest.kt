/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.spotify.models.SpotifySimpleArtist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyRequestPolicyTest {
    @Test
    fun completeGraphQlSearchMetadataDoesNotRequireRestForAnOptionalIsrc() {
        val track = completeTrack()

        assertNull(track.isrc)
        assertFalse(track.needsRestMetadata())
        assertTrue(track.copy(name = "").needsRestMetadata())
        assertTrue(track.copy(artists = emptyList()).needsRestMetadata())
        assertTrue(track.copy(durationMs = 0).needsRestMetadata())
    }

    @Test
    fun successfulGraphQlRequestDoesNotDispatchTheRestFallback() = runTest {
        var restCalls = 0
        val value = spotifyGraphQlWithRestFallback(graphQl = { "profile" }, rest = { restCalls++; "rest" })

        assertEquals("profile", value)
        assertEquals(0, restCalls)
    }

    @Test
    fun rateLimitedAndUnauthorizedGraphQlRequestsDoNotDispatchRest() = runTest {
        for (status in listOf(401, 403, 429)) {
            var restCalls = 0
            val error = Spotify.SpotifyException(status, "Synthetic error", retryAfterSec = 60)
            val result = spotifyRequestResult {
                spotifyGraphQlWithRestFallback(graphQl = { throw error }, rest = { restCalls++; "rest" })
            }

            assertSame(error, result.exceptionOrNull())
            assertEquals(0, restCalls)
        }
    }

    @Test
    fun unsupportedPersistedQueriesKeepOneRestFallback() = runTest {
        var restCalls = 0
        val value = spotifyGraphQlWithRestFallback(
            graphQl = { throw Spotify.SpotifyException(412, "Synthetic missing query") },
            rest = { restCalls++; "rest" },
        )

        assertEquals("rest", value)
        assertEquals(1, restCalls)
    }

    @Test
    fun cancellationEscapesResultsWithoutDispatchingRest() = runTest {
        var restCalls = 0
        val cancellation = CancellationException("Synthetic cancellation")
        try {
            spotifyRequestResult {
                spotifyGraphQlWithRestFallback(graphQl = { throw cancellation }, rest = { restCalls++; "rest" })
            }
            throw AssertionError("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(0, restCalls)
    }

    @Test
    fun cooldownHonorsRetryAfterAndExpiresWithoutShorteningEarlierDeadlines() {
        var now = 1_000_000L
        val cooldown = SpotifyRequestCooldown { now }

        assertEquals(0L, cooldown.remainingSeconds())
        assertEquals(120L, cooldown.recordRateLimit("120"))
        now += 1_000L
        assertEquals(119L, cooldown.recordRateLimit("1"))
        now += 119_000L
        assertEquals(0L, cooldown.remainingSeconds())
    }

    @Test
    fun restAndGraphQlUseIndependentCooldownsWithBoundedFallbacks() {
        val graphQl = SpotifyRequestCooldown { 1_000_000L }
        val rest = SpotifyRequestCooldown { 1_000_000L }

        assertEquals(30L, graphQl.recordRateLimit(null))
        assertEquals(0L, rest.remainingSeconds())
        assertEquals(86_400L, rest.recordRateLimit(Long.MAX_VALUE.toString()))
    }

    @Test
    fun retryAfterSupportsSecondsAndHttpDatesWithoutRetryingEarly() {
        val now = 1_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 120_000L).atZone(ZoneOffset.UTC))

        assertEquals(120L, spotifyRetryAfterSeconds("120", now))
        assertEquals(120L, spotifyRetryAfterSeconds(date, now))
        assertEquals(121L, spotifyRetryAfterSeconds(date, now - 1L))
        assertNull(spotifyRetryAfterSeconds("-1", now))
        assertNull(spotifyRetryAfterSeconds("invalid", now))
    }

    @Test
    fun explicitQuotaExceededResponsesAreDistinguishedFromTransientThrottles() {
        assertTrue(isSpotifyQuotaExceededResponse("""{"error":{"status":429,"reason":"QUOTA_EXCEEDED"}}"""))
        assertFalse(isSpotifyQuotaExceededResponse("""{"error":{"status":429,"message":"Too many requests"}}"""))
        assertFalse(isSpotifyQuotaExceededResponse("""{"error":{"status":403,"reason":"QUOTA_EXCEEDED"}}"""))
        assertFalse(isSpotifyQuotaExceededResponse("not JSON"))
    }

    @Test
    fun quotaReasonRemainsVisibleDuringTheGateAndExpiresWithIt() {
        var now = 1_000_000L
        val cooldown = SpotifyRequestCooldown { now }
        cooldown.recordRateLimit("60", quotaExceeded = true)
        assertTrue(cooldown.quotaExceeded)
        now += 1_000L
        cooldown.recordRateLimit("1")
        assertTrue(cooldown.quotaExceeded)
        now += 59_000L
        assertFalse(cooldown.quotaExceeded)
        cooldown.recordRateLimit("30")
        assertFalse(cooldown.quotaExceeded)
    }

    @Test
    fun quotaExhaustionDoesNotScheduleTheShortHistoryRetry() {
        assertNull(historyRetryWaitMillis(30, hasCachedRows = false, quotaExceeded = true))
        assertEquals(30_000L, historyRetryWaitMillis(30, hasCachedRows = false))
    }

    @Test
    fun reportedExplicitMetadataDoesNotDependOnRestHydration() {
        assertTrue(spotifyReportedExplicit(json("""{"contentRating":{"label":"EXPLICIT"}}""")))
        assertTrue(spotifyReportedExplicit(json("""{"explicit":true}""")))
        assertTrue(spotifyReportedExplicit(json("""{"explicit":false,"contentRating":{"label":"EXPLICIT"}}""")))
        assertFalse(spotifyReportedExplicit(json("""{"contentRating":{"label":"NONE"}}""")))
        assertFalse(spotifyReportedExplicit(json("{}")))
    }

    @Test
    fun reportedRecordingIdentifiersRemainReportedRatherThanGuessed() {
        assertEquals("USAAA2600001", spotifyReportedExternalIds(json("""{"externalIds":{"isrc":"USAAA2600001"}}"""))?.isrc)
        assertEquals("USAAA2600001", spotifyReportedExternalIds(json("""{"external_ids":{"isrc":"USAAA2600001"}}"""))?.isrc)
        assertNull(spotifyReportedExternalIds(json("{}")))
        assertNull(spotifyReportedExternalIds(json("""{"externalIds":{"isrc":null}}""")))
        assertNull(spotifyReportedExternalIds(json("""{"externalIds":{"isrc":123}}""")))
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun completeTrack() = SpotifyTrack(
        id = "synthetic-track",
        name = "Synthetic song",
        artists = listOf(SpotifySimpleArtist(name = "Synthetic artist")),
        durationMs = 180_000,
    )
}
