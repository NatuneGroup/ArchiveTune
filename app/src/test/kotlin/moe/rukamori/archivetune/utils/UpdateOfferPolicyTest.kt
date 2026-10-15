/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.constants.UpdateChannel
import org.junit.Assert.*
import org.junit.Test

class UpdateOfferPolicyTest {
    private fun release(code: Int?, name: String = "999.0.0") = ReleaseInfo(
        tagName = "v$name", name = name, body = "notes", publishedAt = "", htmlUrl = "https://example.test/release",
        downloadUrl = "https://example.test/a.apk", versionCode = code,
    )

    @Test
    fun bothChannels_requireKnownStrictlyNewerCodeRegardlessOfName() {
        for (channel in UpdateChannel.entries) {
            for (code in listOf(null, -1, 0, 99, 100)) {
                assertNull("$channel/$code", Updater.eligibleOffer(release(code), channel, installedCode = 100))
            }
            val offer = Updater.eligibleOffer(release(101, "1.0.0"), channel, installedCode = 100)!!
            assertEquals(channel, offer.channel)
            assertEquals(101, offer.versionCode)
            assertEquals(UpdateEligibility.NEWER, updateEligibility(offer.versionCode, 100))
        }
        assertEquals(UpdateEligibility.UNKNOWN_BUILD_CODE, updateEligibility(null, 100))
        assertEquals(UpdateEligibility.UNKNOWN_BUILD_CODE, updateEligibility(0, 100))
        assertEquals(UpdateEligibility.NOT_NEWER, updateEligibility(100, 100))
        assertEquals(UpdateEligibility.NOT_NEWER, updateEligibility(99, 100))
    }

    @Test
    fun capturedOffer_keepsChannelNotesAndUrlWhenLatestReleaseChanges() {
        for (channel in UpdateChannel.entries) {
            var latest = release(101, "1.0.0")
            val captured = Updater.eligibleOffer(latest, channel, 100)!!
            latest = latest.copy(versionCode = 102, downloadUrl = "https://example.test/b.apk", body = "new notes")
            val newer = Updater.eligibleOffer(latest, channel, 100)!!
            assertEquals("https://example.test/a.apk", captured.downloadUrl)
            assertEquals("notes", captured.notes)
            assertEquals("${channel.name}|v1.0.0|101", captured.identity)
            assertNotEquals(captured.identity, newer.identity)
            assertNotEquals(captured.identity, captured.copy(channel = UpdateChannel.entries.first { it != channel }).identity)
        }
    }

    @Test
    fun archive_requiresReadableMatchingNewerCodeAndExactSource() {
        for (channel in UpdateChannel.entries) {
            val offer = UpdateOffer(channel, "tag", 101, "name", null, "https://example.test/a.apk")
            assertTrue(archiveMatchesOffer(offer.versionCode, 101L, 100, sourceMatches = true))
            for (archive in listOf(null, 0L, 99L, 100L, 102L)) {
                assertFalse(archiveMatchesOffer(offer.versionCode, archive, 100, sourceMatches = true))
            }
            assertFalse(archiveMatchesOffer(101, 101L, 100, sourceMatches = false))
            for (candidate in listOf(0, 99, 100)) {
                assertFalse(archiveMatchesOffer(candidate, candidate.toLong(), 100, sourceMatches = true))
            }
        }
    }

    @Test
    fun requestRevision_rejectsDelayedAAfterBAndAfterReturnToA() {
        val a = UpdateChannel.STABLE
        val b = UpdateChannel.NIGHTLY
        assertTrue(updateRequestIsCurrent(a, 10, a, 10))
        assertFalse(updateRequestIsCurrent(a, 10, b, 11))
        // Even a failed B lookup or return to A cannot make the original A completion current.
        assertFalse(updateRequestIsCurrent(a, 10, a, 12))
        assertFalse(updateRequestIsCurrent(b, 11, a, 12))
        assertTrue(updateRequestIsCurrent(a, 12, a, 12))
    }
}
