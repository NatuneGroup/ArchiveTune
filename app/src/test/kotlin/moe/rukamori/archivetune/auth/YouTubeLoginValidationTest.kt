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
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.response.AccountMenuResponse
import moe.rukamori.archivetune.utils.YTPlayerUtils.InvalidPlaybackLoginContextException
import moe.rukamori.archivetune.utils.YTPlayerUtils.LoginRequiredForPlaybackException
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeLoginValidationTest {
    private class Client {
        var auth = PlaybackAuthState.EMPTY
        val applied = mutableListOf<PlaybackAuthState>()
        fun apply(value: PlaybackAuthState) { auth = value; applied += value }
    }
    private val b = PlaybackAuthState(cookie = "SAPISID=synthetic-B", visitorData = "visitor-B", dataSyncId = "brand-B||user-B")
    private val c = PlaybackAuthState(cookie = "SAPISID=synthetic-C", dataSyncId = "C")

    @Test
    fun reusablePrivateCandidateLeaseSerializesRequestsAndAlwaysClearsBetweenCandidates() = runTest {
        val client = Client()
        val mutex = Mutex()
        val gate = CompletableDeferred<Unit>()
        val observed = mutableListOf<String?>()
        val first = async {
            withPrivateYouTubeCandidate(client, mutex, b, Client::apply) {
                observed += it.auth.dataSyncId
                gate.await()
                assertEquals(b, it.auth)
            }
        }
        runCurrent()
        val second = async {
            withPrivateYouTubeCandidate(client, mutex, c, Client::apply) { observed += it.auth.dataSyncId }
        }
        runCurrent()
        assertEquals(listOf("brand-B||user-B"), observed)
        assertEquals(b, client.auth)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("brand-B||user-B", "C"), observed)
        assertEquals(listOf(b, PlaybackAuthState.EMPTY, c, PlaybackAuthState.EMPTY), client.applied)
        assertEquals(PlaybackAuthState.EMPTY, client.auth)
    }

    @Test
    fun failureAndCancellationClearPrivateAuthAndReleaseTheValidationMutex() = runTest {
        val client = Client()
        val mutex = Mutex()
        val failure = IOException("synthetic request failure")
        try {
            withPrivateYouTubeCandidate(client, mutex, b, Client::apply) { throw failure }
            fail("failure must propagate")
        } catch (error: IOException) { assertSame(failure, error) }
        assertEquals(PlaybackAuthState.EMPTY, client.auth)
        val cancelled = launch {
            withPrivateYouTubeCandidate(client, mutex, b, Client::apply) { awaitCancellation() }
        }
        runCurrent()
        assertEquals(b, client.auth)
        cancelled.cancelAndJoin()
        assertEquals(PlaybackAuthState.EMPTY, client.auth)
        assertFalse(mutex.isLocked)
        withPrivateYouTubeCandidate(client, mutex, c, Client::apply) { assertEquals(c, it.auth) }
        assertEquals(PlaybackAuthState.EMPTY, client.auth)
    }

    @Test
    fun configurationFailureInsideLeaseStillClearsPrivateCandidate() = runTest {
        val client = Client()
        val mutex = Mutex()
        try {
            withPrivateYouTubeCandidate(client, mutex, b, { target, auth ->
                target.apply(auth)
                if (auth != PlaybackAuthState.EMPTY) throw IOException("synthetic configuration failure")
            }) { fail("no request after failed configuration") }
            fail("configuration failure must propagate")
        } catch (_: IOException) { }
        assertEquals(listOf(b, PlaybackAuthState.EMPTY), client.applied)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun identityComesFromOneAccountMenuReadWithoutAnyChannelRead() = runTest {
        var menuCalls = 0
        val identity = validateYouTubeCandidateContext {
            menuCalls++
            menu("Synthetic B")
        }
        assertEquals(1, menuCalls)
        assertEquals("Synthetic B", identity.name)
    }

    @Test
    fun missingAmbiguousOrNamelessFinalMenuFailsWithoutCommittingCandidate() = runTest {
        for (finalMenu in listOf(menu("Synthetic B", 0), menu("Synthetic B", 2), menu(null))) {
            val f = AuthFixture(StandardTestDispatcher(testScheduler))
            f.validate = { validateYouTubeCandidateContext { finalMenu } }
            assertTrue(f.login("B").exceptionOrNull() is UnverifiedYouTubeContextException)
            assertEquals(0, f.store.transactions)
            f.assertAccount("A")
        }
    }

    @Test
    fun terminalPlaybackAuthErrorsReturnOriginalFailureWithOneCallAndNoContextlessRetry() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val errors = listOf(
            InvalidPlaybackLoginContextException("synthetic-video", "https://music.youtube.com/watch?v=synthetic-video", IOException("synthetic")),
            LoginRequiredForPlaybackException("synthetic-video", "https://music.youtube.com/watch?v=synthetic-video", "synthetic confirmation"),
        )
        for (error in errors) {
            var calls = 0
            val result = retryWithoutPlaybackLoginContext(f.repository, { f.runtime }) {
                calls++
                f.assertAccount("A")
                Result.failure<String>(error)
            }
            assertSame(error, result.exceptionOrNull())
            assertEquals(1, calls)
            assertEquals(0, f.store.transactions)
            f.assertAccount("A")
        }
    }

    @Test
    fun successfulCurrentRepairPersistsButDelayedPostLogoutRepairDoesNot() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val success = retryWithoutPlaybackLoginContext(f.repository, { f.runtime.copy(visitorData = "refreshed") }) { Result.success("ok") }
        assertEquals("ok", success.getOrThrow())
        assertEquals("refreshed", f.runtime.visitorData)
        val delayed = f.runtime.copy(visitorData = "late")
        val result = retryWithoutPlaybackLoginContext(f.repository, { delayed }) {
            f.repository.logout()
            Result.success("resolved-before-logout")
        }
        assertTrue(result.isSuccess)
        assertEquals(PlaybackAuthState.EMPTY, f.runtime)
        assertNull(f.store.values.value[moe.rukamori.archivetune.constants.VisitorDataKey])
    }

    // Exact public e2bf1df AccountMenuResponse/Runs/Run/Thumbnails serialization shape.
    private fun menu(name: String?, count: Int = 1): AccountMenuResponse {
        val activeHeader = buildJsonObject {
            putJsonObject("accountName") {
                putJsonArray("runs") {
                    name?.let { add(buildJsonObject { put("text", it); put("navigationEndpoint", JsonNull) }) }
                }
            }
            put("email", JsonNull)
            put("channelHandle", JsonNull)
            putJsonObject("accountPhoto") { putJsonArray("thumbnails") { } }
        }
        val action = buildJsonObject {
            putJsonObject("openPopupAction") {
                putJsonObject("popup") {
                    putJsonObject("multiPageMenuRenderer") {
                        putJsonObject("header") { put("activeAccountHeaderRenderer", activeHeader) }
                    }
                }
            }
        }
        return Json.decodeFromJsonElement<AccountMenuResponse>(buildJsonObject {
            putJsonArray("actions") { repeat(count) { add(action) } }
        })
    }
}
