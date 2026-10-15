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
    fun selectedCompoundBrandIsPreservedInsteadOfDifferentDefaultAccount() {
        val response = response("brand-B||user-B", listOf(
            item("default-A", selected = false), item("brand-B", selected = true),
        ))
        verifySelectedYouTubeContext(response, "brand-B||user-B")
        verifySelectedYouTubeContext(response, "brand-B%7C%7Cuser-B")
        assertUnverified(response, "default-A")
    }

    @Test
    fun missingMismatchedAndAmbiguousReturnedContextFailClosed() {
        val selected = listOf(item("brand-B"))
        assertUnverified(response(null, selected), "brand-B||user-B")
        assertUnverified(response("different-default", selected), "brand-B||user-B")
        assertUnverified(response("brand-B||user-B", emptyList()), "brand-B||user-B")
        assertUnverified(response("brand-B||user-B", listOf(item("brand-B"), item("brand-B"))), "brand-B||user-B")
        assertUnverified(Json.parseToJsonElement("{}"), "brand-B||user-B")
        try {
            verifySelectedYouTubeContext(response("brand-B", selected), " ")
            fail("blank context cannot verify")
        } catch (_: MissingYouTubeDataSyncIdException) { }
    }

    @Test
    fun selectedIdsNotDisplayNamesProveContextAndConflictingIdsFail() {
        val sameName = "Identical synthetic display name"
        val correct = item("brand-B", name = sameName)
        val different = item("brand-C", name = sameName)
        verifySelectedYouTubeContext(response("brand-B||user-B", listOf(correct)), "brand-B||user-B")
        assertUnverified(response("brand-B||user-B", listOf(different)), "brand-B||user-B")
        val conflicting = item("brand-B", extraId = "brand-C")
        assertUnverified(response("brand-B||user-B", listOf(conflicting)), "brand-B||user-B")
    }

    @Test
    fun disabledMissingChannelUnselectedAndUnsupportedSelectionAreNotDefaulted() {
        val id = "brand-B||user-B"
        for (renderer in listOf(item("brand-B", selected = false), item("brand-B", disabled = true),
            item("brand-B", hasChannel = false), buildJsonObject { put("isSelected", true) })) {
            assertUnverified(response(id, listOf(renderer)), id)
        }
        val unsupported = buildJsonObject {
            put("isSelected", true)
            putJsonObject("serviceEndpoint") { putJsonObject("selectActiveIdentityEndpoint") { put("unsupportedSlot", 1) } }
        }
        assertUnverified(response(id, listOf(unsupported)), id)
    }

    @Test
    fun personalSelectedContextUsesExplicitAccountIdWhenNotDelegated() {
        val renderer = buildJsonObject {
            put("isSelected", true)
            putJsonObject("serviceEndpoint") { putJsonObject("selectActiveIdentityEndpoint") { put("obfuscatedSelectedGaiaId", "user-B") } }
        }
        verifySelectedYouTubeContext(response("user-B", listOf(renderer)), "user-B")
        assertUnverified(response("user-C", listOf(renderer)), "user-C")
    }

    @Test
    fun finalPublicAccountMenuIdentityIsReadUnderUnchangedSelectedCandidateLease() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        val client = Client()
        val mutex = Mutex()
        val endpointOrder = mutableListOf<String>()
        f.validate = { candidate ->
            withPrivateYouTubeCandidate(client, mutex, candidate, Client::apply) {
                validateYouTubeCandidateContext(candidate, accountChannels = {
                    endpointOrder += "accountChannels"
                    assertEquals(b, it.auth)
                    f.assertAccount("A")
                    response(b.dataSyncId, listOf(item("default-A", selected = false), item("brand-B")))
                }, accountMenu = {
                    endpointOrder += "accountMenu"
                    assertEquals(b, it.auth)
                    f.assertAccount("A")
                    menu("Final selected B")
                })
            }
        }
        val session = f.repository.completeLogin(f.repository.beginAttempt(), b.cookie.orEmpty(), b.visitorData, b.dataSyncId).getOrThrow()
        assertEquals(listOf("accountChannels", "accountMenu"), endpointOrder)
        assertEquals(b.dataSyncId, session.authState.dataSyncId)
        assertEquals(b.cookie, f.runtime.cookie)
        assertEquals("Final selected B", session.accountName)
        assertEquals("", session.accountEmail)
        assertEquals("", session.accountChannelHandle)
        assertEquals(listOf(b, PlaybackAuthState.EMPTY), client.applied)
        assertEquals(1, f.store.transactions)
    }

    @Test
    fun mismatchedContextNeverRequestsDefaultMenuOrCommitsSubstituteIdentity() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        var menuCalls = 0
        f.validate = { candidate ->
            validateYouTubeCandidateContext(candidate,
                { response("different-default", listOf(item("different-default"))) },
                { menuCalls++; menu("Same display name") })
        }
        assertTrue(f.login("B").exceptionOrNull() is UnverifiedYouTubeContextException)
        assertEquals(0, menuCalls)
        assertEquals(0, f.store.transactions)
        f.assertAccount("A")
    }

    @Test
    fun missingAmbiguousOrNamelessFinalMenuFailsWithoutCommittingCandidate() = runTest {
        for (finalMenu in listOf(menu("Synthetic B", 0), menu("Synthetic B", 2), menu(null))) {
            val f = AuthFixture(StandardTestDispatcher(testScheduler))
            f.validate = { candidate ->
                validateYouTubeCandidateContext(candidate, { response("B", listOf(item("B"))) }, { finalMenu })
            }
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

    private fun assertUnverified(response: JsonElement, candidate: String) {
        try { verifySelectedYouTubeContext(response, candidate); fail("unsupported or mismatched selection must fail") }
        catch (_: UnverifiedYouTubeContextException) { }
    }

    private fun response(id: String?, items: List<JsonObject>): JsonObject = buildJsonObject {
        putJsonObject("responseContext") { putJsonObject("mainAppWebResponseContext") { id?.let { put("dataSyncId", it) } } }
        putJsonArray("actions") { items.forEach { renderer -> add(buildJsonObject { put("accountItemRenderer", renderer) }) } }
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

    private fun item(id: String, selected: Boolean = true, disabled: Boolean = false, hasChannel: Boolean = true,
        name: String = "Synthetic account", extraId: String? = null): JsonObject = buildJsonObject {
        put("isSelected", selected)
        put("isDisabled", disabled)
        put("hasChannel", hasChannel)
        putJsonObject("accountName") { put("simpleText", name) }
        putJsonObject("serviceEndpoint") {
            putJsonObject("selectActiveIdentityEndpoint") {
                put("onBehalfOfUser", id)
                extraId?.let { put("delegatedSessionId", it) }
            }
        }
    }
}
