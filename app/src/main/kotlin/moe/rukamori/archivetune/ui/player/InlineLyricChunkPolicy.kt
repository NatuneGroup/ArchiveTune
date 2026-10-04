/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

const val InlineLyricLinesPerPage = 2

internal data class InlineLyricTimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

internal data class InlineLyricWordRange(
    val wordIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

internal data class InlineLyricChunk(
    val startOffset: Int,
    val endOffset: Int,
    val startMs: Long,
    val lineCount: Int,
)

internal data class InlineLyricPages(
    val chunks: List<InlineLyricChunk>,
    val wordRanges: List<InlineLyricWordRange>,
)

internal fun locateInlineLyricWordRanges(
    text: String,
    words: List<InlineLyricTimedWord>,
): List<InlineLyricWordRange> {
    var searchFrom = 0
    return buildList {
        words.forEachIndexed { index, word ->
            val wordText = word.text.trim()
            if (wordText.isEmpty()) return@forEachIndexed
            val start = text.indexOf(wordText, searchFrom)
            if (start < 0) return@forEachIndexed
            val end = start + wordText.length
            add(
                InlineLyricWordRange(
                    wordIndex = index,
                    startOffset = start,
                    endOffset = end,
                    startMs = word.startMs,
                    endMs = word.endMs,
                ),
            )
            searchFrom = end
        }
    }
}

internal fun createInlineLyricPages(
    text: String,
    visualLineStarts: List<Int>,
    words: List<InlineLyricTimedWord>,
    linesPerPage: Int,
    cueStartMs: Long,
    cueEndMs: Long,
): InlineLyricPages {
    if (text.isEmpty()) return InlineLyricPages(emptyList(), emptyList())

    val starts =
        visualLineStarts
            .asSequence()
            .filter { it in 0 until text.length }
            .distinct()
            .sorted()
            .toMutableList()
            .apply {
                if (isEmpty() || first() != 0) add(0, 0)
            }
    val pageLineCount = linesPerPage.coerceAtLeast(1)
    val pageLineIndexes = starts.indices.step(pageLineCount).toList()
    val wordRanges = locateInlineLyricWordRanges(text, words)
    val linePages =
        pageLineIndexes.mapIndexed { pageIndex, firstLineIndex ->
            val nextPageLineIndex = pageLineIndexes.getOrNull(pageIndex + 1)
            InlineLyricChunk(
                startOffset = starts[firstLineIndex],
                endOffset = nextPageLineIndex?.let(starts::get) ?: text.length,
                startMs = cueStartMs,
                lineCount = (nextPageLineIndex ?: starts.size) - firstLineIndex,
            )
        }

    val cueStart = cueStartMs.coerceAtLeast(0L)
    val naturalCueEnd =
        cueEndMs.takeIf { it > cueStart }
            ?: saturatedAdd(cueStart, linePages.size.toLong() * InlineLyricFallbackPageDurationMs)
    val cueEnd =
        if (naturalCueEnd > cueStart) {
            naturalCueEnd
        } else if (cueStart < Long.MAX_VALUE) {
            cueStart + 1L
        } else {
            cueStart
        }
    val cueDuration = cueEnd - cueStart
    var previousStart = cueStart
    val timedChunks =
        linePages.mapIndexed { pageIndex, page ->
            val fallbackStart =
                cueStart + (cueDuration.toDouble() * pageIndex / linePages.size).toLong()
            val timedStart =
                if (pageIndex == 0) {
                    cueStart
                } else {
                    wordTimeForPage(page, linePages, wordRanges)?.coerceIn(cueStart, cueEnd) ?: fallbackStart
                }
            val latestStart = cueEnd - 1L
            val minimumStart =
                when {
                    pageIndex == 0 -> cueStart
                    previousStart < latestStart -> previousStart + 1L
                    else -> latestStart
                }
            val pageStart = maxOf(minimumStart, timedStart).coerceAtMost(latestStart)
            previousStart = pageStart
            page.copy(startMs = pageStart)
        }

    return InlineLyricPages(chunks = timedChunks, wordRanges = wordRanges)
}

internal fun inlineLyricChunkIndexAt(
    chunks: List<InlineLyricChunk>,
    positionMs: Long,
): Int {
    var activeIndex = 0
    for (index in chunks.indices) {
        if (chunks[index].startMs > positionMs) break
        activeIndex = index
    }
    return activeIndex
}

internal fun inlineLyricChunkText(
    text: String,
    chunk: InlineLyricChunk,
): String {
    val start = chunk.startOffset.coerceIn(0, text.length)
    val end = chunk.endOffset.coerceIn(start, text.length)
    val raw = text.substring(start, end)
    val contentStart = raw.indexOfFirst { !it.isWhitespace() }
    if (contentStart < 0) return ""
    val contentEnd = raw.indexOfLast { !it.isWhitespace() } + 1
    return raw.substring(contentStart, contentEnd)
}

internal fun inlineLyricChunkWordText(
    text: String,
    chunk: InlineLyricChunk,
    word: InlineLyricWordRange,
): String? {
    val chunkTextStart = chunk.startOffset.coerceIn(0, text.length)
    val chunkTextEnd = chunk.endOffset.coerceIn(chunkTextStart, text.length)
    val start = maxOf(chunkTextStart, word.startOffset)
    val end = minOf(chunkTextEnd, word.endOffset)
    if (start >= end) return null
    return text.substring(start, end).trim().takeIf { it.isNotEmpty() }
}

private fun wordTimeForPage(
    page: InlineLyricChunk,
    pages: List<InlineLyricChunk>,
    words: List<InlineLyricWordRange>,
): Long? {
    val word =
        words
            .asSequence()
            .filter { it.startOffset < page.endOffset && it.endOffset > page.startOffset }
            .minByOrNull { it.startOffset }
            ?: return null
    if (word.startOffset >= page.startOffset) return word.startMs

    val wordPages =
        pages.filter { it.startOffset < word.endOffset && it.endOffset > word.startOffset }
    val pageIndex = wordPages.indexOfFirst { it.startOffset == page.startOffset }
    if (pageIndex <= 0 || wordPages.size < 2) return word.startMs
    val wordDuration = (word.endMs - word.startMs).coerceAtLeast(0L)
    return saturatedAdd(word.startMs, (wordDuration.toDouble() * pageIndex / wordPages.size).toLong())
}

private fun saturatedAdd(
    value: Long,
    amount: Long,
): Long =
    if (amount > 0L && value > Long.MAX_VALUE - amount) {
        Long.MAX_VALUE
    } else {
        value + amount
    }

private const val InlineLyricFallbackPageDurationMs = 1_000L
