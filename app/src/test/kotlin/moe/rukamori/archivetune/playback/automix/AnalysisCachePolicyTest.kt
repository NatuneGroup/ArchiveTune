/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCachePolicyTest {
    @Test
    fun fileKeysAndFingerprintsSeparateSourcesAndRevisions() {
        val youtube = AnalysisCachePolicy.sourceFingerprint("track-1", "youtube", "revision-a")
        val tidal = AnalysisCachePolicy.sourceFingerprint("track-1", "tidal", "revision-a")
        val revised = AnalysisCachePolicy.sourceFingerprint("track-1", "youtube", "revision-b")

        assertNotEquals(youtube, tidal)
        assertNotEquals(youtube, revised)
        assertNotEquals(
            AnalysisCachePolicy.fileNameFor("track-1", youtube),
            AnalysisCachePolicy.fileNameFor("track-1", tidal),
        )
        assertNotEquals(
            AnalysisCachePolicy.fileNameFor("track-1", youtube),
            AnalysisCachePolicy.fileNameFor("track-2", youtube),
        )
        assertTrue(AnalysisCachePolicy.fileNameFor("track-1", youtube).matches(Regex("[0-9a-f]{64}\\.json")))
    }

    @Test
    fun reuseChecksSchemaIdentityDurationAndFreshness() {
        val now = 1_000_000_000L
        val fingerprint = AnalysisCachePolicy.sourceFingerprint("track-1", "youtube", "revision-a")
        val entry = AnalysisCacheEntryIdentity(
            schemaVersion = AnalysisCachePolicy.SCHEMA_VERSION,
            trackId = AnalysisCachePolicy.trackIdentity("track-1"),
            sourceFingerprint = fingerprint,
            durationSeconds = 180.0,
            savedAtEpochMs = now - 1_000L,
        )

        assertTrue(AnalysisCachePolicy.isReusable(entry, AnalysisCachePolicy.trackIdentity("track-1"), fingerprint, 181.0, now))
        assertFalse(AnalysisCachePolicy.isReusable(entry, AnalysisCachePolicy.trackIdentity("track-2"), fingerprint, 180.0, now))
        assertFalse(AnalysisCachePolicy.isReusable(entry, AnalysisCachePolicy.trackIdentity("track-1"), "other-source", 180.0, now))
        assertFalse(AnalysisCachePolicy.isReusable(entry.copy(schemaVersion = 1), AnalysisCachePolicy.trackIdentity("track-1"), fingerprint, 180.0, now))
        assertFalse(AnalysisCachePolicy.isReusable(entry.copy(savedAtEpochMs = now + 1), AnalysisCachePolicy.trackIdentity("track-1"), fingerprint, 180.0, now))
        assertFalse(
            AnalysisCachePolicy.isReusable(
                entry.copy(savedAtEpochMs = now - AnalysisCachePolicy.MAX_AGE_MILLIS - 1),
                AnalysisCachePolicy.trackIdentity("track-1"),
                fingerprint,
                180.0,
                now,
            ),
        )
        assertFalse(AnalysisCachePolicy.isReusable(entry, AnalysisCachePolicy.trackIdentity("track-1"), fingerprint, 184.0, now))
    }

    @Test
    fun pruningEnforcesLifetimeByteAndEntryLimits() {
        val now = 2_000_000_000L
        val newest = file("newest", now - 10L, 20L * 1024 * 1024)
        val older = file("older", now - 20L, 13L * 1024 * 1024)
        val expired = file("expired", now - AnalysisCachePolicy.MAX_AGE_MILLIS - 1, 1L)
        val oversized = file("oversized", now - 1L, AnalysisCachePolicy.MAX_BYTES + 1L)

        assertEquals(
            setOf(older.fileName, expired.fileName, oversized.fileName),
            AnalysisCachePolicy.filesToEvict(listOf(newest, older, expired, oversized), now),
        )

        val overEntryLimit = (0..AnalysisCachePolicy.MAX_ENTRIES).map { index ->
            file("entry-$index", now - index, 1L)
        }
        assertEquals(1, AnalysisCachePolicy.filesToEvict(overEntryLimit, now).size)
        assertTrue(AnalysisCachePolicy.filesToEvict(overEntryLimit, now).contains(file("entry-${AnalysisCachePolicy.MAX_ENTRIES}", now - AnalysisCachePolicy.MAX_ENTRIES, 1L).fileName))
    }

    @Test
    fun nonCacheFilesAreDiscardedDuringPruning() {
        val now = 3_000_000_000L
        val temporary = AnalysisCacheFileMetadata("temporary.json.tmp", now - 1L, 10L)
        val oldShortHash = AnalysisCacheFileMetadata("0123456789abcdef.json", now - 1L, 10L)

        assertEquals(setOf(temporary.fileName, oldShortHash.fileName), AnalysisCachePolicy.filesToEvict(listOf(temporary, oldShortHash), now))
    }

    private fun file(id: String, lastModifiedEpochMs: Long, sizeBytes: Long) =
        AnalysisCacheFileMetadata(
            fileName = AnalysisCachePolicy.fileNameFor(id, "fingerprint"),
            lastModifiedEpochMs = lastModifiedEpochMs,
            sizeBytes = sizeBytes,
        )
}
