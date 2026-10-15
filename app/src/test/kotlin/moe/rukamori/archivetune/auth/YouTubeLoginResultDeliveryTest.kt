/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import androidx.lifecycle.viewModelScope
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.viewmodels.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeLoginResultDeliveryTest {
    private val views = mutableListOf<LoginViewModel>()

    @Test
    fun committedResultInvalidatedBeforeMainDeliveryShowsRecoveryAfterLogoutOrSwitch() = runTest {
        withQueuedMain { main ->
            for (switch in listOf(false, true)) {
                val f = AuthFixture(StandardTestDispatcher(testScheduler))
                val vm = commitPendingMainResult(f, main)
                val oldView = vm.webRevision.value
                if (switch) f.login("C").getOrThrow() else f.repository.logout()
                // Commit scheduled the collector before the result continuation. Process its
                // conflated logout/switch event while the submitting Main job is still active.
                main.runNext()
                assertEquals(LoginScreenState.Loading, vm.screenState.value)
                assertFalse(vm.ownsView(oldView))
                main.drain()
                assertEquals(LoginScreenState.Error(LoginError.LoginFailed), vm.screenState.value)
                assertFalse(vm.consumeSuccess())
                assertEquals(if (switch) "C" else null, f.runtime.dataSyncId)
                // Error exposes the real Retry control; it can establish a new owned attempt.
                vm.retry()
                main.drain()
                runCurrent()
                main.drain()
                assertTrue(vm.webRevision.value > oldView)
                assertTrue(vm.screenState.value is LoginScreenState.Success)
                assertTrue(vm.consumeSuccess())
                f.assertAccount("B")
                vm.release()
                vm.viewModelScope.cancel()
                main.drain()
            }
        }
    }

    @Test
    fun releasedSubmissionCannotReceivePendingCommittedSuccessOrStaleRecovery() = runTest {
        withQueuedMain { main ->
            val f = AuthFixture(StandardTestDispatcher(testScheduler))
            val vm = commitPendingMainResult(f, main)
            vm.release()
            val releasedState = vm.screenState.value
            main.drain()
            assertEquals(releasedState, vm.screenState.value)
            assertFalse(vm.consumeSuccess())
            // Release after the commit reservation is not a retroactive rollback.
            f.assertAccount("B")
        }
    }

    @Test
    fun replacedSubmissionCannotOverwriteTheNewAttemptWithStaleRecovery() = runTest {
        withQueuedMain { main ->
            val f = AuthFixture(StandardTestDispatcher(testScheduler))
            val vm = commitPendingMainResult(f, main)
            val firstView = vm.webRevision.value
            vm.retry()
            main.drain()
            assertTrue(vm.webRevision.value > firstView)
            assertEquals(LoginScreenState.Loading, vm.screenState.value)
            runCurrent()
            main.drain()
            assertTrue(vm.screenState.value is LoginScreenState.Success)
            assertTrue(vm.consumeSuccess())
            assertFalse(vm.consumeSuccess())
            assertEquals(2, f.store.transactions)
            f.assertAccount("B")
        }
    }

    private fun TestScope.commitPendingMainResult(f: AuthFixture, main: QueuedMainDispatcher): LoginViewModel {
        val vm = LoginViewModel(f.repository).also { views += it; it.open() }
        main.drain()
        vm.onContextExtracted(vm.webRevision.value, "SAPISID=synthetic-B", "visitor-B", "B")
        main.drain()
        assertEquals(LoginScreenState.Loading, vm.screenState.value)
        runCurrent() // IO commits; Main's collector and result delivery remain queued.
        f.assertAccount("B")
        assertEquals(1, f.store.transactions)
        assertEquals(LoginScreenState.Loading, vm.screenState.value)
        assertTrue(main.pending >= 2)
        return vm
    }

    private suspend fun withQueuedMain(block: suspend (QueuedMainDispatcher) -> Unit) {
        val main = QueuedMainDispatcher()
        Dispatchers.setMain(main)
        try {
            block(main)
        } finally {
            views.forEach { it.release(); it.viewModelScope.cancel() }
            main.drain()
            Dispatchers.resetMain()
        }
    }

    /** Only the dispatcher edge is fake: the real VM, IO commit and ownership collector run. */
    private class QueuedMainDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending: Int get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
        fun drain() {
            repeat(100) { (tasks.pollFirst() ?: return).run() }
            error("Main dispatch did not settle")
        }
    }
}
