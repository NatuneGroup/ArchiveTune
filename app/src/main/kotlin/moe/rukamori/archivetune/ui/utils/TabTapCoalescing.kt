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
    /** The pending request came from a round root button: pop the tab back to its root. */
    val pendingToRoot: Boolean = false,
    /**
     * The pending tab was tapped again while it was the locally selected (optimistic) tab, so the
     * entry it lands on must be re-selected (scroll to top) instead of restoring its old state.
     */
    val pendingReselect: Boolean = false,
)

sealed interface TabNavEvent {
    /**
     * A tab or round root button was tapped.
     *
     * @param targetRoute route of the tapped tab.
     * @param isCurrentTab whether the tap landed on the tab currently shown, decided at click time
     *   from the nav controller's destination hierarchy (a composition-captured `selected` goes
     *   stale when two taps land before a recomposition).
     * @param currentResumed whether the current entry has reached RESUMED. Navigation-compose holds
     *   the entering entry at STARTED until its transition finishes, so `false` means a tab
     *   transition is in flight.
     * @param toRoot the tap asks for the tab's root page (round Library/Home button), which must
     *   pop back to it even from deep inside the tab.
     */
    data class Tap(
        val targetRoute: String,
        val isCurrentTab: Boolean,
        val currentResumed: Boolean,
        val toRoot: Boolean = false,
    ) : TabNavEvent

    /** The current entry settled: it reached RESUMED showing [route]. */
    data class Settled(
        val route: String,
    ) : TabNavEvent
}

sealed interface TabNavAction {
    /**
     * Navigate to the tab now. With [toRoot] the caller pops back to the tab's root page; with
     * [reselect] it re-selects the entry it lands on.
     */
    data class Navigate(
        val targetRoute: String,
        val toRoot: Boolean = false,
        val reselect: Boolean = false,
    ) : TabNavAction

    /** Remember the tapped tab; the settle collector navigates to it once the transition ends. */
    data class SetPending(
        val targetRoute: String,
    ) : TabNavAction

    /** The shown tab was re-selected: the caller scrolls it to top (or opens search). */
    data class Reselect(
        val targetRoute: String,
    ) : TabNavAction

    /** Nothing to do. */
    data object None : TabNavAction
}

/**
 * Advances the coalescing state machine by one event. Tab taps and round root-button taps share
 * this single pending slot, so whichever was requested last wins.
 *
 * - A tab tap on the shown tab with nothing pending is a re-select: no navigation.
 * - A tap while the content is settled and nothing is pending navigates immediately.
 * - A tap on the tab that is already pending (the locally selected one) keeps it pending and
 *   records the re-select intent, so it is applied to that tab's entry once it lands.
 * - Any other tap becomes the pending tab, replacing whatever was pending (last tap wins).
 * - A settle fires at most one navigate: to the last pending tab, unless the settled route is
 *   already the pending one, in which case only a recorded root/re-select intent is applied.
 */
fun reduceTabNav(
    state: TabNavState,
    event: TabNavEvent,
): Pair<TabNavState, TabNavAction> =
    when (event) {
        is TabNavEvent.Tap ->
            when {
                state.pendingTab == null && event.isCurrentTab && !event.toRoot ->
                    state to TabNavAction.Reselect(event.targetRoute)

                state.pendingTab == null && event.currentResumed ->
                    state to TabNavAction.Navigate(event.targetRoute, toRoot = event.toRoot)

                state.pendingTab == event.targetRoute ->
                    state.copy(
                        pendingToRoot = state.pendingToRoot || event.toRoot,
                        pendingReselect = true,
                    ) to TabNavAction.SetPending(event.targetRoute)

                else ->
                    TabNavState(pendingTab = event.targetRoute, pendingToRoot = event.toRoot) to
                        TabNavAction.SetPending(event.targetRoute)
            }

        is TabNavEvent.Settled ->
            when (val pending = state.pendingTab) {
                null -> TabNavState() to TabNavAction.None

                event.route ->
                    TabNavState() to
                        when {
                            state.pendingToRoot ->
                                TabNavAction.Navigate(pending, toRoot = true, reselect = state.pendingReselect)
                            state.pendingReselect -> TabNavAction.Reselect(pending)
                            else -> TabNavAction.None
                        }

                else ->
                    TabNavState() to
                        TabNavAction.Navigate(
                            pending,
                            toRoot = state.pendingToRoot,
                            reselect = state.pendingReselect,
                        )
            }
    }
