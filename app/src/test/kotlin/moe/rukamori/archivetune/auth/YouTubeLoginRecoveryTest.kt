/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.utils.expireYouTubeWebCookies
import moe.rukamori.archivetune.viewmodels.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeLoginRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val views = mutableListOf<LoginViewModel>()
    @Before fun installMain() { Dispatchers.setMain(dispatcher) }
    @After fun releaseMain() {
        views.forEach { it.release(); it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }
    private fun view(f: AuthFixture) = LoginViewModel(f.repository).also { views += it; it.open() }

    @Test
    fun mainFrameHttpNetworkSchemeAndRendererFailuresRemainDistinct() = runTest(dispatcher) {
        for (error in listOf(LoginError.Http, LoginError.Network, LoginError.UnsupportedNavigation, LoginError.RendererLost,
            LoginError.RedirectLoop, LoginError.LoginFailed, LoginError.MissingDataSyncId)) {
            val f = AuthFixture(dispatcher)
            val vm = view(f)
            runCurrent()
            val revision = vm.webRevision.value
            vm.onPageReady(revision)
            assertEquals(LoginScreenState.Empty, vm.screenState.value)
            vm.onError(revision, error)
            vm.onPageReady(revision)
            vm.onPageLoading(revision)
            assertEquals(LoginScreenState.Error(error), vm.screenState.value)
            f.assertAccount("A")
            vm.release()
        }
        assertEquals(LoginError.Http, YouTubeLoginWebEvents.httpError(true, 404))
        assertEquals(LoginError.Network, YouTubeLoginWebEvents.networkError(true, false))
        assertEquals(LoginError.UnsupportedNavigation, YouTubeLoginWebEvents.networkError(true, true))
    }

    @Test
    fun subresource404AndNetworkErrorsAreNotLoginFailures() = runTest(dispatcher) {
        val vm = view(AuthFixture(dispatcher))
        runCurrent()
        vm.onPageReady(vm.webRevision.value)
        listOfNotNull(YouTubeLoginWebEvents.httpError(false, 404),
            YouTubeLoginWebEvents.networkError(false, false), YouTubeLoginWebEvents.networkError(false, true),
            YouTubeLoginWebEvents.httpError(true, 200)).forEach { vm.onError(vm.webRevision.value, it) }
        assertEquals(LoginScreenState.Empty, vm.screenState.value)
    }

    @Test
    fun rendererRetryOwnsNewViewAndRejectsOldPageAndDocumentEvents() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        val vm = view(f)
        runCurrent()
        val lost = vm.webRevision.value
        vm.onError(lost, LoginError.RendererLost)
        val jarBefore = f.store.values.value
        vm.retry()
        runCurrent()
        assertTrue(vm.webRevision.value > lost)
        assertFalse(vm.ownsView(lost))
        vm.onError(lost, LoginError.Http)
        vm.onContextExtracted(lost, "SAPISID=synthetic-B", "visitor-B", "B")
        vm.onExtractionExhausted(lost)
        assertEquals(LoginScreenState.Loading, vm.screenState.value)
        assertEquals(jarBefore, f.store.values.value)
        assertEquals(0, f.store.transactions)
    }

    @Test
    fun cancelInvalidatesValidationAndLateExtractionWithoutClearingSession() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        var cleaned = false
        f.validate = { try { awaitCancellation() } finally { cleaned = true } }
        val vm = view(f)
        runCurrent()
        val revision = vm.webRevision.value
        vm.onContextExtracted(revision, "SAPISID=synthetic-B", "visitor-B", "B")
        runCurrent()
        vm.release()
        runCurrent()
        assertTrue(cleaned)
        assertFalse(vm.ownsView(revision))
        vm.onContextExtracted(revision, "SAPISID=synthetic-C", "visitor-C", "C")
        vm.retry()
        runCurrent()
        assertFalse(vm.consumeSuccess())
        assertEquals(0, f.store.transactions)
        f.assertAccount("A")
    }

    @Test
    fun extractionExhaustionWithoutAnyJavascriptResultIsVisibleMissingContext() = runTest(dispatcher) {
        val vm = view(AuthFixture(dispatcher))
        runCurrent()
        vm.onExtractionExhausted(vm.webRevision.value)
        assertEquals(LoginScreenState.Error(LoginError.MissingDataSyncId), vm.screenState.value)
    }

    @Test
    fun obsoleteCompletedSessionCannotDeliverSuccessAfterLogout() = runTest(dispatcher) {
        val f = AuthFixture(dispatcher)
        val vm = view(f)
        runCurrent()
        vm.onContextExtracted(vm.webRevision.value, "SAPISID=synthetic-B", "visitor-B", "B")
        runCurrent()
        assertTrue(vm.screenState.value is LoginScreenState.Success)
        f.repository.logout()
        assertFalse(vm.consumeSuccess())
        assertEquals(LoginScreenState.Error(LoginError.LoginFailed), vm.screenState.value)
    }

    @Test
    fun documentEvaluationAndReleaseRejectDelayedCallbacks() {
        val callbacks = YouTubeLoginDocumentCallbacks()
        callbacks.pageStarted()
        val page = callbacks.document
        val first = callbacks.nextEvaluation()
        assertTrue(callbacks.accepts(page, first))
        val second = callbacks.nextEvaluation()
        assertFalse(callbacks.accepts(page, first))
        assertTrue(callbacks.accepts(page, second))
        callbacks.invalidateEvaluation()
        assertFalse(callbacks.accepts(page, second))
        val third = callbacks.nextEvaluation()
        callbacks.pageStarted()
        assertFalse(callbacks.accepts(page, third))
        val finalPage = callbacks.document
        val finalEvaluation = callbacks.nextEvaluation()
        callbacks.release()
        assertFalse(callbacks.accepts(finalPage, finalEvaluation))
        assertFalse(callbacks.accepts(finalPage, callbacks.nextEvaluation()))
    }

    @Test
    fun scopedLogoutCleanerExpiresOnlyYoutubeCookiesAndPreservesUnrelatedJar() {
        val jar = mutableMapOf(
            "https://music.youtube.com" to "SAPISID=synthetic; SID=synthetic",
            "https://www.youtube.com" to "__Secure-3PAPISID=synthetic",
            "https://youtube.com" to "SID=synthetic",
            "https://accounts.google.com" to "SID=synthetic-google",
            "https://accounts.spotify.com" to "SID=synthetic-spotify",
        )
        val untouched = jar.filterKeys { !it.endsWith("youtube.com") }
        val writes = mutableListOf<Pair<String, String>>()
        var flushes = 0
        expireYouTubeWebCookies({ jar[it] }, { origin, cookie ->
            writes += origin to cookie
            val name = cookie.substringBefore('=')
            jar[origin] = jar[origin].orEmpty().split(';')
                .filterNot { it.substringBefore('=').trim() == name }.joinToString(";")
        }, { flushes++ })
        assertEquals(12, writes.size)
        assertEquals(1, flushes)
        assertEquals(setOf("https://music.youtube.com", "https://www.youtube.com", "https://youtube.com"), writes.map { it.first }.toSet())
        assertTrue(writes.all { it.second.contains("Max-Age=0; Path=/; Secure") })
        assertTrue(writes.none { it.first.contains("google.com") || it.first.contains("spotify.com") })
        assertEquals(untouched, jar.filterKeys { !it.endsWith("youtube.com") })
        assertTrue(jar.filterKeys { it.endsWith("youtube.com") }.values.all { it.isEmpty() })
        assertTrue(writes.any { it.second.endsWith("Domain=.youtube.com") })
    }
}
