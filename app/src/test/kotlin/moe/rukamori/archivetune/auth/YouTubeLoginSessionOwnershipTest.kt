/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.AccountInfo
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeLoginSessionOwnershipTest {
    @Test
    fun pendingAndFailedCandidateNeverStagesGlobalOrPreferences() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        f.validate = { gate.await(); throw IOException("synthetic transient") }
        val attempt = f.repository.beginAttempt()
        val pending = async { f.complete(attempt, "B") }
        runCurrent()
        f.assertAccount("A")
        assertEquals(0, f.store.transactions)
        gate.complete(Unit)
        assertTrue(pending.await().isFailure)
        f.assertAccount("A")
        assertEquals(0, f.store.transactions)
    }

    @Test
    fun verifiedCandidateCommitsContextCookieVisitorAndNullableIdentityBeforePublication() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        f.validate = { candidate ->
            f.assertAccount("A")
            assertEquals("B", candidate.dataSyncId)
            AccountInfo("Verified B", null, null, null)
        }
        val session = f.login("B").getOrThrow()
        f.assertAccount("B", "Verified B")
        assertEquals("visitor-B", f.store.values.value[VisitorDataKey])
        assertEquals("", f.store.values.value[AccountEmailKey])
        assertEquals("", f.store.values.value[AccountChannelHandleKey])
        assertNull(f.store.values.value[AccountImageUrlKey])
        assertEquals(1, f.store.transactions)
        assertEquals("", session.accountEmail)
        assertTrue(f.repository.isCurrent(session.origin))
        f.publications.forEach { (auth, persisted) -> assertEquals(persisted.toPlaybackAuthState(), auth) }
    }

    @Test
    fun cancellationDuringValidationPreservesCommittedA() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        var cleaned = false
        f.validate = { try { awaitCancellation() } finally { cleaned = true } }
        val attempt = f.repository.beginAttempt()
        val pending = launch { f.complete(attempt, "B") }
        runCurrent()
        f.repository.cancel(attempt)
        pending.cancelAndJoin()
        assertTrue(cleaned)
        assertFalse(f.repository.isCurrent(attempt))
        assertEquals(0, f.store.transactions)
        f.assertAccount("A")
    }

    @Test
    fun newerVerifiedCRejectsDelayedBWithoutRollback() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        f.validate = { if (it.dataSyncId == "B") gate.await(); AuthFixture.identity(it) }
        val bAttempt = f.repository.beginAttempt()
        val b = async { f.complete(bAttempt, "B") }
        runCurrent()
        f.login("C").getOrThrow()
        gate.complete(Unit)
        assertTrue(b.await().exceptionOrNull() is StaleYouTubeLoginException)
        f.assertAccount("C")
        assertEquals(1, f.store.transactions)
    }

    @Test
    fun attemptRequiresBothCurrentGenerationAndCommittedBaseRevision() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val attempt = f.repository.beginAttempt()
        assertTrue(f.repository.isCurrent(attempt))
        assertFalse(f.repository.isCurrent(attempt.copy(baseRevision = attempt.baseRevision + 1)))
        assertFalse(f.repository.isCurrent(attempt.copy(generation = attempt.generation + 1)))
        assertTrue(f.repository.persistVisitor(f.repository.origin(), "refreshed-A"))
        assertFalse(f.repository.isCurrent(attempt))
        assertTrue(f.complete(attempt, "B").isFailure)
        f.assertAccount("A")
    }

    @Test
    fun cancelledAttemptRejectsLateVerifiedResultBeforeCommitReservation() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        f.validate = { gate.await(); AuthFixture.identity(it) }
        val attempt = f.repository.beginAttempt()
        val pending = async { f.complete(attempt, "B") }
        runCurrent()
        f.repository.cancel(attempt)
        gate.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is StaleYouTubeLoginException)
        assertEquals(0, f.store.transactions)
        f.assertAccount("A")
    }

    @Test
    fun persistenceFailurePublishesNoMixedCandidateSnapshot() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        f.store.failWrite = true
        assertTrue(f.login("B").exceptionOrNull() is IOException)
        f.assertAccount("A")
        assertTrue(f.publications.none { it.first.dataSyncId == "B" })
    }

    @Test
    fun reservedCommitSurvivesLaterCancelButLaterLogoutClearsItInOrder() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val barrier = CompletableDeferred<Unit>()
        f.store.barrier = barrier
        val attempt = f.repository.beginAttempt()
        var committed = false
        val commit = launch { f.complete(attempt, "B").getOrThrow(); committed = true }
        runCurrent() // Inside the non-cancellable persistence reservation, still not published.
        assertEquals(1, f.store.transactions)
        assertFalse(f.repository.isCurrent(f.repository.origin()))
        f.assertAccount("A")
        f.repository.cancel(attempt)
        val logout = launch { f.repository.logout() }
        runCurrent()
        assertFalse(logout.isCompleted)
        barrier.complete(Unit)
        commit.join()
        logout.join()
        assertTrue(committed)
        assertEquals(listOf("A", "B", null), f.publications.map { it.first.dataSyncId })
        assertEquals(PlaybackAuthState.EMPTY, f.runtime)
        assertNull(f.store.values.value[AccountNameKey])
    }

    @Test
    fun coroutineCancelAfterReservationCannotLeaveHalfCommittedSession() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val barrier = CompletableDeferred<Unit>()
        f.store.barrier = barrier
        val attempt = f.repository.beginAttempt()
        val commit = launch { f.complete(attempt, "B") }
        runCurrent()
        commit.cancel()
        barrier.complete(Unit)
        commit.join()
        f.assertAccount("B")
        assertEquals(1, f.store.transactions)
    }

    @Test
    fun queuedOldCollectorReconcilesLatestPersistenceInsteadOfOldPayload() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val oldEmission = f.store.values.value
        // App deliberately ignores each emission value and calls this same no-argument method.
        val queuedCollector: suspend () -> Unit = { f.repository.reconcilePersisted(); Unit }
        f.login("B").getOrThrow()
        assertEquals("A", oldEmission[DataSyncIdKey])
        queuedCollector()
        f.assertAccount("B")
    }

    @Test
    fun delayedVisitorIdentityOauthRepairAndPoTokenCannotResurrectObsoleteOrigins() = runTest {
        for (transition in listOf("logout", "switch", "identical-relogin")) {
            val f = AuthFixture(StandardTestDispatcher(testScheduler))
            f.repository.reconcilePersisted()
            val old = f.repository.origin()
            when (transition) {
                "logout" -> f.repository.logout()
                "switch" -> f.login("B").getOrThrow()
                else -> f.login("A").getOrThrow()
            }
            val before = f.store.values.value
            val runtime = f.runtime
            assertFalse(transition, f.repository.persistVisitor(old, "late-visitor"))
            assertFalse(transition, f.repository.persistIdentity(old, AccountInfo("late identity", null, null, null)))
            var oauthEditCalled = false
            assertFalse(transition, f.repository.editOwned(old) { oauthEditCalled = true; it[InnerTubeOAuthTokenKey] = "synthetic-late-oauth" })
            assertFalse(oauthEditCalled)
            assertFalse(transition, f.repository.persistRepair(old, old.authState.copy(visitorData = "late-repair")))
            assertFalse(transition, f.repository.publishRepair(old, old.authState.copy(poTokenGvs = "synthetic-late-po")))
            assertTrue(f.repository.refreshIdentity(old).exceptionOrNull() is StaleYouTubeLoginException)
            assertEquals(before, f.store.values.value)
            assertEquals(runtime, f.runtime)
        }
    }

    @Test
    fun identityRefreshAlreadyInFlightCannotPersistAfterLogoutOrSwitch() = runTest {
        for (switch in listOf(false, true)) {
            val f = AuthFixture(StandardTestDispatcher(testScheduler))
            f.repository.reconcilePersisted()
            val gate = CompletableDeferred<Unit>()
            f.validate = { if (it.dataSyncId == "A") gate.await(); AuthFixture.identity(it) }
            val refresh = async { f.repository.refreshIdentity(f.repository.origin()) }
            runCurrent()
            if (switch) f.login("B").getOrThrow() else f.repository.logout()
            val latest = f.store.values.value
            gate.complete(Unit)
            assertTrue(refresh.await().exceptionOrNull() is StaleYouTubeLoginException)
            assertEquals(latest, f.store.values.value)
            assertEquals(if (switch) "B" else null, f.runtime.dataSyncId)
        }
    }

    @Test
    fun currentOwnedRefreshAndVerifiedChannelAndSavedSwitchStillSucceed() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        f.repository.reconcilePersisted()
        assertTrue(f.repository.refreshIdentity(f.repository.origin()).isSuccess)
        assertTrue(f.repository.persistVisitor(f.repository.origin(), "current-visitor"))
        val switched = f.repository.switchChannel("brand-B||user-B").getOrThrow()
        assertEquals("brand-B||user-B", switched.dataSyncId)
        assertEquals("SAPISID=synthetic-A", switched.cookie)
        val saved = SavedAccount("saved-C", "Unverified label", "", "", "SAPISID=synthetic-C",
            "visitor-C", "C", true, "synthetic-playlists")
        f.repository.switchSavedAccount(saved).getOrThrow()
        f.assertAccount("C")
        assertEquals(true, f.store.values.value[YtmSyncKey])
        assertEquals("synthetic-playlists", f.store.values.value[SelectedYtmPlaylistsKey])
    }

    @Test
    fun loginLogoutAndRepairPreservePersonalPoolSpotifyAndSavedRecords() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val keys = setOf(SavedAccountsKey, SpotifySpDcKey, SpotifyStreamSpDcKey, SpotifyLibraryPlaylistsCacheKey)
        val before = keys.associateWith { f.store.values.value[it] }
        val poolBefore = f.store.values.value[UsePoolAccountsKey]
        f.login("B").getOrThrow()
        assertTrue(f.repository.publishRepair(f.repository.origin(), f.runtime.copy(poTokenPlayer = "synthetic-po")))
        f.repository.logout()
        assertEquals(before, keys.associateWith { f.store.values.value[it] })
        assertEquals(poolBefore, f.store.values.value[UsePoolAccountsKey])
    }
}

/** Fake only the persistence/network/runtime edges; every ownership decision is the repository's. */
internal class AuthFixture(dispatcher: CoroutineDispatcher) {
    val store = AuthStore(mutablePreferencesOf(
        InnerTubeCookieKey to "SAPISID=synthetic-A", DataSyncIdKey to "A", VisitorDataKey to "visitor-A",
        AccountNameKey to "Verified A", AccountEmailKey to "synthetic@example.invalid",
        AccountImageUrlKey to "https://images.invalid/synthetic-A", SavedAccountsKey to "synthetic-saved-records",
        SpotifySpDcKey to "synthetic-catalog", SpotifyStreamSpDcKey to "synthetic-stream",
        SpotifyLibraryPlaylistsCacheKey to "synthetic-library", UsePoolAccountsKey to true,
    ))
    var runtime = store.values.value.toPlaybackAuthState()
    val publications = mutableListOf<Pair<PlaybackAuthState, Preferences>>()
    var validate: suspend (PlaybackAuthState) -> AccountInfo = { identity(it) }
    val repository = YouTubeLoginRepository(store, { runtime }, {
        publications += it to store.values.value
        runtime = it
    }, { validate(it) }, dispatcher)

    suspend fun complete(attempt: YouTubeLoginAttempt, id: String) =
        repository.completeLogin(attempt, "SAPISID=synthetic-$id", "visitor-$id", id)
    suspend fun login(id: String) = complete(repository.beginAttempt(), id)
    fun assertAccount(id: String, name: String = "Verified $id") {
        assertEquals(id, store.values.value[DataSyncIdKey])
        assertEquals("SAPISID=synthetic-$id", store.values.value[InnerTubeCookieKey])
        assertEquals(name, store.values.value[AccountNameKey])
        assertEquals(id, runtime.dataSyncId)
        assertEquals("SAPISID=synthetic-$id", runtime.cookie)
    }
    companion object {
        fun identity(auth: PlaybackAuthState) = AccountInfo("Verified ${auth.dataSyncId}", null, null, null)
    }
}

internal class AuthStore(initial: Preferences) : DataStore<Preferences> {
    val values = MutableStateFlow(initial)
    override val data: Flow<Preferences> = values
    var transactions = 0
    var barrier: CompletableDeferred<Unit>? = null
    var failWrite = false
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        transactions += 1
        val next = transform(values.value)
        barrier?.await()
        if (failWrite) throw IOException("synthetic persistence failure")
        values.value = next
        return next
    }
}
