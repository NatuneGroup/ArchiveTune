/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.utils.YouTubePlaybackAuthRepair
import moe.rukamori.archivetune.utils.potoken.PoTokenResult
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlaybackAuthRepairTest {
    @Test
    fun suspendedAccountChannelsRepairKeepsOriginalOriginThroughNestedVisitorAndTokensAfterIdenticalRelogin() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        f.repository.reconcilePersisted()
        val operation = YouTubePlaybackAuthRepair(f.repository)
        val initial = f.runtime
        val accountChannels = CompletableDeferred<Unit>()
        val steps = mutableListOf<String>()
        val work = async {
            operation.afterBotDetection(initial,
                refreshAccountContext = { state ->
                    steps += "accountChannels"
                    accountChannels.await()
                    state
                },
                refreshVisitor = { state, nested ->
                    steps += "visitor"
                    assertSame(operation, nested)
                    // Unchanged visitor keeps the real token writer's same-session check eligible.
                    nested.refreshVisitor(state) { initial.visitorData }
                },
                mintTokens = { state, nested ->
                    steps += "tokens"
                    assertSame(operation, nested)
                    nested.withGeneratedPoTokens(state, "synthetic-video",
                        PoTokenResult("synthetic-old-player", "synthetic-old-session"))
                },
                hasGvsPoToken = { !it.poTokenGvs.isNullOrBlank() },
                clearCaches = {},
            )
        }
        runCurrent()
        assertEquals(listOf("accountChannels"), steps)
        f.login("A").getOrThrow()
        val replacement = f.repository.origin()
        assertEquals(initial, replacement.authState)
        assertNotEquals(operation.origin.revision, replacement.revision)
        val persisted = f.store.values.value
        val writes = f.store.transactions
        f.publications.clear()
        accountChannels.complete(Unit)
        val localResult = work.await()
        assertEquals(listOf("accountChannels", "visitor", "tokens"), steps)
        assertEquals("synthetic-old-player", localResult.poTokenGvs)
        assertEquals(initial, f.runtime)
        assertEquals(persisted, f.store.values.value)
        assertEquals(writes, f.store.transactions)
        assertTrue(f.publications.isEmpty())
        assertTrue(f.repository.isCurrent(replacement))
    }

    @Test
    fun currentRepairStillRunsVisitorAndTokenWritersWithTheSameOperation() = runTest {
        val f = AuthFixture(StandardTestDispatcher(testScheduler))
        f.repository.reconcilePersisted()
        val operation = YouTubePlaybackAuthRepair(f.repository)
        val persisted = f.store.values.value
        f.publications.clear()
        var clears = 0
        val result = operation.afterBotDetection(f.runtime,
            refreshAccountContext = { it },
            refreshVisitor = { state, nested ->
                assertSame(operation, nested)
                nested.refreshVisitor(state) { state.visitorData }
            },
            mintTokens = { state, nested ->
                assertSame(operation, nested)
                nested.withGeneratedPoTokens(state, "synthetic-video",
                    PoTokenResult("synthetic-current-player", "synthetic-current-session"))
            },
            hasGvsPoToken = { !it.poTokenGvs.isNullOrBlank() },
            clearCaches = { clears++ },
        )
        assertEquals(result, f.runtime)
        assertEquals("synthetic-current-player", f.runtime.poTokenPlayer)
        assertEquals("synthetic-current-session", f.runtime.poTokenGvsSession)
        assertEquals("synthetic-video", f.runtime.poTokenSubsVideoId)
        assertEquals(3, f.publications.size) // Visitor, generated tokens, then outer repair.
        assertEquals(1, clears)
        assertEquals(persisted, f.store.values.value)
        assertEquals(0, f.store.transactions)
    }
}
