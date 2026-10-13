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
 * The Updates screen's commit-history page is cached in DataStore (TTL/ETag, keyed to the branch).
 * The cache payload is written by [Updater]; here we pin the reader's format so an entry written
 * by a future build keeps parsing, and a corrupt one is a miss instead of a crash.
 */
class CommitHistoryCacheTest {
    private val cachedJson =
        """
        [
          {
            "sha": "abc1234",
            "message": "feat(updates): keyless update feed",
            "author": "vossgraves",
            "date": "2026-10-13T15:30:00Z",
            "url": "https://github.com/NatuneGroup/ArchiveTune/commit/abc1234",
            "authorAvatarUrl": "https://avatars.githubusercontent.com/u/1?v=4"
          },
          {
            "sha": "def5678",
            "message": "fix(player): something else",
            "author": "rukamori",
            "date": "2026-10-12T10:00:00Z",
            "url": "https://github.com/NatuneGroup/ArchiveTune/commit/def5678"
          }
        ]
        """.trimIndent()

    @Test
    fun cachePayloadParsesBackToCommits() {
        val commits = Updater.parseCommitHistoryJson(cachedJson)

        assertEquals(
            listOf(
                GitCommit(
                    sha = "abc1234",
                    message = "feat(updates): keyless update feed",
                    author = "vossgraves",
                    date = "2026-10-13T15:30:00Z",
                    url = "https://github.com/NatuneGroup/ArchiveTune/commit/abc1234",
                    authorAvatarUrl = "https://avatars.githubusercontent.com/u/1?v=4",
                ),
                GitCommit(
                    sha = "def5678",
                    message = "fix(player): something else",
                    author = "rukamori",
                    date = "2026-10-12T10:00:00Z",
                    url = "https://github.com/NatuneGroup/ArchiveTune/commit/def5678",
                    authorAvatarUrl = null,
                ),
            ),
            commits,
        )
    }

    @Test
    fun corruptCachePayloadIsAMiss() {
        assertNull(Updater.parseCommitHistoryJson("{oops"))
        assertNull(Updater.parseCommitHistoryJson(""))
        assertNull(Updater.parseCommitHistoryJson("{\"not\":\"an array\"}"))
    }

    @Test
    fun emptyArrayParsesToEmptyList() {
        assertEquals(emptyList<GitCommit>(), Updater.parseCommitHistoryJson("[]"))
    }
}
