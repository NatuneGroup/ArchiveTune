/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Covers the whole refresh or account mutation, including suspended persistence and cache reset. */
internal class StreamingAccountMutationLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
