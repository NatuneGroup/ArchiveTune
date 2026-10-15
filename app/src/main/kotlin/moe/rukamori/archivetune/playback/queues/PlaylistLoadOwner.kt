/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import androidx.media3.common.Player.REPEAT_MODE_OFF
import moe.rukamori.archivetune.playback.UserQueuedKind
import moe.rukamori.archivetune.playback.userQueueSkipCount

/** Direct item/subset insertion: only explicit whole-playlist callers perform browse completion. */
internal fun <T> insertUserQueuedItems(
    items: List<T>,
    kind: UserQueuedKind,
    keepInOrder: Boolean,
    currentIndex: Int?,
    itemCount: Int,
    shuffleEnabled: Boolean,
    upcomingKinds: () -> List<UserQueuedKind>,
    markQueued: (T) -> T,
    append: (List<T>) -> Unit,
    insert: (index: Int, items: List<T>, skipUpcoming: Int, shuffled: Boolean) -> Unit,
    prepare: () -> Unit,
) {
    if (items.isEmpty()) return
    if (!keepInOrder && kind == UserQueuedKind.ADD_TO_QUEUE) {
        append(items)
        prepare()
        return
    }
    val queuedItems = if (keepInOrder) items.map(markQueued) else items
    val hasQueue = itemCount > 0 && currentIndex != null
    val skip = if (keepInOrder && hasQueue) userQueueSkipCount(upcomingKinds(), kind) else 0
    val shuffled = shuffleEnabled && hasQueue
    val index = when {
        !hasQueue -> itemCount
        shuffled && kind == UserQueuedKind.ADD_TO_QUEUE -> itemCount
        shuffled -> requireNotNull(currentIndex) + 1
        else -> (requireNotNull(currentIndex) + 1 + skip).coerceAtMost(itemCount)
    }
    insert(index, queuedItems, skip, shuffled)
    prepare()
}

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
