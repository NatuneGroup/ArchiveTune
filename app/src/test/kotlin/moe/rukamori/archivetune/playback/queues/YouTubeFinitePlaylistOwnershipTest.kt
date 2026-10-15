/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback.queues

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.models.toMediaMetadata
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeFinitePlaylistOwnershipTest {
    @Test fun pageFailureRetainsAcceptedItemsAndTokenThenRetryAddsEachOccurrenceOnce() = runTest {
        val source = FiniteFixtureSource()
        val queue = finiteQueue()
        queue.accept(queue.proposeInitial(source))
        val accepted = queue.snapshot
        val healthyNext = source.next
        source.next = { throw IllegalStateException("synthetic transport failure") }
        try { queue.proposeNext(source); fail("Expected transport failure") }
        catch (_: IllegalStateException) { queue.fail() }
        assertEquals(accepted.entries, queue.snapshot.entries)
        assertEquals(accepted.continuation, queue.snapshot.continuation)
        assertEquals(accepted.consumedTokens, queue.snapshot.consumedTokens)
        assertEquals(FinitePlaylistPhase.FAILED, queue.snapshot.phase)
        assertFalse(queue.confirmedExhausted)
        source.next = healthyNext
        queue.accept(queue.proposeNext(source))
        queue.accept(queue.proposeNext(source))
        assertEquals(135, queue.snapshot.entries.size)
        assertEquals(135, queue.snapshot.entries.map { it.key }.toSet().size)
        assertTrue(queue.confirmedExhausted)
        assertEquals(2, source.calls.count { it == "next:p2:fixture" })
    }

    @Test fun repeatedBlankAndMalformedTerminalPagesNeverAdvanceAcceptedSnapshot() = runTest {
        for (bad in listOf(FinitePlaylistChunk(emptyList(), "p2"),
            FinitePlaylistChunk(finiteSongs().take(1), " "), FinitePlaylistChunk(emptyList(), null))) {
            val source = FiniteFixtureSource()
            val queue = finiteQueue()
            queue.accept(queue.proposeInitial(source))
            val accepted = queue.snapshot
            source.next = { bad }
            expectIncomplete { queue.proposeNext(source) }
            assertSame(accepted, queue.snapshot)
            assertFalse(queue.confirmedExhausted)
        }
    }

    @Test fun unknownInitializingFailedAndExhaustedAreNotInterchangeable() {
        for (phase in FinitePlaylistPhase.entries) {
            val queue = YouTubePlaylistQueue(finiteQueue().endpoint, snapshot = FinitePlaylistSnapshot(phase = phase))
            assertEquals(phase == FinitePlaylistPhase.EXHAUSTED, queue.confirmedExhausted)
            assertEquals(phase == FinitePlaylistPhase.PAGEABLE, queue.hasNextPage())
        }
    }

    @Test fun emptyInitialSuccessRequiresExplicitNumericZeroCount() = runTest {
        for (count in listOf(null, "unknown", "135 songs", "0 of 135")) {
            expectIncomplete { FinitePlaylistLoader.complete("fixture", FiniteFixtureSource(finitePage(emptyList(), null, count))) }
        }
        val result = FinitePlaylistLoader.complete("fixture", FiniteFixtureSource(finitePage(emptyList(), null, "0 songs")))
        assertTrue(result.songs.isEmpty())
        assertNull(result.songsContinuation)
    }

    @Test fun repeatedEmptyTokensStopImmediatelyRatherThanClaimingCompletion() = runTest {
        val source = FiniteFixtureSource()
        source.next = { FinitePlaylistChunk(emptyList(), "p2") }
        expectIncomplete { FinitePlaylistLoader.complete("fixture", source) }
        assertEquals(2, source.calls.size)
    }

    @Test fun requestBoundCountsInitialAndEveryAdvancingEmptyContinuation() = runTest {
        val source = FiniteFixtureSource(finitePage(emptyList(), "1"))
        source.next = { token -> FinitePlaylistChunk(emptyList(), (token.toInt() + 1).toString()) }
        expectIncomplete { FinitePlaylistLoader.complete("fixture", source) }
        assertEquals(200, source.calls.size)
        assertEquals(FinitePlaylistLoader.MAX_REQUESTS, source.calls.size)
    }

    @Test fun selectedReconciliationConsumesTheSameRequestBudgetAndReportsItsUsage() = runTest {
        val song = finiteSongs().last()
        val queue = YouTubePlaylistQueue(song.endpoint!!, song.toFixtureMetadata())
        val source = FiniteFixtureSource()
        val proposal = queue.proposeInitial(source)
        assertEquals(3, proposal.requestsUsed)
        assertEquals(3, source.calls.size)
        source.first = finitePage(emptyList(), "1")
        source.next = { token -> FinitePlaylistChunk(emptyList(), (token.toInt() + 1).toString()) }
        source.calls.clear()
        expectIncomplete { queue.proposeInitial(source) }
        assertEquals(200, source.calls.size)
        assertEquals(FinitePlaylistPhase.INITIALIZING, queue.snapshot.phase)
    }

    @Test fun deadlineIsRecoverableIncompleteRatherThanPartialSuccess() = runTest {
        val source = FiniteFixtureSource()
        source.next = { delay(FinitePlaylistLoader.TIMEOUT_MS + 1); FinitePlaylistChunk(finiteSongs(), null) }
        val started = testScheduler.currentTime
        expectIncomplete { FinitePlaylistLoader.complete("fixture", source) }
        assertEquals(120_000L, testScheduler.currentTime - started)
        assertEquals(2, source.calls.size)
    }

    @Test fun callerCancellationRemainsCancellationAndCommitsNothing() = runTest {
        val source = FiniteFixtureSource()
        val entered = CompletableDeferred<Unit>()
        source.next = { entered.complete(Unit); delay(Long.MAX_VALUE); error("unreachable") }
        var commits = 0
        var cancelled = false
        val job = launch {
            try { withCompleteYouTubePlaylist("fixture", source) { commits++ } }
            catch (error: CancellationException) { cancelled = true; throw error }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(0, commits)
    }

    @Test fun lateTransportResponseAfterCancellationStillCannotEnterCommit() = runTest {
        val source = FiniteFixtureSource()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.next = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            FinitePlaylistChunk(finiteSongs().drop(45), null)
        }
        var commits = 0
        val job = launch { withCompleteYouTubePlaylist("fixture", source) { commits++ } }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, commits)
    }

    @Test fun rapidTailRequestsHaveOneOwnerAndPageItemsAndTokenPublishTogether() = runTest {
        val source = FiniteFixtureSource()
        val queue = finiteQueue()
        queue.accept(queue.proposeInitial(source))
        val owner = PlaylistLoadOwner()
        val ticket = owner.begin(7L, queue)!!
        val release = CompletableDeferred<Unit>()
        val healthy = source.next
        source.next = { token -> release.await(); healthy(token) }
        var observations = 0
        val job = launch {
            val page = queue.proposeNext(source)
            ticket.acceptIfCurrent(7L, queue) {
                queue.accept(page)
                assertEquals(90, queue.snapshot.entries.size)
                assertEquals("p3", queue.snapshot.continuation)
                observations++
            }
            ticket.finish()
        }
        runCurrent()
        repeat(20) { assertNull(owner.begin(7L, queue)) }
        assertEquals(45, queue.snapshot.entries.size)
        assertEquals("p2", queue.snapshot.continuation)
        assertEquals(1, source.calls.count { it == "next:p2:fixture" })
        release.complete(Unit)
        job.join()
        assertEquals(1, observations)
        assertNotNull(owner.begin(7L, queue))
    }

    @Test fun staleInitialPageAndLegacyRestoreResponsesCannotMutateQueuePlayerOrTitle() = runTest {
        for (stage in listOf("initial", "page", "restore")) {
            for (invalidation in listOf("replace", "clear", "cancel")) {
                val source = FiniteFixtureSource()
                val queue = if (stage == "restore") YouTubePlaylistQueue(finiteQueue().endpoint,
                    snapshot = FinitePlaylistSnapshot(phase = FinitePlaylistPhase.UNKNOWN),
                    legacyItems = finiteSongs().take(2).map { it.toFixtureMetadata() }) else finiteQueue()
                if (stage == "page") queue.accept(queue.proposeInitial(source))
                val accepted = queue.snapshot
                val owner = PlaylistLoadOwner()
                var generation = 8L
                var current: Any = queue
                val ticket = owner.begin(generation, current)!!
                val release = CompletableDeferred<Unit>()
                val initial = source.initial
                val next = source.next
                source.initial = { withContext(NonCancellable) { release.await() }; initial() }
                source.next = { token -> withContext(NonCancellable) { release.await() }; next(token) }
                var title = "old title"
                val playerEffects = mutableListOf<String>()
                val job = launch {
                    val proposal = if (stage == "page") queue.proposeNext(source) else queue.proposeInitial(source)
                    ticket.acceptIfCurrent(generation, current) {
                        queue.accept(proposal)
                        title = "stale title"
                        playerEffects += "set/seek/prepare"
                    }
                    ticket.finish()
                }
                runCurrent()
                when (invalidation) {
                    "replace" -> { generation++; current = Any() }
                    "clear" -> { generation++; current = QueueEmptyIdentity }
                    else -> { owner.cancel(); generation++ }
                }
                val newer = owner.begin(generation, current)!!
                release.complete(Unit)
                job.join()
                assertSame(accepted, queue.snapshot)
                assertEquals("old title", title)
                assertTrue(playerEffects.isEmpty())
                assertTrue(newer.isCurrent(generation, current))
                newer.finish()
            }
        }
    }

    private object QueueEmptyIdentity
}

internal fun moe.rukamori.archivetune.innertube.models.SongItem.toFixtureMetadata() =
    toMediaMetadata()
