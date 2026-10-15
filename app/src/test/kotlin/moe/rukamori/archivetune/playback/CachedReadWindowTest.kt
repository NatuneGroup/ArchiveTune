/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedReadWindowTest {
    @Test
    fun seekIntoSecondHalfKeepsItsPositionAndEndsAtEndOfFile() {
        val contentLength = 30_000_000L
        val seekPosition = 20_000_000L

        val window =
            resolveCachedReadWindow(
                position = seekPosition,
                requestedLength = -1L,
                knownContentLength = contentLength,
            )

        assertEquals(seekPosition, window?.position)
        assertEquals(contentLength, (window?.position ?: 0L) + (window?.length ?: 0L))
    }

    @Test
    fun openFromStartCoversWholeFile() {
        val window =
            resolveCachedReadWindow(
                position = 0L,
                requestedLength = -1L,
                knownContentLength = 4_096L,
            )

        assertEquals(CachedReadWindow(position = 0L, length = 4_096L), window)
    }

    @Test
    fun explicitRequestLengthWinsOverKnownContentLength() {
        val window =
            resolveCachedReadWindow(
                position = 1_000L,
                requestedLength = 256L,
                knownContentLength = 4_096L,
            )

        assertEquals(CachedReadWindow(position = 1_000L, length = 256L), window)
    }

    @Test
    fun zeroLengthRequestDoesNotExpandToTheKnownContentLength() {
        assertNull(
            resolveCachedReadWindow(
                position = 0L,
                requestedLength = 0L,
                knownContentLength = 4_096L,
            ),
        )
    }

    @Test
    fun unboundedSeekWithoutRecordedLengthHasNoWindow() {
        val window =
            resolveCachedReadWindow(
                position = 512L,
                requestedLength = -1L,
                knownContentLength = null,
            )

        assertNull(window)
    }

    @Test
    fun unboundedReadWithoutRecordedLengthHasNoWindow() {
        assertNull(
            resolveCachedReadWindow(
                position = 0L,
                requestedLength = -1L,
                knownContentLength = null,
            ),
        )
    }

    @Test
    fun positionAtOrPastKnownEndReturnsNoWindow() {
        assertNull(
            resolveCachedReadWindow(
                position = 4_096L,
                requestedLength = -1L,
                knownContentLength = 4_096L,
            ),
        )
    }

    @Test
    fun staleShorterLengthIsWidenedToTheLengthTheCacheRecorded() {
        val staleWindow = CachedReadWindow(position = 0L, length = 4_000_000L)

        val widened = staleWindow.coveringRecordedLength(recordedContentLength = 30_000_000L, explicitRequest = false)

        assertEquals(CachedReadWindow(position = 0L, length = 30_000_000L), widened)
    }

    @Test
    fun wideningKeepsThePositionOfASeek() {
        val window = CachedReadWindow(position = 10_000_000L, length = 2_000_000L)

        val widened = window.coveringRecordedLength(recordedContentLength = 30_000_000L, explicitRequest = false)

        assertEquals(CachedReadWindow(position = 10_000_000L, length = 20_000_000L), widened)
    }

    @Test
    fun wideningNeverShrinksOrOverridesAnExplicitRequest() {
        val window = CachedReadWindow(position = 0L, length = 4_000_000L)

        assertEquals(window, window.coveringRecordedLength(recordedContentLength = 1_000_000L, explicitRequest = false))
        assertEquals(window, window.coveringRecordedLength(recordedContentLength = 30_000_000L, explicitRequest = true))
    }

    @Test
    fun aHoleBeforeTheRequestedRangeIsNotCoverage() {
        val spans =
            listOf(
                CachedSpan(position = 0L, length = 1_000L, isCached = false),
                CachedSpan(position = 1_000L, length = 2_400_158L, isCached = true),
            )

        assertEquals(621_374L, continuousCachedLength(spans, position = 1_778_785L, requestedLength = 621_374L))
    }

    @Test
    fun aHoleInsideTheRequestedRangeStopsCoverageAtTheGap() {
        val spans =
            listOf(
                CachedSpan(position = 0L, length = 1_000_000L, isCached = true),
                CachedSpan(position = 1_000_000L, length = 1_400_159L, isCached = false),
            )

        assertEquals(500_000L, continuousCachedLength(spans, position = 500_000L, requestedLength = 1_900_000L))
    }

    @Test
    fun aSeekWhoseBytesAreOnlyAHoleIsNotCached() {
        val spans =
            listOf(
                CachedSpan(position = 0L, length = 1_000_000L, isCached = true),
                CachedSpan(position = 1_000_000L, length = 1_400_159L, isCached = false),
            )

        assertEquals(0L, continuousCachedLength(spans, position = 1_778_785L, requestedLength = 621_374L))
    }

    @Test
    fun aFullyCachedFileStillServesASeekIntoItsTail() {
        val spans = listOf(CachedSpan(position = 0L, length = 2_400_159L, isCached = true))

        assertEquals(621_374L, continuousCachedLength(spans, position = 1_778_785L, requestedLength = 621_374L))
    }

    @Test
    fun adjacentCachedSpansJoinIntoOneRun() {
        val spans =
            listOf(
                CachedSpan(position = 1_000L, length = 1_000L, isCached = true),
                CachedSpan(position = 0L, length = 1_000L, isCached = true),
            )

        assertEquals(2_000L, continuousCachedLength(spans, position = 0L, requestedLength = 4_000L))
    }

    @Test
    fun anOpenEndedSpanIsNotACoveredRange() {
        val spans = listOf(CachedSpan(position = 0L, length = -1L, isCached = true))

        assertEquals(0L, continuousCachedLength(spans, position = 0L, requestedLength = 4_000L))
    }

    @Test
    fun aRequestOfNothingIsNeverCovered() {
        val spans = listOf(CachedSpan(position = 0L, length = 2_400_159L, isCached = true))

        assertEquals(0L, continuousCachedLength(spans, position = 0L, requestedLength = 0L))
    }
}
