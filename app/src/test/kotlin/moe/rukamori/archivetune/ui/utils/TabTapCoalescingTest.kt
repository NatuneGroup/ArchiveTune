/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabTapCoalescingTest {
    private val home = "home"
    private val search = "search"
    private val library = "library"

    private fun tap(
        target: String,
        isCurrent: Boolean = false,
        resumed: Boolean = true,
    ): Pair<TabNavState, TabNavAction> =
        reduceTabNav(TabNavState(), TabNavEvent.Tap(target, isCurrent, resumed))

    @Test
    fun `tap on another tab while settled navigates immediately`() {
        val (state, action) = tap(search)

        assertNull(state.pendingTab)
        assertEquals(TabNavAction.Navigate(search), action)
    }

    @Test
    fun `tap on the shown tab is a re-select and never navigates`() {
        val (_, action) = tap(home, isCurrent = true)

        assertEquals(TabNavAction.Reselect(home), action)
    }

    @Test
    fun `tap during a transition is stored as pending instead of stacking a navigate`() {
        val (state, action) = tap(library, resumed = false)

        assertEquals(TabNavAction.SetPending(library), action)
        assertEquals(library, state.pendingTab)
    }

    @Test
    fun `a burst of taps during a transition keeps only the last pending tab`() {
        var state = TabNavState()

        fun tapWhileBusy(target: String) {
            val (next, action) =
                reduceTabNav(state, TabNavEvent.Tap(target, isCurrentTab = false, currentResumed = false))
            state = next
            assertEquals(TabNavAction.SetPending(target), action)
        }

        tapWhileBusy(search)
        tapWhileBusy(library)
        tapWhileBusy(home)

        assertEquals(home, state.pendingTab)
    }

    @Test
    fun `settling with a pending tab fires exactly one navigate to the last pending tab`() {
        val state = TabNavState(pendingTab = library)

        val (next, action) = reduceTabNav(state, TabNavEvent.Settled(search))

        assertNull(next.pendingTab)
        assertEquals(TabNavAction.Navigate(library), action)
    }

    @Test
    fun `settling on the pending tab clears it without navigating again`() {
        val state = TabNavState(pendingTab = search)

        val (next, action) = reduceTabNav(state, TabNavEvent.Settled(search))

        assertNull(next.pendingTab)
        assertEquals(TabNavAction.None, action)
    }

    @Test
    fun `settling without a pending tab changes nothing`() {
        val (next, action) = reduceTabNav(TabNavState(), TabNavEvent.Settled(home))

        assertNull(next.pendingTab)
        assertEquals(TabNavAction.None, action)
    }

    @Test
    fun `tap on a tab that was current before the switch navigates back instead of re-selecting`() {
        // Regression: with a composition-captured `selected`, tapping the origin tab mid-switch
        // was treated as a re-select and never navigated back.
        val (_, action) = tap(home, isCurrent = false, resumed = false)

        assertEquals(TabNavAction.SetPending(home), action)
    }

    @Test
    fun `a re-select tap is decided at click time even when a pending tab exists`() {
        // Transition in flight to search, pending = library; tapping home (not the shown tab)
        // must not be a re-select: the pending tab is replaced by the tap.
        val state = TabNavState(pendingTab = library)

        val (next, action) =
            reduceTabNav(state, TabNavEvent.Tap(home, isCurrentTab = false, currentResumed = true))

        assertEquals(TabNavAction.SetPending(home), action)
        assertEquals(home, next.pendingTab)
    }

    @Test
    fun `a burst settles without stacking navigations`() {
        // Full spam-tap trace: home is shown; taps on search, library, search, home land
        // mid-transition after the first navigate. Expected: 1 immediate navigate, then one
        // navigate per settle, always to the last pending tab.
        var state = TabNavState()
        val navigations = mutableListOf<String>()

        val first = reduceTabNav(state, TabNavEvent.Tap(search, isCurrentTab = false, currentResumed = true))
        state = first.first
        (first.second as? TabNavAction.Navigate)?.let { navigations += it.targetRoute }

        for (target in listOf(library, search, home)) {
            val (next, _) =
                reduceTabNav(state, TabNavEvent.Tap(target, isCurrentTab = false, currentResumed = false))
            state = next
        }

        val settle = reduceTabNav(state, TabNavEvent.Settled(search))
        state = settle.first
        (settle.second as? TabNavAction.Navigate)?.let { navigations += it.targetRoute }

        val settle2 = reduceTabNav(state, TabNavEvent.Settled(home))
        (settle2.second as? TabNavAction.Navigate)?.let { navigations += it.targetRoute }

        assertEquals(listOf(search, home), navigations)
        assertNull(settle2.first.pendingTab)
    }

    @Test
    fun `tapping the pending tab again keeps its re-select intent for the entry it lands on`() {
        // Library is entering; home is tapped (pending, locally selected) and tapped again.
        var state = TabNavState()
        state = reduceTabNav(state, TabNavEvent.Tap(home, isCurrentTab = false, currentResumed = false)).first
        val (afterSecond, secondAction) =
            reduceTabNav(state, TabNavEvent.Tap(home, isCurrentTab = false, currentResumed = false))

        assertEquals(TabNavAction.SetPending(home), secondAction)
        assertEquals(TabNavState(pendingTab = home, pendingReselect = true), afterSecond)

        val (settled, action) = reduceTabNav(afterSecond, TabNavEvent.Settled(library))

        assertEquals(TabNavState(), settled)
        assertEquals(TabNavAction.Navigate(home, reselect = true), action)
    }

    @Test
    fun `a re-select intent on the pending tab is applied when it settles there`() {
        val state = TabNavState(pendingTab = home, pendingReselect = true)

        val (_, action) = reduceTabNav(state, TabNavEvent.Settled(home))

        assertEquals(TabNavAction.Reselect(home), action)
    }

    @Test
    fun `a different tab tapped after the pending one drops the earlier re-select intent`() {
        val state = TabNavState(pendingTab = home, pendingReselect = true)

        val (next, _) = reduceTabNav(state, TabNavEvent.Tap(library, isCurrentTab = false, currentResumed = false))

        assertEquals(TabNavState(pendingTab = library), next)
    }

    @Test
    fun `a root request while settled navigates to the tab root even from inside that tab`() {
        val (state, action) =
            reduceTabNav(TabNavState(), TabNavEvent.Tap(library, isCurrentTab = true, currentResumed = true, toRoot = true))

        assertEquals(TabNavState(), state)
        assertEquals(TabNavAction.Navigate(library, toRoot = true), action)
    }

    @Test
    fun `a root request during a transition replaces the pending tab and wins on settle`() {
        // Search is pending while home is entering; the round Library button is the last request.
        val state = TabNavState(pendingTab = search)

        val (next, action) =
            reduceTabNav(state, TabNavEvent.Tap(library, isCurrentTab = false, currentResumed = false, toRoot = true))

        assertEquals(TabNavAction.SetPending(library), action)
        assertEquals(TabNavState(pendingTab = library, pendingToRoot = true), next)

        val (settled, settleAction) = reduceTabNav(next, TabNavEvent.Settled(home))

        assertNull(settled.pendingTab)
        assertEquals(TabNavAction.Navigate(library, toRoot = true), settleAction)
    }

    @Test
    fun `a tab tap after a pending root request replaces it`() {
        val state = TabNavState(pendingTab = library, pendingToRoot = true)

        val (next, _) = reduceTabNav(state, TabNavEvent.Tap(search, isCurrentTab = false, currentResumed = false))

        assertEquals(TabNavState(pendingTab = search), next)
    }
}
