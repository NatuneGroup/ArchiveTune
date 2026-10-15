/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRequestGateTest {
    @Test
    fun refreshLoadWaitsForInitialLoadInsteadOfBeingDropped() = runBlocking {
        val gate = HomeRequestGate()
        val initialRequest = gate.current()
        val initialStarted = CompletableDeferred<Unit>()
        val finishInitial = CompletableDeferred<Unit>()
        val refreshedStarted = CompletableDeferred<Unit>()
        val completedLoads = mutableListOf<Long>()

        val initialLoad =
            async {
                gate.runCurrentLoad(initialRequest) {
                    initialStarted.complete(Unit)
                    finishInitial.await()
                    completedLoads += initialRequest
                }
            }
        initialStarted.await()

        val refreshRequest = gate.next()
        val refreshLoad =
            async {
                gate.runCurrentLoad(refreshRequest) {
                    refreshedStarted.complete(Unit)
                    completedLoads += refreshRequest
                }
            }

        yield()
        assertFalse("refresh should wait for the in-flight initial load", refreshedStarted.isCompleted)

        finishInitial.complete(Unit)
        assertTrue(initialLoad.await())
        assertTrue(refreshLoad.await())
        assertEquals(listOf(initialRequest, refreshRequest), completedLoads)
    }

    @Test
    fun queuedLoadFromSupersededGenerationIsSkipped() = runBlocking {
        val gate = HomeRequestGate()
        val activeStarted = CompletableDeferred<Unit>()
        val finishActive = CompletableDeferred<Unit>()
        val activeRequest = gate.current()
        var staleLoadStarted = false

        val activeLoad =
            async {
                gate.runCurrentLoad(activeRequest) {
                    activeStarted.complete(Unit)
                    finishActive.await()
                }
            }
        activeStarted.await()
        val staleRequest = gate.next()

        val staleLoad =
            async {
                gate.runCurrentLoad(staleRequest) {
                    staleLoadStarted = true
                }
            }
        yield()
        val currentRequest = gate.next()
        finishActive.complete(Unit)

        assertTrue(activeLoad.await())
        assertFalse(staleLoad.await())
        assertFalse(staleLoadStarted)
        assertTrue(gate.current() == currentRequest)
    }

    @Test
    fun staleCommitIsDroppedWithoutPublishing() {
        val gate = HomeRequestGate()
        val staleRequest = gate.current()
        gate.next()
        var published = false

        assertFalse(gate.commit(staleRequest) { published = true })
        assertFalse(published)
    }

    @Test
    fun currentCommitPublishes() {
        val gate = HomeRequestGate()
        var published = false

        assertTrue(gate.commit(gate.current()) { published = true })
        assertTrue(published)
    }
}
