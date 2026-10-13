/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tag filters decide which release a channel may offer. The stable filter must never pick a
 * pre-release tag (Nightly N…, retired Canary C…) or the `update-feed` manifest release; the
 * nightly filter accepts only `N\d{8}(\d{4})?` tags.
 */
class ReleaseTagFilterTest {
    private fun release(
        tag: String,
        publishedAt: String = "2026-10-13T00:00:00Z",
    ): ReleaseInfo =
        ReleaseInfo(
            tagName = tag,
            name = tag,
            body = null,
            publishedAt = publishedAt,
            htmlUrl = "https://github.com/NatuneGroup/ArchiveTune/releases/tag/$tag",
        )

    @Test
    fun stableFilterRejectsNightlyCanaryAndManifestTags() {
        val releases =
            listOf(
                release("N202610131530"),
                release("C202610121610"),
                release("update-feed"),
                release("v15.0.0"),
            )

        assertEquals("v15.0.0", Updater.findLatestRelease(releases)?.tagName)
    }

    @Test
    fun stableFilterReturnsNullWhenOnlyRejectedTagsExist() {
        val releases =
            listOf(
                release("N202610131530"),
                release("C202610121610"),
                release("update-feed"),
            )

        assertNull(Updater.findLatestRelease(releases))
    }

    @Test
    fun stableFilterAcceptsSemverWithoutVPrefix() {
        val releases = listOf(release("v14.5.0"), release("update-feed"), release("15.0.0", publishedAt = "2026-10-13T01:00:00Z"))

        assertEquals("15.0.0", Updater.findLatestRelease(releases)?.tagName)
    }

    @Test
    fun nightlyFilterAcceptsBothTagFormsAndPicksTheNewest() {
        val releases =
            listOf(
                release("C202610121610"),
                release("update-feed"),
                release("v15.0.0"),
                release("N20261013"),
                release("N202610131530"),
            )

        assertEquals("N202610131530", Updater.findLatestPreRelease(releases, Updater.PreChannel.NIGHTLY)?.tagName)
    }

    @Test
    fun nightlyFilterRejectsCanaryManifestAndStableTags() {
        val releases =
            listOf(
                release("C202610121610"),
                release("update-feed"),
                release("v15.0.0"),
            )

        assertNull(Updater.findLatestPreRelease(releases, Updater.PreChannel.NIGHTLY))
    }
}
