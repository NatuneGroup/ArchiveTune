/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import moe.rukamori.archivetune.constants.AudioSourceType

internal data class AcousticCacheExtent(
    val position: Long,
    val length: Long,
    val fileLength: Long,
    val regular: () -> Boolean,
    val cached: Boolean,
)

internal object AcousticSourcePolicy {
    fun selectedSnapshot(mediaId: String, snapshot: AcousticSourceSnapshot?): AcousticSourceSnapshot? =
        snapshot?.takeIf { it.mediaId == mediaId }

    fun cacheMatches(
        source: AudioSourceType,
        length: Long,
        sampleRate: Int?,
        lossless: Boolean,
        current: AcousticSourceSnapshot?,
    ): Boolean =
        length > 0 && (current == null ||
            (current.source == source &&
                (current.contentLength == null || current.contentLength == length) &&
                (current.advertisedSampleRate == null || current.advertisedSampleRate == sampleRate) &&
                current.claimedLossless == lossless))

    fun completeExtents(length: Long, extents: List<AcousticCacheExtent>, checkActive: () -> Unit): Boolean {
        if (length <= 0 || extents.size > 2048) return false
        var end = 0L
        for (extent in extents) {
            checkActive()
            if (!extent.cached || extent.position != end || extent.length <= 0 ||
                extent.length > length - end || extent.fileLength != extent.length || !extent.regular()
            ) return false
            end += extent.length
        }
        return end == length
    }
}
