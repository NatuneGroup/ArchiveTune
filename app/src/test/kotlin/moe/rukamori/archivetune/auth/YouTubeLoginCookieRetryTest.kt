/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.viewmodels.*
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeLoginCookieRetryTest {
    private val dispatcher = StandardTestDispatcher()
    private val views = mutableListOf<LoginViewModel>()
    @Before fun installMain() { Dispatchers.setMain(dispatcher) }
    @After fun releaseMain() {
        views.forEach { it.release(); it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }
    private fun view(f: AuthFixture) = LoginViewModel(f.repository).also { views += it; it.open() }

    @Test
    fun preferredNonblankCookieWinsAcrossMusicWwwAndApexConflicts() {
        val merged = mergePreferredYouTubeCookies(listOf(
            "SAPISID=synthetic-music; SID=; visitor=synthetic=a=b",
            "SAPISID=synthetic-www; SID=synthetic-www-sid; visitor=stale",
            "SAPISID=; SID=synthetic-apex-sid; other=synthetic-apex",
        ))
        assertEquals("SAPISID=synthetic-music; visitor=synthetic=a=b; SID=synthetic-www-sid; other=synthetic-apex", merged)
    }

    @Test
    fun blankOriginsCannotEraseSecureSigningAliasesOrEqualsInValues() {
        val merged = mergePreferredYouTubeCookies(listOf(null, "SAPISID= ; __Secure-3PAPISID=synthetic==",
            "SAPISID=synthetic-lower; __Secure-3PAPISID=stale; __Secure-1PAPISID=synthetic-one", ""))
        assertEquals("__Secure-3PAPISID=synthetic==; SAPISID=synthetic-lower; __Secure-1PAPISID=synthetic-one", merged)
        assertTrue(hasYouTubeLoginCookie(merged))
        assertTrue(hasYouTubeLoginCookie("__Secure-1PAPISID=synthetic-one"))
        assertTrue(hasYouTubeLoginCookie("__Secure-3PAPISID=synthetic-three"))
        assertFalse(hasYouTubeLoginCookie("SID=synthetic-not-signing"))
        assertFalse(hasYouTubeLoginCookie("SAPISID= ; __Secure-3PAPISID= "))
        assertNull(mergePreferredYouTubeCookies(listOf(null, "", "=bad; broken; SAPISID= ")))
    }

    @Test
    fun transientFailureThenIdenticalContextExplicitRetryValidatesAgainExactlyOnce() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        var calls = 0
        f.validate = { calls++; if (calls == 1) throw IOException("synthetic transient"); AuthFixture.identity(it) }
        val vm = view(f)
        runCurrent()
        val oldView = vm.webRevision.value
        vm.onContextExtracted(oldView, "SAPISID=synthetic-B", "visitor-B", "B")
        vm.onContextExtracted(oldView, "SAPISID=synthetic-B", "visitor-B", "B")
        runCurrent()
        assertEquals(LoginScreenState.Error(LoginError.LoginFailed), vm.screenState.value)
        assertEquals(1, calls)
        f.assertAccount("A")
        vm.retry()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.webRevision.value > oldView)
        assertTrue(vm.screenState.value is LoginScreenState.Success)
        f.assertAccount("B")
        assertTrue(vm.consumeSuccess())
        assertFalse(vm.consumeSuccess())
    }

    @Test
    fun cookieReadinessIsNotVerificationAndMissingContextRemainsVisible() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        var calls = 0
        f.validate = { calls++; AuthFixture.identity(it) }
        val vm = view(f)
        runCurrent()
        val revision = vm.webRevision.value
        vm.onContextExtracted(revision, "__Secure-3PAPISID=synthetic-alias", null, null)
        vm.onPageReady(revision)
        assertFalse(vm.screenState.value is LoginScreenState.Success)
        assertEquals(0, calls)
        assertEquals(0, f.store.transactions)
        vm.onExtractionExhausted(revision)
        assertEquals(LoginScreenState.Error(LoginError.MissingDataSyncId), vm.screenState.value)
        f.assertAccount("A")
    }

    @Test
    fun duplicateSnapshotsSingleFlightAndChangedSelectedContextCancelsObsoleteLease() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        val mutex = Mutex()
        val client = arrayOf(PlaybackAuthState.EMPTY)
        val entered = mutableListOf<String?>()
        val cleared = mutableListOf<PlaybackAuthState>()
        f.validate = { candidate ->
            withPrivateYouTubeCandidate(client, mutex, candidate, { c, auth -> c[0] = auth; cleared += auth }) {
                entered += it[0].dataSyncId
                if (it[0].dataSyncId == "B") awaitCancellation()
                AuthFixture.identity(it[0])
            }
        }
        val vm = view(f)
        runCurrent()
        val revision = vm.webRevision.value
        repeat(3) { vm.onContextExtracted(revision, "SAPISID=synthetic-B", "visitor-B", "B") }
        runCurrent()
        assertEquals(listOf("B"), entered)
        f.assertAccount("A")
        vm.onContextExtracted(revision, "SAPISID=synthetic-C", "visitor-C", "C")
        runCurrent()
        assertEquals(listOf("B", "C"), entered)
        assertEquals(listOf("B", null, "C", null), cleared.map { it.dataSyncId })
        assertEquals(PlaybackAuthState.EMPTY, client[0])
        f.assertAccount("C")
        assertTrue(vm.screenState.value is LoginScreenState.Success)
    }

    @Test
    fun absentSigningCookieFailsBeforeAnyPrivateValidationOrPersistence() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        var calls = 0
        f.validate = { calls++; AuthFixture.identity(it) }
        val result = f.repository.completeLogin(f.repository.beginAttempt(), "SID=synthetic", "visitor-B", "B")
        assertTrue(result.isFailure)
        assertEquals(0, calls)
        assertEquals(0, f.store.transactions)
        f.assertAccount("A")
    }
}
