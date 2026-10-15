/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

private val contentRangeTotalRegex = Regex("(?i)^bytes\\s+\\d+-\\d+/(\\d+)$")

internal fun resolveExpectedDownloadedLength(
    explicitExpectedLength: String?,
    headContentLength: String?,
    rangeResponseCode: Int?,
    rangeContentRange: String?,
    rangeContentLength: String?,
): Long =
    parsePositiveLength(explicitExpectedLength)
        ?: parsePositiveLength(headContentLength)
        ?: when (rangeResponseCode) {
            206 -> contentRangeTotalRegex
                .matchEntire(rangeContentRange?.trim().orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            200 -> parsePositiveLength(rangeContentLength)
            else -> null
        }
        ?: 0L

internal fun isDownloadedContentTruncated(actualLength: Long, expectedLength: Long): Boolean =
    expectedLength > 0L && actualLength < expectedLength

private fun parsePositiveLength(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.takeIf { it > 0L }
