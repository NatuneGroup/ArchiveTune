/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticWorkTest {
    @Test
    fun repeatedCloseReleasesOwnedInputOnlyOnce() {
        var closes = 0
        val work = AcousticWork()
        work.own(Closeable { closes++ })
        work.check()
        work.close()
        work.close()
        assertEquals(1, closes)
        cancelled { work.check() }
    }

    @Test
    fun inputDeliveredAfterCancelIsClosedAndCannotBeUsed() {
        val work = AcousticWork()
        work.close()
        var closes = 0
        cancelled { work.own(Closeable { closes++ }) }
        assertEquals(1, closes)
        work.close()
        assertEquals(1, closes)
    }

    @Test
    fun throwingInputCloseDoesNotPreventCancellationOrRetryWithIndependentWork() {
        val old = AcousticWork()
        old.own(Closeable { throw java.io.IOException("fixture") })
        old.close()
        cancelled { old.check() }
        AcousticWork().use { retry ->
            retry.check()
            var closes = 0
            retry.own(Closeable { closes++ })
            retry.close()
            assertEquals(1, closes)
        }
    }

    @Test
    fun monotonicDeadlineExpiresAtExactBoundAndCancelStillWins() {
        var now = 10_000L
        AcousticWork { now }.use { work ->
            now += AcousticAuditLimits.DEADLINE_MS * 1_000_000 - 1
            work.check()
            now++
            try {
                work.check()
                throw AssertionError("Expired work must not continue")
            } catch (error: AcousticUnavailable) {
                assertEquals("deadline_exceeded", error.code)
            }
            work.close()
            cancelled { work.check() }
        }
    }

    private fun cancelled(block: () -> Unit) {
        try {
            block()
        } catch (_: CancellationException) {
            return
        }
        throw AssertionError("Cancelled work must not continue")
    }
}
