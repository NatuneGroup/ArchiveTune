/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

/**
 * Pure decision logic for coalescing bottom-nav tab taps, kept free of Android types so it can be
 * unit-tested. A tap burst must cost at most one content transition to the last tapped tab instead
 * of a stack of overlapping [navigate] calls.
 *
 * The caller (MainActivity) feeds it [TabNavEvent]s and executes the returned [TabNavAction].
 */
data class TabNavState(
    val pendingTab: String? = null,
)

sealed interface TabNavEvent {
    /**
     * A tab was tapped.
     *
     * @param targetRoute route of the tapped tab.
     * @param isCurrentTab whether the tap landed on the tab currently shown, decided at click time
     *   from the nav controller's destination hierarchy (a composition-captured `selected` goes
     *   stale when two taps land before a recomposition).
     * @param currentResumed whether the current entry has reached RESUMED. Navigation-compose holds
     *   the entering entry at STARTED until its transition finishes, so `false` means a tab
     *   transition is in flight.
     */
    data class Tap(
        val targetRoute: String,
        val isCurrentTab: Boolean,
        val currentResumed: Boolean,
    ) : TabNavEvent

    /** The current entry settled: it reached RESUMED showing [route]. */
    data class Settled(
        val route: String,
    ) : TabNavEvent
}

sealed interface TabNavAction {
    /** Navigate to the tab now. */
    data class Navigate(
        val targetRoute: String,
    ) : TabNavAction

    /** Remember the tapped tab; the settle collector navigates to it once the transition ends. */
    data class SetPending(
        val targetRoute: String,
    ) : TabNavAction

    /** Nothing to navigate — either a re-select (caller scrolls to top) or a no-op. */
    data object None : TabNavAction
}

/**
 * Advances the coalescing state machine by one event.
 *
 * - A tap on the shown tab with nothing pending is a re-select: no navigation.
 * - A tap while the content is settled navigates immediately.
 * - Any other tap becomes the pending tab, replacing whatever was pending (last tap wins).
 * - A settle fires at most one navigate: to the last pending tab, unless the settled route is
 *   already the pending one.
 */
fun reduceTabNav(
    state: TabNavState,
    event: TabNavEvent,
): Pair<TabNavState, TabNavAction> =
    when (event) {
        is TabNavEvent.Tap ->
            when {
                event.isCurrentTab && state.pendingTab == null -> state to TabNavAction.None
                event.currentResumed && state.pendingTab == null ->
                    state to TabNavAction.Navigate(event.targetRoute)
                else -> state.copy(pendingTab = event.targetRoute) to TabNavAction.SetPending(event.targetRoute)
            }

        is TabNavEvent.Settled -> when (state.pendingTab) {
            null, event.route -> TabNavState() to TabNavAction.None
            else -> TabNavState() to TabNavAction.Navigate(state.pendingTab)
        }
    }
