/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.menu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.queues.IncompleteYouTubePlaylistException
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddToPlaylistOperationTest {
    @Test fun strictSupplierFailureClearsLoadingAndRetryInsertsCompleteSongsOnce() = runTest {
        val owner = AddToPlaylistOperation(this)
        val target = mutableListOf("existing")
        var successCallbacks = 0
        val failed = owner.start(
            targets = listOf(target),
            load = { throw IncompleteYouTubePlaylistException() },
            insert = { targets, songs -> targets.single().addAll(songs) },
            onSuccess = { successCallbacks++ },
        )
        failed.join()
        assertFalse(failed.isCancelled)
        assertEquals(PlaylistAddState(failed = true), owner.state.value)
        assertEquals(listOf("existing"), target)
        assertEquals(0, successCallbacks)

        val complete = (1..135).map { if (it in listOf(2, 44, 91)) "duplicate-video" else "video$it" }
        val retry = owner.start(
            targets = listOf(target),
            load = { complete },
            insert = { targets, songs -> ensureCurrent(); targets.single().addAll(songs) },
            onSuccess = { successCallbacks++ },
        )
        assertEquals(PlaylistAddState(loading = true), owner.state.value)
        retry.join()
        assertEquals(listOf("existing") + complete, target)
        assertEquals(3, target.count { it == "duplicate-video" })
        assertEquals(1, successCallbacks)
        assertEquals(PlaylistAddState(), owner.state.value)
    }

    @Test fun providerFailureIsSanitizedAndCancellationIsNotReportedAsIncomplete() = runTest {
        val owner = AddToPlaylistOperation(this)
        var insertions = 0
        owner.start(
            targets = listOf("local"),
            load = { error("Synthetic private provider detail must not enter UI state") },
            insert = { _, _ -> insertions++ },
            onSuccess = { fail("Failed supplier must not succeed") },
        ).join()
        assertEquals(PlaylistAddState(failed = true), owner.state.value)
        val cancelled = owner.start(
            targets = listOf("local"),
            load = { throw CancellationException("Synthetic supplier cancellation") },
            insert = { _, _ -> insertions++ },
            onSuccess = { fail("Cancelled supplier must not succeed") },
        )
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals(0, insertions)
        assertEquals(PlaylistAddState(), owner.state.value)
    }

    @Test fun visibleCancelRejectsLateFetchWithoutExternalJobCancellation() = runTest {
        val owner = AddToPlaylistOperation(this)
        val release = CompletableDeferred<Unit>()
        var insertions = 0
        val pending = owner.start(
            targets = listOf("local"),
            load = { withContext(NonCancellable) { release.await() }; listOf("song") },
            insert = { _, _ -> insertions++ },
            onSuccess = { fail("Dismissed operation must not publish success") },
        )
        runCurrent()
        assertTrue(owner.state.value.loading)
        // This is the same method invoked by both Dialog Cancel and onDismissRequest.
        owner.cancel()
        assertEquals(PlaylistAddState(), owner.state.value)
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, insertions)
        assertEquals(PlaylistAddState(), owner.state.value)
    }

    @Test fun disposalDuringPendingTargetLookupPreventsInsertionAtConsumerCommitGuard() = runTest {
        val owner = AddToPlaylistOperation(this)
        val releaseLookup = CompletableDeferred<Unit>()
        var insertions = 0
        val pending = owner.start(
            targets = listOf("local"),
            load = { listOf("song") },
            insert = { _, _ ->
                // Models the real database duplicate lookup before target insertion.
                withContext(NonCancellable) { releaseLookup.await() }
                ensureCurrent()
                insertions++
            },
            onSuccess = { fail("Disposed operation must not publish success") },
        )
        runCurrent()
        // DisposableEffect invalidation reaches this owner, not merely a visibility flag.
        owner.cancel()
        releaseLookup.complete(Unit)
        pending.join()
        assertEquals(0, insertions)
        assertEquals(PlaylistAddState(), owner.state.value)
    }

    @Test fun reopenedOperationRejectsOldFailureAndFinallyWithoutClearingNewLoading() = runTest {
        val owner = AddToPlaylistOperation(this)
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val committedTargets = mutableListOf<String>()
        val old = owner.start(
            targets = listOf("old target"),
            load = {
                withContext(NonCancellable) { oldRelease.await() }
                throw IllegalStateException("Late old provider failure")
            },
            insert = { targets, _ -> committedTargets.addAll(targets) },
            onSuccess = { fail("Old operation must not succeed") },
        )
        runCurrent()
        owner.cancel() // Close the old dialog, then reopen and select a different target.
        val reopened = owner.start(
            targets = listOf("new target"),
            load = { newRelease.await(); listOf("song") },
            insert = { targets, _ -> ensureCurrent(); committedTargets.addAll(targets) },
            onSuccess = {},
        )
        runCurrent()
        oldRelease.complete(Unit)
        old.join()
        assertEquals(PlaylistAddState(loading = true), owner.state.value)
        assertTrue(committedTargets.isEmpty())
        newRelease.complete(Unit)
        reopened.join()
        assertEquals(listOf("new target"), committedTargets)
        assertEquals(PlaylistAddState(), owner.state.value)
    }

    @Test fun replacementSnapshotsTargetsBeforeAwaitAndRejectsOldSuccess() = runTest {
        val owner = AddToPlaylistOperation(this)
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val selection = mutableListOf("selected before await")
        val committedTargets = mutableListOf<String>()
        var completions = 0
        val old = owner.start(
            targets = listOf("replaced playlist"),
            load = { withContext(NonCancellable) { oldRelease.await() }; listOf("old song") },
            insert = { targets, _ -> committedTargets.addAll(targets) },
            onSuccess = { fail("Replaced playlist must not succeed") },
        )
        runCurrent()
        // start itself owns replacement cancellation; the test never cancels a Job.
        val current = owner.start(
            targets = selection,
            load = { newRelease.await(); listOf("new song") },
            insert = { targets, songs ->
                ensureCurrent()
                assertEquals(listOf("new song"), songs)
                committedTargets.addAll(targets)
            },
            onSuccess = { completions++ },
        )
        selection.clear()
        selection += "changed after await"
        runCurrent()
        oldRelease.complete(Unit)
        old.join()
        assertTrue(owner.state.value.loading)
        assertTrue(committedTargets.isEmpty())
        newRelease.complete(Unit)
        current.join()
        assertEquals(listOf("selected before await"), committedTargets)
        assertEquals(1, completions)
        assertEquals(PlaylistAddState(), owner.state.value)
    }

    @Test fun cancellationDoesNotUndoAnAlreadyCommittedTargetOrPublishStaleCompletion() = runTest {
        val owner = AddToPlaylistOperation(this)
        val release = CompletableDeferred<Unit>()
        val target = mutableListOf<String>()
        var completions = 0
        val pending = owner.start(
            targets = listOf(target),
            load = { listOf("committed song") },
            insert = { targets, songs ->
                ensureCurrent()
                targets.single().addAll(songs)
                withContext(NonCancellable) { release.await() }
            },
            onSuccess = { completions++ },
        )
        runCurrent()
        assertEquals(listOf("committed song"), target)
        owner.cancel()
        release.complete(Unit)
        pending.join()
        assertEquals(listOf("committed song"), target)
        assertEquals(0, completions)
        assertEquals(PlaylistAddState(), owner.state.value)
    }
}
