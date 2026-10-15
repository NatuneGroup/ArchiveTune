/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticSourcePolicyTest {
    private fun snapshot(
        mediaId: String = "B",
        source: AudioSourceType = AudioSourceType.TIDAL,
        key: String = "tidal:B",
        uri: String? = "https://audio.invalid/fixture",
        expires: Long = 1000,
        length: Long? = 100,
        rate: Int? = 48_000,
        lossless: Boolean = true,
    ) = AcousticSourceSnapshot(mediaId, source, key, uri, expires, length, rate, lossless)

    @Test
    fun selectedSongBDoesNotConsumePlayingSongASnapshot() {
        assertNull(AcousticSourcePolicy.selectedSnapshot("B", snapshot(mediaId = "A")))
        assertNull(AcousticSourcePolicy.selectedSnapshot("B", null))
        val selected = snapshot()
        assertSame(selected, AcousticSourcePolicy.selectedSnapshot("B", selected))
    }

    @Test
    fun completionRejectsChangedTrackRenditionAndSourceIdentity() {
        val captured = snapshot()
        assertTrue(captured.matches(snapshot()))
        assertFalse(captured.matches(null))
        listOf(
            snapshot(mediaId = "C"), snapshot(source = AudioSourceType.QOBUZ),
            snapshot(key = "replacement"), snapshot(uri = "https://audio.invalid/new"),
            snapshot(expires = 2000), snapshot(length = 101), snapshot(rate = 44_100),
            snapshot(lossless = false),
        ).forEach { assertFalse(captured.matches(it)) }
    }

    @Test
    fun cacheMetadataMustMatchCurrentSourceLengthRateAndClaim() {
        val current = snapshot()
        assertTrue(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 100, 48_000, true, current))
        assertFalse(AcousticSourcePolicy.cacheMatches(AudioSourceType.QOBUZ, 100, 48_000, true, current))
        assertFalse(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 101, 48_000, true, current))
        assertFalse(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 100, 44_100, true, current))
        assertFalse(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 100, null, true, current))
        assertFalse(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 100, 48_000, false, current))
        assertFalse(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 0, 48_000, true, null))
        assertTrue(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 100, 48_000, true, null))
        assertTrue(AcousticSourcePolicy.cacheMatches(AudioSourceType.TIDAL, 100, 48_000, true,
            snapshot(length = null, rate = null)))
    }

    private fun extent(position: Long, length: Long) = AcousticCacheExtent(position, length, length, { true }, true)

    @Test
    fun onlyExactContiguousCompleteCacheExtentsAreAccepted() {
        val first = extent(0, 40)
        val second = extent(40, 60)
        fun complete(extents: List<AcousticCacheExtent>, length: Long = 100) =
            AcousticSourcePolicy.completeExtents(length, extents) {}
        assertTrue(complete(listOf(first, second)))
        assertFalse(complete(listOf(first, extent(41, 59))))
        assertFalse(complete(listOf(first, extent(39, 61))))
        assertFalse(complete(listOf(first)))
        assertFalse(complete(listOf(extent(1, 99))))
        assertFalse(complete(listOf(first, extent(40, 61))))
        assertFalse(complete(listOf(first, second.copy(fileLength = 59))))
        assertFalse(complete(listOf(first, second.copy(regular = { false }))))
        assertFalse(complete(listOf(first, second.copy(cached = false))))
        assertFalse(complete(listOf(extent(0, 0))))
        assertFalse(complete(emptyList()))
        assertFalse(complete(emptyList(), 0))
        assertFalse(complete(List(2049) { extent(it.toLong(), 1) }, 2049))
        assertTrue(complete(listOf(extent(0, Long.MAX_VALUE)), Long.MAX_VALUE))
        assertFalse(complete(listOf(extent(0, Long.MAX_VALUE), extent(Long.MAX_VALUE, 1)), Long.MAX_VALUE))
        var fileChecks = 0
        assertFalse(complete(listOf(extent(1, 99).copy(regular = { fileChecks++; true }))))
        org.junit.Assert.assertEquals(0, fileChecks)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsCheckedWhileValidatingCacheExtents() {
        AcousticSourcePolicy.completeExtents(100, listOf(extent(0, 100))) {
            throw CancellationException("fixture")
        }
    }
}
