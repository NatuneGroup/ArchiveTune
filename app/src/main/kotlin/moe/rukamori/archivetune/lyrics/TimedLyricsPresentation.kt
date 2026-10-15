/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

internal data class TimedLyricsPresentation(val text: String, val ranges: List<IntRange?>)

/** Preserve one logical source string; a line-only reading has no invented timed alignment. */
internal fun timedLyricsPresentation(entry: LyricsEntry, phonetics: LyricsPhonetics): TimedLyricsPresentation {
    val words = entry.words.orEmpty().filter { !it.isBackground }
    val aligned = phonetics.words?.takeIf { it.size == words.size }
    val text = if (aligned != null) {
        aligned.mapIndexed { index, reading -> reading ?: words[index].text }.joinToString(" ")
    } else phonetics.line ?: entry.text
    var offset = 0
    val ranges = words.mapIndexed { index, word ->
        if (aligned == null && phonetics.line != null) return@mapIndexed null
        val fragment = if (aligned != null) aligned[index] ?: word.text else word.text
        val start = text.indexOf(fragment, offset)
        if (start < 0 || fragment.isEmpty()) null else {
            offset = start + fragment.length
            start until offset
        }
    }
    return TimedLyricsPresentation(text, ranges)
}
