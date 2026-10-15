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

/**
 * One media3 cache span, reduced to what the coverage maths below needs.
 *
 * [position] and [length] mirror [androidx.media3.datasource.cache.CacheSpan]; [isCached] mirrors
 * `CacheSpan.isCached`, which is false for a hole.
 */
internal data class CachedSpan(
    val position: Long,
    val length: Long,
    val isCached: Boolean,
)

/**
 * How many bytes from [position] are cached without ever crossing a gap.
 *
 * `Cache.getCachedSpans` returns hole spans — the ranges the index knows are missing — in the same
 * set as the cached ones, which is why media3's own [androidx.media3.datasource.cache.CacheDataSource]
 * filters them on `isCached`. Counting a hole as coverage handed ExoPlayer a byte range that was
 * never written: the MP4 extractor then read whatever came back for that hole and died with
 * `ParserException: Skipping atom with length > 2147483647`, playback error code 3003. Every seek
 * past a song's buffered head failed that way while playing from the first byte worked.
 *
 * Open-ended spans ([length] < 0, still being written) are not a covered range either, so they are
 * ignored rather than treated as coverage.
 */
internal fun continuousCachedLength(
    spans: List<CachedSpan>,
    position: Long,
    requestedLength: Long,
): Long {
    if (position < 0L || requestedLength <= 0L) return 0L
    val targetEnd = position.safeAdd(requestedLength)
    var cursor = position
    val ordered =
        spans
            .asSequence()
            .filter { it.isCached && it.length > 0L && it.position.safeAdd(it.length) > position }
            .sortedBy { it.position }
    for (span in ordered) {
        if (span.position > cursor) break
        val spanEnd = span.position.safeAdd(span.length)
        if (spanEnd > cursor) {
            cursor = minOf(spanEnd, targetEnd)
            if (cursor >= targetEnd) break
        }
    }
    return (cursor - position).coerceAtLeast(0L)
}

private fun Long.safeAdd(value: Long): Long {
    if (value <= 0L) return this
    val result = this + value
    return if (result < this) Long.MAX_VALUE else result
}
