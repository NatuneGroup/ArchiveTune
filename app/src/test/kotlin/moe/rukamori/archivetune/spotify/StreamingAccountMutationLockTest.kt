/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingAccountMutationLockTest {
    @Test
    fun signOutAndReplacementWaitForRefreshPersistenceBeforeTheirOwnCommit() = runTest {
        for (replacement in listOf(false, true)) {
            val lock = StreamingAccountMutationLock()
            val fetched = CompletableDeferred<Unit>()
            val persist = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var account: Int? = 1
            var session: Int? = 1
            val refresh = launch {
                lock.withLock {
                    events += "refresh-read"
                    fetched.complete(Unit)
                    persist.await()
                    events += "refresh-persist"
                    session = 11
                }
                events += "refresh-return"
            }
            fetched.await()
            val mutation = launch {
                lock.withLock {
                    events += "mutation-commit"
                    account = if (replacement) 2 else null
                    session = if (replacement) 2 else null
                }
            }
            runCurrent()
            assertFalse(mutation.isCompleted)
            assertEquals(listOf("refresh-read"), events)
            persist.complete(Unit)
            refresh.join()
            mutation.join()
            assertEquals(listOf("refresh-read", "refresh-persist", "refresh-return", "mutation-commit"), events)
            assertEquals(if (replacement) 2 else null, account)
            assertEquals(if (replacement) 2 else null, session)
        }
    }

    @Test
    fun unchangedAccountRefreshReturnsOnlyAfterSuspendedCommit() = runTest {
        val lock = StreamingAccountMutationLock()
        val persistence = CompletableDeferred<Unit>()
        var committed = false
        var returned = false
        val refresh = launch {
            val value = lock.withLock {
                persistence.await()
                committed = true
                11
            }
            assertTrue(committed)
            assertEquals(11, value)
            returned = true
        }
        runCurrent()
        assertFalse(returned)
        persistence.complete(Unit)
        refresh.join()
        assertTrue(returned)
    }

    @Test
    fun cancelledRefreshReleasesOwnershipWithoutLateCommit() = runTest {
        val lock = StreamingAccountMutationLock()
        val fetch = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val refresh = launch {
            lock.withLock {
                fetch.await()
                events += "old-commit"
            }
        }
        runCurrent()
        val signOut = launch { lock.withLock { events += "sign-out" } }
        runCurrent()
        assertTrue(events.isEmpty())
        refresh.cancel()
        refresh.join()
        signOut.join()
        fetch.complete(Unit)
        runCurrent()
        assertEquals(listOf("sign-out"), events)
    }

    @Test
    fun persistenceFailureDoesNotReturnSuccessAndAllowsNextAccountMutation() = runTest {
        val lock = StreamingAccountMutationLock()
        var returned = false
        var failure = false
        try {
            lock.withLock<Int> { throw IOException("fake persistence") }
            returned = true
        } catch (_: IOException) {
            failure = true
        }
        assertTrue(failure)
        assertFalse(returned)
        assertEquals(2, lock.withLock { 2 })
    }
}
