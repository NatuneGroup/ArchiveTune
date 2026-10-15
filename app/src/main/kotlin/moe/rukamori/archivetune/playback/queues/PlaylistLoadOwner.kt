/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import androidx.media3.common.Player.REPEAT_MODE_OFF

internal data class PlaylistActionTicket(val operation: Long, val queueGeneration: Long) {
    fun isCurrent(currentOperation: Long, currentQueueGeneration: Long): Boolean =
        operation == currentOperation && queueGeneration == currentQueueGeneration
}

internal fun finiteRecommendationsAllowed(
    phase: FinitePlaylistPhase,
    autoLoadMore: Boolean,
    repeatMode: Int,
): Boolean = phase == FinitePlaylistPhase.EXHAUSTED && autoLoadMore && repeatMode == REPEAT_MODE_OFF

internal fun appendFiniteShuffleOrder(previous: List<Int>, oldCount: Int, newCount: Int): List<Int> =
    previous + (oldCount until newCount).shuffled()

/** Player-thread ownership only; transport cancellation is not an acceptance guarantee. */
internal class PlaylistLoadOwner {
    private var active: Ticket? = null

    fun begin(generation: Long, queue: Any): Ticket? {
        if (active?.isCurrent(generation, queue) == true) return null
        return Ticket(generation, queue).also { active = it }
    }

    fun cancel() { active = null }

    inner class Ticket internal constructor(private val generation: Long, private val queue: Any) {
        fun isCurrent(currentGeneration: Long, currentQueue: Any): Boolean =
            active === this && generation == currentGeneration && queue === currentQueue

        inline fun acceptIfCurrent(currentGeneration: Long, currentQueue: Any, effects: () -> Unit): Boolean {
            if (!isCurrent(currentGeneration, currentQueue)) return false
            effects()
            return true
        }

        fun finish() { if (active === this) active = null }
    }
}

/** The current item is deliberately excluded: insertion must not set/seek/restart it. */
internal fun <T> integrateFinitePreload(
    items: List<T>,
    selectedKey: String,
    keyOf: (T) -> String?,
    insertPrefix: (List<T>) -> Unit,
    appendSuffix: (List<T>) -> Unit,
) {
    val index = items.indexOfFirst { keyOf(it) == selectedKey }
    if (index < 0) throw IncompleteYouTubePlaylistException()
    insertPrefix(items.take(index))
    appendSuffix(items.drop(index + 1))
}
