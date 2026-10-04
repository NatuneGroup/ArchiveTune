/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal data class CachedReadWindow(
    val position: Long,
    val length: Long,
)

internal fun CachedReadWindow.coveringRecordedLength(
    recordedContentLength: Long,
    explicitRequest: Boolean,
): CachedReadWindow {
    if (explicitRequest) return this
    val recordedRemaining = recordedContentLength - position
    return if (recordedRemaining > length) copy(length = recordedRemaining) else this
}

internal fun resolveCachedReadWindow(
    position: Long,
    requestedLength: Long,
    knownContentLength: Long?,
): CachedReadWindow? {
    if (position < 0L) return null
    val length =
        when {
            requestedLength >= 0L -> requestedLength
            knownContentLength != null && knownContentLength > position -> knownContentLength - position
            else -> return null
        }
    return length.takeIf { it > 0L }?.let { CachedReadWindow(position = position, length = length) }
}
