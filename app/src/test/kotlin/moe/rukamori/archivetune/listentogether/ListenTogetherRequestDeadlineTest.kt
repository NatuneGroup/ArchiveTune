/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.listentogether

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherRequestDeadlineTest {
    @Test
    fun anUnansweredRequestStopsAtItsDeadline() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var timeouts = 0
        deadline.start(60_000L) { timeouts++ }
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(0, timeouts)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, timeouts)
    }

    @Test
    fun completingTheHandshakeCancelsTheTimerButKeepsItsSocketCallbacksCurrent() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var timeouts = 0
        val attempt = deadline.start(60_000L) { timeouts++ }
        deadline.complete()
        advanceTimeBy(90_000L)
        runCurrent()
        assertEquals(0, timeouts)
        assertTrue(deadline.isCurrent(attempt))
    }

    @Test
    fun aReplacementConnectionInvalidatesPreviousSocketCallbacksAndTimers() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var previousTimeouts = 0
        var currentTimeouts = 0
        val previous = deadline.start(10_000L) { previousTimeouts++ }
        advanceTimeBy(5_000L)
        val current = deadline.start(20_000L) { currentTimeouts++ }
        advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(deadline.isCurrent(previous))
        assertTrue(deadline.isCurrent(current))
        assertEquals(0, previousTimeouts)
        assertEquals(0, currentTimeouts)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, currentTimeouts)
    }

    @Test
    fun cancelingOrLeavingInvalidatesCallbacksWithoutReportingATimeout() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var timeouts = 0
        val attempt = deadline.start(30_000L) { timeouts++ }
        deadline.cancel()
        advanceTimeBy(90_000L)
        runCurrent()
        assertFalse(deadline.isCurrent(attempt))
        assertEquals(0, timeouts)
    }

    @Test
    fun connectionAndServerFailuresReleasePendingRoomLoading() {
        assertEquals("Connection failed", listenTogetherRequestFailure(ListenTogetherEvent.ConnectionError("Connection failed")))
        assertEquals("Room not found", listenTogetherRequestFailure(ListenTogetherEvent.ServerError("room_not_found", "Room not found")))
        assertEquals("Hosting denied", listenTogetherRequestFailure(ListenTogetherEvent.ServerError("host_not_allowed", "Hosting denied")))
        assertNull(listenTogetherRequestFailure(ListenTogetherEvent.ServerError("invalid_message", "Protocol upgrade")))
        assertNull(listenTogetherRequestFailure(ListenTogetherEvent.RoomCreated("TEST", "synthetic-user")))
    }

    @Test
    fun overlappingRoomRequestsCannotReplaceAnUnansweredRequestsDeadline() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var originalTimeouts = 0
        var replacementTimeouts = 0
        val original = deadline.startIfIdle(30_000L) { originalTimeouts++ }
        assertTrue(original != null)
        assertNull(deadline.startIfIdle(90_000L) { replacementTimeouts++ })
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, originalTimeouts)
        assertEquals(0, replacementTimeouts)
    }

    @Test
    fun aMissingOrMalformedReplyCannotCancelTheRequestDeadline() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var timeouts = 0
        deadline.start(30_000L) { timeouts++ }
        assertNull(deadline.completeWithPayload<String>(null))
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, timeouts)
    }

    @Test
    fun aDecodedReplyCompletesTheDeadlineAndAllowsTheNextRoomRequest() = runTest {
        val deadline = ListenTogetherRequestDeadline(this)
        var firstTimeouts = 0
        deadline.startIfIdle(30_000L) { firstTimeouts++ }
        assertEquals("valid reply", deadline.completeWithPayload("valid reply"))
        assertTrue(deadline.startIfIdle(90_000L) {} != null)
        deadline.cancel()
        advanceTimeBy(90_000L)
        runCurrent()
        assertEquals(0, firstTimeouts)
    }

    @Test
    fun currentPendingProtocolRetrySendsOnceAfterDelay() = runTest {
        val connection = ListenTogetherRequestDeadline(this)
        val room = ListenTogetherRequestDeadline(this)
        val connectionAttempt = connection.start(60_000) {}
        connection.complete()
        val roomAttempt = room.start(30_000) {}
        val sends = mutableListOf<String>()
        launch {
            delay(500)
            connection.runIfCurrent(connectionAttempt) {
                room.runIfCurrent(roomAttempt, requirePending = true) { sends += "original-room" }
            }
        }
        runCurrent()
        assertTrue(sends.isEmpty())
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("original-room"), sends)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, sends.size)
        connection.cancel()
        room.cancel()
    }

    @Test
    fun completedCancelledReplacedAndTimedOutRoomsRejectPostDelayRetry() = runTest {
        for (change in listOf("complete", "cancel", "replace", "timeout")) {
            val room = ListenTogetherRequestDeadline(this)
            val attempt = room.start(100) {}
            var sends = 0
            launch {
                delay(500)
                room.runIfCurrent(attempt, requirePending = true) { sends++ }
            }
            runCurrent()
            when (change) {
                "complete" -> room.complete()
                "cancel" -> room.cancel()
                "replace" -> room.start(1_000) {}
            }
            advanceTimeBy(500)
            runCurrent()
            assertEquals(change, 0, sends)
            if (change == "complete") {
                assertTrue(room.isCurrent(attempt))
                var callbacks = 0
                room.runIfCurrent(attempt) { callbacks++ }
                assertEquals(1, callbacks)
            }
            room.cancel()
        }
    }

    @Test
    fun disconnectAndNewConnectionCannotSendOldRoomRetryIntoNewSocket() = runTest {
        for (replace in listOf(false, true)) {
            val connection = ListenTogetherRequestDeadline(this)
            val room = ListenTogetherRequestDeadline(this)
            val connectionAttempt = connection.start(60_000) {}
            connection.complete()
            val roomAttempt = room.start(30_000) {}
            var sends = 0
            launch {
                delay(500)
                connection.runIfCurrent(connectionAttempt) {
                    room.runIfCurrent(roomAttempt, requirePending = true) { sends++ }
                }
            }
            runCurrent()
            if (replace) connection.start(60_000) {} else connection.cancel()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(0, sends)
            assertTrue(room.isCurrent(roomAttempt))
            connection.cancel()
            room.cancel()
        }
    }

    @Test
    fun finalSendGuardRechecksCancellationEvenAfterDelayHasFinished() = runTest {
        val room = ListenTogetherRequestDeadline(this)
        val attempt = room.start(30_000) {}
        advanceTimeBy(500)
        runCurrent()
        assertTrue(room.isCurrent(attempt))
        room.cancel()
        var mutations = 0
        room.runIfCurrent(attempt, requirePending = true) {
            mutations++
            room.start(30_000) {}
        }
        assertEquals(0, mutations)
        val replacement = room.start(30_000) {}
        assertEquals(replacement, room.currentAttempt())
        room.runIfCurrent(replacement, requirePending = true) { mutations++ }
        assertEquals(1, mutations)
        room.cancel()
    }
}
