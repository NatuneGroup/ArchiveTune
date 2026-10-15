/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same owner used by AppUpdateInstaller, without network/package-manager work. */
class UpdateAttemptOwnerTest {
    @Test(timeout = 5_000)
    fun cancelledAttempt_retainsStagingUntilCleanupAndRejectsLateProgressAndTerminal() = runBlocking {
        for (terminal in listOf("ready-a", "failed-a")) {
            val owner = UpdateAttemptOwner(this, "idle")
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            assertTrue(owner.start("downloading-a") {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        cleanupRelease.await()
                        owner.update { "late-progress-a" }
                    }
                    finished.complete(Unit)
                }
                terminal
            })
            yield()
            owner.cancel()
            assertEquals("idle", owner.state.value)
            cleanupEntered.await()
            assertFalse(owner.start("downloading-b") { "ready-b" })
            owner.update { "cancelled-progress-a" }
            assertEquals("idle", owner.state.value)
            cleanupRelease.complete(Unit)
            finished.await()
            yield()
            assertEquals("idle", owner.state.value)
            assertTrue(owner.start("downloading-b") { "ready-b" })
            yield()
            assertEquals("ready-b", owner.state.value)
        }
    }

    @Test(timeout = 5_000)
    fun cancellationDuringErrorCleanup_cannotPublishFailureOverRetry() = runBlocking {
        val owner = UpdateAttemptOwner(this, "idle")
        val cleanupEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.start("downloading-a") {
            withContext(NonCancellable) {
                cleanupEntered.complete(Unit)
                release.await()
            }
            "failed-a"
        }
        cleanupEntered.await()
        owner.cancel()
        assertFalse(owner.start("downloading-b") { "ready-b" })
        release.complete(Unit)
        yield()
        yield()
        assertEquals("idle", owner.state.value)
        assertTrue(owner.start("downloading-b") { "ready-b" })
        yield()
        assertEquals("ready-b", owner.state.value)
    }

    @Test(timeout = 5_000)
    fun doubleStartAndAcknowledge_doNotReplaceActiveAttempt() = runBlocking {
        val owner = UpdateAttemptOwner(this, "idle")
        val release = CompletableDeferred<Unit>()
        assertTrue(owner.start("downloading-a") { release.await(); "ready-a" })
        assertFalse(owner.start("downloading-b") { "ready-b" })
        owner.acknowledge { !it.startsWith("downloading") }
        assertEquals("downloading-a", owner.state.value)
        yield()
        owner.update { "downloading-a-half" }
        assertEquals("downloading-a-half", owner.state.value)
        release.complete(Unit)
        yield()
        assertEquals("ready-a", owner.state.value)
        owner.acknowledge { !it.startsWith("downloading") }
        assertEquals("idle", owner.state.value)
    }
}
