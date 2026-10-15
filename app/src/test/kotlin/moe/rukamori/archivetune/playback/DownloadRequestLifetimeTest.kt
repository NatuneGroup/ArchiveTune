/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadRequestLifetimeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun request(parent: File) =
        DownloadRequestLifetime<String>(DownloadRequestLifetime.createDirectory(parent))

    @Test
    fun identicalConcurrentRequestsOwnDifferentStagingAndClosingACannotDeleteB() {
        val parent = temporary.newFolder()
        val a = request(parent)
        val b = request(parent)
        try {
            val first = File(a.directory, "content").apply { writeText("same-url") }
            File(a.directory, "content.temp").writeText("partial")
            val second = File(b.directory, "content").apply { writeText("same-url") }
            assertNotEquals(a.directory, b.directory)
            second.inputStream().use { readingB ->
                a.cancel()
                a.cleanup()
                assertFalse(first.exists())
                assertFalse(a.directory.exists())
                assertEquals("same-url", readingB.reader().readText())
                assertEquals("same-url", second.readText())
            }
            assertFalse(b.cancelled)
            assertEquals(1L, b.latch.count)
        } finally {
            a.cleanup()
            b.cleanup()
        }
    }

    @Test
    fun cancelWakesBlockedWaitWithoutWaitingForTransportCallback() {
        val request = request(temporary.newFolder())
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val waiting = executor.submit<Boolean> {
                entered.countDown()
                request.latch.await(30, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            request.cancel()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            assertTrue(request.cancelled)
            assertEquals(0L, request.latch.count)
        } finally {
            request.cancel()
            request.cleanup()
            executor.shutdownNow()
        }
    }

    @Test
    fun lateCompletionAndErrorCleanOnlyCancelledRequestEvenAfterReplacement() {
        val parent = temporary.newFolder()
        val old = request(parent)
        old.cancel()
        old.cleanup()
        val replacement = request(parent)
        try {
            val live = File(replacement.directory, "content").apply { writeText("replacement") }
            old.directory.mkdir()
            File(old.directory, "content.temp").writeText("late partial")
            old.completed()
            assertFalse(old.directory.exists())
            old.directory.mkdir()
            File(old.directory, "content").writeText("late completion")
            old.failed("late error")
            assertFalse(old.directory.exists())
            assertEquals("replacement", live.readText())
            assertFalse(replacement.cancelled)
            assertEquals(1L, replacement.latch.count)
            old.cancel()
            old.cleanup()
            assertTrue(live.exists())
        } finally {
            old.cleanup()
            replacement.cleanup()
        }
    }

    @Test
    fun successfulAndFailedCallbacksWakeOnlyTheirOwnWaiters() {
        val parent = temporary.newFolder()
        val success = request(parent)
        val failure = request(parent)
        try {
            val file = File(success.directory, "content").apply { writeText("finished") }
            success.completed()
            assertEquals(0L, success.latch.count)
            assertEquals(1L, failure.latch.count)
            assertTrue(file.exists())
            failure.failed("fixture failure")
            assertEquals("fixture failure", failure.error.get())
            assertEquals(0L, failure.latch.count)
            assertFalse(success.cancelled)
            assertFalse(failure.cancelled)
        } finally {
            success.cleanup()
            failure.cleanup()
        }
    }
}
