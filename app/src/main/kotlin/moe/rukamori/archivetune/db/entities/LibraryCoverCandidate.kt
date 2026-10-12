/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db.entities

/** One stored song's cover, with one of its credited artists (null when it has none). */
data class LibraryCoverCandidate(
    val title: String?,
    val thumbnailUrl: String?,
    val artistName: String?,
    /** Epoch millis the song was added to the library, null when it is not in it. */
    val inLibrary: Long?,
)
